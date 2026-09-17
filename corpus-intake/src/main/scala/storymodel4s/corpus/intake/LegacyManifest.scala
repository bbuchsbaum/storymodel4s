package storymodel4s.corpus.intake

import io.circe.parser.parse as parseJson
import storymodel4s.core.Checksum
import storymodel4s.corpus.*

/** Migrates a v1 per-corpus source manifest into the thin v2 shape.
  *
  * The four staged manifests each declare their own schema name and share seven keys. None of them
  * can be read as v2 as written: Friends declares `storymodel4s.friends.source-manifest` v1, its
  * artifacts carry a `path` and no `id`, and there is no `records` key at all. Sherlock has neither
  * `artifacts` nor `admissionStatus`; its inventory is `mediaParts` and `boundNonVideoRecords`.
  *
  * So migration is a real step, not a rename, and it is a LIFT rather than a rewrite: the v1 file
  * stays the record of what was accounted for, and this produces the v2 value that verification
  * needs. Only the shapes actually present in the staged files are handled; anything else is a
  * typed refusal rather than a guess.
  */
object LegacyManifest:

  enum MigrationRefusal:
    case NotJson(reason: String)
    case UnknownSchema(found: String)
    case NoArtifacts(schema: String)
    case BadArtifact(index: Int, reason: String)

    def message: String = this match
      case NotJson(r)        => s"not JSON: $r"
      case UnknownSchema(f)  => s"no migration for schema '$f'"
      case NoArtifacts(s)    => s"'$s' declares no artifacts array to migrate"
      case BadArtifact(i, r) => s"artifact $i: $r"

  /** Lifts a v1 manifest whose inventory is an `artifacts` array of {path, byteLength, sha256,
    * role}. That is the Friends and Memento shape. The artifact id is its path, which is what the
    * staged files use as an identity in every other record.
    */
  def liftArtifactsArray(
      corpus: CorpusId,
      json: String
  ): Either[MigrationRefusal, SourceManifest] =
    for
      doc <- parseJson(json).left.map(e => MigrationRefusal.NotJson(e.getMessage))
      cur = doc.hcursor
      schema = cur.get[String]("schema").getOrElse("<absent>")
      raw <- cur
        .get[Vector[io.circe.Json]]("artifacts")
        .left
        .map(_ => MigrationRefusal.NoArtifacts(schema))
      records <- raw.zipWithIndex.foldLeft[Either[MigrationRefusal, Vector[ArtifactRecord]]](
        Right(Vector.empty)
      ) { case (acc, (j, i)) => acc.flatMap(done => artifact(j, i).map(done :+ _)) }
      admission = cur.downField("admissionStatus").get[String]("state").toOption match
        case Some("admitted") => AdmissionState.Admitted
        case Some("declined") => AdmissionState.Declined
        case _                => AdmissionState.Proposed
      blocking = cur
        .downField("admissionStatus")
        .get[Vector[String]]("blocking")
        .getOrElse(Vector.empty)
      courtOpened = cur.downField("admissionStatus").get[Boolean]("courtOpened").getOrElse(false)
      nonClaims = cur.get[Vector[String]]("nonClaims").getOrElse(Vector.empty)
      prose = cur
        .downField("contentPolicy")
        .get[Boolean]("participantRecallProsePresent")
        .getOrElse(false)
      transcript = cur
        .downField("contentPolicy")
        .get[Boolean]("stimulusTranscriptPresent")
        .getOrElse(false)
      external = cur
        .downField("contentPolicy")
        .get[Vector[String]]("externalOnlyArtifactClasses")
        .getOrElse(Vector.empty)
      manifest <- SourceManifest
        .of(
          corpus,
          SourceManifest.Schema,
          SourceManifest.SchemaVersion,
          records,
          Vector.empty,
          AdmissionStatus(admission, courtOpened, blocking),
          ContentPolicy(prose, transcript, external),
          nonClaims,
          Map("migratedFrom" -> io.circe.Json.fromString(schema))
        )
        .left
        .map(f => MigrationRefusal.BadArtifact(-1, f.message))
    yield manifest

  private def artifact(j: io.circe.Json, i: Int): Either[MigrationRefusal, ArtifactRecord] =
    val c = j.hcursor
    for
      path <- c.get[String]("path").left.map(_ => MigrationRefusal.BadArtifact(i, "no path"))
      len <- c
        .get[Long]("byteLength")
        .left
        .map(_ => MigrationRefusal.BadArtifact(i, "no byteLength"))
      shaRaw <- c.get[String]("sha256").left.map(_ => MigrationRefusal.BadArtifact(i, "no sha256"))
      sha <- Checksum.from(shaRaw).left.map(_ => MigrationRefusal.BadArtifact(i, "bad sha256"))
      rel <- RelativeArtifactPath
        .from(path)
        .left
        .map(_ => MigrationRefusal.BadArtifact(i, "unsafe path"))
      role = c.get[String]("role").getOrElse("unstated")
    yield ArtifactRecord(ArtifactId.unsafe(path), rel, len, sha, role)

  /** Lifts the Sherlock manifest, whose inventory is a different concept from Friends'.
    *
    * It has no `artifacts` array. Its inventory is `mediaParts` -- two external video files of 285
    * MB and 311 MB, `storage: external-local-only`, with no path -- plus `boundNonVideoRecords`,
    * which names the typed sidecars that ARE committed (`annotation-lineage.json`,
    * `recall-lineage.json`).
    *
    * **The media parts are deliberately out of scope for this lift.** A v2 manifest is verified
    * against a snapshot, and verifying bytes that are external by declaration would report them
    * missing on every machine. Declaring an artifact this contract cannot check would be the same
    * defect it exists to close, one level out. The lift therefore covers the COMMITTED sidecar
    * records, which is what makes `timebase-repair.json` resolvable inside a verified snapshot --
    * the point of the Sherlock retrofit.
    *
    * `records` is where the Sherlock shape pays off: `boundNonVideoRecords` is already a list of
    * typed records referenced by id, which is exactly `RecordRef`. The v1 record was better
    * organized than the flat shape it would have been folded into.
    */
  def liftSherlockRecords(
      corpus: CorpusId,
      json: String,
      recordBytes: Map[String, Array[Byte]]
  ): Either[MigrationRefusal, SourceManifest] =
    for
      doc <- parseJson(json).left.map(e => MigrationRefusal.NotJson(e.getMessage))
      cur = doc.hcursor
      schema = cur.get[String]("schema").getOrElse("<absent>")
      bound <- cur
        .get[Vector[io.circe.Json]]("boundNonVideoRecords")
        .left
        .map(_ => MigrationRefusal.NoArtifacts(schema))
      named <- bound.zipWithIndex.foldLeft[Either[MigrationRefusal, Vector[(String, String)]]](
        Right(Vector.empty)
      ) { case (acc, (j, i)) =>
        acc.flatMap { done =>
          val c = j.hcursor
          for
            id <- c.get[String]("id").left.map(_ => MigrationRefusal.BadArtifact(i, "no id"))
            rec <- c
              .get[String]("record")
              .left
              .map(_ => MigrationRefusal.BadArtifact(i, "no record"))
          yield done :+ (id -> rec)
        }
      }
      present = named.filter((_, file) => recordBytes.contains(file))
      _ <- Either.cond(present.nonEmpty, (), MigrationRefusal.NoArtifacts(schema))
      artifacts <- present.foldLeft[Either[MigrationRefusal, Vector[ArtifactRecord]]](
        Right(Vector.empty)
      ) { case (acc, (_, file)) =>
        acc.flatMap { done =>
          RelativeArtifactPath
            .from(file)
            .left
            .map(_ => MigrationRefusal.BadArtifact(-1, s"unsafe path $file"))
            .map { rel =>
              val bytes = recordBytes(file)
              done :+ ArtifactRecord(
                ArtifactId.unsafe(file),
                rel,
                bytes.length.toLong,
                Checksum.ofBytes(bytes),
                "typed-record"
              )
            }
        }
      }
      refs = present.map((id, file) =>
        RecordRef(RecordId.unsafe(id), "storymodel4s.sherlock.record", 1, ArtifactId.unsafe(file))
      )
      manifest <- SourceManifest
        .of(
          corpus,
          SourceManifest.Schema,
          SourceManifest.SchemaVersion,
          artifacts,
          refs,
          AdmissionStatus(AdmissionState.Admitted, courtOpened = true, Vector.empty),
          ContentPolicy(false, false, Vector("commercial-episode-video-bytes")),
          cur.get[Vector[String]]("nonClaims").getOrElse(Vector.empty),
          Map(
            "migratedFrom" -> io.circe.Json.fromString(schema),
            "mediaPartsOutOfScope" -> io.circe.Json.fromString(
              "external-local-only video is declared by the v1 record and not verifiable here"
            )
          )
        )
        .left
        .map(f => MigrationRefusal.BadArtifact(-1, f.message))
    yield manifest
