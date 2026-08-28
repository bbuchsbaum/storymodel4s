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

/** Deterministic canonical form by individualization–refinement.
  *
  * Nodes are coloured by concept and attributes, refined Weisfeiler–Lehman style until stable, and
  * whenever a colour class is still not a singleton the search branches: each member of the first
  * non-singleton class is individualized in turn, the colouring is refined again, and the
  * lexicographically smallest serialization among all branches is the canonical form. Members of a
  * tied class that are structural twins (interchangeable by an automorphism that swaps just the two
  * of them) are explored once, which keeps `n` identical `:mod` fillers linear rather than
  * factorial.
  *
  * Law: `AmrIsomorphism.isomorphic(a, b)` iff `form(a) === form(b)`. The result depends only on
  * structure — original variable names never break ties — so it is label-invariant even for graphs
  * 1-WL cannot separate (e.g. a 6-cycle beside two 3-cycles of identical concepts).
  *
  * Complexity: exponential only in the number of non-twin symmetric classes, which for AMR-sized
  * graphs means a few hundred leaves at most. `BranchBudget` bounds the search; a graph that
  * exhausts it is reported through `formBounded` rather than silently producing an unsound form.
  */
object Canonical:
  private type G = AmrGraph[Checked, CanonicalRoles]

  /** Maximum number of discrete colourings examined before `formBounded` reports exhaustion. */
  val BranchBudget: Int = 200000

  private final case class Adj(
      out: Map[NodeId, Vector[(String, NodeId)]],
      in: Map[NodeId, Vector[(String, NodeId)]],
      base: Map[NodeId, String]
  )

  private def adjacency(g: G): Adj =
    val out = g.nodes.map(n => n -> g.relations(n).map((r, t) => (r.render, t))).toMap
    val in = g.nodes.map { n =>
      n -> g.incoming(n).flatMap(_.canonical).map((s, r, _) => (r.render, s))
    }.toMap
    val base = g.nodes.map { n =>
      val attrs = g.attributes(n).map((r, l) => s"${r.render}=${l.render}").sorted.mkString(",")
      val top = if n == g.top then "top" else "node"
      n -> Checksum.ofText(s"$top|${g.concepts(n).render}|$attrs").hex
    }.toMap
    Adj(out, in, base)

  /** Refine a colouring to equitable stability. Colours are hashes of structure only. */
  private def refine(adj: Adj, nodes: Vector[NodeId], start: Map[NodeId, String]) =
    var colour = start
    var classes = colour.values.toSet.size
    var progress = true
    while progress do
      val next = nodes.map { n =>
        val out = adj.out(n).map((r, t) => s"$r>${colour(t)}").sorted.mkString(",")
        val in = adj.in(n).map((r, s) => s"$r<${colour(s)}").sorted.mkString(",")
        n -> Checksum.ofText(s"${colour(n)}|$out|$in").hex
      }.toMap
      val nextClasses = next.values.toSet.size
      progress = nextClasses > classes
      classes = nextClasses
      colour = next
    colour

  /** True when swapping `u` and `v` is an automorphism (identical labelled neighbourhoods). */
  private def twins(adj: Adj, u: NodeId, v: NodeId): Boolean =
    def swap(x: NodeId): NodeId = if x == u then v else if x == v then u else x
    adj.base(u) == adj.base(v) &&
    adj.out(u).map((r, t) => (r, swap(t))).sorted == adj.out(v).sorted &&
    adj.in(u).map((r, s) => (r, swap(s))).sorted == adj.in(v).sorted

  private final case class Leaf(serialization: String, labels: Map[NodeId, Int])

  private def serialize(g: G, rank: Map[NodeId, Int]): String =
    val inst = g.nodes.map(n => s"${rank(n)} / ${g.concepts(n).render}").sorted
    val edges = g.edges.map { e =>
      val t = e.target match
        case AmrValue.Node(x)    => s"n${rank(x)}"
        case AmrValue.Literal(l) => l.render
      f"${rank(e.source)}%06d :${e.role.render} $t"
    }.sorted
    (inst ++ edges).mkString("\n")

  private final case class Search(g: G, adj: Adj):
    var examined = 0
    var best: Option[Leaf] = None
    var exhausted = false

    def run(colour: Map[NodeId, String]): Unit =
      if exhausted then ()
      else
        val refined = refine(adj, g.nodes, colour)
        val classes = g.nodes.groupBy(refined).toVector.sortBy(_._1)
        classes.find(_._2.size > 1) match
          case None =>
            examined += 1
            if examined > BranchBudget then exhausted = true
            else
              val rank = classes.zipWithIndex.map((c, i) => c._2.head -> i).toMap
              val leaf = Leaf(serialize(g, rank), rank)
              if best.forall(b => leaf.serialization < b.serialization) then best = Some(leaf)
          case Some((tied, members)) =>
            // explore one representative per twin orbit, in a label-independent order
            var representatives = Vector.empty[NodeId]
            members.sortBy(_.value).foreach { m =>
              if !representatives.exists(r => twins(adj, r, m)) then representatives :+= m
            }
            representatives.foreach { m =>
              run(refined.updated(m, Checksum.ofText(s"${tied}|individualized").hex))
            }

  private def search(g: G): Search =
    val adj = adjacency(g)
    val s = Search(g, adj)
    s.run(adj.base)
    s

  /** The relabelling map from original node ids to canonical labels `n0, n1, ...`. */
  def labelling(g: G): Map[NodeId, NodeId] =
    val s = search(g)
    val rank = s.best.map(_.labels).getOrElse(g.nodes.zipWithIndex.toMap)
    rank.map((n, i) => n -> NodeId.unsafe(s"n$i"))

  def form(g: G): G =
    val label = labelling(g)
    relabel(g, label)

  /** Canonical form together with whether the search completed within `BranchBudget`. */
  def formBounded(g: G): (G, Boolean) =
    val s = search(g)
    val rank = s.best.map(_.labels).getOrElse(g.nodes.zipWithIndex.toMap)
    (relabel(g, rank.map((n, i) => n -> NodeId.unsafe(s"n$i"))), !s.exhausted)

  private def relabel(g: G, label: Map[NodeId, NodeId]): G =
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
