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

package com.intel.analytics.bigdl.example.quantization


import com.intel.analytics.bigdl.Module
import com.intel.analytics.bigdl.nn._
import com.intel.analytics.bigdl.utils.{Engine, LoggerFilter}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkContext

object Test {
  import Utils._
  LoggerFilter.redirectSparkInfoLogs()
  Logger.getLogger("com.intel.analytics.bigdl.optim").setLevel(Level.INFO)

  def main(args: Array[String]): Unit = {
    testParser.parse(args, TestParams()).foreach { param =>
      val name = param.model
      val path = param.modelPath
      val batchSize = param.batchSize
      val folder = param.folder

      val conf = Engine.createSparkConf()
              .setAppName(s"Test ${name} of ${path} with quantization")
              .set("spark.akka.frameSize", 64.toString)
      val sc = new SparkContext(conf)
      Engine.init

      val partitionNum = Engine.nodeNumber() * Engine.coreNumber()
      val rddData = getRddData(name, sc, partitionNum, folder)
      val transformer = getTransformer(name)
      val evaluationSet = transformer(rddData)

      val model = if (param.model != "lenet") {
        val m = Module.load[Float](path)
        if (param.quantize) {
          Module.quantize(m)
        } else {
          m
        }
      } else {
        val reshape = Reshape[Float](Array(1, 28, 28))
        val newModel = Sequential[Float]()
        newModel.add(reshape)
        newModel.add(Module.load[Float](path))

        if (param.quantize) {
          Module.quantize(newModel)
        } else {
          newModel
        }
      }

      val (modelResult, modelCosts) = time {
        test(model, evaluationSet, batchSize)
      }

      require(modelResult.length > 0, s"unknown result")
      val totalNum = modelResult(0)._1.result()._2

      val accuracies = new Array[Float](modelResult.length)
      modelResult.indices.foreach { i =>
        accuracies(i) = modelResult(i)._1.result()._1
      }

      val costs = Math.round(totalNum / modelCosts * 100) / 100.0

      writeToLog(param.model, param.quantize, totalNum, accuracies, costs)

      sc.stop()
    }
  }
}