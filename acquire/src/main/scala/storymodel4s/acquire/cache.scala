package storymodel4s.acquire

import storymodel4s.core.*

/** Per-stage inputs that must be part of a stage's cache key but are not the story input, the
  * schema, or the build-wide configuration: the prompt packages the stage's agents run under and
  * the provider decoding parameters and seed (review finding #36).
  *
  * Folding these into one global `configHash` would invalidate every stage when one critic's prompt
  * is bumped, or nothing if the bump is forgotten. Keeping them per stage makes invalidation exact.
  */
final case class StageLocalConfig(
    promptPackages: Vector[PromptPackageRef],
    providerParams: Map[String, String],
    seed: Option[Long]
):
  /** Deterministic digest: prompt checksums sorted, params sorted by key, then the seed. */
  def checksum: Checksum =
    ContentAddress.digest(
      Vector("stage-local") ++
        promptPackages.map(p => s"prompt=${p.name}@${p.version}#${p.checksum.hex}").sorted ++
        providerParams.toVector.sortBy(_._1).map((k, v) => s"param=$k=$v") ++
        Vector(s"seed=${seed.fold("none")(_.toString)}")
    )

object StageLocalConfig:
  val empty: StageLocalConfig = StageLocalConfig(Vector.empty, Map.empty, None)

/** Content-addressed key for one stage execution (design record §31.2).
  *
  * Changing embeddings does not invalidate sentence segmentation; changing the hierarchy does not
  * rerun entities. The key covers exactly the inputs a stage's output depends on: the input
  * artifact, the stage schema, the build-wide configuration, the provider fingerprint, and the
  * stage-local prompt packages, parameters, and seed.
  */
object StageCacheKey:
  opaque type StageCacheKey = Checksum
  def of(
      inputChecksum: Checksum,
      stageSchemaVersion: String,
      configHash: Checksum,
      providerFingerprint: Fingerprint,
      local: StageLocalConfig
  ): StageCacheKey =
    ContentAddress.digest(
      Vector(
        "stage-cache",
        inputChecksum.hex,
        stageSchemaVersion,
        configHash.hex,
        providerFingerprint.value,
        local.checksum.hex
      )
    )

  /** Key for a stage with no prompt packages, parameters, or seed of its own. */
  def of(
      inputChecksum: Checksum,
      stageSchemaVersion: String,
      configHash: Checksum,
      providerFingerprint: Fingerprint
  ): StageCacheKey =
    of(inputChecksum, stageSchemaVersion, configHash, providerFingerprint, StageLocalConfig.empty)

  extension (k: StageCacheKey) def checksum: Checksum = k

  /** Reconstruct a cache-key identity already validated as a checksum by the wire codec. */
  def fromChecksum(checksum: Checksum): StageCacheKey = checksum

  given cats.Show[StageCacheKey] = cats.Show.show(_.hex)
  given cats.Order[StageCacheKey] = cats.Order[Checksum]
type StageCacheKey = StageCacheKey.StageCacheKey

/** Record of one stage run: what went in, what came out, and the provider calls made. */
final case class StageRecord(
    stage: StageId,
    key: StageCacheKey,
    inputs: Vector[Checksum],
    outputs: Vector[Checksum],
    calls: Vector[ProviderCall],
    cached: Boolean
):
  /** Digest of the outputs, recorded in the build receipt. */
  def outputChecksum: Checksum = ContentAddress.digest(outputs.map(_.hex))

/** A representation layer whose acquisition may be attempted or skipped (roadmap §3, item 9). */
object LayerId extends OpaqueId("LayerId")
type LayerId = LayerId.T

/** Distinguishes "this layer was never attempted" from "attempted and found empty". */
enum LayerCoverage:
  case NotAttempted
  case Attempted

/** A structurally checked core [[storymodel4s.core.BuildReceipt]] with its stage records and layer
  * coverage.
  *
  * Structural coherence is not evidence that a build ran. In particular, this value cannot issue
  * view authority: every input accepted by [[ExtendedBuildReceipt.of]] is still caller data.
  */
final class ExtendedBuildReceipt private (
    val receipt: BuildReceipt,
    val stages: Vector[StageRecord],
    val layerCoverage: Map[LayerId, LayerCoverage]
):
  private def parts = (receipt, stages, layerCoverage)

  override def equals(other: Any): Boolean = other match
    case that: ExtendedBuildReceipt => parts == that.parts
    case _                          => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String =
    s"ExtendedBuildReceipt(story=${receipt.storyId.value}, stages=${stages.size})"

object ExtendedBuildReceipt:
  /** Check exact ordered stage coherence and the lawful metadata available in this portable model.
    */
  def of(
      receipt: BuildReceipt,
      stages: Vector[StageRecord],
      layerCoverage: Map[LayerId, LayerCoverage]
  ): Either[DomainError, ExtendedBuildReceipt] =
    val expectedStages = stages.map(stage => stage.stage -> stage.outputChecksum)
    val stageIds = stages.map(_.stage)
    if receipt.schemaVersion.trim.isEmpty then
      Left(
        DomainError.InvariantViolation(
          "acquire/build-receipt/schema",
          "build receipt schema version must be nonempty"
        )
      )
    else if receipt.createdAtEpochMillis < 0L then
      Left(
        DomainError.InvariantViolation(
          "acquire/build-receipt/timestamp",
          "build receipt timestamp must be nonnegative"
        )
      )
    else if stageIds.distinct.size != stageIds.size then
      Left(DomainError.DuplicateId("ExtendedBuildReceipt.stage", "stage"))
    else if receipt.stages != expectedStages then
      Left(
        DomainError.InvariantViolation(
          "acquire/build-receipt/stages",
          "core receipt stages must exactly equal the ordered stage records and output checksums"
        )
      )
    else Right(new ExtendedBuildReceipt(receipt, stages, layerCoverage))

/** Accumulates stage records into a receipt. Pure; the orchestrator threads it through stages. */
final case class BuildReceiptBuilder(
    storyId: StoryId,
    sourceChecksum: Checksum,
    schemaVersion: String,
    stages: Vector[StageRecord],
    layerCoverage: Map[LayerId, LayerCoverage]
):
  def record(r: StageRecord): BuildReceiptBuilder = copy(stages = stages :+ r)
  def cover(layer: LayerId, coverage: LayerCoverage): BuildReceiptBuilder =
    copy(layerCoverage = layerCoverage.updated(layer, coverage))
  def build(createdAtEpochMillis: Long): ExtendedBuildReceipt =
    buildChecked(createdAtEpochMillis).fold(
      error => throw new IllegalArgumentException(error.message),
      identity
    )

  /** Checked builder boundary for callers that need invalid metadata reported as data. */
  def buildChecked(createdAtEpochMillis: Long): Either[DomainError, ExtendedBuildReceipt] =
    ExtendedBuildReceipt.of(
      BuildReceipt(
        storyId,
        sourceChecksum,
        schemaVersion,
        stages.map(s => s.stage -> s.outputChecksum),
        createdAtEpochMillis
      ),
      stages,
      layerCoverage
    )

object BuildReceiptBuilder:
  def start(
      storyId: StoryId,
      sourceChecksum: Checksum,
      schemaVersion: String
  ): BuildReceiptBuilder =
    BuildReceiptBuilder(storyId, sourceChecksum, schemaVersion, Vector.empty, Map.empty)
