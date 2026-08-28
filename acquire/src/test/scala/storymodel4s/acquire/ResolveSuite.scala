package storymodel4s.acquire

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import storymodel4s.core.*

class ResolveSuite extends ScalaCheckSuite:
  import Fixtures.*
  import Fixtures.given
  import ResolutionState.*

  private val policy = AcceptancePolicy.Conservative
  private val ordinary = ClaimFamily.EntityMention
  private val highImpact = ClaimFamily.CausalEdge

  test("structural invalidity rejects regardless of everything else"):
    val b = bundle(Vector(proposed(1, 0.9)), structural = StructuralValidity.invalid("bad"))
    assert(Resolver.resolve(ordinary, b, policy).isInstanceOf[Rejected[?]])

  test("blocking findings reject for conservative families and unresolve otherwise"):
    val b = bundle(
      Vector(proposed(1, 0.9)),
      findings = Vector(finding(FindingCode.RoleReversalSuspected))
    )
    Resolver.resolve(highImpact, b, policy) match
      case Rejected(RejectionReason.BlockingFinding(codes)) =>
        assertEquals(codes, Vector(FindingCode.RoleReversalSuspected))
      case other => fail(s"unexpected $other")
    Resolver.resolve(ordinary, b, policy) match
      case Unresolved(ResolutionFailure.BlockingFinding(_)) => ()
      case other                                            => fail(s"unexpected $other")

  test("a low-confidence blocking finding does not block; an unscored one does"):
    val low = bundle(
      Vector(proposed(1, 0.9)),
      findings = Vector(finding(FindingCode.HallucinatedConcept, score = Some(0.02)))
    )
    assert(Resolver.resolve(ordinary, low, policy).isAccepted)
    val high = bundle(
      Vector(proposed(1, 0.9)),
      findings = Vector(finding(FindingCode.HallucinatedConcept, score = Some(0.9)))
    )
    assert(!Resolver.resolve(ordinary, high, policy).isAccepted)
    val unscored = bundle(
      Vector(proposed(1, 0.9)),
      findings = Vector(finding(FindingCode.HallucinatedConcept))
    )
    assert(!Resolver.resolve(ordinary, unscored, policy).isAccepted)

  test("warnings alone do not block"):
    val b = bundle(Vector(proposed(1, 0.9)), findings = Vector(finding(FindingCode.UnknownFrame)))
    assert(Resolver.resolve(ordinary, b, policy).isAccepted)

  test("no substantive proposal is unresolved"):
    val b = bundle[Int](Vector(AgentProposal.abstained(task, receipt)))
    assertEquals(Resolver.resolve(ordinary, b, policy), Unresolved(ResolutionFailure.NoProposal))

  test("acceptance requires a calibrated probability for the leading value"):
    val single = bundle(Vector(proposed(1, 0.99)), calibrated = None)
    assertEquals(
      Resolver.resolve(ordinary, single, policy),
      Unresolved(ResolutionFailure.Uncalibrated)
    )
    val two = bundle(Vector(proposed(1, 0.99), proposed(2, 0.5)), calibrated = None)
    assert(Resolver.resolve(ordinary, two, policy).isInstanceOf[Alternatives[?]])
    val dev = AcceptancePolicy(Map.empty, FamilyPolicy.Development)
    assert(Resolver.resolve(ordinary, single, dev).isInstanceOf[Alternatives[?]])

  test("calibration is attached to the value it calibrates, not to the bundle"):
    // X leads on support; only Y is calibrated → X cannot be accepted on Y's probability
    val b = bundle(
      Vector(proposed(1, 0.5, "e1", "parser"), proposed(1, 0.4, "e2", "agent"), proposed(2, 0.9)),
      calibrated = None
    ).copy(calibrations =
      Vector(CandidateCalibration(2, Probability.unsafe(0.95), "test-calibration"))
    )
    val r = Resolver.resolve(ordinary, b, policy)
    assert(!r.isAccepted, s"unexpected $r")
    assert(r.isInstanceOf[Alternatives[?]])

  test("high-impact families need the required number of agreeing distinct providers"):
    val one = bundle(Vector(proposed(1, 0.9)))
    Resolver.resolve(highImpact, one, policy) match
      case Unresolved(ResolutionFailure.InsufficientAgreement(1, 2)) => ()
      case other                                                     => fail(s"unexpected $other")
    val sameProvider = bundle(Vector(proposed(1, 0.9), proposed(1, 0.8, "e2")))
    Resolver.resolve(highImpact, sameProvider, policy) match
      case Unresolved(ResolutionFailure.InsufficientAgreement(1, 2)) => ()
      case other                                                     => fail(s"unexpected $other")
    val two = bundle(Vector(proposed(1, 0.9, "e1", "parser"), proposed(1, 0.8, "e2", "agent")))
    Resolver.resolve(highImpact, two, policy) match
      case Accepted(1, p, ev) =>
        assertEquals(p, Probability.unsafe(0.95))
        assertEquals(ev.toVector.map(_.evidenceId.value).sorted, Vector("e1", "e2"))
      case other => fail(s"unexpected $other")

  test("conservative families reject with zero source support"):
    val b = bundle(
      Vector(proposed(1, 0.9, "e1", "parser"), proposed(1, 0.8, "e2", "agent")),
      support = 0.0
    )
    assertEquals(Resolver.resolve(highImpact, b, policy), Rejected(RejectionReason.NoSourceSupport))

  test("acceptance needs span evidence unless the family policy waives it"):
    val noSpans = bundle(Vector(proposed(1, 0.9)), spans = None)
    assertEquals(
      Resolver.resolve(ordinary, noSpans, policy),
      Unresolved(ResolutionFailure.NoSpanEvidence)
    )
    val inline = AgentProposal.proposed(
      task,
      1,
      cats.data.NonEmptyVector.one(evWithSpans("s")),
      Some(RawScore.unsafe(0.9)),
      Vector.empty,
      receipt
    )
    assert(Resolver.resolve(ordinary, bundle(Vector(inline), spans = None), policy).isAccepted)
    val dev = AcceptancePolicy(Map.empty, FamilyPolicy.Development)
    assert(Resolver.resolve(ordinary, noSpans, dev).isAccepted)

  test("accept, review, and reject regions follow the family policy"):
    def at(p: Double) =
      Resolver.resolve(ordinary, bundle(Vector(proposed(1, 0.9)), calibrated = Some(p)), policy)
    assert(at(0.7).isAccepted)
    assert(at(0.5).isInstanceOf[Alternatives[?]])
    at(0.1) match
      case Rejected(RejectionReason.BelowRejectBand(_, _)) => ()
      case other                                           => fail(s"unexpected $other")

  test("resolution is not a vote: the leading value is chosen by providers, then score"):
    val b = bundle(Vector(proposed(1, 0.2), proposed(2, 0.9)))
    // equal provider support (1 each) → highest raw score wins
    assertEquals(Resolver.resolve(ordinary, b, policy).accepted, Some(2))
    val c = bundle(
      Vector(proposed(1, 0.2, "e1", "parser"), proposed(1, 0.1, "e2", "agent"), proposed(2, 0.9))
    )
    // more independent providers win even with lower scores; the calibrated probability still gates
    assertEquals(Resolver.resolve(ordinary, c, policy).accepted, Some(1))

  test("alternatives never count toward agreement"):
    val b = bundle(Vector(proposed(1, 0.9), alternative(1, 0.8)))
    Resolver.resolve(highImpact, b, policy) match
      case Unresolved(ResolutionFailure.InsufficientAgreement(1, 2)) => ()
      case other                                                     => fail(s"unexpected $other")

  test("a missing raw score is never rendered as zero and sorts last among alternatives"):
    val b = bundle(
      Vector(proposedUnscored(1), proposed(2, -0.3, "e2"), proposed(3, 0.4, "e3")),
      calibrated = None
    )
    Resolver.resolve(ordinary, b, policy) match
      case Alternatives(vs) =>
        assertEquals(vs.toVector.map(_.value), Vector(3, 2, 1))
        assertEquals(vs.toVector.map(_.score.map(_.value)), Vector(Some(0.4), Some(-0.3), None))
      case other => fail(s"unexpected $other")

  test("family policy validation"):
    assert(FamilyPolicy.of(Probability.unsafe(0.5), Probability.unsafe(0.6), 1, true, false).isLeft)
    assert(FamilyPolicy.of(Probability.unsafe(0.5), Probability.unsafe(0.4), 0, true, false).isLeft)
    assert(
      FamilyPolicy.of(Probability.unsafe(0.5), Probability.unsafe(0.4), 1, true, false).isRight
    )

  property("Accepted carries the calibrated probability of the accepted value"):
    forAll { (f: ClaimFamily, b: EvidenceBundle[Int]) =>
      Resolver.resolve(f, b, policy) match
        case Accepted(v, p, _) => b.calibrationFor(v).exists(_.probability == p)
        case _                 => true
    }

  property("Accepted always carries evidence, with spans somewhere unless waived"):
    forAll { (f: ClaimFamily, b: EvidenceBundle[Int]) =>
      Resolver.resolve(f, b, policy) match
        case Accepted(_, _, ev) =>
          ev.length > 0 && (b.sourceSupport.spans.nonEmpty || ev.exists(_.spans.nonEmpty))
        case _ => true
    }

  property("agreement counts distinct providers, never proposals"):
    forAll { (f: ClaimFamily, b: EvidenceBundle[Int]) =>
      Resolver.resolve(f, b, policy) match
        case Accepted(v, _, _) =>
          val providers = b.proposals
            .filter(p => p.disposition == ProposalDisposition.Proposed && p.value.contains(v))
            .map(_.receipt.call.provider)
            .distinct
            .size
          providers >= policy.forFamily(f).requireAgreement
        case _ => true
    }

  property("resolution is deterministic"):
    forAll { (f: ClaimFamily, b: EvidenceBundle[Int]) =>
      Resolver.resolve(f, b, policy) == Resolver.resolve(f, b, policy)
    }

  property("confident blocking findings never yield Accepted or Alternatives"):
    forAll { (f: ClaimFamily, b: EvidenceBundle[Int]) =>
      val threshold = policy.forFamily(f).criticBlockThreshold
      if CriticFinding.blocking(b.findings, threshold).nonEmpty then
        Resolver.resolve(f, b, policy) match
          case Rejected(_) | Unresolved(_) => true
          case _                           => false
      else true
    }

  property("accepted value is one of the proposed values"):
    forAll { (f: ClaimFamily, b: EvidenceBundle[Int]) =>
      Resolver.resolve(f, b, policy).accepted.forall(v => b.proposals.exists(_.value.contains(v)))
    }

  test("candidate ledger is append-only with supersession history"):
    val b = bundle(Vector(proposed(1, 0.9)))
    val s = Resolver.resolve(ordinary, b, policy)
    val c1 = ClaimId.unsafe("c1")
    val c2 = ClaimId.unsafe("c2")
    val l1 = CandidateLedger.empty[Int].add(c1, LedgerEntry(b, s, None)).toOption.get
    assert(l1.add(c1, LedgerEntry(b, s, None)).isLeft)
    assert(l1.supersede(ClaimId.unsafe("nope"), c2, b, s).isLeft)
    val l2 = l1.supersede(c1, c2, b, Unresolved(ResolutionFailure.NoProposal)).toOption.get
    assertEquals(l2.size, 2)
    assertEquals(l2.history(c2), Vector(c2, c1))
    assertEquals(l2.current, Vector(c2))
    assertEquals(l2.accepted, Vector.empty)
    assertEquals(l1.accepted, Vector(c1 -> 1))
