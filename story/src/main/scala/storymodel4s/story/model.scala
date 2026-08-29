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
final class StoryModel[S <: ModelStatus] private[story] (
    val schemaVersion: String,
    val source: StorySource,
    val atlas: SurfaceAtlas,
    val graph: NarrativeGraph,
    val hierarchy: NarrativeHierarchy,
    val trajectory: DiscourseTrajectory,
    val featureSpaces: Map[FeatureSpaceId, FeatureSpace[?]],
    val sidecars: Map[FeatureSpaceId, SidecarManifest],
    val featureRefs: Vector[FeatureRef],
    val descriptors: Vector[DescriptorClaim],
    val hypotheses: Vector[HypothesisClaim],
    val sensoryProfiles: Map[SituationId, Vector[SensoryProfile]],
    val receipt: Option[BuildReceipt]
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

  /** Internal mutation hook for validator fixtures. Any changed field requires the resulting status
    * to be supplied explicitly or by the expected result type.
    */
  private[story] def copy[T <: ModelStatus](
      schemaVersion: String = schemaVersion,
      source: StorySource = source,
      atlas: SurfaceAtlas = atlas,
      graph: NarrativeGraph = graph,
      hierarchy: NarrativeHierarchy = hierarchy,
      trajectory: DiscourseTrajectory = trajectory,
      featureSpaces: Map[FeatureSpaceId, FeatureSpace[?]] = featureSpaces,
      sidecars: Map[FeatureSpaceId, SidecarManifest] = sidecars,
      featureRefs: Vector[FeatureRef] = featureRefs,
      descriptors: Vector[DescriptorClaim] = descriptors,
      hypotheses: Vector[HypothesisClaim] = hypotheses,
      sensoryProfiles: Map[SituationId, Vector[SensoryProfile]] = sensoryProfiles,
      receipt: Option[BuildReceipt] = receipt
  ): StoryModel[T] =
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

  override def equals(other: Any): Boolean = other match
    case that: StoryModel[?] =>
      schemaVersion == that.schemaVersion && source == that.source && atlas == that.atlas &&
      graph == that.graph && hierarchy == that.hierarchy && trajectory == that.trajectory &&
      featureSpaces == that.featureSpaces && sidecars == that.sidecars &&
      featureRefs == that.featureRefs && descriptors == that.descriptors &&
      hypotheses == that.hypotheses && sensoryProfiles == that.sensoryProfiles &&
      receipt == that.receipt
    case _ => false

  override def hashCode: Int =
    (
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
    ).##

  override def toString: String =
    s"StoryModel(schemaVersion=$schemaVersion, situations=${graph.situations.size})"

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
