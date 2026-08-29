package storymodel4s.interview

import munit.FunSuite

import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Production-path reachability fixture for bd-01M16TAM38RKYXVJK113Y83M4B.
  *
  * Proves [[TargetInduction.PlacementBasis.Ambiguous]] is reached through the same clustering
  * function `induce` uses, and that stay / return / unattached near-misses route to different
  * bases. Address masses 0.4 / 0.3 / 0.3 are owned by the landed InterviewInductionSuite /
  * InterviewProfileSuite tests (46f6b99); this suite does not re-assert them.
  *
  * Synthetic researcher-authored recall; not participant text. No threshold or arithmetic change.
  */
class AmbiguousPlacementSuite extends FunSuite:

  private val cue = Cue("birthday cake", None, Some("birthday"))
  private val th = InductionConfig.default.continuityThreshold

  /** Seed (target) → other-episode marker → probe unit. */
  private def transcript(probe: String): String =
    s"We ate cake at the restaurant. The year before we went to Montreal. $probe"

  private def induce(text: String): (RecallGraph[Checked], Vector[Detail], InductionResult) =
    val src = StorySource.fromText(text).toOption.get
    val g = RecallSegmenter.segment(src)
    val ds = g.ordered.flatMap(u => AtomProjection.fromUnit(u, TurnId.unsafe("t")))
    (g, ds, TargetInduction.induce(g, ds, cue))

  private def unitContaining(g: RecallGraph[Checked], fragment: String): RecallUnit =
    g.ordered
      .find(_.text.toLowerCase.contains(fragment.toLowerCase))
      .getOrElse(fail(s"no unit contains '$fragment'; units=${g.ordered.map(_.text)}"))

  test("capacity: the three-sentence transcript segments into three episodic units") {
    val (g, _, _) = induce(transcript("We ate cake in Montreal."))
    assert(g.ordered.size >= 3, s"need three units, got ${g.ordered.map(_.text)}")
    val seed = unitContaining(g, "ate cake at the restaurant")
    val marker = unitContaining(g, "year before")
    val probe = unitContaining(g, "ate cake in montreal")
    assertEquals(TargetInduction.classify(seed), TargetInduction.UnitClass.Episodic)
    assertEquals(TargetInduction.classify(marker), TargetInduction.UnitClass.OtherEpisode)
    assertEquals(TargetInduction.classify(probe), TargetInduction.UnitClass.Episodic)
  }

  test("two-way continuity holds on the cake-in-Montreal probe and fails on each near-miss") {
    val twoWay = induce(transcript("We ate cake in Montreal."))
    val stay = induce(transcript("Then we walked around Montreal."))
    val ret = induce(transcript("The waiter brought a cake."))
    val neither = induce(transcript("The dog barked in the park."))

    def sides(
        run: (RecallGraph[Checked], Vector[Detail], InductionResult),
        frag: String
    ): (Boolean, Boolean) =
      val g = run._1
      val seed = unitContaining(g, "ate cake at the restaurant")
      val marker = unitContaining(g, "year before")
      val probe = unitContaining(g, frag)
      val withDig = TargetInduction.ClusterContinuity.holds(probe, marker, None, th)
      val withTarget = TargetInduction.ClusterContinuity.holds(probe, seed, None, th)
      (withDig, withTarget)

    val (digTwo, tgtTwo) = sides(twoWay, "ate cake in montreal")
    val (digStay, tgtStay) = sides(stay, "walked around montreal")
    val (digRet, tgtRet) = sides(ret, "waiter brought")
    val (digNone, tgtNone) = sides(neither, "dog barked")

    assert(digTwo && tgtTwo, s"two-way probe must hold both sides, got dig=$digTwo tgt=$tgtTwo")
    assert(digStay && !tgtStay, s"stay near-miss: dig=$digStay tgt=$tgtStay")
    assert(!digRet && tgtRet, s"return near-miss: dig=$digRet tgt=$tgtRet")
    assert(!digNone && !tgtNone, s"unattached near-miss: dig=$digNone tgt=$tgtNone")
  }

  test("clusterAssignments reports Ambiguous on the two-way probe and not on near-misses") {
    def basis(text: String, frag: String): TargetInduction.PlacementBasis =
      val src = StorySource.fromText(text).toOption.get
      val g = RecallSegmenter.segment(src)
      val u = unitContaining(g, frag)
      TargetInduction
        .clusterAssignments(g)
        .getOrElse(u.id, fail(s"no assignment for ${u.text}"))
        .basis

    assertEquals(
      basis(transcript("We ate cake in Montreal."), "ate cake in montreal"),
      TargetInduction.PlacementBasis.Ambiguous
    )
    assertEquals(
      basis(transcript("Then we walked around Montreal."), "walked around montreal"),
      TargetInduction.PlacementBasis.ContinuityStay
    )
    assertEquals(
      basis(transcript("The waiter brought a cake."), "waiter brought"),
      TargetInduction.PlacementBasis.ContinuityReturn
    )
    assertEquals(
      basis(transcript("The dog barked in the park."), "dog barked"),
      TargetInduction.PlacementBasis.Unattached
    )
  }
