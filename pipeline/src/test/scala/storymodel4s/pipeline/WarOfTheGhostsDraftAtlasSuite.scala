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

/** The first court in this repository that renders a machine-built story model.
  *
  * Why it lives in `pipeline` and not in `view`: the only real fifty-sentence compilation this
  * project has is the one `recordings/wog-captured` replays, and `view` cannot see the transport or
  * the provider. Replay mode means no model call and no spend.
  *
  * Why the build is untitled: the pipeline stopped deriving a title from the input file's name, so
  * a build nobody states a title for has no established summary, the `Summary` family abstains, no
  * story segment is derived, sixty-five segment memberships lose their upstream, and the model does
  * not promote. That is the state the viewer has to be able to draw, and drawing it is the point of
  * the draft path: making the model validate in order to satisfy a renderer would move the
  * falsehood out of the picture and into the artifact.
  *
  * What is pinned: the compilation's own counts, the mark census by kind, the identity between the
  * gaps the compilation reports and the gap marks the scene carries, the six context frames with
  * their exact discontinuous extents, and the lines of the textual twin that make all of it
  * auditable. What is deliberately not pinned: any recording's content.
  */
class WarOfTheGhostsDraftAtlasSuite extends FunSuite:
  private val Now = 1700000000000L

  /** The fixture story's id is its canonical text checksum, so it is the same under any file name.
    */
  private val WogStory = "story:e4b036101a7a"

  /** The offsets of the survivor's retelling, measured independently by `StoryBuildSuite`. The
    * whole reason context bands matter: these words are quoted speech, not narration, and an atlas
    * that drew them on the narrated-world lane would put the fabricated battle back in the picture.
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
    val dir = work("wog-draft-atlas")
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
      compilation.derivation.gaps,
      proposals.coverage
    )
    (compilation, draft)

  private def compilation: NarrativeCompilation = compiled._1
  private def draft: DraftModel = compiled._2

  private val state = CommonViewState.empty
  private val spec =
    AtlasSpec(ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Hidden), ThreadPolicy.Selected)

  private def sceneUnder(specification: AtlasSpec): NarrativeScene =
    val provenance = ViewProvenance
      .draftBuild(
        draft,
        "wog-draft-atlas-suite",
        AtlasCompiler.configurationChecksum(state, specification)
      )
      .fold(error => fail(error.message), identity)
    AtlasCompiler(provenance)
      .compileDraft(draft, state, specification)
      .fold(error => fail(error.message), identity)

  private lazy val scene: NarrativeScene = sceneUnder(spec)

  private def census(s: NarrativeScene): Map[String, Int] =
    s.marks
      .groupBy {
        case _: VisualPrimitive.SurfaceUnit    => "surface-unit"
        case _: VisualPrimitive.Region         => "region"
        case _: VisualPrimitive.Landmark       => "landmark"
        case _: VisualPrimitive.Thread         => "thread"
        case _: VisualPrimitive.Portal         => "portal"
        case _: VisualPrimitive.Route          => "route"
        case _: VisualPrimitive.ContextBand    => "context-band"
        case _: VisualPrimitive.Gap            => "gap"
        case _: VisualPrimitive.Abstention     => "abstention"
        case _: VisualPrimitive.UnsatisfiedLaw => "unsatisfied-law"
      }
      .view
      .mapValues(_.size)
      .toMap

  private def gaps(s: NarrativeScene): Vector[VisualPrimitive.Gap] =
    s.marks.collect { case g: VisualPrimitive.Gap => g }
  private def bands(s: NarrativeScene): Vector[VisualPrimitive.ContextBand] =
    s.marks.collect { case b: VisualPrimitive.ContextBand => b }
  private def landmarks(s: NarrativeScene): Vector[VisualPrimitive.Landmark] =
    s.marks.collect { case l: VisualPrimitive.Landmark => l }
  private def laws(s: NarrativeScene): Vector[VisualPrimitive.UnsatisfiedLaw] =
    s.marks.collect { case l: VisualPrimitive.UnsatisfiedLaw => l }

  private def unplacedReason(placement: EpistemicPlacement): String = placement match
    case EpistemicPlacement.AtSpans(_)                  => "at-spans"
    case EpistemicPlacement.NoDiscoursePosition(reason) => reason.render

  test("the machine-built model is exactly the partial one the plan describes") {
    assertEquals(compilation.draft.source.id.value, WogStory)
    assertEquals(compilation.draft.atlas.sentences.size, 50)
    assertEquals(compilation.draft.graph.situations.size, 65)
    assertEquals(compilation.draft.graph.entities.size, 29)
    assertEquals(compilation.draft.graph.contexts.size, 6)

    // No story segment, because nobody stated a title and the summary family therefore abstained.
    assertEquals(compilation.draft.graph.segments.size, 0)
    assertEquals(compilation.validated, None)
    assertEquals(compilation.derivation.gaps.size, 70)
    assertEquals(compilation.validation.report.violations.size, 135)
  }

  test("the receipt states the promotion it renders, derived from the outcome") {
    val promotion = scene.provenance.draft.getOrElse(fail("a draft scene carries a promotion"))
    assertEquals(scene.provenance.basis, ViewBasis.DraftBuild)
    assertEquals(promotion.promoted, false)
    assertEquals(promotion.gapCount, 70)
    assertEquals(promotion.violationCount, 135)
    assertEquals(
      promotion.unsatisfiedLaws.map(law => (law.law, law.severity, law.count.value)),
      Vector(
        ("compiler.required-derivation", Severity.Error, 69),
        ("hierarchy.single-primary-root", Severity.Error, 1),
        ("hierarchy.situation-root-reachable", Severity.Error, 65)
      )
    )
    assertEquals(
      scene.provenance.modelReceiptChecksum,
      compilation.draft.receipt.map(_.contentChecksum)
    )
  }

  test("the mark census: 277 marks, and every absence the compilation recorded is one of them") {
    assertEquals(
      census(scene).toVector.sorted,
      Vector(
        ("abstention", 1),
        ("context-band", 6),
        ("gap", 70),
        ("landmark", 65),
        ("unsatisfied-law", 135)
      )
    )
    assertEquals(scene.marks.size, 277)

    // The identity the acceptance criterion asks for: the gap marks number what the compilation
    // reports, and each names a distinct gap.
    assertEquals(gaps(scene).size, compilation.derivation.gaps.size)
    assertEquals(gaps(scene).map(_.identity.mark).distinct.size, 70)
    assertEquals(
      gaps(scene).map(_.target).toSet,
      compilation.derivation.gaps.map(_.target).toSet
    )
    assertEquals(laws(scene).size, compilation.validation.report.violations.size)
  }

  test("every gap is placed on exact words or says why it has none") {
    assertEquals(
      gaps(scene)
        .map(gap => unplacedReason(gap.placement))
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap,
      Map("at-spans" -> 69, "whole-work" -> 1)
    )

    // The one gap with no discourse position is the story summary: it concerns the whole work, so
    // borrowing the first sentence's offsets would be an invented position.
    val whole = gaps(scene).filter(_.placement match
      case EpistemicPlacement.NoDiscoursePosition(_) => true
      case _                                         => false)
    assertEquals(whole.size, 1)
    assertEquals(whole.head.family.toString, "Summary")
    assertEquals(
      whole.head.address,
      Addressable[CoreRef].address(CoreRef.Story(compilation.draft.source.id))
    )

    // Every placed gap sits on the exact span of a sentence the atlas contains (V-E3 extended).
    val byId = compilation.draft.atlas.byId
    gaps(scene).foreach { gap =>
      gap.placement match
        case EpistemicPlacement.AtSpans(spans) =>
          spans.refs.toVector.foreach { ref =>
            val unit =
              ref.unit.flatMap(byId.get).getOrElse(fail(s"no unit for ${gap.target.render}"))
            assertEquals(unit.span, ref.span)
            assertEquals(unit.kind, SurfaceUnitKind.Sentence)
          }
        case EpistemicPlacement.NoDiscoursePosition(_) => ()
    }
  }

  test("every gap declares a non-colour channel, and the states are the ones D9 names") {
    assertEquals(
      gaps(scene)
        .map(gap => (gap.state, gap.epistemicChannel))
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap,
      Map[(UncertaintyState, Option[EpistemicChannel]), Int](
        (UncertaintyState.Missing, Some(EpistemicChannel.OpenHatch)) -> 65,
        (UncertaintyState.Unresolved, Some(EpistemicChannel.Placeholder)) -> 5
      )
    )
    assert(gaps(scene).forall(_.epistemicChannel.isDefined))
    assert(laws(scene).forall(_.epistemicChannel.contains(EpistemicChannel.Bracket)))
    assert(laws(scene).forall(_.uncertainty.isEmpty))
  }

  test("the one abstained sentence is a mark, with the provider's own reason") {
    val abstentions = scene.marks.collect { case a: VisualPrimitive.Abstention => a }
    assertEquals(abstentions.size, 1)
    val mark = abstentions.head
    assertEquals(mark.unit.value, s"$WogStory:s43")
    assertEquals(
      mark.reason,
      SentenceAbstention.ProviderAbstained(AbstentionReason.FocusNotPredicate(ConceptKind.Entity))
    )
    assertEquals(mark.uncertainty, Some(UncertaintyState.Missing))
    assertEquals(mark.epistemicChannel, Some(EpistemicChannel.OpenHatch))
    assertEquals(unplacedReason(mark.placement), "at-spans")
  }

  test("an unsatisfied law cites its subject's own words, or none at all") {
    assertEquals(
      laws(scene)
        .map(law => unplacedReason(law.placement))
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap,
      Map("at-spans" -> 65, "whole-work" -> 70)
    )

    // The sixty-five reachability violations name a situation, so they carry that situation's own
    // support. The seventy derivation violations name a chart candidate the validator cannot
    // resolve to an address, and they claim no words rather than guessing at one.
    val placed = laws(scene).filter(_.placement match
      case EpistemicPlacement.AtSpans(_) => true
      case _                             => false)
    assertEquals(placed.map(_.violation.law).distinct, Vector("hierarchy.situation-root-reachable"))
    placed.foreach { law =>
      val subject = law.violation.address.getOrElse(fail("a placed law names its subject"))
      val support = Addressable[StoryRef]
        .parse(subject)
        .flatMap(compilation.draft.supporting)
        .getOrElse(fail(s"no support for ${subject.render}"))
      law.placement match
        case EpistemicPlacement.AtSpans(spans) =>
          assert(spans.refs.toVector.forall(support.refs.toVector.contains))
        case EpistemicPlacement.NoDiscoursePosition(_) => fail("expected placed spans")
    }
  }

  test("six context frames band the discourse axis, and none of them is a hull") {
    assertEquals(bands(scene).size, 6)
    assertEquals(bands(scene).map(_.lane).sorted, Vector(0, 1, 2, 3, 4, 5))
    assert(bands(scene).forall(_.basis == ContextBandBasis.ExactScopeEvidence))

    val narrated = bands(scene)
      .find(_.kind == ContextKind.NarratedWorld)
      .getOrElse(
        fail("the narrated world has no band")
      )
    assertEquals(narrated.lane, 0)
    assertEquals(narrated.parent, None)

    // The proof that a band is evidence and not a hull: the narrated world's support is 281
    // separate runs of text. A hull would be one extent covering everything between the first and
    // the last, which would claim the five speech frames as narration.
    assertEquals(narrated.extents.length, 281)
    val hull = narrated.extents.reduceLeft((a, b) => a.hull(b))
    assertNotEquals(narrated.extents.length, 1)
    assert(
      narrated.extents.toVector.map(e => e.x1Exclusive - e.x0).sum < hull.x1Exclusive - hull.x0,
      "a band that covered its own hull would be claiming the gaps between its runs"
    )

    // Each of the five speech frames is exactly one contiguous quotation, under the narrated root.
    val speech = bands(scene).filter(_.kind != ContextKind.NarratedWorld).sortBy(_.lane)
    assertEquals(speech.size, 5)
    assert(speech.forall(_.parent.isDefined))
    assertEquals(
      speech.map(band => (band.lane, band.extents.head.x0, band.extents.head.x1Exclusive)),
      Vector((1, 891, 982), (2, 644, 751), (3, 451, 551), (4, 599, 625), (5, 1724, 1900))
    )
    assert(speech.forall(_.extents.length == 1))
  }

  test("a situation's context is reachable from its own mark, and the retelling is not narration") {
    val frames = compilation.draft.graph.contexts
    assert(landmarks(scene).forall(mark => frames.contains(mark.context)))

    // Fifty-three situations are narrated; twelve are held inside a speech frame. A viewer that
    // could not reach the context from the mark would draw all sixty-five the same way.
    val byNarration =
      landmarks(scene).partition(mark => frames(mark.context).kind == ContextKind.NarratedWorld)
    assertEquals(byNarration._1.size, 53)
    assertEquals(byNarration._2.size, 12)

    // The survivor's retelling: the band at the offsets StoryBuildSuite measured independently,
    // the six situations it holds, and the fact that every one of them lies inside the quotation.
    val (start, end) = RetellingQuotation
    val retelling = bands(scene)
      .find(band => band.extents.head.x0 == start && band.extents.head.x1Exclusive == end)
      .getOrElse(fail("the retelling has no band"))
    assertEquals(
      retelling.kind,
      ContextKind.Speech(ContextHolder.Unattributed(HolderGap.NoCandidate))
    )
    val inside = landmarks(scene).filter(mark =>
      Addressable[StoryRef].parse(retelling.address).contains(StoryRef.Context(mark.context))
    )
    assertEquals(inside.size, 6)
    assert(inside.forall(mark => mark.at.x >= start && mark.at.x < end))
    assert(inside.forall(_.at.lane == retelling.lane))
    assert(byNarration._1.forall(_.at.lane == 0))
  }

  test("the textual twin is the audit surface for everything above") {
    val twin = scene.textualTwin
    assert(twin.startsWith("Narrative Atlas — DiscourseAtlas\n"), twin.take(120))
    assert(twin.contains("Basis: draft build\n"), "the twin states the basis")
    assert(twin.contains("promotable: false; derivation gaps: 70; violations: 135\n"))
    assert(twin.contains("  - compiler.required-derivation Error x69\n"))
    assert(twin.contains("  - hierarchy.single-primary-root Error x1\n"))
    assert(twin.contains("  - hierarchy.situation-root-reachable Error x65\n"))

    def lines(prefix: String): Vector[String] =
      twin.linesIterator.filter(_.startsWith(prefix)).toVector
    assertEquals(lines("  gap ").size, 70)
    assertEquals(lines("  abstention ").size, 1)
    assertEquals(lines("  unsatisfied-law ").size, 135)
    assertEquals(lines("  context-band ").size, 6)
    assertEquals(lines("  landmark ").size, 65)

    assert(lines("  gap ").forall(line => line.contains("channel=")))
    assert(lines("  gap ").exists(_.contains("state=Unresolved channel=Placeholder")))
    assert(lines("  gap ").exists(_.contains("state=Missing channel=OpenHatch")))
    assert(lines("  gap ").exists(_.contains("at=unplaced:whole-work")))
    assert(lines("  abstention ").head.contains("reason=provider-abstained:focus-not-predicate"))
    assert(lines("  unsatisfied-law ").forall(_.contains("channel=Bracket")))
    assert(lines("  landmark ").forall(_.contains("context=")))
    assert(lines("  context-band ").forall(_.contains("basis=ExactScopeEvidence")))
    assert(lines("  context-band ").exists(_.contains("x=[1724,1900)")))
  }

  test("the draft scene is deterministic, and a richer spec compiles the same absences") {
    assertEquals(sceneUnder(spec).textualTwin, scene.textualTwin)

    val richer = AtlasSpec(
      ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Sentences),
      ThreadPolicy.All(PositiveInt.unsafe(8))
    )
    assertEquals(
      census(sceneUnder(richer)).toVector.sorted,
      Vector(
        ("abstention", 1),
        ("context-band", 6),
        ("gap", 70),
        ("landmark", 65),
        ("surface-unit", 50),
        ("thread", 6),
        ("unsatisfied-law", 135)
      )
    )
  }
