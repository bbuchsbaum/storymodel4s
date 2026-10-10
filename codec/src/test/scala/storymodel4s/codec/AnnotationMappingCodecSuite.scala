package storymodel4s.codec

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*

/** Uses the admitted synthetic MappingCodecFixture; annotation ticks are exact parser controls. */
class AnnotationMappingCodecSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)

  test(
    "ordinary annotation interval and point codec preserves exact large ticks without execution"
  ) {
    val f = MappingCodecFixture
    val start = 9007199254740993L
    val bundle = right(
      SourceBundle.annotationTable(
        Checksum.ofText("synthetic annotation clock"),
        start,
        start + 100,
        RationalTimebase.Millisecond
      )
    )
    val axis = bundle.primaryAxis
    val interval = right(PlaybackInterval.onTimeAxis(axis, start + 1, start + 3))
    val point = right(PlaybackInstant.onTimeAxis(axis, start + 5))
    val support = right(
      EvidenceSupport.of(
        bundle,
        Vector(
          EvidenceAnchor.MediaTime(
            bundle.id,
            bundle.streams.head.id,
            axis.id,
            PlaybackIntervalSet.one(interval)
          ),
          EvidenceAnchor.MediaPoint(bundle.id, bundle.streams.head.id, point)
        )
      )
    )
    val source = right(
      SourceRepresentation.of(
        f.view,
        NonEmptyVector.one(BundleEntry.media(bundle)),
        None,
        f.refs.map(_ -> SourceSupportStatus.located(TypedSupport.Anchored(support))).toMap
      )
    )
    val record = f.record(source = source)
    val context = f.context(source = source)
    assertEquals(context.execution, None)
    val decoded = right(MappingCodecs.decode(MappingCodecs.encode(record), context))
    assertEquals(decoded.digest, record.digest)
    assertEquals(MappingCodecs.encode(decoded), MappingCodecs.encode(record))
    val actualBundle = decoded.source.bundles.head.asInstanceOf[BundleEntry.Media].bundle
    assertEquals(actualBundle.primaryAxis.kind, AxisKind.AnnotationTimeline)
    assertEquals(actualBundle.edition, None)
    val readout = right(
      TemporalSupport.read(
        decoded.source,
        f.refs.head,
        TemporalSupport.Selection.Part(bundle.identity)
      )
    )
    assertEquals(
      readout.selected.included.get.intervals.map(i => i.start -> i.endExclusive),
      Vector((start + 1) -> (start + 3))
    )
    assertEquals(readout.selected.included.get.points.map(_.at), Vector(start + 5))
  }

  test("ordinary text codec remains available without execution") {
    val f = MappingCodecFixture
    val record = f.record()
    val decoded = right(MappingCodecs.decode(MappingCodecs.encode(record), f.context()))
    assertEquals(decoded.digest, record.digest)
  }
