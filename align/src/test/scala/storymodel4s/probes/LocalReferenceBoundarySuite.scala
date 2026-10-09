package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.*
import storymodel4s.recall.RecallUnitId

/** Public same-shape control proves each external construction probe can compile. */
final case class ReferenceProfileControl(
    universe: DeclaredUniverse,
    temperature: Double,
    level: Int
)

/** Public same-shape control proves each external construction probe can compile. */
final case class ReferenceResultControl(
    evidence: LocalEvidence,
    profile: LocalReference.Profile,
    outcomes: Vector[LocalReference.Outcome]
)

/** Public same-shape control proves each external construction probe can compile. */
final case class ReferenceComputedControl(
    unit: RecallUnitId,
    costs: Map[AlignState, CostBreakdown],
    mass: Map[AlignState, Double],
    information: LocalReference.Information,
    overflow: Vector[TieOverflow],
    uniformSemantic: Vector[UniformSemanticScores],
    semanticOutcomes: Vector[SemanticOutcomeSummary]
)

/** Public same-shape control proves each external construction probe can compile. */
final case class ReferenceNotComputedControl(
    unit: RecallUnitId,
    costs: Map[AlignState, CostBreakdown],
    reason: LocalReference.NotComputedReason,
    overflow: Vector[TieOverflow],
    uniformSemantic: Vector[UniformSemanticScores],
    semanticOutcomes: Vector[SemanticOutcomeSummary]
)

/** An explicit public product factory is the direct-door positive control. */
object ReferenceProfileControl:
  def fromProduct(p: Product): ReferenceProfileControl =
    ReferenceProfileControl(
      p.productElement(0).asInstanceOf[DeclaredUniverse],
      p.productElement(1).asInstanceOf[Double],
      p.productElement(2).asInstanceOf[Int]
    )

/** An explicit public product factory is the direct-door positive control. */
object ReferenceResultControl:
  def fromProduct(p: Product): ReferenceResultControl =
    ReferenceResultControl(
      p.productElement(0).asInstanceOf[LocalEvidence],
      p.productElement(1).asInstanceOf[LocalReference.Profile],
      p.productElement(2).asInstanceOf[Vector[LocalReference.Outcome]]
    )

/** An explicit public product factory is the direct-door positive control. */
object ReferenceComputedControl:
  def fromProduct(p: Product): ReferenceComputedControl =
    ReferenceComputedControl(
      p.productElement(0).asInstanceOf[RecallUnitId],
      p.productElement(1).asInstanceOf[Map[AlignState, CostBreakdown]],
      p.productElement(2).asInstanceOf[Map[AlignState, Double]],
      p.productElement(3).asInstanceOf[LocalReference.Information],
      p.productElement(4).asInstanceOf[Vector[TieOverflow]],
      p.productElement(5).asInstanceOf[Vector[UniformSemanticScores]],
      p.productElement(6).asInstanceOf[Vector[SemanticOutcomeSummary]]
    )

/** An explicit public product factory is the direct-door positive control. */
object ReferenceNotComputedControl:
  def fromProduct(p: Product): ReferenceNotComputedControl =
    ReferenceNotComputedControl(
      p.productElement(0).asInstanceOf[RecallUnitId],
      p.productElement(1).asInstanceOf[Map[AlignState, CostBreakdown]],
      p.productElement(2).asInstanceOf[LocalReference.NotComputedReason],
      p.productElement(3).asInstanceOf[Vector[TieOverflow]],
      p.productElement(4).asInstanceOf[Vector[UniformSemanticScores]],
      p.productElement(5).asInstanceOf[Vector[SemanticOutcomeSummary]]
    )

/** Each product-shaped positive control is outside align and the LocalReference owner scope. */
class LocalReferenceBoundarySuite extends FunSuite:
  inline val imports = """
    import storymodel4s.align.*
    import storymodel4s.probes.*
    import scala.deriving.Mirror
  """

  test("Profile has no unchecked constructor or apply door") {
    assert(typeCheckErrors(imports + "new LocalReference.Profile(null, 1.0, 0)").nonEmpty)
    assertEquals(typeCheckErrors(imports + "new ReferenceProfileControl(null, 1.0, 0)"), Nil)
    assert(typeCheckErrors(imports + "LocalReference.Profile(null, 1.0, 0)").nonEmpty)
    assertEquals(typeCheckErrors(imports + "ReferenceProfileControl(null, 1.0, 0)"), Nil)
  }

  test("Profile has no replacement copy door") {
    assert(
      typeCheckErrors(
        imports + "null.asInstanceOf[LocalReference.Profile].copy(temperature = 1.0)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "null.asInstanceOf[ReferenceProfileControl].copy(temperature = 1.0)"
      ),
      Nil
    )
  }

  test("Profile has no product construction door") {
    assert(
      typeCheckErrors(
        imports + "summon[Mirror.ProductOf[LocalReference.Profile]].fromProduct((null, 1.0, 0))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "summon[Mirror.ProductOf[ReferenceProfileControl]].fromProduct((null, 1.0, 0))"
      ),
      Nil
    )
  }

  test("Profile has no product Mirror door") {
    assert(typeCheckErrors(imports + "summon[Mirror.ProductOf[LocalReference.Profile]]").nonEmpty)
    assertEquals(
      typeCheckErrors(imports + "summon[Mirror.ProductOf[ReferenceProfileControl]]"),
      Nil
    )
  }

  test("Result has no unchecked constructor or apply door") {
    assert(
      typeCheckErrors(imports + "new LocalReference.Result(null, null, Vector.empty)").nonEmpty
    )
    assertEquals(
      typeCheckErrors(imports + "new ReferenceResultControl(null, null, Vector.empty)"),
      Nil
    )
    assert(typeCheckErrors(imports + "LocalReference.Result(null, null, Vector.empty)").nonEmpty)
    assertEquals(typeCheckErrors(imports + "ReferenceResultControl(null, null, Vector.empty)"), Nil)
  }

  test("Result has no replacement copy door") {
    assert(
      typeCheckErrors(
        imports + "null.asInstanceOf[LocalReference.Result].copy(evidence = null)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(imports + "null.asInstanceOf[ReferenceResultControl].copy(evidence = null)"),
      Nil
    )
  }

  test("Result has no product construction door") {
    assert(
      typeCheckErrors(
        imports + "summon[Mirror.ProductOf[LocalReference.Result]].fromProduct((null, null, Vector.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "summon[Mirror.ProductOf[ReferenceResultControl]].fromProduct((null, null, Vector.empty))"
      ),
      Nil
    )
  }

  test("Result has no product Mirror door") {
    assert(typeCheckErrors(imports + "summon[Mirror.ProductOf[LocalReference.Result]]").nonEmpty)
    assertEquals(typeCheckErrors(imports + "summon[Mirror.ProductOf[ReferenceResultControl]]"), Nil)
  }

  test("Computed has no unchecked constructor or apply door") {
    assert(
      typeCheckErrors(
        imports + "new LocalReference.Computed(null.asInstanceOf[storymodel4s.recall.RecallUnitId], Map.empty, Map.empty, LocalReference.Information.UniformCosts, Vector.empty, Vector.empty, Vector.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "new ReferenceComputedControl(null.asInstanceOf[storymodel4s.recall.RecallUnitId], Map.empty, Map.empty, LocalReference.Information.UniformCosts, Vector.empty, Vector.empty, Vector.empty)"
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        imports + "LocalReference.Computed(null.asInstanceOf[storymodel4s.recall.RecallUnitId], Map.empty, Map.empty, LocalReference.Information.UniformCosts, Vector.empty, Vector.empty, Vector.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "ReferenceComputedControl(null.asInstanceOf[storymodel4s.recall.RecallUnitId], Map.empty, Map.empty, LocalReference.Information.UniformCosts, Vector.empty, Vector.empty, Vector.empty)"
      ),
      Nil
    )
  }

  test("Computed has no replacement copy door") {
    assert(
      typeCheckErrors(
        imports + "null.asInstanceOf[LocalReference.Computed].copy(mass = Map.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "null.asInstanceOf[ReferenceComputedControl].copy(mass = Map.empty)"
      ),
      Nil
    )
  }

  test("Computed has no product construction door") {
    assert(
      typeCheckErrors(
        imports + "summon[Mirror.ProductOf[LocalReference.Computed]].fromProduct((null, Map.empty, Map.empty, LocalReference.Information.UniformCosts, Vector.empty, Vector.empty, Vector.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "summon[Mirror.ProductOf[ReferenceComputedControl]].fromProduct((null, Map.empty, Map.empty, LocalReference.Information.UniformCosts, Vector.empty, Vector.empty, Vector.empty))"
      ),
      Nil
    )
  }

  test("Computed has no product Mirror door") {
    assert(typeCheckErrors(imports + "summon[Mirror.ProductOf[LocalReference.Computed]]").nonEmpty)
    assertEquals(
      typeCheckErrors(imports + "summon[Mirror.ProductOf[ReferenceComputedControl]]"),
      Nil
    )
  }

  test("NotComputed has no unchecked constructor or apply door") {
    assert(
      typeCheckErrors(
        imports + "new LocalReference.NotComputed(null.asInstanceOf[storymodel4s.recall.RecallUnitId], Map.empty, LocalReference.NotComputedReason.Unranked, Vector.empty, Vector.empty, Vector.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "new ReferenceNotComputedControl(null.asInstanceOf[storymodel4s.recall.RecallUnitId], Map.empty, LocalReference.NotComputedReason.Unranked, Vector.empty, Vector.empty, Vector.empty)"
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        imports + "LocalReference.NotComputed(null.asInstanceOf[storymodel4s.recall.RecallUnitId], Map.empty, LocalReference.NotComputedReason.Unranked, Vector.empty, Vector.empty, Vector.empty)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "ReferenceNotComputedControl(null.asInstanceOf[storymodel4s.recall.RecallUnitId], Map.empty, LocalReference.NotComputedReason.Unranked, Vector.empty, Vector.empty, Vector.empty)"
      ),
      Nil
    )
  }

  test("NotComputed has no replacement copy door") {
    assert(
      typeCheckErrors(
        imports + "null.asInstanceOf[LocalReference.NotComputed].copy(reason = LocalReference.NotComputedReason.Unranked)"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "null.asInstanceOf[ReferenceNotComputedControl].copy(reason = LocalReference.NotComputedReason.Unranked)"
      ),
      Nil
    )
  }

  test("NotComputed has no product construction door") {
    assert(
      typeCheckErrors(
        imports + "summon[Mirror.ProductOf[LocalReference.NotComputed]].fromProduct((null, Map.empty, LocalReference.NotComputedReason.Unranked, Vector.empty, Vector.empty, Vector.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "summon[Mirror.ProductOf[ReferenceNotComputedControl]].fromProduct((null, Map.empty, LocalReference.NotComputedReason.Unranked, Vector.empty, Vector.empty, Vector.empty))"
      ),
      Nil
    )
  }

  test("NotComputed has no product Mirror door") {
    assert(
      typeCheckErrors(imports + "summon[Mirror.ProductOf[LocalReference.NotComputed]]").nonEmpty
    )
    assertEquals(
      typeCheckErrors(imports + "summon[Mirror.ProductOf[ReferenceNotComputedControl]]"),
      Nil
    )
  }

  test("checked public factories and inspectable computed fields remain available") {
    assertEquals(typeCheckErrors(imports + "LocalReference.Profile.of(null, 1.0)"), Nil)
    assertEquals(typeCheckErrors(imports + "LocalReference.compute(null, null, null, null)"), Nil)
    assertEquals(typeCheckErrors(imports + "null.asInstanceOf[LocalReference.Computed].mass"), Nil)
    assertEquals(
      typeCheckErrors(imports + "null.asInstanceOf[LocalReference.Result].evidenceId"),
      Nil
    )
  }

  test("Profile has no direct companion fromProduct door") {
    assert(typeCheckErrors(imports + "LocalReference.Profile.fromProduct((null, 1.0, 0))").nonEmpty)
    assertEquals(
      typeCheckErrors(imports + "ReferenceProfileControl.fromProduct((null, 1.0, 0))"),
      Nil
    )
  }

  test("Result has no direct companion fromProduct door") {
    assert(
      typeCheckErrors(
        imports + "LocalReference.Result.fromProduct((null, null, Vector.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(imports + "ReferenceResultControl.fromProduct((null, null, Vector.empty))"),
      Nil
    )
  }

  test("Computed has no direct companion fromProduct door") {
    assert(
      typeCheckErrors(
        imports + "LocalReference.Computed.fromProduct((null, Map.empty, Map.empty, LocalReference.Information.UniformCosts, Vector.empty, Vector.empty, Vector.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "ReferenceComputedControl.fromProduct((null, Map.empty, Map.empty, LocalReference.Information.UniformCosts, Vector.empty, Vector.empty, Vector.empty))"
      ),
      Nil
    )
  }

  test("NotComputed has no direct companion fromProduct door") {
    assert(
      typeCheckErrors(
        imports + "LocalReference.NotComputed.fromProduct((null, Map.empty, LocalReference.NotComputedReason.Unranked, Vector.empty, Vector.empty, Vector.empty))"
      ).nonEmpty
    )
    assertEquals(
      typeCheckErrors(
        imports + "ReferenceNotComputedControl.fromProduct((null, Map.empty, LocalReference.NotComputedReason.Unranked, Vector.empty, Vector.empty, Vector.empty))"
      ),
      Nil
    )
  }
