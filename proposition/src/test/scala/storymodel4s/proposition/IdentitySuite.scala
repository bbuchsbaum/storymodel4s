package storymodel4s.proposition

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

class IdentitySuite extends ScalaCheckSuite:
  import ChartGens.given

  property("canonical form is idempotent") {
    forAll { (chart: PropositionChart[Checked]) =>
      val once = Canonical.form(chart)
      Canonical.form(once) == once
    }
  }

  property("canonical form is isomorphic to its input and preserves validity") {
    forAll { (chart: PropositionChart[Checked]) =>
      val f = Canonical.form(chart)
      ChartIsomorphism.isomorphic(chart, f) && ChartValidator.validate(f).isValid
    }
  }

  property("checksum and canonical form are invariant under concept renaming") {
    forAll(ChartGens.validChart, Gen.chooseNum(1, 1000)) { (chart, salt) =>
      val r = ChartGens.renamed(chart, salt)
      Canonical.checksum(r) == Canonical.checksum(chart) &&
      Canonical.form(r) == Canonical.form(chart)
    }
  }

  property("isomorphic ⇔ canonical forms equal (renamed pairs)") {
    forAll(ChartGens.validChart, Gen.chooseNum(1, 1000)) { (chart, salt) =>
      val r = ChartGens.renamed(chart, salt)
      ChartIsomorphism.isomorphic(chart, r) && Canonical.form(chart) == Canonical.form(r)
    }
  }

  property("isomorphism is reflexive and symmetric; serializations agree with the verdict") {
    forAll(ChartGens.validChart, ChartGens.validChart) { (a, b) =>
      val iso = ChartIsomorphism.isomorphic(a, b)
      ChartIsomorphism.isomorphic(a, a) &&
      iso == ChartIsomorphism.isomorphic(b, a) &&
      iso == (Canonical.serialization(a) == Canonical.serialization(b))
    }
  }

  test("structurally different charts are not isomorphic") {
    val c0 = ConceptId.unsafe("a"); val c1 = ConceptId.unsafe("b")
    val base = Map(c0 -> Concept.predicate("strike"), c1 -> Concept.entity("warrior"))
    val x = PropositionChart.unchecked(
      Some(c0),
      base,
      Vector(PropositionRelation(c0, RoleAssignment.arg(0), ConceptTarget.Node(c1)))
    )
    val y = PropositionChart.unchecked(
      Some(c0),
      base,
      Vector(PropositionRelation(c0, RoleAssignment.arg(1), ConceptTarget.Node(c1)))
    )
    val z = x.copy(polarity = Map(c0 -> Polarity.Negative))
    assert(!ChartIsomorphism.isomorphic(x, y))
    assert(!ChartIsomorphism.isomorphic(x, z))
    assertNotEquals(Canonical.checksum(x), Canonical.checksum(y))
    assertNotEquals(Canonical.checksum(x), Canonical.checksum(z))
  }

  test("reentrancy survives canonicalization and distinguishes charts") {
    val p = ConceptId.unsafe("p"); val e = ConceptId.unsafe("e"); val f = ConceptId.unsafe("f")
    val concepts =
      Map(p -> Concept.predicate("hide"), e -> Concept.entity("man"), f -> Concept.entity("man"))
    val reentrant = PropositionChart.unchecked(
      Some(p),
      concepts - f,
      Vector(
        PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(e)),
        PropositionRelation(p, RoleAssignment.arg(1), ConceptTarget.Node(e))
      )
    )
    val split = PropositionChart.unchecked(
      Some(p),
      concepts,
      Vector(
        PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(e)),
        PropositionRelation(p, RoleAssignment.arg(1), ConceptTarget.Node(f))
      )
    )
    assert(reentrant.isReentrant(e))
    assert(!ChartIsomorphism.isomorphic(reentrant, split))
    val canon = Canonical.form(reentrant)
    assert(canon.conceptIds.exists(canon.isReentrant))
  }

  test("symmetric charts get a unique canonical form regardless of input labeling") {
    // Two indistinguishable entity arguments of one predicate: an automorphism.
    def chart(l1: String, l2: String) =
      val p = ConceptId.unsafe("p"); val a = ConceptId.unsafe(l1); val b = ConceptId.unsafe(l2)
      PropositionChart.unchecked(
        Some(p),
        Map(p -> Concept.predicate("meet"), a -> Concept.entity("man"), b -> Concept.entity("man")),
        Vector(
          PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(a)),
          PropositionRelation(p, RoleAssignment.arg(1), ConceptTarget.Node(b))
        )
      )
    assertEquals(Canonical.serialization(chart("x", "y")), Canonical.serialization(chart("y", "x")))
  }
