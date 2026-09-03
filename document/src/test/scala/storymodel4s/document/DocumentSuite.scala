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
  private val story = StoryId.unsafe("story:test")

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
      Credence.unsafeRaw(1.0, ScorerId.unsafe("test-scorer")),
      NonEmptyVector.one(ev),
      Provenance.deterministic("test", Checksum.ofText("doc"))
    )

  /** Ten sentences, each `go(x)`; entity mention `m$i` sits on node `a` of sentence `i`. */
  private val graph10: MentionGraph =
    MentionGraph.of((0 to 9).map(i => sentence(i) -> chart("go", "x"))).toOption.get
  private def mid(s: String) = MentionId.unsafe[EntityK](s)
  private val table: MentionTable[EntityK] =
    MentionTable
      .of[EntityK]((0 to 9).map(i => mid(s"m$i") -> ChartNodeRef(sentence(i), cid("a"))), graph10)
      .fold(e => fail(e.message), identity)

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

  test("mention table: unknown nodes and kind mismatches are typed errors") {
    val missing = MentionTable.of[EntityK](
      Vector(mid("z") -> ChartNodeRef(sentence(42), cid("a"))),
      graph10
    )
    assert(missing.left.exists(_.isInstanceOf[DocumentError.MentionNotInGraph]))
    val predicateAsEntity = MentionTable.of[EntityK](
      Vector(mid("z") -> ChartNodeRef(sentence(0), cid("p"))),
      graph10
    )
    assert(predicateAsEntity.left.exists(_.isInstanceOf[DocumentError.KindMismatch]))
    val entityAsSituation = MentionTable.of[SituationK](
      Vector(MentionId.unsafe[SituationK]("z") -> ChartNodeRef(sentence(0), cid("a"))),
      graph10
    )
    assert(entityAsSituation.left.exists(_.isInstanceOf[DocumentError.KindMismatch]))
    val ok = MentionTable.of[SituationK](
      Vector(MentionId.unsafe[SituationK]("z") -> ChartNodeRef(sentence(0), cid("p"))),
      graph10
    )
    assert(ok.isRight)
  }

  test("coref: exact identity is an equivalence and the quotient is deterministic and typed") {
    val pairs = Vector(mid("m1") -> mid("m2"), mid("m2") -> mid("m3"), mid("m5") -> mid("m4"))
    val p = CorefPartition.fromPairs(story, pairs, table).fold(e => fail(e.message), identity)
    assert(p.same(mid("m1"), mid("m3")))
    assert(p.same(mid("m3"), mid("m1")))
    assert(p.same(mid("m4"), mid("m5")))
    assert(!p.same(mid("m1"), mid("m4")))
    assert(p.same(mid("m9"), mid("m9")))
    val shuffled =
      CorefPartition.fromPairs(story, pairs.reverse.map(_.swap), table).toOption.get
    assertEquals(shuffled, p)
    val q = p.quotient(Vector(mid("m1"), mid("m2"), mid("m9")))
    assertEquals(q(mid("m1")), q(mid("m2")))
    assertNotEquals(q(mid("m1")), q(mid("m9")))
    // canonical ids are content addresses of the sorted members, never caller strings
    assertEquals(
      q(mid("m9")),
      ExactCorefCluster.canonicalFor[EntityK](story, Vector(mid("m9")))
    )
    assertEquals(
      q(mid("m1")),
      ExactCorefCluster.canonicalFor[EntityK](story, Vector(mid("m1"), mid("m2"), mid("m3")))
    )
    assert(q(mid("m9")).value.startsWith("c-entity:"))
  }

  property("coref: transitivity holds for random pair sets") {
    val genPairs = Gen
      .listOf(Gen.zip(Gen.choose(0, 8), Gen.choose(0, 8)))
      .map(
        _.map((a, b) => mid(s"m$a") -> mid(s"m$b"))
      )
    forAll(genPairs) { pairs =>
      val p = CorefPartition.fromPairs(story, pairs, table).toOption.get
      val ms = (0 to 8).map(i => mid(s"m$i"))
      ms.forall(a =>
        ms.forall(b => ms.forall(c => !(p.same(a, b) && p.same(b, c)) || p.same(a, c)))
      )
    }
  }

  test("coref: overlapping clusters, unknown mentions, and forged canonicals are rejected") {
    val c1 = ExactCorefCluster.of(story, NonEmptySet.of(mid("m0"), mid("m1")), table).toOption.get
    val c2 = ExactCorefCluster.of(story, NonEmptySet.of(mid("m1"), mid("m2")), table).toOption.get
    assert(CorefPartition.of(story, Vector(c1, c2)).isLeft)
    assert(ExactCorefCluster.of(story, NonEmptySet.of(mid("m0"), mid("nope")), table).isLeft)
    val forged = ExactCorefCluster.checked(
      story,
      NonEmptySet.of(mid("m0"), mid("m1")),
      CanonicalId.unsafe[EntityK]("ent:mine"),
      table
    )
    assert(forged.left.exists(_.isInstanceOf[DocumentError.CanonicalMismatch]))
    assert(ExactCorefCluster.checked(story, c1.mentions, c1.canonical, table).isRight)
    assert(CorefPartition.fromPairs(story, Vector(mid("m0") -> mid("zz")), table).isLeft)
  }

  test("projection: modes are restricted per kind and direct mentions are kind-checked") {
    val entityNode = ChartNodeRef(sentence(0), cid("a"))
    val predNode = ChartNodeRef(sentence(0), cid("p"))
    val ent = CanonicalId.unsafe[EntityK]("ent:x")
    val sit = CanonicalId.unsafe[SituationK]("sit:x")
    assert(
      Projection
        .of(NonEmptySet.one(entityNode), ent, ProjectionMode.EventRealization, meta)
        .left
        .exists(_.isInstanceOf[DocumentError.ModeNotAllowed])
    )
    assert(Projection.of(NonEmptySet.one(entityNode), ent, ProjectionMode.Summary, meta).isRight)
    assert(
      Projection
        .of(NonEmptySet.one(entityNode), sit, ProjectionMode.DirectMention, meta, Some(graph10))
        .left
        .exists(_.isInstanceOf[DocumentError.SourceKindMismatch])
    )
    assert(
      Projection
        .of(NonEmptySet.one(predNode), ent, ProjectionMode.DirectMention, meta, Some(graph10))
        .left
        .exists(_.isInstanceOf[DocumentError.SourceKindMismatch])
    )
    assert(
      Projection
        .of(NonEmptySet.one(predNode), sit, ProjectionMode.DirectMention, meta, Some(graph10))
        .isRight
    )
    // a situation may be summarized from entity-kind nodes (a summary is not a direct mention)
    assert(Projection.of(NonEmptySet.one(entityNode), sit, ProjectionMode.Summary, meta).isRight)
  }

  test("projection index: unknown targets and duplicate direct mentions are violations") {
    val src = ChartNodeRef(sentence(0), cid("p"))
    val p1 = Projection
      .of(
        NonEmptySet.one(src),
        CanonicalId.unsafe[SituationK]("sit:none"),
        ProjectionMode.DirectMention,
        meta
      )
      .toOption
      .get
    val p2 = Projection
      .of(
        NonEmptySet.one(src),
        CanonicalId.unsafe[SituationK]("sit:other"),
        ProjectionMode.DirectMention,
        meta
      )
      .toOption
      .get
    val idx = ProjectionIndex(Vector(p1, p2), Vector.empty)
    val violations = idx.validate(NarrativeGraph.empty, Some(MentionGraph.empty))
    assert(violations.exists(_.isInstanceOf[ProjectionViolation.UnknownSituation]))
    assert(violations.exists(_.isInstanceOf[ProjectionViolation.MultipleDirectMentions]))
    assert(violations.exists(_.isInstanceOf[ProjectionViolation.SourceNotInMentionGraph]))
    val mg = MentionGraph.of(Vector(sentence(0) -> chart("go", "boy"))).toOption.get
    assertEquals(idx.unmapped(mg), Vector(ChartNodeRef(sentence(0), cid("a"))))
  }
