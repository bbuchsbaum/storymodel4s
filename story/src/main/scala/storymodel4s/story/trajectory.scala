package storymodel4s.story

import cats.data.NonEmptyVector
import storymodel4s.core.*
import storymodel4s.features.{Estimate, MissingReason}

/** Change between two adjacent atomic units in discourse order. `featureChanges` holds
  * view-specific discontinuities keyed by the declared feature space that produced them; a missing
  * key means that view was not computed, never that the change was zero.
  *
  * `worldTime` is the story-world transition *in `worldTimeContext`*: the nearest context common to
  * both situations. A backward step inside a character's speech is a fact about the order in which
  * the speaker put things, not a narrated-world flashback; consumers that want narrated-world
  * chronology must check `worldTimeContext` against the root.
  */
final case class FlowStep(
    from: SituationId,
    to: SituationId,
    featureChanges: Map[FeatureSpaceId, ScoreEstimate],
    entityTurnover: ScoreEstimate,
    locationChange: Option[Boolean],
    contextChange: Boolean,
    worldTime: Resolved[WorldTimeTransition],
    worldTimeContext: Option[ContextId],
    boundaryBeliefs: Vector[BoundaryBelief]
)

/** The multiview discourse trajectory: one step per adjacent pair of situations. */
final case class DiscourseTrajectory(steps: Vector[FlowStep]):
  def stepFrom(id: SituationId): Option[FlowStep] = steps.find(_.from == id)
  def allMeta: Vector[ClaimMeta] = steps.map(_.worldTime.meta)

  /** Steps whose world-time transition is asserted for the given context (normally the root). */
  def stepsIn(context: ContextId): Vector[FlowStep] =
    steps.filter(_.worldTimeContext.contains(context))

object DiscourseTrajectory:
  val empty: DiscourseTrajectory = DiscourseTrajectory(Vector.empty)

  val DeriveFingerprint: Fingerprint = Fingerprint.unsafe("storymodel4s:trajectory:derive:0.1")

  /** The rule every flow step is determined by: discourse order over the graph's situations and
    *
    * the temporal edges scoped to their common context. A step has no probability of its own;
    *
    * whether a temporal edge licensed its transition is stated by `transition`, not by a score.
    */

  val DeriveRule: RuleId = RuleId.unsafe("trajectory-derive/v1")
  val DeriveStage: StageId = StageId.unsafe("trajectory-derive")

  /** Deterministically derive graph-available views: entity turnover (1 − Jaccard of expanded
    * participant sets), context change, and the world-time transition implied by a stored temporal
    * edge between the adjacent situations *scoped to their nearest common context*. Feature and
    * location views stay absent.
    *
    * Transition mapping: `Meets` → `Continues`; `Before` → `JumpForward`; a backward edge →
    * `JumpBackward`; overlap/containment/equality → `SimultaneousThreadSwitch`; `Unclear` or no
    * edge in that context → `Unresolved` with no alternatives. Each derived value is a
    * `StructurallyDerived` claim citing both situations' supports.
    */
  /** `castResolved` says whether a situation's participant set is complete and every member
    * resolved to an entity; a step between two situations either of whose casts is not is derived
    * with its turnover `Missing(InputUnresolved)` rather than with a number computed over the
    * members that happened to resolve (truthfulness plan D6). The default resolves everything,
    * which is right only for a caller that has checked.
    */
  def derive(
      graph: NarrativeGraph,
      hierarchy: NarrativeHierarchy,
      atlas: SurfaceAtlas,
      softwareVersion: String = StoryModel.SchemaVersion,
      castResolved: SituationId => Boolean = _ => true
  ): DiscourseTrajectory =
    val order = graph.discourseOrder
    val provenance = Provenance.deterministic(softwareVersion, Checksum.ofText("trajectory-derive"))
    val steps = order.zip(order.drop(1)).map { (a, b) =>
      val sa = graph.situations(a)
      val sb = graph.situations(b)
      val ea = graph.expandedEntitiesOf(a)
      val eb = graph.expandedEntitiesOf(b)
      val union = ea.union(eb)
      val turnover: ScoreEstimate =
        if !(castResolved(a) && castResolved(b)) then
          Estimate.Missing(MissingReason.InputUnresolved)
        else if union.isEmpty then Estimate.observed(0.0)
        else Estimate.observed(1.0 - ea.intersect(eb).size.toDouble / union.size)
      val ctxChange = sa.context != sb.context
      val scope = graph.commonContext(sa.context, sb.context)
      val scoped = scope.map(graph.temporalEdgesIn).getOrElse(Vector.empty)
      val forward = scoped.find(e => e.from == a && e.to == b).map(_.relation)
      val backward = scoped.find(e => e.from == b && e.to == a).map(_.relation.converse)
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
      val meta = ClaimMeta.unsafe(
        claimId,
        EpistemicStatus.StructurallyDerived,
        Credence.unsafeDetermined(DeriveRule),
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
        scope,
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
