package storymodel4s.features

import cats.data.NonEmptyVector
import storymodel4s.core.*

/** Identifier of a declared reducer (see [[ScalarReducer.id]]). */
object ReducerId extends OpaqueId("ReducerId")
type ReducerId = ReducerId.T

/** How samples are weighted inside a reduction. */
enum WeightingPolicy:
  case Uniform
  case Kernel(shape: String, bandwidth: Double)

  /** Weights supplied per sample by the caller (e.g. duration, count mass). */
  case Provided(description: String)

  def canonicalString: String = this match
    case Uniform      => "uniform"
    case Kernel(s, b) => s"kernel($s,${CanonicalDouble.render(b)})"
    case Provided(d)  => s"provided($d)"

/** What to do when a reduction's support has missing values. */
enum MissingValuePolicy:
  /** Reduce over observed samples only; all-missing yields `Missing`. */
  case IgnoreMissing

  /** As `IgnoreMissing`, but yield `Missing(Excluded)` when coverage is below `fraction`. */
  case RequireMinCoverage(fraction: Double)

  /** Any missing sample makes the whole derivation fail. */
  case Fail

  def canonicalString: String = this match
    case IgnoreMissing         => "ignore"
    case RequireMinCoverage(f) => s"minCoverage(${CanonicalDouble.render(f)})"
    case Fail                  => "fail"

/** Post-reduction normalization, always tied to the population it was fitted on. */
enum NormalizationPolicy:
  case ZScore(populationId: String)
  case MinMax(populationId: String)
  case UnitLength

  def canonicalString: String = this match
    case ZScore(p)  => s"zscore($p)"
    case MinMax(p)  => s"minmax($p)"
    case UnitLength => "unit"

/** Which tokens of a support are eligible to carry a value. */
enum Eligibility:
  /** Words and numbers only (punctuation and symbols are never eligible). */
  case LexicalTokens

  /** Every token, including punctuation; only meaningful for token-level providers that score it.
    */
  case AllTokens

  def canonicalString: String = this match
    case LexicalTokens => "lexical"
    case AllTokens     => "all"

/** The family of targets a derived track is attached to. Part of the recipe because the same
  * reducer over the same inputs means something different per situation than per window.
  */
enum TargetFamily:
  case Token, Window, Sentence, Boundary, Turn, Situation, Segment, SurfaceUnit

object TargetFamily:
  def of(t: FeatureTarget): TargetFamily = t match
    case _: FeatureTarget.Token       => Token
    case _: FeatureTarget.Window      => Window
    case _: FeatureTarget.Sentence    => Sentence
    case _: FeatureTarget.Boundary    => Boundary
    case _: FeatureTarget.Turn        => Turn
    case _: FeatureTarget.Situation   => Situation
    case _: FeatureTarget.Segment     => Segment
    case _: FeatureTarget.SurfaceUnit => SurfaceUnit

/** Content identity of the ordered target axis on which a derivation ran.
  *
  * Why: a recipe alone does not identify its outputs when the caller supplies the situations,
  * segments, or other targets and their order. The family is included even for an empty basis, and
  * targets are hashed in caller order using their canonical address parts.
  */
object BasisId:
  opaque type BasisId = Checksum
  private val path = "features/basis"

  /** Build an identity only when every target belongs to the declared family. */
  def of(family: TargetFamily, targets: Vector[FeatureTarget]): Either[DomainError, BasisId] =
    targets.find(TargetFamily.of(_) != family) match
      case Some(target) =>
        Left(
          DomainError.InvariantViolation(
            path,
            s"${FeatureTargetKey.parts(target).mkString("/")} is not a $family target"
          )
        )
      case None =>
        Right(
          ContentAddress.digest(
            family.toString +: targets.flatMap(FeatureTargetKey.parts)
          )
        )

  /** Rebuild a wire identity after the checksum decoder has validated its representation. */
  def fromChecksum(checksum: Checksum): BasisId = checksum

  extension (id: BasisId)
    def checksum: Checksum = id

type BasisId = BasisId.BasisId

/** A centred window of `±halfWidth` narrative units around each unit of a [[NarrativeBasis]]: the
  * narrative counterpart of core's `WindowPlan` (ADR 0002 §9 checkpoint 2, "`WindowBasis.Events`").
  *
  * Why: event and scene recipes of the scale selector must be receipted exactly like surface
  * windows, and the unit axis (situations, segments) is known only to the story model, so the plan
  * lives here rather than in core. A half-width of 0 is per-unit aggregation. The axis itself is
  * recorded by the derivation's `targetFamily`.
  *
  * Decision: narrative windows are centred (the target is the centre unit, clipped at the ends of
  * the basis), not width/step sliding windows — a sliding window over events would need an "event
  * range" target that ADR 0002 does not define.
  */
final case class NarrativeWindowPlan private (halfWidth: Int):
  def canonicalString: String = s"narrative(halfWidth=$halfWidth)"

object NarrativeWindowPlan:
  /** Per-unit aggregation: each unit is its own window. */
  val perUnit: NarrativeWindowPlan = NarrativeWindowPlan(0)

  def of(halfWidth: Int): Either[DomainError, NarrativeWindowPlan] =
    if halfWidth < 0 then
      Left(DomainError.InvalidFormat("NarrativeWindowPlan", halfWidth.toString, "negative"))
    else Right(NarrativeWindowPlan(halfWidth))

/** The executable recipe that produced a derived track from its inputs.
  *
  * Why: every smoothed or aggregated value must be replayable and diffable; the recipe is
  * content-addressed so caches and dependency graphs can key on it (design record §110). Every
  * parameter that changes the output is part of the recipe, including eligibility and target
  * family. A recipe has at most one window: `window` slides over the surface axis,
  * `narrativeWindow` over a [[NarrativeBasis]]; the latter is rendered only when present so ids of
  * surface recipes never change.
  */
final case class FeatureDerivation(
    inputs: NonEmptyVector[FeatureSpaceId],
    window: Option[WindowPlan],
    reducer: ReducerId,
    weighting: WeightingPolicy,
    missing: MissingValuePolicy,
    normalization: Option[NormalizationPolicy],
    implementationVersion: String,
    eligibility: Eligibility = Eligibility.LexicalTokens,
    targetFamily: Option[TargetFamily] = None,
    narrativeWindow: Option[NarrativeWindowPlan] = None
):
  def canonicalString: String =
    (Vector(
      "inputs=" + inputs.toVector.map(_.value).mkString(","),
      "window=" + window.map(_.canonicalString).getOrElse("none")
    ) ++ narrativeWindow.map(p => "narrativeWindow=" + p.canonicalString).toVector ++ Vector(
      "reducer=" + reducer.value,
      "weighting=" + weighting.canonicalString,
      "missing=" + missing.canonicalString,
      "normalization=" + normalization.map(_.canonicalString).getOrElse("none"),
      "eligibility=" + eligibility.canonicalString,
      "targets=" + targetFamily.map(_.toString.toLowerCase).getOrElse("none"),
      "impl=" + implementationVersion
    )).mkString(";")

  /** A recipe must not slide over two axes at once; enforced by [[FeatureDerivation.of]], the codec
    * decoder, and [[DerivationGraph.add]].
    */
  def hasSingleWindow: Boolean = window.isEmpty || narrativeWindow.isEmpty

  /** Content address of the recipe. */
  def derivationId: Checksum = Checksum.ofText(canonicalString)

  /** Deterministic, fixed-length identifier for the output space of this recipe applied to its
    * inputs. A content address rather than a path so chains of derivations never grow the id.
    */
  def outputSpaceId: FeatureSpaceId =
    FeatureSpaceId.unsafe("derived:" + derivationId.short(32))

  /** Output identity when this recipe ran on a caller-supplied ordered basis. */
  def outputSpaceId(basis: BasisId): FeatureSpaceId =
    FeatureSpaceId.unsafe(
      "derived:" + Checksum.ofText(derivationId.hex + "|" + basis.checksum.hex).short(32)
    )

object FeatureDerivation:
  private val path = "features/derivation"

  /** Checked constructor: rejects a recipe carrying both a surface and a narrative window. The
    * case-class constructor stays public because recipes are also built by `align` and by `copy` in
    * tests; every boundary that admits a recipe (codec, [[DerivationGraph.add]]) re-validates.
    */
  def of(
      inputs: NonEmptyVector[FeatureSpaceId],
      window: Option[WindowPlan],
      reducer: ReducerId,
      weighting: WeightingPolicy,
      missing: MissingValuePolicy,
      normalization: Option[NormalizationPolicy],
      implementationVersion: String,
      eligibility: Eligibility = Eligibility.LexicalTokens,
      targetFamily: Option[TargetFamily] = None,
      narrativeWindow: Option[NarrativeWindowPlan] = None
  ): Either[DomainError, FeatureDerivation] =
    validated(
      FeatureDerivation(
        inputs,
        window,
        reducer,
        weighting,
        missing,
        normalization,
        implementationVersion,
        eligibility,
        targetFamily,
        narrativeWindow
      )
    )

  def validated(d: FeatureDerivation): Either[DomainError, FeatureDerivation] =
    if d.hasSingleWindow then Right(d)
    else
      Left(
        DomainError.InvariantViolation(path, "recipe carries both a surface and a narrative window")
      )

/** Dependency graph over feature spaces: which recipe produced each derived space.
  *
  * Raw spaces have no entry. Adding an edge that would create a cycle is rejected.
  */
final case class DerivationGraph private (edges: Map[FeatureSpaceId, FeatureDerivation]):
  def inputsOf(space: FeatureSpaceId): Set[FeatureSpaceId] =
    edges.get(space).map(_.inputs.toVector.toSet).getOrElse(Set.empty)

  def isRaw(space: FeatureSpaceId): Boolean = !edges.contains(space)

  /** Transitive inputs (raw and derived) of a space. */
  def ancestors(space: FeatureSpaceId): Set[FeatureSpaceId] =
    def go(frontier: List[FeatureSpaceId], seen: Set[FeatureSpaceId]): Set[FeatureSpaceId] =
      frontier match
        case Nil    => seen
        case h :: t =>
          val ins = inputsOf(h).diff(seen)
          go(ins.toList ++ t, seen ++ ins)
    go(List(space), Set.empty)

  /** Every derived space that transitively depends on `changed`: stale after a change. */
  def staleDescendants(changed: FeatureSpaceId): Set[FeatureSpaceId] =
    edges.keySet.filter(out => ancestors(out).contains(changed))

  def add(output: FeatureSpaceId, d: FeatureDerivation): Either[DomainError, DerivationGraph] =
    val path = s"features/derivations/${output.value}"
    if edges.contains(output) then Left(DomainError.DuplicateId("FeatureSpaceId", output.value))
    else if !d.hasSingleWindow then
      Left(
        DomainError.InvariantViolation(path, "recipe carries both a surface and a narrative window")
      )
    else if d.inputs.toVector.contains(output) then
      Left(DomainError.InvariantViolation(path, "space derived from itself"))
    else
      val next = DerivationGraph(edges + (output -> d))
      if next.ancestors(output).contains(output) then
        Left(DomainError.InvariantViolation(path, "derivation cycle"))
      else Right(next)

  def size: Int = edges.size

object DerivationGraph:
  val empty: DerivationGraph = DerivationGraph(Map.empty)
