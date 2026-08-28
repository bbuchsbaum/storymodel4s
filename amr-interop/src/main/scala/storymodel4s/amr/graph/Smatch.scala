package storymodel4s.amr.graph

import storymodel4s.amr.graph.CheckState.Checked
import storymodel4s.amr.graph.RoleForm.CanonicalRoles

/** Smatch (Cai & Knight 2013) baseline: triple precision/recall/F1 under a variable mapping found
  * by seeded, restarted hill-climbing.
  *
  * This is a *baseline*, not the project's alignment metric: it is symmetric in role direction only
  * through exact triple identity, has no notion of partial or hierarchical matches, and has no
  * external-mass term. It exists so that structural claims can be compared against the standard
  * community number. Deterministic for a fixed `(restarts, seed)`.
  */
object Smatch:
  private type G = AmrGraph[Checked, CanonicalRoles]

  final case class Score(
      matched: Int,
      totalLeft: Int,
      totalRight: Int,
      mapping: Map[NodeId, NodeId]
  ):
    def precision: Double = if totalLeft == 0 then 0.0 else matched.toDouble / totalLeft
    def recall: Double = if totalRight == 0 then 0.0 else matched.toDouble / totalRight
    def f1: Double =
      val p = precision
      val r = recall
      if p + r == 0.0 then 0.0 else 2 * p * r / (p + r)

  private enum Triple:
    case Instance(node: NodeId, concept: String)
    case Attribute(node: NodeId, role: String, value: String)
    case Relation(source: NodeId, role: String, target: NodeId)
    case Top(node: NodeId)

  private def triples(g: G): Vector[Triple] =
    val inst = g.nodes.map(n => Triple.Instance(n, g.concepts(n).render))
    val rest = g.canonicalTriples.map {
      case (s, r, AmrValue.Node(t))    => Triple.Relation(s, r.render, t)
      case (s, r, AmrValue.Literal(l)) => Triple.Attribute(s, r.render, l.render)
    }
    (inst ++ rest) :+ Triple.Top(g.top)

  private def mapTriple(t: Triple, m: Map[NodeId, NodeId]): Option[Triple] = t match
    case Triple.Instance(n, c)     => m.get(n).map(Triple.Instance(_, c))
    case Triple.Attribute(n, r, v) => m.get(n).map(Triple.Attribute(_, r, v))
    case Triple.Relation(s, r, t2) =>
      for a <- m.get(s); b <- m.get(t2) yield Triple.Relation(a, r, b)
    case Triple.Top(n) => m.get(n).map(Triple.Top(_))

  private def count(left: Vector[Triple], right: Set[Triple], m: Map[NodeId, NodeId]): Int =
    left.count(t => mapTriple(t, m).exists(right.contains))

  def score(left: G, right: G, restarts: Int = 4, seed: Long = 0L): Score =
    val lt = triples(left)
    val rt = triples(right)
    val rset = rt.toSet
    val lNodes = left.nodes
    val rNodes = right.nodes
    val rnd = new scala.util.Random(seed)

    def greedy(shuffle: Boolean): Map[NodeId, NodeId] =
      val order = if shuffle then rnd.shuffle(lNodes) else lNodes
      var used = Set.empty[NodeId]
      var m = Map.empty[NodeId, NodeId]
      order.foreach { n =>
        val c = left.concepts(n).render
        val cand = rNodes.filterNot(used.contains)
        val same = cand.filter(r => right.concepts(r).render == c)
        val pick = (if same.nonEmpty then same else cand).headOption
        pick.foreach { r =>
          m += n -> r
          used += r
        }
      }
      m

    def climb(start: Map[NodeId, NodeId]): (Map[NodeId, NodeId], Int) =
      var m = start
      var best = count(lt, rset, m)
      var improved = true
      while improved do
        improved = false
        val mapped = m.values.toSet
        for n <- lNodes if !improved do
          // move to an unused right node
          for r <- rNodes if !improved && !mapped.contains(r) do
            val m2 = m.updated(n, r)
            val s = count(lt, rset, m2)
            if s > best then { m = m2; best = s; improved = true }
          // swap with another left node
          for n2 <- lNodes if !improved && n2 != n && m.contains(n) && m.contains(n2) do
            val m2 = m.updated(n, m(n2)).updated(n2, m(n))
            val s = count(lt, rset, m2)
            if s > best then { m = m2; best = s; improved = true }
      (m, best)

    val starts = greedy(false) +: Vector.fill(math.max(0, restarts - 1))(greedy(true))
    val (bestMap, bestCount) = starts.map(climb).maxBy(_._2)
    Score(bestCount, lt.size, rt.size, bestMap)
