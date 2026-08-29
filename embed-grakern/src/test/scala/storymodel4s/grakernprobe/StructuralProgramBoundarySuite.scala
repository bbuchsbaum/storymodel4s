package storymodel4s.grakernprobe

import scala.compiletime.testing.typeCheckErrors
import scala.deriving.Mirror

import munit.FunSuite

/** Same-file control: a plain case class is still forgeable through `Mirror.Product`. */
final case class StructuralForgeControl(rounds: Int)

/** Outside `storymodel4s.embed.grakern`: the WL program exposes identity, not construction. */
class StructuralProgramBoundarySuite extends FunSuite:

  test("positive control: a case class Mirror.fromProduct still compiles") {
    val errors = typeCheckErrors(
      """summon[scala.deriving.Mirror.ProductOf[storymodel4s.grakernprobe.StructuralForgeControl]]
           .fromProduct(Tuple1(0))"""
    )
    assertEquals(errors, Nil, "control Mirror.fromProduct must compile or the probe cannot fail")
    val forged = summon[Mirror.ProductOf[StructuralForgeControl]].fromProduct(Tuple1(0))
    assertEquals(forged.rounds, 0)
  }

  test("StructuralProgram has no Mirror.ProductOf or fromProduct bypass") {
    val mirror = typeCheckErrors(
      "summon[scala.deriving.Mirror.ProductOf[storymodel4s.embed.grakern.StructuralProgram]]"
    )
    val fromProduct = typeCheckErrors(
      "storymodel4s.embed.grakern.StructuralProgram.fromProduct(EmptyTuple)"
    )
    assert(mirror.nonEmpty, "Mirror.ProductOf reconstructed an unchecked StructuralProgram")
    assert(fromProduct.nonEmpty, "fromProduct reconstructed an unchecked StructuralProgram")
  }

  test("public reads and the smart constructor remain available") {
    val reads = typeCheckErrors(
      """val program = storymodel4s.embed.grakern.StructuralProgram.of(1)
        program.foreach { p =>
          val _ = (p.rounds, p.fingerprint, p.providerName, p.modelName)
        }"""
    )
    assertEquals(reads, Nil)
  }
