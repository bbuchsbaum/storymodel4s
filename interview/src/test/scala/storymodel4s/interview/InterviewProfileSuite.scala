package storymodel4s.interview

import cats.data.NonEmptyVector
import munit.FunSuite

import storymodel4s.core.*
import storymodel4s.features.{Estimate, MissingReason}
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

  test("anchoring is invariant under copying the same place atom") {
    val once = scored(Vector(place("u", "d0", "p")), 1)
    val copies = scored((0 until 4).toVector.map(i => place("u", s"d$i", "p")), 1)
    assertEquals(once.spatiotemporalAnchoring, 1.0)
    assertEquals(copies.spatiotemporalAnchoring, once.spatiotemporalAnchoring)
  }

  test("mental atoms add no fragmentation node") {
    val twoEvents = Vector(event("u", "e0", "s"), event("u", "e1", "s"))
    val withMental = twoEvents ++ (0 until 3).toVector.map(i => mental("u", s"m$i"))
    val a = scored(twoEvents, 1)
    val b = scored(withMental, 1)
    assertEquals(a.fragmentation, Estimate.observed(0.0))
    assertEquals(b.fragmentation, a.fragmentation)
  }

  test("density is invariant under re-atomizing the same unit") {
    val sparse = Vector(event("u", "e0", "s"))
    val dense = (0 until 5).toVector.map(i => event("u", s"e$i", "s"))
    val a = scored(sparse, 1)
    val b = scored(dense, 1)
    assertEquals(a.episodicDensityPerWord, Estimate.observed(0.1))
    assertEquals(b.episodicDensityPerWord, a.episodicDensityPerWord)
    assertEquals(b.episodicDensityPerSecond, a.episodicDensityPerSecond)
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
    assertEquals(a.eventPurity, Estimate.observed(0.5))
    assertEquals(b.eventPurity, a.eventPurity)
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
    assertEquals(ProfileScoring.probeGain(oneProbe), Estimate.observed(0.5))
    assertEquals(ProfileScoring.probeGain(fourProbe), ProfileScoring.probeGain(oneProbe))
  }

  test("perceptual profile is a rate over unique situations") {
    val once = scored(Vector(visual("u", "v0", "s")), 1)
    val copies = scored((0 until 4).toVector.map(i => visual("u", s"v$i", "s")), 1)
    assertEquals(once.perceptualProfile, Map(Modality.Visual -> 1.0))
    assertEquals(copies.perceptualProfile, once.perceptualProfile)
  }

  test("mental profile counts unique units, not atoms") {
    val once = scored(Vector(mental("u", "m0")), 1)
    val copies = scored((0 until 4).toVector.map(i => mental("u", s"m$i")), 1)
    assertEquals(once.mentalStateProfile, Map(MentalStateKind.Emotion -> 1.0))
    assertEquals(copies.mentalStateProfile, once.mentalStateProfile)
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
    assertEquals(ev.fragmentation, Estimate.missing(MissingReason.Excluded))
    assertEquals(ev.episodicDensityPerWord, Estimate.missing(MissingReason.Excluded))
    assertEquals(ev.eventPurity, Estimate.missing(MissingReason.Excluded))
    assertEquals(ev.probeGain, Estimate.missing(MissingReason.Excluded))
  }
