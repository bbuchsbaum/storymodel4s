package storymodel4s.view

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.acquire.{ClaimFamily, ResolutionFailure}
import storymodel4s.core.*
import storymodel4s.document.{
  AbstentionReason,
  ChartNodeRef,
  DerivationGap,
  DerivationGapReason,
  DocRef,
  FillerCounts,
  NarrativeCandidateAddress,
  SentenceCoverage
}
import storymodel4s.proposition.{ConceptId, ConceptKind}
import storymodel4s.story.*

/** Contract courts for the draft Codex path: what a partial model's reading view may claim.
  *
  * Fixture-free by design; the machine-built War of the Ghosts model is compiled in `pipeline`'s
  * `WarOfTheGhostsDraftCodexSuite`, which is the only place a real fifty-sentence compilation
  * exists. What is pinned here is the seam: that a draft receipt and a validated receipt cannot be
  * swapped, that an absence channel must carry the record of a failure, that every absence the
  * draft holds is either an annotation over exact words or an entry in the unplaced ledger, and
  * that a reader can tell reported speech from narration by the annotation's own target.
  */
class DraftCodexSuite extends FunSuite:
  private val text =
    "There were people at Egulac. \"We are going up the river,\" they said. One night two young men went to hunt seals."

  private val source = StorySource.fromText(text).fold(e => fail(e.message), identity)
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val sentences = atlas.sentences

  private def meta(id: String, spans: SpanSet): ClaimMeta =
    ClaimMeta.unsafe(
      ClaimId.unsafe(s"claim:$id"),
      EpistemicStatus.LinguisticallyEntailed,
      Credence.unsafeRaw(1.0),
      NonEmptyVector.one(
        Evidence(
          EvidenceId.unsafe(s"ev:$id"),
          Some(spans),
          Set.empty,
          Fingerprint.unsafe("test:draft-codex-suite:1"),
          StageId.unsafe("test-draft-codex")
        )
      ),
      Provenance.deterministic("draft-codex-suite", Checksum.ofText("draft-codex-suite"))
    )

  private def spansOf(indices: Int*): SpanSet =
    SpanSet
      .of(indices.toVector.map(index => SpanRef(Some(sentences(index).id), sentences(index).span)))
      .getOrElse(fail("no spans"))

  /** The narrated world holds the first and last sentences; the quotation in the middle is a speech
    * frame under it. The narrated world's support is therefore two separate runs, not one hull.
    */
  private val narrated: ContextFrame =
    ContextFrame(
      ContextId.unsafe("context-root:test"),
      None,
      ContextKind.NarratedWorld,
      spansOf(0, 2),
      meta("context-root", spansOf(0, 2))
    )

  private val reported: ContextFrame =
    ContextFrame(
      ContextId.unsafe("context-speech:test"),
      Some(narrated.id),
      ContextKind.Speech(ContextHolder.Unattributed(HolderGap.NoCandidate)),
      spansOf(1),
      meta("context-speech", spansOf(1))
    )

  private val model: StoryModel[ModelStatus.Draft] =
    StoryModel.draft(
      source,
      atlas,
      NarrativeGraph.empty.copy(contexts =
        Map(narrated.id -> narrated, reported.id -> reported)
      ),
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

  private val spec = CodexSpec
    .of(
      Vector(AnnotationChannel(AnnotationKind.Context, AnnotationPriority.unsafe(500))),
      ChannelBudget.All
    )
    .fold(e => fail(e.message), identity)
  private val state = CommonViewState.empty
  private val config = CodexCompiler.configurationChecksum(state, spec)

  private def draftOf(
      gaps: Vector[DerivationGap] = Vector.empty,
      violations: Vector[Violation] = Vector.empty,
      coverage: Vector[SentenceCoverage] = Vector.empty
  ): DraftModel =
    DraftModel.of(model, outcomeOf(violations), DerivationRecord.Reported(gaps, coverage))

  private def provenanceFor(draft: DraftModel): ViewProvenance =
    ViewProvenance
      .draftBuild(draft, "draft-codex-suite", config)
      .fold(e => fail(e.message), identity)

  private def flowOf(draft: DraftModel): CodexFlow =
    CodexCompiler(provenanceFor(draft))
      .compileDraft(draft, state, spec)
      .fold(error => fail(error.message), identity)

  private def absences(flow: CodexFlow): Vector[TextAnnotation] =
    flow.annotations.filter(_.absence.isDefined)

  private def ledgerOf(flow: CodexFlow): DraftAbsenceLedger =
    flow.draft.getOrElse(fail("a draft flow carries an absence ledger"))

  test("a draft receipt and a validated receipt cannot be swapped"):
    val draft = draftOf()
    val fixtureProvenance = ViewProvenance
      .fixture(source.canonicalChecksum, "draft-codex-suite", config)
      .fold(e => fail(e.message), identity)

    val wrongBasis = CodexCompiler(fixtureProvenance).compileDraft(draft, state, spec)
    assert(wrongBasis.isLeft, "a fixture receipt must not produce a draft flow")
    assert(wrongBasis.left.exists(_.message.contains("draft build")))

    val promoted = StoryValidator
      .validate(model)
      .validated
      .getOrElse(fail(StoryValidator.validate(model).report.render))
    val wrongPath = CodexCompiler(provenanceFor(draft)).compile(promoted, state, spec)
    assert(wrongPath.isLeft, "a validated model must not be compiled under a draft receipt")
    assert(wrongPath.left.exists(_.message.contains("use compileDraft")))

  test("a promotion record must describe the bundle it travels with"):
    val quiet = draftOf()
    val noisy = draftOf(gaps =
      Vector(
        gapAt(
          NarrativeCandidateAddress.ContextAssignment(node(0, "d")),
          DerivationGapReason.Unresolved(unresolved)
        )
      )
    )
    val mismatched = CodexCompiler(provenanceFor(quiet)).compileDraft(noisy, state, spec)
    assert(mismatched.isLeft, "a receipt reporting no gaps must not render a model with one")
    assert(mismatched.left.exists(_.message.contains("does not describe this draft")))
    assertEquals(flowOf(noisy).provenance.draft.flatMap(_.gapCount), Some(1))

  test("an absence channel must carry the record of a failure, and only its own"):
    val target = Addressable[DocRef].address(DocRef.ChartNode(node(0, "d")))
    val support = spansOf(0)
    val audit = AuditRecord.deterministic("draft-codex-suite", config)
    val gap = DraftAbsence
      .enumerate(
        draftOf(gaps =
          Vector(
            gapAt(
              NarrativeCandidateAddress.ContextAssignment(node(0, "d")),
              DerivationGapReason.Unresolved(unresolved)
            )
          )
        )
      )
      .head

    // A gap channel with nothing in it would say "the model failed here" and nothing more.
    val bare =
      TextAnnotation.of(target, support, AnnotationKind.Gap, AnnotationPriority.Default, audit)
    assert(bare.isLeft, "a disclosure channel with no record is not a disclosure")
    assert(bare.left.exists(_.message.contains("must carry the record of one")))

    // And a failure may not be drawn on an ordinary channel, where it would read as a finding.
    val disguised = TextAnnotation.of(
      target,
      support,
      AnnotationKind.Claim,
      AnnotationPriority.Default,
      audit,
      Some(gap)
    )
    assert(disguised.isLeft, "a gap drawn on the claim channel reads as a claim")

    val honest = TextAnnotation.of(
      target,
      support,
      AnnotationKind.Gap,
      AnnotationPriority.Default,
      audit,
      Some(gap)
    )
    assert(honest.isRight)

  test("two failures over the same words at one node stay two annotations"):
    // The hazard this closes: an annotation is content-addressed by target, kind and support, and
    // two families failing at one chart node agree on all three. Coalescing them would show one
    // mark where the compilation recorded two failures, which reports less than the model knows.
    val chart = node(0, "d")
    val context = gapAt(
      NarrativeCandidateAddress.ContextAssignment(chart),
      DerivationGapReason.Unresolved(unresolved),
      ClaimFamily.ContextAssignment
    )
    val coverage = gapAt(
      NarrativeCandidateAddress.ParticipantCoverage(chart),
      DerivationGapReason.MissingRawScore,
      ClaimFamily.ParticipantCoverage
    )
    val flow = flowOf(draftOf(gaps = Vector(context, coverage)))
    val marks = absences(flow)
    assertEquals(marks.size, 2)
    assertEquals(marks.map(_.id).distinct.size, 2)
    assertEquals(marks.map(_.target).distinct.size, 1)
    assertEquals(marks.map(_.support).distinct.size, 1)
    assertEquals(ledgerOf(flow).total, 2)

  test("two identical violations keep distinct annotation identities"):
    val same = Violation(
      "hierarchy.situation-root-reachable",
      Severity.Error,
      "situations/0",
      "unreachable",
      Some(Addressable[StoryRef].address(StoryRef.Context(reported.id)))
    )
    val flow = flowOf(draftOf(violations = Vector(same, same)))
    val marks = absences(flow)
    assertEquals(marks.size, 2)
    assertEquals(marks.map(_.id).distinct.size, 2)

  test("an absence that concerns no words is kept in the ledger, never dropped"):
    val summary = gapAt(
      NarrativeCandidateAddress.StorySummary(source.id),
      DerivationGapReason.Unresolved(unresolved),
      ClaimFamily.Summary
    )
    val placed = gapAt(
      NarrativeCandidateAddress.ContextAssignment(node(2, "d")),
      DerivationGapReason.Unresolved(unresolved)
    )
    val law = Violation("hierarchy.single-primary-root", Severity.Error, "segments", "no segments")
    val abstained = SentenceCoverage.Abstained(
      node(0, "d"),
      AbstentionReason.FocusNotPredicate(ConceptKind.Entity)
    )
    val draft =
      draftOf(gaps = Vector(summary, placed), violations = Vector(law), coverage = Vector(abstained))
    val flow = flowOf(draft)
    val ledger = ledgerOf(flow)

    // Every absence is accounted for: two on exact words, two that honestly claim none.
    assertEquals(draft.absences.size, 4)
    assertEquals(ledger.total, 4)
    assertEquals(ledger.marked.size, 2)
    assertEquals(ledger.unplaced.size, 2)
    assertEquals(ledger.marked, absences(flow).map(_.id).sorted)
    assertEquals(
      ledger.unplaced.map(entry => (entry.absence.kind, entry.reason)).toSet,
      Set(
        (AnnotationKind.Gap, NoPositionReason.WholeWork),
        (AnnotationKind.UnsatisfiedLaw, NoPositionReason.WholeWork)
      )
    )

    // The two that name words name the exact sentence the failure concerns and nothing wider.
    val byKind = absences(flow).map(mark => mark.kind -> mark.support).toMap
    assertEquals(byKind(AnnotationKind.Gap), spansOf(2))
    assertEquals(byKind(AnnotationKind.Abstention), spansOf(0))

  test("a reader horizon takes away the span claim and keeps the absence"):
    val gap = gapAt(
      NarrativeCandidateAddress.ContextAssignment(node(2, "d")),
      DerivationGapReason.Unresolved(unresolved)
    )
    val early = CommonViewState
      .of(horizon = EpistemicHorizon.ReaderAt(sentences(0).span.endExclusive))
      .fold(e => fail(e.message), identity)
    val draft = draftOf(gaps = Vector(gap))
    val provenance = ViewProvenance
      .draftBuild(draft, "draft-codex-suite", CodexCompiler.configurationChecksum(early, spec))
      .fold(e => fail(e.message), identity)
    val flow = CodexCompiler(provenance)
      .compileDraft(draft, early, spec)
      .fold(error => fail(error.message), identity)
    assertEquals(absences(flow), Vector.empty)
    assertEquals(
      ledgerOf(flow).unplaced.map(_.reason),
      Vector(NoPositionReason.BeyondHorizon)
    )

  test("a draft declares all three disclosure channels even when one is empty"):
    val flow = flowOf(draftOf())
    assertEquals(ledgerOf(flow).total, 0)
    assert(AnnotationKind.absence.subsetOf(flow.contract.activeKinds))
    assertEquals(
      AnnotationKind.absence.map(_.wireName),
      Set("gap", "abstention", "unsatisfied-law")
    )
    // The channels do not spend the caller's budget, so a draft cannot be compiled as plain prose
    // by declaring a narrow lens.
    assertEquals(flow.contract.channelBudget, ChannelBudget.All)
    assertEquals(spec.activeKinds, Set(AnnotationKind.Context))

  test("only a draft flow may carry an absence ledger or an absence annotation"):
    val draft = draftOf(gaps =
      Vector(
        gapAt(
          NarrativeCandidateAddress.ContextAssignment(node(0, "d")),
          DerivationGapReason.Unresolved(unresolved)
        )
      )
    )
    val flow = flowOf(draft)
    val mark = absences(flow).head
    val fixtureProvenance = ViewProvenance
      .fixture(source.canonicalChecksum, "draft-codex-suite", config)
      .fold(e => fail(e.message), identity)

    val smuggled = CodexFlow.exact(source, Vector(mark), fixtureProvenance)
    assert(smuggled.isLeft, "a fixture flow must not carry a failure mark")
    assert(smuggled.left.exists(_.message.contains("absence annotations")))

    // And a draft receipt with no ledger is refused from the other side.
    val hollow = CodexFlow.exact(source, Vector.empty, provenanceFor(draft))
    assert(hollow.isLeft, "a draft receipt with no disclosure is not a draft flow")

  test("the reading view distinguishes reported speech from the narration around it"):
    val flow = flowOf(draftOf())
    val contexts = flow.annotations.filter(_.kind == AnnotationKind.Context)
    assertEquals(contexts.size, 2)

    val speechAddress = Addressable[StoryRef].address(StoryRef.Context(reported.id))
    val narratedAddress = Addressable[StoryRef].address(StoryRef.Context(narrated.id))
    val speech = contexts.find(_.target == speechAddress).getOrElse(fail("no speech annotation"))
    val narration =
      contexts.find(_.target == narratedAddress).getOrElse(fail("no narrated annotation"))

    // The quoted sentence carries the speech frame; the narration around it does not.
    assertEquals(speech.support, spansOf(1))
    assertEquals(narration.support, spansOf(0, 2))

    // The narrated world's support is its own two runs, never the hull across the quotation, so a
    // reader is never told the quoted words are narration.
    assertEquals(narration.support.spans.length, 2)
    assert(!narration.support.spans.toVector.exists(_.overlaps(sentences(1).span)))

    // And the frame's own kind is reachable from the mark, because the annotation points at the
    // model's statement of it rather than restating it.
    assertEquals(
      Addressable[StoryRef].parse(speech.target).flatMap {
        case StoryRef.Context(id) => model.graph.contexts.get(id).map(_.kind)
        case _                    => None
      },
      Some(ContextKind.Speech(ContextHolder.Unattributed(HolderGap.NoCandidate)))
    )
    assertEquals(flow.navigation.exactAnnotationsFor(speechAddress), Vector(speech.id))

  test("the textual twin is the audit surface for the receipt and every absence"):
    val gap = gapAt(
      NarrativeCandidateAddress.ContextAssignment(node(2, "d")),
      DerivationGapReason.Unresolved(unresolved)
    )
    val summary = gapAt(
      NarrativeCandidateAddress.StorySummary(source.id),
      DerivationGapReason.Unresolved(unresolved),
      ClaimFamily.Summary
    )
    val abstained = SentenceCoverage.Abstained(
      node(0, "d"),
      AbstentionReason.FocusNotPredicate(ConceptKind.Entity)
    )
    val law = Violation("hierarchy.single-primary-root", Severity.Error, "segments", "no segments")
    val twin = flowOf(
      draftOf(
        gaps = Vector(gap, summary),
        violations = Vector(law),
        coverage = Vector(abstained)
      )
    ).textualTwin

    assert(twin.contains("Basis: draft build"), twin)
    assert(twin.contains("Draft promotion"), twin)
    assert(twin.contains("promotable: false; derivation gaps: 2; violations: 1"), twin)
    assert(twin.contains("  - hierarchy.single-primary-root Error x1"), twin)
    assert(twin.contains("unsatisfied-law"), twin)
    assert(twin.contains("  absence=unresolved-family family=ContextAssignment"), twin)
    assert(twin.contains("state=Unresolved channel=Placeholder"), twin)
    assert(twin.contains("  absence=abstained-sentence"), twin)
    assert(twin.contains("reason=provider-abstained:focus-not-predicate:Entity"), twin)
    assert(twin.contains("Unplaced absences"), twin)
    assert(twin.contains("at=unplaced:whole-work"), twin)
    assert(twin.contains("channel=Bracket"), twin)

  test("a validated flow's annotation identities do not move when a draft channel exists"):
    // The absence key is appended to the content address and never interleaved, so an ordinary
    // annotation addresses exactly as it did before the disclosure channels were minted.
    val audit = AuditRecord.deterministic("draft-codex-suite", config)
    val target = Addressable[StoryRef].address(StoryRef.Context(narrated.id))
    val ordinary = TextAnnotation
      .of(target, spansOf(0), AnnotationKind.Context, AnnotationPriority.Default, audit)
      .fold(e => fail(e.message), identity)
    assertEquals(
      ordinary.id.value,
      AnnotationId
        .unsafe(
          ContentAddress.of(
            "annotation",
            target.render,
            "context",
            s"unit:some:${sentences(0).id.value}",
            sentences(0).span.start.toString,
            sentences(0).span.endExclusive.toString
          )
        )
        .value
    )
    assertEquals(ordinary.absence, None)

  test("a draft flow is a pure function of its inputs whatever order they arrive in"):
    val gaps = Vector(
      gapAt(
        NarrativeCandidateAddress.ContextAssignment(node(2, "d")),
        DerivationGapReason.Unresolved(unresolved)
      ),
      gapAt(
        NarrativeCandidateAddress.ParticipantCoverage(node(0, "p")),
        DerivationGapReason.MissingRawScore,
        ClaimFamily.ParticipantCoverage
      )
    )
    val coverage = Vector(
      SentenceCoverage.Proposed(node(2, "p"), FillerCounts(1, 0, 0, 0, 0, 0)),
      SentenceCoverage.Abstained(node(0, "d"), AbstentionReason.NoFocus)
    )
    val forward = flowOf(draftOf(gaps = gaps, coverage = coverage))
    val reversed = flowOf(draftOf(gaps = gaps.reverse, coverage = coverage.reverse))
    assertEquals(forward.textualTwin, reversed.textualTwin)
