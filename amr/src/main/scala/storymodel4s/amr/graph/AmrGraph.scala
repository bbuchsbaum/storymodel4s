package storymodel4s.amr.graph

import cats.Eq
import storymodel4s.amr.graph.CheckState.{Checked, Unchecked}
import storymodel4s.amr.graph.RoleForm.{CanonicalRoles, SurfaceRoles}

/** A propositional chart: a rooted, node-labelled, role-edge-labelled graph.
  *
  * `C` records whether the structural laws have been checked; `R` records whether roles are as
  * written (possibly inverse) or canonical (direct only). Construction is private: unchecked graphs
  * come from `AmrGraph.unchecked` or the decoder; checked graphs only from `AmrValidator`.
  *
  * Why a phantom-typed single class: downstream code can demand `AmrGraph[Checked, CanonicalRoles]`
  * and be guaranteed that dangling references, missing concepts, and inverse spellings are gone.
  */
final case class AmrGraph[C <: CheckState, R <: RoleForm] private[amr] (
    top: NodeId,
    nodes: Vector[NodeId],
    concepts: Map[NodeId, Concept],
    edges: Vector[Edge],
    metadata: Vector[(String, String)]
):
  def nodeCount: Int = nodes.size
  def edgeCount: Int = edges.size
  def concept(id: NodeId): Option[Concept] = concepts.get(id)
  def topConcept: Option[Concept] = concepts.get(top)

  lazy val outgoing: Map[NodeId, Vector[Edge]] =
    edges.groupBy(_.source).withDefaultValue(Vector.empty)
  lazy val incoming: Map[NodeId, Vector[Edge]] =
    edges
      .flatMap(e => e.target.nodeId.map(_ -> e))
      .groupBy(_._1)
      .map((k, v) => k -> v.map(_._2))
      .withDefaultValue(Vector.empty)

  /** Edges as canonical triples (endpoints swapped for inverse roles); inverse-to-literal edges are
    * dropped because they have no canonical reading.
    */
  def canonicalTriples: Vector[(NodeId, Role, AmrValue)] = edges.flatMap(_.canonical)

  /** Attribute (literal-valued) canonical triples of a node. */
  def attributes(id: NodeId): Vector[(Role, AmrLiteral)] =
    canonicalTriples.collect { case (s, r, AmrValue.Literal(l)) if s == id => (r, l) }

  /** Node-valued canonical triples leaving `id`. */
  def relations(id: NodeId): Vector[(Role, NodeId)] =
    canonicalTriples.collect { case (s, r, AmrValue.Node(t)) if s == id => (r, t) }

  /** Nodes whose concept is a frame — the event-like fragments. */
  def frameNodes: Vector[(NodeId, FrameId)] =
    nodes.flatMap(n => concepts.get(n).collect { case Concept.Frame(f) => (n, f) })

  /** True when node `id` carries `:polarity -`. */
  def isNegated(id: NodeId): Boolean =
    attributes(id).exists((r, l) => r == Role.polarity && l == AmrLiteral.Symbol("-"))

  /** Nodes referenced (as source or node target) but not defined with a concept. */
  def undefinedReferences: Set[NodeId] =
    val referenced = edges.flatMap(e => e.source +: e.target.nodeId.toVector).toSet
    (referenced + top) -- concepts.keySet

  def withMetadata(meta: Vector[(String, String)]): AmrGraph[C, R] = copy(metadata = meta)

  /** Forget the check state (e.g. after an edit). */
  def uncheck: AmrGraph[Unchecked, SurfaceRoles] =
    AmrGraph[Unchecked, SurfaceRoles](top, nodes, concepts, edges, metadata)

  /** Deterministic multi-line rendering of the triples, for diagnostics. */
  def renderTriples: String =
    val inst = nodes.map(n => s"${n.value} / ${concepts.get(n).map(_.render).getOrElse("?")}")
    (inst ++ edges.sorted.map(_.render)).mkString("\n")

object AmrGraph:
  /** Build an unchecked graph. Duplicate node definitions keep the first concept. */
  def unchecked(
      top: NodeId,
      concepts: Vector[(NodeId, Concept)],
      edges: Vector[Edge],
      metadata: Vector[(String, String)] = Vector.empty
  ): AmrGraph[Unchecked, SurfaceRoles] =
    val nodes = concepts.map(_._1).distinct
    val conceptMap = concepts.foldLeft(Map.empty[NodeId, Concept]) { case (m, (n, c)) =>
      if m.contains(n) then m else m.updated(n, c)
    }
    AmrGraph(top, nodes, conceptMap, edges, metadata)

  /** Build an unchecked graph whose node list may include nodes defined without a concept (as
    * PENMAN `(b)` permits); validation reports them as `MissingConcept`.
    */
  def uncheckedWithNodes(
      top: NodeId,
      nodes: Vector[NodeId],
      concepts: Vector[(NodeId, Concept)],
      edges: Vector[Edge],
      metadata: Vector[(String, String)] = Vector.empty
  ): AmrGraph[Unchecked, SurfaceRoles] =
    val base = unchecked(top, concepts, edges, metadata)
    base.copy(nodes = (nodes ++ base.nodes).distinct)

  private[amr] def checked[R <: RoleForm](
      top: NodeId,
      nodes: Vector[NodeId],
      concepts: Map[NodeId, Concept],
      edges: Vector[Edge],
      metadata: Vector[(String, String)]
  ): AmrGraph[Checked, R] = AmrGraph(top, nodes, concepts, edges, metadata)

  /** Exact artifact equality: same top, same node identifiers and concepts, same edge set. Edge
    * order and metadata are not semantic. For renaming-invariant equality use `AmrIsomorphism`.
    */
  given [C <: CheckState, R <: RoleForm]: Eq[AmrGraph[C, R]] = Eq.instance { (a, b) =>
    a.top == b.top && a.concepts == b.concepts && a.edges.sorted == b.edges.sorted
  }

  extension (g: AmrGraph[Checked, CanonicalRoles])
    /** A canonical-role graph is trivially a valid surface-role graph. */
    def asSurface: AmrGraph[Checked, SurfaceRoles] =
      AmrGraph(g.top, g.nodes, g.concepts, g.edges, g.metadata)
