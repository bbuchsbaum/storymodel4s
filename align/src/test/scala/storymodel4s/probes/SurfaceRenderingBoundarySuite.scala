package surfaceprobe

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import storymodel4s.align.*
import storymodel4s.proposition.GraphOrder

final case class OpenRenderedPair(
    unit: UnitContent[GraphOrder.Canonical],
    target: TargetContent[GraphOrder.Canonical],
    grain: ContentGrain,
    unitText: String,
    targetText: String
)
final case class OpenCosineValue(metric: EmbeddingMetric, value: Double)
final case class OpenCoordinates(
    source: String,
    span: String,
    unitId: String,
    targetRef: String,
    binding: String,
    sourceAtlas: String,
    view: String
)

/** Consumer-side construction and coordinate courts keep checked rendering unforgeable. */
class SurfaceRenderingBoundarySuite extends FunSuite:

  test("rendered pair constructor is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; new OpenRenderedPair(???, ???, ???, ???, ???)"
      ),
      "same-shape positive control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; new SurfaceRendering.Pair(???, ???, ???, ???, ???)"
      ),
      "constructor opened"
    )
  }

  test("rendered pair apply is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; OpenRenderedPair(???, ???, ???, ???, ???)"
      ),
      "same-shape positive control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; SurfaceRendering.Pair(???, ???, ???, ???, ???)"
      ),
      "apply opened"
    )
  }

  test("rendered pair copy is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; (??? : OpenRenderedPair).copy()"
      ),
      "same-shape positive control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; (??? : SurfaceRendering.Pair).copy()"
      ),
      "copy opened"
    )
  }

  test("rendered pair fromProduct is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; summon[scala.deriving.Mirror.ProductOf[OpenRenderedPair]].fromProduct(???)"
      ),
      "same-shape positive control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; summon[scala.deriving.Mirror.ProductOf[SurfaceRendering.Pair]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("rendered pair Mirror is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; summon[scala.deriving.Mirror.ProductOf[OpenRenderedPair]]"
      ),
      "same-shape positive control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; summon[scala.deriving.Mirror.ProductOf[SurfaceRendering.Pair]]"
      ),
      "Mirror opened"
    )
  }

  test("cosine value constructor is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; new OpenCosineValue(???, ???)"
      ),
      "same-shape positive control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; new EmbeddingMetric.CosineDistanceValue(???, ???)"
      ),
      "constructor opened"
    )
  }

  test("cosine value apply is closed") {
    assert(
      typeChecks("import storymodel4s.align.*; import surfaceprobe.*; OpenCosineValue(???, ???)"),
      "same-shape positive control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; EmbeddingMetric.CosineDistanceValue(???, ???)"
      ),
      "apply opened"
    )
  }

  test("cosine value copy is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; (??? : OpenCosineValue).copy()"
      ),
      "same-shape positive control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; (??? : EmbeddingMetric.CosineDistanceValue).copy()"
      ),
      "copy opened"
    )
  }

  test("cosine value fromProduct is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; summon[scala.deriving.Mirror.ProductOf[OpenCosineValue]].fromProduct(???)"
      ),
      "same-shape positive control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; summon[scala.deriving.Mirror.ProductOf[EmbeddingMetric.CosineDistanceValue]].fromProduct(???)"
      ),
      "fromProduct opened"
    )
  }

  test("cosine value Mirror is closed") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; summon[scala.deriving.Mirror.ProductOf[OpenCosineValue]]"
      ),
      "same-shape positive control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; import surfaceprobe.*; summon[scala.deriving.Mirror.ProductOf[EmbeddingMetric.CosineDistanceValue]]"
      ),
      "Mirror opened"
    )
  }

  test("rendered pair cannot read source") {
    assert(typeChecks("(??? : surfaceprobe.OpenCoordinates).source"), "read control")
    assert(!typeChecks("(??? : storymodel4s.align.RenderedContentPair).source"))
  }

  test("rendered pair cannot read span") {
    assert(typeChecks("(??? : surfaceprobe.OpenCoordinates).span"), "read control")
    assert(!typeChecks("(??? : storymodel4s.align.RenderedContentPair).span"))
  }

  test("rendered pair cannot read unitId") {
    assert(typeChecks("(??? : surfaceprobe.OpenCoordinates).unitId"), "read control")
    assert(!typeChecks("(??? : storymodel4s.align.RenderedContentPair).unitId"))
  }

  test("rendered pair cannot read targetRef") {
    assert(typeChecks("(??? : surfaceprobe.OpenCoordinates).targetRef"), "read control")
    assert(!typeChecks("(??? : storymodel4s.align.RenderedContentPair).targetRef"))
  }

  test("rendered pair cannot read binding") {
    assert(typeChecks("(??? : surfaceprobe.OpenCoordinates).binding"), "read control")
    assert(!typeChecks("(??? : storymodel4s.align.RenderedContentPair).binding"))
  }

  test("rendered pair cannot read sourceAtlas") {
    assert(typeChecks("(??? : surfaceprobe.OpenCoordinates).sourceAtlas"), "read control")
    assert(!typeChecks("(??? : storymodel4s.align.RenderedContentPair).sourceAtlas"))
  }

  test("rendered pair cannot read view") {
    assert(typeChecks("(??? : surfaceprobe.OpenCoordinates).view"), "read control")
    assert(!typeChecks("(??? : storymodel4s.align.RenderedContentPair).view"))
  }

  test("direct companion product control and allowed text reads compile") {
    assert(typeChecks("storymodel4s.align.FunctionPrior.fromProduct(???)"))
    assert(typeChecks("(??? : storymodel4s.align.RenderedContentPair).unitText"))
    assert(typeChecks("(??? : storymodel4s.align.RenderedContentPair).targetText"))
  }
