package storymodel4s.acquire

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*

/** Differential court against the exact pre-migration Resolver, not an old-binary replay.
  * Fixtures contain text support only. The historical object is separately hash-bound.
  */
class D1aTextResolverParitySuite extends FunSuite:
  import D1aAcquireFixtures.*
  private val family = ClaimFamily.EntityMention

  private def same(b: EvidenceBundle[Int], fp: FamilyPolicy,
      clue: String, f: ClaimFamily = family): Unit =
    val policy = AcceptancePolicy(Map(f -> fp), FamilyPolicy.Development)
    assertEquals(Resolver.resolve(f, b, policy), BaselineTextResolver.resolve(f, b, policy), clue)

  test("all 8640 declared text-policy cases preserve complete resolution states"):
    var checked = 0
    for
      fp <- Vector(FamilyPolicy.Ordinary, FamilyPolicy.Conservative, FamilyPolicy.Development)
      basisIndex <- 0 until 8
      pattern <- 0 until 5
      supportForm <- 0 until 4
      score <- Vector(0.0, 0.5, 1.0)
      structural <- Vector(StructuralValidity.Valid, StructuralValidity.invalid("named-violation"))
      critic <- 0 until 3
    do
      val e1 = inlineEvidence("first", text = supportForm >= 2)
      val e2 = inlineEvidence("second", text = supportForm >= 2)
      val proposals = pattern match
        case 0 => Vector(proposal(1, e1))
        case 1 => Vector(proposal(1, e1), proposal(1, e2))
        case 2 => Vector(proposal(1, e1), proposal(1, e2, "independent"))
        case 3 => Vector(proposal(1, e1), proposal(1, e2, alternative = true))
        case _ => Vector(proposal(1, e1, alternative = true))
      val bases = basisIndex match
        case 0 => Vector.empty
        case 1 => Vector(CandidateBasis(2, determined))
        case 2 => Vector(CandidateBasis(1, determined))
        case n =>
          val probabilities = Vector(fp.reviewBand.value - 0.01, fp.reviewBand.value,
            (fp.reviewBand.value + fp.acceptThreshold.value) / 2.0,
            fp.acceptThreshold.value, fp.acceptThreshold.value + 0.01)
          Vector(CandidateBasis(1, calibrated(probabilities(n - 3))))
      val findings = critic match
        case 0 => Vector.empty
        case 1 => Vector(Fixtures.finding(FindingCode.UnknownFrame))
        case _ => Vector(Fixtures.finding(FindingCode.HallucinatedConcept,
          score = Some(fp.criticBlockThreshold)))
      val b = EvidenceBundle(proposals, findings, structural,
        SourceSupport.text(score, Option.when(supportForm == 1 || supportForm == 3)(Fixtures.someSpans)),
        1.0, bases)
      same(b, fp, s"policy=$fp basis=$basisIndex pattern=$pattern support=$supportForm score=$score structural=$structural critic=$critic")
      checked += 1
    assertEquals(checked, 8640)

  test("text controls retain no-proposal precedence and unresolved ledger references"):
    val noProposals = Vector(Vector.empty[AgentProposal[Int]],
      Vector(AgentProposal.abstained[Int](Fixtures.task, Fixtures.receipt)),
      Vector(AgentProposal.unsupported[Int](Fixtures.task, Vector.empty, Fixtures.receipt)))
    for proposals <- noProposals; fp <- Vector(FamilyPolicy.Ordinary, FamilyPolicy.Conservative) do
      val b = bundle(proposals)
      same(b, fp, "no substantive proposal")
      same(b.copy(structural = StructuralValidity.invalid("invalid")), fp, "structural precedence")
      same(b.copy(findings = Vector(Fixtures.finding(FindingCode.HallucinatedConcept))), fp, "critic precedence")
    val ref = EvidenceRef.ById(EvidenceId.unsafe("external-ledger"))
    val b = bundle(Vector(proposal(1, ref)))
    same(b, FamilyPolicy.Ordinary, "ById without bundle spans")
    same(b.copy(sourceSupport = SourceSupport.text(1.0, Some(Fixtures.someSpans))),
      FamilyPolicy.Ordinary, "ById with bundle spans")

  test("text losing support and losing calibration cannot license the leader"):
    val b = bundle(Vector(proposal(1, inlineEvidence("one"), "one"),
      proposal(1, inlineEvidence("two"), "two"), proposal(2, inlineEvidence("losing", text = true), "three")))
    same(b, FamilyPolicy.Ordinary, "only losing support")
    same(b.copy(bases = Vector(CandidateBasis(2, calibrated(0.99)))),
      FamilyPolicy.Ordinary, "only losing calibration")

  test("text rank ties and duplicate evidence retain complete ordering"):
    val a = inlineEvidence("a", text = true)
    val z = inlineEvidence("z", text = true)
    val patterns = Vector(
      Vector(proposal(2, z), proposal(1, a)),
      Vector(proposal(1, a, score = None), proposal(2, z, score = Some(-0.1))),
      Vector(proposal(1, a, score = Some(0.1)), proposal(2, z, score = Some(0.9))),
      Vector(proposal(1, a), proposal(1, z, alternative = true), proposal(2, a)),
      Vector(proposal(1, z), proposal(1, a, "two"), proposal(1, z, "three"))
    )
    for proposals <- patterns; bases <- Vector(Vector.empty, Vector(CandidateBasis(1, determined), CandidateBasis(2, determined))) do
      same(bundle(proposals).copy(bases = bases), FamilyPolicy.Ordinary, s"ties $proposals $bases")
    val repeated = AgentProposal.proposed(Fixtures.task, 1, NonEmptyVector.of(z, a, z),
      None, Vector.empty, Fixtures.receipt)
    val actual = Resolver.resolve(family, bundle(Vector(repeated)), AcceptancePolicy.Conservative)
    actual match
      case ResolutionState.Accepted(_, _, evidence) => assertEquals(evidence.toVector, Vector(z, a))
      case other => fail(s"unexpected $other")

  test("text threshold neighbors and critic score boundaries preserve outcomes"):
    for fp <- Vector(FamilyPolicy.Ordinary, FamilyPolicy.Conservative, FamilyPolicy.Development) do
      val b = bundle(Vector(proposal(1, inlineEvidence("a", text = true)),
        proposal(1, inlineEvidence("b", text = true), "two")))
      for threshold <- Vector(fp.reviewBand.value, fp.acceptThreshold.value)
          p <- Vector(java.lang.Math.nextDown(threshold), threshold, java.lang.Math.nextUp(threshold)) do
        same(b.copy(bases = Vector(CandidateBasis(1, calibrated(p)))), fp, s"probability=$p")
      for score <- Vector(None, Some(java.lang.Math.nextDown(fp.criticBlockThreshold)),
          Some(fp.criticBlockThreshold), Some(java.lang.Math.nextUp(fp.criticBlockThreshold))) do
        same(b.copy(findings = Vector(Fixtures.finding(FindingCode.HallucinatedConcept, score = score))),
          fp, s"critic=$score")

  test("text upstream-only evidence and every declared family preserve default and override policy"):
    val upstream = EvidenceRef.Inline(Fixtures.evidence("upstream")
      .copy(upstream = Set(ClaimId.unsafe("upstream-claim"))))
    val b = bundle(Vector(proposal(1, upstream), proposal(1, upstream, "two")))
    val families = Vector(ClaimFamily.ReportedToRootPromotion, ClaimFamily.EventCoreference,
      ClaimFamily.RoleReversal, ClaimFamily.Polarity, ClaimFamily.StrictPrecedence,
      ClaimFamily.CausalEdge, ClaimFamily.TargetEpisodeMembership, ClaimFamily.EntityMention,
      ClaimFamily.EntityCoreference, ClaimFamily.SituationMention, ClaimFamily.ParticipantRole,
      ClaimFamily.ParticipantCoverage, ClaimFamily.SituationCircumstance, ClaimFamily.Modality,
      ClaimFamily.ContextAssignment, ClaimFamily.TemporalRelation, ClaimFamily.GoalRelation,
      ClaimFamily.StateChange, ClaimFamily.Reference, ClaimFamily.Boundary,
      ClaimFamily.SegmentMembership, ClaimFamily.DiscourseTrajectory, ClaimFamily.Summary,
      ClaimFamily.DetailAtom, ClaimFamily.Custom("d1a", "custom"))
    for f <- families do
      assertEquals(Resolver.resolve(f, b, AcceptancePolicy.Conservative),
        BaselineTextResolver.resolve(f, b, AcceptancePolicy.Conservative), f.toString)
      for fp <- Vector(FamilyPolicy.Ordinary, FamilyPolicy.Conservative, FamilyPolicy.Development) do
        same(b, fp, s"override=$f/$fp", f)
