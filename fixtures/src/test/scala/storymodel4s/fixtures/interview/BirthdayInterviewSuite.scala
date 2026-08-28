package storymodel4s.fixtures.interview

import munit.FunSuite

import storymodel4s.core.*
import storymodel4s.interview.*
import storymodel4s.interview.scoring.*

class BirthdayInterviewSuite extends FunSuite:
  private lazy val model = BirthdayInterview.model
  private lazy val scores = BirthdayInterview.scores
  private lazy val profile = BirthdayInterview.profile

  private def unitTextOf(a: DetailAssessment): String =
    model.recall.byId(a.detail.sourceUnit).text.toLowerCase

  private def assessmentsMentioning(fragment: String): Vector[DetailAssessment] =
    model.assessments.filter(a => unitTextOf(a).contains(fragment.toLowerCase))

  test("transcript validates and only participant turns yield units") {
    assert(model.recall.units.nonEmpty)
    val participantTurns = model.source.transcript
      .turnsByRole(SpeakerRole.Participant)
      .map(_.id)
      .toSet
    assert(model.details.forall(d => participantTurns.contains(d.turn)))
    assertEquals(model.recall.units.map(_.ordinal), model.recall.units.indices.toVector)
  }

  test("habitual family statement is routed to repeated/personal knowledge") {
    val as = assessmentsMentioning("always goes out")
    assert(as.nonEmpty)
    as.foreach { a =>
      val m = a.address.mode
      assert(
        m == MemoryAddress.PersonalKnowledge(PersonalKnowledgeKind.HabitOrRoutine) ||
          (m match
            case MemoryAddress.Episode(_, EpisodeScope.RepeatedOrCategoric) => true
            case _                                                          => false),
        s"unexpected address $m"
      )
      assert(a.targetMass < 0.2)
    }
  }

  test("prior-year trip is another specific episode, not the target") {
    val as = assessmentsMentioning("Montreal")
    assert(as.nonEmpty)
    as.foreach { a =>
      a.address.mode match
        case MemoryAddress.Episode(_, EpisodeScope.OtherSpecific) => ()
        case other => fail(s"expected OtherSpecific, got $other")
      assert(a.targetMass < 0.3)
    }
    assert(model.otherEpisodes.exists(_.scope == EpisodeScope.OtherSpecific))
  }

  test("explicit retrieval failure is metacognitive discourse") {
    val as = assessmentsMentioning("can't remember")
    assert(as.nonEmpty)
    as.foreach { a =>
      assertEquals(
        a.address.mode,
        MemoryAddress.Discourse(InterviewDiscourseFunction.Metacognitive)
      )
    }
  }

  test("restaurant is a target location with attributes") {
    val spatial = model.assessments.filter { a =>
      a.detail.atom match
        case DetailAtom.SpatialFact(SpatialClaim.AtLocation(_, "restaurant")) => true
        case _                                                                => false
    }
    assert(spatial.nonEmpty)
    spatial.foreach(a => assert(a.targetMass >= 0.5, s"target mass ${a.targetMass}"))
    val attrs = model.details.collect {
      case Detail(
            _,
            DetailAtom.AttributeFact(AtomTarget.Entity(_), Attribute("description", v)),
            _,
            _,
            _,
            _
          ) =>
        v
    }
    assert(attrs.contains("small") && attrs.contains("french"), attrs.toString)
  }

  test("embarrassment is a target mental state and the causal link is recorded") {
    val emotion = model.assessments.filter { a =>
      a.detail.atom match
        case DetailAtom.MentalStateFact(_, MentalState(MentalStateKind.Emotion, "embarrassment")) =>
          true
        case _ => false
    }
    assert(emotion.nonEmpty)
    emotion.foreach(a => assert(a.targetMass >= 0.5))
    val causal = model.details.collect {
      case Detail(_, DetailAtom.RelationalFact(NarrativeRelationRef.Causal(c, e)), _, _, _, _) =>
        (c, e)
    }
    assert(causal.nonEmpty, "expected a causal relational atom")
    val embarrassedSit =
      emotion.map(a => AtomProjection.situationOf(model.recall.byId(a.detail.sourceUnit)))
    assert(causal.exists { case (_, e) => embarrassedSit.contains(e) })
    assert(model.target.exists(_.relations.nonEmpty))
  }

  test("weather and visual detail are target perceptual facts") {
    val perceptual = model.assessments.filter { a =>
      a.detail.atom match
        case DetailAtom.PerceptualFact(_, Modality.Visual, _) => true
        case _                                                => false
    }
    assert(perceptual.exists(a => unitTextOf(a).contains("raining")))
    assert(perceptual.exists(a => unitTextOf(a).contains("fogged")))
    assert(
      perceptual
        .filter(_.promptContext.phase == InterviewPhase.FreeRecall)
        .forall(_.targetMass >= 0.5)
    )
  }

  test("expected internal count exceeds expected external count") {
    assert(scores.expectedInternal > scores.expectedExternal, scores.expected.toString)
    assert(scores.expectedInternal > 0.0)
  }

  test("purity is strictly between 0 and 1") {
    val p = profile.eventPurity.toOption.getOrElse(fail("purity undefined"))
    assert(p > 0.0 && p < 1.0, p.toString)
  }

  test("probe gain is defined and in [0, 1]") {
    val g = profile.probeGain.toOption.getOrElse(fail("probe gain undefined"))
    assert(g >= 0.0 && g <= 1.0, g.toString)
    assert(g > 0.0, "post-probe material should add target mass")
  }

  test("intervals contain the point estimates") {
    scores.expected.foreach { case (c, e) =>
      assert(e.interval.contains(e.point), s"$c: $e")
      assert(e.interval.low >= 0.0)
    }
    scores.byPhase.values.foreach(_.foreach { case (_, e) => assert(e.interval.contains(e.point)) })
  }

  test("hard counts round the expected counts under the standard policy") {
    scores.expected.foreach { case (c, e) =>
      assertEquals(scores.hard(c), math.round(e.point).toInt)
    }
  }

  test("phases are attributed to the right probe context") {
    val phases = model.assessments.map(_.promptContext.phase).toSet
    assertEquals(
      phases,
      Set[InterviewPhase](
        InterviewPhase.FreeRecall,
        InterviewPhase.GeneralProbe,
        InterviewPhase.SpecificProbe
      )
    )
    assert(
      model.assessments
        .filter(_.promptContext.phase == InterviewPhase.SpecificProbe)
        .forall(_.promptContext.afterProbe.contains(BirthdayInterview.specificProbe))
    )
  }

  test("first-person and source-monitoring evidence are recorded from the specific probe") {
    val probeAs = model.assessments.filter(_.promptContext.phase == InterviewPhase.SpecificProbe)
    assert(probeAs.exists(_.experiential.firstPersonLanguage))
    assert(probeAs.exists(_.sourceMonitoring.contains(SourceMonitoring.DirectMemory)))
    assert(profile.sourceMonitoring.getOrElse(SourceMonitoring.DirectMemory, 0) >= 1)
  }

  test("episodic density per word and per second are defined") {
    assert(profile.episodicDensityPerWord.isObserved)
    assert(profile.episodicDensityPerSecond.isObserved)
    assert(profile.perceptualProfile.nonEmpty)
    assert(profile.mentalStateProfile.contains(MentalStateKind.Emotion))
  }

  test("scoring is deterministic") {
    val again = BirthdayInterview.build()
    assertEquals(TraditionalScoring.score(again), scores)
    assertEquals(ProfileScoring.profile(again), profile)
    assertEquals(
      again.assessments.map(a => (a.detail.id, a.address.toVector)),
      model.assessments.map(a => (a.detail.id, a.address.toVector))
    )
  }
