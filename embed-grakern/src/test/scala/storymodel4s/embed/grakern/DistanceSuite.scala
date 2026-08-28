package storymodel4s.embed.grakern

import munit.FunSuite

import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.proposition.*
import storymodel4s.proposition.CheckState.Checked

/** `d_wl` laws: G1 (structural differences separate), G2 (renaming/order invariance, bit-exact),
  * paraphrase stability, Missing behaviour, receipts, determinism.
  */
class DistanceSuite extends FunSuite:
  import Charts.*

  private val straight = transitive("find", "anna", "brother")
  private val swapped = transitive("find", "brother", "anna")
  private val negated = transitive("find", "anna", "brother", negated = true)
  private val renamed =
    transitive("find", "anna", "brother", ids = ("x1", "y2", "z3"), relationOrder = false)
  private val other = transitive("hear", "anna", "scream")

  private def prepared(sources: PropositionChart[Checked]*): GrakernStructuralDistance =
    GrakernStructuralDistance.prepare(sources.toVector).fold(e => fail(e.message), identity)

  private def d(
      dist: GrakernStructuralDistance,
      q: PropositionChart[Checked],
      s: PropositionChart[Checked]
  ): Double =
    dist(PropositionEvidence.hand(q), PropositionEvidence.hand(s)) match
      case Estimate.Observed(v, _) => v
      case other                   => fail(s"expected an observed distance, got $other")

  test("identical charts have d_wl = 0; unrelated charts have d_wl close to 1") {
    val dist = prepared(straight, other)
    assertEqualsDouble(d(dist, straight, straight), 0.0, 1e-9)
    assert(d(dist, straight, other) > 0.5, d(dist, straight, other))
  }

  test("G1: ARG0/ARG1 filler swap gives kernel < 1 (d_wl > 0)") {
    val dist = prepared(straight, swapped)
    val dd = d(dist, straight, swapped)
    assert(dd > 0.0, dd)
    assert(dd < 1.0, dd)
    assertEqualsDouble(d(dist, swapped, swapped), 0.0, 1e-9)
  }

  test("G1: polarity flip and embedding-kind change give d_wl > 0") {
    val dist = prepared(straight, negated)
    assert(d(dist, straight, negated) > 0.0)
    val speech = embedded(EmbeddingKind.Speech)
    val belief = embedded(EmbeddingKind.Belief)
    val dist2 = prepared(speech, belief)
    assert(d(dist2, speech, belief) > 0.0)
    assertEqualsDouble(d(dist2, belief, belief), 0.0, 1e-9)
  }

  test("G2: alpha-renaming and relation order give bit-identical d_wl") {
    val dist = prepared(straight, swapped, other)
    val a = d(dist, straight, swapped)
    val b = d(dist, renamed, swapped)
    assertEquals(java.lang.Double.doubleToLongBits(a), java.lang.Double.doubleToLongBits(b))
    assertEquals(
      java.lang.Double.doubleToLongBits(d(dist, straight, other)),
      java.lang.Double.doubleToLongBits(d(dist, renamed, other))
    )
  }

  test("paraphrase-stable: same structure with different concept ids is distance 0") {
    val dist = prepared(straight)
    assertEqualsDouble(d(dist, renamed, straight), 0.0, 1e-9)
  }

  test("Missing when the source chart was not prepared") {
    val dist = prepared(straight)
    assertEquals(
      dist(PropositionEvidence.hand(straight), PropositionEvidence.hand(other)),
      Estimate.missing(MissingReason.Excluded)
    )
  }

  test("receipts: one ProviderCall per distinct query chart, with the grakern pin") {
    val dist = prepared(straight, swapped, other)
    d(dist, straight, swapped)
    d(dist, straight, other) // same query chart: memoized, no new call
    d(dist, negated, other)
    val calls = dist.receipts
    assertEquals(calls.size, 2)
    assert(calls.forall(_.provider == "grakern"))
    assert(calls.forall(_.version == GrakernPin.revision))
    assert(calls.forall(_.params("rounds") == "2"))
    assert(calls.forall(_.params.contains("fingerprint")))
    assertEquals(GrakernPin.revision.length, 40)
  }

  test("determinism: two independent preparations agree bit-for-bit and receipt checksums match") {
    val a = prepared(straight, swapped, other)
    val b = prepared(straight, swapped, other)
    val qa = d(a, negated, swapped)
    val qb = d(b, negated, swapped)
    assertEquals(java.lang.Double.doubleToLongBits(qa), java.lang.Double.doubleToLongBits(qb))
    assertEquals(a.receipts.map(_.outputChecksum), b.receipts.map(_.outputChecksum))
    assertEquals(a.receipts.map(_.inputChecksum), b.receipts.map(_.inputChecksum))
  }

  test("structural EmbeddingSpace identity is a function of program and dictionary size") {
    val p1 = StructuralProgram.of(2).fold(e => fail(e.message), identity)
    val p2 = StructuralProgram.of(3).fold(e => fail(e.message), identity)
    val s1 = StructuralSpaces.of(p1, 10).fold(e => fail(e.message), identity)
    val s1b = StructuralSpaces.of(p1, 10).fold(e => fail(e.message), identity)
    val s2 = StructuralSpaces.of(p2, 10).fold(e => fail(e.message), identity)
    assertEquals(s1.id, s1b.id)
    assertNotEquals(s1.id, s2.id)
    assertNotEquals(p1.fingerprint, p2.fingerprint)
  }

  test(
    "prepared dictionary vectors are L2-normalized and reproduce the kernel for prepared charts"
  ) {
    val dist = prepared(straight, other)
    val v = dist.prepared.vectorOf(straight).fold(e => fail(e.message), identity)
    assertEqualsDouble(v.norm, 1.0, 1e-9)
  }
