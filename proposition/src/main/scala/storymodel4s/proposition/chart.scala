package storymodel4s.proposition

import cats.Order
import cats.data.NonEmptySet
import storymodel4s.core.*

/** Chart-local concept identifier. Only meaningful inside one chart; never content-addressed. */
object ConceptId extends OpaqueId("ConceptId")
type ConceptId = ConceptId.T

/** A normalized lexical form (`strike`, `young-man`). Whitespace-free, nonempty. */
object Lemma extends OpaqueId("Lemma")
type Lemma = Lemma.T

/** Reference to an external frame inventory entry (e.g. PropBank `strike-01`).
  *
  * Why optional everywhere: a chart with a bare lemma and no frame is a legitimate, usable chart.
  * Frame sense is weak evidence (design record §102); `senseCredence` records how much to trust the
  * sense choice itself, separately from any claim about the proposition.
  */
final case class FrameRef(namespace: String, id: String, senseCredence: Option[Credence]):
  /** Identity of the frame ignoring how confident the assignment was. */
  def key: (String, String) = (namespace, id)

enum ConceptKind:
  case Predicate, Entity, Property, Quantity, Name, Special, Unknown

/** A node of a chart: a lexical concept with optional frame reference. */
final case class Concept(
    lemma: Lemma,
    gloss: Option[String],
    frame: Option[FrameRef],
    kind: ConceptKind
):
  def isPredicate: Boolean = kind == ConceptKind.Predicate
  def isUnknown: Boolean = kind == ConceptKind.Unknown

object Concept:
  private val UnknownLemma: Lemma = Lemma.unsafe("?")

  private given Order[Credence] =
    Order.by(c => (c.rawScore, c.calibrated, c.calibrationModel))
  private given Order[FrameRef] = Order.by(f => (f.namespace, f.id, f.senseCredence))
  private given Order[ConceptKind] = Order.by(_.ordinal)

  /** Lawful total order consistent with `==` (all fields, in declaration order). */
  given Order[Concept] = Order.by(c => (c.kind, c.lemma, c.frame, c.gloss))

  /** Explicitly underspecified concept: the extractor knows a participant exists but not what. */
  val unknown: Concept = Concept(UnknownLemma, None, None, ConceptKind.Unknown)

  def predicate(lemma: String, frame: Option[FrameRef] = None): Concept =
    Concept(Lemma.unsafe(lemma), None, frame, ConceptKind.Predicate)
  def entity(lemma: String): Concept = Concept(Lemma.unsafe(lemma), None, None, ConceptKind.Entity)
  def property(lemma: String): Concept =
    Concept(Lemma.unsafe(lemma), None, None, ConceptKind.Property)
  def name(lemma: String): Concept = Concept(Lemma.unsafe(lemma), None, None, ConceptKind.Name)

/** Stable, project-owned semantic role vocabulary shared by every layer above `proposition`.
  *
  * Why here: story participant edges, recall sketches, and interview detail atoms all need the same
  * closed set, and none of them may depend on an external frame inventory to name a role.
  */
enum ParticipantRole:
  case Agent, Patient, Theme, Experiencer, Stimulus, Instrument, Beneficiary, Source, Destination,
    Location, Time, Manner, Cause, Result
  case Custom(namespace: String, label: String)

/** The role exactly as the source representation expressed it. */
enum SourceRole:
  /** `ARG0`..`ARG9`; meaning is frame-specific and never implies a normalized role by itself. */
  case Numbered(index: Int)

  /** A named role in its *bare* spelling: `location`, `time`, `mod`, `polarity`, ... — never with a
    * leading `:` (that is PENMAN concrete syntax, not the role). Names shaped like `ARGn` must use
    * [[Numbered]]; the validator rejects both mistakes.
    */
  case Named(name: String)

  /** `op1`, `op2`, ... */
  case Operand(index: Int)

  /** Provider- or project-specific role outside the standard inventory. */
  case Extension(namespace: String, name: String)

  def render: String = this match
    case Numbered(i)      => s"ARG$i"
    case Named(n)         => n
    case Operand(i)       => s"op$i"
    case Extension(ns, n) => s"$ns:$n"

object SourceRole:
  val MaxNumbered = 9

  /** A role name that is really a numbered argument written as text. */
  private val NumberedShape = "(?i)arg([0-9]+)".r

  def numbered(index: Int): Either[DomainError, SourceRole] =
    if index < 0 || index > MaxNumbered then
      Left(
        DomainError
          .InvalidFormat("SourceRole", index.toString, s"ARG index outside 0..$MaxNumbered")
      )
    else Right(Numbered(index))

  /** Why a named role is unacceptable, if it is. */
  def nameProblem(name: String): Option[String] =
    if name.isEmpty then Some("empty role name")
    else if name.startsWith(":") then Some("role name must be bare (no leading ':')")
    else if name.exists(c => c.isWhitespace || c == '|' || c == '<' || c == '>') then
      Some("role name contains whitespace or a reserved separator")
    else None

  /** The numbered index a name like `ARG0` denotes, if it has that shape. */
  def numberedShape(name: String): Option[Int] = name match
    case NumberedShape(digits) => digits.toIntOption
    case _                     => None

  /** A validated bare named role. */
  def named(name: String): Either[DomainError, SourceRole] =
    nameProblem(name) match
      case Some(why) => Left(DomainError.InvalidFormat("SourceRole", name, why))
      case None      =>
        if numberedShape(name).nonEmpty then
          Left(DomainError.InvalidFormat("SourceRole", name, "numbered roles must use Numbered"))
        else Right(Named(name))

  /** Lawful total order consistent with `==`: by constructor, then by fields. */
  given Order[SourceRole] = Order.by {
    case Numbered(i)      => (0, i, "", "")
    case Named(n)         => (1, 0, n, "")
    case Operand(i)       => (2, i, "", "")
    case Extension(ns, n) => (3, 0, ns, n)
  }

/** A source role plus an optional normalized interpretation carrying its own credence.
  *
  * Law: a numbered role on a concept with no frame must not carry a normalized role — there is
  * nothing to license the interpretation (design record §102).
  */
final case class RoleAssignment(
    source: SourceRole,
    normalized: Option[(ParticipantRole, Credence)]
):
  def normalizedRole: Option[ParticipantRole] = normalized.map(_._1)

object RoleAssignment:
  def apply(source: SourceRole): RoleAssignment = RoleAssignment(source, None)
  def arg(index: Int): RoleAssignment = RoleAssignment(SourceRole.Numbered(index), None)
  def named(name: String): RoleAssignment = RoleAssignment(SourceRole.Named(name), None)

enum LiteralValue:
  case Text(value: String)
  case Number(value: BigDecimal)
  case Symbol(value: String)

  /** Rendering used by glosses and serializations; numbers use [[LiteralValue.canonicalNumber]] so
    * that `==` (numeric, scale-insensitive) and the rendered form agree.
    */
  def render: String = this match
    case Text(v)   => "\"" + v + "\""
    case Number(v) => LiteralValue.canonicalNumber(v)
    case Symbol(v) => v

object LiteralValue:
  /** Scale-normalized plain decimal: `1E+3`, `1000.0`, and `1000` all render as `1000`. */
  def canonicalNumber(v: BigDecimal): String =
    val stripped = v.bigDecimal.stripTrailingZeros
    if stripped.signum == 0 then "0" else stripped.toPlainString

enum ConceptTarget:
  case Node(id: ConceptId)
  case Literal(value: LiteralValue)

  /** The relation exists but its filler is unknown or unrecoverable. */
  case Unknown

  def nodeId: Option[ConceptId] = this match
    case Node(id) => Some(id)
    case _        => None

/** One directed labeled relation. Reentrancy is a concept that is the target of several relations.
  */
final case class PropositionRelation(from: ConceptId, role: RoleAssignment, to: ConceptTarget)

enum Polarity:
  case Positive, Negative, Unknown

/** How an embedded proposition is held by its container (said, believed, wanted, ...). */
enum EmbeddingKind:
  case Speech, Belief, Desire, Intention, Hypothetical, Counterfactual, Memory, Imagination, Unknown

/** `content` is not asserted at chart root; it is held under `container` with `kind`. */
final case class EmbeddedProposition(container: ConceptId, kind: EmbeddingKind, content: ConceptId)

/** What an alignment entry points at. Relation references are structural so they survive renaming.
  */
enum AlignmentTarget:
  case Concepts(ids: NonEmptySet[ConceptId])
  case Relation(relation: PropositionRelation)

  def conceptIds: Set[ConceptId] = this match
    case Concepts(ids) => ids.toSortedSet.toSet
    case Relation(r)   => Set(r.from) ++ r.to.nodeId

/** Exact text support for part of a chart. One span may support many concepts; a concept may have
  * no span (abstract or implied). Spans may be discontinuous.
  */
final case class PropositionAlignment(
    target: AlignmentTarget,
    spans: SpanSet,
    credence: Credence,
    meta: ClaimMeta
)

enum ChartOrigin:
  case Hand
  case Parser(fingerprint: Fingerprint)
  case Agent(fingerprint: Fingerprint)
  case Converted(from: String, fingerprint: Fingerprint)

  /** Selected by the deterministic resolver from several candidates. */
  case Resolved

/** Where a chart came from and which rival charts were considered. */
final case class ChartProvenance(
    origin: ChartOrigin,
    receipts: Vector[ProviderCall],
    alternatives: Vector[(Checksum, Credence)]
)

object ChartProvenance:
  val hand: ChartProvenance = ChartProvenance(ChartOrigin.Hand, Vector.empty, Vector.empty)

/** Phantom check state of a chart. */
sealed trait CheckState
object CheckState:
  sealed trait Unchecked extends CheckState
  sealed trait Checked extends CheckState
type Unchecked = CheckState.Unchecked
type Checked = CheckState.Checked

/** A partial, evidence-backed local semantic chart.
  *
  * This is the canonical local semantic contract of storymodel4s (design record §99.1). It is
  * deliberately smaller than AMR: no variables, no inverse roles, no reification, frames optional,
  * unknowns explicit. AMR graphs, parser outputs, and agent proposals are all *decoded into* it.
  *
  * Reentrancy is representational only: a concept referenced by several relations. Polarity is
  * recorded per concept (normally predicates). Embedded propositions are held, not asserted.
  */
final case class PropositionChart[C <: CheckState] private[proposition] (
    focus: Option[ConceptId],
    concepts: Map[ConceptId, Concept],
    relations: Vector[PropositionRelation],
    polarity: Map[ConceptId, Polarity],
    embedded: Vector[EmbeddedProposition],
    alignments: Vector[PropositionAlignment],
    provenance: ChartProvenance,
    sentence: Option[SurfaceUnitId]
):
  def isEmpty: Boolean = concepts.isEmpty
  def conceptIds: Vector[ConceptId] = concepts.keys.toVector.sorted
  def concept(id: ConceptId): Option[Concept] = concepts.get(id)
  def polarityOf(id: ConceptId): Polarity = polarity.getOrElse(id, Polarity.Unknown)

  def relationsFrom(id: ConceptId): Vector[PropositionRelation] = relations.filter(_.from == id)
  def relationsTo(id: ConceptId): Vector[PropositionRelation] =
    relations.filter(_.to.nodeId.contains(id))

  /** Number of relations whose target is `id`; > 1 means reentrant. */
  def referenceCount(id: ConceptId): Int = relationsTo(id).size
  def isReentrant(id: ConceptId): Boolean = referenceCount(id) > 1

  def predicates: Vector[ConceptId] =
    conceptIds.filter(id => concepts(id).isPredicate)

  /** Every embedding holding `id` as content (a reentrant proposition may be held several ways,
    * e.g. both said and believed). Deterministic order.
    */
  def embeddingsOf(id: ConceptId): Vector[EmbeddedProposition] =
    embedded.filter(_.content == id).sortBy(e => (e.kind.ordinal, e.container.value))

  /** The kinds under which `id` is held; empty when asserted at root. */
  def embeddingKinds(id: ConceptId): Set[EmbeddingKind] = embeddingsOf(id).map(_.kind).toSet

  /** The single embedding of `id` when there is exactly one; `None` if absent *or* ambiguous. Use
    * [[embeddingsOf]] when several holders are possible.
    */
  def embeddingOf(id: ConceptId): Option[EmbeddedProposition] =
    embeddingsOf(id) match
      case Vector(one) => Some(one)
      case _           => None
  def isEmbedded(id: ConceptId): Boolean = embedded.exists(_.content == id)

  /** Concepts with no alignment support at all. */
  def unalignedConcepts: Vector[ConceptId] =
    val aligned = alignments.flatMap(_.target.conceptIds).toSet
    conceptIds.filterNot(aligned)

  /** Forget the check state (e.g. before editing). */
  def unchecked: PropositionChart[Unchecked] =
    new PropositionChart[Unchecked](
      focus,
      concepts,
      relations,
      polarity,
      embedded,
      alignments,
      provenance,
      sentence
    )

  /** Same chart with relations and embeddings in a different order (identity-preserving). */
  private[proposition] def reordered(
      relations: Vector[PropositionRelation],
      embedded: Vector[EmbeddedProposition]
  ): PropositionChart[C] =
    new PropositionChart[C](
      focus,
      concepts,
      relations,
      polarity,
      embedded,
      alignments,
      provenance,
      sentence
    )

  private[proposition] def relabel(f: ConceptId => ConceptId): PropositionChart[C] =
    def tgt(t: ConceptTarget): ConceptTarget = t match
      case ConceptTarget.Node(id) => ConceptTarget.Node(f(id))
      case other                  => other
    def rel(r: PropositionRelation): PropositionRelation = r.copy(from = f(r.from), to = tgt(r.to))
    new PropositionChart[C](
      focus.map(f),
      concepts.map((k, v) => f(k) -> v),
      relations.map(rel),
      polarity.map((k, v) => f(k) -> v),
      embedded.map(e => e.copy(container = f(e.container), content = f(e.content))),
      alignments.map { a =>
        a.copy(target = a.target match
          case AlignmentTarget.Concepts(ids) => AlignmentTarget.Concepts(ids.map(f))
          case AlignmentTarget.Relation(r)   => AlignmentTarget.Relation(rel(r)))
      },
      provenance,
      sentence
    )

object PropositionChart:
  /** Build an unchecked chart; run [[ChartValidator.validate]] to obtain a checked one. */
  def unchecked(
      focus: Option[ConceptId],
      concepts: Map[ConceptId, Concept],
      relations: Vector[PropositionRelation],
      polarity: Map[ConceptId, Polarity] = Map.empty,
      embedded: Vector[EmbeddedProposition] = Vector.empty,
      alignments: Vector[PropositionAlignment] = Vector.empty,
      provenance: ChartProvenance = ChartProvenance.hand,
      sentence: Option[SurfaceUnitId] = None
  ): PropositionChart[Unchecked] =
    new PropositionChart[Unchecked](
      focus,
      concepts,
      relations,
      polarity,
      embedded,
      alignments,
      provenance,
      sentence
    )

  val empty: PropositionChart[Unchecked] = unchecked(None, Map.empty, Vector.empty)
