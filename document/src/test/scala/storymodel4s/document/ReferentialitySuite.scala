package storymodel4s.document

import munit.FunSuite
import scala.deriving.Mirror
import storymodel4s.proposition.{ConceptKind, ParticipantRole}
import storymodel4s.story.CircumstanceKind

/** Court for the closed referentiality rule.
  *
  * Why these and not more: the rule is a total function of two small enums, so the courts that
  * matter are the ones a future edit can break silently — a role added to `ParticipantRole` and
  * forgotten in the rule's own list, and a case quietly moved from one licence to another.
  */
class ReferentialitySuite extends FunSuite:

  /** The enum's own case labels, from its mirror rather than from a list beside it. `Custom` is the
    * one parameterized case and names no single value, so it is excluded here and licensed by its
    * own clause.
    */
  private val mirrorLabels: Vector[String] =
    val mirror = summon[Mirror.SumOf[ParticipantRole]]
    scala.compiletime
      .constValueTuple[mirror.MirroredElemLabels]
      .productIterator
      .map(_.toString)
      .toVector

  test("the rule's closed role list is exactly the enum, less Custom") {
    assertEquals(
      Referentiality.closedRoles.map(_.toString).sorted,
      (mirrorLabels.toSet - "Custom").toVector.sorted
    )
  }

  test("only roles that take a referent are referential") {
    assertEquals(
      Referentiality.referentialRoles.map(_.toString).sorted,
      Vector(
        "Agent",
        "Beneficiary",
        "Destination",
        "Experiencer",
        "Instrument",
        "Location",
        "Patient",
        "Source",
        "Stimulus",
        "Theme"
      )
    )
  }

  test("a time role licenses a circumstance and never a referent") {
    assertEquals(
      Referentiality.licence(ParticipantRole.Time),
      RoleLicence.Circumstance(CircumstanceKind.Time)
    )
    assertEquals(
      Referentiality.licence(ParticipantRole.Manner),
      RoleLicence.Circumstance(CircumstanceKind.Manner)
    )
  }

  test("a role relating eventualities licenses neither a referent nor a circumstance") {
    assertEquals(Referentiality.licence(ParticipantRole.Cause), RoleLicence.Eventuality)
    assertEquals(Referentiality.licence(ParticipantRole.Result), RoleLicence.Eventuality)
  }

  test("an extension role is unestablished, but the provider's own :domain takes a referent") {
    assertEquals(
      Referentiality.licence(ParticipantRole.Custom("amr", "purpose")),
      RoleLicence.Unestablished
    )
    assertEquals(
      Referentiality.licence(ParticipantRole.Custom("someone-else", "domain")),
      RoleLicence.Unestablished
    )
    assertEquals(Referentiality.licence(Referentiality.PredicationSubject), RoleLicence.Referent)
  }

  test("only Entity and Name concepts denote a referent") {
    val denoting = Vector(
      ConceptKind.Predicate,
      ConceptKind.Entity,
      ConceptKind.Property,
      ConceptKind.Quantity,
      ConceptKind.Name,
      ConceptKind.Special,
      ConceptKind.Unknown
    ).filter(Referentiality.denotesReferent)
    assertEquals(denoting, Vector(ConceptKind.Entity, ConceptKind.Name))
  }
