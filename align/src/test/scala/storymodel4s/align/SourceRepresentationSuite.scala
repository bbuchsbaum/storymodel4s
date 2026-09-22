package storymodel4s.align

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.features.MissingReason

class SourceRepresentationSuite extends FunSuite:
  import SherlockShapedSource.*
  private def compositionRefused(value: Either[MappingRefusal, ?]): Unit = value match
    case Left(MappingRefusal.InvalidComposition(_)) => ()
    case other => fail(s"expected composition refusal, got $other")

  test("Sherlock-shaped rows resolve to part bundles") {
    val value = source(true)
    assertEquals(
      value.target(e(2)).get.axisMembership,
      Map(
        a.primaryAxis.id -> BundleRole.Part(a.id),
        composed.primaryAxis.id -> BundleRole.Composition
      )
    )
    assertEquals(value.target(e(7)).get.axisMembership(b.primaryAxis.id), BundleRole.Part(b.id))
    val exact =
      value.target(e(8)).get.sourceSupport.asInstanceOf[SourceSupportStatus.Located].support
    val anchors = exact.asInstanceOf[TypedSupport.Anchored].support.anchors.toVector
    assertEquals(
      anchors.collect { case EvidenceAnchor.MediaPoint(_, _, at) => at.at },
      Vector(30L, 70L)
    )
    assertNotEquals(
      value.target(e(2)).get.sourceSupport.asInstanceOf[SourceSupportStatus.Located].support,
      nodes(1).support
    )
  }
  test("axis outside inventory refuses") {
    assertEquals(
      SourceRepresentation
        .of(view, NonEmptyVector.one(BundleEntry.media(composed)), None, physical(true))
        .swap
        .toOption
        .get,
      MappingRefusal.ForeignAxis(a.primaryAxis.id)
    )
    assert(SourceRepresentation.of(view, bundles, Some(composition), physical(true)).isRight)
  }
  test("ambiguous axis refuses") {
    val oneView = view.copy(nodes = Vector(nodes.head))
    val onePhysical = Map(e(1) -> physical(false)(e(1)))
    assertEquals(
      SourceRepresentation
        .of(
          oneView,
          NonEmptyVector.of(BundleEntry.media(a), BundleEntry.media(a)),
          None,
          onePhysical
        )
        .left
        .toOption,
      Some(MappingRefusal.AmbiguousAxis(a.primaryAxis.id))
    )
    assert(
      SourceRepresentation
        .of(oneView, NonEmptyVector.one(BundleEntry.media(a)), None, onePhysical)
        .isRight
    )
  }
  test("composition covers exactly its parts") {
    compositionRefused(DeclaredComposition.of(composed, NonEmptyVector.one(a)))
    val incomplete = SourceBundle
      .of(
        composed.edition,
        composed.sourceKind,
        composed.streams,
        composed.primaryAxis,
        composed.authorityTracks,
        mappings.take(1)
      )
      .toOption
      .get
    compositionRefused(DeclaredComposition.of(incomplete, parts))
    assert(DeclaredComposition.of(composed, parts).isRight)
  }
  test("composition over an unlisted part refuses") {
    val unlocated =
      nodes.map(n => n.ref -> SourceSupportStatus.unlocated(UnlocatedReason.NoLocusInSource)).toMap
    compositionRefused(
      SourceRepresentation.of(
        view,
        NonEmptyVector.of(BundleEntry.media(a), BundleEntry.media(part("foreign"))),
        Some(composition),
        unlocated
      )
    )
    assert(SourceRepresentation.of(view, bundles, Some(composition), unlocated).isRight)
  }
  test("cross-part target refuses without composition") {
    // A bundle's own mappings do not silently declare the workspace's part composition.
    val listed = NonEmptyVector
      .of[BundleEntry](BundleEntry.media(a), BundleEntry.media(b), BundleEntry.media(composed))
    assertEquals(
      SourceRepresentation
        .of(crossView, listed, None, Map(cross -> crossSupport))
        .swap
        .toOption
        .get,
      MappingRefusal.CrossPartWithoutComposition(cross)
    )
    assert(
      SourceRepresentation
        .of(crossView, bundles, Some(composition), Map(cross -> crossSupport))
        .isRight
    )
  }
  test("e2 to e7 Incomparable without declaration and Before under composition") {
    assertEquals(source(false).order(a.primaryAxis.id, b.primaryAxis.id), PartOrder.Incomparable)
    assertEquals(source(false).order(b.primaryAxis.id, a.primaryAxis.id), PartOrder.Incomparable)
    assertEquals(source(true).order(a.primaryAxis.id, b.primaryAxis.id), PartOrder.Before)
    assertEquals(source(true).order(b.primaryAxis.id, a.primaryAxis.id), PartOrder.After)
    assertEquals(source(false).order(a.primaryAxis.id, a.primaryAxis.id), PartOrder.Same)
  }
  test("duplicate target ref refuses") {
    assertEquals(
      SourceRepresentation
        .of(view.copy(nodes = nodes :+ nodes.head), bundles, None, physical(false))
        .swap
        .toOption
        .get,
      MappingRefusal.DuplicateTarget(nodes.head.ref)
    )
  }
  test("g1 is Partial and does not fabricate e4 support") {
    val value = source(false)
    val missing = value
      .target(group)
      .get
      .supportCoverage
      .asInstanceOf[SupportCoverage.Partial]
      .missing
      .toSortedSet
      .toSet
    assertEquals(missing, Set(e(4)))
    assertEquals(
      value.target(e(4)).get.sourceSupport.asInstanceOf[SourceSupportStatus.Unlocated].reason,
      UnlocatedReason.NoLocusInSource
    )
    assert(value.target(e(2)).get.supportCoverage.isInstanceOf[SupportCoverage.Complete])
  }
  test("unlocated leaf is Unknown") {
    assertEquals(
      source(false).target(e(4)).get.supportCoverage.asInstanceOf[SupportCoverage.Unknown].reason,
      CoverageUnknownReason.TargetUnlocated
    )
  }
  test("permuted view has same digest") {
    val permuted = SourceRepresentation
      .of(view.copy(nodes = nodes.reverse), bundles.reverse, None, physical(false))
      .toOption
      .get
    assertEquals(permuted.digest, source(false).digest)
  }
  test("Declared twin changes scopeDigest") {
    val twin = view.copy(nodes = nodes.map(_.copy(propositional = PropositionalScope.Declared)))
    assertEquals(ViewFingerprint.of(twin), ViewFingerprint.of(view))
    val changed = SourceRepresentation.of(twin, bundles, None, physical(false)).toOption.get
    assertNotEquals(changed.scopeDigest, source(false).scopeDigest)
    assertNotEquals(changed.digest, source(false).digest)
  }
  test("scope preserves complete custom reasons and optional presence") {
    def changed(scope: PropositionalScope, predicate: Option[String]): SourceRepresentation =
      SourceRepresentation
        .of(
          view.copy(nodes = nodes.map(_.copy(propositional = scope, predicate = predicate))),
          bundles,
          None,
          physical(false)
        )
        .toOption
        .get
    assertNotEquals(
      changed(PropositionalScope.Undeclared(MissingReason.Custom("a,b", "c")), None).scopeDigest,
      changed(PropositionalScope.Undeclared(MissingReason.Custom("a", "b,c")), None).scopeDigest
    )
    assertNotEquals(
      changed(PropositionalScope.Declared, None).scopeDigest,
      changed(PropositionalScope.Declared, Some("")).scopeDigest
    )
  }
  test("physical inventory must be total and full bundle identities match") {
    assertEquals(
      SourceRepresentation.of(view, bundles, None, physical(false).removed(e(4))).swap.toOption.get,
      MappingRefusal.PhysicalInventoryMismatch
    )
    val foreign = SourceRepresentation.of(
      view,
      NonEmptyVector.of(BundleEntry.media(part("other")), BundleEntry.media(b)),
      None,
      physical(false)
    )
    assertEquals(foreign.swap.toOption.get, MappingRefusal.ForeignBundle(a.identity))
  }
  test("text support stays exact and requires one declared text identity") {
    val physical = nodes.map(n => n.ref -> SourceSupportStatus.located(n.support)).toMap
    val text = BundleEntry.text(Checksum.ofText("declared canonical source"))
    val source =
      SourceRepresentation.of(view, NonEmptyVector.one(text), None, physical).toOption.get
    assertEquals(
      source.target(e(2)).get.sourceSupport.asInstanceOf[SourceSupportStatus.Located].support,
      nodes(1).support
    )
    assert(source.target(e(2)).get.axisMembership.isEmpty)
    assertEquals(
      SourceRepresentation.of(view, bundles, None, physical).swap.toOption.get,
      MappingRefusal.InvalidTextSource
    )
  }

  test("distinct parts sharing a legacy bundle ID refuse") {
    val variantAxis = SourceBundle
      .editionPlaybackAxis(a.edition.get, a.streams, a.authorityTracks, 0L, 80L, timebase)
      .toOption
      .get
    val segment = CompositionSegment
      .of(
        PlaybackInterval.on(a.primaryAxis, 0L, 40L).toOption.get,
        PlaybackInterval.on(variantAxis, 0L, 40L).toOption.get,
        OccurrenceId.unsafe("same-id-part")
      )
      .toOption
      .get
    val receipt = SourceDerivationReceipt
      .of("same-id-witness/v1", "different coordinates", Vector(a.identity))
      .toOption
      .get
    val mapping =
      TrackComposition.of(a.primaryAxis.id, variantAxis.id, Vector(segment), receipt).toOption.get
    val variant = SourceBundle
      .of(a.edition, a.sourceKind, a.streams, variantAxis, a.authorityTracks, Vector(mapping))
      .toOption
      .get
    assertEquals(a.id, variant.id)
    assertNotEquals(a.identity, variant.identity)
    assertNotEquals(a.primaryAxis.id, variant.primaryAxis.id)
    val evidence = EvidenceSupport
      .of(
        variant,
        Vector(
          EvidenceAnchor.MediaTime(
            variant.id,
            a.streams.head.id,
            a.primaryAxis.id,
            PlaybackIntervalSet.one(PlaybackInterval.on(a.primaryAxis, 0L, 10L).toOption.get)
          ),
          EvidenceAnchor.MediaTime(
            variant.id,
            a.streams.head.id,
            variant.primaryAxis.id,
            PlaybackIntervalSet.one(PlaybackInterval.on(variant.primaryAxis, 0L, 10L).toOption.get)
          )
        )
      )
      .toOption
      .get
    val oneView = view.copy(nodes = Vector(nodes.head))
    val physical = Map(e(1) -> SourceSupportStatus.located(TypedSupport.Anchored(evidence)))
    assertEquals(
      SourceRepresentation
        .of(
          oneView,
          NonEmptyVector.of(BundleEntry.media(a), BundleEntry.media(variant)),
          None,
          physical
        )
        .left
        .toOption,
      Some(MappingRefusal.AmbiguousBundle(a.id))
    )
    assert(
      SourceRepresentation
        .of(
          oneView,
          NonEmptyVector.one(BundleEntry.media(a)),
          None,
          Map(e(1) -> SherlockShapedSource.physical(false)(e(1)))
        )
        .isRight
    )
  }
