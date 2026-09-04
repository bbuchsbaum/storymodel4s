package storymodel4s.view

import cats.data.NonEmptyVector
import cats.syntax.all.*
import storymodel4s.core.*
import storymodel4s.features.*
import storymodel4s.story.*

/** Whether a feature was checked against the inputs that induced the narrative structure. No use
  * ledger accompanies features-record/v1, so an aggregate cannot claim independence.
  */
enum FeatureCircularity:
  case NotAggregate, NotAssessed

/** A shared domain within one measured space and grain, never across incompatible quantities. */
final class FeatureDomain private[view] (val minimum: Double, val maximum: Double):
  override def equals(other: Any): Boolean = other match
    case that: FeatureDomain => minimum == that.minimum && maximum == that.maximum
    case _                   => false
  override def hashCode(): Int = (minimum, maximum).hashCode

  /** Display fraction only; a constant track is centred, not assigned a scientific zero. */
  def fraction(value: Double): Double =
    if minimum == maximum then 0.5
    else
      val width = maximum - minimum
      if width.isInfinite then (value / 2.0 - minimum / 2.0) / (maximum / 2.0 - minimum / 2.0)
      else (value - minimum) / width

/** One complete measurement outcome. Construction is owned by the model-bound view compiler,
  * keeping the value, units, support, coverage and derivation together across the rendering seam.
  */
final class FeatureValue private[view] (
    val space: FeatureSpace[Double],
    val target: FeatureTarget,
    val support: SpanSet,
    val estimate: Estimate[Double],
    val coverage: Option[Coverage],
    val derivation: Option[FeatureDerivation],
    val provenance: TrackProvenance,
    val circularity: FeatureCircularity,
    val sidecarChecksum: Checksum,
    val domain: Option[FeatureDomain]
):
  private def fields = (
    space,
    target,
    support,
    estimate,
    coverage,
    derivation,
    provenance,
    circularity,
    sidecarChecksum,
    domain
  )
  override def equals(other: Any): Boolean = other match
    case that: FeatureValue => fields == that.fields
    case _                  => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = description

  def address: Address =
    Addressable[FeatureAddress].address(FeatureAddress.Observation(space.id, target))

  /** Deterministic inspector/twin text; absent coverage is never a fabricated complete fraction. */
  def description: String =
    val outcome = estimate match
      case Estimate.Observed(v, c) => s"value=$v credence=${c.fold("not supplied")(_.toString)}"
      case Estimate.Missing(r)     => s"missing=$r"
    val covered = coverage.fold("not recorded")(c => s"${c.observed}/${c.eligible}")
    val spans = support.spans.toVector.map(s => s"[${s.start},${s.endExclusive})").mkString(",")
    val recipe = derivation.fold("raw")(_.canonicalString)
    val basis = provenance.basisId.fold("none")(_.checksum.hex)
    val bounds = domain.fold("no observed values")(d => s"${d.minimum}..${d.maximum}")
    s"${space.description}; space=${space.id.value}; $outcome; units=${space.units.getOrElse("unspecified")}; " +
      s"support=$spans; coverage=$covered; circularity=$circularity; domain=$bounds; recipe=$recipe; basis=$basis; sidecar=${sidecarChecksum.hex}"

/** Compile scalar outcomes already materialized by a checked resolver. This module has no codec,
  * byte storage or I/O dependency and never recalculates a measurement from a clipped span.
  */
object FeatureRendering:
  /** Select an actual track using its recorded recipe and ordered basis. */
  def selection(track: FeatureTrack[? <: FeatureTarget, Double]): FeatureSelection =
    track.derivation.fold[FeatureSelection](FeatureSelection.Raw(track.space.id))(d =>
      FeatureSelection.Derived(d.derivationId, track.provenance.basisId.map(_.checksum))
    )

  private[view] def attach(
      model: StoryModel[?],
      scene: NarrativeScene,
      track: FeatureTrack[FeatureTarget, Double]
  ): Either[DomainError, NarrativeScene] =
    def require(condition: Boolean, message: String): Either[DomainError, Unit] =
      Either.cond(condition, (), DomainError.InvariantViolation("view/features", message))
    val selected = selection(track)
    val space = track.space.id
    val resolver = SupportResolver(
      SurfaceSequence(model.atlas),
      situation = id => model.graph.situations.get(id).map(_.support),
      segment = id => model.graph.segments.get(id).map(_.support)
    )
    val observedTargets = track.observations.filter(_.estimate.isObserved).map(_.target)
    val refs = model.featureRefs.filter(_.space == space).sortBy(_.row)
    val scalar = track.space.valueSchema match
      case FeatureValueSchema.Scalar(units) => units == track.space.units
      case _                                => false
    for
      _ <- FeatureTrack.validatedScores(track)
      _ <- require(
        scene.state.horizon == EpistemicHorizon.Omniscient,
        "materialized features require an omniscient horizon; no prefix-safe measurement was supplied"
      )
      _ <- require(
        scene.state.feature.contains(selected),
        "selection does not describe the supplied track"
      )
      _ <- require(
        selected.resolveSpace == Right(space),
        "derived output identity does not match its recipe and basis"
      )
      _ <- require(
        track.provenance.storyChecksum.contains(model.source.canonicalChecksum),
        "track belongs to another source"
      )
      _ <- require(
        model.featureSpaces.get(space).contains(track.space),
        "track space is not the model's declared space"
      )
      _ <- require(scalar, "a scalar feature requires a scalar schema with matching units")
      _ <- require(
        model.sidecars.get(space).exists(_.rowCount == observedTargets.size),
        "track count differs from the model's sidecar"
      )
      _ <- require(
        refs.map(_.target) == observedTargets && refs.map(_.row) == refs.indices.toVector,
        "observed targets differ from the model's compact sidecar references"
      )
      outcomes <- track.observations
        .filter(o => FeaturePlanner.atScale(model, scene.featureLayer.scale, o.target))
        .traverse { o =>
          resolver
            .support(o.target)
            .toRight(
              DomainError
                .InvariantViolation("view/features/support", "target has no source support")
            )
            .flatMap(s =>
              require(o.support.contains(s), "track support differs from its model target")
                .as(o -> s)
            )
        }
      values = outcomes.flatMap(_._1.estimate.toOption)
      domain = if values.isEmpty then None else Some(new FeatureDomain(values.min, values.max))
      compiled = outcomes.map { (o, support) =>
        val value = new FeatureValue(
          track.space,
          o.target,
          support,
          o.estimate,
          o.coverage,
          track.derivation,
          track.provenance,
          if track.derivation.isEmpty then FeatureCircularity.NotAggregate
          else FeatureCircularity.NotAssessed,
          model.sidecars(space).checksum,
          domain
        )
        val id = VisualIdentity.of(
          value.address,
          scene.zoom.narrative,
          MarkId.unsafe(
            ContentAddress.of(
              "mark",
              value.address.render,
              "feature",
              scene.zoom.narrative.toString
            )
          )
        )
        VisualPrimitive.Feature(id, value)
      }
      marks = scene.marks ++ compiled
      _ <- compiled.traverse_(AtlasCompiler(scene.provenance).checkEvidence(model, _))
      navigation <- SceneNavigation.from(marks)
      placements =
        scene.selectionPlacements ++ (scene.state.selection ++ scene.state.focus).toVector.flatMap {
          address =>
            NonEmptyVector
              .fromVector(navigation.marksFor(address))
              .map(ms => address -> SelectionPlacement.OnMark(ms))
        }
    yield new NarrativeScene(
      scene.contract,
      scene.zoom,
      scene.state,
      new AtlasFeatureLayer(
        scene.featureLayer.scale,
        FeatureChannelState.Materialized(selected, space, outcomes.size),
        scene.featureLayer.observations
      ),
      marks,
      navigation,
      placements,
      scene.provenance
    )
