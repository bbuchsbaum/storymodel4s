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

/** A core [[storymodel4s.core.BuildReceipt]] extended with per-stage records and layer coverage. */
final case class ExtendedBuildReceipt(
    receipt: BuildReceipt,
    stages: Vector[StageRecord],
    layerCoverage: Map[LayerId, LayerCoverage]
)

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
    ExtendedBuildReceipt(
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
