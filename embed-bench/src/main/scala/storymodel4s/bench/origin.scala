package storymodel4s.bench

import storymodel4s.core.{Checksum, ContentAddress, StorySource}

/** Which partition of a frozen set a story belongs to (protocol §5). */
enum Partition:
  case Development, Calibration, UntouchedTest

  def render: String = this match
    case Development   => "development"
    case Calibration   => "calibration"
    case UntouchedTest => "untouched-test"

/** Protocol Law I5: how likely a story's text or its published summaries sit in a tested channel's
  * training data. Measured by the manifest's recorded procedure (counted summary surfaces on a
  * frozen site list), never by anyone's impression of how famous a story is.
  *
  * It is here rather than in the corpus document because a validity condition the label-minting
  * function does not check is decoration: a set of memorized stories must not be able to mint a
  * calibrated number just because a human forgot to read the protocol.
  */
enum ContaminationRisk:
  case Low, Medium, High

  def render: String = this match
    case Low    => "low"
    case Medium => "medium"
    case High   => "high"

/** Where a bench case comes from. The type is the label discipline: only cases whose origin is a
  * verified [[Origin.Frozen]] set can contribute to a `Calibrated` report; anything under the
  * diagnostic root, or any hand-wired fixture, is [[Origin.Diagnostic]] and can never be promoted.
  *
  * Why: ADR 0001 §D7 and the protocol §7 forbid selecting defaults or calibrating on material a
  * tested channel touched, or on synthetic/regression fixtures (WOG never selects).
  */
enum Origin:
  /** A story from a frozen, manifest-verified set. */
  case Frozen(
      setId: String,
      protocolVersion: Int,
      protocolChecksum: Checksum,
      manifestChecksum: Checksum,
      partition: Partition,
      /** Stories in the SET (not merely in this run) scored `high` — Law I5 clause 3. */
      highRiskStories: Vector[String],
      /** `medium` stories in the untouched-test partition with no recorded justification. */
      unjustifiedMediumInTest: Vector[String]
  )

  /** Material from the diagnostic root or a code fixture; `reason` says why it is not selection
    * evidence.
    */
  case Diagnostic(root: String, reason: String)

  def isFrozen: Boolean = this match
    case Frozen(_, _, _, _, _, _, _) => true
    case Diagnostic(_, _)            => false

  def render: String = this match
    case Frozen(set, v, p, m, part, high, medium) =>
      val i5 =
        if high.nonEmpty then s" contaminated=${high.mkString(",")}"
        else if medium.nonEmpty then s" unjustified-medium=${medium.mkString(",")}"
        else ""
      s"frozen:$set protocol=v$v/${p.short()} manifest=${m.short()} partition=${part.render}$i5"
    case Diagnostic(root, reason) => s"diagnostic:$root ($reason)"

/** The adjudication protocol document, pinned by content checksum (protocol §6, manifest rule 6).
  *
  * Why: a set is frozen under a specific revision of the rules. The bench recomputes the document's
  * checksum at the candidate under test and reports drift; numbers produced under a drifted
  * protocol may not be labelled calibrated until a successor set is frozen.
  */
object ProtocolDocument:
  val path: String = "docs/plans/2026-08-28-m1-fixture-adjudication-protocol.md"
  val version: Int = 1

  /** `canonicalText` SHA-256 of the protocol document at the revision this bench was built against.
    * A test recomputes it from the repository so an unnoticed edit fails the build.
    */
  val pinned: Checksum =
    Checksum.unsafe("510a1a9dc738290f349429ecde08e1f014e12e08f1392266b93c4b064f3d872e")

  /** The checksum of a protocol document's text, canonicalized exactly as a story text is. */
  def checksumOf(documentText: String): Checksum =
    Checksum.ofText(StorySource.canonicalize(documentText))

/** Typed reasons a frozen set fails verification. Every reason is fail-closed: no case is produced.
  */
enum FreezeError:
  case MissingFile(setId: String, path: String)
  case ChecksumMismatch(setId: String, path: String, recorded: Checksum, actual: Checksum)
  case SetIdMismatch(setId: String, expectedSuffix: String)
  case EmptySet(setId: String)
  case NoStoryPartition(setId: String, storyId: String)
  case NoStoryContamination(setId: String, storyId: String)

  def message: String = this match
    case MissingFile(s, p)            => s"$s: manifest lists $p but the store has no such file"
    case ChecksumMismatch(s, p, r, a) => s"$s: $p recorded ${r.short()} but read ${a.short()}"
    case SetIdMismatch(s, suffix)     => s"$s: set id does not end with the manifest suffix $suffix"
    case EmptySet(s)                  => s"$s: the manifest lists no story"
    case NoStoryPartition(s, story)   => s"$s: story $story has no partition"
    case NoStoryContamination(s, story) =>
      s"$s: story $story has no recorded contaminationRisk (Law I5); a manifest that predates " +
        "the law fails closed rather than defaulting to low"

/** The typed content of a frozen set's `manifest.json` (protocol §6). Parsing the JSON is the
  * codec's job (the codec module is not a dependency of the bench); the bench owns the verification
  * rules over the typed value.
  */
final case class FrozenManifest(
    setId: String,
    protocolVersion: Int,
    protocolChecksum: Checksum,
    files: Map[String, Checksum],
    stories: Vector[String],
    partitions: Map[String, Partition],
    /** Law I5 clause 1: the measured risk of every story. Absence is a verification failure. */
    contamination: Map[String, ContaminationRisk],
    /** Written justifications for `medium` stories in the untouched-test partition (clause 2). */
    mediumJustifications: Map[String, String] = Map.empty
):
  /** Content address of the manifest itself: every field, in a canonical order. */
  def checksum: Checksum =
    ContentAddress.digest(
      Vector("frozen-manifest/v1", protocolVersion.toString, protocolChecksum.hex) ++
        files.toVector.sortBy(_._1).flatMap { case (p, c) => Vector(p, c.hex) } ++
        stories.sorted ++
        partitions.toVector.sortBy(_._1).flatMap { case (s, p) => Vector(s, p.render) } ++
        contamination.toVector.sortBy(_._1).flatMap { case (s, r) => Vector(s, r.render) } ++
        mediumJustifications.toVector.sortBy(_._1).flatMap { case (s, j) => Vector(s, j) }
    )

  /** Stories scored `high` anywhere in the set (Law I5 clause 3). */
  def highRiskStories: Vector[String] =
    stories.filter(s => contamination.get(s).contains(ContaminationRisk.High)).sorted

  /** `medium` stories in the untouched-test partition carrying no written justification. */
  def unjustifiedMediumInTest: Vector[String] =
    stories
      .filter(s =>
        contamination.get(s).contains(ContaminationRisk.Medium) &&
          partitions.get(s).contains(Partition.UntouchedTest) &&
          !mediumJustifications.get(s).exists(_.trim.nonEmpty)
      )
      .sorted

  /** The suffix a conforming set id carries: `f-<yyyymmdd>-<8 hex of the manifest checksum>`. */
  def idSuffix: String = checksum.hex.take(8)

/** A frozen set whose every file has been read and matched against its manifest. The constructor is
  * private: the only way to obtain one is [[FrozenSet.verify]].
  */
final class FrozenSet private[bench] (val manifest: FrozenManifest):
  def origin(storyId: String): Option[Origin.Frozen] =
    manifest.partitions.get(storyId).map { p =>
      Origin.Frozen(
        manifest.setId,
        manifest.protocolVersion,
        manifest.protocolChecksum,
        manifest.checksum,
        p,
        manifest.highRiskStories,
        manifest.unjustifiedMediumInTest
      )
    }

object FrozenSet:
  /** Verify a manifest against a file store (the FREEZE rule, protocol §6): every listed file must
    * exist and hash to its recorded checksum; the set id must carry the manifest's suffix; every
    * story must have a partition. Fails closed on the first violation.
    */
  def verify(
      manifest: FrozenManifest,
      read: String => Option[Array[Byte]]
  ): Either[FreezeError, FrozenSet] =
    val id = manifest.setId
    if manifest.stories.isEmpty then Left(FreezeError.EmptySet(id))
    else if !id.endsWith(manifest.idSuffix) then
      Left(FreezeError.SetIdMismatch(id, manifest.idSuffix))
    else
      val partitionError =
        manifest.stories
          .find(s => !manifest.partitions.contains(s))
          .map(FreezeError.NoStoryPartition(id, _))
          .orElse(
            manifest.stories
              .find(s => !manifest.contamination.contains(s))
              .map(FreezeError.NoStoryContamination(id, _))
          )
      partitionError match
        case Some(e) => Left(e)
        case None    =>
          val fileError =
            manifest.files.toVector.sortBy(_._1).iterator.flatMap { case (path, recorded) =>
              read(path) match
                case None        => Some(FreezeError.MissingFile(id, path))
                case Some(bytes) =>
                  val actual = Checksum.ofBytes(bytes)
                  if actual == recorded then None
                  else Some(FreezeError.ChecksumMismatch(id, path, recorded, actual))
            }
          fileError.nextOption().toLeft(new FrozenSet(manifest))
