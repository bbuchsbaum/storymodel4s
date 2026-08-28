package storymodel4s.core

/** Identifies the exact extractor/model/version that produced a piece of evidence.
  *
  * Convention: `provider:model:version:hash`, but any whitespace-free string is accepted so that
  * human annotators and rules can be fingerprinted too.
  */
object Fingerprint extends OpaqueId("Fingerprint")
type Fingerprint = Fingerprint.T

/** One call to an acquisition provider (rule, local model, remote LLM, human import). */
final case class ProviderCall(
    provider: String,
    model: String,
    version: String,
    promptTemplateVersion: Option[String],
    inputChecksum: Checksum,
    outputChecksum: Checksum,
    params: Map[String, String],
    seed: Option[Long],
    cached: Boolean
)

/** Everything needed to reproduce or audit how a claim was produced. */
final case class Provenance(
    calls: Vector[ProviderCall],
    softwareVersion: String,
    configHash: Checksum
)

object Provenance:
  /** Provenance for deterministic library code with no provider calls. */
  def deterministic(softwareVersion: String, configHash: Checksum): Provenance =
    Provenance(Vector.empty, softwareVersion, configHash)

  /** Provenance for hand-authored fixtures and human adjudication. */
  def human(annotator: String, softwareVersion: String): Provenance =
    Provenance(Vector.empty, softwareVersion, Checksum.ofText(s"human:$annotator"))

/** Content-addressed receipt of a build. */
final case class BuildReceipt(
    storyId: StoryId,
    sourceChecksum: Checksum,
    schemaVersion: String,
    stages: Vector[(StageId, Checksum)],
    createdAtEpochMillis: Long
):
  /** Digest over everything except the timestamp, so equal builds have equal receipts. */
  def contentChecksum: Checksum =
    ContentAddress.digest(
      Vector(storyId.value, sourceChecksum.hex, schemaVersion) ++
        stages.map((s, c) => s"${s.value}=${c.hex}")
    )
