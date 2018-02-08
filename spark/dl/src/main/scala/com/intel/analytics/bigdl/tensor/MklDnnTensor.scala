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

package com.intel.analytics.bigdl.tensor

import java.io.{IOException, ObjectInputStream}

import com.intel.analytics.bigdl.mkl.{Memory, MklDnn}
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import serialization.Bigdl.TensorType

import scala.reflect.ClassTag

class MklDnnTensor[T: ClassTag](
  _dnnstorage: Storage[T],
  _dnnstorageOffset: Int,
  _dnnsize: Array[Int],
  _dnnstride: Array[Int],
  _dnnnDimension: Int)(implicit ev: TensorNumeric[T])
  extends DenseTensor[T](_dnnstorage, _dnnstorageOffset, _dnnsize, _dnnstride, _dnnnDimension) {
  private val errorMsg = s"MklDnnTensor only supports float"

  val ELEMENT_SIZE: Int = {
    ev.getType() match {
      case FloatType => 4
      case DoubleType => 8
      case _ => throw new IllegalArgumentException(errorMsg)
    }
  }

  private val CACHE_LINE_SIZE = 64
  private val DEBUG = false
  private val NullPtr = 0L

  @transient private var _pointer = if (_size != null && _size.product != 0) {
    allocate(_size.product)
  } else {
    NullPtr
  }

  private def allocate(capacity: Int): Long = {
    require(capacity != 0, s"capacity should not be 0")
    val ptr = Memory.AlignedMalloc(capacity * ELEMENT_SIZE, CACHE_LINE_SIZE)
    require(ptr != 0L, s"allocate native aligned memory failed")
    ptr
  }

  def ptr: Long = _pointer
  def release(): Unit = {
    if (_pointer != NullPtr) {
      Memory.AlignedFree(_pointer)
    }
    _pointer = NullPtr
  }

  def syncFromHeap(): this.type = {
    if (this._storage == null) {
      this._storage = Storage[T](size().product)
    }
    MklDnnTensor.syncFromHeap(this, this._storage.array(), storageOffset() - 1)
    this
  }

  def syncToHeap(): Storage[T] = {
    if (_storage == null || _storage.length() != nElement()) {
      this._storage = Storage[T](nElement())
    }
    MklDnnTensor.syncToHeap(this, this._storage.array(), storageOffset() - 1)
    this._storage
  }

  override def nElement(): Int = if (_size == null) { 0 } else { _size.product }

  override def storage(): Storage[T] = {
    if (_storage == null || _storage.length() != nElement()) {
      this._storage = Storage[T](nElement())
    }
//    MklDnnTensor.syncToHeap(this, this._storage.array(), storageOffset() - 1)
    this._storage
  }

  override def resize(size: Array[Int], stride: Array[Int] = null): this.type = {
    require(size.product != 0, s"size should not be 0")

    if (size.product != nElement()) {
      release()
      _pointer = allocate(size.product)
    }
    this._size = size
    this._stride = DenseTensor.size2Stride(size)

    this
  }

  override def resize(size: Int): this.type = {
    require(size != 0, s"size should not be 0")

    if (size != nElement()) {
      release()
      _pointer = allocate(size)
    }

    this._size = Array(size)
    this._stride = DenseTensor.size2Stride(this._size)

    this
  }

  override def getTensorType: TensorType = MklDnnType

  @throws(classOf[IOException])
  private def readObject(in: ObjectInputStream): Unit = {
    in.defaultReadObject()
    this._pointer = allocate(_size.product)
  }

}

object MklDnnTensor {
  MklDnn.isLoaded
  def apply[T: ClassTag](size: Array[Int])(implicit ev: TensorNumeric[T]): MklDnnTensor[T] = {
    val storageOffset = 0
    val stride = DenseTensor.size2Stride(size)
    val dim = size.length
    new MklDnnTensor[T](null, storageOffset, size, stride, dim)
  }

  def apply[T: ClassTag]()(implicit ev: TensorNumeric[T]): MklDnnTensor[T] = {
    new MklDnnTensor[T](null, 0, null, null, 0)
  }

  val DEBUG = false
  def syncToHeap[T: ClassTag](dnnTensor: MklDnnTensor[T], heap: Array[T], offset: Int): Unit = {
    require(dnnTensor.ptr != 0, s"native storage has not been allocated")
    def toFloat(array: Array[T]): Array[Float] = array.asInstanceOf[Array[Float]]

    if (DEBUG) {
      println("convert from native storage -> heap array")
      for (ste <- Thread.currentThread().getStackTrace) {
        if (ste.toString.contains("com.intel.analytics.bigdl.")) {
          println("\t|----> " + ste)
        }
      }
    }
    Memory.CopyPtr2Array(dnnTensor.ptr, 0, toFloat(heap), offset,
      dnnTensor.nElement(), dnnTensor.ELEMENT_SIZE)
  }

  def syncFromHeap[T: ClassTag](dnnTensor: MklDnnTensor[T], heap: Array[T], offset: Int): Unit = {
    require(dnnTensor.ptr != 0, s"native storage has not been allocated")
    def toFloat(array: Array[T]): Array[Float] = array.asInstanceOf[Array[Float]]

    if (DEBUG) {
      println("sync from heap array -> native storage")
      for (ste <- Thread.currentThread().getStackTrace) {
        if (ste.toString.contains("com.intel.analytics.bigdl.")) {
          println("\t|----> " + ste)
        }
      }
    }
    Memory.CopyArray2Ptr(toFloat(heap), offset,
      dnnTensor.ptr, 0, dnnTensor.nElement(), dnnTensor.ELEMENT_SIZE)
  }
}