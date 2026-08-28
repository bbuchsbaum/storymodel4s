package storymodel4s.interview

import cats.data.NonEmptyVector
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll

import storymodel4s.core.*
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.proposition.ParticipantRole
import storymodel4s.recall.*
import storymodel4s.interview.scoring.*

class InterviewSuite extends ScalaCheckSuite:

  // ---- Distribution ------------------------------------------------------------------------

  private val weightsGen: Gen[Vector[(String, Double)]] =
    Gen
      .nonEmptyListOf(Gen.zip(Gen.oneOf("a", "b", "c", "d"), Gen.choose(0.0, 10.0)))
      .map(_.toVector)

  property("Distribution normalizes to unit mass and entropy is bounded") {
    forAll(weightsGen) { ws =>
      Distribution.of(ws) match
        case Left(_)  => ws.map(_._2).sum <= 0.0
        case Right(d) =>
          val total = d.support.toVector.map(d.apply).sum
          math.abs(total - 1.0) < 1e-9 && d.entropy >= -1e-12 &&
          d.entropy <= math.log(d.support.size.toDouble) + 1e-9 && d.support.contains(d.mode)
    }
  }

  test("Distribution rejects zero and negative mass") {
    assert(Distribution.of(Vector("a" -> 0.0)).isLeft)
    assert(Distribution.of(Vector("a" -> -1.0, "b" -> 2.0)).isLeft)
    assertEquals(Distribution.point("x")("x"), 1.0)
  }

  test("Distribution.mode is deterministic under ties") {
    val d = Distribution.unsafe("b" -> 1.0, "a" -> 1.0)
    assertEquals(d.mode, "a")
    assertEquals(d.mass(_ == "a"), 0.5)
  }

  // ---- Pseudonymizer -----------------------------------------------------------------------

  private val plain = StorySource
    .fromText("Yesterday Anna met Bob Smith at Bob's cafe. Anna laughed; annabelle did not.")
    .toOption
    .get

  test("pseudonymization is relational, whole-word, deterministic, and reversible with the key") {
    val table = Vector(
      PseudonymEntry("Anna", "[PERSON_1]"),
      PseudonymEntry("Bob Smith", "[PERSON_2]"),
      PseudonymEntry("Bob", "[PERSON_2]")
    )
    val p = Pseudonymizer.pseudonymize(plain, table).toOption.get
    val out = p.source.canonicalText
    assertEquals(
      out,
      "Yesterday [PERSON_1] met [PERSON_2] at [PERSON_2]'s cafe. [PERSON_1] laughed; annabelle did not."
    )
    assert(!out.contains("Anna "))
    assertEquals(Pseudonymizer.pseudonymize(plain, table).toOption.get.source.id, p.source.id)
    // Reversal is exact when the key is one-to-one.
    val unique =
      Pseudonymizer.pseudonymize(plain, Vector(PseudonymEntry("Anna", "[PERSON_1]"))).toOption.get
    assertEquals(Pseudonymizer.reverse(unique), plain.canonicalText)
    // pseudonymization never mutates the original
    assertEquals(plain.canonicalText.take(9), "Yesterday")
  }

  test("untouched token spans map through the offset map exactly") {
    val table = Vector(PseudonymEntry("Anna", "[PERSON_1]"))
    val p = Pseudonymizer.pseudonymize(plain, table).toOption.get
    val atlas = SurfaceAnalyzer.analyze(plain)
    val newText = p.source.canonicalText
    atlas.tokens.foreach { tok =>
      val original = atlas.text(tok)
      if !original.equalsIgnoreCase("Anna") then
        val mapped = p.mapSpan(tok.span).toOption.get
        assertEquals(mapped.slice(newText).toOption.get, original, s"token $original")
    }
    val anna = atlas.tokens.find(t => atlas.text(t) == "Anna").get
    assertEquals(p.mapSpan(anna.span).toOption.get.slice(newText).toOption.get, "[PERSON_1]")
  }

  // ---- InterviewSource validation ----------------------------------------------------------

  private def twoTurnSource(probeTurnRole: SpeakerRole, phase: Option[InterviewPhase]) =
    val src = StorySource
      .fromText("Interviewer:\n\nTell me more.\n\nParticipant:\n\nWe ate cake.")
      .toOption
      .get
    val atlas = SurfaceAnalyzer.analyze(src)
    val t = src.canonicalText
    val i = t.indexOf("Tell me more.")
    val p = t.indexOf("We ate cake.")
    val iv = SpeakerId.unsafe("i")
    val pv = SpeakerId.unsafe("p")
    val probe = PromptId.unsafe("probe:g")
    val turns = Vector(
      TranscriptTurn(
        TurnId.unsafe("t0"),
        iv,
        SpanSet.one(TextSpan.unsafe(i, i + 13)),
        None,
        phase,
        Some(probe)
      ),
      TranscriptTurn(
        TurnId.unsafe("t1"),
        pv,
        SpanSet.one(TextSpan.unsafe(p, p + 12)),
        None,
        phase,
        Some(probe)
      )
    )
    InterviewSource(
      TranscriptAtlas(atlas, turns, Map(iv -> probeTurnRole, pv -> SpeakerRole.Participant)),
      Cue("Tell me more.", None, None),
      Vector(Probe(probe, ProbeKind.General, TurnId.unsafe("t0"))),
      None
    )

  test("probes must reference interviewer turns with a consistent phase") {
    assert(
      InterviewSource
        .validated(twoTurnSource(SpeakerRole.Interviewer, Some(InterviewPhase.GeneralProbe)))
        .isRight
    )
    assert(
      InterviewSource
        .validated(twoTurnSource(SpeakerRole.Participant, Some(InterviewPhase.GeneralProbe)))
        .isLeft
    )
    assert(
      InterviewSource
        .validated(twoTurnSource(SpeakerRole.Interviewer, Some(InterviewPhase.SpecificProbe)))
        .isLeft
    )
    assert(InterviewSource.validated(twoTurnSource(SpeakerRole.Interviewer, None)).isRight)
  }

  // ---- Atoms -------------------------------------------------------------------------------

  private def unit(
      text: String,
      function: DiscourseFunction = DiscourseFunction.EpisodicAssertion
  ): RecallUnit =
    val src = StorySource.fromText(text).toOption.get
    val g = RecallSegmenter.segment(src)
    g.ordered.head.copy(function = function)

  test("projection yields an event and participants for a predicate unit and never needs a frame") {
    val u = unit("I walked to the restaurant with my sister.")
    val ds = AtomProjection.fromUnit(u, TurnId.unsafe("t"), chart = None)
    val atoms = ds.map(_.atom)
    assert(atoms.exists { case DetailAtom.EventOccurrence(_) => true; case _ => false })
    assert(atoms.exists {
      case DetailAtom.ParticipantFact(_, ParticipantRole.Agent, e) => e == AtomProjection.Speaker
      case _                                                       => false
    })
    assert(atoms.exists {
      case DetailAtom.SpatialFact(SpatialClaim.AtLocation(_, "restaurant")) => true; case _ => false
    })
    assert(ds.forall(_.expectedCountMass == Estimate.observed(1.0)))
    assertEquals(ds.map(_.id).distinct.size, ds.size)
  }

  test("a unit without propositional content still yields one countable atom") {
    val u = unit("Well.", DiscourseFunction.TaskCommentary)
    val ds = AtomProjection.fromUnit(u, TurnId.unsafe("t"))
    assertEquals(ds.size, 1)
    ds.head.atom match
      case DetailAtom.AttributeFact(_, Attribute("statement", _)) => ()
      case other                                                  => fail(s"unexpected $other")
  }

  test("emotion and perceptual cues become mental-state and perceptual atoms") {
    val u = unit("I felt embarrassed when the room went dark.")
    val atoms = AtomProjection.fromUnit(u, TurnId.unsafe("t")).map(_.atom)
    assert(
      atoms.contains(
        DetailAtom.MentalStateFact(
          AtomProjection.Speaker,
          MentalState(MentalStateKind.Emotion, "embarrassment")
        )
      )
    )
    assert(atoms.exists {
      case DetailAtom.PerceptualFact(_, Modality.Visual, _) => true; case _ => false
    })
  }

  // ---- Assessment independence (§59) ------------------------------------------------------

  test("target membership, specificity and re-experiencing are independent fields") {
    val u = unit("We ate cake at the restaurant.")
    val d = AtomProjection.fromUnit(u, TurnId.unsafe("t")).head
    val fp = Fingerprint.unsafe("test")
    val meta = ClaimMeta(
      ClaimId.unsafe("c"),
      EpistemicStatus.Hypothesized,
      Credence.unsafeRaw(0.9),
      NonEmptyVector.one(
        Evidence(EvidenceId.unsafe("e"), Some(d.support), Set.empty, fp, StageId.unsafe("s"))
      ),
      Provenance.deterministic("test", Checksum.ofText("cfg"))
    )
    val highInternalNoPhenomenology = DetailAssessment(
      d,
      Distribution.point(
        MemoryAddress.Episode(EpisodeId.unsafe("ep"), EpisodeScope.TargetSpecific)
      ),
      Distribution.point(DetailFacet.Event),
      Estimate.observed(0.95),
      ExperientialEvidence.none,
      EpistemicStatus.Hypothesized,
      PromptContext(InterviewPhase.FreeRecall, None),
      None,
      meta
    )
    assertEquals(highInternalNoPhenomenology.targetMass, 1.0)
    assert(!highInternalNoPhenomenology.experiential.firstPersonLanguage)
    val lowSpecificityButReliving = highInternalNoPhenomenology.copy(
      specificity = Estimate.missing(MissingReason.ProviderAbstained),
      experiential = ExperientialEvidence(
        Some(Estimate.observed(1.0)),
        true,
        Vector(SourceMonitoring.DirectMemory)
      )
    )
    assertEquals(lowSpecificityButReliving.targetMass, 1.0)
    assert(!lowSpecificityButReliving.specificity.isObserved)
  }

  test("inferred episodes cannot carry surface-explicit status") {
    val bad = EpisodeModel.of(
      EpisodeId.unsafe("x"),
      EpisodeScope.TargetSpecific,
      Vector.empty,
      Set.empty,
      Set.empty,
      Vector.empty,
      Vector.empty,
      EpistemicStatus.SurfaceExplicit,
      None
    )
    assert(bad.isLeft)
    val ok = EpisodeModel.of(
      EpisodeId.unsafe("x"),
      EpisodeScope.TargetSpecific,
      Vector.empty,
      Set.empty,
      Set.empty,
      Vector.empty,
      Vector.empty,
      EpistemicStatus.Hypothesized,
      None
    )
    assert(ok.isRight)
  }

  // ---- Scoring laws ------------------------------------------------------------------------

  private val addressGen: Gen[Distribution[MemoryAddress]] =
    val ep = EpisodeId.unsafe("ep")
    val alts: Vector[MemoryAddress] = Vector(
      MemoryAddress.Episode(ep, EpisodeScope.TargetSpecific),
      MemoryAddress.Episode(ep, EpisodeScope.OtherSpecific),
      MemoryAddress.PersonalKnowledge(PersonalKnowledgeKind.HabitOrRoutine),
      MemoryAddress.GeneralKnowledge,
      MemoryAddress.Discourse(InterviewDiscourseFunction.Metacognitive),
      MemoryAddress.Unresolved
    )
    Gen
      .listOfN(alts.size, Gen.choose(0.0, 1.0))
      .map(ws =>
        Distribution.of(alts.zip(ws)).getOrElse(Distribution.point(MemoryAddress.Unresolved))
      )

  private val facetGen: Gen[Distribution[DetailFacet]] =
    Gen
      .listOfN(DetailFacet.values.length, Gen.choose(0.0, 1.0))
      .map(ws =>
        Distribution
          .of(DetailFacet.values.toVector.zip(ws))
          .getOrElse(Distribution.point(DetailFacet.Other))
      )

  property("category distribution preserves total mass and internal mass equals target mass") {
    forAll(addressGen, facetGen) { (addr, facets) =>
      val u = unit("We ate cake.")
      val d = AtomProjection.fromUnit(u, TurnId.unsafe("t")).head
      val meta = ClaimMeta(
        ClaimId.unsafe("c"),
        EpistemicStatus.Hypothesized,
        Credence.unsafeRaw(0.5),
        NonEmptyVector.one(
          Evidence(
            EvidenceId.unsafe("e"),
            Some(d.support),
            Set.empty,
            Fingerprint.unsafe("f"),
            StageId.unsafe("s")
          )
        ),
        Provenance.deterministic("t", Checksum.ofText("c"))
      )
      val a = DetailAssessment(
        d,
        addr,
        facets,
        Estimate.observed(0.5),
        ExperientialEvidence.none,
        EpistemicStatus.Hypothesized,
        PromptContext(InterviewPhase.FreeRecall, None),
        None,
        meta
      )
      val cats = TraditionalScoring.categoryDistribution(a, AiScoringPolicy.Standard)
      val internal = cats.mass(_.isInternal)
      val expectedInternal = a.targetMass * facets.mass(_ != DetailFacet.Other)
      math.abs(internal - expectedInternal) < 1e-9
    }
  }

  test("Interval contains its point and ExpectedCount adds componentwise") {
    val a = ExpectedCount(1.0, Interval(0.5, 2.0))
    val b = ExpectedCount(0.25, Interval(0.0, 1.0))
    val s = ExpectedCount.+(a, b)
    assertEquals(s, ExpectedCount(1.25, Interval(0.5, 3.0)))
    assert(s.interval.contains(s.point))
  }

  given Arbitrary[Double] = Arbitrary(Gen.choose(0.0, 1.0))
