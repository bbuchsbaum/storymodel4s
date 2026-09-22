package storymodel4s.align

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*

class TemporalSupportSuite extends FunSuite:
  import SherlockShapedSource.*
  import TemporalSupport.*
  test(
    "overlapping part mappings cannot turn foreign or mixed evidence into a selected occurrence"
  ) {
    val streams = parts.toVector.flatMap(_.streams)
    val authority = streams.map(_.id)
    val primary = SourceBundle
      .editionPlaybackAxis(EditionId.unsafe("overlap"), streams, authority, 0L, 20L, timebase)
      .toOption
      .get
    val receipt = SourceDerivationReceipt
      .of("synthetic-overlap/v1", "same target geometry", parts.toVector.map(_.identity))
      .toOption
      .get
    val maps = parts.toVector.map { part =>
      val segment = CompositionSegment
        .of(
          PlaybackInterval.on(part.primaryAxis, 0L, 20L).toOption.get,
          PlaybackInterval.on(primary, 0L, 20L).toOption.get,
          OccurrenceId.unsafe("same-label")
        )
        .toOption
        .get
      TrackComposition.of(part.primaryAxis.id, primary.id, Vector(segment), receipt).toOption.get
    }
    val bundle = SourceBundle
      .of(primary.edition, SourceKind.FilmEdition, streams, primary, authority, maps)
      .toOption
      .get
    def representation(selected: Vector[SourceBundle]): SourceRepresentation =
      val evidence = EvidenceSupport
        .of(
          bundle,
          selected.map { part =>
            EvidenceAnchor.MediaTime(
              bundle.id,
              part.streams.head.id,
              primary.id,
              PlaybackIntervalSet.one(PlaybackInterval.on(primary, 2L, 5L).toOption.get)
            )
          }
        )
        .toOption
        .get
      SourceRepresentation
        .of(
          view.copy(nodes = Vector(nodes.head)),
          bundles,
          Some(DeclaredComposition.of(bundle, parts).toOption.get),
          Map(e(1) -> SourceSupportStatus.located(TypedSupport.Anchored(evidence)))
        )
        .toOption
        .get
    val sa = Selection.Occurrence(maps(0).identity, OccurrenceId.unsafe("same-label"))
    val sb = Selection.Occurrence(maps(1).identity, OccurrenceId.unsafe("same-label"))
    assertEquals(
      TemporalSupport.read(representation(Vector(b)), e(1), sa),
      Left(Refusal.OccurrenceEvidenceMismatch)
    )
    assertEquals(read(representation(Vector(b)), e(1), sb).selected.included.get.bounds, (2L, 5L))
    assertEquals(
      TemporalSupport.read(representation(Vector(a, b)), e(1), sa),
      Left(Refusal.OccurrenceEvidenceMismatch)
    )
    assertEquals(read(representation(Vector(a)), e(1), sa).selected.included.get.bounds, (2L, 5L))
  }
  private def read(
      value: SourceRepresentation,
      ref: SourceNodeRef,
      selection: Selection
  ): TemporalSupport =
    TemporalSupport.read(value, ref, selection).fold(e => fail(e.toString), identity)

  test("transitive unlocated descendants survive located intermediate groups") {
    val nested =
      view.copy(nodes = nodes.map(n => if n.ref == e(4) then n.copy(parent = Some(e(2))) else n))
    val value = SourceRepresentation.of(nested, bundles, None, physical(false)).toOption.get
    assert(
      value.target(group).get.supportCoverage.isInstanceOf[SupportCoverage.Complete]
    ) // Legacy wire is unchanged.
    val result = read(value, group, Selection.Part(a.identity))
    assertEquals(result.unlocatedDescendants, Vector(e(4)))
    assertEquals(result.unavailableDescendants, Vector(e(4)))
    assertEquals(result.sourceDigest, value.digest)
    assertEquals(
      result.selected.included.get.intervals.map(i => (i.start, i.endExclusive)),
      Vector((10L, 20L))
    )
  }
  test("unlocated parent remains unavailable even with located children") {
    val ns = nodes.filterNot(_.ref == e(4))
    val loci = physical(false)
      .removed(e(4))
      .updated(group, SourceSupportStatus.unlocated(UnlocatedReason.NoLocusInSource))
    val value = SourceRepresentation.of(view.copy(nodes = ns), bundles, None, loci).toOption.get
    val result = read(value, group, Selection.Part(a.identity))
    assertEquals(result.selected.state, State.Unlocated)
    assertEquals(result.selected.included, None)
    assertEquals(result.unlocatedDescendants, Vector.empty)
  }
  test("self-parent and two-node cycles refuse before descendant traversal") {
    Vector(
      nodes.map(n => if n.ref == group then n.copy(parent = Some(group)) else n),
      nodes.map(n => if n.ref == group then n.copy(parent = Some(e(2))) else n)
    ).foreach { ns =>
      val value =
        SourceRepresentation.of(view.copy(nodes = ns), bundles, None, physical(false)).toOption.get
      assertEquals(
        TemporalSupport.read(value, group, Selection.Part(a.identity)),
        Left(Refusal.CyclicHierarchy)
      )
    }
  }
  test("located descendants on another axis are coordinate-unavailable and not imputed") {
    val nested =
      view.copy(nodes = nodes.map(n => if n.ref == e(7) then n.copy(parent = Some(e(2))) else n))
    val value = SourceRepresentation.of(nested, bundles, None, physical(false)).toOption.get
    val result = read(value, e(2), Selection.Part(a.identity))
    assertEquals(result.unlocatedDescendants, Vector.empty)
    assertEquals(result.unavailableDescendants, Vector(e(7)))
    assertEquals(result.nodes.find(_.ref == e(7)).get.state, State.NoSupportOnAxis)
  }
  test("no parent containment or descendant-hull inference is made") {
    val loci = physical(false).updated(e(4), physical(false)(e(3)))
    val value = SourceRepresentation.of(view, bundles, None, loci).toOption.get
    val result = read(value, group, Selection.Part(a.identity))
    assertEquals(result.unlocatedDescendants, Vector.empty)
    assert(
      !result.selected.included.get.contains(result.nodes.find(_.ref == e(4)).get.included.get)
    )
    assertEquals(result.selected.included.get.bounds, (10L, 20L))
  }
  test(
    "part selection and supplied composed occurrence stay distinct without projecting native support"
  ) {
    val value = source(true)
    val selection = Selection.Occurrence(mappings(1).identity, OccurrenceId.unsafe("part-1"))
    val native = read(value, e(8), Selection.Part(b.identity))
    val composedResult = read(value, e(8), selection)
    assertEquals(native.selected.included.get.points.map(_.at), Vector(30L))
    assertEquals(composedResult.selected.included.get.points.map(_.at), Vector(70L))
    assertEquals(native.coordinate.occurrence, None)
    assertEquals(composedResult.coordinate.occurrence, Some(mappings(1).segments.head))
    val nativeOnly =
      SourceRepresentation.of(view, bundles, Some(composition), physical(false)).toOption.get
    assertEquals(read(nativeOnly, e(8), selection).selected.state, State.NoSupportOnAxis)
    assertEquals(
      TemporalSupport.read(value, e(8), Selection.Part(Checksum.ofText("wrong"))),
      Left(Refusal.UnknownPart)
    )
    assertEquals(
      TemporalSupport.read(
        value,
        e(8),
        Selection.Occurrence(Checksum.ofText("wrong"), OccurrenceId.unsafe("part-1"))
      ),
      Left(Refusal.UnknownOccurrence)
    )
  }
  private def repeated(duplicateLabels: Boolean) =
    val primary = SourceBundle
      .editionPlaybackAxis(
        EditionId.unsafe("repeated"),
        a.streams,
        a.authorityTracks,
        0L,
        40L,
        timebase
      )
      .toOption
      .get
    val segments = Vector(0L, 20L).zipWithIndex.map { (start, index) =>
      CompositionSegment
        .of(
          PlaybackInterval.on(a.primaryAxis, 0L, 20L).toOption.get,
          PlaybackInterval.on(primary, start, start + 20L).toOption.get,
          OccurrenceId.unsafe(if duplicateLabels then "same" else s"repeat-$index")
        )
        .toOption
        .get
    }
    val receipt = SourceDerivationReceipt
      .of("synthetic-repeat/v1", "two occurrences", Vector(a.identity))
      .toOption
      .get
    val mapping = TrackComposition.of(a.primaryAxis.id, primary.id, segments, receipt).toOption.get
    val bundle = SourceBundle
      .of(
        primary.edition,
        SourceKind.FilmEdition,
        a.streams,
        primary,
        a.authorityTracks,
        Vector(mapping)
      )
      .toOption
      .get
    val evidence = EvidenceSupport
      .of(
        bundle,
        Vector(
          EvidenceAnchor.MediaTime(
            bundle.id,
            a.streams.head.id,
            primary.id,
            PlaybackIntervalSet
              .of(
                Vector((2L, 5L), (10L, 30L)).map((s, e) =>
                  PlaybackInterval.on(primary, s, e).toOption.get
                )
              )
              .toOption
              .get
          ),
          EvidenceAnchor
            .MediaPoint(bundle.id, a.streams.head.id, PlaybackInstant.on(primary, 20L).toOption.get)
        )
      )
      .toOption
      .get
    val rep = SourceRepresentation
      .of(
        view.copy(nodes = Vector(nodes.head)),
        NonEmptyVector.one(BundleEntry.media(a)),
        Some(DeclaredComposition.of(bundle, NonEmptyVector.one(a)).toOption.get),
        Map(e(1) -> SourceSupportStatus.located(TypedSupport.Anchored(evidence)))
      )
      .toOption
      .get
    (rep, mapping)

  test(
    "repeated occurrences partition supplied unions and point boundaries without losing excluded extent"
  ) {
    val (rep, mapping) = repeated(false)
    val first =
      read(rep, e(1), Selection.Occurrence(mapping.identity, OccurrenceId.unsafe("repeat-0")))
    val second =
      read(rep, e(1), Selection.Occurrence(mapping.identity, OccurrenceId.unsafe("repeat-1")))
    assertEquals(
      first.selected.included.get.intervals.map(i => (i.start, i.endExclusive)),
      Vector((2L, 5L), (10L, 20L))
    )
    assertEquals(
      first.selected.excluded.get.intervals.map(i => (i.start, i.endExclusive)),
      Vector((20L, 30L))
    )
    assertEquals(first.selected.included.get.points, Vector.empty)
    assertEquals(first.selected.excluded.get.points.map(_.at), Vector(20L))
    assertEquals(second.selected.included.get.points.map(_.at), Vector(20L))
    assertEquals(second.selected.excluded.get, first.selected.included.get)
    assertNotEquals(first.coordinate.occurrence, second.coordinate.occurrence)
    val (ambiguous, same) = repeated(true)
    assertEquals(
      TemporalSupport
        .read(ambiguous, e(1), Selection.Occurrence(same.identity, OccurrenceId.unsafe("same"))),
      Left(Refusal.AmbiguousOccurrence)
    )
  }
  test("outside an occurrence remains known excluded geometry rather than unlocated support") {
    val value = source(true)
    val result =
      read(value, e(7), Selection.Occurrence(mappings(0).identity, OccurrenceId.unsafe("part-0")))
    assertEquals(result.selected.state, State.OutsideOccurrence)
    assertEquals(result.selected.included, None)
    assertEquals(result.selected.excluded.get.bounds, (60L, 70L))
  }
  test("ticks beyond 2^53 are exact and display bounds never fill interval gaps") {
    val start = 9007199254740993L
    val bundle = SourceBundle
      .filmEdition(EditionId.unsafe("large"), Checksum.ofText("large"), start, start + 10, timebase)
      .toOption
      .get
    val evidence = EvidenceSupport
      .of(
        bundle,
        Vector(
          EvidenceAnchor.MediaTime(
            bundle.id,
            bundle.streams.head.id,
            bundle.primaryAxis.id,
            PlaybackIntervalSet
              .of(
                Vector((start, start + 1), (start + 4, start + 6)).map((a, b) =>
                  PlaybackInterval.on(bundle.primaryAxis, a, b).toOption.get
                )
              )
              .toOption
              .get
          )
        )
      )
      .toOption
      .get
    val value = SourceRepresentation
      .of(
        view.copy(nodes = Vector(nodes.head)),
        NonEmptyVector.one(BundleEntry.media(bundle)),
        None,
        Map(e(1) -> SourceSupportStatus.located(TypedSupport.Anchored(evidence)))
      )
      .toOption
      .get
    val result = read(value, e(1), Selection.Part(bundle.identity)).selected.included.get
    assertEquals(result.bounds, (start, start + 6))
    assertEquals(
      result.intervals.map(i => (i.start, i.endExclusive)),
      Vector((start, start + 1), (start + 4, start + 6))
    )
  }
