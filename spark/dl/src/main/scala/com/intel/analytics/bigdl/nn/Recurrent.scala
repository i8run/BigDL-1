/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.bigdl.nn


import com.intel.analytics.bigdl._
import com.intel.analytics.bigdl.nn.abstractnn.{AbstractModule, Activity, TensorModule}
import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.bigdl.utils.serializer.{ContainerSerializable, DataConverter, ModuleData, ModuleSerializer}
import com.intel.analytics.bigdl.utils.{T, Table}
import serialization.Bigdl.{AttrValue, BigDLModule}

import scala.reflect.runtime.universe
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

/**
 * [[Recurrent]] module is a container of rnn cells
 * Different types of rnn cells can be added using add() function
 */
class Recurrent[T : ClassTag](var batchNormParams: BatchNormParams[T] = null)
  (implicit ev: TensorNumeric[T]) extends Container[Tensor[T], Tensor[T], T] {

  private var hidden: Activity = null
  private var gradHidden: Activity = null
  private var hiddenShape: Array[Int] = null
  private val currentInput = T()
  private val currentGradOutput = T()
  private val gradInputCell = Tensor[T]()
  private var outputCell = Tensor[T]()
  private val _input = T()
  private val batchDim = Recurrent.batchDim
  private val timeDim = Recurrent.timeDim
  private val inputDim = 1
  private val hidDim = 2
  private var (batchSize, times) = (0, 0)
  private var topology: Cell[T] = null
  private val outputBuffer = Tensor[T]()
  private val gradBuffer = Tensor[T]()
  private var preTopology: AbstractModule[Activity, Activity, T] = null
  private val dropouts: ArrayBuffer[Array[Dropout[T]]] =
    new ArrayBuffer[Array[Dropout[T]]]
  private val timeBuffer =
    new ArrayBuffer[(AbstractModule[_ <: Activity, _ <: Activity, T], Long, Long)]
  private var layer: TensorModule[T] = null

  /**
   *
   *  modules: -- preTopology
   *           |- BatchNormalization (optional)
   *           |- topology (cell)
   *
   * The topology (or cell) will be cloned for N times w.r.t the time dimension.
   * The preTopology will be execute only once before the recurrence.
   *
   * @param module module to be add
   * @return this container
   */
  override def add(module: AbstractModule[_ <: Activity, _ <: Activity, T]): Recurrent.this.type = {
    require(module.isInstanceOf[Cell[T]],
      "Recurrent: contained module should be Cell type")

    topology = module.asInstanceOf[Cell[T]]
    preTopology = topology.preTopology

    if (batchNormParams != null && preTopology == null) {
      throw new IllegalArgumentException(
        s"${topology.getName} does not support BatchNormalization." +
          s" Please add preTopology for it. You can simply using: " +
          s"override def preTopology: AbstractModule[Activity, Activity, T] = Identity()")
    }

    if (batchNormParams != null) {
      layer = batchNormalization(batchNormParams)
      preTopology = Sequential[T]().add(preTopology).add(layer)
    }

    if (preTopology != null) {
      modules += preTopology
    }
    modules += topology
    this
  }

  private def batchNormalization(batchNormParams: BatchNormParams[T]) = {
    TimeDistributed[T](BatchNormalization[T](
      nOutput = topology.hiddenSizeOfPreTopo,
      batchNormParams.eps,
      batchNormParams.momentum,
      affine = batchNormParams.affine,
      batchNormParams.initWeight,
      batchNormParams.initBias,
      batchNormParams.initGradWeight,
      batchNormParams.initGradBias))
  }

  // list of cell modules cloned from added modules
  private val cells: ArrayBuffer[Cell[T]]
  = ArrayBuffer[Cell[T]]()

  // when the currentTimes less than input times, we should do share again.
  private var currentTimes = 0

  /**
   * Clone N models; N depends on the time dimension of the input
   * @param sizes, the first element is batchSize, the second is times, the third is hiddensize
    *             the left is size of images
   */
  private def extend(sizes: Array[Int]): Unit = {
    val times = sizes(timeDim - 1)
    val batchSize = sizes(batchDim - 1)
    val imageSize = sizes.drop(3)
    if (hidden == null) {
      require((preTopology == null && modules.length == 1) ||
        (topology != null && preTopology != null && modules.length == 2),
        "Recurrent extend: should contain only one cell or plus a pre-topology" +
          " to process input")

      cells.clear()
      cells += topology
      val cell = cells.head

      // The cell will help initialize or resize the hidden variable.
      hidden = cell.hidResize(hidden = null, batchSize = batchSize, imageSize)

      /*
       * Since the gradHidden is only used as an empty Tensor or Table during
       * backward operations. We can reuse the hidden variable by pointing the
       * gradHidden to it.
       */
      gradHidden = hidden
    } else {
      cells.head.hidResize(hidden = hidden, batchSize = batchSize, imageSize)
      gradHidden = hidden
    }
    var t = cells.length
    if (t < times) {
      val cloneCell = cells.head.cloneModule()
      cloneCell.parameters()._1.map(_.set())
      cloneCell.parameters()._2.map(_.set())
      while (t < times) {
        cells += cloneCell.cloneModule()
          .asInstanceOf[Cell[T]]
        t += 1
      }
    }
  }

  /**
   * Sharing weights, bias, gradWeights across all the cells in time dim
   * @param cells
   */
  def share(cells: ArrayBuffer[Cell[T]]): Unit = {
    val params = cells.head.parameters()
    cells.foreach(c => {
      if (!c.parameters().eq(params)) {
        var i = 0
        while (i < c.parameters()._1.length) {
          c.parameters()._1(i).set(params._1(i))
          i += 1
        }
        i = 0
        while (i < c.parameters()._2.length) {
          c.parameters()._2(i).set(params._2(i))
          i += 1
        }

        dropouts.append(findDropouts(c))
      }
    })

    val stepLength = dropouts.length
    for (i <- dropouts.head.indices) {
      val head = dropouts.head(i)
      val noise = head.noise
      for (j <- 1 until stepLength) {
        val current = dropouts(j)(i)
        current.noise = noise
        current.isResampling = false
      }
    }
  }

  def findDropouts(cell: Cell[T]): Array[Dropout[T]] = {
    var result: Array[Dropout[T]] = null
    cell.cell match {
      case container: Container[_, _, T] =>
        result = container
          .findModules("Dropout")
          .toArray
          .map(_.asInstanceOf[Dropout[T]])
      case _ =>
    }

    result
  }

  private def quantizeOptim(): Unit = {
    currentInput(hidDim) = if (initState != null) initState
    else hidden

    currentInput(inputDim) = outputCell.select(timeDim, 1)
    cells.head.forward(currentInput)
  }

  override def updateOutput(input: Tensor[T]): Tensor[T] = {
    require(input.dim == 3 || input.dim == 5 || input.dim == 6,
      "Recurrent: input should be a 3D/5D/6D Tensor, e.g [batch, times, nDim], " +
        s"current input.dim = ${input.dim}")

    batchSize = input.size(batchDim)
    times = input.size(timeDim)

    outputCell = if (preTopology != null) {
      preTopology.forward(input).toTensor[T]
    } else {
      input
    }

    val hiddenSize = topology.hiddensShape(0)
    val outputSize = input.size()
    outputSize(2) = hiddenSize
    output.resize(outputSize)
    // Clone N modules along the sequence dimension.
    extend(outputSize)

    /**
     * for quantization, we need do forward first for allocating the weight memory
     */
    quantizeOptim()

    if (times > currentTimes) {
      currentTimes = times
      share(cells)
    }


    /**
     * currentInput forms a T() type. It contains two elements, hidden and input.
     * Each time it will feed the cell with T(hidden, input) (or T(input, hidden) depends on
     * your hidDim and inputDim), and the cell will give a table output containing two
     * identical elements T(output, output). One of the elements from the cell output is
     * the updated hidden. Thus the currentInput will update its hidden element with this output.
     */
    var i = 1
    // init state
    currentInput(hidDim) = if (initState != null) initState
     else hidden
    while (i <= times) {
      currentInput(inputDim) = Recurrent.selectCopy(outputCell, i, outputBuffer)
      cells(i - 1).forward(currentInput)
      currentInput(hidDim) = cells(i - 1).output.toTable(hidDim)
      i += 1
    }

    Recurrent.copy(cells.map(x => x.output.toTable[Tensor[T]](inputDim)), output)
    output
  }

  def getState(): Activity = {
    require(cells != null && cells(times - 1).output != null,
      "getState need to be called after updateOutput")
    cells(times - 1).output.toTable(hidDim)
  }

  private var initState: Activity = null
  def setState(state: Activity): Unit = {
    initState = state
  }

  override def accGradParameters(input: Tensor[T], gradOutput: Tensor[T]): Unit = {
    currentGradOutput(hidDim) = gradHidden
    /**
     * Since we clone module along the time dimension, the output of each
     * iteration have been recorded by the cloned modules. Thus, we can
     * reuse these outputs during the backward operations by copying the
     * outputs to _input variable.
     *
     * The output of Cell(i-1) should be one of the elements fed to the inputs
     * of Cell(i)
     * The first module in the cells array accepts zero hidden parameter.
     */

    var i = times
    while (i >= 1) {
      currentGradOutput(inputDim) = Recurrent.selectCopy(gradOutput, i, gradBuffer)
      _input(hidDim) = if (i > 1) cells(i - 2).output.toTable(hidDim)
        else hidden
      _input(inputDim) = Recurrent.selectCopy(outputCell, i, outputBuffer)
      if (i == 1) {
        cells(i - 1).regluarized(true)
      } else {
        cells(i - 1).regluarized(false)
      }
      cells(i - 1).accGradParameters(_input, currentGradOutput)
      currentGradOutput(hidDim) = cells(i - 1).gradInput.toTable(hidDim)
      i -= 1
    }
    if (preTopology != null) {
      preTopology.accGradParameters(input, gradInputCell)
    }
  }

  override def updateGradInput(input: Tensor[T], gradOutput: Tensor[T]): Tensor[T] = {

    gradInput = if (preTopology != null) {
      /**
       * if preTopology is Sequential, it has not created gradInput.
       * Thus, it needs to create a new Tensor.
       */
      if (preTopology.gradInput == null) {
        preTopology.gradInput = Tensor[T]()
      }
      preTopology.gradInput.toTensor[T]
    } else {
      gradInputCell
    }
    gradInputCell.resizeAs(outputCell)
    currentGradOutput(hidDim) = gradHidden
    var i = times
    while (i >= 1) {
      currentGradOutput(inputDim) = Recurrent.selectCopy(gradOutput, i, gradBuffer)
      _input(hidDim) = if (i > 1) cells(i - 2).output.toTable(hidDim)
        else hidden
      _input(inputDim) = Recurrent.selectCopy(outputCell, i, outputBuffer)
      cells(i - 1).updateGradInput(_input, currentGradOutput)
      currentGradOutput(hidDim) = cells(i - 1).gradInput.toTable(hidDim)
      i -= 1
    }
    Recurrent.copy(cells.map(x => x.gradInput.toTable[Tensor[T]](inputDim)), gradInputCell)
    if (preTopology != null) {
      gradInput = preTopology.updateGradInput(input, gradInputCell).toTensor[T]
    }
    gradInput
  }

  override def backward(input: Tensor[T], gradOutput: Tensor[T]): Tensor[T] = {
    val st = System.nanoTime
    currentGradOutput(hidDim) = gradHidden
    var i = times

    while (i >= 1) {
      currentGradOutput(inputDim) = Recurrent.selectCopy(gradOutput, i, gradBuffer)
      _input(hidDim) = if (i > 1) cells(i - 2).output.toTable(hidDim)
      else hidden
      _input(inputDim) = Recurrent.selectCopy(outputCell, i, outputBuffer)
      if (i == 1) {
        cells(i - 1).regluarized(true)
      } else {
        cells(i - 1).regluarized(false)
      }
      cells(i - 1).backward(_input, currentGradOutput)
      currentGradOutput(hidDim) = cells(i - 1).gradInput.toTable(hidDim)
      i -= 1
    }

    gradInput = if (preTopology != null) {
      /**
       * if preTopology is Sequential, it has not created gradInput.
       * Thus, it needs to create a new Tensor.
       */
      if (preTopology.gradInput == null) {
        preTopology.gradInput = Tensor[T]()
      }
      preTopology.gradInput.toTensor[T]
    } else {
      gradInputCell
    }
    gradInputCell.resizeAs(outputCell)
    Recurrent.copy(cells.map(x => x.gradInput.toTable[Tensor[T]](inputDim)), gradInputCell)

    if (preTopology != null) {
      gradInput = preTopology.backward(input, gradInputCell).toTensor[T]
    }

    this.backwardTime = System.nanoTime - st
    gradInput
  }

  private def appendTimes(module: Module[T]): Unit = {
    if (module != null) {
      module.getTimes.foreach(x => {
        timeBuffer.append(x)
      })
    }
  }

  private def bufferTime(): (Long, Long) = {
    var forwardSum = 0L
    var backwardSum = 0L
    timeBuffer.foreach(x => {
      forwardSum += x._2
      backwardSum += x._3
    })
    (forwardSum, backwardSum)
  }

  override def getTimes():
  Array[(AbstractModule[_ <: Activity, _ <: Activity, T], Long, Long)] = {
    timeBuffer.clear

    val head = if (!cells.isEmpty) {
      cells.head
    } else null

    var i = 1
    while (i < times) {
      head.addTimes(cells(i))
      i += 1
    }

    appendTimes(preTopology)
    appendTimes(head)

    val (bufferForward, bufferBackward) = bufferTime()
    timeBuffer.append(
      (this,
        this.forwardTime - bufferForward,
        this.backwardTime - bufferBackward))
    timeBuffer.toArray
  }

  override def resetTimes(): Unit = {
    if (preTopology != null) {
      preTopology.resetTimes
    }
    var i = 0
    while (i < times) {
      cells(i).resetTimes
      i += 1
    }
    this.forwardTime = 0
    this.backwardTime = 0
  }

  override def clearState() : this.type = {
    super.clearState()
    hidden = null
    gradHidden = null
    hiddenShape = null
    gradInputCell.set()
    outputCell.set()
    currentInput.clear()
    currentGradOutput.clear()
    _input.clear()
    cells.foreach(x => x.clearState())
    cells.clear()
    timeBuffer.clear()
    initState = null
    outputBuffer.set()
    gradBuffer.set()
    this
  }

  override def reset(): Unit = {
    require((preTopology == null && modules.length == 1) ||
      (topology != null && preTopology != null && modules.length == 2),
      "Recurrent extend: should contain only one cell or plus a pre-topology" +
        " to process input.")
    require(topology.isInstanceOf[Cell[T]],
      "Recurrent: should contain module with Cell type")

    modules.foreach(_.reset())
    cells.clear()
  }

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Recurrent[T]]

  override def equals(other: Any): Boolean = other match {
    case that: Recurrent[T] =>
      super.equals(that) &&
        (that canEqual this) &&
        cells == that.cells
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(super.hashCode(), cells)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }

  override def toString(): String = s"${getPrintName}${modules}"
}

object Recurrent extends ContainerSerializable {

  private val batchDim = 1
  private val timeDim = 2

  def apply[@specialized(Float, Double) T: ClassTag](
    batchNormParams: BatchNormParams[T] = null)
    (implicit ev: TensorNumeric[T]) : Recurrent[T] = {
    new Recurrent[T](batchNormParams)
  }

  /**
   * set the cells' output and gradInput to recurrent's output and gradInput
   * to decrease the copy expense.
   * Copy src tensor to dst tensor along timeDime, default timeDime 2, batchDim 1
   * @param src
   * @param dst
   */
  private[bigdl] def copy[@specialized(Float, Double) T: ClassTag](
    src: ArrayBuffer[Tensor[T]], dst: Tensor[T]): Unit = {
    val timeSize = dst.size(timeDim)
    var t = 1
    while (t <= timeSize) {
      copyToIndex(src(t -1), dst, t)
      t += 1
    }
  }

  /**
   * select srcIndex subset of the 2-th dimension from src, and copy to dst
   * @param src
   * @param srcIndex the index of 2-th dimension from src
   * @param dst
   */
  private[bigdl] def selectCopy[@specialized(Float, Double) T: ClassTag](
    src: Tensor[T], srcIndex: Int, dst: Tensor[T]): Tensor[T] = {
    if (src.isContiguous() && dst.isContiguous()) {
      if ((dst.nElement() == 0) || (dst.nElement() != (src.nElement() / src.size(2)))) {
        dst.resizeAs(src.select(2, srcIndex))
      }

      val batchSize = src.size(batchDim)
      val timeSize = src.size(timeDim)
      val stepSize = src.nElement() / (batchSize * timeSize)

      val srcArr = src.storage().array()
      var srcOffset = src.storageOffset() - 1
      val dstArr = dst.storage().array()
      var dstOffset = dst.storageOffset() - 1

      val recordSize = timeSize * stepSize
      val indexSize = (srcIndex-1) * stepSize

      var b = 0
      while (b < batchSize) {
        System.arraycopy(srcArr, srcOffset + indexSize, dstArr, dstOffset, stepSize)
        srcOffset += recordSize
        dstOffset += stepSize
        b += 1
      }
    } else {
      val output = src.select(2, srcIndex)
      dst.resizeAs(output).copy(output)
    }
    dst
  }

  /**
   * copy src to be dst dstIndex subset of the 2-th dimension
   * @param src
   * @param dst
   * @param dstIndex the index of 2-th dimension from dst
   */
  private[bigdl] def copyToIndex[@specialized(Float, Double) T: ClassTag](
    src: Tensor[T], dst: Tensor[T], dstIndex: Int): Tensor[T] = {
    if (src.isContiguous() && dst.isContiguous()) {
      val batchSize = dst.size(batchDim)
      val timeSize = dst.size(timeDim)
      val stepSize = dst.nElement() / (batchSize * timeSize)

      val dstArr = dst.storage().array()
      var dstOffset = dst.storageOffset() - 1
      val srcArr = src.storage().array()
      var srcOffset = src.storageOffset() - 1

      val recordSize = timeSize * stepSize
      val indexSize = (dstIndex - 1) * stepSize

      var b = 0
      while (b < batchSize) {
        System.arraycopy(srcArr, srcOffset, dstArr, dstOffset + indexSize, stepSize)
        srcOffset += stepSize
        dstOffset += recordSize
        b += 1
      }
    } else {
      dst.select(2, dstIndex).copy(src)
    }
    dst
  }

  override def doLoadModule[T: ClassTag](model : BigDLModule)
    (implicit ev: TensorNumeric[T]) : AbstractModule[Activity, Activity, T] = {

    val attrMap = model.getAttrMap

    val flag = DataConverter
      .getAttributeValue(attrMap.get("bnorm"))
      .asInstanceOf[Boolean]
    val recurrent = if (flag) {
      Recurrent[T](BatchNormParams[T]())
    } else {
      Recurrent[T]()
    }

    val topologyAttr = attrMap.get("topology")
    recurrent.topology = DataConverter.getAttributeValue(topologyAttr).
      asInstanceOf[Cell[T]]

    val preTopologyAttr = attrMap.get("preTopology")
    recurrent.preTopology = DataConverter.getAttributeValue(preTopologyAttr).
      asInstanceOf[AbstractModule[Activity, Activity, T]]

    if (recurrent.preTopology != null) {
      recurrent.modules.append(recurrent.preTopology)
    }
    recurrent.modules.append(recurrent.topology)

    if (flag) {
      val bnormEpsAttr = attrMap.get("bnormEps")
      recurrent.batchNormParams.eps =
        DataConverter.getAttributeValue(bnormEpsAttr)
          .asInstanceOf[Double]

      val bnormMomentumAttr = attrMap.get("bnormMomentum")
      recurrent.batchNormParams.momentum =
        DataConverter.getAttributeValue(bnormMomentumAttr)
          .asInstanceOf[Double]

      val bnormInitWeightAttr = attrMap.get("bnormInitWeight")
      recurrent.batchNormParams.initWeight =
        DataConverter.getAttributeValue(bnormInitWeightAttr)
          .asInstanceOf[Tensor[T]]

      val bnormInitBiasAttr = attrMap.get("bnormInitBias")
      recurrent.batchNormParams.initBias =
        DataConverter.getAttributeValue(bnormInitBiasAttr)
          .asInstanceOf[Tensor[T]]

      val bnormInitGradWeightAttr = attrMap.get("bnormInitGradWeight")
      recurrent.batchNormParams.initGradWeight =
        DataConverter.getAttributeValue(bnormInitGradWeightAttr)
          .asInstanceOf[Tensor[T]]

      val bnormInitGradBiasAttr = attrMap.get("bnormInitGradBias")
      recurrent.batchNormParams.initGradBias =
        DataConverter.getAttributeValue(bnormInitGradBiasAttr)
          .asInstanceOf[Tensor[T]]

      val bnormAffineAttr = attrMap.get("bnormAffine")
      recurrent.batchNormParams.affine =
        DataConverter.getAttributeValue(bnormAffineAttr)
        .asInstanceOf[Boolean]
    }

    createBigDLModule(model, recurrent)
    recurrent
  }

  override def doSerializeModule[T: ClassTag](module : ModuleData[T],
                                            recurrentBuilder : BigDLModule.Builder)
                                           (implicit ev: TensorNumeric[T]) : Unit = {

    val recurrent = module.module.asInstanceOf[Recurrent[T]]

    val topologyBuilder = AttrValue.newBuilder
    DataConverter.setAttributeValue(topologyBuilder, recurrent.topology,
      ModuleSerializer.abstractModuleType)
    recurrentBuilder.putAttr("topology", topologyBuilder.build)

    val preTopologyBuilder = AttrValue.newBuilder
    DataConverter.setAttributeValue(preTopologyBuilder,
      recurrent.preTopology, ModuleSerializer.abstractModuleType)
    recurrentBuilder.putAttr("preTopology", preTopologyBuilder.build)

    val flag = if (recurrent.batchNormParams != null) {

      val bnormEpsBuilder = AttrValue.newBuilder
      DataConverter.setAttributeValue(bnormEpsBuilder,
        recurrent.batchNormParams.eps, universe.typeOf[Double])
      recurrentBuilder.putAttr("bnormEps", bnormEpsBuilder.build)

      val bnormMomentumBuilder = AttrValue.newBuilder
      DataConverter.setAttributeValue(bnormMomentumBuilder,
        recurrent.batchNormParams.momentum, universe.typeOf[Double])
      recurrentBuilder.putAttr("bnormMomentum", bnormMomentumBuilder.build)

      val bnormInitWeightBuilder = AttrValue.newBuilder
      DataConverter.setAttributeValue(bnormInitWeightBuilder,
        recurrent.batchNormParams.initWeight, ModuleSerializer.tensorType)
      recurrentBuilder.putAttr("bnormInitWeight", bnormInitWeightBuilder.build)

      val bnormInitBiasBuilder = AttrValue.newBuilder
      DataConverter.setAttributeValue(bnormInitBiasBuilder,
        recurrent.batchNormParams.initBias, ModuleSerializer.tensorType)
      recurrentBuilder.putAttr("bnormInitBias", bnormInitBiasBuilder.build)

      val bnormInitGradWeightBuilder = AttrValue.newBuilder
      DataConverter.setAttributeValue(bnormInitGradWeightBuilder,
        recurrent.batchNormParams.initGradWeight, ModuleSerializer.tensorType)
      recurrentBuilder.putAttr("bnormInitGradWeight", bnormInitGradWeightBuilder.build)

      val bnormInitGradBiasBuilder = AttrValue.newBuilder
      DataConverter.setAttributeValue(bnormInitGradBiasBuilder,
        recurrent.batchNormParams.initGradBias, ModuleSerializer.tensorType)
      recurrentBuilder.putAttr("bnormInitGradBias", bnormInitGradBiasBuilder.build)

      val bnormAffineBuilder = AttrValue.newBuilder
      DataConverter.setAttributeValue(bnormAffineBuilder,
        recurrent.batchNormParams.affine, universe.typeOf[Boolean])
      recurrentBuilder.putAttr("bnormAffine", bnormAffineBuilder.build)

      true
    } else {
      false
    }

    val bNormBuilder = AttrValue.newBuilder
    DataConverter.setAttributeValue(bNormBuilder,
      flag, universe.typeOf[Boolean])
    recurrentBuilder.putAttr("bnorm", bNormBuilder.build)

    createSerializeBigDLModule(recurrentBuilder, module)
  }
}

case class BatchNormParams[T : ClassTag](
             var eps: Double = 1e-5, // avoid divde zero
             var momentum: Double = 0.1, // momentum for weight update
             var initWeight: Tensor[T] = null,
             var initBias: Tensor[T] = null,
             var initGradWeight: Tensor[T] = null,
             var initGradBias: Tensor[T] = null,
             var affine: Boolean = true)(implicit ev: TensorNumeric[T])
