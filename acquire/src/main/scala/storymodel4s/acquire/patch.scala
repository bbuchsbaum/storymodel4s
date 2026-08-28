package storymodel4s.acquire

import cats.data.NonEmptyVector
import storymodel4s.core.*

/** Placeholder identifier for a node a patch creates; resolved to a real identifier on apply. */
object TempId extends OpaqueId("TempId")
type TempId = TempId.T

/** A node reference inside a patch: either a temporary node created earlier in the same patch or an
  * existing node addressed by its string identifier.
  *
  * `Existing` is deliberately string-backed: one patch vocabulary must address chart concepts,
  * story situations, entities, and segments, all of whose opaque identifiers are string-backed. The
  * applier for a concrete graph converts and rejects unknown identifiers with
  * [[PatchError.UnknownNode]]; no consumer may interpret the string beyond that.
  */
enum NodeRef:
  case Temp(id: TempId)
  case Existing(id: String)

/** Closed vocabulary of annotation keys an agent may attach to a node. Open ontologies use
  * `Custom(namespace, name)`; agents cannot invent free-form keys (contract 2).
  */
enum AnnotationKey:
  case Gloss
  case Note
  case ReviewFlag
  case AlternativeAnalysis
  case Custom(namespace: String, name: String)

  def render: String = this match
    case Custom(ns, n) => s"$ns:$n"
    case other         => other.toString

/** Typed patch operations over an abstract target graph with node payload `N` and edge label `E`.
  *
  * Why: an LLM never constructs a trusted graph. It emits these, which are decoded, resolved,
  * validated, and only then applied atomically.
  *
  * Identity-changing operations (`MergeMentions`, `SplitMention`) carry `supersedes`: the claim the
  * merge or split replaces, so the ledger keeps the machine record (roadmap §3, Part 2 item 10).
  */
enum PatchOp[+N, +E]:
  case AddNode(temp: TempId, payload: N, evidence: Vector[EvidenceRef])
  case AddEdge(from: NodeRef, label: E, to: NodeRef, evidence: Vector[EvidenceRef])
  case RemoveEdge(from: NodeRef, label: E, to: NodeRef, evidence: Vector[EvidenceRef])
  case SetFocus(node: NodeRef, evidence: Vector[EvidenceRef])
  case MergeMentions(
      mentions: NonEmptyVector[NodeRef],
      supersedes: Option[ClaimId],
      evidence: Vector[EvidenceRef]
  )
  case SplitMention(
      mention: NodeRef,
      into: NonEmptyVector[TempId],
      supersedes: Option[ClaimId],
      evidence: Vector[EvidenceRef]
  )
  case ProposeRelation(relation: E, from: NodeRef, to: NodeRef, evidence: Vector[EvidenceRef])
  case SetContext(node: NodeRef, context: NodeRef, evidence: Vector[EvidenceRef])
  case Annotate(target: NodeRef, key: AnnotationKey, value: String, evidence: Vector[EvidenceRef])

  def evidenceRefs: Vector[EvidenceRef] = this match
    case AddNode(_, _, e)            => e
    case AddEdge(_, _, _, e)         => e
    case RemoveEdge(_, _, _, e)      => e
    case SetFocus(_, e)              => e
    case MergeMentions(_, _, e)      => e
    case SplitMention(_, _, _, e)    => e
    case ProposeRelation(_, _, _, e) => e
    case SetContext(_, _, e)         => e
    case Annotate(_, _, _, e)        => e

  /** Temporary identifiers this operation introduces. */
  def introduces: Vector[TempId] = this match
    case AddNode(t, _, _)            => Vector(t)
    case SplitMention(_, into, _, _) => into.toVector
    case _                           => Vector.empty

  /** The claim this operation supersedes, if it is identity-changing. */
  def supersededClaim: Option[ClaimId] = this match
    case MergeMentions(_, s, _)   => s
    case SplitMention(_, _, s, _) => s
    case _                        => None

  /** Node references this operation reads. */
  def references: Vector[NodeRef] = this match
    case AddNode(_, _, _)            => Vector.empty
    case AddEdge(f, _, t, _)         => Vector(f, t)
    case RemoveEdge(f, _, t, _)      => Vector(f, t)
    case SetFocus(n, _)              => Vector(n)
    case MergeMentions(ms, _, _)     => ms.toVector
    case SplitMention(m, _, _, _)    => Vector(m)
    case ProposeRelation(_, f, t, _) => Vector(f, t)
    case SetContext(n, c, _)         => Vector(n, c)
    case Annotate(n, _, _, _)        => Vector(n)

/** An ordered, atomic group of operations with provenance.
  *
  * `origins(i)` is the identifier of the patch that contributed `ops(i)`; composition keeps
  * per-operation attribution and merges provider calls from both sides, so a parser's operations
  * are never attributed to the agent whose patch was composed after them (review finding #36).
  */
final case class Patch[N, E](
    id: PatchId,
    ops: Vector[PatchOp[N, E]],
    provenance: Provenance,
    origins: Vector[PatchId]
):
  def isEmpty: Boolean = ops.isEmpty
  def tempIds: Vector[TempId] = ops.flatMap(_.introduces)
  def supersedes: Vector[ClaimId] = ops.flatMap(_.supersededClaim)

  /** Operations attributed to `origin`. */
  def opsFrom(origin: PatchId): Vector[PatchOp[N, E]] =
    ops.zip(origins).collect { case (op, o) if o == origin => op }

  /** Sequential composition. The identifier is the right-hand patch's (the composite is what the
    * right-hand author produced last); provider calls are the union in order; per-operation origins
    * are concatenated. An empty patch is an identity on ops and calls.
    */
  def ++(other: Patch[N, E]): Patch[N, E] =
    val prov = Provenance(
      calls = (provenance.calls ++ other.provenance.calls).distinct,
      softwareVersion = other.provenance.softwareVersion,
      configHash = other.provenance.configHash
    )
    val id0 = if other.isEmpty && ops.nonEmpty then id else other.id
    val prov0 = if other.isEmpty && ops.nonEmpty then provenance.copy(calls = prov.calls) else prov
    Patch(id0, ops ++ other.ops, prov0, origins ++ other.origins)

  /** Temporaries must be introduced once and before use. */
  def wellFormed: Either[PatchError, Patch[N, E]] =
    val seen = scala.collection.mutable.Set.empty[TempId]
    val result = ops.zipWithIndex.foldLeft[Either[PatchError, Unit]](Right(())) {
      case (Left(e), _)         => Left(e)
      case (Right(()), (op, i)) =>
        val unresolved = op.references.collectFirst {
          case NodeRef.Temp(t) if !seen.contains(t) => t
        }
        val dup = op.introduces
          .find(seen.contains)
          .orElse(op.introduces.diff(op.introduces.distinct).headOption)
        (dup, unresolved) match
          case (Some(t), _) => Left(PatchError.DuplicateTemp(i, t))
          case (_, Some(t)) => Left(PatchError.UnresolvedTemp(i, t))
          case (None, None) =>
            op.introduces.foreach(seen += _)
            Right(())
    }
    result.map(_ => this)

object Patch:
  def apply[N, E](id: PatchId, ops: Vector[PatchOp[N, E]], provenance: Provenance): Patch[N, E] =
    Patch(id, ops, provenance, ops.map(_ => id))

  def empty[N, E](id: PatchId, provenance: Provenance): Patch[N, E] =
    Patch(id, Vector.empty, provenance, Vector.empty)

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
