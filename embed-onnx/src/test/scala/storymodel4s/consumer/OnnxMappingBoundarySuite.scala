package onnxmappingconsumer

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import storymodel4s.align.*
import storymodel4s.embed.*
import storymodel4s.embed.onnx.*

final case class OpenConfig(
    candidates: StrictCandidateConfig,
    querySensitivity: Sensitivity,
    documentSensitivity: Sensitivity,
    metric: EmbeddingMetric,
    emptyPolicy: SurfaceEmptyPolicy,
    pricing: StrictCostModel
)
final case class OpenRegistered(
    context: SurfaceScoringContext,
    config: OnnxMappingRegistration.Config,
    queryRecord: OnnxSentenceEmbedder.RecordedBatch,
    documentRecord: OnnxSentenceEmbedder.RecordedBatch,
    session: SurfaceScoringSession,
    effectiveCandidates: StrictCandidateConfig,
    candidates: StrictCandidates,
    pricing: StrictCostModel,
    evidence: LocalEvidence,
    basis: OnnxMappingBasis
)

object OpenReplay:
  def replay(
      context: SurfaceScoringContext,
      config: OnnxMappingRegistration.Config,
      model: OnnxSentenceModel,
      provider: ProviderFingerprint,
      query: OnnxSentenceEmbedder.RecordedBatch,
      document: OnnxSentenceEmbedder.RecordedBatch
  ): Either[OnnxMappingRefusal, OnnxMappingRegistration.Registered] = ???

/** Declared vectors and unrelated receipts cannot manufacture concrete execution authority. */
class OnnxMappingBoundarySuite extends FunSuite:

  test("config constructor is closed outside the defining package") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; new OpenConfig(???, ???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; new OnnxMappingRegistration.Config(???, ???, ???, ???, ???, ???)"
      ),
      "constructor opened"
    )
  }

  test("config apply is closed outside the defining package") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; OpenConfig(???, ???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; OnnxMappingRegistration.Config(???, ???, ???, ???, ???, ???)"
      ),
      "apply opened"
    )
  }

  test("config copy is closed outside the defining package") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; (??? : OpenConfig).copy()"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; (??? : OnnxMappingRegistration.Config).copy()"
      ),
      "copy opened"
    )
  }

  test("config fromProduct is closed outside the defining package") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenConfig]].fromProduct(???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OnnxMappingRegistration.Config]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("config Mirror is closed outside the defining package") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenConfig]]"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OnnxMappingRegistration.Config]]"
      ),
      "Mirror opened"
    )
  }

  test("registered constructor is closed outside the defining package") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; new OpenRegistered(???, ???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; new OnnxMappingRegistration.Registered(???, ???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "constructor opened"
    )
  }

  test("registered apply is closed outside the defining package") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; OpenRegistered(???, ???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; OnnxMappingRegistration.Registered(???, ???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "apply opened"
    )
  }

  test("registered copy is closed outside the defining package") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; (??? : OpenRegistered).copy()"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; (??? : OnnxMappingRegistration.Registered).copy()"
      ),
      "copy opened"
    )
  }

  test("registered fromProduct is closed outside the defining package") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenRegistered]].fromProduct(???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OnnxMappingRegistration.Registered]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("registered Mirror is closed outside the defining package") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenRegistered]]"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OnnxMappingRegistration.Registered]]"
      ),
      "Mirror opened"
    )
  }

  test("raw recording replay cannot bypass bound registration context and configuration") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; OpenReplay.replay(???, ???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import storymodel4s.embed.*; import storymodel4s.embed.onnx.*; import onnxmappingconsumer.*; OnnxMappingRegistration.replay(???, ???, ???, ???, ???, ???)"
      ),
      "raw replay bypass opened"
    )
  }
