package storymodel4s.view

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*

/** The shared reader-horizon closure (ADR 0002 D4 row 7). */
class VisibilitySuite extends FunSuite:
  val fp = Fingerprint.unsafe("test:rules:0")
  val stage = StageId.unsafe("test")
  val prov = Provenance.deterministic("test", Checksum.ofText("test"))

  def claim(
      id: String,
      spans: Option[SpanSet],
      upstream: Set[ClaimId] = Set.empty,
      status: EpistemicStatus = EpistemicStatus.SurfaceExplicit
  ): ClaimMeta =
    ClaimMeta.unsafe(
      ClaimId.unsafe(id),
      status,
      Credence.unsafeRaw(1.0, ScorerId.unsafe("test-scorer")),
      NonEmptyVector.one(Evidence(EvidenceId.unsafe(s"e:$id"), spans, upstream, fp, stage)),
      prov
    )

  def span(a: Int, b: Int) = SpanSet.one(TextSpan.unsafe(a, b))

  val early = claim("early", Some(span(0, 10)))
  val late = claim("late", Some(span(40, 50)))
  val inferred =
    claim("inferred", None, Set(early.id, late.id), EpistemicStatus.WorldKnowledgeInferred)
  val onlyEarly = claim("only-early", None, Set(early.id), EpistemicStatus.StructurallyDerived)
  val ungrounded = claim("ungrounded", None, Set.empty, EpistemicStatus.Hypothesized)
  val ledger =
    ClaimLedger.empty.addAll(Vector(early, late, inferred, onlyEarly, ungrounded)).toOption.get

  test("reader horizons stay inside canonical text and never split a UTF-16 code point"):
    val text = "a\ud83d\udc7bb"
    assert(EvidenceVisibility.validateHorizon(text, EpistemicHorizon.Omniscient).isRight)
    assert(EvidenceVisibility.validateHorizon(text, EpistemicHorizon.ReaderAt(0)).isRight)
    assert(EvidenceVisibility.validateHorizon(text, EpistemicHorizon.ReaderAt(text.length)).isRight)
    assert(EvidenceVisibility.validateHorizon(text, EpistemicHorizon.ReaderAt(-1)).isLeft)
    assert(EvidenceVisibility.validateHorizon(text, EpistemicHorizon.ReaderAt(5)).isLeft)
    assert(EvidenceVisibility.validateHorizon(text, EpistemicHorizon.ReaderAt(2)).isLeft)

  test("a claim is visible only when all cited spans end at or before the horizon"):
    assertEquals(EvidenceVisibility.visibleClaims(10, ledger).contains(early.id), true)
    assertEquals(EvidenceVisibility.visibleClaims(9, ledger).contains(early.id), false)
    assertEquals(EvidenceVisibility.visibleClaims(49, ledger).contains(late.id), false)
    assertEquals(EvidenceVisibility.visibleClaims(50, ledger).contains(late.id), true)

  test("visibility is the transitive closure over upstream claims"):
    val at20 = EvidenceVisibility.visibleClaims(20, ledger)
    assert(at20.contains(onlyEarly.id))
    assert(!at20.contains(inferred.id))
    val at50 = EvidenceVisibility.visibleClaims(50, ledger)
    assert(at50.contains(inferred.id))

  test("ungrounded evidence is never visible under a reader horizon"):
    assert(!EvidenceVisibility.visibleClaims(1000, ledger).contains(ungrounded.id))

  test("omniscient means all; clipSupport drops only spans ending after t"):
    assertEquals(EvidenceVisibility.visibleUnder(EpistemicHorizon.Omniscient, ledger), None)
    val support =
      SpanSet.of(Vector(SpanRef(TextSpan.unsafe(0, 5)), SpanRef(TextSpan.unsafe(30, 60)))).get
    assertEquals(
      EvidenceVisibility.clipSupport(support, EpistemicHorizon.Omniscient),
      Some(support)
    )
    assertEquals(
      EvidenceVisibility.clipSupport(support, EpistemicHorizon.ReaderAt(10)).map(_.spans.toVector),
      Some(Vector(TextSpan.unsafe(0, 5)))
    )
    assertEquals(EvidenceVisibility.clipSupport(support, EpistemicHorizon.ReaderAt(4)), None)

  test("state parts are canonical regardless of set iteration order"):
    val a = Addressable[CoreRef].address(CoreRef.Claim(early.id))
    val b = Addressable[CoreRef].address(CoreRef.Claim(late.id))
    val s1 = CommonViewState.of(selection = Set(a, b)).toOption.get
    val s2 = CommonViewState.of(selection = Set(b, a)).toOption.get
    assertEquals(EvidenceVisibility.stateParts(s1), EvidenceVisibility.stateParts(s2))
    assert(EvidenceVisibility.stateParts(s1).head == "horizon:omniscient")
