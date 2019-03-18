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
package com.intel.analytics.bigdl.nn.mkldnn

import com.intel.analytics.bigdl.mkl.{AlgKind, DataType, Memory}
import com.intel.analytics.bigdl.nn.mkldnn.Phase.{InferencePhase, TrainingPhase}
import com.intel.analytics.bigdl.nn.{SpatialAveragePooling, SpatialMaxPooling}
import com.intel.analytics.bigdl.tensor.{DnnTensor, Tensor}
import com.intel.analytics.bigdl.utils.{BigDLSpecHelper, Engine}
import com.intel.analytics.bigdl.utils.RandomGenerator.RNG
import com.intel.analytics.bigdl.utils.intermediate.{BlasToIR, IRToDnn}
import org.apache.commons.lang3.SerializationUtils

import scala.util.Random

class MaxPoolingSpec extends BigDLSpecHelper {
  "Max Pooling test1" should "be correct" in {
    val batchSize = 2
    val input = Tensor[Float](batchSize, 480, 28, 28).apply1(e => Random.nextFloat())
    val gradOutput = Tensor[Float](batchSize, 480, 14, 14).apply1(e => Random.nextFloat())

    val pad = -1
    RNG.setSeed(100)
    val pool = MaxPooling(3, 3, 2, 2, padH = pad, padW = pad)
    RNG.setSeed(100)
    val layer = SpatialMaxPooling[Float](3, 3, 2, 2, padH = pad, padW = pad).ceil()

    val seq = Sequential()
    seq.add(ReorderMemory(HeapData(Array(batchSize, 480, 28, 28), Memory.Format.nchw),
      HeapData(Array(batchSize, 480, 28, 28), Memory.Format.nchw)))
    seq.add(pool)
    seq.add(ReorderMemory(HeapData(Array(batchSize, 480, 14, 14), Memory.Format.nchw),
      HeapData(Array(batchSize, 480, 14, 14), Memory.Format.nchw)))
    seq.compile(Phase.TrainingPhase, Array(HeapData(Array(batchSize, 480, 28, 28),
      Memory.Format.nchw)))

    for (i <- 0 to 3) {
      input.rand()
      gradOutput.rand()

      seq.forward(input)
      seq.backward(input, gradOutput)

      layer.forward(input)
      layer.backward(input, gradOutput)
    }

    val output1 = seq.forward(input)
    val output2 = layer.forward(input).toTensor[Float]

    output1 should be(output2)

    val grad2 = layer.backward(input, output2).toTensor[Float]
    val grad1 = seq.backward(input, output2)
    grad1 should be(grad2)
  }

  "Convert max pooling with ceilMode to dnn layer" should "be correct" in {
    val batchSize = 2
    val input = Tensor[Float](batchSize, 480, 28, 28).apply1(e => Random.nextFloat())
    val gradOutput = Tensor[Float](batchSize, 480, 14, 14).apply1(e => Random.nextFloat())

    RNG.setSeed(100)
    val layer = SpatialMaxPooling[Float](3, 3, 2, 2).ceil()

    val irelement = BlasToIR[Float].convertLayer(layer)
    val pool = IRToDnn[Float].convertLayer(irelement)

    val seq = Sequential()
    seq.add(ReorderMemory(HeapData(Array(batchSize, 480, 28, 28), Memory.Format.nchw),
      HeapData(Array(batchSize, 480, 28, 28), Memory.Format.nchw)))
    seq.add(pool)
    seq.add(ReorderMemory(HeapData(Array(batchSize, 480, 14, 14), Memory.Format.nchw),
      HeapData(Array(batchSize, 480, 14, 14), Memory.Format.nchw)))
    seq.compile(Phase.TrainingPhase, Array(HeapData(Array(batchSize, 480, 28, 28),
      Memory.Format.nchw)))

    for (i <- 0 to 3) {
      input.rand()
      gradOutput.rand()

      seq.forward(input)
      seq.backward(input, gradOutput)

      layer.forward(input)
      layer.backward(input, gradOutput)
    }

    val output1 = seq.forward(input)
    val output2 = layer.forward(input).toTensor[Float]
    output1 should be(output2)

    val grad2 = layer.backward(input, output2).toTensor[Float]
    val grad1 = seq.backward(input, output2)
    grad1 should be(grad2)
  }

  "Max Pooling test2" should "be correct" in {
    val batchSize = 2
    val input = Tensor[Float](batchSize, 64, 112, 112).apply1(e => Random.nextFloat())
    val gradOutput = Tensor[Float](batchSize, 480, 14, 14).apply1(e => Random.nextFloat())

    RNG.setSeed(100)
    val pool = MaxPooling(3, 3, 2, 2)
    RNG.setSeed(100)
    val layer = SpatialMaxPooling[Float](3, 3, 2, 2).ceil()

    val output2 = layer.forward(input).toTensor[Float]

    val seq = Sequential()
    seq.add(ReorderMemory(HeapData(Array(batchSize, 64, 112, 112), Memory.Format.nchw),
      HeapData(Array(batchSize, 64, 112, 112), Memory.Format.nchw)))
    seq.add(pool)
    seq.add(ReorderMemory(HeapData(Array(batchSize, 64, 56, 56), Memory.Format.nchw),
      HeapData(Array(batchSize, 64, 56, 56), Memory.Format.nchw)))
    seq.compile(Phase.TrainingPhase, Array(HeapData(Array(batchSize, 64, 112, 112),
      Memory.Format.nchw)))
    val output1 = seq.forward(input)
    output1 should be(output2)

    val grad2 = layer.backward(input, output2).toTensor[Float]
    val grad1 = seq.backward(input, output2)
    grad1 should be(grad2)
  }

  "max pooling with java serialization" should "be correct" in {
    val batchSize = 2
    val inputShape = Array(batchSize, 64, 112, 112)
    val outputShape = Array(batchSize, 64, 56, 56)
    val input = Tensor[Float](batchSize, 64, 112, 112).rand(-1, 1)

    val pool = MaxPooling(3, 3, 2, 2)
    pool.setRuntime(new MklDnnRuntime)
    pool.initFwdPrimitives(Array(HeapData(inputShape, Memory.Format.nchw)), TrainingPhase)
    pool.initBwdPrimitives(Array(HeapData(outputShape, Memory.Format.nchw)), TrainingPhase)
    pool.initGradWPrimitives(Array(HeapData(outputShape, Memory.Format.nchw)), TrainingPhase)

    val cloned = SerializationUtils.clone(pool)
    cloned.setRuntime(new MklDnnRuntime)
    cloned.initFwdPrimitives(Array(HeapData(inputShape, Memory.Format.nchw)), TrainingPhase)
    cloned.initBwdPrimitives(Array(HeapData(outputShape, Memory.Format.nchw)), TrainingPhase)
    cloned.initGradWPrimitives(Array(HeapData(outputShape, Memory.Format.nchw)), TrainingPhase)

    pool.forward(input)
    cloned.forward(input)

    Tools.dense(pool.output) should be (Tools.dense(cloned.output))

    val gradOutput = Tensor[Float](outputShape).rand(-1, 1)
    pool.backward(input, gradOutput)
    cloned.backward(input, gradOutput)

    Tools.dense(pool.gradInput) should be (Tools.dense(cloned.gradInput))
  }

  "max pooling with int8" should "be correct" in {
    val inputShape = Array(4, 3, 5, 5)
    val outputShape = Array(4, 3, 2, 2)

    val kernel = 3
    val pad = 1

    val runtime = new MklDnnRuntime

    val input = Tensor[Float](inputShape).rand(0, 1)

    val heapData = HeapData(inputShape, Memory.Format.nchw, DataType.F32)
    val nativeData = NativeData(inputShape, Memory.Format.nhwc, DataType.U8)
    val inputScales = input.clone().max(1)._1.max(3)._1.max(4)._1.storage().array()
    heapData.setMask(0)
    heapData.setScales(inputScales.map(x => 255f / x))

    val reorder = ReorderMemory(nativeData)
    reorder.setRuntime(runtime)
    reorder.initFwdPrimitives(Array(heapData), InferencePhase)

    val reorderedInput = reorder.forward(input)

    {
      val len = inputShape.product
      val output = new Array[Byte](len)
      Memory.CopyPtr2ByteArray(reorderedInput.asInstanceOf[DnnTensor[Byte]].storageAddress(),
        0, output, 0, len, 1)
      output.foreach(println)
    }

    val pool = MaxPooling(3, 3, 2, 2)
    pool.evaluate()
    pool.setRuntime(runtime)
    pool.initFwdPrimitives(Array(nativeData), InferencePhase)
    pool.forward(reorderedInput)

    {
      val len = outputShape.product
      val output = new Array[Byte](len)
      Memory.CopyPtr2ByteArray(pool.output.asInstanceOf[DnnTensor[Byte]].storageAddress(),
        0, output, 0, len, 1)
      output.foreach(println)
    }

    val heapData2 = HeapData(outputShape, Memory.Format.nchw, DataType.F32)
    val reorder2 = ReorderMemory(heapData2)
    reorder2.setRuntime(runtime)
    reorder2.initFwdPrimitives(pool.outputFormats(), InferencePhase)
    reorder2.forward(pool.output)

    println(reorder2.output)
  }
}
