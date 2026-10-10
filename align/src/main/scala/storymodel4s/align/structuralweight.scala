package storymodel4s.align

import storymodel4s.core.Checksum

/** An exact reduced fraction keeps endpoint dispatch and policy identity independent of rounding.
  */
final class StructuralWeight private (val numerator: Long, val denominator: Long):
  def isReference: Boolean = numerator == 0L
  def isReconstruction: Boolean = numerator == denominator
  private[align] def coefficient: Double = numerator.toDouble / denominator.toDouble
  override def equals(other: Any): Boolean = other match
    case w: StructuralWeight => numerator == w.numerator && denominator == w.denominator
    case _                   => false
  override def hashCode: Int = (numerator, denominator).hashCode
  override def toString: String = s"$numerator/$denominator"

object StructuralWeight:
  val Reference: StructuralWeight = new StructuralWeight(0L, 1L)
  val Reconstruction: StructuralWeight = new StructuralWeight(1L, 1L)

  /** Reduced nonnegative signed-64-bit components are the supported exact wire domain. */
  def of(numerator: Long, denominator: Long): Either[AlignError, StructuralWeight] =
    if denominator > 0L && numerator >= 0L && numerator <= denominator &&
      BigInt(numerator).gcd(BigInt(denominator)) == BigInt(1)
    then Right(new StructuralWeight(numerator, denominator))
    else Left(AlignError.InvalidConfig("structural weight", "expected reduced fraction in [0,1]"))

  def parse(value: String): Either[AlignError, StructuralWeight] =
    val refused =
      AlignError.InvalidConfig("structural weight", "expected canonical numerator/denominator")
    value.split("/", -1).toList match
      case n :: d :: Nil if n.matches("0|[1-9][0-9]*") && d.matches("[1-9][0-9]*") =>
        (n.toLongOption, d.toLongOption) match
          case (Some(numerator), Some(denominator)) => of(numerator, denominator)
          case _                                    => Left(refused)
      case _ => Left(refused)

/** Versioned transition coefficients define interior posterior mass without mixing estimands. */
final class StructuralProfile private (
    val weight: StructuralWeight,
    val inference: HsmmConfig,
    val preset: StructuredReconstruction.Profile
):
  val fingerprint: Checksum = MappingRender.digest(
    Vector(
      "structural-coefficients/sherlock/v1",
      weight.toString,
      preset.fingerprint.hex,
      inference.fingerprint.hex,
      "uniform-initial;unit-duration;terminal-one;raw-argmax;no-fill"
    )
  )
  override def equals(other: Any): Boolean = other match
    case p: StructuralProfile => fingerprint == p.fingerprint
    case _                    => false
  override def hashCode: Int = fingerprint.hashCode
  override def toString: String = s"StructuralProfile($weight,${fingerprint.hex})"

object StructuralProfile:
  /** Every coefficient, including external entry and persistence, receives the declared weight. */
  def of(weight: StructuralWeight): Either[AlignError, StructuralProfile] =
    val preset = StructuredReconstruction.Profile.sherlock()
    HsmmConfig
      .of(
        temperature = preset.inference.temperature,
        transitions =
          TransitionModel(preset.inference.transitions.theta.map { (kind, coefficient) =>
            kind -> (weight.coefficient * coefficient)
          }),
        refinementPasses = 0,
        refinementWeight = 0.0
      )
      .map(new StructuralProfile(weight, _, preset))

/** A weighted member retains its genuine inference result and the execution that supplied evidence.
  */
final class StructuralSelection private (
    val execution: MappingRun.Execution,
    val profile: StructuralProfile,
    val inference: Option[HsmmResult]
):
  val weight: StructuralWeight = profile.weight
  val identity: Checksum = MappingRender.digest(
    Vector(
      "weighted-mapping-member/v1",
      execution.identity.hex,
      profile.fingerprint.hex
    )
  )
  val kind: MeasureKind =
    if weight.isReference then MeasureKind.NormalizedScoreMass else MeasureKind.ModelPosterior
  lazy val record: Either[MappingRefusal, MappingResult] =
    execution.record(MappingRun.Member.Weighted(this))
  override def equals(other: Any): Boolean = other match
    case s: StructuralSelection => identity == s.identity
    case _                      => false
  override def hashCode: Int = identity.hashCode
  override def toString: String = s"StructuralSelection($weight,${profile.fingerprint.hex})"

object StructuralSelection:
  def of(
      execution: MappingRun.Execution,
      weight: StructuralWeight
  ): Either[AlignError, StructuralSelection] =
    for
      profile <- StructuralProfile.of(weight)
      _ <- Either.cond(
        execution.reconstruction.profile == profile.preset && execution.reconstruction.fill.isEmpty,
        (),
        AlignError.InvalidConfig("weighted mapping", "requires named no-fill Sherlock preset")
      )
      result <-
        if weight.isReference || weight.isReconstruction then Right(None)
        else
          GraphHsmm
            .infer(
              execution.recall,
              execution.view,
              execution.reference.evidence,
              profile.inference
            )
            .map(Some(_))
    yield new StructuralSelection(execution, profile, result)
