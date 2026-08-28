package storymodel4s.story

import cats.data.NonEmptyVector
import storymodel4s.core.*

/** Change between two adjacent atomic units in discourse order. `featureChanges` holds
  * view-specific discontinuities keyed by the declared feature space that produced them; a missing
  * key means that view was not computed, never that the change was zero.
  */
final case class FlowStep(
    from: SituationId,
    to: SituationId,
    featureChanges: Map[FeatureSpaceId, ScoreEstimate],
    entityTurnover: Double,
    locationChange: Option[Boolean],
    contextChange: Boolean,
    worldTime: Resolved[WorldTimeTransition],
    boundaryBeliefs: Vector[BoundaryBelief]
)

/** The multiview discourse trajectory: one step per adjacent pair of situations. */
final case class DiscourseTrajectory(steps: Vector[FlowStep]):
  def stepFrom(id: SituationId): Option[FlowStep] = steps.find(_.from == id)
  def allMeta: Vector[ClaimMeta] = steps.map(_.worldTime.meta)

object DiscourseTrajectory:
  val empty: DiscourseTrajectory = DiscourseTrajectory(Vector.empty)

  val DeriveFingerprint: Fingerprint = Fingerprint.unsafe("storymodel4s:trajectory:derive:0.1")
  val DeriveStage: StageId = StageId.unsafe("trajectory-derive")

  /** Deterministically derive graph-available views: entity turnover (1 − Jaccard of participant
    * sets), context change, and the world-time transition implied by a stored temporal edge between
    * the adjacent situations. Feature and location views stay absent.
    *
    * Transition mapping: `Meets` → `Continues`; `Before` → `JumpForward`; a backward edge →
    * `JumpBackward`; overlap/containment/equality → `SimultaneousThreadSwitch`; `Unclear` or no
    * edge → `Unresolved` with no alternatives. Each derived value is a `StructurallyDerived` claim
    * citing both situations' supports.
    */
  def derive(
      graph: NarrativeGraph,
      hierarchy: NarrativeHierarchy,
      atlas: SurfaceAtlas,
      softwareVersion: String = StoryModel.SchemaVersion
  ): DiscourseTrajectory =
    val order = graph.discourseOrder
    val provenance = Provenance.deterministic(softwareVersion, Checksum.ofText("trajectory-derive"))
    val steps = order.zip(order.drop(1)).map { (a, b) =>
      val sa = graph.situations(a)
      val sb = graph.situations(b)
      val ea = graph.entitiesOf(a)
      val eb = graph.entitiesOf(b)
      val union = ea.union(eb)
      val turnover =
        if union.isEmpty then 0.0 else 1.0 - ea.intersect(eb).size.toDouble / union.size
      val ctxChange = sa.context != sb.context
      val forward = graph.relations.temporal.find(e => e.from == a && e.to == b).map(_.relation)
      val backward =
        graph.relations.temporal.find(e => e.from == b && e.to == a).map(_.relation.converse)
      val transition = forward.orElse(backward) match
        case Some(TemporalRelation.Meets)  => WorldTimeTransition.Continues
        case Some(TemporalRelation.Before) => WorldTimeTransition.JumpForward(None)
        case Some(TemporalRelation.After | TemporalRelation.MetBy) =>
          WorldTimeTransition.JumpBackward(None)
        case Some(TemporalRelation.Unclear) | None => WorldTimeTransition.Unresolved(Vector.empty)
        case Some(_)                               => WorldTimeTransition.SimultaneousThreadSwitch
      val claimId = ClaimId.unsafe(ContentAddress.of("flow", a.value, b.value))
      val evidence = Evidence(
        EvidenceId.unsafe(ContentAddress.of("flow-ev", a.value, b.value)),
        Some(sa.support ++ sb.support),
        Set(sa.meta.id, sb.meta.id),
        DeriveFingerprint,
        DeriveStage
      )
      val meta = ClaimMeta(
        claimId,
        EpistemicStatus.StructurallyDerived,
        Credence.unsafeRaw(if forward.orElse(backward).isDefined then 1.0 else 0.0),
        NonEmptyVector.one(evidence),
        provenance
      )
      val endA = sa.support.minSpan.endExclusive
      val startB = sb.support.minSpan.start
      val unitsBetween =
        atlas.sentences.filter(u => u.span.endExclusive >= endA && u.span.start < startB).map(_.id)
      val beliefs = hierarchy.boundaryBeliefs.filter(bb => unitsBetween.contains(bb.afterUnit))
      FlowStep(
        a,
        b,
        Map.empty,
        turnover,
        None,
        ctxChange,
        Resolved(transition, meta, Vector.empty),
        beliefs
      )
    }
    DiscourseTrajectory(steps)

enum SensoryModality:
  case Visual, Auditory, Tactile, Motor, Spatial, Olfactory, Gustatory, Interoceptive

/** `Expressed`: sensory language actually present; `Evoked`: model-estimated imagery. */
enum SensoryProfileKind:
  case Expressed, Evoked

/** Per-modality sensory scores of a situation; `Missing` marks modalities the provider did not
  * score, never a zero.
  */
final case class SensoryProfile(
    kind: SensoryProfileKind,
    scores: Map[SensoryModality, ScoreEstimate]
)
