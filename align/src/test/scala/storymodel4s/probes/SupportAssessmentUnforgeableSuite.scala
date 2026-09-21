package storymodel4s.align.attack {

  import scala.compiletime.testing.typeCheckErrors

  /** The construction probes compiled INSIDE package `storymodel4s.align` (a subpackage), where
    * every `private[align]` member is visible. A qualified-private constructor is open here; a
    * bare-private one is not. The consumer-package court below asserts on these results.
    *
    * `typeCheckErrors` is a compile-time macro: these values are computed when this file compiles,
    * so a mutation of the production types needs this file recompiled (a clean test compile) to be
    * seen at all. Incremental compilation will otherwise serve the stale expansion.
    */
  private[storymodel4s] object SupportPackageAttack:
    // POSITIVE CONTROLS, same shapes as the probed doors: `typeCheckErrors` reports errors for a
    // snippet that fails for ANY reason, so a refusal without a passing control proves nothing.
    val productControl = typeCheckErrors(
      """import storymodel4s.align.*
       import storymodel4s.features.MissingReason
       case class BreakdownControl(
         terms: Map[CostTerm, Double],
         mode: Option[FidelityMode],
         exclusion: Option[Exclusion],
         total: Double,
         missingTerms: Set[CostTerm],
         sourceChartCoverage: Option[StructuralCoverage],
         reductions: Map[CostTerm, StructuralReductionReceipt],
         support: SupportAssessment,
         imputedTerms: Map[CostTerm, MissingReason]
       )
       val applied = BreakdownControl(???, ???, ???, 0.0, ???, ???, ???, ???, ???)
       val constructed = new BreakdownControl(???, ???, ???, 0.0, ???, ???, ???, ???, ???)
       val copied = applied.copy(total = 0.25)
       // A block-local case class has no reachable companion fromProduct, so the direct door's
       // control is a class that defines one, as a case class companion does.
       final class BreakdownDirectControl(val terms: Map[CostTerm, Double])
       object BreakdownDirectControl:
         def fromProduct(product: Product): BreakdownDirectControl = ???
       val direct = BreakdownDirectControl.fromProduct((???, ???, ???, 0.0, ???, ???, ???, ???, ???))
       val mirrored = summon[scala.deriving.Mirror.ProductOf[BreakdownControl]]
         .fromProduct((???, ???, ???, 0.0, ???, ???, ???, ???, ???))
       case class AssessedControl(share: Double, basis: CellSupportBasis)
       val a = AssessedControl(0.5, ???)
       val b = new AssessedControl(0.5, ???)
       val c = a.copy(share = 1.0)
       val d = summon[scala.deriving.Mirror.ProductOf[AssessedControl]].fromProduct((0.5, ???))
       """
    )
    val checkedDoor = typeCheckErrors(
      """import storymodel4s.align.*
       AlignWire.costBreakdown(???, ???, ???, 0.0, ???, ???, ???, ???, ???)
       """
    )

    val costBreakdownConstructor = typeCheckErrors(
      "import storymodel4s.align.*; new CostBreakdown(???, ???, ???, 0.0, ???, ???, ???, ???, ???)"
    )
    val costBreakdownApply = typeCheckErrors(
      "import storymodel4s.align.*; CostBreakdown(???, ???, ???, 0.0, ???, ???, ???, ???, ???)"
    )
    val costBreakdownCopy = typeCheckErrors(
      "import storymodel4s.align.*; CostBreakdown.unreachable.copy(total = 0.25)"
    )
    val costBreakdownFromProduct = typeCheckErrors(
      "import storymodel4s.align.*; CostBreakdown.fromProduct((???, ???, ???, 0.0, ???, ???, ???, ???, ???))"
    )
    val costBreakdownMirror = typeCheckErrors(
      """import storymodel4s.align.*
       summon[scala.deriving.Mirror.ProductOf[CostBreakdown]]
         .fromProduct((???, ???, ???, 0.0, ???, ???, ???, ???, ???))
       """
    )
    val costBreakdownProduct = typeCheckErrors(
      "import storymodel4s.align.*; val p: Product = CostBreakdown.unreachable"
    )

    val assessedConstructor = typeCheckErrors(
      "import storymodel4s.align.*; new SupportAssessment.Assessed(0.5, ???, ???)"
    )
    val assessedDerived = typeCheckErrors(
      "import storymodel4s.align.*; SupportAssessment.Assessed.derived(0.5, ???, ???)"
    )
    val assessedApply = typeCheckErrors(
      "import storymodel4s.align.*; SupportAssessment.Assessed(0.5, ???, ???)"
    )
    val assessedCopy = typeCheckErrors(
      "import storymodel4s.align.*; (??? : SupportAssessment.Assessed).copy(share = 0.5)"
    )
    val assessedMirror = typeCheckErrors(
      "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[SupportAssessment.Assessed]]"
    )
    val unestablishedConstructor = typeCheckErrors(
      "import storymodel4s.align.*; new SupportAssessment.Unestablished(???, ???)"
    )
    val unestablishedDerived = typeCheckErrors(
      "import storymodel4s.align.*; SupportAssessment.Unestablished.derived(???, ???)"
    )
    val notApplicableConstructor = typeCheckErrors(
      "import storymodel4s.align.*; new SupportAssessment.NotApplicable(???)"
    )
    val notApplicableOf = typeCheckErrors(
      "import storymodel4s.align.*; SupportAssessment.NotApplicable.of(???)"
    )
    val basisConstructor = typeCheckErrors(
      "import storymodel4s.align.*; new CellSupportBasis(???, ???, ???)"
    )
    val basisMirror = typeCheckErrors(
      "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[CellSupportBasis]]"
    )

}

package storymodel4s.probes {

  import munit.FunSuite
  import scala.compiletime.testing.{typeCheckErrors, typeChecks}

  /** The construction boundary of `CostBreakdown` and the support carrier, probed from an unrelated
    * consumer package (here) and from an `align` subpackage (`SupportPackageAttack`).
    *
    * The seal is what makes "a share is derived, never supplied" a property of the type rather than
    * of the one checked factory. While `CostBreakdown` was a case class with a `private[align]`
    * constructor, its `Mirror.fromProduct` rebuilt a record with any support outside `align`, and
    * any `align` subpackage could call the constructor, `apply` or `copy`
    * (bd-01M17ZNXY6AS1CMBQJRH3JMNVX, bd-01M19956MFSG7076QE4J66T7E9).
    */
  class SupportAssessmentUnforgeableSuite extends FunSuite:
    import storymodel4s.align.attack.SupportPackageAttack as attack

    private def refused(door: String, errors: List[scala.compiletime.testing.Error]): Unit =
      assert(errors.nonEmpty, s"$door is open")

    test("an align subpackage: same-shape controls compile, every product door is closed") {
      assertEquals(attack.productControl, Nil, "the same-shape product controls must compile")
      assertEquals(attack.checkedDoor, Nil, "the checked factory must stay reachable")
      refused("CostBreakdown constructor", attack.costBreakdownConstructor)
      refused("CostBreakdown.apply", attack.costBreakdownApply)
      refused("CostBreakdown.copy", attack.costBreakdownCopy)
      refused("CostBreakdown.fromProduct", attack.costBreakdownFromProduct)
      refused("Mirror.ProductOf[CostBreakdown]", attack.costBreakdownMirror)
      refused("CostBreakdown as Product", attack.costBreakdownProduct)
      refused("Assessed constructor", attack.assessedConstructor)
      refused("Assessed.derived", attack.assessedDerived)
      refused("Assessed.apply", attack.assessedApply)
      refused("Assessed.copy", attack.assessedCopy)
      refused("Mirror.ProductOf[Assessed]", attack.assessedMirror)
      refused("Unestablished constructor", attack.unestablishedConstructor)
      refused("Unestablished.derived", attack.unestablishedDerived)
      refused("NotApplicable constructor", attack.notApplicableConstructor)
      refused("NotApplicable.of", attack.notApplicableOf)
      refused("CellSupportBasis constructor", attack.basisConstructor)
      refused("Mirror.ProductOf[CellSupportBasis]", attack.basisMirror)
    }

    test("an unrelated consumer: same-shape controls compile, every product door is closed") {
      val control = typeCheckErrors(
        """import storymodel4s.align.*
         import storymodel4s.features.MissingReason
         case class BreakdownControl(
           terms: Map[CostTerm, Double],
           mode: Option[FidelityMode],
           exclusion: Option[Exclusion],
           total: Double,
           missingTerms: Set[CostTerm],
           sourceChartCoverage: Option[StructuralCoverage],
           reductions: Map[CostTerm, StructuralReductionReceipt],
           support: SupportAssessment,
           imputedTerms: Map[CostTerm, MissingReason]
         )
         val applied = BreakdownControl(???, ???, ???, 0.0, ???, ???, ???, ???, ???)
         val constructed = new BreakdownControl(???, ???, ???, 0.0, ???, ???, ???, ???, ???)
         val copied = applied.copy(total = 0.25)
         // A block-local case class has no reachable companion fromProduct, so the direct door's
         // control is a class that defines one, as a case class companion does.
         final class BreakdownDirectControl(val terms: Map[CostTerm, Double])
         object BreakdownDirectControl:
           def fromProduct(product: Product): BreakdownDirectControl = ???
         val direct = BreakdownDirectControl.fromProduct((???, ???, ???, 0.0, ???, ???, ???, ???, ???))
         val mirrored = summon[scala.deriving.Mirror.ProductOf[BreakdownControl]]
           .fromProduct((???, ???, ???, 0.0, ???, ???, ???, ???, ???))
         """
      )
      assertEquals(control, Nil, "the same-shape product control must compile")
      assertEquals(
        typeCheckErrors(
          """import storymodel4s.align.*
           AlignWire.costBreakdown(???, ???, ???, 0.0, ???, ???, ???, ???, ???)
           """
        ),
        Nil,
        "the checked factory must stay reachable"
      )
      Vector(
        "constructor" -> typeCheckErrors(
          "import storymodel4s.align.*; new CostBreakdown(???, ???, ???, 0.0, ???, ???, ???, ???, ???)"
        ),
        "apply" -> typeCheckErrors(
          "import storymodel4s.align.*; CostBreakdown(???, ???, ???, 0.0, ???, ???, ???, ???, ???)"
        ),
        "copy" -> typeCheckErrors(
          "import storymodel4s.align.*; CostBreakdown.unreachable.copy(total = 0.25)"
        ),
        "fromProduct" -> typeCheckErrors(
          "import storymodel4s.align.*; CostBreakdown.fromProduct((???, ???, ???, 0.0, ???, ???, ???, ???, ???))"
        ),
        "Mirror.ProductOf.fromProduct" -> typeCheckErrors(
          """import storymodel4s.align.*
           summon[scala.deriving.Mirror.ProductOf[CostBreakdown]]
             .fromProduct((???, ???, ???, 0.0, ???, ???, ???, ???, ???))
           """
        ),
        "the align-internal checked door" -> typeCheckErrors(
          "import storymodel4s.align.*; CostBreakdown.checked(???, ???, ???, 0.0, ???, ???, ???, ???, ???)"
        ),
        "the align-internal external producer" -> typeCheckErrors(
          "import storymodel4s.align.*; CostBreakdown.external(1.0)"
        ),
        "the align-internal basis producer" -> typeCheckErrors(
          "import storymodel4s.align.*; CellSupportBasis.fromWeights(???, ???, CostWeights.default)"
        )
      ).foreach((door, errors) => refused(s"CostBreakdown $door", errors))
    }

    test("an unrelated consumer cannot mint support or a basis") {
      Vector(
        "new Assessed" -> typeCheckErrors(
          "import storymodel4s.align.*; new SupportAssessment.Assessed(0.5, ???, ???)"
        ),
        "Assessed.apply" -> typeCheckErrors(
          "import storymodel4s.align.*; SupportAssessment.Assessed(0.5, ???, ???)"
        ),
        "Assessed.derived" -> typeCheckErrors(
          "import storymodel4s.align.*; SupportAssessment.Assessed.derived(0.5, ???, ???)"
        ),
        "Assessed.copy" -> typeCheckErrors(
          "import storymodel4s.align.*; (??? : SupportAssessment.Assessed).copy(share = 0.5)"
        ),
        "Assessed.fromProduct" -> typeCheckErrors(
          "import storymodel4s.align.*; SupportAssessment.Assessed.fromProduct((0.5, ???, ???))"
        ),
        "Mirror[Assessed]" -> typeCheckErrors(
          "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[SupportAssessment.Assessed]]"
        ),
        "new Unestablished" -> typeCheckErrors(
          "import storymodel4s.align.*; new SupportAssessment.Unestablished(???, ???)"
        ),
        "new NotApplicable" -> typeCheckErrors(
          "import storymodel4s.align.*; new SupportAssessment.NotApplicable(???)"
        ),
        "new CellSupportBasis" -> typeCheckErrors(
          "import storymodel4s.align.*; new CellSupportBasis(???, ???, ???)"
        ),
        "Mirror[CellSupportBasis]" -> typeCheckErrors(
          "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[CellSupportBasis]]"
        )
      ).foreach((door, errors) => refused(door, errors))
      // Controls: the public derivation doors are reachable, so the refusals are not vacuous.
      assert(
        typeChecks(
          "import storymodel4s.align.*; SupportAssessment.fromEvidence(Set.empty, Set.empty, Map.empty)"
        )
      )
      assert(
        typeChecks(
          "import storymodel4s.align.*; (b: CellSupportBasis) => SupportAssessment.derive(b)"
        )
      )
      assert(typeChecks("import storymodel4s.align.*; SupportAssessment.externalState"))
    }

    test("only Assessed exposes a number; no numeric comparison spans the three states") {
      assert(typeChecks("import storymodel4s.align.*; (x: SupportAssessment.Assessed) => x.share"))
      Vector(
        "(x: SupportAssessment) => x.share" -> typeCheckErrors(
          "import storymodel4s.align.*; (x: SupportAssessment) => x.share"
        ),
        "(x: SupportAssessment.Unestablished) => x.share" -> typeCheckErrors(
          "import storymodel4s.align.*; (x: SupportAssessment.Unestablished) => x.share"
        ),
        "(x: SupportAssessment.NotApplicable) => x.share" -> typeCheckErrors(
          "import storymodel4s.align.*; (x: SupportAssessment.NotApplicable) => x.share"
        ),
        "(xs: Vector[SupportAssessment]) => xs.map(_.share).max" -> typeCheckErrors(
          "import storymodel4s.align.*; (xs: Vector[SupportAssessment]) => xs.map(_.share).max"
        ),
        "(a: SupportAssessment.Assessed, n: SupportAssessment.NotApplicable) => a.share < n" -> typeCheckErrors(
          "import storymodel4s.align.*; (a: SupportAssessment.Assessed, n: SupportAssessment.NotApplicable) => a.share < n"
        ),
        "summon[Ordering[SupportAssessment]]" -> typeCheckErrors(
          "import storymodel4s.align.*; summon[Ordering[SupportAssessment]]"
        ),
        "(b: CostBreakdown) => b.supportWeight" -> typeCheckErrors(
          "import storymodel4s.align.*; (b: CostBreakdown) => b.supportWeight"
        )
      ).foreach((code, errors) => refused(code, errors))
    }

}
