package storymodel4s.view

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.story.RelationLayer

class CodexCompilerSuite extends FunSuite:
  private val config = Checksum.ofText("codex-compiler-suite")
  private val provenance = Provenance.deterministic("codex-compiler-suite", config)

  private def claimAddress(value: String): Address =
    Addressable[CoreRef].address(CoreRef.Claim(ClaimId.unsafe(value)))

  private def annotation(
      value: String,
      span: TextSpan,
      priority: AnnotationPriority = AnnotationPriority.Default,
      upstream: Vector[Address] = Vector.empty
  ): TextAnnotation =
    TextAnnotation
      .of(
        claimAddress(value),
        SpanSet.one(span),
        AnnotationKind.Claim,
        priority,
        AuditRecord.of(upstream, provenance)
      )
      .fold(error => fail(error.message), identity)

  test("CodexSpec enforces its typed annotation-channel budget"):
    val budget = ChannelBudget.of(1, 1).fold(error => fail(error.message), identity)
    val channels = Vector(
      AnnotationChannel(AnnotationKind.Claim, AnnotationPriority.Default),
      AnnotationChannel(AnnotationKind.Entity, AnnotationPriority.Default)
    )

    assert(CodexSpec.of(channels, budget).isLeft)
    assert(CodexSpec.of(channels.take(1), budget).isRight)
    assert(ChannelBudget.of(AnnotationKind.values.length + 1, 0).isLeft)
    assert(LanePolicy.of(LanePolicy.Maximum + 1).isLeft)

  test("CommonViewState admits only addresses from the closed visualization seam"):
    val valid = claimAddress("claim:selection")
    val foreign = Address(
      ModuleTag.unsafe("foreign"),
      AddressKind.unsafe("object"),
      AddressKey.of("x")
    )

    assert(CommonViewState.of(selection = Set(valid), focus = Some(valid)).isRight)
    assert(CommonViewState.of(selection = Set(foreign)).isLeft)

  test("duplicate semantic proposals coalesce at maximum priority and merge upstream audit refs"):
    val firstUpstream = claimAddress("claim:upstream-a")
    val secondUpstream = claimAddress("claim:upstream-b")
    val low = annotation(
      "claim:duplicate",
      TextSpan.unsafe(0, 5),
      AnnotationPriority.unsafe(10),
      Vector(firstUpstream)
    )
    val high = annotation(
      "claim:duplicate",
      TextSpan.unsafe(0, 5),
      AnnotationPriority.unsafe(900),
      Vector(secondUpstream)
    )

    val result =
      TextAnnotation.coalesce(Vector(low, high)).fold(error => fail(error.message), identity)

    assertEquals(result.length, 1)
    assertEquals(result.head.id, low.id)
    assertEquals(result.head.priority, AnnotationPriority.unsafe(900))
    assertEquals(result.head.audit.upstream, Vector(firstUpstream, secondUpstream).sorted)

  test("lane allocation is deterministic, bounded, and never drops overflowed annotations"):
    val annotations = Vector(
      annotation("claim:lane-a", TextSpan.unsafe(0, 10)),
      annotation("claim:lane-b", TextSpan.unsafe(0, 10)),
      annotation("claim:lane-c", TextSpan.unsafe(0, 10)),
      annotation("claim:lane-d", TextSpan.unsafe(0, 10))
    )
    val policy = LanePolicy.of(2).fold(error => fail(error.message), identity)
    val forward =
      LaneAllocation.allocate(annotations, policy).fold(error => fail(error.message), identity)
    val reversed =
      LaneAllocation
        .allocate(annotations.reverse, policy)
        .fold(error => fail(error.message), identity)

    assertEquals(forward, reversed)
    assertEquals(forward.placements.size, annotations.size)
    assertEquals(forward.overflow.size, 2)
    assertEquals(
      forward.placements.count(_.slot match
        case LaneSlot.Lane(_)  => true
        case LaneSlot.Overflow => false),
      2
    )
    assertEquals(forward.receipt.algorithm, LaneAlgorithm.IntervalFirstFit)
    assertEquals(forward.receipt.policy, policy)

  test("compiler configuration checksums ignore Set and channel input order"):
    val first = claimAddress("claim:first")
    val second = claimAddress("claim:second")
    val stateA = CommonViewState
      .of(
        selection = Set(first, second),
        focus = Some(first),
        relationLayers = Set(RelationLayer.Causal, RelationLayer.Reference)
      )
      .fold(error => fail(error.message), identity)
    val stateB = CommonViewState
      .of(
        selection = Set(second, first),
        focus = Some(first),
        relationLayers = Set(RelationLayer.Reference, RelationLayer.Causal)
      )
      .fold(error => fail(error.message), identity)
    val budget = ChannelBudget.All
    val channelA = AnnotationChannel(AnnotationKind.Claim, AnnotationPriority.unsafe(10))
    val channelB = AnnotationChannel(AnnotationKind.Relation, AnnotationPriority.unsafe(20))
    val specA = CodexSpec
      .of(Vector(channelA, channelB), budget)
      .fold(error => fail(error.message), identity)
    val specB = CodexSpec
      .of(Vector(channelB, channelA), budget)
      .fold(error => fail(error.message), identity)

    assertEquals(
      CodexCompiler.configurationChecksum(stateA, specA),
      CodexCompiler.configurationChecksum(stateB, specB)
    )

  test("shared evidence visibility is transitive, cycle-guarded, and clips future support"):
    val fingerprint = Fingerprint.unsafe("rule:visibility-test:1")
    val stage = StageId.unsafe("visibility-test")
    def evidence(
        id: String,
        spans: Option[SpanSet],
        upstream: Set[ClaimId] = Set.empty
    ): Evidence =
      Evidence(EvidenceId.unsafe(id), spans, upstream, fingerprint, stage)
    def meta(id: String, status: EpistemicStatus, item: Evidence): ClaimMeta =
      ClaimMeta
        .of(
          ClaimId.unsafe(id),
          status,
          Credence.unsafeRaw(1.0),
          NonEmptyVector.one(item),
          provenance
        )
        .fold(error => fail(error.message), identity)

    val root = meta(
      "claim:root",
      EpistemicStatus.SurfaceExplicit,
      evidence("evidence:root", Some(SpanSet.one(TextSpan.unsafe(0, 4))))
    )
    val derived = meta(
      "claim:derived",
      EpistemicStatus.StructurallyDerived,
      evidence("evidence:derived", None, Set(root.id))
    )
    val late = meta(
      "claim:late",
      EpistemicStatus.SurfaceExplicit,
      evidence("evidence:late", Some(SpanSet.one(TextSpan.unsafe(10, 12))))
    )
    val cycleAId = ClaimId.unsafe("claim:cycle-a")
    val cycleBId = ClaimId.unsafe("claim:cycle-b")
    val cycleA = meta(
      cycleAId.value,
      EpistemicStatus.StructurallyDerived,
      evidence("evidence:cycle-a", None, Set(cycleBId))
    )
    val cycleB = meta(
      cycleBId.value,
      EpistemicStatus.StructurallyDerived,
      evidence("evidence:cycle-b", None, Set(cycleAId))
    )
    val unknown = meta(
      "claim:unknown-upstream",
      EpistemicStatus.StructurallyDerived,
      evidence("evidence:unknown", None, Set(ClaimId.unsafe("claim:not-in-ledger")))
    )
    val ledger = ClaimLedger.empty
      .addAll(Vector(root, derived, late, cycleA, cycleB, unknown))
      .fold(error => fail(error.message), identity)

    val visibleAtFour = EvidenceVisibility.visibleClaims(4, ledger)
    assertEquals(visibleAtFour, Set(root.id, derived.id))
    assert(EvidenceVisibility.visibleClaims(12, ledger).contains(late.id))
    val support = SpanSet.unsafe(
      SpanRef(TextSpan.unsafe(0, 4)),
      SpanRef(TextSpan.unsafe(10, 12))
    )
    assertEquals(
      EvidenceVisibility.clipSupport(support, EpistemicHorizon.ReaderAt(4)),
      Some(SpanSet.one(TextSpan.unsafe(0, 4)))
    )
