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

package com.intel.analytics.bigdl.nn.bigquant

import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.bigdl.utils.Table
import com.intel.analytics.bigdl.utils.serializer.{DataConverter, ModuleData, ModuleSerializable}
import scala.reflect.ClassTag
import serialization.Bigdl.{AttrValue, BigDLModule, BigDLTensor}
import scala.reflect.runtime.universe

trait QuantSerializer extends ModuleSerializable {
  def serializeWeight[T: ClassTag](module: ModuleData[T],
    modelBuilder: BigDLModule.Builder)(implicit ev: TensorNumeric[T]): Unit

  def serializeBias[T: ClassTag](module: ModuleData[T],
    modelBuilder: BigDLModule.Builder)(implicit ev: TensorNumeric[T]): Unit = {
    val paramTable : Table = module.module.getParametersTable
    if (paramTable != null && paramTable.contains(module.module.getName)) {
      val modulePramTable: Table = paramTable(module.module.getName)
      val bias: Tensor[T] = if (modulePramTable.contains("bias")) {
        modulePramTable("bias")
      } else {
        null
      }

      if (bias != null) {
        val biasTensorBuilder = BigDLTensor.newBuilder
        copyFromBigDLTensor(bias, biasTensorBuilder)
        modelBuilder.setBias(biasTensorBuilder.build)
      }
    }
  }

  def serializeOthers[T: ClassTag](module: ModuleData[T],
    modelBuilder: BigDLModule.Builder)(implicit ev: TensorNumeric[T]): Unit = {
    val layer = module.module.asInstanceOf[QuantModule[T]]

    val weightSumBuilder = AttrValue.newBuilder
    DataConverter.setAttributeValue(weightSumBuilder, layer.weightSum,
      universe.typeOf[Array[Float]])
    modelBuilder.putAttr("weightSum", weightSumBuilder.build)

    val minBuilder = AttrValue.newBuilder
    DataConverter.setAttributeValue(minBuilder, layer.min, universe.typeOf[Array[Float]])
    modelBuilder.putAttr("min", minBuilder.build)

    val maxBuilder = AttrValue.newBuilder
    DataConverter.setAttributeValue(maxBuilder, layer.max, universe.typeOf[Array[Float]])
    modelBuilder.putAttr("max", maxBuilder.build)
  }

  def loadWeight[T: ClassTag](model: BigDLModule,
    module: ModuleData[T])(implicit ev: TensorNumeric[T]): Unit

  def loadBias[T: ClassTag](model: BigDLModule,
    module: ModuleData[T])(implicit ev: TensorNumeric[T]): Unit = {
    val paramTable : Table = module.module.getParametersTable
    if (paramTable != null && paramTable.contains(model.getName)) {
      val modulePramTable : Table = paramTable(module.module.getName)
      val bias : Tensor[T] = if (modulePramTable.contains("bias")) {
        modulePramTable("bias")
      } else {
        null
      }

      if (bias != null) {
        copy2BigDLTensor(bias, model.getBias)
      }
    }
  }

  def loadOthers[T: ClassTag](model: BigDLModule,
    module: ModuleData[T])(implicit ev: TensorNumeric[T]): Unit = {
    def tensorCopy(src: Array[java.lang.Float], dst: Array[Float], offset: Int): Unit = {
      for (i <- src.indices) {
        dst(i + offset) = src(i)
      }
    }

    val attrMap = model.getAttrMap
    val weightSum = DataConverter.getAttributeValue(attrMap.get("weightSum"))
            .asInstanceOf[Array[java.lang.Float]]
    val min = DataConverter.getAttributeValue(attrMap.get("min"))
            .asInstanceOf[Array[java.lang.Float]]
    val max = DataConverter.getAttributeValue(attrMap.get("max"))
            .asInstanceOf[Array[java.lang.Float]]

    val layer = module.module.asInstanceOf[QuantModule[T]]
    tensorCopy(weightSum, layer.weightSum.asInstanceOf[Array[Float]], 0)
    tensorCopy(min, layer.min.asInstanceOf[Array[Float]], 0)
    tensorCopy(max, layer.max.asInstanceOf[Array[Float]], 0)
  }

  override protected def copyFromBigDL[T: ClassTag](module: ModuleData[T],
    modelBuilder: BigDLModule.Builder)(implicit ev: TensorNumeric[T]): Unit = {
    serializeWeight(module, modelBuilder)
    serializeBias(module, modelBuilder)
    serializeOthers(module, modelBuilder)
  }

  override protected def copy2BigDL[T: ClassTag](model: BigDLModule, module: ModuleData[T])
          (implicit ev: TensorNumeric[T]): Unit = {
    loadWeight(model, module)
    loadBias(model, module)
    loadOthers(model, module)
  }
}