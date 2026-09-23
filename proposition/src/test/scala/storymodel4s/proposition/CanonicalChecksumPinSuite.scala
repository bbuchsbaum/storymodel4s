package storymodel4s.proposition

import munit.FunSuite
import org.scalacheck.Gen
import org.scalacheck.rng.Seed
import storymodel4s.core.Sha256

/** Regression guard, not a positive control: pins `Canonical`'s public outputs as they were before
  * the S2a-1 keying parameter (mote bd-01M379KVFH4YD0WT6S3K1ZS1J1). The digest was captured on
  * `8e3b9090`, before `identity.scala` changed. The corpus includes glossed twins, because the new
  * gloss-aware keying must not leak into the default identity, which ignores glosses.
  */
class CanonicalChecksumPinSuite extends FunSuite:
  private def id(s: String) = ConceptId.unsafe(s)

  private def checked(u: PropositionChart[Unchecked]): PropositionChart[Checked] =
    ChartValidator.check(u).fold(v => fail(s"invalid chart: $v"), identity)

  private val generated: Vector[PropositionChart[Checked]] =
    (1 to 120).toVector.flatMap(i => ChartGens.validChart(Gen.Parameters.default, Seed(i.toLong)))

  /** Two same-lemma fillers told apart only by gloss. */
  private def glossTwins(first: String, second: String): PropositionChart[Checked] =
    val p = id("p")
    checked(
      PropositionChart.unchecked(
        Some(p),
        Map(
          p -> Concept.predicate("see"),
          id(first) -> Concept(Lemma.unsafe("animal"), Some("pet"), None, ConceptKind.Entity),
          id(second) -> Concept(Lemma.unsafe("animal"), Some("wild"), None, ConceptKind.Entity)
        ),
        Vector(
          PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(id(first))),
          PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(id(second)))
        )
      )
    )

  private val corpus = generated ++ Vector(glossTwins("c", "d"), glossTwins("d", "c"))

  private def render(c: PropositionChart[Checked]): String =
    Vector(
      Canonical.checksum(c).hex,
      Canonical.isExact(c).toString,
      Canonical.order(c).map(_.value).mkString(","),
      Canonical.serialization(c)
    ).mkString("|")

  test("the corpus is the one the pin was captured on") {
    assertEquals(generated.size, 70)
  }

  test("Canonical checksum, exactness, order and serialization are byte-identical to the base") {
    assertEquals(
      Sha256.hexDigest(corpus.map(render).mkString("\n")),
      "cd3d54fb1850f086fdea739929dfe1861e727eff068fbe4fcfd2378729d22eac"
    )
  }
