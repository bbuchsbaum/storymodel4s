package storymodel4s.document

import storymodel4s.proposition.{ConceptKind, ParticipantRole}
import storymodel4s.story.CircumstanceKind

/** What a participant role licenses about the concept standing in it.
  *
  * Why a closed enum and not a predicate: "this filler is not a participant" has more than one
  * truth, and a `Boolean` would publish them as one. A time is evidence the model can hold in a
  * place built for it; a filler under a role that relates two eventualities is something the entity
  * layer cannot represent at all; an extension role is one whose referentiality nobody has
  * established. Those need different outcomes and different receipts, so they get different cases.
  */
enum RoleLicence:
  /** The role takes a referent, so an entity mention and a participant edge are licensed. */
  case Referent

  /** The role names a circumstance of the situation rather than a participant in it. */
  case Circumstance(kind: CircumstanceKind)

  /** The role relates the situation to an eventuality. A filler standing here is a proposition or
    * an event, and an entity-kind concept under it is a shape this provider does not read.
    */
  case Eventuality

  /** An extension role outside the project's closed vocabulary. Nothing establishes whether it
    * takes a referent, so nothing licenses one: the fail-closed answer, not the convenient one.
    */
  case Unestablished

  def render: String = this match
    case Referent        => "referent"
    case Circumstance(k) => s"circumstance:${k.render}"
    case Eventuality     => "eventuality"
    case Unestablished   => "unestablished"

/** Whether a chart filler is a referent of its situation, decided by the role and the concept
  * together.
  *
  * Why both coordinates and not one: the role alone admitted `then`, `now`, `midnight`, `thus` and
  * `together` as participants and minted an entity for each, because `Time` and `Manner` normalize
  * to roles like any other; the concept kind alone would admit a quantity, which measures a
  * referent without being one. A referent needs a role that takes one *and* a concept that can
  * denote one, and this object is the only place that conjunction is stated.
  *
  * Why here and not inline in the provider: the rule is named on every receipt and written into
  * `ChartProposalProvider.RulesText`, so it must be one object a reader can go and check against
  * the prose.
  */
object Referentiality:
  /** Rule name recorded on the receipts of every filler this rule turned away. */
  val RuleName: String = "role-referentiality-rule"

  /** Every case of the closed role vocabulary except `Custom`, which is parameterized and so names
    * no single value.
    *
    * Written out because Scala 3 gives no `values` array to an enum carrying a parameterized case.
    * `ReferentialitySuite` proves this list complete against the enum's own mirror, so a role added
    * to `ParticipantRole` and forgotten here fails a test rather than quietly dropping out of the
    * rule text.
    */
  val closedRoles: Vector[ParticipantRole] = Vector(
    ParticipantRole.Agent,
    ParticipantRole.Patient,
    ParticipantRole.Theme,
    ParticipantRole.Experiencer,
    ParticipantRole.Stimulus,
    ParticipantRole.Instrument,
    ParticipantRole.Beneficiary,
    ParticipantRole.Source,
    ParticipantRole.Destination,
    ParticipantRole.Location,
    ParticipantRole.Time,
    ParticipantRole.Manner,
    ParticipantRole.Cause,
    ParticipantRole.Result
  )

  /** The roles that take a referent, derived from [[licence]] rather than listed beside it: a rule
    * text a reader could find disagreeing with the code is not a rule.
    */
  val referentialRoles: Vector[ParticipantRole] =
    closedRoles.filter(role => licence(role) == RoleLicence.Referent)

  /** The licence a role carries, total over [[ParticipantRole]].
    *
    * The referential set is every role of the closed vocabulary that names a thing taking part in
    * the situation: agent, patient, theme, experiencer, stimulus, instrument, beneficiary, source,
    * destination, and location. `Location` is on it because a place is a referent — a canoe, a
    * shore, a named village — and no chart signal distinguishes "a place" from "an entity used as
    * one"; asserting that distinction from a word list would be exactly the world knowledge this
    * layer refuses. `Cause` and `Result` relate eventualities. `Custom` is refused whatever its
    * namespace, including this provider's own `amr:domain`, which says which concept a property is
    * predicated of and therefore describes rather than participates.
    */
  def licence(role: ParticipantRole): RoleLicence = role match
    case ParticipantRole.Agent | ParticipantRole.Patient | ParticipantRole.Theme |
        ParticipantRole.Experiencer | ParticipantRole.Stimulus | ParticipantRole.Instrument |
        ParticipantRole.Beneficiary | ParticipantRole.Source | ParticipantRole.Destination |
        ParticipantRole.Location =>
      RoleLicence.Referent
    case ParticipantRole.Time         => RoleLicence.Circumstance(CircumstanceKind.Time)
    case ParticipantRole.Manner       => RoleLicence.Circumstance(CircumstanceKind.Manner)
    case ParticipantRole.Cause        => RoleLicence.Eventuality
    case ParticipantRole.Result       => RoleLicence.Eventuality
    case ParticipantRole.Custom(_, _) => RoleLicence.Unestablished

  /** Whether a concept of this kind can denote a referent at all.
    *
    * `Entity` and `Name` can. `Quantity` cannot: a quantity measures a referent and is an attribute
    * of one, so admitting it would put the measure in the cast. `Property`, `Predicate`, `Special`
    * and `Unknown` cannot either, and the first of those is how `:domain` and `:mod` fillers are
    * already kept out of the entity layer by the chart adapter.
    */
  def denotesReferent(kind: ConceptKind): Boolean = kind match
    case ConceptKind.Entity | ConceptKind.Name => true
    case ConceptKind.Quantity | ConceptKind.Property | ConceptKind.Predicate | ConceptKind.Special |
        ConceptKind.Unknown =>
      false

  /** Why a concept kind was refused a referent reading, for the receipt. */
  def kindReason(kind: ConceptKind): String = s"concept-kind-not-referential:$kind"
