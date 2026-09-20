package storymodel4s.codec

import io.circe.syntax.*
import munit.FunSuite
import storymodel4s.core.*
import CoreCodecs.given

class D1bPointCodecSuite extends FunSuite:
  private def bundle(timebase: RationalTimebase) = SourceBundle.filmEdition(
    EditionId.unsafe("point-wire"), Checksum.ofText("picture"), 0L, 100L, timebase).toOption.get
  private def support(b: SourceBundle) = EvidenceSupport.of(b, Vector(
    EvidenceAnchor.MediaPoint(b.id, b.streams.head.id,
      PlaybackInstant.on(b.primaryAxis, 25L).toOption.get))).toOption.get

  test("accepting control: point component records exact point and full binding"):
    val b = bundle(RationalTimebase.Millisecond)
    val json = support(b).asJson
    assertEquals(json.hcursor.get[String]("schema"), Right("evidence-support/v2"))
    assertEquals(json.hcursor.get[String]("bundleIdentity"), Right(b.identity.hex))
    val anchor = json.hcursor.downField("anchors").downArray
    assertEquals(anchor.get[String]("type"), Right("MediaPoint"))
    assertEquals(anchor.get[String]("tick"), Right("25"))
    assertEquals(anchor.get[String]("axis"), Right(b.primaryAxis.id.value))
    assert(json.as[EvidenceSupport].isLeft)

  test("same legacy id with changed full binding has distinct point component bytes"):
    val a = bundle(RationalTimebase.Millisecond)
    val first = a.streams.head
    val changed = SourceStream.of(first.id, first.kind, first.checksum, first.nativeAxis,
      first.extent, first.timebase, Vector(StreamId.unsafe("upstream-receipt"))).toOption.get
    val b = SourceBundle.of(a.edition, a.sourceKind, Vector(changed), a.primaryAxis,
      a.authorityTracks, a.mappings).toOption.get
    assertEquals(a.id, b.id)
    assertEquals(a.primaryAxis.id, b.primaryAxis.id)
    assertEquals(support(a).anchors.toVector, support(b).anchors.toVector)
    assertNotEquals(support(a).asJson, support(b).asJson)
