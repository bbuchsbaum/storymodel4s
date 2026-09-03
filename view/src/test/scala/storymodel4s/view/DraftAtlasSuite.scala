package storymodel4s.view

import munit.FunSuite
import storymodel4s.acquire.{ClaimFamily, ResolutionFailure}
import storymodel4s.core.*
import storymodel4s.document.{
  AbstentionReason,
  ChartNodeRef,
  CoordinatedBranch,
  DerivationGap,
  DerivationGapReason,
  DocRef,
  FillerCounts,
  NarrativeCandidateAddress,
  SentenceCoverage
}
import storymodel4s.proposition.{ConceptId, ConceptKind, SourceRole}
import storymodel4s.story.*

/** Contract courts for the draft Atlas path: what a partial model may and may not claim.
  *
  * Fixture-free by design; the machine-built War of the Ghosts model is compiled in
  * `pipeline`'s `WarOfTheGhostsDraftAtlasSuite`, which is the only place a real fifty-sentence
  * compilation exists. What is pinned here is the seam: that a draft receipt and a validated
  * receipt cannot be swapped, that a promotion record must describe the bundle it travels with,
  * that every recorded absence becomes exactly one mark, and that the evidence law (V-E3) refuses a
  * mark sitting on text the model does not support.
  */
class DraftAtlasSuite extends FunSuite:
  private val text = "There were people at Egulac. One night two young men went to hunt seals."

  private val source =
    StorySource.fromText(text).fold(e => fail(e.message), identity)
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val sentences = atlas.sentences

  private val model: StoryModel[ModelStatus.Draft] =
    StoryModel.draft(
      source,
      atlas,
      NarrativeGraph.empty,
      NarrativeHierarchy(Vector.empty, Vector.empty),
      DiscourseTrajectory.empty
    )

  private def node(index: Int, concept: String): ChartNodeRef =
    ChartNodeRef(sentences(index).id, ConceptId.unsafe(concept))

  private def gapAt(
      target: NarrativeCandidateAddress,
      reason: DerivationGapReason,
      family: ClaimFamily = ClaimFamily.ContextAssignment
  ): DerivationGap =
    DerivationGap(
      StageId.unsafe("narrative-compile"),
      family,
      target,
      reason,
      Set.empty,
      Vector.empty
    )

  private val unresolved = ResolutionFailure.NoProposal

  private def outcomeOf(violations: Vector[Violation]): ValidationOutcome =
    ValidationOutcome(ValidationReport(violations), None)

  private val spec =
    AtlasSpec(ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Hidden), ThreadPolicy.Selected)
  private val state = CommonViewState.empty
  private val config = AtlasCompiler.configurationChecksum(state, spec)

  private def draftOf(
      gaps: Vector[DerivationGap] = Vector.empty,
      violations: Vector[Violation] = Vector.empty,
      coverage: Vector[SentenceCoverage] = Vector.empty
  ): DraftModel =
    DraftModel.of(model, outcomeOf(violations), gaps, coverage)

  private def provenanceFor(draft: DraftModel): ViewProvenance =
    ViewProvenance
      .draftBuild(draft, "draft-atlas-suite", config)
      .fold(e => fail(e.message), identity)

  private def sceneOf(draft: DraftModel): NarrativeScene =
    AtlasCompiler(provenanceFor(draft))
      .compileDraft(draft, state, spec)
      .fold(error => fail(error.message), identity)

  private def gapMarks(scene: NarrativeScene): Vector[VisualPrimitive.Gap] =
    scene.marks.collect { case g: VisualPrimitive.Gap => g }

  test("a draft receipt and a validated receipt cannot be swapped"):
    val draft = draftOf()
    val draftProvenance = provenanceFor(draft)
    val fixtureProvenance = ViewProvenance
      .fixture(source.canonicalChecksum, "draft-atlas-suite", config)
      .fold(e => fail(e.message), identity)

    // A draft scene refuses a receipt that does not declare the draft basis.
    val wrongBasis = AtlasCompiler(fixtureProvenance).compileDraft(draft, state, spec)
    assert(wrongBasis.isLeft, "a fixture receipt must not produce a draft scene")
    assert(wrongBasis.left.exists(_.message.contains("draft build")))

    // And the validated entry point refuses a draft receipt, so a draft scene cannot be minted
    // through the path whose receipt says the model was promoted.
    assertEquals(draftProvenance.basis, ViewBasis.DraftBuild)

  test("a promotion record must describe the bundle it travels with"):
    val quiet = draftOf()
    val noisy = draftOf(gaps = Vector(gapAt(
      NarrativeCandidateAddress.ContextAssignment(node(0, "d")),
      DerivationGapReason.Unresolved(unresolved)
    )))
    val quietReceipt = provenanceFor(quiet)

    val mismatched = AtlasCompiler(quietReceipt).compileDraft(noisy, state, spec)
    assert(mismatched.isLeft, "a receipt reporting no gaps must not render a model with one")
    assert(mismatched.left.exists(_.message.contains("does not describe this draft")))
    assertEquals(sceneOf(noisy).provenance.draft.map(_.gapCount), Some(1))

  test("a provenance carries a promotion record exactly when it declares a draft build"):
    val promotion = DraftPromotion.from(outcomeOf(Vector.empty), Vector.empty)
    val draftWithout = ViewProvenance.of(
      source.canonicalChecksum,
      None,
      ViewBasis.DraftBuild,
      "draft-atlas-suite",
      config,
      draft = None
    )
    val validatedWith = ViewProvenance.of(
      source.canonicalChecksum,
      Some(Checksum.ofText("receipt")),
      ViewBasis.ValidatedBuild,
      "draft-atlas-suite",
      config,
      draft = Some(promotion)
    )
    assert(draftWithout.isLeft, "a draft basis with no promotion record is not a statement")
    assert(validatedWith.isLeft, "a validated basis may not carry a promotion record")

  test("the promotion record is derived from the outcome, never stated"):
    val violations = Vector(
      Violation("hierarchy.single-primary-root", Severity.Error, "segments", "no segments"),
      Violation("compiler.required-derivation", Severity.Error, "a", "unresolved:NoProposal"),
      Violation("compiler.required-derivation", Severity.Error, "b", "unresolved:NoProposal")
    )
    val promotion = DraftPromotion.from(outcomeOf(violations), Vector(gapAt(
      NarrativeCandidateAddress.StorySummary(source.id),
      DerivationGapReason.Unresolved(unresolved),
      ClaimFamily.Summary
    )))
    assertEquals(promotion.promoted, false)
    assertEquals(promotion.gapCount, 1)
    assertEquals(promotion.violationCount, 3)
    assertEquals(
      promotion.unsatisfiedLaws.map(law => (law.law, law.count.value)),
      Vector(("compiler.required-derivation", 2), ("hierarchy.single-primary-root", 1))
    )

  test("every uncertainty state has its own non-colour channel"):
    val states = UncertaintyState.values.toVector
    assertEquals(states.map(_.channel).distinct.size, states.size)
    assertEquals(
      states.map(state => (state, state.channel)).toMap,
      Map(
        UncertaintyState.Missing -> EpistemicChannel.OpenHatch,
        UncertaintyState.Alternatives -> EpistemicChannel.Fan,
        UncertaintyState.Unresolved -> EpistemicChannel.Placeholder
      )
    )
    // An unsatisfied promotion law is not a D9 uncertainty state and does not borrow one's channel.
    assert(!states.map(_.channel).contains(EpistemicChannel.Bracket))

  test("a gap's uncertainty state is classified from its own reason"):
    def stateOf(reason: DerivationGapReason): UncertaintyState = UncertaintyState.of(reason)
    assertEquals(stateOf(DerivationGapReason.Alternatives), UncertaintyState.Alternatives)
    assertEquals(stateOf(DerivationGapReason.Unresolved(unresolved)), UncertaintyState.Unresolved)
    assertEquals(stateOf(DerivationGapReason.MissingRawScore), UncertaintyState.Missing)
    assertEquals(stateOf(DerivationGapReason.MissingSpanEvidence), UncertaintyState.Missing)
    assertEquals(
      stateOf(DerivationGapReason.MissingUpstream(Vector.empty)),
      UncertaintyState.Missing
    )

  test("every derivation gap becomes exactly one mark, placed on exact surface material"):
    val chartGap = gapAt(
      NarrativeCandidateAddress.ContextAssignment(node(1, "d")),
      DerivationGapReason.Unresolved(unresolved)
    )
    val summaryGap = gapAt(
      NarrativeCandidateAddress.StorySummary(source.id),
      DerivationGapReason.Unresolved(unresolved),
      ClaimFamily.Summary
    )
    val draft = draftOf(gaps = Vector(chartGap, summaryGap))
    val scene = sceneOf(draft)
    val marks = gapMarks(scene)

    assertEquals(marks.size, draft.gaps.size)
    assertEquals(marks.size, 2)
    assertEquals(marks.map(_.identity.mark).distinct.size, 2)

    val chartMark = marks.find(_.target == chartGap.target).getOrElse(fail("no chart-node mark"))
    assertEquals(
      chartMark.address,
      Addressable[DocRef].address(DocRef.ChartNode(node(1, "d")))
    )
    assertEquals(
      chartMark.placement,
      EpistemicPlacement.AtSpans(SpanSet.one(SpanRef(Some(sentences(1).id), sentences(1).span)))
    )
    assertEquals(chartMark.state, UncertaintyState.Unresolved)
    assertEquals(chartMark.epistemicChannel, Some(EpistemicChannel.Placeholder))

    // A story summary concerns the whole work, so it claims no discourse position rather than
    // borrowing the first sentence's.
    val summaryMark = marks.find(_.target == summaryGap.target).getOrElse(fail("no summary mark"))
    assertEquals(
      summaryMark.address,
      Addressable[CoreRef].address(CoreRef.Story(source.id))
    )
    assertEquals(
      summaryMark.placement,
      EpistemicPlacement.NoDiscoursePosition(NoPositionReason.WholeWork)
    )

  test("a gap naming a surface unit the atlas does not contain claims no span"):
    val absent = SurfaceUnitId.unsafe("story:absent:s99")
    val gap = gapAt(
      NarrativeCandidateAddress.Situation(ChartNodeRef(absent, ConceptId.unsafe("z"))),
      DerivationGapReason.MissingSpanEvidence,
      ClaimFamily.SituationMention
    )
    val mark = gapMarks(sceneOf(draftOf(gaps = Vector(gap)))).head
    assertEquals(
      mark.placement,
      EpistemicPlacement.NoDiscoursePosition(NoPositionReason.UnitAbsentFromAtlas(absent))
    )

  test("a reader horizon leaves the gap visible and takes away only its span claim"):
    val gap = gapAt(
      NarrativeCandidateAddress.ContextAssignment(node(1, "d")),
      DerivationGapReason.Unresolved(unresolved)
    )
    val early = CommonViewState
      .of(horizon = EpistemicHorizon.ReaderAt(sentences(0).span.endExclusive))
      .fold(e => fail(e.message), identity)
    val earlySpec = spec
    val draft = draftOf(gaps = Vector(gap))
    val provenance = ViewProvenance
      .draftBuild(
        draft,
        "draft-atlas-suite",
        AtlasCompiler.configurationChecksum(early, earlySpec)
      )
      .fold(e => fail(e.message), identity)
    val scene = AtlasCompiler(provenance)
      .compileDraft(draft, early, earlySpec)
      .fold(error => fail(error.message), identity)
    val mark = gapMarks(scene).head
    assertEquals(
      mark.placement,
      EpistemicPlacement.NoDiscoursePosition(NoPositionReason.BeyondHorizon)
    )

  test("only a coverage row that admitted no root becomes an abstention"):
    val proposed = SentenceCoverage.Proposed(node(0, "p"), FillerCounts(1, 0, 0, 0, 0, 0))
    val abstained =
      SentenceCoverage.Abstained(node(1, "d"), AbstentionReason.FocusNotPredicate(ConceptKind.Entity))
    val scene = sceneOf(draftOf(coverage = Vector(proposed, abstained)))
    val marks = scene.marks.collect { case a: VisualPrimitive.Abstention => a }
    assertEquals(marks.size, 1)
    assertEquals(marks.head.unit, sentences(1).id)
    assertEquals(
      marks.head.reason,
      SentenceAbstention.ProviderAbstained(AbstentionReason.FocusNotPredicate(ConceptKind.Entity))
    )
    assertEquals(marks.head.uncertainty, Some(UncertaintyState.Missing))
    assertEquals(marks.head.epistemicChannel, Some(EpistemicChannel.OpenHatch))
    assertEquals(
      marks.head.placement,
      EpistemicPlacement.AtSpans(SpanSet.one(SpanRef(Some(sentences(1).id), sentences(1).span)))
    )

  test("a coordinating focus that admitted nothing keeps every branch's own refusal"):
    val branches = Vector(
      CoordinatedBranch.Abstained(node(0, "a"), SourceRole.Operand(1), AbstentionReason.NestedCoordination),
      CoordinatedBranch.Abstained(node(0, "b"), SourceRole.Operand(1), AbstentionReason.NoFocus)
    )
    val row = SentenceCoverage.Coordinated(node(0, "c"), branches)
    assertEquals(
      SentenceAbstention.from(row),
      Some(
        SentenceAbstention.CoordinationAdmittedNothing(
          Vector(AbstentionReason.NestedCoordination, AbstentionReason.NoFocus)
        )
      )
    )
    assertEquals(SentenceAbstention.from(SentenceCoverage.EmptyChart(sentences(0).id)),
      Some(SentenceAbstention.EmptyChart))
    assertEquals(SentenceAbstention.from(SentenceCoverage.NoChart(sentences(0).id)),
      Some(SentenceAbstention.NoChart))

  test("every promotion violation becomes one mark, and an unresolvable subject claims no text"):
    val violations = Vector(
      Violation("hierarchy.single-primary-root", Severity.Error, "segments", "no segments"),
      Violation("compiler.required-derivation", Severity.Error, "a", "unresolved:NoProposal")
    )
    val scene = sceneOf(draftOf(violations = violations))
    val marks = scene.marks.collect { case l: VisualPrimitive.UnsatisfiedLaw => l }
    assertEquals(marks.size, 2)
    assertEquals(marks.map(_.identity.mark).distinct.size, 2)
    assertEquals(marks.map(_.epistemicChannel).distinct, Vector(Some(EpistemicChannel.Bracket)))
    assert(marks.forall(_.uncertainty.isEmpty), "a violated law is not a D9 uncertainty state")
    assert(
      marks.forall(
        _.placement == EpistemicPlacement.NoDiscoursePosition(NoPositionReason.WholeWork)
      ),
      "a violation whose path names no addressable object claims no words"
    )

  test("two identical violations keep distinct mark identities"):
    val same = Violation("hierarchy.single-primary-root", Severity.Error, "segments", "no segments")
    val scene = sceneOf(draftOf(violations = Vector(same, same)))
    val marks = scene.marks.collect { case l: VisualPrimitive.UnsatisfiedLaw => l }
    assertEquals(marks.size, 2)
    assertEquals(marks.map(_.identity.mark).distinct.size, 2)

  test("V-E3 refuses an absence mark sitting on text the model does not support"):
    val draft = draftOf()
    val compiler = AtlasCompiler(provenanceFor(draft))
    val unit = sentences(0)
    val address = Addressable[DocRef].address(DocRef.ChartNode(node(0, "d")))
    def gapMark(spans: SpanSet): VisualPrimitive =
      VisualPrimitive.Gap(
        VisualIdentity.of(address, NarrativeLevel.Scene, MarkId.unsafe("forged")),
        ClaimFamily.ContextAssignment,
        NarrativeCandidateAddress.ContextAssignment(node(0, "d")),
        DerivationGapReason.Unresolved(unresolved),
        UncertaintyState.Unresolved,
        EpistemicPlacement.AtSpans(spans)
      )

    val exact = SpanSet.one(SpanRef(Some(unit.id), unit.span))
    assert(compiler.checkEvidence(model, gapMark(exact)).isRight)

    // Same words, shifted by one: not any surface unit's exact span, so the mark is refused.
    val shifted = SpanSet.one(
      SpanRef(Some(unit.id), TextSpan.unsafe(unit.span.start + 1, unit.span.endExclusive))
    )
    assert(compiler.checkEvidence(model, gapMark(shifted)).isLeft)

    // A span naming no unit at all cannot be checked against the atlas and is refused.
    val anonymous = SpanSet.one(SpanRef(None, unit.span))
    assert(compiler.checkEvidence(model, gapMark(anonymous)).isLeft)

  test("V-E3 refuses an unsatisfied-law mark citing words its subject does not"):
    val draft = draftOf()
    val compiler = AtlasCompiler(provenanceFor(draft))
    val unit = sentences(0)
    val violation =
      Violation("hierarchy.single-primary-root", Severity.Error, "segments", "no segments")
    val mark = VisualPrimitive.UnsatisfiedLaw(
      VisualIdentity.of(
        Addressable[CoreRef].address(CoreRef.Story(source.id)),
        NarrativeLevel.Scene,
        MarkId.unsafe("forged-law")
      ),
      violation,
      EpistemicPlacement.AtSpans(SpanSet.one(SpanRef(Some(unit.id), unit.span)))
    )
    assert(
      compiler.checkEvidence(model, mark).isLeft,
      "a law whose violation names no subject may not cite that subject's words"
    )

  test("the textual twin renders the draft receipt and every absence mark"):
    val gap = gapAt(
      NarrativeCandidateAddress.ContextAssignment(node(1, "d")),
      DerivationGapReason.Unresolved(unresolved)
    )
    val abstained =
      SentenceCoverage.Abstained(node(0, "d"), AbstentionReason.FocusNotPredicate(ConceptKind.Entity))
    val violation =
      Violation("hierarchy.single-primary-root", Severity.Error, "segments", "no segments")
    val twin = sceneOf(
      draftOf(gaps = Vector(gap), violations = Vector(violation), coverage = Vector(abstained))
    ).textualTwin

    assert(twin.contains("Basis: draft build"), twin)
    assert(twin.contains("Draft promotion"), twin)
    assert(twin.contains("promotable: false"), twin)
    assert(twin.contains("derivation gaps: 1"), twin)
    assert(twin.contains("hierarchy.single-primary-root Error x1"), twin)
    assert(twin.contains("  gap "), twin)
    assert(twin.contains("state=Unresolved channel=Placeholder"), twin)
    assert(twin.contains("  abstention "), twin)
    assert(twin.contains("reason=provider-abstained:focus-not-predicate:Entity"), twin)
    assert(twin.contains("  unsatisfied-law "), twin)
    assert(twin.contains("channel=Bracket"), twin)

  test("a draft scene is a pure function of its inputs whatever order they arrive in"):
    val gaps = Vector(
      gapAt(
        NarrativeCandidateAddress.ContextAssignment(node(1, "d")),
        DerivationGapReason.Unresolved(unresolved)
      ),
      gapAt(
        NarrativeCandidateAddress.ParticipantCoverage(node(0, "p")),
        DerivationGapReason.MissingRawScore,
        ClaimFamily.ParticipantCoverage
      )
    )
    val forward = sceneOf(draftOf(gaps = gaps))
    val reversed = sceneOf(draftOf(gaps = gaps.reverse))
    assertEquals(forward.textualTwin, reversed.textualTwin)
