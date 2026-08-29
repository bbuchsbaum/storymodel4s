package storymodel4s.interview

import cats.data.NonEmptyVector
import munit.FunSuite

import storymodel4s.core.*
import storymodel4s.features.{Coverage, Estimate, MissingReason}
import storymodel4s.interview.scoring.*
import storymodel4s.recall.*
import storymodel4s.story.NarrativeNodeId

/** Unit- and situation-level profile laws: atomization density must not change any strand. */
class InterviewProfileSuite extends FunSuite:

  private val targetEp = EpisodeId.unsafe("ep:target")
  private val otherEp = EpisodeId.unsafe("ep:other")
  private val targetAddr = MemoryAddress.Episode(targetEp, EpisodeScope.TargetSpecific)
  private val otherAddr = MemoryAddress.Episode(otherEp, EpisodeScope.OtherSpecific)

  private def unit(text: String): RecallUnit =
    val src = StorySource.fromText(text).toOption.get
    RecallSegmenter.segment(src).ordered.head

  private lazy val proto: Detail =
    AtomProjection.fromUnit(unit("We ate cake."), TurnId.unsafe("t")).head

  private def metaFor(d: Detail): ClaimMeta =
    ClaimMeta.unsafe(
      ClaimId.unsafe(s"c:${d.id.value}"),
      EpistemicStatus.Hypothesized,
      Credence.unsafeRaw(0.5),
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

  private def sit(id: String): SituationId = SituationId.unsafe(s"sit:$id")

  private def assessment(
      unitId: String,
      detailId: String,
      atom: DetailAtom,
      address: MemoryAddress,
      phase: InterviewPhase = InterviewPhase.FreeRecall
  ): DetailAssessment =
    val d = proto.copy(
      id = DetailId.unsafe(detailId),
      atom = atom,
      sourceUnit = RecallUnitId.unsafe(unitId)
    )
    DetailAssessment(
      d,
      Distribution.point(address),
      DetailAssessment.defaultFacets(atom),
      Estimate.observed(0.5),
      ExperientialEvidence.none,
      EpistemicStatus.Hypothesized,
      PromptContext(phase, None),
      None,
      metaFor(d)
    )

  private def event(unitId: String, detailId: String, sitId: String): DetailAssessment =
    assessment(unitId, detailId, DetailAtom.EventOccurrence(sit(sitId)), targetAddr)

  private def place(unitId: String, detailId: String, sitId: String): DetailAssessment =
    assessment(
      unitId,
      detailId,
      DetailAtom.SpatialFact(SpatialClaim.AtLocation(sit(sitId), PlaceName("restaurant"))),
      targetAddr
    )

  private def visual(unitId: String, detailId: String, sitId: String): DetailAssessment =
    assessment(
      unitId,
      detailId,
      DetailAtom.PerceptualFact(
        AtomProjection.Speaker,
        Modality.Visual,
        NarrativeNodeId.Situation(sit(sitId))
      ),
      targetAddr
    )

  private def mental(unitId: String, detailId: String): DetailAssessment =
    assessment(
      unitId,
      detailId,
      DetailAtom.MentalStateFact(
        AtomProjection.Speaker,
        MentalState(MentalStateKind.Emotion, MentalStateLabel.Happiness)
      ),
      targetAddr
    )

  private def scored(
      as: Vector[DetailAssessment],
      participantUnits: Int,
      words: Double = 10.0
  ): Profile =
    ProfileScoring.profile(as, participantUnits, words, 10.0, None)

  test("an Ambiguous placement is publishable and a member of nothing") {
    // The end-to-end consequence of the induction fixture in InterviewInductionSuite. Every other
    // assessment in this suite uses Distribution.point, so the split case has never been scored
    // here - which is why this went unnoticed.
    //
    // Induction emits exactly this for PlacementBasis.Ambiguous: 0.4 Unresolved, 0.3 target,
    // 0.3 other-specific. Not invented for the test; measured off TargetInduction.
    val split = Distribution
      .of(
        Vector(
          MemoryAddress.Unresolved -> 0.4,
          targetAddr -> 0.3,
          otherAddr -> 0.3
        )
      )
      .getOrElse(fail("could not build the split distribution"))

    val d = proto.copy(
      id = DetailId.unsafe("d-amb"),
      atom = DetailAtom.EventOccurrence(sit("s")),
      sourceUnit = RecallUnitId.unsafe("u-amb")
    )
    val ambiguous = DetailAssessment(
      d,
      split,
      DetailAssessment.defaultFacets(d.atom),
      Estimate.observed(0.5),
      ExperientialEvidence.none,
      EpistemicStatus.Hypothesized,
      PromptContext(InterviewPhase.FreeRecall, None),
      None,
      metaFor(d)
    )

    val p = scored(Vector(ambiguous), 1)

    // MEASURED, not reasoned from the masses. The unit reaches NEITHER class: 0.3 is below the
    // 0.5 membership threshold on both, so it is neither a target unit nor an other-specific one.
    // eventPurity is target / (target + other-specific) and both arms are empty, so it abstains.
    assert(
      p.eventPurity.estimate.isInstanceOf[Estimate.Missing[?]],
      s"eventPurity should abstain when the unit is in neither class: ${p.eventPurity}"
    )

    // POSITIVE CONTROL, without which the assertion above proves nothing: the SAME detail with a
    // concentrated placement must NOT abstain. Otherwise "eventPurity is Missing" could be Missing
    // for any unrelated reason and this test would pass while measuring nothing.
    val concentrated = ambiguous.copy(address = Distribution.point(targetAddr))
    val control = scored(Vector(concentrated), 1)
    assertEquals(
      control.eventPurity.estimate,
      Estimate.observed(1.0),
      s"control: a concentrated target placement must score, not abstain: ${control.eventPurity}"
    )

    // Meanwhile 0.6 of this detail's mass IS placed. It is not an abstention by the model - the
    // model committed 60% of it to real episodes and simply spread it across two. A resolution
    // summary built from these masses clears its own 0.5 threshold and says "publish", while every
    // membership metric has dropped the unit. Two thresholds, both 0.5, different quantities.
    assertEqualsDouble(1.0 - split(MemoryAddress.Unresolved), 0.6, 1e-9)
    assert(
      PlacementResolution
        .of(PlacementGrain.Detail, 0.6, 0.4, 0.0)
        .fold(e => fail(e.message), identity)
        .clearsThreshold,
      "resolution says publish while the profile counts the unit nowhere"
    )
  }

  test("anchoring is invariant under copying the same place atom") {
    val once = scored(Vector(place("u", "d0", "p")), 1)
    val copies = scored((0 until 4).toVector.map(i => place("u", s"d$i", "p")), 1)
    assertEquals(once.spatiotemporalAnchoring.estimate, Estimate.observed(1.0))
    assertEquals(copies.spatiotemporalAnchoring.estimate, once.spatiotemporalAnchoring.estimate)
  }

  test("mental atoms add no fragmentation node") {
    val twoEvents = Vector(event("u", "e0", "s"), event("u", "e1", "s"))
    val withMental = twoEvents ++ (0 until 3).toVector.map(i => mental("u", s"m$i"))
    val a = scored(twoEvents, 1)
    val b = scored(withMental, 1)
    assertEquals(a.fragmentation.estimate, Estimate.observed(0.0))
    assertEquals(b.fragmentation.estimate, a.fragmentation.estimate)
  }

  test("density is invariant under re-atomizing the same unit") {
    val sparse = Vector(event("u", "e0", "s"))
    val dense = (0 until 5).toVector.map(i => event("u", s"e$i", "s"))
    val a = scored(sparse, 1)
    val b = scored(dense, 1)
    assertEquals(a.episodicDensityPerWord.estimate, Estimate.observed(0.1))
    assertEquals(b.episodicDensityPerWord.estimate, a.episodicDensityPerWord.estimate)
    assertEquals(b.episodicDensityPerSecond.estimate, a.episodicDensityPerSecond.estimate)
  }

  test("purity is invariant under within-episode re-atomization") {
    val oneEach = Vector(
      event("t", "t0", "s"),
      assessment("o", "o0", DetailAtom.EventOccurrence(sit("o")), otherAddr)
    )
    val fourTarget = (0 until 4).toVector.map(i => event("t", s"t$i", "s")) :+
      assessment("o", "o0", DetailAtom.EventOccurrence(sit("o")), otherAddr)
    val a = scored(oneEach, 2)
    val b = scored(fourTarget, 2)
    assertEquals(a.eventPurity.estimate, Estimate.observed(0.5))
    assertEquals(b.eventPurity.estimate, a.eventPurity.estimate)
  }

  test("probeGain is invariant under re-atomizing a phase") {
    val oneProbe = Vector(
      event("free", "f0", "s"),
      assessment(
        "probe",
        "p0",
        DetailAtom.EventOccurrence(sit("s")),
        targetAddr,
        InterviewPhase.SpecificProbe
      )
    )
    val fourProbe = Vector(event("free", "f0", "s")) ++ (0 until 4).toVector.map(i =>
      assessment(
        "probe",
        s"p$i",
        DetailAtom.EventOccurrence(sit("s")),
        targetAddr,
        InterviewPhase.SpecificProbe
      )
    )
    assertEquals(ProfileScoring.probeGain(oneProbe, 2).estimate, Estimate.observed(0.5))
    assertEquals(ProfileScoring.probeGain(fourProbe, 2).estimate, Estimate.observed(0.5))
  }

  test("perceptual profile is a rate over unique situations") {
    val once = scored(Vector(visual("u", "v0", "s")), 1)
    val copies = scored((0 until 4).toVector.map(i => visual("u", s"v$i", "s")), 1)
    assertEquals(once.perceptualProfile(Modality.Visual).estimate, Estimate.observed(1.0))
    assertEquals(
      copies.perceptualProfile(Modality.Visual).estimate,
      once.perceptualProfile(Modality.Visual).estimate
    )
  }

  test("mental profile counts unique units, not atoms") {
    val once = scored(Vector(mental("u", "m0")), 1)
    val copies = scored((0 until 4).toVector.map(i => mental("u", s"m$i")), 1)
    assertEquals(once.mentalStateProfile(MentalStateKind.Emotion).estimate, Estimate.observed(1.0))
    assertEquals(
      copies.mentalStateProfile(MentalStateKind.Emotion).estimate,
      once.mentalStateProfile(MentalStateKind.Emotion).estimate
    )
  }

  test("strands are invariant under assessment order") {
    val as = Vector(
      event("t", "t0", "s"),
      place("t", "p0", "s"),
      visual("t", "v0", "s"),
      mental("t", "m0"),
      assessment("o", "o0", DetailAtom.EventOccurrence(sit("o")), otherAddr)
    )
    val a = scored(as, 2)
    val b = scored(as.reverse, 2)
    assertEquals(b.episodicDensityPerWord, a.episodicDensityPerWord)
    assertEquals(b.eventPurity, a.eventPurity)
    assertEquals(b.spatiotemporalAnchoring, a.spatiotemporalAnchoring)
    assertEquals(b.perceptualProfile, a.perceptualProfile)
    assertEquals(b.mentalStateProfile, a.mentalStateProfile)
    assertEquals(b.fragmentation, a.fragmentation)
    assertEquals(b.probeGain, a.probeGain)
  }

  test("empty target situations yield missing fragmentation, not zero") {
    val ev = scored(Vector.empty, 0)
    assertEquals(ev.fragmentation.estimate, Estimate.missing(MissingReason.Excluded))
    assertEquals(ev.episodicDensityPerWord.estimate, Estimate.missing(MissingReason.Excluded))
    assertEquals(ev.eventPurity.estimate, Estimate.missing(MissingReason.Excluded))
    assertEquals(ev.probeGain.estimate, Estimate.missing(MissingReason.Excluded))
    assertEquals(ev.spatiotemporalAnchoring.estimate, Estimate.missing(MissingReason.Excluded))
  }

  test("residual mass below 0.5 does not classify a unit") {
    val leak = Distribution.unsafe(
      targetAddr -> 0.85,
      otherAddr -> 0.045,
      MemoryAddress.Unresolved -> 0.105
    )
    val mixed = Distribution.unsafe(targetAddr -> 0.9, otherAddr -> 0.1)
    def withAddr(id: String, dist: Distribution[MemoryAddress]): DetailAssessment =
      event("u", id, "s").copy(address = dist)
    val leaked = scored(Vector(withAddr("d0", leak)), 1)
    val mixedP = scored(Vector(withAddr("d1", mixed)), 1)
    assertEquals(leaked.eventPurity.estimate, Estimate.observed(1.0))
    assertEquals(leaked.otherEventDrift.estimate, Estimate.observed(0.0))
    assertEquals(mixedP.eventPurity.estimate, Estimate.observed(1.0))
    assertEquals(mixedP.otherEventDrift.estimate, Estimate.observed(0.0))
  }

  test("TemporalFact.Relation is not an anchor") {
    val rel = assessment(
      "u",
      "r0",
      DetailAtom.TemporalFact(
        TemporalClaim.Relation(sit("a"), RecallTemporalRelation.Before, sit("b"))
      ),
      targetAddr
    )
    val ev = scored(Vector(rel), 1)
    assertEquals(ev.spatiotemporalAnchoring.estimate, Estimate.missing(MissingReason.Excluded))
  }

  test("induced alternative leak does not reclassify the target unit") {
    val text = "We ate cake at the restaurant yesterday. The year before we drove to Montreal."
    val graph = RecallSegmenter.segment(StorySource.fromText(text).toOption.get)
    assert(
      graph.ordered.size >= 2,
      graph.ordered.map(u => u.text -> TargetInduction.classify(u)).toString
    )
    val details = graph.ordered.flatMap(u => AtomProjection.fromUnit(u, TurnId.unsafe("t")))
    val cfg = InductionConfig.of(alternativeMargin = 1.0).toOption.get
    val induced = TargetInduction.induce(graph, details, Cue("what happened", None, None), cfg)
    assert(
      induced.alternatives.nonEmpty,
      s"alts=${induced.alternatives} others=${induced.otherEpisodes} units=${graph.ordered
          .map(u => u.text -> TargetInduction.classify(u))}"
    )
    val as = details.flatMap { d =>
      induced.addresses.get(d.id).map { addr =>
        assessment(d.sourceUnit.value, d.id.value, d.atom, targetAddr).copy(
          detail = d,
          address = addr
        )
      }
    }
    def otherMass(a: DetailAssessment): Double =
      a.massAt {
        case MemoryAddress.Episode(_, EpisodeScope.OtherSpecific) => true
        case _                                                    => false
      }
    val targetAs = as.filter(_.targetMass >= 0.5)
    assert(targetAs.exists(a => otherMass(a) > 0.0 && otherMass(a) < 0.5))
    val byUnit = as.groupBy(_.detail.sourceUnit).values
    val targetN = byUnit.count(_.exists(_.targetMass >= 0.5))
    val otherN = byUnit.count(_.exists(a => otherMass(a) >= 0.5))
    assert(targetN >= 1)
    assertEquals(otherN, 1)
    val ev = scored(as, byUnit.size)
    assertEquals(
      ev.eventPurity.estimate,
      Estimate.observed(targetN.toDouble / (targetN + otherN).toDouble)
    )
    assertEquals(
      ev.otherEventDrift.estimate,
      Estimate.observed(otherN.toDouble / byUnit.size.toDouble)
    )
  }

  test("unit-rate coverage uses participantUnits, not assessed count") {
    val as = Vector(
      event("t", "t0", "s"),
      assessment("o", "o0", DetailAtom.EventOccurrence(sit("o")), otherAddr)
    )
    val tight = scored(as, 2)
    val wide = scored(as, 5)
    assertEquals(tight.episodicDensityPerWord.estimate, wide.episodicDensityPerWord.estimate)
    assertEquals(tight.episodicDensityPerWord.coverage, Coverage.unsafe(2, 2))
    assertEquals(wide.episodicDensityPerWord.coverage, Coverage.unsafe(5, 2))
    assertEquals(wide.eventPurity.coverage, Coverage.unsafe(5, 2))
  }
