package storymodel4s.pipeline

import io.circe.Json
import io.circe.jawn.JawnParser
import java.nio.charset.StandardCharsets.UTF_8
import storymodel4s.codec.Canonical
import storymodel4s.core.{Checksum, ContentAddress}
import storymodel4s.corpus.intake.RecallTimingIntake
import storymodel4s.embed.PseudonymizedText

/** Admits recall text to one explicitly local disclosure scope with its actual authority basis. */
object RecallDisclosure:
  /** This slice permits disclosure only to the local workspace. */
  enum Scope:
    case LocalWorkspace

  /** Typed reasons an admission cannot mint a public-corpus authority. */
  enum Refusal:
    case SyntheticTextMismatch
    case UntrustedSherlockLineage
    case InvalidLineage
    case UnknownSherlockSource
    case SherlockByteIdentityMismatch
    case SherlockCsvInvalid
    case SherlockReplayEmpty

  /** Public, digest-bound facts about one admitted public-corpus replay. */
  final class PublicRecord private[RecallDisclosure] (
      val recordDigest: Checksum,
      val upstreamRelease: String,
      val sourceId: String,
      val transformVersion: String,
      val textDigest: Checksum
  ):
    private def fields = (recordDigest, upstreamRelease, sourceId, transformVersion, textDigest)
    override def equals(other: Any): Boolean = other match
      case that: PublicRecord => fields == that.fields
      case _                  => false
    override def hashCode: Int = fields.##
    override def toString: String =
      s"PublicRecord($sourceId, ${recordDigest.short()}, ${textDigest.short()})"

  /** Authority retained by an [[Input]], never reconstructed from a receipt description. */
  sealed trait Basis

  object Basis:
    /** Ordinary local text has no outward public-corpus or pseudonymization authority. */
    case object LocalOnly extends Basis

    /** A public corpus text replayed from a checksum-pinned local artifact. */
    final class PublicCorpusAdmitted private[RecallDisclosure] (val record: PublicRecord)
        extends Basis:
      override def equals(other: Any): Boolean = other match
        case that: PublicCorpusAdmitted => record == that.record
        case _                          => false
      override def hashCode: Int = record.hashCode
      override def toString: String = s"PublicCorpusAdmitted($record)"

    /** A detector-certified pseudonymization witness retained with its exact rendered text. */
    final class CertifiedPseudonymized private[RecallDisclosure] (val payload: PseudonymizedText)
        extends Basis:
      override def equals(other: Any): Boolean = other match
        case that: CertifiedPseudonymized => payload == that.payload
        case _                            => false
      override def hashCode: Int = payload.hashCode
      override def toString: String = "CertifiedPseudonymized(<redacted>)"

  /** Closed text-bound disclosure input. Its factories, rather than a receipt import, mint scope.
    */
  final class Input private[RecallDisclosure] (
      val text: String,
      val basis: Basis,
      val scope: Scope
  ):
    private def fields = (text, basis, scope)
    override def equals(other: Any): Boolean = other match
      case that: Input => fields == that.fields
      case _           => false
    override def hashCode: Int = fields.##
    override def toString: String =
      s"RecallDisclosure.Input(${Checksum.ofText(text).short()}, $basis, $scope)"

  private final case class SherlockEntry(
      sourceId: String,
      byteLength: Int,
      checksum: Checksum,
      recordCount: Int,
      upstreamRelease: String
  )

  private val parser = new JawnParser(maxValueSize = None, allowDuplicateKeys = false)
  private val TrustedSherlockLineageDigest =
    Checksum.unsafe("4b41ef7e6ebacbd0d24800fca0c7ee172a7540b9996cf903edc2782744f9363e")
  private val SherlockHeader = Vector(
    "Words",
    "Princeton Dataspace Word Onset Time (sec)",
    "Princeton Dataspace TR Number",
    "Princeton Dataspace TR Onset (sec)",
    "OpenNeuro Word Onset Time (sec)",
    "OpenNeuro TR Number",
    "OpenNeuro TR Onset (sec)"
  )
  private val SherlockTransformVersion = "sherlock-word-csv-replay/v1"
  private val SyntheticText = "Anna arrived."
  private val SyntheticChecksum =
    Checksum.unsafe("6a13364a36a31e930e61ae1eb25bdc9b777e744b4a775699d937e7f0a5882d4b")

  /** Binds ordinary supplied text to the local-only scope. */
  def local(raw: String): Input = new Input(raw, Basis.LocalOnly, Scope.LocalWorkspace)

  /** Retains a genuine pseudonymization witness and derives the bound text from that witness. */
  def certified(payload: PseudonymizedText): Input =
    new Input(payload.text, new Basis.CertifiedPseudonymized(payload), Scope.LocalWorkspace)

  /** Admits only the fixed project-synthetic control text for courts and CLI examples. */
  def projectSynthetic(raw: String): Either[Refusal, Input] =
    Either.cond(
      raw == SyntheticText && Checksum.ofText(raw) == SyntheticChecksum,
      new Input(
        raw,
        new Basis.PublicCorpusAdmitted(
          new PublicRecord(
            ContentAddress.digest(Vector("project-synthetic-control/v1", SyntheticChecksum.hex)),
            "project-synthetic-control/v1",
            "project-synthetic-anna-arrived",
            "fixed-text/v1",
            SyntheticChecksum
          )
        ),
        Scope.LocalWorkspace
      ),
      Refusal.SyntheticTextMismatch
    )

  /** Replays one trusted Sherlock CSV's word column after checking its version-pinned lineage. */
  def sherlock(
      sourceId: String,
      csv: Array[Byte],
      lineage: Array[Byte]
  ): Either[Refusal, Input] =
    for
      entry <- trustedEntry(sourceId, lineage)
      snapshot = csv.clone()
      _ <- Either.cond(
        snapshot.length == entry.byteLength && Checksum.ofBytes(snapshot) == entry.checksum,
        (),
        Refusal.SherlockByteIdentityMismatch
      )
      text <- replayWords(snapshot, entry.recordCount)
      textDigest = Checksum.ofText(text)
      record = new PublicRecord(
        ContentAddress.digest(
          Vector(
            "sherlock-recall-public-record/v1",
            TrustedSherlockLineageDigest.hex,
            entry.sourceId,
            entry.byteLength.toString,
            entry.checksum.hex,
            entry.recordCount.toString,
            entry.upstreamRelease,
            SherlockTransformVersion,
            textDigest.hex
          )
        ),
        entry.upstreamRelease,
        entry.sourceId,
        SherlockTransformVersion,
        textDigest
      )
    yield new Input(
      text,
      new Basis.PublicCorpusAdmitted(record),
      Scope.LocalWorkspace
    )

  /** True only when the caller retains the same closed input and its exact admitted text. */
  def canPublishLocalWorkspace(input: Input, exactText: String): Boolean =
    input.scope == Scope.LocalWorkspace && input.text == exactText &&
      input.basis != Basis.LocalOnly

  /** A safe receipt rendering: it records identities and scope but never text or a live witness. */
  def receiptDescription(input: Input): String =
    val basis = input.basis match
      case Basis.LocalOnly                      => Json.obj("kind" -> Json.fromString("local-only"))
      case admitted: Basis.PublicCorpusAdmitted =>
        Json.obj(
          "kind" -> Json.fromString("public-corpus-admitted"),
          "recordDigest" -> Json.fromString(admitted.record.recordDigest.hex),
          "upstreamRelease" -> Json.fromString(admitted.record.upstreamRelease),
          "sourceId" -> Json.fromString(admitted.record.sourceId),
          "transformVersion" -> Json.fromString(admitted.record.transformVersion)
        )
      case certified: Basis.CertifiedPseudonymized =>
        val payload = certified.payload
        Json.obj(
          "kind" -> Json.fromString("certified-pseudonymized"),
          "policyId" -> Json.fromString(payload.policyId.value),
          "keyId" -> Json.fromString(payload.keyId.value),
          "payloadReceipt" -> Json.fromString(payload.digest.render),
          "sourceDetection" -> Json.fromString(payload.sourceDetection.fold("absent")(_.render)),
          "destinationDetection" -> Json.fromString(
            payload.destinationDetection.fold("absent")(_.render)
          )
        )
    Canonical.print(
      Json.obj(
        "schemaVersion" -> Json.fromString("recall-disclosure-receipt/v1"),
        "scope" -> Json.fromString("local-workspace"),
        "textDigest" -> Json.fromString(Checksum.ofText(input.text).hex),
        "basis" -> basis
      )
    )

  private def trustedEntry(sourceId: String, lineage: Array[Byte]): Either[Refusal, SherlockEntry] =
    val snapshot = lineage.clone()
    for
      _ <- Either.cond(
        Checksum.ofBytes(snapshot) == TrustedSherlockLineageDigest,
        (),
        Refusal.UntrustedSherlockLineage
      )
      text <- strictUtf8(snapshot).toRight(Refusal.InvalidLineage)
      json <- parser.parse(text).toOption.toRight(Refusal.InvalidLineage)
      cursor = json.hcursor
      schema <- cursor.get[String]("schema").toOption.toRight(Refusal.InvalidLineage)
      _ <- Either.cond(
        schema == "storymodel4s.sherlock.recall-lineage",
        (),
        Refusal.InvalidLineage
      )
      release <- cursor
        .downField("publicRelease")
        .get[String]("tag")
        .toOption
        .toRight(Refusal.InvalidLineage)
      commit <- cursor
        .downField("publicRelease")
        .get[String]("commit")
        .toOption
        .toRight(Refusal.InvalidLineage)
      sources <- cursor.get[Vector[Json]]("sources").toOption.toRight(Refusal.InvalidLineage)
      entry <- sources
        .find(_.hcursor.get[String]("id").toOption.contains(sourceId))
        .toRight(Refusal.UnknownSherlockSource)
      length <- entry.hcursor
        .get[Int]("localCsvByteLength")
        .toOption
        .toRight(Refusal.InvalidLineage)
      checksumText <- entry.hcursor
        .get[String]("localCsvSha256")
        .toOption
        .toRight(Refusal.InvalidLineage)
      checksum <- Checksum.from(checksumText).left.map(_ => Refusal.InvalidLineage)
      count <- entry.hcursor.get[Int]("recordCount").toOption.toRight(Refusal.InvalidLineage)
      _ <- Either.cond(length >= 0 && count > 0, (), Refusal.InvalidLineage)
    yield SherlockEntry(sourceId, length, checksum, count, s"$release@$commit")

  private def replayWords(bytes: Array[Byte], expectedRecords: Int): Either[Refusal, String] =
    RecallTimingIntake
      .records(bytes)
      .left
      .map(_ => Refusal.SherlockCsvInvalid)
      .flatMap { table =>
        val rows = table.drop(1)
        val valid = table.headOption.contains(SherlockHeader) && rows.size == expectedRecords &&
          rows.forall(_.size == SherlockHeader.size)
        val replay = rows.map(_.head.trim).filter(_.nonEmpty).mkString(" ")
        if !valid then Left(Refusal.SherlockCsvInvalid)
        else Either.cond(replay.nonEmpty, replay, Refusal.SherlockReplayEmpty)
      }

  private def strictUtf8(bytes: Array[Byte]): Option[String] =
    val decoded = new String(bytes, UTF_8)
    Option.when(decoded.getBytes(UTF_8).sameElements(bytes))(decoded)
