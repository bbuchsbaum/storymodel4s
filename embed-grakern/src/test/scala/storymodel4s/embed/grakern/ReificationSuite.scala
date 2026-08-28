package storymodel4s.embed.grakern

import munit.FunSuite

import grakern.core.SampleKey

import storymodel4s.proposition.*
import storymodel4s.proposition.CheckState.Checked

/** G1 through reification: structurally different charts reify to non-isomorphic directed labelled
  * graphs (proved by brute force on the small reified graphs), and G2: renaming and relation order
  * do not change the reified structure.
  */
class ReificationSuite extends FunSuite:
  import Charts.*

  private def reify(chart: PropositionChart[Checked], name: String): ReifiedChart =
    ChartNeighbourhood.of(chart, SampleKey(name)).fold(e => fail(e.toString), identity)

  /** Brute-force isomorphism of small directed labelled graphs (vertex keys + arc kinds). */
  private def isomorphic(a: ReifiedChart, b: ReifiedChart): Boolean =
    if a.vertices.size != b.vertices.size || a.arcs.size != b.arcs.size then false
    else
      val av = a.vertices.map(_._1)
      val bv = b.vertices.map(_._1)
      val ak = a.vertices.toMap
      val bk = b.vertices.toMap
      val aArcs = a.arcs.toSet
      // candidates for each a-vertex: b-vertices with the same key
      val candidates = av.map(v => bv.filter(w => bk(w) == ak(v)))
      def search(i: Int, used: Set[String], m: Map[String, String]): Boolean =
        if i == av.size then aArcs.forall { case (s, t, k) => b.arcs.contains((m(s), m(t), k)) }
        else
          candidates(i).exists { w =>
            !used.contains(w) && search(i + 1, used + w, m.updated(av(i), w))
          }
      search(0, Set.empty, Map.empty)

  test("reification is deterministic and key-complete") {
    val r = reify(transitive("find", "anna", "brother"), "t")
    assertEquals(r.vertices.size, 5) // 3 concepts + 2 relations
    assertEquals(r.arcs.size, 4)
    assertEquals(
      r.vertices.map(_._2.kind).sortBy(_.ordinal),
      Vector(
        ReifiedKind.Concept,
        ReifiedKind.Concept,
        ReifiedKind.Concept,
        ReifiedKind.Relation,
        ReifiedKind.Relation
      )
    )
    val again = reify(transitive("find", "anna", "brother"), "t")
    assertEquals(again.vertices, r.vertices)
    assertEquals(again.arcs, r.arcs)
  }

  test("G1: swapping fillers across ARG0/ARG1 yields non-isomorphic reified graphs") {
    val straight = reify(transitive("find", "anna", "brother"), "s")
    val swapped = reify(transitive("find", "brother", "anna"), "r")
    assert(!isomorphic(straight, swapped))
    assert(isomorphic(straight, straight))
  }

  test("G1: polarity flip and embedding-kind change yield non-isomorphic reified graphs") {
    val pos = reify(transitive("find", "anna", "brother"), "p")
    val neg = reify(transitive("find", "anna", "brother", negated = true), "n")
    assert(!isomorphic(pos, neg))
    val speech = reify(embedded(EmbeddingKind.Speech), "sp")
    val belief = reify(embedded(EmbeddingKind.Belief), "be")
    assert(!isomorphic(speech, belief))
  }

  test("G2: alpha-renaming concept ids and reversing relation order give identical reifications") {
    val base = reify(transitive("find", "anna", "brother"), "k")
    val renamed = reify(
      transitive("find", "anna", "brother", ids = ("zz", "q", "m"), relationOrder = false),
      "k"
    )
    assertEquals(renamed.vertices, base.vertices)
    assertEquals(renamed.arcs, base.arcs)
  }

  test("literals and unknown fillers become keyed leaves") {
    val r = reify(withLeaves, "leaves")
    val kinds = r.vertices.map(_._2.kind)
    assert(kinds.contains(ReifiedKind.Literal))
    assert(kinds.contains(ReifiedKind.Unknown))
    assertEquals(r.vertices.count(_._2.kind == ReifiedKind.Relation), 3)
    assertEquals(r.arcs.size, 6)
  }
