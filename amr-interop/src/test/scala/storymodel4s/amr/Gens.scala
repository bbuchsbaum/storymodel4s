package storymodel4s.amr

import org.scalacheck.Gen
import storymodel4s.amr.graph.*
import storymodel4s.amr.graph.CheckState.{Checked, Unchecked}
import storymodel4s.amr.graph.RoleForm.{CanonicalRoles, SurfaceRoles}
import storymodel4s.amr.penman.*

/** Generators for valid graphs and for minimally invalid graphs (one violation each). */
object Gens:
  val frameConcepts: Vector[String] =
    Vector("want-01", "go-02", "say-01", "see-01", "believe-01", "like-01", "give-01", "hit-01")
  val lexicalConcepts: Vector[String] =
    Vector("boy", "girl", "dog", "house", "ghost", "river", "canoe", "warrior", "man")
  val standardRoles: Vector[String] =
    Vector("location", "time", "mod", "manner", "purpose", "poss", "domain")

  val concept: Gen[Concept] =
    Gen.oneOf(Gen.oneOf(frameConcepts), Gen.oneOf(lexicalConcepts)).map(Concept.unsafe)

  val literal: Gen[AmrLiteral] = Gen.oneOf(
    Gen.oneOf("-", "+", "imperative", "expressive").map(AmrLiteral.Symbol(_)),
    Gen.chooseNum(-50, 500).map(i => AmrLiteral.Number(i.toString)),
    Gen.oneOf("Paris", "New York", "O\"Brien", "a\\b").map(AmrLiteral.Text(_))
  )

  val literalRole: Gen[Role] = Gen.oneOf(
    Gen.const(Role.polarity),
    Gen.oneOf("quant", "mode", "wiki", "year").map(Role.standard),
    Gen.chooseNum(1, 3).map(i => Role.Operand(PosIndex.unsafe(i)))
  )

  val nodeRole: Gen[Role] = Gen.frequency(
    5 -> Gen.chooseNum(0, 4).map(Role.arg),
    2 -> Gen.oneOf(standardRoles).map(Role.standard),
    1 -> Gen.chooseNum(1, 3).map(i => Role.Operand(PosIndex.unsafe(i)))
  )

  /** A connected canonical-role graph: a random spanning tree from `n0` plus a few extra edges
    * (reentrancies, possibly cycles) and literal attributes. With some probability the tree has
    * *duplicate-role siblings sharing a concept* (several `:op1 (x / boy)` under one parent) and an
    * extra reentrancy into one of them, so canonical-form ties are exercised.
    */
  val canonicalGraph: Gen[AmrGraph[Checked, CanonicalRoles]] = for
    n <- Gen.chooseNum(1, 8)
    concepts <- Gen.listOfN(n, concept)
    parents <- Gen.sequence[List[Int], Int]((1 until n).map(i => Gen.chooseNum(0, i - 1)))
    roles <- Gen.listOfN(math.max(0, n - 1), nodeRole)
    extraCount <- Gen.chooseNum(0, 2)
    extras <- Gen.listOfN(
      extraCount,
      Gen.zip(Gen.chooseNum(0, n - 1), Gen.chooseNum(0, n - 1), nodeRole)
    )
    attrCount <- Gen.chooseNum(0, 2)
    attrs <- Gen.listOfN(attrCount, Gen.zip(Gen.chooseNum(0, n - 1), literalRole, literal))
    twins <- Gen.frequency(2 -> Gen.const(0), 1 -> Gen.chooseNum(2, 4))
    twinRole <- nodeRole
    twinConcept <- concept
    twinLink <- Gen.prob(0.5)
  yield
    val ids = (0 until n).map(i => NodeId.unsafe(s"x$i")).toVector
    val treeEdges = parents.zip(roles).zipWithIndex.map { case ((p, r), i) =>
      Edge(ids(p), SurfaceRole.direct(r), AmrValue.Node(ids(i + 1)))
    }
    val extraEdges =
      extras.map((s, t, r) => Edge(ids(s), SurfaceRole.direct(r), AmrValue.Node(ids(t))))
    val attrEdges = attrs.map((s, r, l) => Edge(ids(s), SurfaceRole.direct(r), AmrValue.Literal(l)))
    val twinIds = (0 until twins).map(i => NodeId.unsafe(s"w$i")).toVector
    val twinEdges = twinIds.map(w => Edge(ids(0), SurfaceRole.direct(twinRole), AmrValue.Node(w)))
    val twinBack =
      if twinLink && twins > 0 then
        Vector(Edge(ids(n - 1), SurfaceRole.direct(Role.arg(2)), AmrValue.Node(twinIds.head)))
      else Vector.empty
    val g = AmrGraph.unchecked(
      ids(0),
      ids.zip(concepts).toVector ++ twinIds.map(_ -> twinConcept),
      (treeEdges ++ extraEdges ++ attrEdges).toVector ++ twinEdges ++ twinBack
    )
    RoleCanonicalizer.canonicalize(AmrValidator.validateOrThrow(g))

  /** Random consistent renaming of a canonical graph's nodes, with edges shuffled and some
    * node-target edges flipped to inverse spelling: an alpha-variant with surface trivia.
    */
  def alphaVariant(g: AmrGraph[Checked, CanonicalRoles]): Gen[AmrGraph[Unchecked, SurfaceRoles]] =
    for
      perm <- Gen.const(g.nodes).flatMap(ns => Gen.pick(ns.size, ns).map(_.toVector))
      order <- Gen.pick(g.edges.size, g.edges.indices).map(_.toVector)
      flips <- Gen.listOfN(g.edges.size, Gen.prob(0.3))
    yield
      val ren = g.nodes.zip(perm.map(p => NodeId.unsafe("v" + g.nodes.indexOf(p)))).toMap
      val edges = order.zip(flips).map { (i, flip) =>
        val e = g.edges(i)
        val t = e.target match
          case AmrValue.Node(x) => AmrValue.Node(ren(x))
          case l                => l
        e.target match
          case AmrValue.Node(x) if flip => Edge(ren(x), e.role.invert, AmrValue.Node(ren(e.source)))
          case _                        => Edge(ren(e.source), e.role, t)
      }
      AmrGraph.unchecked(ren(g.top), g.nodes.map(n => ren(n) -> g.concepts(n)), edges)

  /** One-violation mutations of a valid graph, labelled by the law they break. */
  def invalidVariant(
      g: AmrGraph[Checked, CanonicalRoles]
  ): Gen[(String, AmrGraph[Unchecked, SurfaceRoles])] =
    val concepts = g.nodes.map(n => n -> g.concepts(n))
    val ghost = NodeId.unsafe("ghost")
    val anyNode = Gen.oneOf(g.nodes)
    Gen.oneOf(
      Gen.const("top-not-defined" -> AmrGraph.unchecked(ghost, concepts, g.edges)),
      anyNode.map(n =>
        "dangling-source" -> AmrGraph.unchecked(
          g.top,
          concepts,
          g.edges :+ Edge(ghost, SurfaceRole.direct(Role.arg(0)), AmrValue.Node(n))
        )
      ),
      anyNode.map(n =>
        "unresolved-variable" -> AmrGraph.unchecked(
          g.top,
          concepts,
          g.edges :+ Edge(n, SurfaceRole.direct(Role.arg(1)), AmrValue.Node(ghost))
        )
      ),
      Gen.const(
        "disconnected" -> AmrGraph
          .unchecked(g.top, concepts :+ (ghost -> Concept.unsafe("island")), g.edges)
      ),
      anyNode.map(n =>
        "inverse-to-literal" -> AmrGraph.unchecked(
          g.top,
          concepts,
          g.edges :+ Edge(
            n,
            SurfaceRole(Role.arg(0), Orientation.Inverse),
            AmrValue.Literal(AmrLiteral.Symbol("-"))
          )
        )
      ),
      anyNode.map(n =>
        "invalid-literal" -> AmrGraph.unchecked(
          g.top,
          concepts,
          g.edges :+ Edge(
            n,
            SurfaceRole.direct(Role.standard("quant")),
            AmrValue.Literal(AmrLiteral.Number("12x"))
          )
        )
      )
    )

  // ---- PENMAN trees --------------------------------------------------------------------

  val marker: Gen[AlignmentMarker] = for
    prefix <- Gen.option(Gen.const("e"))
    idx <- Gen.nonEmptyListOf(Gen.chooseNum(0, 30))
  yield AlignmentMarker(prefix, idx.toVector)

  val penmanLiteral: Gen[PenmanLiteral] = Gen.oneOf(
    Gen.oneOf("-", "+", "imperative").map(PenmanLiteral.Sym(_)),
    Gen.chooseNum(-9, 999).map(i => PenmanLiteral.Num(i.toString)),
    Gen.oneOf("1.5", "2.0e3", "-0.25").map(PenmanLiteral.Num(_)),
    Gen.oneOf("Paris", "New York", "O\"Brien", "a\\b", "tab\tx").map(PenmanLiteral.Str(_))
  )

  val roleText: Gen[String] = Gen.oneOf(
    Gen.chooseNum(0, 5).map(i => s"ARG$i"),
    Gen.chooseNum(0, 5).map(i => s"ARG$i-of"),
    Gen.oneOf("location", "time", "mod", "op1", "op2", "polarity", "consist-of", "domain")
  )

  /** A tree with fresh variables, nested nodes, reentrant references, literals, and markers. */
  val penmanTree: Gen[PenmanTree] =
    def node(depth: Int, defined: Vector[String], counter: Iterator[Int]): Gen[PenmanNode] =
      val v = s"v${counter.next()}"
      for
        c <- Gen.oneOf(frameConcepts ++ lexicalConcepts)
        cm <- Gen.option(marker)
        k <- if depth >= 3 then Gen.const(0) else Gen.chooseNum(0, 3)
        branches <- Gen.sequence[Vector[Branch], Branch]((0 until k).map { _ =>
          for
            r <- roleText
            rm <- Gen.option(marker)
            t <- Gen.frequency(
              3 -> node(depth + 1, defined :+ v, counter).map(Target.NodeTarget(_)),
              1 -> Gen
                .zip(penmanLiteral, Gen.option(marker))
                .map((l, m) => Target.LiteralTarget(l, m)),
              (if defined.nonEmpty then 1 else 0) -> Gen
                .zip(Gen.oneOf(defined :+ v), Gen.option(marker))
                .map((d, m) => Target.VarRef(Variable(d), m))
            )
          yield Branch(RoleToken(r, rm), t)
        })
      yield PenmanNode(Variable(v), Some(ConceptToken(c, cm)), branches)
    for
      root <- Gen.const(()).flatMap(_ => node(0, Vector.empty, Iterator.from(0)))
      headers <- Gen.listOf(
        Gen.oneOf(
          Gen
            .zip(
              Gen.oneOf("id", "snt", "save-date"),
              Gen.oneOf("x1", "The boy wants to go.", "2026-08-28")
            )
            .map((k, v) => Header.Meta(k, v)),
          Gen.oneOf("a comment", "another one").map(Header.Comment(_))
        )
      )
    yield PenmanTree(headers.toVector, root)
