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

/** The full story representation `(A, G, H, X, Γ)` plus feature manifests, descriptors, and an
  * optional receipt. Constructed only through [[StoryModel.draft]] and promoted by validation.
  */
final case class StoryModel[S <: ModelStatus] private[story] (
    schemaVersion: String,
    source: StorySource,
    atlas: SurfaceAtlas,
    graph: NarrativeGraph,
    hierarchy: NarrativeHierarchy,
    trajectory: DiscourseTrajectory,
    featureSpaces: Map[FeatureSpaceId, FeatureSpace],
    featureRefs: Vector[FeatureRef],
    descriptors: Vector[DescriptorClaim],
    sensoryProfiles: Map[SituationId, Vector[SensoryProfile]],
    receipt: Option[BuildReceipt]
):
  /** Every inline claim in the model, in a deterministic order. */
  lazy val claims: Vector[ClaimMeta] =
    (graph.allMeta ++ hierarchy.allMeta ++ trajectory.allMeta ++ descriptors.map(_.meta))
      .sortBy(_.id)

  /** The derived, normalized claim ledger; fails on duplicate claim identifiers. */
  lazy val ledger: Either[DomainError, ClaimLedger] = ClaimLedger.empty.addAll(claims)

  private[story] def withStatus[T <: ModelStatus]: StoryModel[T] =
    new StoryModel[T](
      schemaVersion,
      source,
      atlas,
      graph,
      hierarchy,
      trajectory,
      featureSpaces,
      featureRefs,
      descriptors,
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
      featureSpaces: Map[FeatureSpaceId, FeatureSpace] = Map.empty,
      featureRefs: Vector[FeatureRef] = Vector.empty,
      descriptors: Vector[DescriptorClaim] = Vector.empty,
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
      featureRefs,
      descriptors,
      sensoryProfiles,
      receipt
    )

  /** Human adjudication promotes a validated model; the adjudicator is recorded by the caller in
    * the claim ledger, so this is a pure status change.
    */
  def adjudicated(model: StoryModel[ModelStatus.Validated]): StoryModel[ModelStatus.Adjudicated] =
    model.withStatus[ModelStatus.Adjudicated]
