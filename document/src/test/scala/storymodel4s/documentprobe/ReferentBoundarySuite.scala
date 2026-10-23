package storymodel4s.documentprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** External-package proof that ADR 0024's validated referent types have no construction bypass: no
  * `new`, `apply`, `copy`, `fromProduct` or `Mirror.ProductOf`. `IdentityHint` is the same-package
  * case class that licenses nothing, and serves as the positive control.
  */
class ReferentBoundarySuite extends FunSuite:

  // Macro snippets are strings, so these real references make Zinc invalidate the court.
  private val dependsOn: List[Class[?]] = List(
    classOf[storymodel4s.document.ProperName],
    classOf[storymodel4s.document.ReferentMention],
    classOf[storymodel4s.document.ReferentContext],
    classOf[storymodel4s.document.IdentityLink],
    classOf[storymodel4s.document.DistinctLink],
    classOf[storymodel4s.document.ReferentResolution],
    classOf[storymodel4s.document.IdentityHint],
    classOf[storymodel4s.document.PresenceClaim]
  )

  test("controls: the case-class hint exposes every door the guarded types must close") {
    assert(dependsOn.forall(_ != null))
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.IdentityHint]]"
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """storymodel4s.document.IdentityHint.fromProduct(
             (storymodel4s.document.ReferentMentionId.unsafe("a"),
              storymodel4s.document.ReferentMentionId.unsafe("b"),
              storymodel4s.document.ContributoryBasis.AppearanceMatch,
              storymodel4s.document.HintDirection.Toward))"""
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """storymodel4s.document.IdentityHint(
             storymodel4s.document.ReferentMentionId.unsafe("a"),
             storymodel4s.document.ReferentMentionId.unsafe("b"),
             storymodel4s.document.ContributoryBasis.AppearanceMatch,
             storymodel4s.document.HintDirection.Toward).copy()"""
      ),
      Nil
    )
  }

  test("no Mirror.ProductOf for the guarded referent types") {
    val open = List(
      "ProperName" -> typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.ProperName]]"
      ),
      "PresenceClaim" -> typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.PresenceClaim]]"
      ),
      "ReferentMention" -> typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.ReferentMention]]"
      ),
      "ReferentContext" -> typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.ReferentContext]]"
      ),
      "IdentityLink" -> typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.IdentityLink]]"
      ),
      "DistinctLink" -> typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.DistinctLink]]"
      ),
      "ReferentResolution" -> typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.ReferentResolution]]"
      )
    ).collect { case (name, errs) if errs.isEmpty => name }
    assertEquals(open, Nil)
  }

  test("no fromProduct, apply or new from outside the package") {
    val open = List(
      "ProperName" -> typeCheckErrors("storymodel4s.document.ProperName.fromProduct(EmptyTuple)"),
      "PresenceClaim" -> typeCheckErrors(
        "storymodel4s.document.PresenceClaim.fromProduct(EmptyTuple)"
      ),
      "ReferentMention" -> typeCheckErrors(
        "storymodel4s.document.ReferentMention.fromProduct(EmptyTuple)"
      ),
      "ReferentContext" -> typeCheckErrors(
        "storymodel4s.document.ReferentContext.fromProduct(EmptyTuple)"
      ),
      "IdentityLink" -> typeCheckErrors(
        "storymodel4s.document.IdentityLink.fromProduct(EmptyTuple)"
      ),
      "DistinctLink" -> typeCheckErrors(
        "storymodel4s.document.DistinctLink.fromProduct(EmptyTuple)"
      ),
      "ReferentResolution" -> typeCheckErrors(
        "storymodel4s.document.ReferentResolution.fromProduct(EmptyTuple)"
      )
    ).collect { case (name, errs) if errs.isEmpty => name }
    assertEquals(open, Nil)
    // Each `new` probe has the constructor's exact shape and must fail on access alone.
    def inaccessible(errors: List[scala.compiletime.testing.Error]): Boolean =
      errors.nonEmpty && errors.forall(_.message.contains("cannot be accessed"))
    val newDoors = List(
      "ProperName" -> typeCheckErrors("""new storymodel4s.document.ProperName("x")"""),
      "PresenceClaim" -> typeCheckErrors(
        """new storymodel4s.document.PresenceClaim(
             null: storymodel4s.core.EvidenceAnchor.MediaTime,
             null: storymodel4s.document.PresenceBasis)"""
      ),
      "IdentityLink" -> typeCheckErrors(
        """new storymodel4s.document.IdentityLink(
             null: storymodel4s.document.ReferentContext,
             null: storymodel4s.document.ReferentMention,
             null: storymodel4s.document.ReferentMention,
             storymodel4s.document.IdentityBasis.DeicticReference)"""
      ),
      "DistinctLink" -> typeCheckErrors(
        """new storymodel4s.document.DistinctLink(
             null: storymodel4s.document.ReferentContext,
             null: storymodel4s.document.ReferentMention,
             null: storymodel4s.document.ReferentMention,
             storymodel4s.document.DistinctnessBasis.SimultaneousTracks)"""
      ),
      "ReferentResolution" -> typeCheckErrors(
        """new storymodel4s.document.ReferentResolution(
             null: storymodel4s.document.ReferentContext,
             Vector.empty[cats.data.NonEmptyVector[storymodel4s.document.ReferentMentionId]],
             Set.empty[Int],
             Set.empty[(storymodel4s.document.ReferentMentionId, storymodel4s.document.ReferentMentionId)],
             Vector.empty[storymodel4s.document.IdentityHint])"""
      )
    )
    assertEquals(
      newDoors.collect { case (n, errs) if !inaccessible(errs) => n -> errs.map(_.message) },
      Nil
    )
    assert(typeCheckErrors("""storymodel4s.document.ProperName("x")""").nonEmpty)
  }

  test("control: a public constructor of the same shape compiles with new") {
    assertEquals(
      typeCheckErrors(
        """new storymodel4s.document.IdentityHint(
             storymodel4s.document.ReferentMentionId.unsafe("a"),
             storymodel4s.document.ReferentMentionId.unsafe("b"),
             storymodel4s.document.ContributoryBasis.AppearanceMatch,
             storymodel4s.document.HintDirection.Toward)"""
      ),
      Nil
    )
  }

  test("no copy on a licensed link") {
    assert(
      typeCheckErrors(
        """(null: storymodel4s.document.IdentityLink).copy(
             basis = storymodel4s.document.IdentityBasis.DeicticReference)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(null: storymodel4s.document.DistinctLink).copy(
             basis = storymodel4s.document.DistinctnessBasis.SimultaneousTracks)"""
      ).nonEmpty
    )
  }
