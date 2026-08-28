package storymodel4s.amr.graph

import storymodel4s.amr.graph.CheckState.Checked
import storymodel4s.amr.graph.RoleForm.CanonicalRoles
import storymodel4s.core.Checksum

/** Alpha-renaming and edge-order invariant equality of canonical graphs, decided by exact
  * backtracking with concept/degree/attribute pruning.
  *
  * Why exact: AMRs are small (tens of nodes); a sound and complete test is affordable and keeps
  * `AmrIsomorphism` semantically distinct from graded `Smatch`.
  */
object AmrIsomorphism:
  private type G = AmrGraph[Checked, CanonicalRoles]

  /** Per-node invariant that any isomorphism must preserve. */
  private def signature(g: G, n: NodeId): String =
    val c = g.concepts(n).render
    val attrs = g.attributes(n).map((r, l) => s"${r.render}=${l.render}").sorted.mkString(",")
    val outRoles = g.relations(n).map(_._1.render).sorted.mkString(",")
    val inRoles = g.incoming(n).flatMap(_.canonical).map(_._2.render).sorted.mkString(",")
    s"$c|$attrs|out:$outRoles|in:$inRoles"

  def findIsomorphism(a: G, b: G): Option[Map[NodeId, NodeId]] =
    if a.nodeCount != b.nodeCount || a.edgeCount != b.edgeCount then None
    else
      val sigA = a.nodes.map(n => n -> signature(a, n)).toMap
      val sigB = b.nodes.map(n => n -> signature(b, n)).toMap
      if sigA.values.toVector.sorted != sigB.values.toVector.sorted then None
      else if sigA(a.top) != sigB(b.top) then None
      else
        val relA = a.nodes.map(n => n -> a.relations(n).toSet).toMap
        val relB = b.nodes.map(n => n -> b.relations(n).toSet).toMap
        val bySigB = b.nodes.groupBy(sigB)
        // order: top first, then BFS from top so candidates are constrained early
        val order = bfsOrder(a)

        def consistent(m: Map[NodeId, NodeId], n: NodeId, target: NodeId): Boolean =
          relA(n).forall { (r, t) =>
            m.get(t) match
              case Some(tb) => relB(target).contains((r, tb))
              case None     => relB(target).exists(_._1 == r)
          } && m.forall { (na, nb) =>
            relA(na).forall { (r, t) =>
              if t == n then relB(nb).contains((r, target)) else true
            }
          }

        def go(i: Int, m: Map[NodeId, NodeId], used: Set[NodeId]): Option[Map[NodeId, NodeId]] =
          if i == order.length then
            // full edge check
            val ok = a.nodes.forall(n => relA(n).map((r, t) => (r, m(t))) == relB(m(n)))
            if ok then Some(m) else None
          else
            val n = order(i)
            val candidates =
              if n == a.top then Vector(b.top)
              else bySigB.getOrElse(sigA(n), Vector.empty).filterNot(used.contains)
            candidates.iterator
              .filter(c => consistent(m, n, c))
              .map(c => go(i + 1, m.updated(n, c), used + c))
              .collectFirst { case Some(r) => r }

        go(0, Map.empty, Set.empty)

  def isomorphic(a: G, b: G): Boolean = findIsomorphism(a, b).isDefined

  private def bfsOrder(g: G): Vector[NodeId] =
    var seen = Set(g.top)
    var queue = Vector(g.top)
    val out = Vector.newBuilder[NodeId]
    while queue.nonEmpty do
      val n = queue.head
      queue = queue.tail
      out += n
      val nbrs = g.relations(n).map(_._2) ++ g.incoming(n).map(_.source)
      nbrs.foreach { m =>
        if !seen.contains(m) then
          seen += m
          queue = queue :+ m
      }
    // any nodes not reached (cannot happen for checked graphs) are appended
    out.result() ++ g.nodes.filterNot(seen.contains)

/** Deterministic canonical form: nodes relabelled `n0, n1, ...` by a DFS from the top that orders
  * siblings by role and by a Weisfeiler–Lehman refinement of their neighbourhoods; edges sorted.
  *
  * Law: `AmrIsomorphism.isomorphic(a, b)` iff `form(a) === form(b)` (exact equality). Ties between
  * WL-indistinguishable siblings that are not automorphic could in principle break the law; such
  * graphs do not arise in AMR practice and the property suite guards the claim.
  */
object Canonical:
  private type G = AmrGraph[Checked, CanonicalRoles]

  private def refine(g: G): Map[NodeId, String] =
    var sig: Map[NodeId, String] = g.nodes.map { n =>
      val attrs = g.attributes(n).map((r, l) => s"${r.render}=${l.render}").sorted.mkString(",")
      n -> Checksum.ofText(s"${g.concepts(n).render}|$attrs").hex
    }.toMap
    var i = 0
    while i < g.nodeCount do
      sig = g.nodes.map { n =>
        val out = g.relations(n).map((r, t) => s"${r.render}>${sig(t)}").sorted.mkString(",")
        val in = g
          .incoming(n)
          .flatMap(_.canonical)
          .map((s, r, _) => s"${r.render}<${sig(s)}")
          .sorted
          .mkString(",")
        n -> Checksum.ofText(s"${sig(n)}|$out|$in").hex
      }.toMap
      i += 1
    sig

  /** The relabelling map from original node ids to canonical labels. */
  def labelling(g: G): Map[NodeId, NodeId] =
    val sig = refine(g)
    var label = Map.empty[NodeId, NodeId]
    var next = 0
    def visit(n: NodeId): Unit =
      if !label.contains(n) then
        label += n -> NodeId.unsafe(s"n$next")
        next += 1
        val out = g.relations(n).sortBy((r, t) => (r.render, sig(t), t.value))
        out.foreach((_, t) => visit(t))
        val in = g.incoming(n).flatMap(_.canonical).sortBy((s, r, _) => (r.render, sig(s), s.value))
        in.foreach((s, _, _) => visit(s))
    visit(g.top)
    g.nodes.sortBy(n => (sig(n), n.value)).foreach(visit)
    label

  def form(g: G): G =
    val label = labelling(g)
    val nodes = g.nodes.map(label).sortBy(_.value.drop(1).toInt)
    val concepts = g.concepts.map((n, c) => label(n) -> c)
    val edges = g.edges.map { e =>
      Edge(
        label(e.source),
        e.role,
        e.target match
          case AmrValue.Node(t) => AmrValue.Node(label(t))
          case lit              => lit
      )
    }.sorted
    // Metadata is provenance, not meaning: the canonical form carries none.
    AmrGraph.checked[CanonicalRoles](label(g.top), nodes, concepts, edges, Vector.empty)

  /** A stable digest of the canonical form; equal for isomorphic graphs. */
  def digest(g: G): Checksum = Checksum.ofText(form(g).renderTriples)
