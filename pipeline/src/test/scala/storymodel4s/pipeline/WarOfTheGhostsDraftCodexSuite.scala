package storymodel4s.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import munit.FunSuite
import scala.collection.mutable.ListBuffer
import storymodel4s.core.*
import storymodel4s.document.{
  AbstentionReason,
  ChartProposalProvider,
  NarrativeCompilation,
  NarrativeCompiler
}
import storymodel4s.fixtures.wog.WarOfTheGhostsText
import storymodel4s.proposition.ConceptKind
import storymodel4s.provider.agent.*
import storymodel4s.story.*
import storymodel4s.view.*

/** The reading view of a machine-built story: its words, with the model's own failures on them.
  *
  * Companion to `WarOfTheGhostsDraftAtlasSuite`, over the same replayed compilation. The Atlas
  * shows where the model is partial on a discourse axis; this shows it on the prose, which is the
  * surface `vision.md` puts first and the one a draft edition could not produce at all until the
  * Codex learned a draft path. Replay mode means no model call and no spend.
  *
  * What is pinned: the annotation census by channel, the identity between the absences the
  * compilation reports and the ones the flow accounts for, that the survivor's retelling is marked
  * as reported speech while the narration around it is not, and the twin lines that make all of it
  * auditable. What is deliberately not pinned: any recording's content.
  */
class WarOfTheGhostsDraftCodexSuite extends FunSuite:
  private val Now = 1700000000000L

  /** The fixture story's id is its canonical text checksum, so it is the same under any file name.
    */
  private val WogStory = "story:e4b036101a7a"

  /** The offsets of the survivor's retelling, measured independently by `StoryBuildSuite`. The
    * whole reason the context channel matters here: these words are quoted speech, and a reading
    * view that left them unmarked would show the fabricated battle as narrated prose.
    */
  private val RetellingQuotation: (Int, Int) = (1724, 1900)

  private val capturedRecordings: Path =
    Paths.get(getClass.getResource("/recordings/wog-captured").toURI)

  private val workDirs = ListBuffer.empty[Path]

  override def afterAll(): Unit =
    workDirs.foreach { dir =>
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(path => Files.deleteIfExists(path): Unit)
    }

  private def work(name: String): Path =
    val dir = Files.createTempDirectory(s"pipeline-$name")
    workDirs += dir
    dir

  /** The whole replay, run once: parse, propose, compile, and bind the draft to its own evidence.
    */
  private lazy val compiled: (NarrativeCompilation, DraftModel) =
    val dir = work("wog-draft-codex")
    val textPath = dir.resolve("war-of-the-ghosts.txt")
    Files.write(textPath, WarOfTheGhostsText.text.getBytes(StandardCharsets.UTF_8)): Unit
    val parsed = ClaudeParseDriver
      .parse(DriverMode.Replay, textPath, capturedRecordings, Map.empty, Now)
      .fold(error => fail(error.message), identity)
    val proposals = ChartProposalProvider
      .propose(parsed.story, parsed.atlas, parsed.charts)
      .fold(error => fail(error.message), identity)
    val input = ChartProposalProvider
      .input(parsed.story, parsed.atlas, parsed.charts, Some(parsed.parserStage._1), Now)
      .fold(error => fail(error.message), identity)
    val compilation = NarrativeCompiler.compile(input).fold(error => fail(error.message), identity)
    val draft = DraftModel.of(
      compilation.draft,
      compilation.validation,
      DerivationRecord.Reported(compilation.derivation.gaps, proposals.coverage)
    )
    (compilation, draft)

  private def compilation: NarrativeCompilation = compiled._1
  private def draft: DraftModel = compiled._2

  private val state = CommonViewState.empty
  private val spec = CodexSpec
    .forLens(CodexLens.Overview, ChannelBudget.All)
    .fold(error => fail(error.message), identity)

  private def flowFrom(
      bundle: DraftModel,
      viewState: CommonViewState,
      specification: CodexSpec
  ): CodexFlow =
    val provenance = ViewProvenance
      .draftBuild(
        bundle,
        "wog-draft-codex-suite",
        CodexCompiler.configurationChecksum(viewState, specification)
      )
      .fold(error => fail(error.message), identity)
    CodexCompiler(provenance)
      .compileDraft(bundle, viewState, specification)
      .fold(error => fail(error.message), identity)

  private lazy val flow: CodexFlow = flowFrom(draft, state, spec)

  private def census(f: CodexFlow): Vector[(String, Int)] =
    f.annotations.groupBy(_.kind.wireName).view.mapValues(_.size).toVector.sorted

  private def ledgerOf(f: CodexFlow): DraftAbsenceLedger =
    f.draft.getOrElse(fail("a draft flow carries an absence ledger"))

  private def marks(f: CodexFlow): Vector[TextAnnotation] =
    f.annotations.filter(_.absence.isDefined)

  private def contexts(f: CodexFlow): Vector[TextAnnotation] =
    f.annotations.filter(_.kind == AnnotationKind.Context)

  private def frameOf(annotation: TextAnnotation): ContextFrame =
    Addressable[StoryRef]
      .parse(annotation.target)
      .collect { case StoryRef.Context(id) => id }
      .flatMap(compilation.draft.graph.contexts.get)
      .getOrElse(fail(s"${annotation.target.render} is not a context frame"))

  private def covering(
      annotations: Vector[TextAnnotation],
      span: TextSpan
  ): Vector[TextAnnotation] =
    annotations.filter(_.support.spans.toVector.exists(_.overlaps(span)))

  test("the machine-built model is the same partial one the draft Atlas court renders") {
    assertEquals(compilation.draft.source.id.value, WogStory)
    assertEquals(compilation.draft.atlas.sentences.size, 50)
    assertEquals(compilation.draft.graph.situations.size, 65)
    assertEquals(compilation.draft.graph.entities.size, 23)
    assertEquals(compilation.draft.graph.contexts.size, 6)
    // The root segment, derived from its members and unsummarized because no title was stated
    // (ADR 0005 §10); 84 gaps, one of them the summary; the abstained sentence's 3 violations.
    assertEquals(compilation.draft.graph.segments.size, 1)
    assertEquals(
      compilation.draft.graph.segments.values.map(_.summary).toVector,
      Vector(SegmentSummary.Unsummarized(SummaryGap.NotProposed))
    )
    assertEquals(compilation.draft.hierarchy.containment.size, 65)
    assertEquals(compilation.validated, None)
    assertEquals(compilation.derivation.gaps.size, 84)
    assertEquals(compilation.validation.report.violations.size, 3)
  }

  test("the receipt states the promotion the reading view renders, and refuses the other path") {
    val promotion = flow.provenance.draft.getOrElse(fail("a draft flow carries a promotion"))
    assertEquals(flow.provenance.basis, ViewBasis.DraftBuild)
    assertEquals(promotion.promoted, false)
    assertEquals(promotion.gapCount, Some(84))
    assertEquals(promotion.violationCount, 3)
    assertEquals(
      flow.provenance.modelReceiptChecksum,
      compilation.draft.receipt.map(_.contentChecksum)
    )
  }

  test("the annotation census: 244 marks on the words, of which 84 are failures") {
    assertEquals(
      census(flow),
      Vector(
        ("abstention", 1),
        ("claim", 65),
        ("context", 6),
        ("entity", 23),
        ("gap", 83),
        ("hierarchy", 66)
      )
    )
    assertEquals(flow.annotations.size, 244)

    // The hierarchy channel carries the root segment and its sixty-five memberships: since ADR
    // 0005 §10 the segment is derived from its members, so an untitled build has a hierarchy to
    // draw. The relation channel is declared and empty because this view state selects no
    // relation layer. A declared empty channel says that; an undeclared one would be
    // indistinguishable from a renderer that dropped it.
    assert(flow.contract.activeKinds.contains(AnnotationKind.Hierarchy))
    assert(flow.contract.activeKinds.contains(AnnotationKind.Relation))
    assert(AnnotationKind.absence.subsetOf(flow.contract.activeKinds))
    assertEquals(
      census(flow).map(_._1).toSet.intersect(Set("hierarchy", "relation")),
      Set("hierarchy")
    )
    val hierarchy = flow.annotations.filter(_.kind == AnnotationKind.Hierarchy)
    assertEquals(
      hierarchy.count(annotation =>
        Addressable[StoryRef].parse(annotation.target).exists {
          case StoryRef.Segment(_) => true
          case _                   => false
        }
      ),
      1
    )
    assertEquals(
      hierarchy.count(annotation =>
        Addressable[StoryRef].parse(annotation.target).exists {
          case StoryRef.Containment(_, _, _) => true
          case _                             => false
        }
      ),
      65
    )

    // Every absence carries its own record, and no ordinary annotation carries one. The 84 are
    // the 83 placed gaps and the abstention; no unsatisfied law names words since ADR 0005 §10.
    assertEquals(marks(flow).size, 84)
    assert(marks(flow).forall(mark => mark.absence.exists(_.kind == mark.kind)))
    assert(flow.annotations.filterNot(_.kind.marksAbsence).forall(_.absence.isEmpty))
  }

  test("every absence the compilation recorded is accounted for, marked or unplaced") {
    val ledger = ledgerOf(flow)
    val expected =
      compilation.derivation.gaps.size + draft.abstentions.size +
        compilation.validation.report.violations.size
    assertEquals(draft.absences.size, expected)
    assertEquals(ledger.total, expected)
    assertEquals(ledger.total, 88)
    assertEquals(ledger.marked, marks(flow).map(_.id).sorted)

    // Four absences concern no words and say so, rather than being dropped from the reading view
    // or borrowing a sentence they do not describe: the summary gap and the three derivation laws.
    assertEquals(ledger.unplaced.size, 4)
    assertEquals(
      ledger.unplaced
        .groupBy(entry => (entry.absence.kind.wireName, entry.reason.render))
        .view
        .mapValues(_.size)
        .toVector
        .sorted,
      Vector((("gap", "whole-work"), 1), (("unsatisfied-law", "whole-work"), 3))
    )

    // The one unplaced gap is the story summary: it concerns the whole work, so borrowing the first
    // sentence's offsets would be an invented position.
    val summary = ledger.unplaced.filter(_.absence.kind == AnnotationKind.Gap).head
    assertEquals(
      summary.subject,
      Addressable[CoreRef].address(CoreRef.Story(compilation.draft.source.id))
    )
    assert(summary.absence.render.contains("family=Summary"), summary.absence.render)
  }

  test("every failure that names words names the exact sentence it concerns") {
    val byId = compilation.draft.atlas.byId
    val placed = marks(flow).filterNot(_.kind == AnnotationKind.UnsatisfiedLaw)
    assertEquals(placed.size, 84)
    placed.foreach { mark =>
      mark.support.refs.toVector.foreach { ref =>
        val unit = ref.unit.flatMap(byId.get).getOrElse(fail(s"no unit under ${mark.id.value}"))
        assertEquals(unit.span, ref.span)
        assertEquals(unit.kind, SurfaceUnitKind.Sentence)
      }
    }

    // No law is placed: the three remaining violations name a chart candidate with no address,
    // and the sixty-five reachability laws that used to cite their situation's own words were the
    // cost of a root segment waiting on its summary, retired by ADR 0005 §10.
    assertEquals(marks(flow).filter(_.kind == AnnotationKind.UnsatisfiedLaw), Vector.empty)
    assertEquals(
      ledgerOf(flow).unplaced.count(_.absence.kind == AnnotationKind.UnsatisfiedLaw),
      compilation.validation.report.violations.size
    )
  }

  test("every failure declares a non-colour channel, and the states are the ones D9 names") {
    assertEquals(
      marks(flow)
        .flatMap(_.absence)
        .map(absence =>
          s"${absence.kind.wireName}/${absence.uncertainty.fold("-")(_.toString)}/${absence.channel}"
        )
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toVector
        .sorted,
      Vector(
        ("abstention/Missing/OpenHatch", 1),
        ("gap/Alternatives/Fan", 22),
        ("gap/Missing/OpenHatch", 53),
        ("gap/Unresolved/Placeholder", 8)
      )
    )

    // The Atlas counts 53 Missing, 9 Unresolved and 22 Alternatives over all 84 gaps. The ninth
    // Unresolved is the story summary, which concerns the whole work and so has no words to sit
    // on here.
    assertEquals(
      ledgerOf(flow).unplaced
        .filter(_.absence.kind == AnnotationKind.Gap)
        .flatMap(_.absence.uncertainty),
      Vector(UncertaintyState.Unresolved)
    )
  }

  test("the one abstained sentence is a mark on the words the provider refused") {
    val abstentions = marks(flow).filter(_.kind == AnnotationKind.Abstention)
    assertEquals(abstentions.size, 1)
    val mark = abstentions.head
    assertEquals(
      mark.target,
      Addressable[CoreRef].address(CoreRef.SurfaceUnit(SurfaceUnitId.unsafe(s"$WogStory:s43")))
    )
    assertEquals(
      mark.absence.map(_.content),
      Some(
        AbsenceContent.AbstainedSentence(
          SurfaceUnitId.unsafe(s"$WogStory:s43"),
          SentenceAbstention.ProviderAbstained(
            AbstentionReason.FocusNotPredicate(ConceptKind.Entity)
          )
        )
      )
    )
    val sentence = compilation.draft.atlas.byId(SurfaceUnitId.unsafe(s"$WogStory:s43"))
    assertEquals(mark.support.spans.toVector, Vector(sentence.span))
  }

  test("the survivor's retelling reads as reported speech, and the narration around it does not") {
    val frames = contexts(flow)
    assertEquals(frames.size, 6)

    val narration = frames
      .find(annotation => frameOf(annotation).kind == ContextKind.NarratedWorld)
      .getOrElse(fail("the narrated world has no annotation"))
    val speech = frames.filterNot(_ == narration)
    assertEquals(speech.size, 5)
    assert(speech.forall(annotation => frameOf(annotation).kind.heldBy.isDefined))

    // The narrated world's support is its own 281 runs, not a hull: a hull would run straight
    // through the five quotations and mark them as narration.
    assertEquals(narration.support.spans.length, 281)
    assert(speech.forall(_.support.spans.length == 1))

    val (start, end) = RetellingQuotation
    val retelling = speech
      .find(annotation =>
        annotation.support.spans.head.start == start &&
          annotation.support.spans.head.endExclusive == end
      )
      .getOrElse(fail("the retelling has no annotation"))
    assertEquals(
      frameOf(retelling).kind,
      ContextKind.Speech(ContextHolder.Unattributed(HolderGap.NoCandidate))
    )

    // Every sentence of the retelling carries the reported-speech annotation and no other frame's;
    // no sentence of the narration carries a speech annotation at all. That is the distinction a
    // reader of the prose needs, and it is the one the plan's §2 says the viewer may not lose.
    val quotation = TextSpan.unsafe(start, end)
    val inside = compilation.draft.atlas.sentences.filter(sentence =>
      sentence.span.start >= start && sentence.span.endExclusive <= end
    )
    assert(inside.nonEmpty, "the retelling holds whole sentences")
    inside.foreach(sentence =>
      assertEquals(covering(speech, sentence.span).map(_.id), Vector(retelling.id))
    )
    val narrated =
      compilation.draft.atlas.sentences.filter(sentence => covering(speech, sentence.span).isEmpty)
    assert(narrated.nonEmpty)
    narrated.foreach(sentence =>
      assert(
        covering(frames, sentence.span).forall(_ == narration),
        s"sentence ${sentence.id.value} lies in no quotation and may carry no speech frame"
      )
    )

    // And the retelling's annotation reaches only the retelling's own words.
    val retold = compilation.draft.atlas.sentences.filter(sentence =>
      covering(speech, sentence.span).contains(retelling)
    )
    assert(retold.nonEmpty)
    assert(retold.forall(_.span.overlaps(quotation)))

    // The model's own scope evidence, surfaced rather than smoothed. The narrated-world frame's
    // support is word-level, and 63 of its 281 runs lie inside a speech frame — the content words
    // of the quoted passages, 22 of them inside this retelling. So a reader of the retelling sees
    // both frames on those words. The view neither adds that nor hides it; it belongs to the
    // narrated frame's support in `document`, and pinning it here makes a later fix there arrive as
    // a visible change to this court rather than a silent one.
    val overlapping = narration.support.spans.toVector.count(run =>
      speech.exists(_.support.spans.toVector.exists(_.overlaps(run)))
    )
    assertEquals(overlapping, 63)
    assertEquals(narration.support.spans.toVector.count(_.overlaps(quotation)), 22)

    // And a reader moving from the words reaches the frame itself, which is where the model states
    // that this is speech; the reading view points at that statement rather than restating it.
    assertEquals(flow.navigation.exactAnnotationsFor(retelling.target), Vector(retelling.id))
    assertEquals(flow.navigation.targetOf(retelling.id), Some(retelling.target))
  }

  test("the textual twin is the audit surface for everything above") {
    val twin = flow.textualTwin
    assert(twin.startsWith("Narrative Codex\n"), twin.take(120))
    assert(twin.contains("Basis: draft build\n"), "the twin states the basis")
    assert(twin.contains("promotable: false; derivation gaps: 84; violations: 3\n"))
    assert(twin.contains("  - compiler.required-derivation Error x3\n"))
    assert(!twin.contains("hierarchy."), "no hierarchy law is unsatisfied since ADR 0005 §10")
    assert(
      twin.contains(
        "Channels: abstention,claim,context,entity,gap,hierarchy,relation,unsatisfied-law\n"
      )
    )

    def lines(prefix: String): Vector[String] =
      twin.linesIterator.filter(_.startsWith(prefix)).toVector
    assertEquals(lines("  absence=unresolved-family").size, 83)
    assertEquals(lines("  absence=abstained-sentence").size, 1)
    assertEquals(lines("  absence=unsatisfied-law").size, 0)
    assert(lines("  absence=").forall(_.contains("channel=")))
    assert(lines("  absence=").exists(_.contains("state=Unresolved channel=Placeholder")))
    assert(lines("  absence=").exists(_.contains("state=Missing channel=OpenHatch")))
    assert(
      lines("  absence=abstained-sentence").head.contains(
        "reason=provider-abstained:focus-not-predicate"
      )
    )

    // Every absence with no discourse position is listed too, so the twin accounts for all 88:
    // the summary gap and the three derivation laws, which sit on no words.
    assert(twin.contains("Unplaced absences\n"))
    assertEquals(lines("- ").count(_.contains("at=unplaced:whole-work")), 4)
    assertEquals(lines("- ").count(_.contains("unsatisfied-law")), 3)
  }

  test("the draft flow is deterministic, and relation layers add no absence and lose none") {
    assertEquals(flowFrom(draft, state, spec).textualTwin, flow.textualTwin)

    val layered = CommonViewState
      .of(relationLayers = RelationLayer.values.toSet)
      .fold(error => fail(error.message), identity)
    val richer = flowFrom(draft, layered, spec)
    val relations = richer.annotations.count(_.kind == AnnotationKind.Relation)
    assert(relations > 0, "the model records relations and the reading view must show them")
    assertEquals(marks(richer).size, marks(flow).size)
    assertEquals(ledgerOf(richer).total, ledgerOf(flow).total)
    assertEquals(richer.annotations.size, flow.annotations.size + relations)
  }

  test("a model that arrives without its derivation record draws a smaller, still honest view") {
    // A derivation gap is a statement about the derivation, not about the story, so a consumer
    // holding only a decoded `storymodel.json` has none. Re-validating independently finds no
    // violation: the root segment exists and every situation is under it (ADR 0005 §10), and the
    // three `compiler.required-derivation` errors only the compiler can raise.
    val revalidated = StoryValidator.validate(compilation.draft)
    assertEquals(revalidated.report.violations, Vector.empty)
    val alone = DraftModel.withoutDerivationRecord(compilation.draft, revalidated)
    val aloneFlow = flowFrom(alone, state, spec)

    assertEquals(
      census(aloneFlow),
      Vector(("claim", 65), ("context", 6), ("entity", 23), ("hierarchy", 66))
    )
    assertEquals(ledgerOf(aloneFlow).total, 0)
    assertEquals(ledgerOf(aloneFlow).unplaced.size, 0)

    // And the receipt says which it is: "record not supplied" is not "0 gaps".
    assertEquals(alone.promotion.gapCount, None)
    assertEquals(flow.provenance.draft.flatMap(_.gapCount), Some(84))
    assert(aloneFlow.textualTwin.contains("derivation gaps: record not supplied"))
    assert(flow.textualTwin.contains("derivation gaps: 84"))

    // The two receipts are different statements, so neither flow can be compiled under the other.
    val crossed = CodexCompiler(
      ViewProvenance
        .draftBuild(
          alone,
          "wog-draft-codex-suite",
          CodexCompiler.configurationChecksum(state, spec)
        )
        .fold(error => fail(error.message), identity)
    ).compileDraft(draft, state, spec)
    assert(crossed.isLeft, "a receipt saying nothing about derivation must not render 84 gaps")
  }
