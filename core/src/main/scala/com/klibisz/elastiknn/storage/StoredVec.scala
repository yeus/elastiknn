package com.klibisz.elastiknn.storage

import com.klibisz.elastiknn.api.Vec

/**
  * Abstraction for different vector storage layouts and typeclasses for encoding/decoding them.
  * This is decoupled from the api Vec case classes so we can support various optimizations that might change the
  * interface, e.g. streaming the vectors in a read-once fashion. Currently the fastest storage methods support roughly
  * the same interface.
  *
  * The current default serialization method is using sun.misc.Unsafe to eek out the best possible performance.
  * The implementation is based mostly on the Kryo library's use of sun.misc.Unsafe.
  * Many other options were considered:
  *  - fast-serialization library with unsafe configuration - roughly same as using Unsafe.
  *  - kryo library with unsafe input/output - a bit slower than fast-serialization and bare Unsafe.
  *  - java.io.ObjectOutput/InputStreams - 30-40% slower than Unsafe, but by far the best vanilla JVM solution.
  *  - protocol buffers - roughly same as ObjectOutput/InputStreams, but with smaller byte array sizes; the size
  *    doesn't seem to matter as it's compressed by ES anyway.
  *  - java.io.DataOutput/InputStreams - far slower.
  *  - scodec - far slower.
  *
  *  Anything using Unsafe comes with the tradeoff that it requires extra JVM security permissions.
  *  If this becomes a problem we should likely switch to ObjectOutput/InputStreams.
  */
sealed trait StoredVec

object StoredVec {

  sealed trait SparseBool extends StoredVec {
    val trueIndices: Array[Int]
  }

  sealed trait DenseFloat extends StoredVec {
    val values: Array[Float]
  }

  /**
    * Typeclasses for converting api vecs to stored vecs.
    */
  trait Codec[V <: Vec, S <: StoredVec] {
    def decode(barr: Array[Byte]): S
    def encode(vec: V): Array[Byte]
  }

  object Codec {
    implicit def derived[V <: Vec: Encoder, S <: StoredVec: Decoder]: Codec[V, S] =
      new Codec[V, S] {
        override def decode(barr: Array[Byte]): S = implicitly[Decoder[S]].apply(barr)
        override def encode(vec: V): Array[Byte] = implicitly[Encoder[V]].apply(vec)
      }
  }

  trait Decoder[S <: StoredVec] {
    def apply(barr: Array[Byte]): S
  }

  object Decoder {
    implicit val sparseBool: Decoder[SparseBool] = (barr: Array[Byte]) =>
      new SparseBool {
        override val trueIndices: Array[Int] = UnsafeSerialization.readInts(barr)
    }
    implicit val denseFloat: Decoder[DenseFloat] = (barr: Array[Byte]) =>
      new DenseFloat {
        override val values: Array[Float] = UnsafeSerialization.readFloats(barr)
    }
  }

  trait Encoder[V <: Vec] {
    def apply(vec: V): Array[Byte]
  }

  object Encoder {
    implicit val sparseBool: Encoder[Vec.SparseBool] = (vec: Vec.SparseBool) => UnsafeSerialization.writeInts(vec.trueIndices)
    implicit val denseFloat: Encoder[Vec.DenseFloat] = (vec: Vec.DenseFloat) => UnsafeSerialization.writeFloats(vec.values)
  }

}
