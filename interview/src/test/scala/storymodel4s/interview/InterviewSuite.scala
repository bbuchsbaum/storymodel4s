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
    // Relational (many-to-one) keys are explicitly irreversible.
    assert(!p.isReversible)
    assert(Pseudonymizer.reverse(p).isLeft)
    // Reversal is exact when the key is one-to-one.
    val unique =
      Pseudonymizer.pseudonymize(plain, Vector(PseudonymEntry("Anna", "[PERSON_1]"))).toOption.get
    assert(unique.isReversible)
    assertEquals(Pseudonymizer.reverse(unique), Right(plain.canonicalText))
    // pseudonymization never mutates the original
    assertEquals(plain.canonicalText.take(9), "Yesterday")
  }

  test("matching is case-sensitive by default and never rewrites ordinary words") {
    val src = StorySource.fromText("I will go when Will arrives. Mark the date, Mark.").toOption.get
    val strict = Pseudonymizer
      .pseudonymize(
        src,
        Vector(PseudonymEntry("Will", "[PERSON_3]"), PseudonymEntry("Mark", "[PERSON_4]"))
      )
      .toOption
      .get
    assertEquals(
      strict.source.canonicalText,
      "I will go when [PERSON_3] arrives. [PERSON_4] the date, [PERSON_4]."
    )
    val loose = Pseudonymizer
      .pseudonymize(src, Vector(PseudonymEntry("will", "[PERSON_3]", caseInsensitive = true)))
      .toOption
      .get
    assertEquals(
      loose.source.canonicalText,
      "I [PERSON_3] go when [PERSON_3] arrives. Mark the date, Mark."
    )
  }

  private def checkAllTokensMap(text: String, table: Vector[PseudonymEntry]): Unit =
    val src = StorySource.fromText(text).toOption.get
    val p = Pseudonymizer.pseudonymize(src, table).toOption.get
    val atlas = SurfaceAnalyzer.analyze(src)
    val newText = p.source.canonicalText
    // Expected token text: the same whole-word rewrite applied to the token alone (a token such
    // as "Anna's" contains a name at its boundary and must map to "[PERSON_1]'s").
    def rewrite(s: String): String =
      table.sortBy(e => (-e.surface.length, e.surface)).foldLeft(s) { (acc, e) =>
        Pseudonymizer.occurrences(acc, e.surface, e.caseInsensitive).reverse.foldLeft(acc) {
          (t, span) => t.substring(0, span.start) + e.pseudonym + t.substring(span.endExclusive)
        }
      }
    atlas.tokens.foreach { tok =>
      val original = atlas.text(tok)
      val mapped = p.mapSpan(tok.span).toOption.get
      val slice = mapped.slice(newText).toOption.get
      // A token strictly inside a multi-token replacement ("Bob" in "Bob Smith") maps to the
      // whole pseudonym; every other token maps to its own rewrite.
      p.segments.find(s => s.replaced.isDefined && s.original.contains(tok.span)) match
        case Some(seg) if seg.original != tok.span => assertEquals(slice, seg.replaced.get)
        case _ => assertEquals(slice, rewrite(original), s"token '$original' in '$text'")
    }

  test("every token span, including punctuation adjacent to a replaced name, maps exactly") {
    checkAllTokensMap(
      "Anna, go! \"Anna\" left (Anna's hat); Anna.",
      Vector(PseudonymEntry("Anna", "[PERSON_1]"))
    )
    checkAllTokensMap(
      "Bob Smith said: Bob Smith! Yes, Bob.",
      Vector(PseudonymEntry("Bob Smith", "[PERSON_2]"), PseudonymEntry("Bob", "[PERSON_3]"))
    )
  }

  private val nameGen: Gen[String] = Gen.oneOf("Anna", "Bob", "Claire", "Dmitri")
  private val wordGen: Gen[String] = Gen.oneOf("went", "home", "the", "cake", "then", "late")
  private val punctGen: Gen[String] = Gen.oneOf("", ",", ".", ";", "!", "?", "'s")
  private val sentenceGen: Gen[String] =
    Gen
      .nonEmptyListOf(
        Gen.zip(Gen.frequency(1 -> nameGen, 2 -> wordGen), punctGen).map { case (w, p) => w + p }
      )
      .map(_.mkString(" "))

  property("offset map is exact for every token on random name-bearing sentences") {
    forAll(sentenceGen) { text =>
      checkAllTokensMap(
        text,
        Vector(
          PseudonymEntry("Anna", "[P1]"),
          PseudonymEntry("Bob", "[P2]"),
          PseudonymEntry("Claire", "[SISTER]"),
          PseudonymEntry("Dmitri", "[UNCLE_OF_SPEAKER]")
        )
      )
      true
    }
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
      case DetailAtom.SpatialFact(SpatialClaim.AtLocation(_, PlaceName("restaurant"))) => true
      case _                                                                           => false
    })
    assert(ds.forall(_.expectedCountMass == Estimate.observed(1.0)))
    assertEquals(ds.map(_.id).distinct.size, ds.size)
  }

  test("a unit without propositional content still yields one countable atom") {
    val u = unit("Well.", DiscourseFunction.TaskCommentary)
    val ds = AtomProjection.fromUnit(u, TurnId.unsafe("t"))
    assertEquals(ds.size, 1)
    ds.head.atom match
      case DetailAtom.AttributeFact(_, Attribute(AttributeKey.Statement, _)) => ()
      case other => fail(s"unexpected $other")
  }

  test("emotion and perceptual cues become mental-state and perceptual atoms") {
    val u = unit("I felt embarrassed when the room went dark.")
    val atoms = AtomProjection.fromUnit(u, TurnId.unsafe("t")).map(_.atom)
    assert(
      atoms.contains(
        DetailAtom.MentalStateFact(
          AtomProjection.Speaker,
          MentalState(MentalStateKind.Emotion, MentalStateLabel.Embarrassment)
        )
      )
    )
    assert(atoms.exists {
      case DetailAtom.PerceptualFact(_, Modality.Visual, _) => true; case _ => false
    })
  }

  test("word splitting keeps non-ASCII letters and is locale independent") {
    assertEquals(Text.words("Café İstanbul, naïve—yes"), Vector("Café", "İstanbul", "naïve", "yes"))
    assertEquals(Text.lower("CAFÉ"), "café")
  }

  // ---- Routing is total (§21) ---------------------------------------------------------------

  test("every discourse function has an explicit destination and nothing defaults to an episode") {
    val episodic = Set[DiscourseFunction](
      DiscourseFunction.EpisodicAssertion,
      DiscourseFunction.Summary
    )
    DiscourseFunction.values.foreach { f =>
      val u = unit("Something happened somewhere.", f)
      val c = TargetInduction.classify(u)
      val canBeEpisode = c match
        case TargetInduction.UnitClass.Episodic | TargetInduction.UnitClass.Summary |
            TargetInduction.UnitClass.OtherEpisode =>
          true
        case _ => false
      assertEquals(canBeEpisode, episodic.contains(f), s"function $f classified as $c")
      assertEquals(MemoryAddress.discourseOf(f).isEmpty, episodic.contains(f))
    }
    val u = unit("Something happened somewhere.", DiscourseFunction.Uninterpretable)
    val ds = AtomProjection.fromUnit(u, TurnId.unsafe("t"))
    val src = StorySource.fromText("Something happened somewhere.").toOption.get
    val g = RecallSegmenter.segment(src)
    val graph = g.copy(units = g.units.map(_.copy(function = DiscourseFunction.Uninterpretable)))
    val r = TargetInduction.induce(graph, ds, Cue("cue", None, None))
    ds.foreach(d => assertEquals(r.addresses(d.id).mode, MemoryAddress.Unresolved))
  }

  // ---- Config ------------------------------------------------------------------------------

  test("InductionConfig validates masses and thresholds and never throws downstream") {
    assert(InductionConfig.of(targetMass = 1.5).isLeft)
    assert(InductionConfig.of(targetMass = 0.0).isLeft)
    assert(InductionConfig.of(otherMass = -0.1).isLeft)
    assert(InductionConfig.of(discourseMass = Double.NaN).isLeft)
    assert(InductionConfig.of(alternativeMargin = 1.1).isLeft)
    val extreme = InductionConfig.of(targetMass = 1.0, otherMass = 1.0, discourseMass = 1.0)
    assert(extreme.isRight)
    val u = unit("We ate cake at the restaurant.")
    val ds = AtomProjection.fromUnit(u, TurnId.unsafe("t"))
    val graph =
      RecallSegmenter.segment(StorySource.fromText("We ate cake at the restaurant.").toOption.get)
    val r = TargetInduction.induce(graph, ds, Cue("cake", None, None), extreme.toOption.get)
    ds.foreach { d =>
      val a = r.addresses(d.id)
      assert(math.abs(a.support.toVector.map(a.apply).sum - 1.0) < 1e-9)
      assert(a.support.forall(k => a(k) >= 0.0))
    }
  }

  // ---- Assessment independence (§59) ------------------------------------------------------

  private def metaFor(d: Detail, raw: Double): ClaimMeta =
    ClaimMeta.unsafe(
      ClaimId.unsafe(s"c:${d.id.value}"),
      EpistemicStatus.Hypothesized,
      Credence.unsafeRaw(raw),
      NonEmptyVector.one(
        Evidence(
          EvidenceId.unsafe(s"e:${d.id.value}"),
          Some(d.support),
          Set.empty,
          Fingerprint.unsafe("test"),
          StageId.unsafe("s")
        )
      ),
      Provenance.deterministic("test", Checksum.ofText("cfg"))
    )

  test("target membership, specificity and re-experiencing are independent fields") {
    val u = unit("We ate cake at the restaurant.")
    val d = AtomProjection.fromUnit(u, TurnId.unsafe("t")).head
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
      metaFor(d, 0.9)
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

  // ---- Model validation (§22, §24) ---------------------------------------------------------

  private def smallDraft(
      addressOf: (Detail, EpisodeModel) => Distribution[MemoryAddress],
      duplicateAssessment: Boolean = false
  ) =
    val source = InterviewSource
      .validated(twoTurnSource(SpeakerRole.Interviewer, Some(InterviewPhase.GeneralProbe)))
      .toOption
      .get
    val seg = InterviewSegmenter.segment(source)
    val details = seg.graph.ordered.flatMap(u => AtomProjection.fromUnit(u, seg.turnOf(u.id)))
    val other = EpisodeModel.hypothesized(
      EpisodeId.unsafe("other"),
      EpisodeScope.OtherSpecific,
      Vector.empty,
      Set.empty,
      Set.empty,
      Vector.empty,
      Vector.empty,
      None
    )
    val as = details.map { d =>
      DetailAssessment(
        d,
        addressOf(d, other),
        DetailAssessment.defaultFacets(d.atom),
        Estimate.observed(0.5),
        ExperientialEvidence.none,
        EpistemicStatus.Hypothesized,
        PromptContext(InterviewPhase.GeneralProbe, None),
        None,
        metaFor(d, 0.5)
      )
    }
    val assessments = if duplicateAssessment then as ++ as.take(1) else as
    InterviewModel.draft(
      source,
      seg.graph,
      details,
      assessments,
      None,
      Vector.empty,
      Vector(other),
      ClaimLedger.empty
    )

  test("an address scope must agree with the episode's declared scope") {
    val ok = smallDraft((_, o) =>
      Distribution.point(MemoryAddress.Episode(o.id, EpisodeScope.OtherSpecific))
    )
    assert(InterviewModel.validate(ok).isValid)
    val mismatch = smallDraft((_, o) =>
      Distribution.point(MemoryAddress.Episode(o.id, EpisodeScope.TargetSpecific))
    )
    val errs = InterviewModel.validate(mismatch).swap.toOption.get.toNonEmptyList.toList
    assert(errs.exists {
      case InterviewModel.Violation.ScopeMismatch(_, _, _, _) => true
      case _                                                  => false
    })
  }

  test("every detail is assessed exactly once") {
    val dup = smallDraft(
      (_, o) => Distribution.point(MemoryAddress.Episode(o.id, EpisodeScope.OtherSpecific)),
      duplicateAssessment = true
    )
    val errs = InterviewModel.validate(dup).swap.toOption.get.toNonEmptyList.toList
    assert(errs.exists {
      case InterviewModel.Violation.DuplicateAssessment(_) => true
      case _                                               => false
    })
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

  private lazy val cakeDetail: Detail =
    AtomProjection.fromUnit(unit("We ate cake."), TurnId.unsafe("t")).head

  private def assessment(
      addr: Distribution[MemoryAddress],
      facets: Distribution[DetailFacet],
      d: Detail = cakeDetail
  ): DetailAssessment =
    DetailAssessment(
      d,
      addr,
      facets,
      Estimate.observed(0.5),
      ExperientialEvidence.none,
      EpistemicStatus.Hypothesized,
      PromptContext(InterviewPhase.FreeRecall, None),
      None,
      metaFor(d, 0.5)
    )

  property("category distribution preserves total mass and internal mass equals target mass") {
    forAll(addressGen, facetGen) { (addr, facets) =>
      val a = assessment(addr, facets)
      val cats = TraditionalScoring.categoryDistribution(a, AiScoringPolicy.Standard).get
      val internal = cats.mass(_.isInternal)
      val expectedInternal = a.targetMass * facets.mass(_ != DetailFacet.Other)
      math.abs(internal - expectedInternal) < 1e-9
    }
  }

  test("RepetitionRule.Ignore removes repetition mass instead of counting it as Other") {
    val rep = MemoryAddress.Discourse(InterviewDiscourseFunction.Repetition(DetailId.unsafe("x")))
    val pure = assessment(Distribution.point(rep), Distribution.point(DetailFacet.Event))
    val ignore = AiScoringPolicy.Standard.copy(repetitionRule = RepetitionRule.Ignore)
    assertEquals(TraditionalScoring.categoryDistribution(pure, ignore), None)
    val counted = TraditionalScoring.categoryDistribution(pure, AiScoringPolicy.Standard).get
    assertEquals(counted(AiCategory.Repetition), 1.0)
    val mixed = assessment(
      Distribution.unsafe(rep -> 0.5, MemoryAddress.GeneralKnowledge -> 0.5),
      Distribution.point(DetailFacet.Event)
    )
    val d = TraditionalScoring.categoryDistribution(mixed, ignore).get
    assertEquals(d(AiCategory.ExternalSemantic), 1.0)
    assertEquals(d(AiCategory.Other), 0.0)
  }

  test("Interval contains its point, ExpectedCount adds componentwise, and raw counts say so") {
    val a = ExpectedCount(1.0, Interval(0.5, 2.0), None)
    val b = ExpectedCount(0.25, Interval(0.0, 1.0), None)
    val s = ExpectedCount.+(a, b)
    assertEquals(s, ExpectedCount(1.25, Interval(0.5, 3.0), None))
    assert(s.interval.contains(s.point))
    assertEquals(s.label, "RawExpectation")
    assert(!s.isCalibrated)
  }

  test("phenomenology strands are separate and invariant under duplicating details") {
    val exp = ExperientialEvidence(None, true, Vector(SourceMonitoring.DirectMemory))
    val a1 = assessment(
      Distribution.point(MemoryAddress.Unresolved),
      Distribution.point(DetailFacet.Event)
    )
    val a2 = a1.copy(experiential = exp, sourceMonitoring = Some(SourceMonitoring.DirectMemory))
    val once = ProfileScoring.phenomenology(Vector(a1, a2), None)
    val twice = ProfileScoring.phenomenology(Vector(a1, a2, a1, a2), None)
    assertEquals(once.firstPersonRate, Estimate.observed(0.5))
    assertEquals(twice.firstPersonRate, once.firstPersonRate)
    assertEquals(once.explicitRating, None)
    assertEquals(once.sourceMonitoring, Map(SourceMonitoring.DirectMemory -> 1))
    assertEquals(ProfileScoring.phenomenology(Vector.empty, None).firstPersonRate.isObserved, false)
  }

  test("a Missing count mass excludes the detail and is reported as coverage") {
    val missing =
      cakeDetail.copy(expectedCountMass = Estimate.missing(MissingReason.ProviderAbstained))
    val a = assessment(
      Distribution.point(MemoryAddress.GeneralKnowledge),
      Distribution.point(DetailFacet.Event),
      missing
    )
    assertEquals(missing.observedMass, None)
    assertEquals(TraditionalScoring.massCoverage(Vector(a)).observed, 0)
    assertEquals(TraditionalScoring.massCoverage(Vector(a)).eligible, 1)
  }

  given Arbitrary[Double] = Arbitrary(Gen.choose(0.0, 1.0))
