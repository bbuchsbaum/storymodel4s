package storymodel4s.corpus

import cats.data.NonEmptyVector

import io.circe.Json
import storymodel4s.core.{Checksum, OpaqueId}

object CorpusId extends OpaqueId("CorpusId")
type CorpusId = CorpusId.T

object RecordId extends OpaqueId("RecordId")
type RecordId = RecordId.T

/** One accounted-for artifact: identity, where it sits, and what it must hash to. */
final case class ArtifactRecord(
    id: ArtifactId,
    path: RelativeArtifactPath,
    byteLength: Long,
    sha256: Checksum,
    role: String
)

/** A reference to a typed sidecar record, BY ARTIFACT IDENTITY rather than by path.
  *
  * This is the whole of the sidecar fix. A reference that merely named a path would reopen "verify
  * one thing, read another" for exactly the records that carry the crosswalks: `timebase-repair`,
  * `alias-map`, `annotation-lineage`. Naming an `ArtifactId` makes a sidecar resolvable only inside
  * a verified snapshot, and makes a dangling reference a verification failure rather than a runtime
  * surprise.
  */
final case class RecordRef(id: RecordId, schema: String, schemaVersion: Int, artifact: ArtifactId)

enum AdmissionState:
  case Proposed, Admitted, Declined

  def render: String = productPrefix.toLowerCase

/** What a source set's admission RECORDS. It never decides: admission is an owner's act. */
final case class AdmissionStatus(
    state: AdmissionState,
    courtOpened: Boolean,
    blocking: Vector[String]
)

/** What may leave the data root. Declared so a receipt can be checked against it. */
final case class ContentPolicy(
    participantProsePresent: Boolean,
    stimulusTranscriptPresent: Boolean,
    externalOnlyClasses: Vector[String]
)

/** The thin v2 source manifest: artifact identity plus references to typed records.
  *
  * Deliberately embeds no `Clock`, `Segmentation`, `CodeBook` or `Capability`. An earlier revision
  * did, and that is what stopped its own minimum subset from compiling; it would also have folded
  * the rich existing records into one flat schema. `docs/data/sherlock/` is already organized the
  * right way, with `timebase-repair`, `alias-map` and `annotation-lineage` as separate files, and
  * `timebase-repair.json` in particular carries `certifies`/`doesNotCertify`, `nonEquivalences`,
  * `scientificRestrictions` and a `whyNotRepaired` note that a flat manifest would destroy.
  */
final class SourceManifest private[corpus] (
    val corpus: CorpusId,
    val schema: String,
    val schemaVersion: Int,
    val artifacts: Vector[ArtifactRecord],
    val records: Vector[RecordRef],
    val admission: AdmissionStatus,
    val contentPolicy: ContentPolicy,
    val nonClaims: Vector[String],
    val extensions: Map[String, Json]
):
  def artifact(id: ArtifactId): Option[ArtifactRecord] = artifacts.find(_.id == id)
  def declaredIds: Set[ArtifactId] = artifacts.map(_.id).toSet
  override def toString: String =
    s"SourceManifest(${corpus.value}, ${artifacts.size} artifacts, ${records.size} records)"

object SourceManifest:
  val Schema: String = "storymodel4s.corpus.source-manifest"
  val SchemaVersion: Int = 2

  /** Builds a manifest, refusing the structural errors that would make verification meaningless. */
  private[corpus] def of(
      corpus: CorpusId,
      artifacts: Vector[ArtifactRecord],
      records: Vector[RecordRef],
      admission: AdmissionStatus,
      contentPolicy: ContentPolicy,
      nonClaims: Vector[String],
      extensions: Map[String, Json]
  ): Either[VerificationFailure, SourceManifest] =
    val dupes = artifacts.groupBy(_.id).collect { case (id, xs) if xs.sizeIs > 1 => id }
    if artifacts.isEmpty then Left(VerificationFailure.EmptyManifest)
    else if dupes.nonEmpty then Left(VerificationFailure.DuplicateArtifact(dupes.head))
    else
      val declared = artifacts.map(_.id).toSet
      records.find(r => !declared.contains(r.artifact)) match
        case Some(r) => Left(VerificationFailure.DanglingRecordRef(r.id, r.artifact))
        case None    =>
          Right(
            new SourceManifest(
              corpus,
              Schema,
              SchemaVersion,
              artifacts,
              records,
              admission,
              contentPolicy,
              nonClaims,
              extensions
            )
          )

/** Why a snapshot could not be verified. Carries no content and no absolute path. */
enum VerificationFailure:
  case EmptyManifest
  case DuplicateArtifact(id: ArtifactId)
  case WrongSchema(found: String)
  case WrongSchemaVersion(found: Int)

  /** Declared by the manifest, absent from the store. */
  case MissingFromStore(id: ArtifactId)

  /** Present in the store, absent from the manifest. The direction an un-enumerable reader cannot
    * check, and the reason [[ArtifactStore]] must list.
    */
  case NotInManifest(id: ArtifactId)
  case ByteLengthMismatch(id: ArtifactId, expected: Long, found: Long)
  case ChecksumMismatch(id: ArtifactId, expected: Checksum, found: Checksum)
  case Unreadable(id: ArtifactId, refusal: IntakeRefusal)
  case DanglingRecordRef(record: RecordId, artifact: ArtifactId)

  def message: String = this match
    case EmptyManifest         => "manifest declares no artifacts"
    case DuplicateArtifact(id) => s"${id.value} is declared twice"
    case WrongSchema(f)        => s"schema is '$f', expected '${SourceManifest.Schema}'"
    case WrongSchemaVersion(f) => s"schema version is $f, expected ${SourceManifest.SchemaVersion}"
    case MissingFromStore(id)  => s"${id.value} is declared but absent from the snapshot"
    case NotInManifest(id)     => s"${id.value} is in the snapshot but not declared"
    case ByteLengthMismatch(id, e, f) => s"${id.value} is $f bytes, declared $e"
    case ChecksumMismatch(id, e, f)   =>
      s"${id.value} hashes to ${f.short()}, declared ${e.short()}"
    case Unreadable(id, r)          => s"${id.value}: ${r.message}"
    case DanglingRecordRef(rec, ar) => s"record ${rec.value} names undeclared artifact ${ar.value}"

/** An enumerable source of artifact bytes.
  *
  * `list` is not a convenience. Without it, "present in the snapshot but absent from the manifest"
  * is uncheckable, and an unaccounted artifact can sit beside the accounted ones unnoticed. A
  * function `ArtifactId => Option[Array[Byte]]` cannot be enumerated, which is why an earlier
  * design could not implement its own completeness test.
  */
trait ArtifactStore:
  def list: Vector[ArtifactId]
  def bytes(id: ArtifactId): Either[IntakeRefusal, Array[Byte]]

/** Bytes that THIS process hashed, held in storage it owns.
  *
  * `IArray`, not `Array`. Two aliasing routes were demonstrated by probe: writing through a public
  * accessor, and writing through the array the store still holds. Making the carrier non-case
  * addresses neither, so `verify` clones on entry and exposes only an immutable view.
  */
final class VerifiedArtifact private[corpus] (
    val id: ArtifactId,
    val bytes: IArray[Byte],
    val checksum: Checksum
):
  def byteLength: Int = bytes.length

  /** A fresh mutable copy for callers that need one. Never the backing store. */
  def toArray: Array[Byte] = IArray.genericWrapArray(bytes).toArray

  override def toString: String =
    s"VerifiedArtifact(${id.value},bytes=${bytes.length},checksum=${checksum.short()})"

/** A manifest and the bytes that were checked against it.
  *
  * Unforgeable and unobtainable: the only constructor is [[SourceManifest.verify]], and it carries
  * what it verified rather than a promise that something was verified elsewhere. An earlier design
  * discarded the bytes it hashed, so the reader re-obtained them from an unstated source and
  * nothing bound the bytes hashed to the bytes read.
  */
final class Verified private[corpus] (
    val manifest: SourceManifest,
    val artifacts: Vector[VerifiedArtifact]
):
  private lazy val byId: Map[ArtifactId, VerifiedArtifact] = artifacts.map(a => a.id -> a).toMap

  def artifact(id: ArtifactId): Option[VerifiedArtifact] = byId.get(id)

  /** Resolves a typed sidecar record to the bytes that were verified for it.
    *
    * Refuses a reference whose declared schema or version disagrees with the record, so a sidecar's
    * identity is checked HERE -- inside the snapshot -- rather than trusted from the reference.
    */
  def record(
      id: RecordId,
      schema: String,
      schemaVersion: Int
  ): Either[VerificationFailure, VerifiedArtifact] =
    manifest.records.find(_.id == id) match
      case None => Left(VerificationFailure.DanglingRecordRef(id, ArtifactId.unsafe("<unknown>")))
      case Some(ref) =>
        if ref.schema != schema then Left(VerificationFailure.WrongSchema(ref.schema))
        else if ref.schemaVersion != schemaVersion then
          Left(VerificationFailure.WrongSchemaVersion(ref.schemaVersion))
        else
          byId
            .get(ref.artifact)
            .toRight(VerificationFailure.DanglingRecordRef(id, ref.artifact))

  override def toString: String =
    s"Verified(${manifest.corpus.value}, ${artifacts.size} artifacts)"

/** Verification: the only door to a [[Verified]]. */
object Verify:

  /** Hashes every declared artifact from bytes this process copied, and checks the snapshot in BOTH
    * directions: nothing declared may be missing, and nothing present may be undeclared.
    *
    * Accumulates every failure rather than stopping at the first, so one run tells the whole story
    * of a bad snapshot (design contract rule 12's accumulating half).
    */
  def verify(
      manifest: SourceManifest,
      store: ArtifactStore
  ): Either[NonEmptyVector[VerificationFailure], Verified] =
    val failures = Vector.newBuilder[VerificationFailure]

    if manifest.schema != SourceManifest.Schema then
      failures += VerificationFailure.WrongSchema(manifest.schema)
    if manifest.schemaVersion != SourceManifest.SchemaVersion then
      failures += VerificationFailure.WrongSchemaVersion(manifest.schemaVersion)

    val present = store.list.toSet
    val declared = manifest.declaredIds
    declared.diff(present).toVector.sortBy(_.value).foreach { id =>
      failures += VerificationFailure.MissingFromStore(id)
    }
    present.diff(declared).toVector.sortBy(_.value).foreach { id =>
      failures += VerificationFailure.NotInManifest(id)
    }

    val verified = Vector.newBuilder[VerifiedArtifact]
    manifest.artifacts.filter(r => present.contains(r.id)).foreach { rec =>
      store.bytes(rec.id) match
        case Left(refusal) => failures += VerificationFailure.Unreadable(rec.id, refusal)
        case Right(raw)    =>
          // Copy BEFORE hashing, into storage this object owns. Hashing the store's array and then
          // retaining it leaves the store able to change what was "verified" afterwards.
          val owned = raw.clone()
          if owned.length.toLong != rec.byteLength then
            failures += VerificationFailure
              .ByteLengthMismatch(rec.id, rec.byteLength, owned.length.toLong)
          else
            val actual = Checksum.ofBytes(owned)
            if actual != rec.sha256 then
              failures += VerificationFailure.ChecksumMismatch(rec.id, rec.sha256, actual)
            else verified += new VerifiedArtifact(rec.id, IArray.unsafeFromArray(owned), actual)
    }

    val found = failures.result()
    NonEmptyVector.fromVector(found) match
      case Some(nev) => Left(nev)
      case None      => Right(new Verified(manifest, verified.result()))
