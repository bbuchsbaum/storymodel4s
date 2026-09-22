package storymodel4s.align

import cats.syntax.all.*
import storymodel4s.core.Checksum

enum Stage:
  case Candidates, Rendering, Scoring, Context, Refinement, Inference, Decision, Projection

final class ProviderIdentity private (
    val provider: String,
    val model: String,
    val artifact: Checksum
)
object ProviderIdentity:
  def of(
      provider: String,
      model: String,
      artifact: Checksum
  ): Either[MappingRefusal, ProviderIdentity] =
    if provider.trim.isEmpty || model.trim.isEmpty then
      Left(MappingRefusal.InvalidValue("provider", "provider and model must be nonempty"))
    else Right(new ProviderIdentity(provider, model, artifact))
enum ProviderStatus:
  case Identified(identity: ProviderIdentity)
  case NotApplicable(reason: String)

/** Caller-supplied stage description. Possession of a receipt does not prove execution. */
final class StageReceipt private (
    val stage: Stage,
    val policy: String,
    val config: Checksum,
    val inputs: Vector[Checksum],
    val provider: ProviderStatus
):
  private[align] def render: String = MappingRender.sequence(
    Vector(
      stage.toString,
      policy,
      config.hex,
      MappingRender.sequence(inputs.map(_.hex)),
      provider match
        case ProviderStatus.Identified(p) =>
          MappingRender.sequence(Vector("identified", p.provider, p.model, p.artifact.hex))
        case ProviderStatus.NotApplicable(reason) =>
          MappingRender.sequence(Vector("not-applicable", reason))
    )
  )
object StageReceipt:
  def of(
      stage: Stage,
      policy: String,
      config: Checksum,
      inputs: Vector[Checksum],
      provider: ProviderStatus
  ): Either[MappingRefusal, StageReceipt] =
    val invalidProvider = provider match
      case ProviderStatus.NotApplicable(reason) => reason.trim.isEmpty
      case _                                    => false
    if policy.trim.isEmpty || invalidProvider then
      Left(
        MappingRefusal.InvalidValue("stage-receipt", "policy and absence reasons must be nonempty")
      )
    else Right(new StageReceipt(stage, policy, config, inputs, provider))

enum UnknownProvenanceReason:
  case HistoricalArtifact, NominationProvenanceUnproven, CallerSuppliedDecision, NotRun

sealed trait StageProvenance
object StageProvenance:
  final class Unknown private (
      val reason: UnknownProvenanceReason,
      val asserted: Option[StageReceipt]
  ) extends StageProvenance
  object Unknown:
    private[StageProvenance] def of(
        reason: UnknownProvenanceReason,
        asserted: Option[StageReceipt]
    ): Unknown = new Unknown(reason, asserted)

  /** Reserved for a later executed-stage producer. G1 supplies no construction door. */
  final class Derived private (val receipt: StageReceipt) extends StageProvenance

  def unknown(reason: UnknownProvenanceReason, asserted: Option[StageReceipt]): Unknown =
    Unknown.of(reason, asserted)
  private[align] def receipt(value: StageProvenance): Option[StageReceipt] = value match
    case p: Unknown => p.asserted
    case p: Derived => Some(p.receipt)
  private[align] def render(value: StageProvenance): String = value match
    case p: Unknown =>
      MappingRender.sequence(
        Vector("unknown", p.reason.toString, MappingRender.optional(p.asserted.map(_.render)))
      )
    case p: Derived => MappingRender.sequence(Vector("derived", p.receipt.render))

final class StageEntryId private (val digest: Checksum):
  override def equals(other: Any): Boolean = other match
    case that: StageEntryId => digest == that.digest
    case _                  => false
  override def hashCode: Int = digest.hashCode
object StageEntryId:
  def of(stage: Stage, provenance: StageProvenance): Either[MappingRefusal, StageEntryId] =
    StageProvenance.receipt(provenance) match
      case Some(receipt) if receipt.stage != stage =>
        Left(MappingRefusal.StageMismatch(stage, receipt.stage))
      case _ =>
        Right(
          new StageEntryId(
            MappingRender.digest(
              Vector("stage-entry/v1", stage.toString, StageProvenance.render(provenance))
            )
          )
        )

final class StageEntry private (
    val id: StageEntryId,
    val stage: Stage,
    val provenance: StageProvenance
)
object StageEntry:
  def of(stage: Stage, provenance: StageProvenance): Either[MappingRefusal, StageEntry] =
    StageEntryId.of(stage, provenance).map(id => new StageEntry(id, stage, provenance))

/** A complete canonical ledger. Unknown and not-run stages remain present. */
final class StageLedger private (val entries: Vector[StageEntry]):
  def get(id: StageEntryId): Option[StageEntry] = entries.find(_.id == id)
  def at(stage: Stage): Vector[StageEntry] = entries.filter(_.stage == stage)
object StageLedger:
  def of(entries: Vector[StageEntry]): Either[MappingRefusal, StageLedger] =
    val duplicates = entries
      .groupBy(_.id)
      .collect { case (id, xs) if xs.size > 1 => id }
      .toVector
      .sortBy(_.digest.hex)
    val missing = Stage.values.toVector.filterNot(s => entries.exists(_.stage == s))
    if duplicates.nonEmpty then Left(MappingRefusal.DuplicateStageEntry(duplicates.head))
    else if missing.nonEmpty then Left(MappingRefusal.MissingStages(missing))
    else if entries != entries.sortBy(e => (e.stage.ordinal, e.id.digest.hex)) then
      Left(MappingRefusal.NonCanonicalLedger)
    else
      entries
        .traverse_ { entry =>
          StageEntryId.of(entry.stage, entry.provenance).flatMap { expected =>
            if expected == entry.id then Right(())
            else
              Left(MappingRefusal.InvalidValue("stage-entry.id", "identifier does not recompute"))
          }
        }
        .map(_ => new StageLedger(entries))

final class UnitStageRefs private (
    val inference: StageEntryId,
    val candidates: StageEntryId,
    val decision: StageEntryId
)
object UnitStageRefs:
  def of(
      ledger: StageLedger,
      inference: StageEntryId,
      candidates: StageEntryId,
      decision: StageEntryId
  ): Either[MappingRefusal, UnitStageRefs] =
    Vector(inference -> Stage.Inference, candidates -> Stage.Candidates, decision -> Stage.Decision)
      .traverse_ { case (id, expected) =>
        ledger.get(id) match
          case None                                   => Left(MappingRefusal.DanglingStageEntry(id))
          case Some(entry) if entry.stage != expected =>
            Left(MappingRefusal.StageMismatch(expected, entry.stage))
          case Some(_) => Right(())
      }
      .map(_ => new UnitStageRefs(inference, candidates, decision))
