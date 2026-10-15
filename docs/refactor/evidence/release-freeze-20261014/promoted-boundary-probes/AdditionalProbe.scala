package externalpromotionprobe
import storymodel4s.core.*
import storymodel4s.align.*
import storymodel4s.embed.*
import storymodel4s.media.*
import storymodel4s.recall.*
object AdditionalProbe:
  def main(args: Array[String]): Unit =
    val a = SemanticView.Custom("a:b", "c")
    val b = SemanticView.Custom("a", "b:c")
    assert(a != b && a.render == b.render)
    val provider = ProviderFingerprint.of("m","t","i","r")
    def space(view: SemanticView) = EmbeddingSpace.of(provider, Role.Document, view, None, Dimension.unsafe(1), Normalization.L2, TruncationPolicy.Reject).toOption.get
    assert(space(a).id == space(b).id)
    println("CUSTOM_VIEW: distinct namespace/name pairs have same render and same GeometryId")
    val declared = DeclaredFrames.of(3, Long.MaxValue, Vector.empty).toOption.get
    assert(declared.expectedPts == Vector(0L, Long.MaxValue, -2L))
    assert(DeclaredFrames.of(3, 1L, Vector.empty).toOption.get.expectedPts == Vector(0L,1L,2L))
    println("DECLARED_FRAMES: checked positive fields produce expectedPts=[0,9223372036854775807,-2]")
    val picture = PictureGeometry(Int.MaxValue, Int.MaxValue)
    assert(picture.frameBytes < 0L)
    assert(PictureGeometry(1,1).frameBytes == 3L)
    println("PICTURE_GEOMETRY: positive Int dimensions produce negative frameBytes=" + picture.frameBytes)
    val unit = RecallUnitId.unsafe("probe-unit")
    val one = AlignState.External(ExternalState.Association)
    val two = AlignState.External(ExternalState.Commentary)
    val row = AlignmentRow.of(unit, Map(one -> Double.MaxValue, two -> Double.MaxValue)).toOption.get
    assert(row.total.isPosInfinity && row.normalized.total == 0.0)
    assert(AlignmentRow.of(unit, Map(one -> 1.0, two -> 1.0)).toOption.get.normalized.total == 1.0)
    println("ALIGNMENT_ROW: admitted finite masses overflow total; normalized silently becomes all-zero")
    println("PROBES_PASSED=4")
