package storymodel4s.documentprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** External-package proof that ADR 0026's checked types have no construction bypass: no `new`,
  * `apply`, `copy`, `fromProduct` or `Mirror.ProductOf`. `IdentityHint`, a public case class of the
  * same package, is the positive control (see `ReferentBoundarySuite`).
  */
class PromotionBoundarySuite extends FunSuite:

  // Macro snippets are strings, so these real references make Zinc invalidate the court.
  private val dependsOn: List[Class[?]] = List(
    classOf[storymodel4s.document.SpeechPromotion],
    classOf[storymodel4s.document.Contestation],
    classOf[storymodel4s.document.IdentityHint]
  )

  test("control: the case-class hint exposes the doors the guarded types must close") {
    assert(dependsOn.forall(_ != null))
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.IdentityHint]]"
      ),
      Nil
    )
  }

  test("no Mirror.ProductOf, fromProduct or apply for a promotion or a contestation") {
    val open = List(
      "SpeechPromotion mirror" -> typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.SpeechPromotion]]"
      ),
      "Contestation mirror" -> typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.Contestation]]"
      ),
      "SpeechPromotion fromProduct" -> typeCheckErrors(
        "storymodel4s.document.SpeechPromotion.fromProduct(EmptyTuple)"
      ),
      "Contestation fromProduct" -> typeCheckErrors(
        "storymodel4s.document.Contestation.fromProduct(EmptyTuple)"
      ),
      "Contestation apply" -> typeCheckErrors(
        """storymodel4s.document.Contestation(
             storymodel4s.core.SituationId.unsafe("s"),
             storymodel4s.document.ContestationBasis.Deception,
             null: storymodel4s.core.ClaimMeta)"""
      )
    ).collect { case (name, errs) if errs.isEmpty => name }
    assertEquals(open, Nil)
  }

  test("no new from outside the package") {
    // Each probe has the constructor's exact shape and must fail on access alone.
    def inaccessible(errors: List[scala.compiletime.testing.Error]): Boolean =
      errors.nonEmpty && errors.forall(_.message.contains("cannot be accessed"))
    val doors = List(
      "Contestation" -> typeCheckErrors(
        """new storymodel4s.document.Contestation(
             storymodel4s.core.SituationId.unsafe("s"),
             storymodel4s.document.ContestationBasis.Deception,
             null: storymodel4s.core.ClaimMeta)"""
      ),
      "SpeechPromotion" -> typeCheckErrors(
        """new storymodel4s.document.SpeechPromotion(
             storymodel4s.core.StoryId.unsafe("s"),
             storymodel4s.core.Checksum.ofText("s"),
             storymodel4s.core.SituationId.unsafe("s"),
             storymodel4s.core.ContextId.unsafe("c"),
             storymodel4s.story.Predicate("dead", None, "dead"),
             storymodel4s.story.Polarity.Positive,
             Vector.empty[(storymodel4s.story.ParticipantRole, storymodel4s.core.EntityId,
               storymodel4s.document.ReferentMentionId)],
             storymodel4s.core.Checksum.ofText("r"),
             storymodel4s.core.Checksum.ofText("c"),
             null: storymodel4s.core.ClaimMeta)"""
      )
    )
    assertEquals(
      doors.collect { case (n, errs) if !inaccessible(errs) => n -> errs.map(_.message) },
      Nil
    )
  }

  test("no copy on a promotion or a contestation") {
    assert(
      typeCheckErrors(
        """(null: storymodel4s.document.SpeechPromotion).copy(
             polarity = storymodel4s.story.Polarity.Negative)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(null: storymodel4s.document.Contestation).copy(
             basis = storymodel4s.document.ContestationBasis.Retraction)"""
      ).nonEmpty
    )
  }
