package storymodel4s.pipeline

import storymodel4s.align.bridge.TimedSourceView
import storymodel4s.codec.TimedSourceArtifactCodec

/** JVM-compatible name for the portable checked timed-source artifact replay. */
object TimedAnnotationInput:
  val Header: Vector[String] = TimedSourceArtifactCodec.Header

  def prepare(csv: Array[Byte], descriptor: Array[Byte]): Either[String, TimedSourceView] =
    TimedSourceArtifactCodec.prepare(csv, descriptor)

  def encode(csv: Array[Byte], descriptor: Array[Byte]): Either[String, String] =
    TimedSourceArtifactCodec.encode(csv, descriptor)

  def decode(text: String): Either[String, TimedSourceView] =
    TimedSourceArtifactCodec.decode(text)
