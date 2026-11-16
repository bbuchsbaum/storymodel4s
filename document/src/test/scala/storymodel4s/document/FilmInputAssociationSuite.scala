package storymodel4s.document

import munit.FunSuite
import cats.data.{NonEmptySet, NonEmptyVector}
import storymodel4s.core.*
import storymodel4s.proposition.*
class FilmInputAssociationSuite extends FunSuite:
  import FilmInputFixtures.*
  private val refs = Vector(
    ChartNodeRef(a, evidenceA.chart.conceptIds.head),
    ChartNodeRef(b, evidenceB.chart.conceptIds.head)
  )
  private def input(scopes: Vector[FilmAttemptScope] = Vector(scope(Vector(a, b), refs))) =
    FilmCompilerInput.of(prepared, associations, expected, scopes)
  test("valid nonempty checked input") { assert(input().exists(_.charts.nonEmpty)) }
  test("A provenance selects A and shared with origin") {
    val p = input().toOption.get.provenanceFor(Vector(refs.head)).toOption.get
    assertEquals(p.calls.toSet, Set(callA, shared)); assertEquals(p.records, Vector(rows.head))
  }
  test("A plus B provenance deduplicates shared calls and retains both origins") {
    val p = input().toOption.get.provenanceFor(refs).toOption.get
    assertEquals(p.calls.toSet, Set(callA, callB, shared));
    assertEquals(p.calls.count(_ == shared), 1)
    assertEquals(p.records.toSet, rows.toSet)
  }
  test("swapped expected binding is refused") {
    assert(input(Vector(scope(Vector(a, b), refs))).isRight);
    assert(
      FilmCompilerInput
        .of(prepared, associations, expected.reverse, Vector(scope(Vector(a, b), refs)))
        .isLeft
    )
  }
  test("unknown contributor is refused") {
    assert(input(Vector(scope(Vector(a), Vector(refs.head, refs(1))))).isLeft)
  }
  test("declared sentence with an unknown chart concept is refused") {
    assert(
      input(Vector(scope(Vector(a), Vector(ChartNodeRef(a, ConceptId.unsafe("missing")))))).isLeft
    )
  }
  test("independent parser request recipe and output pins refuse substitutions") {
    val bad = bindingA.copy(requestChecksum = Checksum.ofText("other"))
    assert(
      FilmCompilerInput
        .of(prepared, associations, Vector(bad, bindingB), Vector(scope(Vector(a, b), refs)))
        .isLeft
    )
    assert(
      FilmCompilerInput
        .of(
          prepared,
          associations,
          Vector(bindingA.copy(recipeIdentity = Checksum.ofText("other")), bindingB),
          Vector(scope(Vector(a, b), refs))
        )
        .isLeft
    )
    assert(
      FilmCompilerInput
        .of(
          prepared,
          associations,
          Vector(bindingA.copy(outputChartChecksum = Checksum.ofText("other")), bindingB),
          Vector(scope(Vector(a, b), refs))
        )
        .isLeft
    )
  }
  private def aligned(
      span: TextSpan,
      credence: Credence = Credence.unmeasured
  ): PropositionEvidence =
    val concept = evidenceA.chart.conceptIds.head
    val spans = SpanSet.one(SpanRef(Some(a), span))
    val ev = Evidence(
      EvidenceId.unsafe("align"),
      Some(spans),
      Set.empty,
      Fingerprint.unsafe("f1-parser"),
      StageId.unsafe("parse")
    )
    val alignment = PropositionAlignment(
      AlignmentTarget.Concepts(NonEmptySet.one(concept)),
      spans,
      credence,
      ClaimMeta.unsafe(
        ClaimId.unsafe("align"),
        EpistemicStatus.SurfaceExplicit,
        Credence.unmeasured,
        NonEmptyVector.one(ev),
        Provenance.deterministic("test", Checksum.ofText("test"))
      )
    )
    PropositionEvidence.of(
      ChartValidator
        .check(
          PropositionChart.unchecked(
            Some(concept),
            evidenceA.chart.concepts,
            Vector.empty,
            alignments = Vector(alignment),
            provenance = evidenceA.chart.provenance,
            sentence = Some(a)
          )
        )
        .toOption
        .get
    )

  private def inputWithA(ev: PropositionEvidence) =
    val bound = bindingA.copy(outputChartChecksum = FilmCompilerInput.fullChartChecksum(ev))
    FilmCompilerInput.of(
      prepared,
      Vector(FilmChartAssociation(ev, bound), associations(1)),
      Vector(bound, bindingB),
      Vector(scope(Vector(a, b), refs))
    )

  test("alignment outside its declared sentence is refused by the alignment guard") {
    val sentenceA = prepared.atlas.surface.get.surface.byId(a)
    val sentenceB = prepared.atlas.surface.get.surface.byId(b)
    assert(inputWithA(aligned(sentenceA.span)).isRight)
    val refusal = inputWithA(aligned(sentenceB.span)).left.toOption.get
    assert(refusal.toString.contains("chart/alignment"))
  }

  test("full chart identity retains alignment spans and credence") {
    val sentence = prepared.atlas.surface.get.surface.byId(a)
    val full = aligned(sentence.span)
    val shortened = aligned(TextSpan.unsafe(sentence.span.start, sentence.span.endExclusive - 1))
    val scored = aligned(sentence.span, Credence.unsafeRaw(0.5, ScorerId.unsafe("test")))
    assertNotEquals(
      FilmCompilerInput.fullChartChecksum(full),
      FilmCompilerInput.fullChartChecksum(shortened)
    )
    assertNotEquals(
      FilmCompilerInput.fullChartChecksum(full),
      FilmCompilerInput.fullChartChecksum(scored)
    )
    val substituted = associations.updated(0, FilmChartAssociation(full, bindingA))
    assert(
      FilmCompilerInput
        .of(prepared, substituted, expected, Vector.empty)
        .left
        .toOption
        .get
        .toString
        .contains("chart/output")
    )
  }

  test("provenance refuses unknown nodes and returns no unrelated origin") {
    val value = input().toOption.get
    assert(value.provenanceFor(Vector(ChartNodeRef(a, ConceptId.unsafe("missing")))).isLeft)
    assertEquals(value.provenanceFor(Vector.empty).toOption.get.records, Vector.empty)
    assertEquals(
      value.provenanceFor(Vector(refs.head)).toOption.get.surfaceReceipt,
      prepared.derivation.receipt
    )
  }
