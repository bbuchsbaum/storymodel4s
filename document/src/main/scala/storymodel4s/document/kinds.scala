package storymodel4s.document

import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}
import storymodel4s.proposition.ConceptKind

/** Runtime evidence for a narrative kind: its content-address tag and which chart concepts may
  * stand as mentions of it.
  *
  * Why: `MentionId[K]` is a phantom-typed string, so `MentionId.unsafe[EntityK]("m7")` and
  * `MentionId.unsafe[SituationK]("m7")` are the same runtime value. The witness is what lets the
  * document layer check, against the actual chart concept, that a mention really is of kind `K`.
  */
sealed trait KindWitness[K <: NarrativeKind]:
  /** Stable tag used in content addresses (`c-entity:…`, `c-situation:…`). */
  def tag: String

  /** Whether a chart concept of this kind may be a mention of `K`. */
  def accepts(kind: ConceptKind): Boolean

object KindWitness:
  def apply[K <: NarrativeKind](using w: KindWitness[K]): KindWitness[K] = w

  given entity: KindWitness[EntityK] with
    val tag: String = "entity"
    def accepts(kind: ConceptKind): Boolean = kind match
      case ConceptKind.Entity | ConceptKind.Name | ConceptKind.Quantity => true
      case _                                                            => false

  /** `Special` covers AMR `-91` rolesets (`be-located-at-91`, `have-org-role-91`), which are
    * predicates; `Unknown` is never accepted as a situation mention.
    */
  given situation: KindWitness[SituationK] with
    val tag: String = "situation"
    def accepts(kind: ConceptKind): Boolean = kind match
      case ConceptKind.Predicate | ConceptKind.Property | ConceptKind.Special => true
      case _                                                                  => false

/** Typed errors of document composition. */
enum DocumentError:
  case UnknownMention(mention: String, path: String)
  case MentionNotInGraph(mention: String, node: ChartNodeRef, path: String)
  case KindMismatch(mention: String, node: ChartNodeRef, found: ConceptKind, expected: String)
  case DuplicateMention(mention: String, path: String)
  case DuplicateCanonical(canonical: String, path: String)
  case CanonicalMismatch(given_ : String, expected: String, path: String)
  case ModeNotAllowed(mode: ProjectionMode, kind: String, path: String)
  case SourceKindMismatch(node: ChartNodeRef, found: ConceptKind, expected: String, path: String)

  def message: String = this match
    case UnknownMention(m, p)           => s"$p: mention $m is not in the mention table"
    case MentionNotInGraph(m, n, p)     => s"$p: mention $m refers to ${n.key}, absent from graph"
    case KindMismatch(m, n, f, e)       => s"mention $m at ${n.key} is $f, not a $e concept"
    case DuplicateMention(m, p)         => s"$p: mention $m appears more than once"
    case DuplicateCanonical(c, p)       => s"$p: canonical $c named by more than one cluster"
    case CanonicalMismatch(g, e, p)     => s"$p: canonical id $g does not match content address $e"
    case ModeNotAllowed(m, k, p)        => s"$p: projection mode $m is not allowed for $k targets"
    case SourceKindMismatch(n, f, e, p) => s"$p: direct-mention source ${n.key} is $f, not $e"

/** Mentions of kind `K` tied to the chart nodes they occupy in the mention graph.
  *
  * Why: a coreference partition ranges over mentions, while charts hold concepts; without this
  * table nothing connects the two and kind safety cannot be checked. Validation requires every node
  * to exist in the graph and to carry a concept the kind witness accepts.
  */
final case class MentionTable[K <: NarrativeKind] private (
    entries: Map[MentionId[K], ChartNodeRef]
):
  def node(m: MentionId[K]): Option[ChartNodeRef] = entries.get(m)
  def contains(m: MentionId[K]): Boolean = entries.contains(m)
  def mentions: Vector[MentionId[K]] = entries.keys.toVector.sorted
  def size: Int = entries.size

object MentionTable:
  def empty[K <: NarrativeKind]: MentionTable[K] = MentionTable(Map.empty)

  /** Build and validate against the mention graph. */
  def of[K <: NarrativeKind](
      entries: Iterable[(MentionId[K], ChartNodeRef)],
      graph: MentionGraph
  )(using w: KindWitness[K]): Either[DocumentError, MentionTable[K]] =
    val es = entries.toVector
    val dup = es.map(_._1).groupBy(identity).collectFirst { case (m, ms) if ms.size > 1 => m }
    dup match
      case Some(m) => Left(DocumentError.DuplicateMention(m.value, "MentionTable"))
      case None    =>
        es.sortBy(_._1)
          .foldLeft[Either[DocumentError, Map[MentionId[K], ChartNodeRef]]](Right(Map.empty)) {
            case (Left(e), _)            => Left(e)
            case (Right(acc), (m, node)) =>
              graph.concept(node) match
                case None    => Left(DocumentError.MentionNotInGraph(m.value, node, "MentionTable"))
                case Some(c) =>
                  if w.accepts(c.kind) then Right(acc.updated(m, node))
                  else Left(DocumentError.KindMismatch(m.value, node, c.kind, w.tag))
          }
          .map(MentionTable(_))
