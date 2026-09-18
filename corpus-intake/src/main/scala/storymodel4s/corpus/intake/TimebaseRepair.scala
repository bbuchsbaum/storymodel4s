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
      /** Parsed because the zero-offset argument in `clockRepairs` rests on it being 0. It was not
        * read, so the justification could be falsified in the file with nothing noticing.
        */
      annotationStartSeconds: Long,
      annotationEndSeconds: Long,
      playbackEndTicks: Long,
      playbackStartTicks: Long,
      uncoveredTailTicks: Long,
      /** The record's own statement of the mapping shape. `clockRepairs` implements the identity
        * scaling and nothing else, so a record declaring anything else must REFUSE rather than be
        * quietly given an identity repair.
        */
      mapping: String,
      /** The record's own formula. The receipt used to build this string itself, which meant an
        * emitted receipt could assert a derivation its own source contradicted.
        */
      formula: String
  ):
    /** The derivation that existed only as prose. */
    def durationTicks: Long = playbackEndTicks + uncoveredTailTicks

  final case class Record(
      annotationSha256: Checksum,
      inputRows: Int,
      runs: Vector[Run],
      /** The crosswalk's declared id. The receipt carried a Scala literal `...-v1`, so a v2 record
        * would have produced receipts naming a derivation that no longer existed.
        */
      crosswalkId: String
  ):
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

  /** The one derivation `clockRepairs` performs, written the way the record writes it.
    *
    * Symbolic, not interpolated: the record says `* ticksPerSecond`, and a receipt that said
    * `* 2500` would be quoting something its source does not contain. A record declaring any other
    * formula is refused rather than given this repair with its own words attached.
    */
  val ImplementedFormula: String = "playbackTicks = rawAnnotationSeconds * ticksPerSecond"

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
      crosswalkId <- cur
        .downField("annotationToPlaybackCrosswalk")
        .get[String]("id")
        .left
        .map(_ => RepairRefusal.MissingField("annotationToPlaybackCrosswalk/id"))
      runsJson <- cur
        .downField("annotationToPlaybackCrosswalk")
        .get[Vector[io.circe.Json]]("runs")
        .left
        .map(_ => RepairRefusal.MissingField("annotationToPlaybackCrosswalk/runs"))
      runs <- runsJson.foldLeft[Either[RepairRefusal, Vector[Run]]](Right(Vector.empty)) {
        (acc, j) => acc.flatMap(done => run(j).map(done :+ _))
      }
    yield Record(sha, inputRows, runs, crosswalkId)

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
      startSeconds <- num("annotationStartSeconds")
      endSeconds <- num("annotationEndSeconds")
      endTicks <- num("playbackEndTicks")
      startTicks <- num("playbackStartTicks")
      tail <- num("uncoveredTailTicks")
      mapping <- str("mapping")
      formula <- str("formula")
    yield Run(
      runId,
      partId,
      axisId,
      bounds._1,
      bounds._2,
      startSeconds,
      endSeconds,
      endTicks,
      startTicks,
      tail,
      mapping,
      formula
    )

  /** Builds a REAL `ClockRepair` per run, giving `core`'s mapping vocabulary its first production
    * caller. The repair is the identity on seconds scaled to ticks, which is exactly what the
    * record's own `formula` says: `playbackTicks = rawAnnotationSeconds * ticksPerSecond`.
    *
    * **Offset is zero for BOTH runs, and that is correct even though run-2 times are run-local.**
    * The record declares `annotationStartSeconds: 0` and `playbackStartTicks: 0` for each run,
    * because each run maps to its OWN part's playback axis, which also starts at zero. An offset
    * would be needed only to map both runs onto one continuous timeline -- which is exactly the
    * repaired notebook clock the record refuses, and refuses for media in `whyNotRepaired`.
    *
    * **The source axis is per-run, and that is not decoration.** The record declares one coordinate
    * system, `annotation-run-local-seconds`, containing two runs -- but run-1 second 100 and run-2
    * second 100 are DIFFERENT MOMENTS. They are two coordinate spaces sharing a name. Giving them
    * one axis id would let a single source axis map to two different target axes, and
    * `projectRunLocalSeconds` could not tell which repair applies to a given second. The suffix
    * makes the spaces distinct, which is what lets the axis check in `ClockRepair` mean anything.
    */
  def clockRepairs(
      record: Record,
      ticksPerSecond: Long
  ): Either[DomainError, Vector[ClockRepair]] =
    record.runs.foldLeft[Either[DomainError, Vector[ClockRepair]]](Right(Vector.empty)) {
      (acc, r) =>
        for
          done <- acc
          // The record declares the mapping SHAPE and this function implements exactly one of
          // them. A record declaring `affine` used to be handed an identity repair anyway -- the
          // declaration was parsed into nothing and the code did as it pleased.
          _ <- Either.cond(
            r.mapping == "identity",
            (),
            DomainError.InvariantViolation(
              s"sherlock/repair/${r.runId}/mapping",
              s"the record declares mapping '${r.mapping}', and only 'identity' is implemented"
            )
          )
          // The receipt QUOTES the record's formula, which is only safe if the record describes
          // the operation this function performs. Quoting alone is not enough: a test comparing
          // the receipt to the record passes tautologically once the receipt is a copy of it, so
          // the formula is checked against the IMPLEMENTATION here instead.
          //
          // Note the old code built `... * $ticksPerSecond` (`* 2500`) while the record says
          // `* ticksPerSecond` symbolically, so the emitted receipt never did match its source.
          _ <- Either.cond(
            r.formula == ImplementedFormula,
            (),
            DomainError.InvariantViolation(
              s"sherlock/repair/${r.runId}/formula",
              s"the record declares '${r.formula}', and this builds '$ImplementedFormula'"
            )
          )
          scale <- ExactRational.of(ticksPerSecond, 1L)
          // The offset is DERIVED from the record rather than asserted to be zero. It comes out
          // zero for this record because both declared starts are zero -- which is what the prose
          // above argues -- but a record that said otherwise used to be silently overridden by
          // `ExactRational.Zero`, and `playbackStartTicks` was parsed and then never read.
          offset <- ExactRational.of(
            r.playbackStartTicks - r.annotationStartSeconds * ticksPerSecond,
            1L
          )
          source <- Right(PresentationAxisId.unsafe(s"annotation-run-local-seconds:${r.runId}"))
          target <- Right(PresentationAxisId.unsafe(r.axisId))
          // The receipt now QUOTES the record: its own crosswalk id and its own formula. It used
          // to carry a Scala literal id and a formula this function built itself, so a receipt
          // could assert a derivation its own source contradicted, and a v2 record would have
          // produced receipts naming v1.
          receipt <- SourceDerivationReceipt.of(
            record.crosswalkId,
            r.formula,
            Vector(record.annotationSha256)
          )
          repair <- ClockRepair.of(source, target, scale, offset, receipt)
        yield done :+ repair
    }
