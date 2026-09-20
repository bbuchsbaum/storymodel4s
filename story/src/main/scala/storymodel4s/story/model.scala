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
final class StoryModel[S <: ModelStatus] private (
    val schemaVersion: String,
    val atlas: NarrativeSourceAtlas,
    val graph: NarrativeGraph,
    val hierarchy: NarrativeHierarchy,
    val trajectory: DiscourseTrajectory,
    val featureSpaces: Map[FeatureSpaceId, FeatureSpace[?]],
    val sidecars: Map[FeatureSpaceId, SidecarManifest],
    val featureRefs: Vector[FeatureRef],
    val descriptors: Vector[DescriptorClaim],
    val hypotheses: Vector[HypothesisClaim],
    val sensoryProfiles: Map[SituationId, Vector[SensoryProfile]],
    val receipt: Option[BuildReceipt],
    val discourseOrder: Vector[SituationId],
    private val supportProjections: Map[TypedSupport, PrimaryProjection]
):
  val bundle: SourceBundle = atlas.bundle
  private val identity = StoryModel.identityOf(atlas)
  val storyId: StoryId = identity._1
  val sourceChecksum: Checksum = identity._2

  /** Projection of an admitted model support; the map is derived at the shared join. */
  private[story] def projectionOf(support: TypedSupport): PrimaryProjection = supportProjections(support)

  /** Every inline claim in the model, in a deterministic order: node claims, resolved-value claims
    * (entity labels, segment summaries), scoped attributes, every relation layer, containment,
    * trajectory transitions, descriptors, and hypotheses.
    */
  lazy val claims: Vector[ClaimMeta] =
    (graph.allMeta ++ hierarchy.allMeta ++ trajectory.allMeta ++ descriptors.map(_.meta) ++
      hypotheses.map(_.meta)).sortBy(_.id)

  /** All ordered read views share the order checked during construction. */
  lazy val discoursePosition: Map[SituationId, Int] = graph.discoursePosition(discourseOrder)
  lazy val situationsByEntity: Map[EntityId, Vector[SituationId]] =
    graph.situationsByEntity(discourseOrder)
  lazy val situationsByContext: Map[ContextId, Vector[SituationId]] =
    graph.situationsByContext(discourseOrder)
  def situationsWithin(context: ContextId): Vector[SituationId] =
    graph.situationsWithin(context, discourseOrder)
  private[story] def situationsCovering(span: TextSpan): Vector[SituationId] =
    graph.situationsCovering(span, discourseOrder)

  /** The derived, normalized claim ledger; fails on duplicate claim identifiers. */
  lazy val ledger: Either[DomainError, ClaimLedger] = ClaimLedger.empty.addAll(claims)

  /** Evidence support of a reference across graph and hierarchy: containment edges resolve to the
    * spans their claims cite; everything else delegates to [[NarrativeGraph.supporting]].
    */
  private[story] def supporting(ref: StoryRef): Option[SpanSet] = ref match
    case StoryRef.Containment(m, p, k) =>
      hierarchy.containment
        .find(e => e.member == m && e.parent == p && e.kind == k)
        .flatMap(_.meta.spans)
    case other => graph.supporting(other)

  /** Every graph or hierarchy reference whose evidence overlaps `span`, ordered by address. */
  private[story] def covering(span: TextSpan): Vector[StoryRef] =
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
      atlas: NarrativeSourceAtlas = atlas,
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
  ): Either[DomainError, StoryModel[T]] =
    StoryModel.checked[T](
      schemaVersion,
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
      receipt,
      discourseOrder,
      supportProjections
    )

  override def equals(other: Any): Boolean = other match
    case that: StoryModel[?] =>
      schemaVersion == that.schemaVersion && atlas == that.atlas &&
      graph == that.graph && hierarchy == that.hierarchy && trajectory == that.trajectory &&
      featureSpaces == that.featureSpaces && sidecars == that.sidecars &&
      featureRefs == that.featureRefs && descriptors == that.descriptors &&
      hypotheses == that.hypotheses && sensoryProfiles == that.sensoryProfiles &&
      receipt == that.receipt
    case _ => false

  override def hashCode: Int =
    (
      schemaVersion,
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
  /** Moved 0.1.0 -> 0.2.0 when `RelationLayers.circumstances` became a required encoded field. A
    * version that stayed put would have let two incompatible shapes share one tag, and worse: a
    * 0.1.0 model has no circumstance layer because the rule that fills it did not exist, so reading
    * one as "circumstances: []" would publish "evaluated and found none" for a model that never
    * evaluated. There is no migration step for that reason; a 0.1.0 artifact is refused and
    * rebuilt.
    *
    * Moved 0.2.0 -> 0.3.0 when a holder-bearing [[ContextKind]] began carrying a [[ContextHolder]]
    * instead of a bare `EntityId`, changing the encoded shape from `{"entity": ...}` to
    * `{"holder": ...}`. Again no migration step: a 0.2.0 model's speech contexts all name an
    * entity, but nothing in that artifact records whether an *absent* speech context was absent
    * because the text held no speech or because the compiler had no vocabulary for an unattributed
    * one, so a mechanical lift would invent the distinction it is supposed to preserve.
    *
    * Moved 0.5.0 -> 0.6.0 when a [[SegmentNode]] gained its own `meta` and its `summary` became a
    * [[SegmentSummary]], stated or a typed absence (ADR 0005 §10). No migration step: a 0.5.0 model
    * has a segment only where it has a summary, so a lift could mint the segment's claim but not
    * say what the summary's absence was absent for, and a 0.5.0 model with no segments cannot be
    * told from one whose text had no situations.
    *
    * Moved 0.6.0 -> 0.7.0 when `RecallUnit.evidence` began to be written at all. No migration step:
    * a 0.6.0 artifact cannot distinguish a unit that genuinely carried no chart from one whose
    * chart the encoder discarded, so reading it as `None` would assert an absence nobody observed.
    *
    * This value and `codec.SchemaVersions.Current` must be equal: the encoder writes the model's
    * own stamp and the decoder checks the codec's supported list, so a model stamped with one and
    * read against the other cannot round trip. `CodecSuite` asserts the equality, because nothing
    * else did and the two constants sat one module apart carrying separate histories of the same
    * number.
    */
  val SchemaVersion: String = "0.7.0"

  def draft(
      atlas: NarrativeSourceAtlas,
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
  ): Either[DomainError, StoryModel[ModelStatus.Draft]] =
    checked[ModelStatus.Draft](
      schemaVersion,
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

  private def checked[S <: ModelStatus](
      schemaVersion: String,
      atlas: NarrativeSourceAtlas,
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
  ): Either[DomainError, StoryModel[S]] =
    val bundle = atlas.bundle
    val allClaims = graph.allMeta ++ hierarchy.allMeta ++ trajectory.allMeta ++
      descriptors.map(_.meta) ++ hypotheses.map(_.meta)
    val boundaries = hierarchy.boundaryBeliefs ++ trajectory.steps.flatMap(_.boundaryBeliefs)
    val evidence = allClaims.flatMap(_.evidence.toVector) ++ boundaries.flatMap(_.evidence)
    val (storyId, checksum) = identityOf(atlas)
    def invalid(path: String, reason: String): Left[DomainError, Nothing] =
      Left(DomainError.InvariantViolation(path, reason))
    def checkEvidence(e: Evidence): Either[DomainError, Unit] =
      if bundle.primaryAxis.kind == AxisKind.TextCharacter && e.anchors.nonEmpty then
        invalid("model/evidence", "text StoryModel cannot carry anchored evidence")
      else if bundle.primaryAxis.kind == AxisKind.EditionPlayback && e.spans.nonEmpty then
        invalid("model/evidence", "anchored StoryModel cannot carry bare text spans")
      else e.anchors match
        case None => Right(())
        case Some(support) => EvidenceSupport.of(bundle, support.anchors.toVector).map(_ => ())
    for
      projections <- graph.supportEntries.sortBy(_._1)
        .foldLeft[Either[DomainError, Map[TypedSupport, PrimaryProjection]]](Right(Map.empty)) {
          case (result, (path, support)) =>
            for
              values <- result
              projection <- PrimaryProjection.on(bundle, support).left.map(error =>
                DomainError.InvariantViolation(path + "/support", error.message))
            yield values.updated(support, projection)
        }
      _ <- evidence.foldLeft[Either[DomainError, Unit]](Right(()))((result, e) => result.flatMap(_ => checkEvidence(e)))
      _ <- receipt match
        case Some(r) if r.storyId != storyId || r.sourceChecksum != checksum =>
          invalid("model/receipt", "receipt story identity or source checksum differs from the atlas")
        case _ => Right(())
      order <- graph.discourseOrderOn(bundle)
    yield new StoryModel[S](schemaVersion, atlas, graph, hierarchy, trajectory, featureSpaces,
      sidecars, featureRefs, descriptors, hypotheses, sensoryProfiles, receipt, order, projections)

  private def identityOf(atlas: NarrativeSourceAtlas): (StoryId, Checksum) = atlas match
    case text: TextNarrativeAtlas => (text.atlas.source.id, text.atlas.source.canonicalChecksum)
    case anchored: AnchoredNarrativeAtlas =>
      val surface = anchored.surface.fold(Vector("surface:none"))(s => Vector("surface:some", s.identity.hex))
      val parts = Vector("story-anchored", anchored.bundle.identity.hex) ++ surface
      (StoryId.unsafe(ContentAddress.of(parts.head, parts.tail*)), ContentAddress.digest(parts))

  /** The source is derived from this surface, never supplied as an independent pair. */
  def draftText(
      surface: SurfaceAtlas,
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
  ): Either[DomainError, TextModel[ModelStatus.Draft]] =
    for
      atlas <- TextNarrativeAtlas.of(surface)
      model <- draft(atlas, graph, hierarchy, trajectory, featureSpaces, sidecars, featureRefs,
        descriptors, hypotheses, sensoryProfiles, receipt, schemaVersion)
      text <- TextModel.checked(model)
    yield text

  def asText[S <: ModelStatus](model: StoryModel[S]): Option[TextModel[S]] = TextModel.fromModel(model)

  /** Human adjudication promotes a validated model; the adjudicator is recorded by the caller in
    * the claim ledger, so this is a pure status change.
    */
  def adjudicated(model: StoryModel[ModelStatus.Validated]): StoryModel[ModelStatus.Adjudicated] =
    model.withStatus[ModelStatus.Adjudicated]

  def adjudicated(model: TextModel[ModelStatus.Validated]): TextModel[ModelStatus.Adjudicated] =
    model.promoted[ModelStatus.Adjudicated]

/** Canonical text access derived only from the model's text atlas. */
final class StoryText private (val source: StorySource, val surface: SurfaceAtlas, val stream: StreamId)
object StoryText:
  private[story] def fromAtlas(atlas: TextNarrativeAtlas): StoryText =
    new StoryText(atlas.atlas.source, atlas.atlas, atlas.bundle.streams.head.id)

/** A checked text capability; no implicit conversion or independent text/model join. */
final class TextModel[S <: ModelStatus] private (val model: StoryModel[S], val text: StoryText):
  def source: StorySource = text.source
  def atlas: SurfaceAtlas = text.surface
  def bundle: SourceBundle = model.bundle
  def storyId: StoryId = model.storyId
  def sourceChecksum: Checksum = model.sourceChecksum
  def schemaVersion: String = model.schemaVersion
  def graph: NarrativeGraph = model.graph
  def hierarchy: NarrativeHierarchy = model.hierarchy
  def trajectory: DiscourseTrajectory = model.trajectory
  def featureSpaces: Map[FeatureSpaceId, FeatureSpace[?]] = model.featureSpaces
  def sidecars: Map[FeatureSpaceId, SidecarManifest] = model.sidecars
  def featureRefs: Vector[FeatureRef] = model.featureRefs
  def descriptors: Vector[DescriptorClaim] = model.descriptors
  def hypotheses: Vector[HypothesisClaim] = model.hypotheses
  def sensoryProfiles: Map[SituationId, Vector[SensoryProfile]] = model.sensoryProfiles
  def receipt: Option[BuildReceipt] = model.receipt
  def claims: Vector[ClaimMeta] = model.claims
  def ledger: Either[DomainError, ClaimLedger] = model.ledger
  def discourseOrder: Vector[SituationId] = model.discourseOrder
  def discoursePosition: Map[SituationId, Int] = model.discoursePosition
  def situationsByEntity: Map[EntityId, Vector[SituationId]] = model.situationsByEntity
  def situationsByContext: Map[ContextId, Vector[SituationId]] = model.situationsByContext
  def situationsWithin(context: ContextId): Vector[SituationId] = model.situationsWithin(context)
  def situationsCovering(span: TextSpan): Vector[SituationId] = model.situationsCovering(span)
  def supporting(ref: StoryRef): Option[SpanSet] = model.supporting(ref)
  def covering(span: TextSpan): Vector[StoryRef] = model.covering(span)
  private[story] def promoted[T <: ModelStatus]: TextModel[T] =
    new TextModel(model.withStatus[T], text)
  override def equals(other: Any): Boolean = other match
    case that: TextModel[?] => model == that.model
    case _ => false
  override def hashCode: Int = model.hashCode
  override def toString: String = s"TextModel($model)"

object TextModel:
  private[story] def fromModel[S <: ModelStatus](model: StoryModel[S]): Option[TextModel[S]] =
    model.atlas match
      case atlas: TextNarrativeAtlas => Some(new TextModel(model, StoryText.fromAtlas(atlas)))
      case _: AnchoredNarrativeAtlas => None
  private[story] def checked[S <: ModelStatus](model: StoryModel[S]): Either[DomainError, TextModel[S]] =
    fromModel(model).toRight(DomainError.InvariantViolation("model/text", "model has no canonical text atlas"))
