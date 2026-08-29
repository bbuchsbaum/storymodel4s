package storymodel4s.interview

import munit.FunSuite

import storymodel4s.core.*
import storymodel4s.features.{Estimate, MissingReason}
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

  private def distOf(text: String, fragment: String): Distribution[MemoryAddress] =
    val (g, ds, r) = induce(text)
    val u = g.ordered.find(_.text.toLowerCase.contains(fragment)).get
    val d = ds.find(_.sourceUnit == u.id).get
    r.addresses(d.id)

  private def modeOf(text: String, fragment: String): MemoryAddress =
    distOf(text, fragment).mode

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

  test("a unit continuous with BOTH clusters reaches Ambiguous, and the corpus had no such case") {
    // The genuinely hard case: the speaker says something that belongs to the target AND to the
    // digression at once. No fixture in this suite reached it before - measured, 33 details across
    // the four texts, zero Ambiguous - so the branch that decides it was untested. That is the
    // case where an honest placement summary matters most.
    //
    // "restaurant" and "cake" tie it to the target; "Montreal" ties it to the digression.
    val text =
      "We ate cake at the restaurant. The year before we went to Montreal. " +
        "The Montreal restaurant had cake too."
    val (g, ds, r) = induce(text)
    val third = g.ordered.last
    val ids = ds.filter(_.support.minSpan.start >= third.minSpan.start).map(_.id)
    assert(ids.nonEmpty, "third sentence produced no details")

    ids.flatMap(r.addresses.get).foreach { d =>
      val unresolved = d(MemoryAddress.Unresolved)
      val placed = d.weights.filter(_._1 != MemoryAddress.Unresolved)
      // The Ambiguous signature from induce(): primary Unresolved 0.4, the remaining 0.6 split
      // evenly between the target and the digression. Asserted as VALUES, because "it is split"
      // is true of many shapes and only these numbers identify the branch that produced them.
      assertEqualsDouble(unresolved, 0.4, 1e-9, d.weights.toString)
      assertEquals(placed.size, 2, d.weights.toString)
      placed.values.foreach(v => assertEqualsDouble(v, 0.3, 1e-9, d.weights.toString))

      // AND THE CONSEQUENCE, which is the reason this fixture exists. Resolved mass is 0.6, which
      // clears PlacementResolution's 0.5 abstention threshold - the summary says "resolved enough,
      // publish". But ProfileScoring's membership threshold is also 0.5 and applies to mass on ONE
      // class, and neither class has more than 0.3. So the same detail is simultaneously
      // publishable and a member of nothing. Two thresholds, both 0.5, measuring different
      // quantities.
      val resolved = 1.0 - unresolved
      assert(resolved >= 0.5, s"resolved $resolved")
      assert(placed.values.forall(_ < 0.5), placed.toString)
    }
  }

  test("then does not yank a continuous digression onto the target") {
    val text =
      "We ate cake at the restaurant. The year before we went to Montreal. Then we walked around Montreal."
    modeOf(text, "walked around montreal") match
      case MemoryAddress.Episode(_, EpisodeScope.OtherSpecific) => ()
      case other => fail(s"expected OtherSpecific, got $other")
  }

  test("lost digression continuity returns only when the target still holds") {
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

  test("ClusterContinuity is symmetric and is the stay/return predicate") {
    val text =
      "We ate cake at the restaurant. The year before we went to Montreal. Then we walked around Montreal."
    val (g, _, _) = induce(text)
    val cake = g.ordered.find(_.text.toLowerCase.contains("ate cake")).get
    val montreal = g.ordered.find(_.text.toLowerCase.contains("year before")).get
    val walk = g.ordered.find(_.text.toLowerCase.contains("walked")).get
    val th = InductionConfig.default.continuityThreshold
    assertEquals(
      TargetInduction.ClusterContinuity.holds(montreal, walk, None, th),
      TargetInduction.ClusterContinuity.holds(walk, montreal, None, th)
    )
    assert(TargetInduction.ClusterContinuity.holds(montreal, walk, None, th))
    assert(!TargetInduction.ClusterContinuity.holds(cake, walk, None, th))
    modeOf(text, "walked around montreal") match
      case MemoryAddress.Episode(_, EpisodeScope.OtherSpecific) => ()
      case other => fail(s"stay must match ClusterContinuity, got $other")
  }

  test("discontinuous with both digression and target is not a silent target") {
    val text =
      "We ate cake at the restaurant. The year before we went to Montreal. The dog barked in the park."
    val addr = distOf(text, "the dog barked")
    assertEquals(addr.mode, MemoryAddress.Unresolved)
    assert(!addr.support.exists(_.isTargetSpecific), addr.support.toString)
    assert(
      addr.support.exists {
        case MemoryAddress.Episode(_, EpisodeScope.OtherSpecific) => true
        case _                                                    => false
      },
      addr.support.toString
    )
  }

  test("continuous with both digression and target is recorded as ambiguous") {
    val text =
      "We ate cake at the restaurant. The year before we went to Montreal. We ate cake in Montreal."
    val addr = distOf(text, "ate cake in montreal")
    assertEquals(addr.mode, MemoryAddress.Unresolved)
    assert(addr.support.exists(_.isTargetSpecific), addr.support.toString)
    assert(
      addr.support.exists {
        case MemoryAddress.Episode(_, EpisodeScope.OtherSpecific) => true
        case _                                                    => false
      },
      addr.support.toString
    )
  }

  test("unattached keeps materialized competitor mass when that cluster wins the target") {
    val text =
      "We ate at the restaurant. The year before we went to Montreal. The birthday cake had candles."
    val addr = distOf(text, "birthday cake")
    assertEquals(addr.mode, MemoryAddress.Unresolved)
    assert(
      addr(MemoryAddress.Unresolved) > 0.5 && addr(MemoryAddress.Unresolved) < 0.6,
      addr.toVector.toString
    )
    assert(addr.support.exists(_.isTargetSpecific), addr.support.toString)
    assert(addr.mass(_.isTargetSpecific) > 0.4, addr.toVector.toString)
  }

  test("an absent class lookup is missing, never an observed specificity") {
    assertEquals(
      TargetInduction.specificityOf(None, anchored = false),
      Estimate.missing(SpecificityMissingReason.Unclassified)
    )
  }

  test("an explicitly uninterpretable unit has a distinct missing specificity") {
    val text = "I cannot interpret this memory."
    val source = StorySource.fromText(text).toOption.get
    val base = RecallSegmenter.segment(source)
    val uninterpretable = base.ordered.head.copy(function = DiscourseFunction.Uninterpretable)
    val graph = base.copy(units = Vector(uninterpretable))
    val details = AtomProjection.fromUnit(uninterpretable, TurnId.unsafe("uninterpretable"))
    val result = TargetInduction.induce(graph, details, Cue("memory", None, None))

    assert(details.nonEmpty)
    details.foreach { detail =>
      assertEquals(
        result.specificity(detail.id),
        Estimate.missing(SpecificityMissingReason.ClassifiedUninterpretable)
      )
    }
  }

  test("a detail whose source unit is absent gets a source-absent missing reason") {
    val source = StorySource.fromText("We ate soup.").toOption.get
    val graph = RecallSegmenter.segment(source)
    val foreign = unit("I remember the birthday cake.")
    val details = AtomProjection.fromUnit(foreign, TurnId.unsafe("foreign"))
    val result = TargetInduction.induce(graph, details, Cue("birthday", None, None))

    assert(details.nonEmpty)
    details.foreach { detail =>
      assertEquals(
        result.specificity(detail.id),
        Estimate.missing(SpecificityMissingReason.SourceUnitAbsent)
      )
    }
  }

  test("assessment never turns an omitted specificity entry into observed zero") {
    val text = "I remember we ate cake."
    val source = StorySource.fromText(text).toOption.get
    val graph = RecallSegmenter.segment(source)
    val turnId = TurnId.unsafe("assess")
    val details = graph.ordered.flatMap(u => AtomProjection.fromUnit(u, turnId))
    val result = TargetInduction
      .induce(graph, details, Cue("cake", None, None))
      .copy(specificity = Map.empty)
    val participant = SpeakerId.unsafe("participant")
    val interview = InterviewSource(
      TranscriptAtlas.unsafe(
        SurfaceAnalyzer.analyze(source),
        Vector(
          TranscriptTurn(
            turnId,
            participant,
            SpanSet.one(TextSpan.unsafe(0, source.canonicalText.length)),
            None,
            Some(InterviewPhase.FreeRecall),
            None
          )
        ),
        Map(participant -> SpeakerRole.Participant)
      ),
      Cue("cake", None, None),
      Vector.empty,
      None
    )
    val assessments = TargetInduction.assess(interview, graph, details, result)

    assert(assessments.nonEmpty)
    assessments.foreach { assessment =>
      assertEquals(assessment.specificity, Estimate.missing(MissingReason.Unknown))
    }
  }
