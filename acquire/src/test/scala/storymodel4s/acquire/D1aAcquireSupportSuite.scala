package storymodel4s.acquire

import munit.FunSuite
import storymodel4s.core.*

class D1aAcquireSupportSuite extends FunSuite:
  import D1aAcquireFixtures.*
  private val policy = AcceptancePolicy(Map.empty, FamilyPolicy.Ordinary)
  private def resolve(b: EvidenceBundle[Int]) = Resolver.resolve(ClaimFamily.EntityMention, b, policy)
  private val missing = ResolutionState.Unresolved(ResolutionFailure.NoSpanEvidence)

  test("accepting control: existing text support retains spans and acceptance"):
    val text = SourceSupport.text(0.5, Some(Fixtures.someSpans))
    assertEquals(text.support, Some(TypedSupport.Text(Fixtures.someSpans)))
    assertEquals(text.spans, Some(Fixtures.someSpans))
    assert(resolve(bundle(Vector(proposal(1, inlineEvidence("text", text = true))), text.support)).isAccepted)
    assertEquals(SourceSupport.text(0.5, None).spans, None)
    assertEquals(SourceSupport(0.5, Some(TypedSupport.Anchored(anchored))).spans, None)

  test("inline singular support covers neither, text, anchors and twin form without selecting a winner"):
    assertEquals(inlineEvidence("none").support, None)
    assertEquals(inlineEvidence("text", text = true).support, Some(TypedSupport.Text(Fixtures.someSpans)))
    assertEquals(inlineEvidence("anchors", anchors = true).support, Some(TypedSupport.Anchored(anchored)))
    val both = inlineEvidence("both", text = true, anchors = true)
    assertEquals(both.support, None)
    assertEquals(both.spans, Some(Fixtures.someSpans))

  test("ById cannot invent direct support without a ledger"):
    val ref = EvidenceRef.ById(EvidenceId.unsafe("in-some-other-ledger"))
    assertEquals(ref.support, None)
    assertEquals(resolve(bundle(Vector(proposal(1, ref)))), missing)
    assert(resolve(bundle(Vector(proposal(1, ref)), Some(TypedSupport.Anchored(anchored)))).isAccepted)

  test("anchored bundle support alone admits determined and calibrated winners"):
    for basis <- Vector(determined, calibrated(0.95)) do
      val b = bundle(Vector(proposal(1, inlineEvidence("no-direct"))), Some(TypedSupport.Anchored(anchored)), basis)
      assert(resolve(b).isAccepted)
      assertEquals(resolve(b.copy(sourceSupport = SourceSupport(1.0, None))), missing)

  test("winning inline anchored support alone admits determined and calibrated winners"):
    for basis <- Vector(determined, calibrated(0.95)) do
      val b = bundle(Vector(proposal(1, inlineEvidence("winning", anchors = true))), basis = basis)
      assert(resolve(b).isAccepted)
      assertEquals(resolve(b.copy(proposals = Vector(proposal(1, inlineEvidence("missing"))))), missing)

  test("losing inline anchors do not support the winner"):
    val b = bundle(Vector(proposal(1, inlineEvidence("first"), "one"),
      proposal(1, inlineEvidence("second"), "two"), proposal(2, inlineEvidence("loser", anchors = true), "three")))
    assertEquals(resolve(b), missing)
    assert(resolve(b.copy(sourceSupport = SourceSupport(1.0, Some(TypedSupport.Anchored(anchored))))).isAccepted)

  test("twin-form inline evidence alone is ambiguous but does not erase another valid witness"):
    val b = bundle(Vector(proposal(1, inlineEvidence("both", text = true, anchors = true))))
    assertEquals(resolve(b), missing)
    assert(resolve(b.copy(sourceSupport = SourceSupport.text(1.0, Some(Fixtures.someSpans)))).isAccepted)

  test("anchored presence preserves structural critic agreement and calibration boundaries"):
    val b = bundle(Vector(proposal(1, inlineEvidence("anchor", anchors = true))))
    assert(!resolve(b.copy(structural = StructuralValidity.invalid("bad"))).isAccepted)
    assert(!resolve(b.copy(findings = Vector(Fixtures.finding(FindingCode.HallucinatedConcept)))).isAccepted)
    assertEquals(resolve(b.copy(bases = Vector.empty)), ResolutionState.Unresolved(ResolutionFailure.Uncalibrated))
    assert(!Resolver.resolve(ClaimFamily.CausalEdge, b, AcceptancePolicy.Conservative).isAccepted)
    assert(resolve(b).isAccepted)
