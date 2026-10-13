package storymodel4s.align

import storymodel4s.core.Checksum
import storymodel4s.recall.SegmentationId

/** A readout declares its axis and original grain before an execution can authorize it. */
object MeasurementCompatibility:
  enum Axis:
    case Discourse, StoryWorld
  enum UnknownTransitions:
    case PreserveAdjacencies
  enum Quantity:
    case OrganizationCounts
  enum Verdict:
    case CompatibleWithDeclaredPolicy, ModelDependentOnly, Incompatible, UnknownProvenance
  enum Reason:
    case OriginalUnitsRequired, WrongResolution, ForeignSegmentation, StructuredInference
    case UnprovenUpstreamStages, ExecutedIndependentContent, UnsupportedOrigin

  /** Checked declaration; it is a request and conveys no execution authority. */
  final class Request private (
      val axis: Axis,
      val organization: AnalysisGrain,
      val level: Int,
      val unknown: UnknownTransitions
  ):
    val quantity: Quantity = Quantity.OrganizationCounts
    val digest: Checksum = MappingRender.digest(
      Vector(
        "organization-request/v1",
        axis.toString,
        organization match
          case AnalysisGrain.InferenceUnit(s) => s.digest.hex
          case other                          => other.toString
        ,
        level.toString,
        unknown.toString
      )
    )
    override def equals(other: Any): Boolean = other match
      case r: Request => digest == r.digest
      case _          => false
    override def hashCode: Int = digest.hashCode
    override def toString: String = s"OrganizationRequest($axis, $organization, $level)"
  object Request:
    def of(
        axis: Axis,
        segmentation: SegmentationId,
        level: Int,
        unknown: UnknownTransitions = UnknownTransitions.PreserveAdjacencies
    ): Either[MappingRefusal, Request] =
      if level < 0 then Left(MappingRefusal.InvalidValue("organization.level", "negative level"))
      else Right(new Request(axis, AnalysisGrain.InferenceUnit(segmentation), level, unknown))

  /** A description of inspected provenance; only the owning producer may certify its readout. */
  final class Assessment private[MeasurementCompatibility] (
      val verdict: Verdict,
      val reasons: Vector[Reason]
  ):
    override def equals(other: Any): Boolean = other match
      case a: Assessment => verdict == a.verdict && reasons == a.reasons
      case _             => false
    override def hashCode: Int = (verdict, reasons).hashCode
    override def toString: String = s"Compatibility($verdict, $reasons)"

  /** Historical or numerical records cannot self-certify missing producer/provider provenance. */
  def inspect(record: MappingResult, request: Request): Assessment =
    val mismatch = request.organization != record.roles.organization ||
      request.organization != AnalysisGrain.InferenceUnit(record.inventory.segmentation)
    if mismatch then new Assessment(Verdict.Incompatible, Vector(Reason.ForeignSegmentation))
    else if record.ledger.entries.exists(e =>
        e.provenance match
          case p: StageProvenance.Derived =>
            p.member match
              case MappingRun.Member.Reference   => false
              case MappingRun.Member.Weighted(s) => !s.weight.isReference
              case _                             => true
          case _ => false
      )
    then new Assessment(Verdict.ModelDependentOnly, Vector(Reason.StructuredInference))
    else if record.policies.universe.grain != TargetGrain.SingleLevel(request.level) then
      new Assessment(Verdict.Incompatible, Vector(Reason.WrongResolution))
    else new Assessment(Verdict.UnknownProvenance, Vector(Reason.UnprovenUpstreamStages))
