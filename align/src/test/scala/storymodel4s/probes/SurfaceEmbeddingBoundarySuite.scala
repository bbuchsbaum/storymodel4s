package surfaceembeddingconsumer

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.embed.*
import storymodel4s.features.Estimate
import storymodel4s.proposition.GraphOrder
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

final case class OpenQuery(content: UnitContent[GraphOrder.Canonical], text: String)
final case class OpenDocument(content: TargetContent[GraphOrder.Canonical], text: String)
final case class OpenContext(
    recall: RecallGraph[Checked],
    source: SourceView,
    representation: SourceRepresentation,
    sourceAtlas: SurfaceAtlas,
    queries: Vector[(RecallUnitId, RenderedUnitInput)],
    documents: Vector[(SourceNodeRef, RenderedTargetInput)]
)
final case class OpenData(
    querySpace: EmbeddingSpace,
    documentSpace: EmbeddingSpace,
    geometry: GeometryPair,
    emptyPolicy: SurfaceEmptyPolicy,
    queries: Map[RenderedUnitInput, SurfaceEmbedding.Outcome],
    documents: Map[RenderedTargetInput, SurfaceEmbedding.Outcome]
)
final case class OpenSession(
    context: SurfaceScoringContext,
    channel: StrictSemanticChannel.FactorizedSurface
)
final case class OpenScore(
    query: SurfaceEmbedding.Outcome,
    document: SurfaceEmbedding.Outcome,
    estimate: Estimate[Double],
    calculationError: Option[EmbedError]
)
final case class OpenCandidates(
    policy: CandidateTiePolicy,
    binding: StrictBinding,
    byUnit: Map[RecallUnitId, StrictCandidateSet],
    semanticChannel: Option[StrictSemanticChannel],
    surfaceContext: Option[SurfaceScoringContext]
)
final case class OpenRead(id: String, ordinal: Int, span: String, source: String, binding: String)

/** Outside-package controls pin every new constructor/product door and endpoint read boundary. */
class SurfaceEmbeddingBoundarySuite extends FunSuite:

  test("query constructor is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new OpenQuery(???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new SurfaceRendering.UnitInput(???, ???)"
      ),
      "constructor opened"
    )
  }

  test("query apply is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; OpenQuery(???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; SurfaceRendering.UnitInput(???, ???)"
      ),
      "apply opened"
    )
  }

  test("query copy is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : OpenQuery).copy()"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : SurfaceRendering.UnitInput).copy()"
      ),
      "copy opened"
    )
  }

  test("query fromProduct is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenQuery]].fromProduct(???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceRendering.UnitInput]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("query Mirror is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenQuery]]"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceRendering.UnitInput]]"
      ),
      "Mirror opened"
    )
  }

  test("document constructor is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new OpenDocument(???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new SurfaceRendering.TargetInput(???, ???)"
      ),
      "constructor opened"
    )
  }

  test("document apply is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; OpenDocument(???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; SurfaceRendering.TargetInput(???, ???)"
      ),
      "apply opened"
    )
  }

  test("document copy is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : OpenDocument).copy()"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : SurfaceRendering.TargetInput).copy()"
      ),
      "copy opened"
    )
  }

  test("document fromProduct is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenDocument]].fromProduct(???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceRendering.TargetInput]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("document Mirror is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenDocument]]"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceRendering.TargetInput]]"
      ),
      "Mirror opened"
    )
  }

  test("context constructor is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new OpenContext(???, ???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new SurfaceRendering.Context(???, ???, ???, ???, ???, ???)"
      ),
      "constructor opened"
    )
  }

  test("context apply is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; OpenContext(???, ???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; SurfaceRendering.Context(???, ???, ???, ???, ???, ???)"
      ),
      "apply opened"
    )
  }

  test("context copy is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : OpenContext).copy()"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : SurfaceRendering.Context).copy()"
      ),
      "copy opened"
    )
  }

  test("context fromProduct is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenContext]].fromProduct(???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceRendering.Context]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("context Mirror is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenContext]]"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceRendering.Context]]"
      ),
      "Mirror opened"
    )
  }

  test("data constructor is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new OpenData(???, ???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new SurfaceEmbedding.Data(???, ???, ???, ???, ???, ???)"
      ),
      "constructor opened"
    )
  }

  test("data apply is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; OpenData(???, ???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; SurfaceEmbedding.Data(???, ???, ???, ???, ???, ???)"
      ),
      "apply opened"
    )
  }

  test("data copy is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : OpenData).copy()"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : SurfaceEmbedding.Data).copy()"
      ),
      "copy opened"
    )
  }

  test("data fromProduct is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenData]].fromProduct(???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceEmbedding.Data]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("data Mirror is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenData]]"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceEmbedding.Data]]"
      ),
      "Mirror opened"
    )
  }

  test("session constructor is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new OpenSession(???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new SurfaceEmbedding.Session(???, ???)"
      ),
      "constructor opened"
    )
  }

  test("session apply is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; OpenSession(???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; SurfaceEmbedding.Session(???, ???)"
      ),
      "apply opened"
    )
  }

  test("session copy is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : OpenSession).copy()"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : SurfaceEmbedding.Session).copy()"
      ),
      "copy opened"
    )
  }

  test("session fromProduct is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenSession]].fromProduct(???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceEmbedding.Session]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("session Mirror is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenSession]]"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceEmbedding.Session]]"
      ),
      "Mirror opened"
    )
  }

  test("score constructor is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new OpenScore(???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new SurfaceEmbedding.Score(???, ???, ???, ???)"
      ),
      "constructor opened"
    )
  }

  test("score apply is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; OpenScore(???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; SurfaceEmbedding.Score(???, ???, ???, ???)"
      ),
      "apply opened"
    )
  }

  test("score copy is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : OpenScore).copy()"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : SurfaceEmbedding.Score).copy()"
      ),
      "copy opened"
    )
  }

  test("score fromProduct is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenScore]].fromProduct(???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceEmbedding.Score]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("score Mirror is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenScore]]"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceEmbedding.Score]]"
      ),
      "Mirror opened"
    )
  }

  test("strict candidates constructor is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new OpenCandidates(???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new StrictCandidates(???, ???, ???, ???, ???)"
      ),
      "constructor opened"
    )
  }

  test("strict candidates apply is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; OpenCandidates(???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; StrictCandidates(???, ???, ???, ???, ???)"
      ),
      "apply opened"
    )
  }

  test("strict candidates copy is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : OpenCandidates).copy()"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : StrictCandidates).copy()"
      ),
      "copy opened"
    )
  }

  test("strict candidates fromProduct is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenCandidates]].fromProduct(???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[StrictCandidates]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("strict candidates Mirror is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenCandidates]]"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[StrictCandidates]]"
      ),
      "Mirror opened"
    )
  }

  test("RenderedUnitInput cannot read id") {
    assert(typeChecks("(??? : surfaceembeddingconsumer.OpenRead).id"))
    assert(!typeChecks("(??? : storymodel4s.align.RenderedUnitInput).id"))
  }

  test("RenderedUnitInput cannot read ordinal") {
    assert(typeChecks("(??? : surfaceembeddingconsumer.OpenRead).ordinal"))
    assert(!typeChecks("(??? : storymodel4s.align.RenderedUnitInput).ordinal"))
  }

  test("RenderedUnitInput cannot read span") {
    assert(typeChecks("(??? : surfaceembeddingconsumer.OpenRead).span"))
    assert(!typeChecks("(??? : storymodel4s.align.RenderedUnitInput).span"))
  }

  test("RenderedUnitInput cannot read source") {
    assert(typeChecks("(??? : surfaceembeddingconsumer.OpenRead).source"))
    assert(!typeChecks("(??? : storymodel4s.align.RenderedUnitInput).source"))
  }

  test("RenderedUnitInput cannot read binding") {
    assert(typeChecks("(??? : surfaceembeddingconsumer.OpenRead).binding"))
    assert(!typeChecks("(??? : storymodel4s.align.RenderedUnitInput).binding"))
  }

  test("RenderedTargetInput cannot read id") {
    assert(typeChecks("(??? : surfaceembeddingconsumer.OpenRead).id"))
    assert(!typeChecks("(??? : storymodel4s.align.RenderedTargetInput).id"))
  }

  test("RenderedTargetInput cannot read ordinal") {
    assert(typeChecks("(??? : surfaceembeddingconsumer.OpenRead).ordinal"))
    assert(!typeChecks("(??? : storymodel4s.align.RenderedTargetInput).ordinal"))
  }

  test("RenderedTargetInput cannot read span") {
    assert(typeChecks("(??? : surfaceembeddingconsumer.OpenRead).span"))
    assert(!typeChecks("(??? : storymodel4s.align.RenderedTargetInput).span"))
  }

  test("RenderedTargetInput cannot read source") {
    assert(typeChecks("(??? : surfaceembeddingconsumer.OpenRead).source"))
    assert(!typeChecks("(??? : storymodel4s.align.RenderedTargetInput).source"))
  }

  test("RenderedTargetInput cannot read binding") {
    assert(typeChecks("(??? : surfaceembeddingconsumer.OpenRead).binding"))
    assert(!typeChecks("(??? : storymodel4s.align.RenderedTargetInput).binding"))
  }

  test("document constructor is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new OpenDocument(???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new SurfaceRendering.TargetInput(???, ???)"
      ),
      "constructor opened"
    )
  }

  test("document apply is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; OpenDocument(???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; SurfaceRendering.TargetInput(???, ???)"
      ),
      "apply opened"
    )
  }

  test("document copy is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : OpenDocument).copy()"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : SurfaceRendering.TargetInput).copy()"
      ),
      "copy opened"
    )
  }

  test("document fromProduct is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenDocument]].fromProduct(???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceRendering.TargetInput]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("document Mirror is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenDocument]]"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceRendering.TargetInput]]"
      ),
      "Mirror opened"
    )
  }

  test("context constructor is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new OpenContext(???, ???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new SurfaceRendering.Context(???, ???, ???, ???, ???, ???)"
      ),
      "constructor opened"
    )
  }

  test("context apply is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; OpenContext(???, ???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; SurfaceRendering.Context(???, ???, ???, ???, ???, ???)"
      ),
      "apply opened"
    )
  }

  test("context copy is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : OpenContext).copy()"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : SurfaceRendering.Context).copy()"
      ),
      "copy opened"
    )
  }

  test("context fromProduct is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenContext]].fromProduct(???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceRendering.Context]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("context Mirror is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenContext]]"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceRendering.Context]]"
      ),
      "Mirror opened"
    )
  }

  test("data constructor is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new OpenData(???, ???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; new SurfaceEmbedding.Data(???, ???, ???, ???, ???, ???)"
      ),
      "constructor opened"
    )
  }

  test("data apply is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; OpenData(???, ???, ???, ???, ???, ???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; SurfaceEmbedding.Data(???, ???, ???, ???, ???, ???)"
      ),
      "apply opened"
    )
  }

  test("data copy is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : OpenData).copy()"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; (??? : SurfaceEmbedding.Data).copy()"
      ),
      "copy opened"
    )
  }

  test("data fromProduct is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenData]].fromProduct(???)"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceEmbedding.Data]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("data Mirror is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[OpenData]]"
      ),
      "same-shape control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceembeddingconsumer.*; summon[scala.deriving.Mirror.ProductOf[SurfaceEmbedding.Data]]"
      ),
      "Mirror opened"
    )
  }
