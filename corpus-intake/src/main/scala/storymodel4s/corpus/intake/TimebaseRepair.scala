package storymodel4s.corpus.intake

import io.circe.parser.parse as parseJson
import storymodel4s.core.*

/** Reads `storymodel4s.sherlock.timebase-repair` as a typed sidecar record.
  *
  * This record is the reason the manifest is thin. It carries `certifies`/`doesNotCertify`,
  * `nonEquivalences`, `scientificRestrictions` and a `whyNotRepaired` note explaining that the
  * upstream notebook's repaired clock is correct for an fMRI analysis axis and wrong for media --
  * none of which belongs in a flat artifact manifest.
  *
  * Until now nothing read it. Its constants were transcribed into `SherlockAnnotations`
  * (`sherlock.scala:45-67`) and transcribed a third time into a test named for the JSON that never
  * opened it, asserting Scala literals against Scala literals. Editing the JSON changed nothing and
  * broke nothing.
  *
  * The binding derivation was prose only: part duration is the annotation's playback end PLUS the
  * uncovered tail (3,565,000 + 500 = 3,565,500 for part A; 3,860,000 + 27,000 = 3,887,000 for part
  * B). It is code here.
  */
object TimebaseRepair:
  val Schema: String = "storymodel4s.sherlock.timebase-repair"
  val SchemaVersion: Int = 2

  final case class Run(
      runId: String,
      partId: String,
      axisId: String,
      firstRow: Int,
      lastRow: Int,
      annotationEndSeconds: Long,
      playbackEndTicks: Long,
      uncoveredTailTicks: Long
  ):
    /** The derivation that existed only as prose. */
    def durationTicks: Long = playbackEndTicks + uncoveredTailTicks

  final case class Record(annotationSha256: Checksum, inputRows: Int, runs: Vector[Run]):
    def run(id: String): Option[Run] = runs.find(_.runId == id)

    /** The last annotation row of run 1, which `SherlockAnnotations` calls `run1EndRow`. */
    def run1EndRow: Option[Int] = run("run-1").map(_.lastRow)

  enum RepairRefusal:
    case NotJson(reason: String)
    case WrongSchema(found: String)
    case WrongSchemaVersion(found: Int)
    case MissingField(path: String)
    case BadRowRange(raw: String)

    def message: String = this match
      case NotJson(r)            => s"not JSON: $r"
      case WrongSchema(f)        => s"schema is '$f', expected '$Schema'"
      case WrongSchemaVersion(f) => s"schema version is $f, expected $SchemaVersion"
      case MissingField(p)       => s"missing or malformed field: $p"
      case BadRowRange(raw)      => s"annotationRows '$raw' is not a 'first-last' range"

  private val RowRange = raw"^(\d+)-(\d+)$$".r

  def parse(json: String): Either[RepairRefusal, Record] =
    for
      doc <- parseJson(json).left.map(e => RepairRefusal.NotJson(e.getMessage))
      cur = doc.hcursor
      schema <- cur.get[String]("schema").left.map(_ => RepairRefusal.MissingField("schema"))
      _ <- Either.cond(schema == Schema, (), RepairRefusal.WrongSchema(schema))
      version <- cur
        .get[Int]("schemaVersion")
        .left
        .map(_ => RepairRefusal.MissingField("schemaVersion"))
      _ <- Either.cond(version == SchemaVersion, (), RepairRefusal.WrongSchemaVersion(version))
      shaRaw <- cur
        .downField("inputAnnotation")
        .get[String]("sha256")
        .left
        .map(_ => RepairRefusal.MissingField("inputAnnotation/sha256"))
      sha <- Checksum
        .from(shaRaw)
        .left
        .map(_ => RepairRefusal.MissingField("inputAnnotation/sha256"))
      inputRows <- cur
        .downField("repair")
        .get[Int]("inputRows")
        .left
        .map(_ => RepairRefusal.MissingField("repair/inputRows"))
      runsJson <- cur
        .downField("annotationToPlaybackCrosswalk")
        .get[Vector[io.circe.Json]]("runs")
        .left
        .map(_ => RepairRefusal.MissingField("annotationToPlaybackCrosswalk/runs"))
      runs <- runsJson.foldLeft[Either[RepairRefusal, Vector[Run]]](Right(Vector.empty)) {
        (acc, j) => acc.flatMap(done => run(j).map(done :+ _))
      }
    yield Record(sha, inputRows, runs)

  private def run(j: io.circe.Json): Either[RepairRefusal, Run] =
    val c = j.hcursor
    def str(f: String) = c.get[String](f).left.map(_ => RepairRefusal.MissingField(s"runs/$f"))
    def num(f: String) = c.get[Long](f).left.map(_ => RepairRefusal.MissingField(s"runs/$f"))
    for
      runId <- str("runId")
      partId <- str("partId")
      axisId <- str("axisId")
      rows <- str("annotationRows")
      bounds <- rows match
        case RowRange(a, b) => Right((a.toInt, b.toInt))
        case other          => Left(RepairRefusal.BadRowRange(other))
      endSeconds <- num("annotationEndSeconds")
      endTicks <- num("playbackEndTicks")
      tail <- num("uncoveredTailTicks")
    yield Run(runId, partId, axisId, bounds._1, bounds._2, endSeconds, endTicks, tail)

  /** Builds a REAL `ClockRepair` per run, giving `core`'s mapping vocabulary its first production
    * caller. The repair is the identity on seconds scaled to ticks, which is exactly what the
    * record's own `formula` says: `playbackTicks = rawAnnotationSeconds * ticksPerSecond`.
    */
  def clockRepairs(
      record: Record,
      ticksPerSecond: Long
  ): Either[DomainError, Vector[ClockRepair]] =
    record.runs.foldLeft[Either[DomainError, Vector[ClockRepair]]](Right(Vector.empty)) {
      (acc, r) =>
        for
          done <- acc
          scale <- ExactRational.of(ticksPerSecond, 1L)
          source <- Right(PresentationAxisId.unsafe(s"annotation-run-local-seconds:${r.runId}"))
          target <- Right(PresentationAxisId.unsafe(r.axisId))
          receipt <- SourceDerivationReceipt.of(
            "annotation-raw-to-part-playback-v1",
            s"playbackTicks = rawAnnotationSeconds * $ticksPerSecond",
            Vector(record.annotationSha256)
          )
          repair <- ClockRepair.of(source, target, scale, ExactRational.Zero, receipt)
        yield done :+ repair
    }
