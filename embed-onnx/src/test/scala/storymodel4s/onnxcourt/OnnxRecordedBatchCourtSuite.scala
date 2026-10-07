package storymodel4s.onnxcourt

import scala.compiletime.testing.typeChecks

import munit.FunSuite

/** Consumers outside embed/onnx cannot promote arbitrary result parts into a concrete attempt. */
class OnnxRecordedBatchCourtSuite extends FunSuite:
  test("record constructor is closed with a same-shape positive control"):
    assert(typeChecks("""
      import storymodel4s.embed.*
      import storymodel4s.embed.onnx.*
      def lawful(e: OnnxSentenceEmbedder, b: EmbedBatch) = e.record(b)
      final class RecordedBatchConstructorControl(r: Vector[EmbedRequest], m: OnnxSentenceModel, p: ProviderFingerprint,
        rt: String, s: Vector[EmbeddingSpace], v: BatchResult)
      def control(e: OnnxSentenceEmbedder, b: EmbedBatch) =
        new RecordedBatchConstructorControl(b.requests, e.model, e.info.provider, e.runtimeIdentity, e.spaces, e.embed(b))
    """))
    assert(!typeChecks("""
      import storymodel4s.embed.*
      import storymodel4s.embed.onnx.*
      def forge(e: OnnxSentenceEmbedder, b: EmbedBatch, result: BatchResult) =
        new OnnxSentenceEmbedder.RecordedBatch(
          b.requests, e.model, e.info.provider, e.runtimeIdentity, e.spaces, result)
    """))

  test("record apply is closed with a same-shape positive control"):
    assert(typeChecks("""
      import storymodel4s.embed.*
      import storymodel4s.embed.onnx.*
      final case class RecordedBatchApplyControl(r: Vector[EmbedRequest], m: OnnxSentenceModel, p: ProviderFingerprint,
        rt: String, s: Vector[EmbeddingSpace], v: BatchResult)
      def control(e: OnnxSentenceEmbedder, b: EmbedBatch) =
        RecordedBatchApplyControl(b.requests, e.model, e.info.provider, e.runtimeIdentity, e.spaces, e.embed(b))
    """))
    assert(!typeChecks("""
      import storymodel4s.embed.*
      import storymodel4s.embed.onnx.*
      def forge(e: OnnxSentenceEmbedder, b: EmbedBatch, result: BatchResult) =
        OnnxSentenceEmbedder.RecordedBatch(
          b.requests, e.model, e.info.provider, e.runtimeIdentity, e.spaces, result)
    """))

  test("record copy is closed with a same-shape positive control"):
    assert(typeChecks("""
      import storymodel4s.embed.BatchResult
      final case class RecordedBatchCopyControl(result: BatchResult)
      def control(record: RecordedBatchCopyControl, replacement: BatchResult) = record.copy(result = replacement)
    """))
    assert(!typeChecks("""
      import storymodel4s.embed.BatchResult
      import storymodel4s.embed.onnx.OnnxSentenceEmbedder.RecordedBatch
      def forge(record: RecordedBatch, replacement: BatchResult) = record.copy(result = replacement)
    """))

  test("record fromProduct is closed with a same-shape positive control"):
    assert(typeChecks("""
      final case class RecordedBatchProductControl(result: Int)
      def control = summon[scala.deriving.Mirror.ProductOf[RecordedBatchProductControl]].fromProduct(Tuple1(1))
    """))
    assert(!typeChecks("""
      import storymodel4s.embed.onnx.OnnxSentenceEmbedder.RecordedBatch
      def forge = summon[scala.deriving.Mirror.ProductOf[RecordedBatch]].fromProduct(Tuple1(1))
    """))

  test("record product mirror is closed with a same-shape positive control"):
    assert(typeChecks("""
      final case class RecordedBatchMirrorControl(result: Int)
      val control = summon[scala.deriving.Mirror.ProductOf[RecordedBatchMirrorControl]]
    """))
    assert(!typeChecks("""
      import storymodel4s.embed.onnx.OnnxSentenceEmbedder.RecordedBatch
      val forge = summon[scala.deriving.Mirror.ProductOf[RecordedBatch]]
    """))

  test("captured payloads cannot be read with a same-shape positive control"):
    assert(typeChecks("""
      import storymodel4s.embed.EmbedRequest
      final class RecordedBatchPayloadControl(val requests: Vector[EmbedRequest])
      def control(record: RecordedBatchPayloadControl) = record.requests
    """))
    assert(!typeChecks("""
      import storymodel4s.embed.onnx.OnnxSentenceEmbedder.RecordedBatch
      def leak(record: RecordedBatch) = record.requests
    """))
