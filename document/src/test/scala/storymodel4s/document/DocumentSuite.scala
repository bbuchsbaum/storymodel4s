package storymodel4s.document

import cats.data.{NonEmptySet, NonEmptyVector}
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}
import storymodel4s.proposition.*
import storymodel4s.story.NarrativeGraph

/** Composition substrate laws: disjoint union, exact-coreference partition, projection index. */
class DocumentSuite extends ScalaCheckSuite:

  private def cid(s: String) = ConceptId.unsafe(s)
  private def sentence(n: Int) = SurfaceUnitId.unsafe(s"sent:$n")

  /** A tiny checked chart: `pred(agent)`. */
  private def chart(pred: String, agent: String): PropositionChart[Checked] =
    val p = cid("p")
    val a = cid("a")
    val c = PropositionChart.unchecked(
      Some(p),
      Map(p -> Concept.predicate(pred), a -> Concept.entity(agent)),
      Vector(PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(a)))
    )
    ChartValidator.check(c).fold(v => fail(v.mkString(",")), identity)

  private val meta: ClaimMeta =
    val fp = Fingerprint.unsafe("test:doc:0")
    val ev = Evidence(
      EvidenceId.unsafe("ev:doc"),
      None,
      Set.empty,
      fp,
      StageId.unsafe("test")
    )
    ClaimMeta.unsafe(
      ClaimId.unsafe("claim:doc"),
      EpistemicStatus.StructurallyDerived,
      Credence.unsafeRaw(1.0),
      NonEmptyVector.one(ev),
      Provenance.deterministic("test", Checksum.ofText("doc"))
    )

  test("mention graph: disjoint union rejects a shared sentence and keeps charts intact") {
    val g1 = MentionGraph.of(Vector(sentence(0) -> chart("go", "boy"))).toOption.get
    val g2 = MentionGraph.of(Vector(sentence(1) -> chart("see", "girl"))).toOption.get
    val u = (g1 ++ g2).toOption.get
    assertEquals(u.size, 2)
    assertEquals(u.nodes.size, 4)
    assert(u.contains(ChartNodeRef(sentence(0), cid("p"))))
    assert((u ++ g1).isLeft)
    assertEquals(u.chart(sentence(0)), g1.chart(sentence(0)))
  }

  property("mention graph: union is associative with the empty graph as identity") {
    val genGraph = Gen.listOfN(3, Gen.choose(0, 50)).map { ns =>
      MentionGraph.of(ns.distinct.map(n => sentence(n) -> chart("go", "x"))).toOption.get
    }
    forAll(genGraph, genGraph, genGraph) { (a, b, c) =>
      val disjoint =
        a.sentences.toSet.intersect(b.sentences.toSet).isEmpty &&
          b.sentences.toSet.intersect(c.sentences.toSet).isEmpty &&
          a.sentences.toSet.intersect(c.sentences.toSet).isEmpty
      if !disjoint then Prop.passed
      else
        val left = (a ++ b).flatMap(_ ++ c)
        val right = (b ++ c).flatMap(a ++ _)
        Prop((left == right) && ((a ++ MentionGraph.empty) == Right(a)))
    }
  }

  private def mid(s: String) = MentionId.unsafe[EntityK](s)
  private def canonicalFor(ms: NonEmptySet[MentionId[EntityK]]) =
    CanonicalId.unsafe[EntityK]("ent:" + ms.toSortedSet.map(_.value).mkString("+"))

  test("coref: exact identity is an equivalence and the quotient is deterministic") {
    val pairs = Vector(mid("m1") -> mid("m2"), mid("m2") -> mid("m3"), mid("m5") -> mid("m4"))
    val p = CorefPartition.fromPairs(pairs, canonicalFor).toOption.get
    assert(p.same(mid("m1"), mid("m3")))
    assert(p.same(mid("m3"), mid("m1")))
    assert(p.same(mid("m4"), mid("m5")))
    assert(!p.same(mid("m1"), mid("m4")))
    assert(p.same(mid("m9"), mid("m9")))
    val shuffled = CorefPartition.fromPairs(pairs.reverse.map(_.swap), canonicalFor).toOption.get
    assertEquals(shuffled, p)
    assertEquals(p.quotient(Vector(mid("m1"), mid("m2"), mid("m9")))(mid("m9")), "m9")
  }

  property("coref: transitivity holds for random pair sets") {
    val genPairs = Gen
      .listOf(Gen.zip(Gen.choose(0, 8), Gen.choose(0, 8)))
      .map(
        _.map((a, b) => mid(s"m$a") -> mid(s"m$b"))
      )
    forAll(genPairs) { pairs =>
      val p = CorefPartition.fromPairs(pairs, canonicalFor).toOption.get
      val ms = (0 to 8).map(i => mid(s"m$i"))
      ms.forall(a =>
        ms.forall(b => ms.forall(c => !(p.same(a, b) && p.same(b, c)) || p.same(a, c)))
      )
    }
  }

  test("coref: overlapping clusters are rejected") {
    val c1 = ExactCorefCluster(NonEmptySet.of(mid("a"), mid("b")), CanonicalId.unsafe[EntityK]("x"))
    val c2 = ExactCorefCluster(NonEmptySet.of(mid("b"), mid("c")), CanonicalId.unsafe[EntityK]("y"))
    assert(CorefPartition.of(Vector(c1, c2)).isLeft)
  }

  test("projection index: unknown targets and duplicate direct mentions are violations") {
    val src = ChartNodeRef(sentence(0), cid("p"))
    val p1 = Projection[SituationK](
      NonEmptySet.one(src),
      CanonicalId.unsafe[SituationK]("sit:none"),
      ProjectionMode.DirectMention,
      meta
    )
    val p2 = p1.copy(target = CanonicalId.unsafe[SituationK]("sit:other"))
    val idx = ProjectionIndex(Vector(p1, p2), Vector.empty)
    val violations = idx.validate(NarrativeGraph.empty, Some(MentionGraph.empty))
    assert(violations.exists(_.isInstanceOf[ProjectionViolation.UnknownSituation]))
    assert(violations.exists(_.isInstanceOf[ProjectionViolation.MultipleDirectMentions]))
    assert(violations.exists(_.isInstanceOf[ProjectionViolation.SourceNotInMentionGraph]))
    val mg = MentionGraph.of(Vector(sentence(0) -> chart("go", "boy"))).toOption.get
    assertEquals(idx.unmapped(mg), Vector(ChartNodeRef(sentence(0), cid("a"))))
  }
