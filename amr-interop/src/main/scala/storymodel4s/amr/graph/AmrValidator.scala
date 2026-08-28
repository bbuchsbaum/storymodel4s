package storymodel4s.amr.graph

import cats.data.{NonEmptyChain, Validated, ValidatedNec}
import cats.syntax.all.*
import storymodel4s.amr.graph.CheckState.{Checked, Unchecked}
import storymodel4s.amr.graph.RoleForm.{CanonicalRoles, SurfaceRoles}

/** One structural law failure (design record §42.2). */
enum AmrViolation:
  /** Law 1: the declared top is not a defined node. */
  case TopNotDefined(top: NodeId)

  /** Law 2: an edge leaves a node that is not defined. */
  case DanglingSource(edge: Edge)

  /** Law 3 / 7: a node-valued target (a PENMAN variable) is not defined anywhere. */
  case UnresolvedVariable(edge: Edge)

  /** Law 4: a node was referenced/defined without a concept. */
  case MissingConcept(node: NodeId)

  /** Law 5: nodes not reachable from the top ignoring direction. */
  case Disconnected(unreachable: Set[NodeId])

  /** Law 6: an inverse role cannot point at a literal (`:ARG0-of "x"` has no canonical reading). */
  case InverseRoleToLiteral(edge: Edge)

  /** Law 6: a literal value that is not lexically valid. */
  case InvalidLiteral(edge: Edge, reason: String)

  /** Law 8 under a strict profile: the same semantic triple appears twice. */
  case DuplicateTriple(edge: Edge)

  /** Refinement: a directed cycle exists (only reported when the profile requires acyclicity). */
  case Cyclic(nodes: Vector[NodeId])

  def message: String = this match
    case TopNotDefined(t)      => s"top '${t.value}' is not a defined node"
    case DanglingSource(e)     => s"edge '${e.render}' leaves an undefined node"
    case UnresolvedVariable(e) => s"edge '${e.render}' targets an undefined variable"
    case MissingConcept(n)     => s"node '${n.value}' has no concept"
    case Disconnected(u)       =>
      s"nodes not connected to top: ${u.map(_.value).toVector.sorted.mkString(", ")}"
    case InverseRoleToLiteral(e) => s"inverse role with literal target: '${e.render}'"
    case InvalidLiteral(e, r)    => s"invalid literal in '${e.render}': $r"
    case DuplicateTriple(e)      => s"duplicate triple '${e.render}'"
    case Cyclic(ns)              => s"directed cycle through ${ns.map(_.value).mkString(" -> ")}"

/** What the validator enforces beyond the nine base laws. */
final case class ValidationProfile(strictDuplicates: Boolean, requireAcyclic: Boolean)
object ValidationProfile:
  /** Duplicates are removed silently; cycles are permitted (§41.3). */
  val default: ValidationProfile =
    ValidationProfile(strictDuplicates = false, requireAcyclic = false)
  val strict: ValidationProfile = ValidationProfile(strictDuplicates = true, requireAcyclic = true)

/** Promotes an unchecked graph to a checked one, accumulating every violation. */
object AmrValidator:
  type Result = ValidatedNec[AmrViolation, AmrGraph[Checked, SurfaceRoles]]

  def validate(
      g: AmrGraph[Unchecked, SurfaceRoles],
      profile: ValidationProfile = ValidationProfile.default
  ): Result =
    // A node is "defined" when it was introduced (with or without a concept); law 4 separately
    // requires every defined node to carry a concept.
    val defined = g.nodes.toSet ++ g.concepts.keySet
    val v1: ValidatedNec[AmrViolation, Unit] =
      if defined.contains(g.top) then ().validNec else AmrViolation.TopNotDefined(g.top).invalidNec
    val v4: ValidatedNec[AmrViolation, Unit] =
      g.nodes
        .filterNot(g.concepts.contains)
        .toVector
        .traverse_(n => AmrViolation.MissingConcept(n).invalidNec)
    val edgeChecks: ValidatedNec[AmrViolation, Unit] = g.edges.traverse_ { e =>
      val src: ValidatedNec[AmrViolation, Unit] =
        if defined.contains(e.source) then ().validNec
        else AmrViolation.DanglingSource(e).invalidNec
      val tgt: ValidatedNec[AmrViolation, Unit] = e.target match
        case AmrValue.Node(t) =>
          if defined.contains(t) then ().validNec else AmrViolation.UnresolvedVariable(e).invalidNec
        case AmrValue.Literal(l) =>
          val inv: ValidatedNec[AmrViolation, Unit] =
            if e.role.isInverse then AmrViolation.InverseRoleToLiteral(e).invalidNec
            else ().validNec
          val lit: ValidatedNec[AmrViolation, Unit] = l match
            case AmrLiteral.Number(r) if l.numeric.isEmpty =>
              AmrViolation.InvalidLiteral(e, s"not a number: $r").invalidNec
            case AmrLiteral.Symbol(s) if s.isEmpty || s.exists(_.isWhitespace) =>
              AmrViolation.InvalidLiteral(e, "empty or whitespace symbol").invalidNec
            case _ => ().validNec
          inv *> lit
      src *> tgt
    }
    val deduped = dedupe(g.edges)
    val v8: ValidatedNec[AmrViolation, Unit] =
      if profile.strictDuplicates then
        duplicates(g.edges).traverse_(e => AmrViolation.DuplicateTriple(e).invalidNec)
      else ().validNec
    val v5: ValidatedNec[AmrViolation, Unit] =
      if !defined.contains(g.top) then ().validNec
      else
        val unreachable = defined -- reachableUndirected(g.top, g.edges)
        if unreachable.isEmpty then ().validNec
        else AmrViolation.Disconnected(unreachable).invalidNec
    val vCycle: ValidatedNec[AmrViolation, Unit] =
      if profile.requireAcyclic then
        Cycles.find(g.edges) match
          case Some(c) => AmrViolation.Cyclic(c).invalidNec
          case None    => ().validNec
      else ().validNec

    (v1, v4, edgeChecks, v8, v5, vCycle).tupled.map { _ =>
      AmrGraph.checked[SurfaceRoles](g.top, g.nodes, g.concepts, deduped, g.metadata)
    }

  /** Convenience for tests and fixtures: validate or fail with all messages. */
  def validateOrThrow(
      g: AmrGraph[Unchecked, SurfaceRoles],
      profile: ValidationProfile = ValidationProfile.default
  ): AmrGraph[Checked, SurfaceRoles] =
    validate(g, profile) match
      case Validated.Valid(c)   => c
      case Validated.Invalid(e) => throw new IllegalArgumentException(messages(e).mkString("; "))

  def messages(errors: NonEmptyChain[AmrViolation]): Vector[String] =
    errors.toChain.toVector.map(_.message)

  /** Remove edges whose canonical triple repeats an earlier one, keeping first occurrences. */
  private[amr] def dedupe(edges: Vector[Edge]): Vector[Edge] =
    val seen = scala.collection.mutable.HashSet.empty[(NodeId, Role, AmrValue)]
    edges.filter { e =>
      e.canonical match
        case Some(t) => seen.add(t)
        case None    => true
    }

  private def duplicates(edges: Vector[Edge]): Vector[Edge] =
    val seen = scala.collection.mutable.HashSet.empty[(NodeId, Role, AmrValue)]
    edges.filter(e => e.canonical.exists(t => !seen.add(t)))

  private[amr] def reachableUndirected(start: NodeId, edges: Vector[Edge]): Set[NodeId] =
    val adj = scala.collection.mutable.HashMap.empty[NodeId, List[NodeId]].withDefaultValue(Nil)
    edges.foreach { e =>
      e.target.nodeId.foreach { t =>
        adj(e.source) = t :: adj(e.source)
        adj(t) = e.source :: adj(t)
      }
    }
    var seen = Set(start)
    var stack = List(start)
    while stack.nonEmpty do
      val n = stack.head
      stack = stack.tail
      adj(n).foreach { m =>
        if !seen.contains(m) then
          seen += m
          stack = m :: stack
      }
    seen

/** Directed-cycle detection over canonical triples. */
object Cycles:
  /** A cycle as a node sequence (first node repeated at the end), if one exists. */
  def find(edges: Vector[Edge]): Option[Vector[NodeId]] =
    val adj = edges
      .flatMap(_.canonical)
      .collect { case (s, _, AmrValue.Node(t)) => (s, t) }
      .groupBy(_._1)
      .map((k, v) => k -> v.map(_._2))
      .withDefaultValue(Vector.empty)
    val nodes = adj.keys.toVector.sortBy(_.value)
    val White = 0; val Grey = 1; val Black = 2
    val color = scala.collection.mutable.HashMap.empty[NodeId, Int].withDefaultValue(White)
    var found: Option[Vector[NodeId]] = None

    def dfs(n: NodeId, path: List[NodeId]): Unit =
      if found.isEmpty then
        color(n) = Grey
        adj(n).foreach { m =>
          if found.isEmpty then
            color(m) match
              case c if c == Grey =>
                // `path` holds the current DFS chain with the newest node first; the cycle is the
                // suffix from `m` to `n`, closed by `m`. A self-loop therefore reports `[m, m]`.
                val cyc = path.reverse.dropWhile(_ != m)
                found = Some((cyc :+ m).toVector)
              case c if c == White => dfs(m, m :: path)
              case _               => ()
        }
        color(n) = Black

    nodes.foreach(n => if color(n) == White && found.isEmpty then dfs(n, List(n)))
    found

  def isAcyclic(edges: Vector[Edge]): Boolean = find(edges).isEmpty

/** Flips inverse roles so every edge reads in its canonical semantic direction. */
object RoleCanonicalizer:
  def canonicalize(g: AmrGraph[Checked, SurfaceRoles]): AmrGraph[Checked, CanonicalRoles] =
    val edges = g.edges.flatMap { e =>
      e.canonical.map((s, r, t) => Edge(s, SurfaceRole.direct(r), t))
    }
    AmrGraph
      .checked[CanonicalRoles](g.top, g.nodes, g.concepts, AmrValidator.dedupe(edges), g.metadata)

  /** Validate and canonicalize in one step. */
  def fromUnchecked(
      g: AmrGraph[Unchecked, SurfaceRoles],
      profile: ValidationProfile = ValidationProfile.default
  ): ValidatedNec[AmrViolation, AmrGraph[Checked, CanonicalRoles]] =
    AmrValidator.validate(g, profile).map(canonicalize)

/** Shape refinements computed on checked graphs (§41.3): connectivity is guaranteed by validation;
  * acyclicity is optional evidence that topological algorithms may demand.
  */
final case class Shape(connected: Boolean, acyclic: Boolean)

object Shape:
  def of[R <: RoleForm](g: AmrGraph[Checked, R]): Shape =
    Shape(connected = true, acyclic = Cycles.isAcyclic(g.edges))

/** Evidence that a checked canonical graph is acyclic. Only obtainable through `Acyclic.check`. */
final class Acyclic private (val graph: AmrGraph[Checked, CanonicalRoles]):
  /** Nodes in a topological order (sources before targets). */
  def topologicalOrder: Vector[NodeId] =
    val out = (n: NodeId) => graph.relations(n)
    val indeg = scala.collection.mutable.HashMap.empty[NodeId, Int].withDefaultValue(0)
    graph.nodes.foreach(n => out(n).foreach((_, t) => indeg(t) += 1))
    var ready = graph.nodes.filter(n => indeg(n) == 0).toList
    val result = Vector.newBuilder[NodeId]
    while ready.nonEmpty do
      val n = ready.head
      ready = ready.tail
      result += n
      out(n).foreach { (_, t) =>
        indeg(t) -= 1
        if indeg(t) == 0 then ready = ready :+ t
      }
    result.result()

object Acyclic:
  def check(g: AmrGraph[Checked, CanonicalRoles]): Option[Acyclic] =
    if Cycles.isAcyclic(g.edges) then Some(new Acyclic(g)) else None
