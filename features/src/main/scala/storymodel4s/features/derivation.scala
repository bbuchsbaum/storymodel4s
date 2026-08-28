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
  case Token, Window, Sentence, Boundary, Turn, Situation, Segment

object TargetFamily:
  def of(t: FeatureTarget): TargetFamily = t match
    case _: FeatureTarget.Token     => Token
    case _: FeatureTarget.Window    => Window
    case _: FeatureTarget.Sentence  => Sentence
    case _: FeatureTarget.Boundary  => Boundary
    case _: FeatureTarget.Turn      => Turn
    case _: FeatureTarget.Situation => Situation
    case _: FeatureTarget.Segment   => Segment

/** The executable recipe that produced a derived track from its inputs.
  *
  * Why: every smoothed or aggregated value must be replayable and diffable; the recipe is
  * content-addressed so caches and dependency graphs can key on it (design record §110). Every
  * parameter that changes the output is part of the recipe, including eligibility and target
  * family.
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
    targetFamily: Option[TargetFamily] = None
):
  def canonicalString: String =
    Vector(
      "inputs=" + inputs.toVector.map(_.value).mkString(","),
      "window=" + window.map(_.canonicalString).getOrElse("none"),
      "reducer=" + reducer.value,
      "weighting=" + weighting.canonicalString,
      "missing=" + missing.canonicalString,
      "normalization=" + normalization.map(_.canonicalString).getOrElse("none"),
      "eligibility=" + eligibility.canonicalString,
      "targets=" + targetFamily.map(_.toString.toLowerCase).getOrElse("none"),
      "impl=" + implementationVersion
    ).mkString(";")

  /** Content address of the recipe. */
  def derivationId: Checksum = Checksum.ofText(canonicalString)

  /** Deterministic, fixed-length identifier for the output space of this recipe applied to its
    * inputs. A content address rather than a path so chains of derivations never grow the id.
    */
  def outputSpaceId: FeatureSpaceId =
    FeatureSpaceId.unsafe("derived:" + derivationId.short(32))

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
