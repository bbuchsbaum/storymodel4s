package storymodel4s.story

import storymodel4s.core.*

/** Phantom status of a story model. Only [[StoryValidator]] can promote `Draft` to `Validated`;
  * downstream scientific APIs require `Validated` or `Adjudicated`.
  */
sealed trait ModelStatus
object ModelStatus:
  sealed trait Draft extends ModelStatus
  sealed trait Validated extends ModelStatus
  sealed trait Adjudicated extends ModelStatus

/** The full story representation `(A, G, H, X, Γ)` plus feature manifests, descriptors, hypotheses,
  * and an optional receipt. Constructed only through [[StoryModel.draft]] and promoted by
  * validation.
  */
final case class StoryModel[S <: ModelStatus] private[story] (
    schemaVersion: String,
    source: StorySource,
    atlas: SurfaceAtlas,
    graph: NarrativeGraph,
    hierarchy: NarrativeHierarchy,
    trajectory: DiscourseTrajectory,
    featureSpaces: Map[FeatureSpaceId, FeatureSpace[?]],
    sidecars: Map[FeatureSpaceId, SidecarManifest],
    featureRefs: Vector[FeatureRef],
    descriptors: Vector[DescriptorClaim],
    hypotheses: Vector[HypothesisClaim],
    sensoryProfiles: Map[SituationId, Vector[SensoryProfile]],
    receipt: Option[BuildReceipt]
):
  /** Every inline claim in the model, in a deterministic order: node claims, resolved-value claims
    * (entity labels, segment summaries), scoped attributes, every relation layer, containment,
    * trajectory transitions, descriptors, and hypotheses.
    */
  lazy val claims: Vector[ClaimMeta] =
    (graph.allMeta ++ hierarchy.allMeta ++ trajectory.allMeta ++ descriptors.map(_.meta) ++
      hypotheses.map(_.meta)).sortBy(_.id)

  /** The derived, normalized claim ledger; fails on duplicate claim identifiers. */
  lazy val ledger: Either[DomainError, ClaimLedger] = ClaimLedger.empty.addAll(claims)

  /** Evidence support of a reference across graph and hierarchy: containment edges resolve to the
    * spans their claims cite; everything else delegates to [[NarrativeGraph.supporting]].
    */
  def supporting(ref: StoryRef): Option[SpanSet] = ref match
    case StoryRef.Containment(m, p, k) =>
      hierarchy.containment
        .find(e => e.member == m && e.parent == p && e.kind == k)
        .flatMap(_.meta.spans)
    case other => graph.supporting(other)

  /** Every graph or hierarchy reference whose evidence overlaps `span`, ordered by address. */
  def covering(span: TextSpan): Vector[StoryRef] =
    val fromHierarchy = hierarchy.containment
      .filter(e => e.meta.spans.exists(_.spans.exists(_.overlaps(span))))
      .map(e => StoryRef.Containment(e.member, e.parent, e.kind))
    val ev = Addressable[StoryRef]
    (graph.covering(span) ++ fromHierarchy).distinct.sortBy(r => ev.address(r).render)

  private[story] def withStatus[T <: ModelStatus]: StoryModel[T] =
    new StoryModel[T](
      schemaVersion,
      source,
      atlas,
      graph,
      hierarchy,
      trajectory,
      featureSpaces,
      sidecars,
      featureRefs,
      descriptors,
      hypotheses,
      sensoryProfiles,
      receipt
    )

object StoryModel:
  val SchemaVersion: String = "0.1.0"

  def draft(
      source: StorySource,
      atlas: SurfaceAtlas,
      graph: NarrativeGraph,
      hierarchy: NarrativeHierarchy,
      trajectory: DiscourseTrajectory,
      featureSpaces: Map[FeatureSpaceId, FeatureSpace[?]] = Map.empty,
      sidecars: Map[FeatureSpaceId, SidecarManifest] = Map.empty,
      featureRefs: Vector[FeatureRef] = Vector.empty,
      descriptors: Vector[DescriptorClaim] = Vector.empty,
      hypotheses: Vector[HypothesisClaim] = Vector.empty,
      sensoryProfiles: Map[SituationId, Vector[SensoryProfile]] = Map.empty,
      receipt: Option[BuildReceipt] = None,
      schemaVersion: String = SchemaVersion
  ): StoryModel[ModelStatus.Draft] =
    new StoryModel[ModelStatus.Draft](
      schemaVersion,
      source,
      atlas,
      graph,
      hierarchy,
      trajectory,
      featureSpaces,
      sidecars,
      featureRefs,
      descriptors,
      hypotheses,
      sensoryProfiles,
      receipt
    )

  /** Human adjudication promotes a validated model; the adjudicator is recorded by the caller in
    * the claim ledger, so this is a pure status change.
    */
  def adjudicated(model: StoryModel[ModelStatus.Validated]): StoryModel[ModelStatus.Adjudicated] =
    model.withStatus[ModelStatus.Adjudicated]
