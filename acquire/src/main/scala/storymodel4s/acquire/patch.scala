package storymodel4s.acquire

import cats.data.NonEmptyVector
import storymodel4s.core.*

/** Placeholder identifier for a node a patch creates; resolved to a real identifier on apply. */
object TempId extends OpaqueId("TempId")
type TempId = TempId.T

/** A node reference inside a patch: either a temporary node created earlier in the same patch or an
  * existing node addressed by its string identifier.
  */
enum NodeRef:
  case Temp(id: TempId)
  case Existing(id: String)

/** Typed patch operations over an abstract target graph with node payload `N` and edge label `E`.
  *
  * Why: an LLM never constructs a trusted graph. It emits these, which are decoded, resolved,
  * validated, and only then applied atomically.
  */
enum PatchOp[+N, +E]:
  case AddNode(temp: TempId, payload: N, evidence: Vector[EvidenceRef])
  case AddEdge(from: NodeRef, label: E, to: NodeRef, evidence: Vector[EvidenceRef])
  case RemoveEdge(from: NodeRef, label: E, to: NodeRef, evidence: Vector[EvidenceRef])
  case SetFocus(node: NodeRef, evidence: Vector[EvidenceRef])
  case MergeMentions(mentions: NonEmptyVector[NodeRef], evidence: Vector[EvidenceRef])
  case ProposeRelation(relation: E, from: NodeRef, to: NodeRef, evidence: Vector[EvidenceRef])
  case SetContext(node: NodeRef, context: NodeRef, evidence: Vector[EvidenceRef])
  case Annotate(target: NodeRef, key: String, value: String, evidence: Vector[EvidenceRef])

  def evidenceRefs: Vector[EvidenceRef] = this match
    case AddNode(_, _, e)            => e
    case AddEdge(_, _, _, e)         => e
    case RemoveEdge(_, _, _, e)      => e
    case SetFocus(_, e)              => e
    case MergeMentions(_, e)         => e
    case ProposeRelation(_, _, _, e) => e
    case SetContext(_, _, e)         => e
    case Annotate(_, _, _, e)        => e

  /** Temporary identifiers this operation introduces. */
  def introduces: Option[TempId] = this match
    case AddNode(t, _, _) => Some(t)
    case _                => None

  /** Node references this operation reads. */
  def references: Vector[NodeRef] = this match
    case AddNode(_, _, _)            => Vector.empty
    case AddEdge(f, _, t, _)         => Vector(f, t)
    case RemoveEdge(f, _, t, _)      => Vector(f, t)
    case SetFocus(n, _)              => Vector(n)
    case MergeMentions(ms, _)        => ms.toVector
    case ProposeRelation(_, f, t, _) => Vector(f, t)
    case SetContext(n, c, _)         => Vector(n, c)
    case Annotate(n, _, _, _)        => Vector(n)

/** An ordered, atomic group of operations with provenance. */
final case class Patch[N, E](id: PatchId, ops: Vector[PatchOp[N, E]], provenance: Provenance):
  def isEmpty: Boolean = ops.isEmpty
  def tempIds: Vector[TempId] = ops.flatMap(_.introduces)

  /** Sequential composition; the right-hand patch's provenance is retained. */
  def ++(other: Patch[N, E]): Patch[N, E] =
    Patch(other.id, ops ++ other.ops, other.provenance)

  /** Temporaries must be introduced once and before use. */
  def wellFormed: Either[PatchError, Patch[N, E]] =
    val seen = scala.collection.mutable.Set.empty[TempId]
    val result = ops.zipWithIndex.foldLeft[Either[PatchError, Unit]](Right(())) {
      case (Left(e), _)         => Left(e)
      case (Right(()), (op, i)) =>
        val unresolved = op.references.collectFirst {
          case NodeRef.Temp(t) if !seen.contains(t) => t
        }
        (op.introduces, unresolved) match
          case (Some(t), _) if seen.contains(t) => Left(PatchError.DuplicateTemp(i, t))
          case (_, Some(t))                     => Left(PatchError.UnresolvedTemp(i, t))
          case (intro, None)                    =>
            intro.foreach(seen += _)
            Right(())
    }
    result.map(_ => this)

object Patch:
  def empty[N, E](id: PatchId, provenance: Provenance): Patch[N, E] =
    Patch(id, Vector.empty, provenance)

/** Why a patch could not be applied. The graph is left untouched in every case. */
enum PatchError:
  case UnresolvedTemp(opIndex: Int, temp: TempId)
  case DuplicateTemp(opIndex: Int, temp: TempId)
  case UnknownNode(opIndex: Int, node: String)
  case InvalidOp(opIndex: Int, reason: String)
  case Conflict(opIndex: Int, reason: String)
  case Invalid(reasons: NonEmptyVector[String])

/** Mapping from temporary identifiers to the identifiers the applier assigned. */
final case class TempResolution(assigned: Map[TempId, String]):
  def resolve(ref: NodeRef): Option[String] = ref match
    case NodeRef.Temp(t)     => assigned.get(t)
    case NodeRef.Existing(i) => Some(i)
  def bind(t: TempId, id: String): TempResolution = TempResolution(assigned.updated(t, id))

object TempResolution:
  val empty: TempResolution = TempResolution(Map.empty)

/** How one operation applies to a graph of type `G`. Implementations must be pure: returning `Left`
  * must not have observable effects, which is what makes [[PatchApplier.applyPatch]] atomic.
  */
trait PatchApplier[G, N, E]:
  def applyOp(
      graph: G,
      opIndex: Int,
      op: PatchOp[N, E],
      resolution: TempResolution
  ): Either[PatchError, (G, TempResolution)]

  /** Optional whole-graph validation after all operations. */
  def validate(graph: G): Either[PatchError, G] = Right(graph)

object PatchApplier:
  /** Atomic application: either every operation succeeds and the result validates, or the original
    * graph is returned unchanged through `Left`.
    */
  def applyPatch[G, N, E](graph: G, patch: Patch[N, E])(using
      applier: PatchApplier[G, N, E]
  ): Either[PatchError, G] =
    patch.wellFormed.flatMap { p =>
      val stepped = p.ops.zipWithIndex.foldLeft[Either[PatchError, (G, TempResolution)]](
        Right((graph, TempResolution.empty))
      ) {
        case (Left(e), _)             => Left(e)
        case (Right((g, r)), (op, i)) => applier.applyOp(g, i, op, r)
      }
      stepped.flatMap((g, _) => applier.validate(g))
    }

  /** Apply patches in sequence, stopping at the first failure. */
  def applyAll[G, N, E](graph: G, patches: Iterable[Patch[N, E]])(using
      PatchApplier[G, N, E]
  ): Either[PatchError, G] =
    patches.foldLeft[Either[PatchError, G]](Right(graph))((acc, p) =>
      acc.flatMap(g => applyPatch(g, p))
    )
