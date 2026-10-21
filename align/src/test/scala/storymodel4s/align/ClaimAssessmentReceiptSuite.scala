package storymodel4s.align

import cats.data.NonEmptySet
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.proposition.*
import storymodel4s.recall.RecallUnitId

/** Synthetic contract cases use the existing admitted Anna fixture; no participant material. */
class ClaimAssessmentReceiptSuite extends FunSuite:
  import MappingMeasureFixture.{recall, view, inventory, source, result}
  private lazy val mapping = HistoricalMapping
    .of(
      result,
      recall,
      view,
      inventory(recall),
      source(view),
      HistoricalDecode.ArgmaxOnly
    )
    .toOption
    .get
  private val unit = recall.ordered.head
  private val event = view.nodes.head
  private val question =
    ClaimAssessmentQuestion(ClaimId.unsafe("question:action"), unit.id, event.ref, Facet.Action)
  private val citation =
    ClaimSourceCitation(EvidenceId.unsafe("evidence:one"), event.ref, event.support)
  private def submission(state: RecallClaimState = RecallClaimState.Expressed) =
    val expressed =
      state == RecallClaimState.Expressed || state == RecallClaimState.ReferenceUnresolved
    ClaimAssessmentSubmission(
      question.id,
      state,
      unit.span,
      Option.when(expressed)(unit.span),
      None,
      Set.empty,
      Set.empty,
      state match
        case RecallClaimState.Expressed            => Set(ClaimAssessmentUnknown.SourceUnavailable)
        case RecallClaimState.ExtractionUnresolved =>
          Set(ClaimAssessmentUnknown.RecallExtractionUnavailable)
        case RecallClaimState.ReferenceUnresolved => Set(ClaimAssessmentUnknown.ReferenceUnresolved)
        case _                                    => Set.empty,
      ClaimAssessorKind.DeterministicRule,
      Fingerprint.unsafe("synthetic-contract/v1")
    )
  private def receipt(
      s: ClaimAssessmentSubmission = submission(),
      cs: Vector[ClaimSourceCitation] = Vector(citation)
  ) = ClaimAssessmentReceipt.checked(mapping, Vector(question), cs, Vector(s))
  private def accepted(s: ClaimAssessmentSubmission) = receipt(s).toOption.get

  test("reviewed omission and failed extraction remain distinct on the same absent chart") {
    val omitted = accepted(submission(RecallClaimState.ReviewedNotExpressed))
    val failed = accepted(submission(RecallClaimState.ExtractionUnresolved))
    assertEquals(omitted.status(question.id), Some(ClaimAssessmentStatus.NotExpressed))
    assertEquals(failed.status(question.id), Some(ClaimAssessmentStatus.ExtractionUnresolved))
    assertEquals(omitted.populationDigest, failed.populationDigest)
    assertEquals(omitted.totalQuestions, 1)
    assertEquals(failed.totalQuestions, 1)
    assertNotEquals(omitted, failed)
  }

  test("missing source evidence produces neither support nor counterevidence") {
    val r = accepted(submission())
    assertEquals(r.status(question.id), Some(ClaimAssessmentStatus.Unresolved))
    assertEquals(r.expressedQuestions, 1)
    assertEquals(r.resolvedQuestions, 0)
    assertEquals(r.submissions.head.supporting, Set.empty[EvidenceId])
    assertEquals(r.submissions.head.counterevidence, Set.empty[EvidenceId])
  }

  test("support with unresolved context stays unanswered and preserves the support") {
    val r = accepted(
      submission().copy(
        supporting = Set(citation.id),
        unresolved = Set(ClaimAssessmentUnknown.ContextUnresolved)
      )
    )
    assertEquals(r.status(question.id), Some(ClaimAssessmentStatus.Unresolved))
    assertEquals(r.submissions.head.supporting, Set(citation.id))
    assertEquals(r.resolvedQuestions, 0)
    val counter = accepted(
      submission().copy(
        counterevidence = Set(citation.id),
        unresolved = Set(ClaimAssessmentUnknown.ContextUnresolved)
      )
    )
    assertEquals(counter.status(question.id), Some(ClaimAssessmentStatus.Unresolved))
    assertEquals(counter.resolvedQuestions, 0)
  }

  test("unknown participant retains expressed action evidence without resolving identity") {
    val r =
      accepted(submission(RecallClaimState.ReferenceUnresolved).copy(supporting = Set(citation.id)))
    assertEquals(r.status(question.id), Some(ClaimAssessmentStatus.ReferenceUnresolved))
    assertEquals(r.expressedQuestions, 1)
    assertEquals(r.resolvedQuestions, 0)
    assertEquals(r.submissions.head.supporting, Set(citation.id))
  }

  test("support and counterevidence coexist rather than cancelling") {
    val r = accepted(
      submission().copy(
        supporting = Set(citation.id),
        counterevidence = Set(citation.id),
        unresolved = Set.empty
      )
    )
    assertEquals(r.status(question.id), Some(ClaimAssessmentStatus.Conflicted))
    assertEquals(r.resolvedQuestions, 0)
    assertEquals(r.submissions.head.supporting, r.submissions.head.counterevidence)
  }

  test("duplicate citations cannot multiply a question's assessed credit") {
    val second = citation.copy(id = EvidenceId.unsafe("evidence:duplicate-description"))
    val s = submission().copy(supporting = Set(citation.id, second.id), unresolved = Set.empty)
    val r = receipt(s, Vector(citation, second)).toOption.get
    assertEquals(r.counts(ClaimAssessmentStatus.Supported), 1)
    assertEquals(r.resolvedQuestions, 1)
    assertEquals(r.totalQuestions, 1)
    assert(receipt(s, Vector(citation, citation)).isLeft)
  }

  test("fixed question population cannot lose or duplicate unanswered submissions") {
    assert(
      ClaimAssessmentReceipt
        .checked(mapping, Vector(question), Vector(citation), Vector.empty)
        .isLeft
    )
    assert(
      ClaimAssessmentReceipt
        .checked(mapping, Vector(question), Vector(citation), Vector(submission(), submission()))
        .isLeft
    )
    assert(
      ClaimAssessmentReceipt
        .checked(mapping, Vector(question, question), Vector(citation), Vector(submission()))
        .isLeft
    )
  }

  test("every status remains in the fixed denominator and alignment bytes stay unchanged") {
    val before = mapping.digest
    val qs = Vector.tabulate(7)(i => question.copy(id = ClaimId.unsafe(s"q:$i")))
    val ss = Vector(
      submission().copy(supporting = Set(citation.id), unresolved = Set.empty),
      submission().copy(counterevidence = Set(citation.id), unresolved = Set.empty),
      submission().copy(
        supporting = Set(citation.id),
        counterevidence = Set(citation.id),
        unresolved = Set.empty
      ),
      submission(),
      submission(RecallClaimState.ReviewedNotExpressed),
      submission(RecallClaimState.ExtractionUnresolved),
      submission(RecallClaimState.ReferenceUnresolved)
    ).zip(qs).map((s, q) => s.copy(question = q.id))
    val r = ClaimAssessmentReceipt.checked(mapping, qs, Vector(citation), ss).toOption.get
    assertEquals(r.counts, ClaimAssessmentStatus.values.map(_ -> 1).toMap)
    assertEquals(r.totalQuestions, 7)
    assertEquals(r.expressedQuestions, 5)
    assertEquals(r.resolvedQuestions, 2)
    assertEquals(r.mappingDigest, before)
    assert(r.mapping eq mapping)
    assertEquals(mapping.digest, before)
    val ablated = ClaimAssessmentReceipt
      .checked(
        mapping,
        qs,
        Vector.empty,
        ss.map(s =>
          if s.recallState == RecallClaimState.Expressed then
            s.copy(
              supporting = Set.empty,
              counterevidence = Set.empty,
              unresolved = Set(ClaimAssessmentUnknown.SourceUnavailable)
            )
          else s
        )
      )
      .toOption
      .get
    assertEquals(ablated.populationDigest, r.populationDigest)
    assertEquals(ablated.totalQuestions, 7)
    assertEquals(ablated.expressedQuestions, 5)
    assertEquals(ablated.resolvedQuestions, 0)
  }

  test("foreign unit event and evidence links are refused") {
    assert(
      ClaimAssessmentReceipt
        .checked(
          mapping,
          Vector(question.copy(unit = RecallUnitId.unsafe("foreign"))),
          Vector(citation),
          Vector(submission())
        )
        .isLeft
    )
    assert(
      ClaimAssessmentReceipt
        .checked(
          mapping,
          Vector(question.copy(event = SourceNodeRef.Situation(SituationId.unsafe("foreign")))),
          Vector(citation),
          Vector(submission())
        )
        .isLeft
    )
    assert(receipt(submission().copy(supporting = Set(EvidenceId.unsafe("foreign")))).isLeft)
  }

  test("citation cannot borrow another source interval") {
    val beyond = TypedSupport.Text(
      SpanSet.one(TextSpan.unsafe(view.scoringLength + 1, view.scoringLength + 4))
    )
    assert(receipt(cs = Vector(citation.copy(support = beyond))).isLeft)
  }

  test("recall spans and review scope are bound to their own unit") {
    val beyond = SpanSet.one(
      TextSpan.unsafe(
        recall.transcript.canonicalText.length + 1,
        recall.transcript.canonicalText.length + 4
      )
    )
    assert(receipt(submission().copy(claimSpans = Some(beyond))).isLeft)
    assert(receipt(submission().copy(reviewedSpans = beyond)).isLeft)
    val foreignUnit = SpanSet.one(
      SpanRef(Some(SurfaceUnitId.unsafe("foreign-surface-unit")), unit.span.minSpan)
    )
    assert(receipt(submission().copy(claimSpans = Some(foreignUnit))).isLeft)
    assert(
      receipt(
        submission(RecallClaimState.ReviewedNotExpressed).copy(claimSpans = Some(unit.span))
      ).isLeft
    )
    assert(receipt(submission().copy(claimSpans = None)).isLeft)
  }

  private val focus = ConceptId.unsafe("p")
  private def chartReference(scope: SpanSet = unit.span): ClaimChartReference =
    val meta = ClaimMeta.unsafe(
      ClaimId.unsafe("synthetic:alignment"),
      EpistemicStatus.Hypothesized,
      Credence.unsafeRaw(1.0, ScorerId.unsafe("synthetic")),
      cats.data.NonEmptyVector.one(
        Evidence(
          EvidenceId.unsafe("synthetic:span"),
          Some(scope),
          Set.empty,
          Fingerprint.unsafe("fixture"),
          StageId.unsafe("fixture")
        )
      ),
      Provenance.deterministic("synthetic", Checksum.ofText("synthetic"))
    )
    val element = AlignmentTarget.Concepts(NonEmptySet.one(focus))
    val chart = ChartValidator
      .validate(
        PropositionChart.unchecked(
          Some(focus),
          Map(focus -> Concept.predicate("test")),
          Vector.empty,
          alignments = Vector(
            PropositionAlignment(
              element,
              scope,
              Credence.unsafeRaw(1.0, ScorerId.unsafe("synthetic")),
              meta
            )
          )
        )
      )
      .toOption
      .get
    ClaimChartReference(mapping.inventory.transcript, PropositionEvidence.of(chart), element)

  test("chart owner element provenance and textual scope must agree") {
    val ref = chartReference()
    assert(receipt(submission().copy(recallChart = Some(ref))).isRight)
    assert(
      receipt(
        submission().copy(recallChart = Some(ref.copy(owner = Checksum.ofText("foreign"))))
      ).isLeft
    )
    assert(
      receipt(
        submission().copy(recallChart =
          Some(
            ref.copy(element =
              AlignmentTarget.Concepts(NonEmptySet.one(ConceptId.unsafe("foreign")))
            )
          )
        )
      ).isLeft
    )
    val shortSpan =
      SpanSet.one(TextSpan.unsafe(unit.span.minSpan.start, unit.span.minSpan.start + 1))
    assert(receipt(submission().copy(claimSpans = Some(shortSpan), recallChart = Some(ref))).isLeft)
    assert(
      receipt(
        submission(RecallClaimState.ReferenceUnresolved)
          .copy(recallChart = Some(ref), supporting = Set(citation.id))
      ).isRight
    )
    val foreign = ref.copy(evidence =
      ref.evidence.copy(provenance =
        ChartProvenance(ChartOrigin.Agent(Fingerprint.unsafe("other")), Vector.empty, Vector.empty)
      )
    )
    assert(receipt(submission().copy(recallChart = Some(foreign))).isLeft)
  }

  test("foreign bundle evidence is refused even with identical interval coordinates") {
    val bundle = SourceBundle
      .filmEdition(
        EditionId.unsafe("edition:one"),
        Checksum.ofText("media:one"),
        0L,
        100L,
        RationalTimebase.Millisecond
      )
      .toOption
      .get
    val other = SourceBundle
      .filmEdition(
        EditionId.unsafe("edition:two"),
        Checksum.ofText("media:two"),
        0L,
        100L,
        RationalTimebase.Millisecond
      )
      .toOption
      .get
    def anchored(b: SourceBundle): TypedSupport =
      val interval = PlaybackInterval.on(b.primaryAxis, 0L, 100L).toOption.get
      val anchors = EvidenceSupport
        .of(
          b,
          Vector(
            EvidenceAnchor.MediaTime(
              b.id,
              b.streams.head.id,
              b.primaryAxis.id,
              PlaybackIntervalSet.of(Vector(interval)).toOption.get
            )
          )
        )
        .toOption
        .get
      TypedSupport.Anchored(anchors)
    val good = anchored(bundle)
    val mediaSource = SourceRepresentation
      .of(
        view,
        cats.data.NonEmptyVector.one(BundleEntry.media(bundle)),
        None,
        view.nodes.map(n => n.ref -> SourceSupportStatus.located(good)).toMap
      )
      .toOption
      .get
    val mediaMapping = HistoricalMapping
      .of(result, recall, view, inventory(recall), mediaSource, HistoricalDecode.ArgmaxOnly)
      .toOption
      .get
    val cited = citation.copy(support = good)
    assert(
      ClaimAssessmentReceipt
        .checked(mediaMapping, Vector(question), Vector(cited), Vector(submission()))
        .isRight
    )
    assert(
      ClaimAssessmentReceipt
        .checked(
          mediaMapping,
          Vector(question),
          Vector(cited.copy(support = anchored(other))),
          Vector(submission())
        )
        .isLeft
    )
  }

  test("source chart owner binds full source identity as well as coordinates") {
    val owner = ClaimAssessmentReceipt.sourceChartOwner(mapping, event.ref, event.support)
    val reference = chartReference(event.support.textSpans.get).copy(owner = owner)
    assert(receipt(cs = Vector(citation.copy(chart = Some(reference)))).isRight)
    val wrong = reference.copy(owner = event.support.identity)
    assert(receipt(cs = Vector(citation.copy(chart = Some(wrong)))).isLeft)
  }
