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

  /** The role relates the situation to another **situation**, not to a thing. `:cause` and
    * `:result` take an eventuality, and what they describe belongs to the causal layer, which is
    * empty today. A filler here is not a participant and it is not a circumstance either: it is a
    * claim about how two situations stand to one another, and this provider has no family for that
    * yet. It is counted and receipted under its own name so the slice that builds the causal layer
    * can find every one of them rather than starting from the text again.
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
    * layer refuses. `Cause` and `Result` relate eventualities.
    *
    * `Custom` is refused with one named exception. `amr:domain` is this provider's own role, minted
    * by `ChartProposalProvider.NamedRoles` for the predicative shape `(d / dead :domain (h / he))`,
    * where the filler is the concept the property is predicated of. That filler is a referent — the
    * state holds of it — and the reason `:domain` is a `Custom` at all is that it names no
    * *thematic* role, which is a different question from whether it takes a referent. Refusing it
    * would drop a real cast member to keep a tidy rule. Every other `Custom` role, `amr:purpose`
    * included, is refused: nothing has established what its filler is, and the fail-closed answer
    * is the truthful one.
    */
  def licence(role: ParticipantRole): RoleLicence = role match
    case ParticipantRole.Agent | ParticipantRole.Patient | ParticipantRole.Theme |
        ParticipantRole.Experiencer | ParticipantRole.Stimulus | ParticipantRole.Instrument |
        ParticipantRole.Beneficiary | ParticipantRole.Source | ParticipantRole.Destination |
        ParticipantRole.Location =>
      RoleLicence.Referent
    case ParticipantRole.Time               => RoleLicence.Circumstance(CircumstanceKind.Time)
    case ParticipantRole.Manner             => RoleLicence.Circumstance(CircumstanceKind.Manner)
    case ParticipantRole.Cause              => RoleLicence.Eventuality
    case ParticipantRole.Result             => RoleLicence.Eventuality
    case role if role == PredicationSubject => RoleLicence.Referent
    case ParticipantRole.Custom(_, _)       => RoleLicence.Unestablished

  /** The provider's own `:domain` role: the concept a predicative state is predicated of. Named
    * here rather than spelled inline so the exception has one definition and one place to read
    * about it.
    */
  val PredicationSubject: ParticipantRole = ParticipantRole.Custom("amr", "domain")

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

  /** A role as it is rendered on a receipt and in the rules text. One rendering, so a reader
    * comparing the two is comparing the same string.
    */
  def renderRole(role: ParticipantRole): String = role match
    case ParticipantRole.Custom(namespace, label) => s"Custom($namespace,$label)"
    case other                                    => other.toString

/** Why the referentiality rule turned a filler away, as a closed set.
  *
  * Why typed and why five cases: before this existed, a turned-away filler was a bare increment on
  * one counter, and the audit of 2026-09-02 found 51 of 119 argument fillers leaving the provider
  * that way — in no layer, no gap, and no alternatives list, with nothing recording that they had
  * been seen at all. Each case here needs a different fix by a different slice: a `:cause` filler
  * is work for the causal layer, an unlicensed numbered argument is work for the frame lexicon, a
  * filler reached by two roles is a chart the provider will not guess about. One number could not
  * tell them apart, so no one could act on any of them.
  */
enum FillerRefusal:
  /** The role relates situations; the filler is the causal layer's business. */
  case RoleTakesSituation(role: ParticipantRole)

  /** An extension role whose referentiality nobody has established. Fails closed. */
  case RoleUnestablished(role: ParticipantRole)

  /** The role takes a referent but the concept cannot denote one. */
  case ConceptNotReferential(kind: ConceptKind)

  /** No single normalized participant role reached the filler: a numbered argument the frame
    * lexicon did not license, an operand, or an extension role outside the standard table.
    */
  case NoLicensedRole

  /** Two or more different licensed roles reached the filler. The chart says both, so the provider
    * proposes neither rather than picking one.
    */
  case SeveralLicensedRoles

  def render: String = this match
    case RoleTakesSituation(role) => s"role-takes-situation:${Referentiality.renderRole(role)}"
    case RoleUnestablished(role)  => s"role-unestablished:${Referentiality.renderRole(role)}"
    case ConceptNotReferential(k) => Referentiality.kindReason(k)
    case NoLicensedRole           => "no-licensed-role"
    case SeveralLicensedRoles     => "several-licensed-roles"
