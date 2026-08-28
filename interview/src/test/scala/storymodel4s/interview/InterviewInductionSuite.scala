package storymodel4s.interview

import munit.FunSuite

import storymodel4s.core.*
import storymodel4s.recall.*

/** Laws for bd-01M15BM30JHMDHC28EZX8WTQBY and bd-01M15BM32YMTAWDM7ZSVN0KCP5.
  *
  * InterviewSuite is held by W3; this suite owns the induction foils only.
  */
class InterviewInductionSuite extends FunSuite:

  private def unit(
      text: String,
      function: DiscourseFunction = DiscourseFunction.EpisodicAssertion
  ): RecallUnit =
    val src = StorySource.fromText(text).toOption.get
    RecallSegmenter.segment(src).ordered.head.copy(function = function, text = text)

  private def induce(text: String): (RecallGraph, Vector[Detail], InductionResult) =
    val src = StorySource.fromText(text).toOption.get
    val g = RecallSegmenter.segment(src)
    val ds = g.ordered.flatMap(u => AtomProjection.fromUnit(u, TurnId.unsafe("t")))
    (g, ds, TargetInduction.induce(g, ds, Cue("birthday cake", None, Some("birthday"))))

  private def modeOf(text: String, fragment: String): MemoryAddress =
    val (g, ds, r) = induce(text)
    val u = g.ordered.find(_.text.toLowerCase.contains(fragment)).get
    val d = ds.find(_.sourceUnit == u.id).get
    r.addresses(d.id).mode

  test("embedded retrieval-failure language does not erase an episodic unit") {
    val text = "I remember we ate cake but I don't remember the street."
    val u = unit(text)
    assertEquals(u.function, DiscourseFunction.EpisodicAssertion)
    assertEquals(TargetInduction.classify(u), TargetInduction.UnitClass.Episodic)
    val src = StorySource.fromText(text).toOption.get
    val base = RecallSegmenter.segment(src)
    val g = base.copy(units = base.units.map(_.copy(text = text, function = u.function)))
    val ds = AtomProjection.fromUnit(u, TurnId.unsafe("t"))
    val r = TargetInduction.induce(g, ds, Cue("birthday cake", None, Some("birthday")))
    assert(ds.nonEmpty)
    ds.foreach { d =>
      r.addresses(d.id).mode match
        case MemoryAddress.Episode(_, _) => ()
        case other                       => fail(s"expected an episode address, got $other")
    }
  }

  test("a pure SourceMonitoring unit still classifies as metacognitive") {
    val u = unit("I can't remember.", DiscourseFunction.SourceMonitoring)
    assertEquals(TargetInduction.classify(u), TargetInduction.UnitClass.Metacognitive)
  }

  test("an evaluative clause cannot demote an episodic unit") {
    val u = unit("It was great we ate cake.")
    assertEquals(TargetInduction.classify(u), TargetInduction.UnitClass.Episodic)
  }

  test("OtherEpisode and Habitual cues still refine episodic speech") {
    assertEquals(
      TargetInduction.classify(unit("The year before we went to Montreal.")),
      TargetInduction.UnitClass.OtherEpisode
    )
    assertEquals(
      TargetInduction.classify(unit("We always go out for birthdays.")),
      TargetInduction.UnitClass.Habitual
    )
  }

  test("then does not yank a continuous digression onto the target") {
    val text =
      "We ate cake at the restaurant. The year before we went to Montreal. Then we walked around Montreal."
    modeOf(text, "walked around montreal") match
      case MemoryAddress.Episode(_, EpisodeScope.OtherSpecific) => ()
      case other => fail(s"expected OtherSpecific, got $other")
  }

  test("loss of continuity still returns to the target without a connective") {
    val text =
      "We ate cake at the restaurant. The year before we went to Montreal. The waiter brought a cake."
    modeOf(text, "the waiter brought") match
      case MemoryAddress.Episode(_, EpisodeScope.TargetSpecific) => ()
      case other => fail(s"expected TargetSpecific, got $other")
  }

  test("an explicit return cue still returns to the target") {
    val text =
      "We ate cake at the restaurant. The year before we went to Montreal. Anyway we sang."
    modeOf(text, "anyway we sang") match
      case MemoryAddress.Episode(_, EpisodeScope.TargetSpecific) => ()
      case other => fail(s"expected TargetSpecific, got $other")
  }
