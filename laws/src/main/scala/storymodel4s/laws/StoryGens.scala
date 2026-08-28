package storymodel4s.laws

import storymodel4s.story.*

import cats.data.NonEmptyVector
import org.scalacheck.{Arbitrary, Gen}
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}

/** Programmatic construction of small valid story models, and ScalaCheck generators over them. */
object StorySmall:
  val fp: Fingerprint = Fingerprint.unsafe("test:rules:0")
  val stage: StageId = StageId.unsafe("test")
  val prov: Provenance = Provenance.deterministic("test", Checksum.ofText("test"))

  def meta(key: String, status: EpistemicStatus, spans: Option[SpanSet]): ClaimMeta =
    ClaimMeta.unsafe(
      ClaimId.unsafe(s"c:$key"),
      status,
      Credence.unsafeRaw(1.0),
      NonEmptyVector.one(Evidence(EvidenceId.unsafe(s"e:$key"), spans, Set.empty, fp, stage)),
      prov
    )

  /** A synthetic story with `n` sentences, one paragraph. */
  def source(n: Int): StorySource =
    StorySource
      .fromText((0 until n).map(i => s"Sentence number $i happens here.").mkString(" "), Some("t"))
      .toOption
      .get

  final case class Built(
      source: StorySource,
      atlas: SurfaceAtlas,
      world: ContextId,
      root: SegmentId,
      scene: SegmentId,
      entities: Vector[EntityId],
      situations: Vector[SituationId],
      graph: NarrativeGraph,
      hierarchy: NarrativeHierarchy
  ):
    def draft(
        graph: NarrativeGraph = graph,
        hierarchy: NarrativeHierarchy = hierarchy,
        featureSpaces: Map[FeatureSpaceId, FeatureSpace[?]] = Map.empty,
        featureRefs: Vector[FeatureRef] = Vector.empty
    ): StoryModel[ModelStatus.Draft] =
      StoryModel.draft(
        source,
        atlas,
        graph,
        hierarchy,
        DiscourseTrajectory.derive(graph, hierarchy, atlas),
        featureSpaces,
        featureRefs = featureRefs
      )

  /** `n` situations in one scene under one root, `m` entities, a Before chain, participants. */
  def build(n: Int, m: Int, chain: Boolean = true): Built =
    require(n >= 1 && m >= 1)
    val src = source(n)
    val atlas = SurfaceAnalyzer.analyze(src)
    def sp(i: Int): SpanSet =
      SpanSet.one(SpanRef(Some(atlas.sentences(i).id), atlas.sentences(i).span))
    val world = ContextId.unsafe("ctx:world")
    val root = SegmentId.unsafe("seg:root")
    val scene = SegmentId.unsafe("seg:scene")
    val ents = (0 until m).toVector.map(i => EntityId.unsafe(s"ent:$i"))
    val sits = (0 until n).toVector.map(i => SituationId.unsafe(s"sit:$i"))
    val entityNodes = ents.zipWithIndex.map { (id, i) =>
      id -> EntityNode(
        id,
        Resolved(
          s"entity $i",
          meta(s"ent:$i:label", EpistemicStatus.SurfaceExplicit, Some(sp(0))),
          Vector.empty
        ),
        EntityType.Person,
        NonEmptyVector.one(MentionId.unsafe[EntityK](s"m:ent:$i")),
        Vector.empty,
        sp(0),
        meta(s"ent:$i", EpistemicStatus.SurfaceExplicit, Some(sp(0)))
      )
    }.toMap
    val sitNodes = sits.zipWithIndex.map { (id, i) =>
      val node: SituationNode =
        if i % 3 == 2 then
          SituationNode.State(
            StateNode(
              id,
              Predicate(s"state$i", None, s"state $i"),
              s"state $i",
              world,
              Polarity.Positive,
              Modality.Asserted,
              sp(i),
              NonEmptyVector.one(MentionId.unsafe[SituationK](s"m:sit:$i")),
              meta(s"sit:$i", EpistemicStatus.SurfaceExplicit, Some(sp(i)))
            )
          )
        else
          SituationNode.Event(
            EventNode(
              id,
              Predicate(s"event$i", None, s"event $i"),
              s"event $i",
              world,
              Polarity.Positive,
              Modality.Asserted,
              None,
              sp(i),
              NonEmptyVector.one(MentionId.unsafe[SituationK](s"m:sit:$i")),
              meta(s"sit:$i", EpistemicStatus.SurfaceExplicit, Some(sp(i)))
            )
          )
      id -> node
    }.toMap
    val participants = sits.zipWithIndex.map { (s, i) =>
      val e = ents(i % m)
      ParticipantEdge(
        s,
        ParticipantRole.Agent,
        e,
        meta(s"p:$i", EpistemicStatus.SurfaceExplicit, Some(sp(i)))
      )
    }
    val temporal =
      if chain then
        sits.zip(sits.drop(1)).zipWithIndex.map { case ((a, b), i) =>
          TemporalEdge(
            a,
            TemporalRelation.Before,
            b,
            world,
            meta(s"t:$i", EpistemicStatus.SurfaceExplicit, Some(sp(i)))
          )
        }
      else Vector.empty
    val contexts = Map(
      world -> ContextFrame(
        world,
        None,
        ContextKind.NarratedWorld,
        sp(0),
        meta("ctx:world", EpistemicStatus.SurfaceExplicit, Some(sp(0)))
      )
    )
    val allSpan = SpanSet.of((0 until n).map(i => sp(i).refs.head)).get
    val segments = Map(
      root -> SegmentNode(
        root,
        SegmentKind.Story,
        2,
        Resolved("root", meta("seg:root", EpistemicStatus.HumanAdjudicated, None), Vector.empty),
        allSpan
      ),
      scene -> SegmentNode(
        scene,
        SegmentKind.Scene,
        1,
        Resolved("scene", meta("seg:scene", EpistemicStatus.HumanAdjudicated, None), Vector.empty),
        allSpan
      )
    )
    val containment =
      ContainmentEdge(
        NarrativeMember.Segment(scene),
        root,
        HierarchyKind.PrimarySegmentation,
        1.0,
        meta("cont:scene", EpistemicStatus.HumanAdjudicated, None)
      ) +:
        sits.zipWithIndex.map { (s, i) =>
          ContainmentEdge(
            NarrativeMember.Situation(s),
            scene,
            HierarchyKind.PrimarySegmentation,
            1.0,
            meta(s"cont:$i", EpistemicStatus.HumanAdjudicated, None)
          )
        }
    val graph = NarrativeGraph(
      entityNodes,
      sitNodes,
      segments,
      contexts,
      RelationLayers(participants, temporal, Vector.empty, Vector.empty, Vector.empty, Vector.empty)
    )
    Built(
      src,
      atlas,
      world,
      root,
      scene,
      ents,
      sits,
      graph,
      NarrativeHierarchy(containment, Vector.empty)
    )

object StoryGens:
  val built: Gen[StorySmall.Built] = for
    n <- Gen.chooseNum(1, 8)
    m <- Gen.chooseNum(1, 4)
    chain <- Gen.oneOf(true, false)
  yield StorySmall.build(n, m, chain)

  val temporalRelation: Gen[TemporalRelation] = Gen.oneOf(TemporalRelation.values.toSeq)

  val canonicalRelation: Gen[TemporalRelation] =
    Gen.oneOf(TemporalRelation.values.filter(_.isCanonical).toSeq)

  given Arbitrary[StorySmall.Built] = Arbitrary(built)
  given Arbitrary[TemporalRelation] = Arbitrary(temporalRelation)
