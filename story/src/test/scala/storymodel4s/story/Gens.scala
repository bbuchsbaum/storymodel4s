package storymodel4s.story

import cats.data.NonEmptyVector
import org.scalacheck.{Arbitrary, Gen}
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}

/** Programmatic construction of small valid story models, and ScalaCheck generators over them. */
object Small:
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

/** Minimal mutations of a valid built model, one per validator law introduced by the review fix
  * pass. Each returns a draft that should violate exactly the named law (plus any law it entails).
  */
object Mutations:
  import Small.meta

  private def sp(b: Small.Built, i: Int): SpanSet =
    SpanSet.one(SpanRef(Some(b.atlas.sentences(i).id), b.atlas.sentences(i).span))

  def edge(
      b: Small.Built,
      a: Int,
      rel: TemporalRelation,
      c: Int,
      key: String,
      ctx: Option[ContextId] = None
  ): TemporalEdge =
    TemporalEdge(
      b.situations(a),
      rel,
      b.situations(c),
      ctx.getOrElse(b.world),
      meta(key, EpistemicStatus.Hypothesized, None)
    )

  def withTemporal(b: Small.Built, edges: Vector[TemporalEdge]): StoryModel[ModelStatus.Draft] =
    b.draft(graph = b.graph.copy(relations = b.graph.relations.copy(temporal = edges)))

  /** `A Before B, C During B, C Before A`: consistent pairwise, contradictory as intervals. */
  def intervalContradiction1(b: Small.Built): StoryModel[ModelStatus.Draft] =
    withTemporal(
      b,
      Vector(
        edge(b, 0, TemporalRelation.Before, 1, "ab"),
        edge(b, 2, TemporalRelation.During, 1, "cb"),
        edge(b, 2, TemporalRelation.Before, 0, "ca")
      )
    )

  /** `A Before B, A Starts B`: A cannot both end before B starts and start with B. */
  def intervalContradiction2(b: Small.Built): StoryModel[ModelStatus.Draft] =
    withTemporal(
      b,
      Vector(
        edge(b, 0, TemporalRelation.Before, 1, "ab"),
        edge(b, 0, TemporalRelation.Starts, 1, "as")
      )
    )

  /** A consistent mixed model: `A Overlaps B, B Contains C, C Before D, A Before D`. */
  def intervalConsistent(b: Small.Built): StoryModel[ModelStatus.Draft] =
    withTemporal(
      b,
      Vector(
        edge(b, 0, TemporalRelation.Overlaps, 1, "ab"),
        edge(b, 1, TemporalRelation.Contains, 2, "bc"),
        edge(b, 2, TemporalRelation.Before, 3, "cd"),
        edge(b, 0, TemporalRelation.Before, 3, "ad")
      )
    )

  def duplicateTemporal(b: Small.Built): StoryModel[ModelStatus.Draft] =
    withTemporal(
      b,
      Vector(
        edge(b, 0, TemporalRelation.Before, 1, "x"),
        edge(b, 0, TemporalRelation.Before, 1, "y")
      )
    )

  def unclearPlusStrict(b: Small.Built): StoryModel[ModelStatus.Draft] =
    withTemporal(
      b,
      Vector(
        edge(b, 0, TemporalRelation.Before, 1, "x"),
        edge(b, 0, TemporalRelation.Unclear, 1, "y")
      )
    )

  def overlapsBothWays(b: Small.Built): StoryModel[ModelStatus.Draft] =
    withTemporal(
      b,
      Vector(
        edge(b, 0, TemporalRelation.Overlaps, 1, "x"),
        edge(b, 1, TemporalRelation.Overlaps, 0, "y")
      )
    )

  /** The atlas of a different text. */
  def foreignAtlas(b: Small.Built): StoryModel[ModelStatus.Draft] =
    val other = Small.source(b.situations.size + 1)
    StoryModel.draft(
      b.source,
      SurfaceAnalyzer.analyze(other),
      b.graph,
      b.hierarchy,
      DiscourseTrajectory.derive(b.graph, b.hierarchy, b.atlas)
    )

  /** A scene whose span no longer covers its members. */
  def sceneTooSmall(b: Small.Built): StoryModel[ModelStatus.Draft] =
    val scene = b.graph.segments(b.scene)
    val shrunk = scene.copy(support = sp(b, 0))
    b.draft(graph = b.graph.copy(segments = b.graph.segments.updated(b.scene, shrunk)))

  /** The selected entity label repeated as an alternative. */
  def selfAlternative(b: Small.Built): StoryModel[ModelStatus.Draft] =
    val e = b.graph.entities(b.entities(0))
    val bad =
      e.copy(label = e.label.copy(alternatives = Vector((e.label.value, Credence.unsafeRaw(0.5)))))
    b.draft(graph = b.graph.copy(entities = b.graph.entities.updated(e.id, bad)))

  /** A trajectory with a step missing. */
  def incompleteTrajectory(b: Small.Built): StoryModel[ModelStatus.Draft] =
    val t = DiscourseTrajectory.derive(b.graph, b.hierarchy, b.atlas)
    StoryModel.draft(b.source, b.atlas, b.graph, b.hierarchy, DiscourseTrajectory(t.steps.drop(1)))

  /** A sidecar whose dimension disagrees with the declared vector schema. */
  def dimensionMismatch(b: Small.Built): StoryModel[ModelStatus.Draft] =
    val id = FeatureSpaceId.unsafe("space:v")
    val space = FeatureSpace[Vector[Double]](
      id,
      "vec",
      FeatureValueSchema.Vector(4),
      None,
      Fingerprint.unsafe("test:embed:0"),
      false,
      None
    )
    val manifest =
      SidecarManifest(id, 3, 1, storymodel4s.features.Dtype.Float32, Checksum.ofText("x"))
    StoryModel.draft(
      b.source,
      b.atlas,
      b.graph,
      b.hierarchy,
      DiscourseTrajectory.derive(b.graph, b.hierarchy, b.atlas),
      featureSpaces = Map(id -> space),
      sidecars = Map(id -> manifest)
    )

  /** A group membership cycle. */
  def membershipCycle(b: Small.Built): StoryModel[ModelStatus.Draft] =
    require(b.entities.size >= 2)
    val es = Vector(
      EntityEdge(
        b.entities(0),
        EntityRelation.MemberOf,
        b.entities(1),
        meta("m1", EpistemicStatus.Hypothesized, None)
      ),
      EntityEdge(
        b.entities(1),
        EntityRelation.MemberOf,
        b.entities(0),
        meta("m2", EpistemicStatus.Hypothesized, None)
      )
    )
    b.draft(graph = b.graph.copy(relations = b.graph.relations.copy(entityRelations = es)))

object Gens:
  val built: Gen[Small.Built] = for
    n <- Gen.chooseNum(1, 8)
    m <- Gen.chooseNum(1, 4)
    chain <- Gen.oneOf(true, false)
  yield Small.build(n, m, chain)

  val temporalRelation: Gen[TemporalRelation] = Gen.oneOf(TemporalRelation.values.toSeq)

  val canonicalRelation: Gen[TemporalRelation] =
    Gen.oneOf(TemporalRelation.values.filter(_.isCanonical).toSeq)

  given Arbitrary[Small.Built] = Arbitrary(built)
  given Arbitrary[TemporalRelation] = Arbitrary(temporalRelation)
