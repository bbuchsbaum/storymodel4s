package storymodel4s.laws

import storymodel4s.story.*

import cats.data.NonEmptyVector
import org.scalacheck.{Arbitrary, Gen}
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}
import storymodel4s.features.{
  Dtype,
  FeatureSpace,
  FeatureTarget,
  FeatureValueSchema,
  SidecarManifest
}
import storymodel4s.features.FeatureRef

/** The shape of a synthetic story model: how many scenes/episodes, which relation families and
  * contexts are present. Every combination builds a *valid* model (see `StoryLaws`).
  */
final case class StoryShape(
    episodes: Int,
    scenesPerEpisode: Int,
    situationsPerScene: Int,
    entities: Int,
    chain: Boolean,
    speechContext: Boolean,
    causal: Boolean,
    goals: Boolean,
    references: Boolean,
    stateChanges: Boolean,
    auxiliaryArc: Boolean,
    boundaryBeliefs: Boolean,
    features: Boolean
):
  def situations: Int = episodes * scenesPerEpisode * situationsPerScene
  def scenes: Int = episodes * scenesPerEpisode

object StoryShape:
  /** The minimal shape the old generator produced: one scene, no extras. */
  def flat(n: Int, m: Int, chain: Boolean): StoryShape =
    StoryShape(1, 1, n, m, chain, false, false, false, false, false, false, false, false)

  val gen: Gen[StoryShape] = for
    episodes <- Gen.chooseNum(1, 2)
    scenes <- Gen.chooseNum(1, 3)
    sits <- Gen.chooseNum(2, 4)
    ents <- Gen.chooseNum(2, 4)
    chain <- Gen.oneOf(true, false)
    speech <- Gen.oneOf(true, false)
    causal <- Gen.oneOf(true, false)
    goals <- Gen.oneOf(true, false)
    refs <- Gen.oneOf(true, false)
    states <- Gen.oneOf(true, false)
    aux <- Gen.oneOf(true, false)
    beliefs <- Gen.oneOf(true, false)
    feats <- Gen.oneOf(true, false)
  yield StoryShape(
    episodes,
    scenes,
    sits,
    ents,
    chain,
    speech,
    causal,
    goals,
    refs,
    states,
    aux,
    beliefs,
    feats
  )

/** Programmatic construction of valid story models, and ScalaCheck generators over them. */
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
      shape: StoryShape,
      source: StorySource,
      atlas: SurfaceAtlas,
      world: ContextId,
      speech: Option[ContextId],
      root: SegmentId,
      episodes: Vector[SegmentId],
      scenes: Vector[SegmentId],
      entities: Vector[EntityId],
      situations: Vector[SituationId],
      graph: NarrativeGraph,
      hierarchy: NarrativeHierarchy,
      featureSpaces: Map[FeatureSpaceId, FeatureSpace[?]],
      sidecars: Map[FeatureSpaceId, SidecarManifest],
      featureRefs: Vector[FeatureRef]
  ):
    /** Backwards-compatible alias: the first scene. */
    def scene: SegmentId = scenes.head

    /** Sentence span of situation `i`. */
    def span(i: Int): SpanSet =
      SpanSet.one(SpanRef(Some(atlas.sentences(i).id), atlas.sentences(i).span))

    def draft(
        graph: NarrativeGraph = graph,
        hierarchy: NarrativeHierarchy = hierarchy,
        featureSpaces: Map[FeatureSpaceId, FeatureSpace[?]] = featureSpaces,
        sidecars: Map[FeatureSpaceId, SidecarManifest] = sidecars,
        featureRefs: Vector[FeatureRef] = featureRefs,
        descriptors: Vector[DescriptorClaim] = Vector.empty,
        sensoryProfiles: Map[SituationId, Vector[SensoryProfile]] = Map.empty,
        trajectory: Option[DiscourseTrajectory] = None,
        source: StorySource = source,
        atlas: SurfaceAtlas = atlas
    ): StoryModel[ModelStatus.Draft] =
      StoryModel.draft(
        source,
        atlas,
        graph,
        hierarchy,
        trajectory.getOrElse(DiscourseTrajectory.derive(graph, hierarchy, atlas)),
        featureSpaces,
        sidecars,
        featureRefs,
        descriptors,
        sensoryProfiles = sensoryProfiles
      )

  /** One scene under one root, `n` situations, `m` entities, an optional Before chain. */
  def build(n: Int, m: Int, chain: Boolean = true): Built =
    build(StoryShape.flat(n, m, chain))

  /** Build a valid model of the given shape.
    *
    * Layout: root (level 3) → episodes (level 2) → scenes (level 1) → situations. Situation `i`
    * anchors sentence `i`; every third situation is a state. With `speechContext`, the last
    * situation of every scene lives in a `Speech(entity 0)` context nested under the world, and its
    * chain edge is scoped to that context. Causal/goal/reference/state-change edges join
    * consecutive world situations of a scene; an auxiliary `GoalArc` membership puts the first
    * situation of each scene under the *last* scene's parent episode with graded weight.
    */
  def build(shape: StoryShape): Built =
    require(shape.situations >= 1 && shape.entities >= 1)
    val n = shape.situations
    val src = source(n)
    val atlas = SurfaceAnalyzer.analyze(src)
    def sp(i: Int): SpanSet =
      SpanSet.one(SpanRef(Some(atlas.sentences(i).id), atlas.sentences(i).span))
    def spanOf(is: Seq[Int]): SpanSet = SpanSet.of(is.map(i => sp(i).refs.head)).get
    val world = ContextId.unsafe("ctx:world")
    val speech = Option.when(shape.speechContext)(ContextId.unsafe("ctx:speech"))
    val root = SegmentId.unsafe("seg:root")
    val episodes = (0 until shape.episodes).toVector.map(i => SegmentId.unsafe(s"seg:ep:$i"))
    val scenes = (0 until shape.scenes).toVector.map(i => SegmentId.unsafe(s"seg:scene:$i"))
    val ents = (0 until shape.entities).toVector.map(i => EntityId.unsafe(s"ent:$i"))
    val sits = (0 until n).toVector.map(i => SituationId.unsafe(s"sit:$i"))
    def sceneOf(i: Int): Int = i / shape.situationsPerScene
    def episodeOf(scene: Int): Int = scene / shape.scenesPerEpisode
    def sceneMembers(s: Int): Vector[Int] =
      (s * shape.situationsPerScene until (s + 1) * shape.situationsPerScene).toVector
    def isLastOfScene(i: Int): Boolean = (i + 1) % shape.situationsPerScene == 0
    def contextOf(i: Int): ContextId =
      speech.filter(_ => isLastOfScene(i) && shape.situationsPerScene > 1).getOrElse(world)
    def isState(i: Int): Boolean = i % 3 == 2

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
        speech.toVector.map(c =>
          ScopedAttribute(
            c,
            "role",
            "speaker",
            meta(s"ent:$i:attr", EpistemicStatus.Hypothesized, None)
          )
        ),
        sp(0),
        meta(s"ent:$i", EpistemicStatus.SurfaceExplicit, Some(sp(0)))
      )
    }.toMap
    val sitNodes = sits.zipWithIndex.map { (id, i) =>
      val node: SituationNode =
        if isState(i) then
          SituationNode.State(
            StateNode(
              id,
              Predicate(s"state$i", None, s"state $i"),
              s"state $i",
              contextOf(i),
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
              contextOf(i),
              Polarity.Positive,
              if contextOf(i) == world then Modality.Asserted else Modality.Reported,
              None,
              sp(i),
              NonEmptyVector.one(MentionId.unsafe[SituationK](s"m:sit:$i")),
              meta(s"sit:$i", EpistemicStatus.SurfaceExplicit, Some(sp(i)))
            )
          )
      id -> node
    }.toMap
    val participants = sits.zipWithIndex.map { (s, i) =>
      ParticipantEdge(
        s,
        ParticipantRole.Agent,
        ents(i % shape.entities),
        meta(s"p:$i", EpistemicStatus.SurfaceExplicit, Some(sp(i)))
      )
    }
    // chain within each scene; an edge must lie within BOTH endpoint contexts, so a world→speech
    // step is scoped to the speech context (the narrower one), never to the world
    val temporal =
      if shape.chain then
        (0 until n - 1).toVector.filter(i => sceneOf(i) == sceneOf(i + 1)).map { i =>
          val ctx =
            if contextOf(i) == world && contextOf(i + 1) == world then world else speech.get
          TemporalEdge(
            sits(i),
            TemporalRelation.Before,
            sits(i + 1),
            ctx,
            meta(s"t:$i", EpistemicStatus.SurfaceExplicit, Some(sp(i)))
          )
        }
      else Vector.empty
    def worldPairs: Vector[(Int, Int)] =
      (0 until n - 1).toVector
        .filter(i =>
          sceneOf(i) == sceneOf(i + 1) && contextOf(i) == world && contextOf(i + 1) == world
        )
        .map(i => (i, i + 1))
    val causal =
      if shape.causal then
        worldPairs.map((a, b) =>
          CausalEdge(
            sits(a),
            CausalRelation.Enables,
            sits(b),
            meta(s"cause:$a", EpistemicStatus.LinguisticallyEntailed, Some(sp(a)))
          )
        )
      else Vector.empty
    val goals =
      if shape.goals then
        worldPairs.headOption.toVector.map((a, b) =>
          GoalEdge(
            sits(a),
            GoalRelation.Motivates,
            sits(b),
            meta(s"goal:$a", EpistemicStatus.WorldKnowledgeInferred, None)
          )
        )
      else Vector.empty
    val references =
      if shape.references && n >= 2 then
        Vector(
          ReferenceEdge(
            sits(n - 1),
            NarrativeReference.Retrospective,
            sits(0),
            meta("ref:last", EpistemicStatus.StructurallyDerived, None)
          )
        )
      else Vector.empty
    val stateChanges =
      if shape.stateChanges then
        worldPairs.collect {
          case (a, b) if !isState(a) && isState(b) =>
            StateChangeEdge(
              sits(a),
              StateChangeKind.Initiates,
              sits(b),
              meta(s"sc:$a", EpistemicStatus.LinguisticallyEntailed, Some(sp(a)))
            )
        }
      else Vector.empty
    val contexts = Map(
      world -> ContextFrame(
        world,
        None,
        ContextKind.NarratedWorld,
        spanOf(0 until n),
        meta("ctx:world", EpistemicStatus.SurfaceExplicit, Some(sp(0)))
      )
    ) ++ speech.map(c =>
      c -> ContextFrame(
        c,
        Some(world),
        ContextKind.Speech(ents(0)),
        spanOf((0 until n).filter(i => contextOf(i) == c)),
        meta("ctx:speech", EpistemicStatus.SurfaceExplicit, Some(sp(0)))
      )
    )
    val segments = Map(
      root -> SegmentNode(
        root,
        SegmentKind.Story,
        3,
        Resolved("root", meta("seg:root", EpistemicStatus.HumanAdjudicated, None), Vector.empty),
        spanOf(0 until n)
      )
    ) ++ episodes.zipWithIndex.map { (id, e) =>
      val members = (0 until shape.scenes).filter(s => episodeOf(s) == e).flatMap(sceneMembers)
      id -> SegmentNode(
        id,
        SegmentKind.Episode,
        2,
        Resolved(
          s"episode $e",
          meta(s"seg:ep:$e", EpistemicStatus.HumanAdjudicated, None),
          Vector.empty
        ),
        spanOf(members)
      )
    } ++ scenes.zipWithIndex.map { (id, s) =>
      id -> SegmentNode(
        id,
        SegmentKind.Scene,
        1,
        Resolved(
          s"scene $s",
          meta(s"seg:scene:$s", EpistemicStatus.HumanAdjudicated, None),
          Vector.empty
        ),
        spanOf(sceneMembers(s))
      )
    }
    val containment =
      episodes.zipWithIndex.map { (id, e) =>
        ContainmentEdge(
          NarrativeMember.Segment(id),
          root,
          HierarchyKind.PrimarySegmentation,
          1.0,
          meta(s"cont:ep:$e", EpistemicStatus.HumanAdjudicated, None)
        )
      } ++ scenes.zipWithIndex.map { (id, s) =>
        ContainmentEdge(
          NarrativeMember.Segment(id),
          episodes(episodeOf(s)),
          HierarchyKind.PrimarySegmentation,
          1.0,
          meta(s"cont:scene:$s", EpistemicStatus.HumanAdjudicated, None)
        )
      } ++ sits.zipWithIndex.map { (s, i) =>
        ContainmentEdge(
          NarrativeMember.Situation(s),
          scenes(sceneOf(i)),
          HierarchyKind.PrimarySegmentation,
          1.0,
          meta(s"cont:$i", EpistemicStatus.HumanAdjudicated, None)
        )
      } ++ (if shape.auxiliaryArc then
              (0 until shape.scenes).toVector.map { s =>
                ContainmentEdge(
                  NarrativeMember.Situation(sits(sceneMembers(s).head)),
                  episodes.last,
                  HierarchyKind.GoalArc,
                  0.7,
                  meta(s"arc:$s", EpistemicStatus.Hypothesized, None)
                )
              }
            else Vector.empty)
    val beliefs =
      if shape.boundaryBeliefs then
        (0 until shape.scenes - 1).toVector.map { s =>
          val last = sceneMembers(s).last
          BoundaryBelief(
            atlas.sentences(last).id,
            1,
            0.8,
            None,
            NonEmptyVector.one(
              Evidence(EvidenceId.unsafe(s"e:boundary:$s"), Some(sp(last)), Set.empty, fp, stage)
            )
          )
        }
      else Vector.empty
    val spaceId = FeatureSpaceId.unsafe("space:semantic")
    val (spaces, sidecars, refs) =
      if shape.features then
        val space =
          FeatureSpace[Vector[Double]](
            spaceId,
            "semantic",
            FeatureValueSchema.Vector(4),
            None,
            fp,
            true
          )
        val manifest = SidecarManifest(spaceId, 4, n, Dtype.Float32, Checksum.ofText("rows"))
        val rs = sits.zipWithIndex.map((s, i) => FeatureRef(FeatureTarget.Situation(s), spaceId, i))
        (Map(spaceId -> space), Map(spaceId -> manifest), rs)
      else (Map.empty, Map.empty, Vector.empty)
    val graph = NarrativeGraph(
      entityNodes,
      sitNodes,
      segments,
      contexts,
      RelationLayers(participants, temporal, causal, goals, stateChanges, references)
    )
    Built(
      shape,
      src,
      atlas,
      world,
      speech,
      root,
      episodes,
      scenes,
      ents,
      sits,
      graph,
      NarrativeHierarchy(containment, beliefs),
      spaces,
      sidecars,
      refs
    )

object StoryGens:
  /** Rich valid models of every shape; always at least two situations. */
  val built: Gen[StorySmall.Built] = StoryShape.gen.map(StorySmall.build)

  /** The original flat family (one scene). */
  val flat: Gen[StorySmall.Built] = for
    n <- Gen.chooseNum(2, 8)
    m <- Gen.chooseNum(1, 4)
    chain <- Gen.oneOf(true, false)
  yield StorySmall.build(n, m, chain)

  val temporalRelation: Gen[TemporalRelation] = Gen.oneOf(TemporalRelation.values.toSeq)

  val canonicalRelation: Gen[TemporalRelation] =
    Gen.oneOf(TemporalRelation.values.filter(_.isCanonical).toSeq)

  given Arbitrary[StorySmall.Built] = Arbitrary(Gen.frequency(3 -> built, 1 -> flat))
  given Arbitrary[TemporalRelation] = Arbitrary(temporalRelation)
