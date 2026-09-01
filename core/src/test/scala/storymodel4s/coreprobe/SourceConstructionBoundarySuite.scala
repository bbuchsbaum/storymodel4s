package storymodel4s.coreprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite
import storymodel4s.core.*

/** External four-door refusals for C1 joined-claim types. */
class SourceConstructionBoundarySuite extends FunSuite:

  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what must not be constructible outside storymodel4s.core")

  test("positive control: companion fromProduct remains on a same-module case class"):
    val errors = typeCheckErrors(
      """storymodel4s.core.SurfaceUnit.fromProduct((storymodel4s.core.SurfaceUnitId.unsafe("u"), storymodel4s.core.SurfaceUnitKind.Token, storymodel4s.core.TextSpan.unsafe(0, 1), 0, None))"""
    )
    assert(
      errors.isEmpty,
      s"if the fromProduct control fails, every refusal beside it is meaningless:\n${errors.mkString("\n")}"
    )

  test("positive control: copy remains on a same-module case class"):
    val errors = typeCheckErrors(
      """(u: storymodel4s.core.SurfaceUnit) => u.copy(ordinal = 1)"""
    )
    assert(
      errors.isEmpty,
      s"if the copy control fails, copy refusals are meaningless:\n${errors.mkString("\n")}"
    )

  test("positive control: apply remains on a same-module case class"):
    val errors = typeCheckErrors(
      """storymodel4s.core.SurfaceUnit(storymodel4s.core.SurfaceUnitId.unsafe("u"), storymodel4s.core.SurfaceUnitKind.Token, storymodel4s.core.TextSpan.unsafe(0, 1), 0, None)"""
    )
    assert(
      errors.isEmpty,
      s"if the apply control fails, apply refusals are meaningless:\n${errors.mkString("\n")}"
    )

  test("positive control: Mirror.ProductOf remains on a same-module case class"):
    assertEquals(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.SurfaceUnit]]"),
      Nil,
      "the summon control must compile or Mirror refusals prove nothing"
    )

  test("joined-claim types have no Mirror.ProductOf summon door"):
    refused(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.ExactRational]]"),
      "ExactRational"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.RationalTimebase]]"
      ),
      "RationalTimebase"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.PacketTimeFields]]"
      ),
      "PacketTimeFields"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.FixturePacketWitness]]"
      ),
      "FixturePacketWitness"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.FixturePacketClassification]]"
      ),
      "FixturePacketClassification"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.CallerRuntimePacketRecord]]"
      ),
      "CallerRuntimePacketRecord"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.DerivationReceipt]]"
      ),
      "DerivationReceipt"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.AxisExtent.TextChars]]"
      ),
      "TextChars"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.MediaDuration.KnownPositive]]"
      ),
      "KnownPositive"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.PresentationAxis]]"
      ),
      "PresentationAxis"
    )
    refused(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.SourceBundle]]"),
      "SourceBundle"
    )
    refused(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.EvidenceSupport]]"),
      "EvidenceSupport"
    )
    refused(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.ClockRepair]]"),
      "ClockRepair"
    )
    refused(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.BoundaryClaim]]"),
      "BoundaryClaim"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.TextNarrativeAtlas]]"
      ),
      "TextNarrativeAtlas"
    )

  test("joined-claim types have no companion fromProduct door"):
    refused(
      typeCheckErrors("storymodel4s.core.ExactRational.fromProduct(null)"),
      "ExactRational.fromProduct"
    )
    refused(
      typeCheckErrors("storymodel4s.core.SourceBundle.fromProduct(null)"),
      "SourceBundle.fromProduct"
    )
    refused(
      typeCheckErrors("storymodel4s.core.EvidenceSupport.fromProduct(null)"),
      "EvidenceSupport.fromProduct"
    )
    refused(
      typeCheckErrors("storymodel4s.core.PresentationAxis.fromProduct(null)"),
      "PresentationAxis.fromProduct"
    )
    refused(
      typeCheckErrors("storymodel4s.core.PacketTimeFields.fromProduct(null)"),
      "PacketTimeFields.fromProduct"
    )
    refused(
      typeCheckErrors("storymodel4s.core.TextNarrativeAtlas.fromProduct(null)"),
      "TextNarrativeAtlas.fromProduct"
    )

  test("joined-claim types have no copy door"):
    refused(
      typeCheckErrors("(x: storymodel4s.core.ExactRational) => x.copy(numerator = 3L)"),
      "ExactRational.copy"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.core.SourceBundle) => x.copy(streams = Vector.empty)"),
      "SourceBundle.copy"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.core.EvidenceSupport) => x.copy(anchors = x.anchors)"),
      "EvidenceSupport.copy"
    )
    refused(
      typeCheckErrors(
        "(x: storymodel4s.core.PresentationAxis) => x.copy(kind = storymodel4s.core.AxisKind.EditionPlayback)"
      ),
      "PresentationAxis.copy"
    )
    refused(
      typeCheckErrors(
        "(x: storymodel4s.core.PacketTimeFields) => x.copy(pts = storymodel4s.core.TimestampField.missing)"
      ),
      "PacketTimeFields.copy"
    )
    refused(
      typeCheckErrors("(x: storymodel4s.core.TextNarrativeAtlas) => x.copy(units = Vector.empty)"),
      "TextNarrativeAtlas.copy"
    )

  test("joined-claim types have no public apply or new door"):
    refused(typeCheckErrors("storymodel4s.core.ExactRational(1L, 0L)"), "ExactRational.apply")
    refused(
      typeCheckErrors(
        "storymodel4s.core.PacketTimeFields(storymodel4s.core.TimestampField.present(1L), storymodel4s.core.TimestampField.present(2L))"
      ),
      "PacketTimeFields.apply"
    )
    refused(
      typeCheckErrors("new storymodel4s.core.MediaDuration.KnownPositive(0L)"),
      "KnownPositive.new"
    )
    refused(typeCheckErrors("new storymodel4s.core.AxisExtent.TextChars(0)"), "TextChars.new")
    refused(typeCheckErrors("storymodel4s.core.EvidenceSupport(null)"), "EvidenceSupport.apply")

  test("checked factories remain visible and refuse the named unlawful values"):
    assert(typeCheckErrors("storymodel4s.core.ExactRational.of(1L, 2L)").isEmpty)
    assert(
      typeCheckErrors(
        "storymodel4s.core.PacketTimeFields.of(storymodel4s.core.TimestampField.missing, storymodel4s.core.TimestampField.missing)"
      ).isEmpty
    )
    assert(ExactRational.of(1L, 0L).isLeft)
    assert(PacketTimeFields.of(TimestampField.present(1L), TimestampField.present(2L)).isLeft)
    assert(MediaDuration.knownPositive(0L).isLeft)
    assert(AxisExtent.textChars(0).isLeft)
    assert(
      EvidenceSupport
        .of(
          SourceBundle.writtenText(StorySource.fromText("Hi.").toOption.get).toOption.get,
          Vector.empty
        )
        .isLeft
    )
