package storymodel4s.acquire

import cats.data.NonEmptyVector
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.*
import storymodel4s.core.*

/** Reference in-memory graph used to check the patch laws. */
final case class SimpleGraph(
    nodes: Map[String, String],
    edges: Set[(String, String, String)],
    focus: Option[String],
    annotations: Map[(String, String), String],
    contexts: Map[String, String],
    merged: Vector[Set[String]]
)

object SimpleGraph:
  val empty: SimpleGraph =
    SimpleGraph(Map.empty, Set.empty, None, Map.empty, Map.empty, Vector.empty)

  given PatchApplier[SimpleGraph, String, String] with
    def applyOp(
        g: SimpleGraph,
        i: Int,
        op: PatchOp[String, String],
        r: TempResolution
    ): Either[PatchError, (SimpleGraph, TempResolution)] =
      def node(ref: NodeRef): Either[PatchError, String] =
        r.resolve(ref) match
          case Some(id) if g.nodes.contains(id) => Right(id)
          case Some(id)                         => Left(PatchError.UnknownNode(i, id))
          case None                             =>
            ref match
              case NodeRef.Temp(t)     => Left(PatchError.UnresolvedTemp(i, t))
              case NodeRef.Existing(x) => Left(PatchError.UnknownNode(i, x))
      op match
        case PatchOp.AddNode(t, payload, _) =>
          val id = s"n${g.nodes.size + 1}-${t.value}"
          Right((g.copy(nodes = g.nodes.updated(id, payload)), r.bind(t, id)))
        case PatchOp.AddEdge(f, l, t, _) =>
          for a <- node(f); b <- node(t)
          yield (g.copy(edges = g.edges + ((a, l, b))), r)
        case PatchOp.RemoveEdge(f, l, t, _) =>
          for
            a <- node(f)
            b <- node(t)
            _ <-
              if g.edges.contains((a, l, b)) then Right(())
              else Left(PatchError.InvalidOp(i, "edge absent"))
          yield (g.copy(edges = g.edges - ((a, l, b))), r)
        case PatchOp.SetFocus(n, _)          => node(n).map(id => (g.copy(focus = Some(id)), r))
        case PatchOp.MergeMentions(ms, _, _) =>
          ms.toVector.traverseE(node).map(ids => (g.copy(merged = g.merged :+ ids.toSet), r))
        case PatchOp.ProposeRelation(rel, f, t, _) =>
          for a <- node(f); b <- node(t)
          yield (g.copy(edges = g.edges + ((a, s"rel:$rel", b))), r)
        case PatchOp.SetContext(n, c, _) =>
          for a <- node(n); b <- node(c)
          yield (g.copy(contexts = g.contexts.updated(a, b)), r)
        case PatchOp.SplitMention(m, into, _, _) =>
          node(m).map { id =>
            into.toVector.zipWithIndex.foldLeft((g, r)) { case ((g1, r1), (t, k)) =>
              val nid = s"$id/$k-${t.value}"
              (g1.copy(nodes = g1.nodes.updated(nid, g.nodes(id))), r1.bind(t, nid))
            }
          }
        case PatchOp.Annotate(n, k, v, _) =>
          node(n).map(id => (g.copy(annotations = g.annotations.updated((id, k.render), v)), r))

  extension [A, E, B](v: Vector[A])
    private def traverseE(f: A => Either[E, B]): Either[E, Vector[B]] =
      v.foldLeft[Either[E, Vector[B]]](Right(Vector.empty))((acc, a) =>
        acc.flatMap(bs => f(a).map(bs :+ _))
      )

class PatchSuite extends ScalaCheckSuite:
  import Fixtures.*
  private val prov = Provenance.deterministic("0.1.0", cfg)
  private def patch(id: String, ops: PatchOp[String, String]*): Patch[String, String] =
    Patch(PatchId.unsafe(id), ops.toVector, prov)
  private val base: SimpleGraph =
    SimpleGraph.empty.copy(nodes = Map("a" -> "A", "b" -> "B"))
  private val ex = NodeRef.Existing
  private val t1 = TempId.unsafe("t1")

  test("empty patch is identity"):
    assertEquals(PatchApplier.applyPatch(base, patch("p0")), Right(base))

  test("a failing patch leaves the graph untouched and reports the op index"):
    val p = patch(
      "p1",
      PatchOp.AddNode(t1, "C", Vector.empty),
      PatchOp.AddEdge(NodeRef.Temp(t1), "arg0", ex("a"), Vector.empty),
      PatchOp.AddEdge(ex("a"), "arg1", ex("missing"), Vector.empty)
    )
    PatchApplier.applyPatch(base, p) match
      case Left(PatchError.UnknownNode(2, "missing")) => ()
      case other                                      => fail(s"unexpected $other")
    // immutability: base is what it was
    assertEquals(base.nodes.keySet, Set("a", "b"))

  test("temporaries must be introduced before use and only once"):
    val useFirst = patch("p2", PatchOp.SetFocus(NodeRef.Temp(t1), Vector.empty))
    assert(useFirst.wellFormed.isLeft)
    val twice = patch(
      "p3",
      PatchOp.AddNode(t1, "C", Vector.empty),
      PatchOp.AddNode(t1, "D", Vector.empty)
    )
    twice.wellFormed match
      case Left(PatchError.DuplicateTemp(1, _)) => ()
      case other                                => fail(s"unexpected $other")

  test("temporaries resolve to assigned identifiers across operations"):
    val p = patch(
      "p4",
      PatchOp.AddNode(t1, "C", Vector.empty),
      PatchOp.AddEdge(ex("a"), "arg0", NodeRef.Temp(t1), Vector.empty),
      PatchOp.SetFocus(NodeRef.Temp(t1), Vector.empty),
      PatchOp.Annotate(NodeRef.Temp(t1), AnnotationKey.Note, "v", Vector.empty),
      PatchOp.SetContext(NodeRef.Temp(t1), ex("b"), Vector.empty),
      PatchOp.MergeMentions(NonEmptyVector.of(ex("a"), NodeRef.Temp(t1)), None, Vector.empty),
      PatchOp.ProposeRelation("before", ex("a"), ex("b"), Vector.empty)
    )
    val g = PatchApplier.applyPatch(base, p).toOption.get
    assertEquals(g.nodes.size, 3)
    val c = g.focus.get
    assert(g.edges.contains(("a", "arg0", c)))
    assertEquals(g.annotations((c, AnnotationKey.Note.render)), "v")
    assertEquals(g.contexts(c), "b")
    assertEquals(g.merged, Vector(Set("a", c)))
    assert(g.edges.contains(("a", "rel:before", "b")))

  test("remove edge fails when absent and succeeds when present"):
    val add = patch("p5", PatchOp.AddEdge(ex("a"), "x", ex("b"), Vector.empty))
    val rm = patch("p6", PatchOp.RemoveEdge(ex("a"), "x", ex("b"), Vector.empty))
    assert(PatchApplier.applyPatch(base, rm).isLeft)
    val g = PatchApplier.applyAll(base, Vector(add, rm)).toOption.get
    assertEquals(g.edges, Set.empty)

  // --- properties over non-conflicting patches ------------------------------------------------

  private val opGen: Gen[PatchOp[String, String]] =
    val existing = Gen.oneOf("a", "b").map(ex(_))
    Gen.oneOf(
      for f <- existing; t <- existing; l <- Gen.oneOf("arg0", "arg1", "mod")
      yield PatchOp.AddEdge(f, l, t, Vector.empty),
      existing.map(n => PatchOp.SetFocus(n, Vector.empty)),
      for n <- existing; k <- Gen.oneOf("k1", "k2"); v <- Gen.oneOf("v1", "v2")
      yield PatchOp.Annotate(n, AnnotationKey.Custom("t", k), v, Vector.empty),
      for n <- existing; c <- existing yield PatchOp.SetContext(n, c, Vector.empty)
    )
  private val patchGen: Gen[Patch[String, String]] = for
    n <- Gen.chooseNum(0, 4)
    ops <- Gen.listOfN(n, opGen)
    id <- Gen.chooseNum(1, 1000)
  yield Patch(PatchId.unsafe(s"g$id"), ops.toVector, prov)
  given Arbitrary[Patch[String, String]] = Arbitrary(patchGen)

  property("composition is associative for non-conflicting patches"):
    forAll { (p1: Patch[String, String], p2: Patch[String, String], p3: Patch[String, String]) =>
      val left = PatchApplier.applyPatch(base, (p1 ++ p2) ++ p3)
      val right = PatchApplier.applyPatch(base, p1 ++ (p2 ++ p3))
      left == right && left == PatchApplier.applyAll(base, Vector(p1, p2, p3))
    }

  property("empty patch is a left and right identity"):
    forAll { (p: Patch[String, String]) =>
      val e = Patch.empty[String, String](PatchId.unsafe("e"), prov)
      PatchApplier.applyPatch(base, e ++ p) == PatchApplier.applyPatch(base, p) &&
      PatchApplier.applyPatch(base, p ++ e) == PatchApplier.applyPatch(base, p)
    }

  test("composition keeps per-operation attribution and merges provider calls"):
    val callA = Fixtures.callFor("parser")
    val callB = Fixtures.callFor("agent")
    val pa: Patch[String, String] =
      Patch(
        PatchId.unsafe("pa"),
        Vector(PatchOp.SetFocus(ex("a"), Vector.empty)),
        prov.copy(calls = Vector(callA))
      )
    val pb: Patch[String, String] =
      Patch(
        PatchId.unsafe("pb"),
        Vector(PatchOp.SetFocus(ex("b"), Vector.empty)),
        prov.copy(calls = Vector(callB))
      )
    val c = pa ++ pb
    assertEquals(c.origins, Vector(PatchId.unsafe("pa"), PatchId.unsafe("pb")))
    assertEquals(c.opsFrom(PatchId.unsafe("pa")), pa.ops)
    assertEquals(c.provenance.calls, Vector(callA, callB))
    val e = Patch.empty[String, String](PatchId.unsafe("e"), prov)
    assertEquals((pa ++ e).id, pa.id)
    assertEquals((e ++ pa).id, pa.id)

  test("split introduces temporaries and records what it supersedes"):
    val claim = ClaimId.unsafe("c-old")
    val t2 = TempId.unsafe("t2")
    val p = patch(
      "split",
      PatchOp.SplitMention(ex("a"), NonEmptyVector.of(t1, t2), Some(claim), Vector.empty),
      PatchOp.SetFocus(NodeRef.Temp(t2), Vector.empty)
    )
    assertEquals(p.tempIds, Vector(t1, t2))
    assertEquals(p.supersedes, Vector(claim))
    val g = PatchApplier.applyPatch(base, p).toOption.get
    assertEquals(g.nodes.size, 4)
    assert(g.focus.exists(_.contains("t2")))
    val dup =
      patch("dup", PatchOp.SplitMention(ex("a"), NonEmptyVector.of(t1, t1), None, Vector.empty))
    assert(PatchApplier.applyPatch(base, dup).isLeft)
