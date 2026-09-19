package storymodel4s.corpus.intake

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import io.circe.{Decoder, Json}
import io.circe.jawn.JawnParser
import storymodel4s.core.*
import storymodel4s.corpus.*

/** Checked reader for the committed Sherlock declaration. Its byte digest binds all prose and
  * machine-readable restrictions; notebook provenance is declared, not independently verified.
  * Media bytes are admission pins and are not opened by this coordinate adapter.
  */
object TimebaseRepair:
  val Schema: String = "storymodel4s.sherlock.timebase-repair"
  val SchemaVersion: Int = 2
  val ImplementedFormula: String = "playbackTicks = rawAnnotationSeconds * ticksPerSecond"
  private val RawCoordinate = "annotation-run-local-seconds (raw)"
  private val jsonParser = JawnParser(allowDuplicateKeys = false)

  final case class Run(
      runId: String,
      partId: String,
      axisId: String,
      firstRow: Int,
      lastRow: Int,
      annotationStartSeconds: Long,
      annotationEndSeconds: Long,
      playbackEndTicks: Long,
      playbackStartTicks: Long,
      uncoveredTailTicks: Long,
      mapping: String,
      formula: String
  ):
    // Parsing proves this sum representable before constructing a Run.
    def durationTicks: Long = playbackEndTicks + uncoveredTailTicks
    def sourceAxis: PresentationAxisId =
      PresentationAxisId.unsafe(s"annotation-run-local-seconds:$runId")

  final class Record private[TimebaseRepair] (
      val annotationSha256: Checksum,
      val inputRows: Int,
      val runs: Vector[Run],
      val crosswalkId: String,
      val manifest: SherlockAnnotations.MediaManifest,
      val checksum: Checksum,
      val certifies: String,
      val doesNotCertify: String,
      val whyNotRepaired: String,
      val explicitlyNotUsed: String,
      val scientificRestrictions: Map[String, Vector[String]],
      val nonEquivalences: Vector[String],
      private[intake] val document: Json
  ):
    def run(id: String): Option[Run] = runs.find(_.runId == id)
    def run1EndRow: Option[Int] = run("run-1").map(_.lastRow)
    val notebookProvenanceStatus: String = "declared-unverified"

  enum RepairRefusal:
    case NotJson(reason: String)
    case InvalidRecord(path: String, reason: String)
    case ReadFailed(path: String)
    def message: String = this match
      case NotJson(r)          => s"not JSON: $r"
      case InvalidRecord(p, r) => s"$p: $r"
      case ReadFailed(p)       => s"repair record unavailable: $p"

  private final case class Invalid(path: String, reason: String) extends RuntimeException
  private[intake] def failure(path: String, reason: String): DomainError =
    DomainError.InvariantViolation(s"sherlock/repair/$path", reason)

  private class Fields(root: Json):
    def json(path: String): Json =
      path.split('/').foldLeft(root) { (j, key) =>
        key.toIntOption match
          case Some(i) =>
            j.asArray.flatMap(_.lift(i)).getOrElse(throw Invalid(path, "missing array member"))
          case None => j.asObject.flatMap(_(key)).getOrElse(throw Invalid(path, "missing field"))
      }
    def get[A: Decoder](path: String): A =
      json(path).as[A].fold(_ => throw Invalid(path, "malformed value"), identity)
    def str(path: String): String =
      val s = get[String](path)
      check(s.trim.nonEmpty, path, "empty value")
      s
    def long(path: String): Long = get[Long](path)
    def int(path: String): Int = get[Int](path)
    def decimal(path: String): BigDecimal = get[BigDecimal](path)
    def array(path: String): Vector[Json] = get[Vector[Json]](path)
    def check(ok: Boolean, path: String, why: String): Unit =
      if !ok then throw Invalid(path, why)
    def equal[A: Decoder](path: String, value: A): Unit =
      check(
        get[A](path) == value,
        path,
        s"unsupported or inconsistent declaration; expected $value"
      )
    def hash(path: String): Checksum =
      Checksum.from(str(path)).fold(_ => throw Invalid(path, "invalid SHA256"), identity)
    def range(path: String): (Int, Int) =
      val raw = str(path)
      val pair = raw.split("-", -1).toVector.map(_.toIntOption)
      check(
        raw.matches("[0-9]+-[0-9]+") && pair.size == 2 && pair.forall(_.nonEmpty) &&
          pair.head.get > 0 && pair.last.get >= pair.head.get,
        path,
        "expected positive first-last range"
      )
      (pair.head.get, pair.last.get)

  /** Resolve the repository-relative record, never a literal fallback. Git review establishes the
    * root declaration's authority; this loader records its observed identity, not committedness.
    */
  def loadCommitted(): Either[DomainError, Record] =
    val local = Path.of("docs/data/sherlock/timebase-repair.json")
    val path =
      if Files.isRegularFile(local) then local
      else Path.of("../docs/data/sherlock/timebase-repair.json")
    load(path)

  def load(path: Path): Either[DomainError, Record] =
    try parse(Files.readAllBytes(path)).left.map(r => failure("record", r.message))
    catch
      case NonFatal(_) => Left(failure("record", RepairRefusal.ReadFailed(path.toString).message))

  def parse(json: String): Either[RepairRefusal, Record] = parse(
    json.getBytes(StandardCharsets.UTF_8)
  )

  def parse(bytes: Array[Byte]): Either[RepairRefusal, Record] =
    val owned = bytes.clone()
    val artifactId = ArtifactId.unsafe("timebase-repair.json")
    val recordId = RecordId.unsafe("sherlock-timebase-repair")
    val store = new ArtifactStore:
      def list: Vector[ArtifactId] = Vector(artifactId)
      def bytes(id: ArtifactId): Either[IntakeRefusal, Array[Byte]] =
        if id == artifactId then Right(owned) else Left(IntakeRefusal.MissingArtifact(id))
    // The root is caller-supplied. This observed one-record manifest establishes owned-byte
    // consistency and unique typed resolution, not independent authenticity or media admission.
    for
      path <- RelativeArtifactPath
        .from("timebase-repair.json")
        .left
        .map(e => RepairRefusal.InvalidRecord("record", e.message))
      manifest <- SourceManifest
        .of(
          CorpusId.unsafe("sherlock-clock-declaration"),
          SourceManifest.Schema,
          SourceManifest.SchemaVersion,
          Vector(
            ArtifactRecord(
              artifactId,
              path,
              owned.length.toLong,
              Checksum.ofBytes(owned),
              "clock-declaration"
            )
          ),
          Vector(RecordRef(recordId, Schema, SchemaVersion, artifactId)),
          AdmissionStatus(AdmissionState.Proposed, false, Vector.empty),
          ContentPolicy(false, false, Vector.empty),
          Vector(
            "Observed local root identity; no independent authenticity or media verification."
          ),
          Map.empty
        )
        .left
        .map(e => RepairRefusal.InvalidRecord("record", e.message))
      snapshot <- Verify
        .verify(manifest, store)
        .left
        .map(es => RepairRefusal.InvalidRecord("record", es.toVector.map(_.message).mkString("; ")))
      artifact <- snapshot
        .record(recordId, Schema, SchemaVersion)
        .left
        .map(e => RepairRefusal.InvalidRecord("record", e.message))
      record <- parseArtifact(artifact)
    yield record

  private def parseArtifact(artifact: VerifiedArtifact): Either[RepairRefusal, Record] =
    jsonParser
      .parseByteArray(artifact.toArray)
      .left
      .map(e => RepairRefusal.NotJson(e.message))
      .flatMap { root =>
        try Right(read(root, artifact.checksum))
        catch case Invalid(p, r) => Left(RepairRefusal.InvalidRecord(p, r))
      }

  private def read(root: Json, checksum: Checksum): Record =
    val d = new Fields(root)
    d.equal("schema", Schema)
    d.equal("schemaVersion", SchemaVersion)
    val _ = d.str("artifactId")
    val annotation = d.hash("inputAnnotation/sha256")
    val _ = d.str("inputAnnotation/artifactId")
    val inputRows = d.int("repair/inputRows")
    d.check(inputRows > 0, "repair/inputRows", "must be positive")
    val edition = "presentationEditionIdentity"
    val editionId = d.str(s"$edition/id")
    d.equal(s"$edition/authority", "owner-declaration-plus-byte-identity")
    d.equal(s"$edition/declaredBy", "owner")
    d.equal(s"$edition/bytesDisposition", "external-to-git-and-not-redistributed")
    d.equal(s"$edition/filenamesUsedAsIdentity", false)
    d.equal(s"$edition/pathsRecorded", false)
    val _ = d.str(s"$edition/declaration")
    d.check(d.array(s"$edition/parts").size == 2, s"$edition/parts", "exactly two parts required")
    val parts = (0 to 1).toVector.map { i =>
      val p = s"$edition/parts/$i"
      val rate = d.long(s"$p/video/ticksPerSecond")
      val duration = d.long(s"$p/video/durationTicks")
      d.check(rate > 0 && duration > 0, p, "positive rate and duration required")
      d.equal(s"$p/presentationOrdinal", i + 1)
      d.check(d.long(s"$p/byteLength") > 0, p, "positive byte length required")
      d.equal(s"$p/primaryStream", "video")
      d.equal(s"$p/video/startPts", 0L)
      d.equal(s"$p/video/timeBase", s"1/$rate")
      d.equal(s"$p/video/constantFrameRate", true)
      val perFrame = d.long(s"$p/video/ticksPerFrame")
      val frames = d.long(s"$p/video/frameCount")
      d.check(
        perFrame > 0 && frames > 0 && BigInt(frames) * perFrame == duration,
        p,
        "frame extent disagrees"
      )
      val rawRate = d.str(s"$p/video/frameRate")
      val frameRate = rawRate.split("/", -1).toVector.map(_.toLongOption)
      d.check(
        rawRate.matches("[0-9]+/[0-9]+") && frameRate.size == 2 && frameRate.forall(
          _.exists(_ > 0)
        ) && BigInt(
          frameRate.head.get
        ) * perFrame == BigInt(rate) * frameRate.last.get,
        p,
        "frame rate disagrees"
      )
      d.check(
        d.decimal(s"$p/video/durationSeconds") * BigDecimal(rate) == BigDecimal(duration),
        p,
        "seconds/ticks disagree"
      )
      SherlockAnnotations.PartIdentity(
        d.str(s"$p/partId"),
        i + 1,
        d.hash(s"$p/sha256"),
        duration,
        rate
      )
    }
    d.check(
      parts.map(_.partId).distinct.size == 2 && parts.map(_.sha256).distinct.size == 2,
      edition,
      "duplicate part identity"
    )
    val systems = d.array("coordinateSystems")
    val ids = systems.indices.map(i => d.str(s"coordinateSystems/$i/id")).toVector
    d.check(ids.distinct.size == ids.size, "coordinateSystems", "duplicate coordinate ID")
    def system(id: String): String =
      val i = ids.indexOf(id)
      d.check(i >= 0, "coordinateSystems", s"missing coordinate $id")
      s"coordinateSystems/$i"
    val local = system("media-part-local-seconds")
    d.equal(s"$local/kind", "container-presentation-time")
    d.equal(s"$local/experimentalEditionReceipt", editionId)
    d.equal(s"$local/experimentalEditionBinding", "owner-declared-and-byte-identified")
    d.check(d.array(s"$local/parts").size == 2, local, "exactly two local extents required")
    val cw = "annotationToPlaybackCrosswalk"
    val crosswalkId = d.str(s"$cw/id")
    d.equal(s"$cw/status", "established")
    d.equal(s"$cw/editionReceipt", editionId)
    d.equal(s"$cw/certifies", "coordinate-mapping-only")
    d.equal(s"$cw/doesNotCertify", "media-reachability")
    d.equal(s"$cw/sourceCoordinate", RawCoordinate)
    d.equal(s"$cw/explicitlyNotUsed", "topic-notebook-repaired-seconds")
    val why = d.str(s"$cw/whyNotRepaired")
    d.equal(s"$cw/tailHandling", "observed-not-trimmed-not-imputed")
    d.check(d.array(s"$cw/runs").size == 2, cw, "exactly two runs required")
    val runs = parts.zipWithIndex.map { (part, i) =>
      val p = s"$cw/runs/$i"
      d.equal(s"$p/runId", s"run-${i + 1}")
      d.equal(s"$p/partId", part.partId)
      d.equal(s"$p/sourceCoordinate", RawCoordinate)
      d.equal(s"$p/mapping", "identity")
      d.equal(s"$p/formula", ImplementedFormula)
      val (first, last) = d.range(s"$p/annotationRows")
      val start = d.long(s"$p/annotationStartSeconds")
      val end = d.long(s"$p/annotationEndSeconds")
      val startTicks = d.long(s"$p/playbackStartTicks")
      val endTicks = d.long(s"$p/playbackEndTicks")
      val tail = d.long(s"$p/uncoveredTailTicks")
      d.check(start >= 0 && end >= start && tail >= 0, p, "invalid extent")
      d.check(
        BigInt(start) * part.ticksPerSecond == startTicks && BigInt(
          end
        ) * part.ticksPerSecond == endTicks,
        p,
        "starts/ends contradict identity formula"
      )
      d.check(
        BigInt(endTicks) + tail == part.durationTicks,
        p,
        "annotation end plus tail differs from media extent"
      )
      val axis = system(d.str(s"$p/axisId"))
      d.equal(s"$axis/kind", "edition-playback-time")
      d.equal(s"$axis/status", "established")
      d.equal(s"$axis/partId", part.partId)
      d.equal(s"$axis/editionReceipt", editionId)
      for field <- Vector(
          "timeBase",
          "ticksPerSecond",
          "ticksPerFrame",
          "startPts",
          "durationTicks",
          "durationSeconds",
          "frameCount"
        )
      do
        d.check(
          d.json(s"$axis/$field") == d.json(s"$edition/parts/$i/video/$field"),
          axis,
          s"$field disagrees with primary video"
        )
      d.equal(s"$local/parts/$i/partId", part.partId)
      d.check(
        d.decimal(s"$local/parts/$i/startSeconds") == 0 && d.decimal(
          s"$local/parts/$i/endSeconds"
        ) * BigDecimal(part.ticksPerSecond) == BigDecimal(part.durationTicks),
        local,
        "local seconds extent disagrees"
      )
      val frame = d.long(s"$axis/ticksPerFrame")
      d.check(
        BigInt(d.long(s"$p/playbackEndFrame")) * frame == endTicks && BigInt(
          d.long(s"$p/uncoveredTailFrames")
        ) * frame == tail,
        p,
        "crosswalk frame extent disagrees"
      )
      d.check(
        d.decimal(s"$p/uncoveredTailSeconds") * BigDecimal(part.ticksPerSecond) == BigDecimal(tail),
        p,
        "tail seconds disagree"
      )
      Run(
        s"run-${i + 1}",
        part.partId,
        d.str(s"$p/axisId"),
        first,
        last,
        start,
        end,
        endTicks,
        startTicks,
        tail,
        "identity",
        ImplementedFormula
      )
    }
    d.check(
      runs.head.firstRow == 1 && runs(1).firstRow.toLong == runs.head.lastRow.toLong + 1 && runs(
        1
      ).lastRow == inputRows,
      cw,
      "row partition has a gap, overlap or wrong extent"
    )
    d.check(runs.map(_.axisId).distinct.size == 2, cw, "run target axes must differ")
    d.check(
      ids.toSet == Set(
        "media-part-local-seconds",
        "annotation-run-local-seconds",
        "scanner-tr",
        "topic-notebook-repaired-seconds",
        "recall-princeton-seconds-and-tr",
        "recall-openneuro-seconds-and-tr"
      ) ++ runs.map(_.axisId),
      "coordinateSystems",
      "unsupported coordinate system"
    )
    d.equal(s"$cw/exactness/annotationTimeValues", Math.multiplyExact(inputRows.toLong, 2L))
    for key <- Vector("nonIntegerValues", "valuesOffFrameBoundary", "valuesOffPtsTick") do
      d.equal(s"$cw/exactness/$key", 0)
    val raw = system("annotation-run-local-seconds")
    d.equal(s"$raw/kind", "coder-table-interval")
    d.check(d.array(s"$raw/runs").size == 2, raw, "exactly two raw runs required")
    val ordinary = d.range(s"$raw/runs/0/ordinaryRows")
    val dropped = d.get[Vector[Int]]("repair/dropSourceRows")
    d.check(
      dropped.nonEmpty && dropped.distinct.size == dropped.size && dropped.sorted == dropped,
      "repair/dropSourceRows",
      "unique ordered break rows required"
    )
    d.check(
      ordinary._1 == 1 && ordinary._2 < runs.head.lastRow && ordinary._2.toLong + dropped.size == runs.head.lastRow.toLong && dropped.zipWithIndex
        .forall((row, i) => row.toLong == ordinary._2.toLong + i + 1),
      raw,
      "ordinary and scan-break ranges disagree"
    )
    d.equal(s"$raw/runs/0/runId", "run-1")
    d.equal(s"$raw/runs/0/scanBreakRows", dropped)
    d.equal(s"$raw/runs/0/ordinaryStartSeconds", runs.head.annotationStartSeconds)
    d.equal(s"$raw/runs/0/rawEndIncludingScanBreakSeconds", runs.head.annotationEndSeconds)
    d.equal(s"$raw/runs/1/runId", "run-2")
    d.check(
      d.range(s"$raw/runs/1/rows") == (runs(1).firstRow, runs(1).lastRow),
      raw,
      "run-2 row range differs"
    )
    d.equal(s"$raw/runs/1/startSeconds", runs(1).annotationStartSeconds)
    d.equal(s"$raw/runs/1/endSeconds", runs(1).annotationEndSeconds)
    val offset = d.long("repair/offsetSeconds/run-2")
    d.check(
      offset >= 0 && offset == d.long(
        s"$raw/runs/0/ordinaryEndSeconds"
      ) && offset <= runs.head.annotationEndSeconds,
      "repair/offsetSeconds",
      "offset must be the ordinary run-1 end"
    )
    d.equal("repair/offsetSeconds/run-1", 0L)
    d.equal("repair/purity", "pure-coordinate-transform")
    d.equal("repair/formula/repairedStartSeconds", "rawStartSeconds + offsetSeconds(runId)")
    d.equal("repair/formula/repairedEndSeconds", "rawEndSeconds + offsetSeconds(runId)")
    d.check(
      d.array("repair/runAssignment").size == 2,
      "repair/runAssignment",
      "two assignments required"
    )
    for i <- 0 to 1 do
      d.equal(s"repair/runAssignment/$i/runId", runs(i).runId)
      d.check(
        d.range(
          s"repair/runAssignment/$i/sourceRows"
        ) == (if i == 0 then ordinary else (runs(1).firstRow, runs(1).lastRow)),
        "repair/runAssignment",
        "assignment differs from raw coordinate"
      )
    val retained = inputRows - dropped.size
    d.equal("repair/retainedRows", retained)
    d.equal("repair/serializationReceipt/recordCount", retained)
    val _ = d.hash("repair/serializationReceipt/sha256")
    d.equal("repair/serializationReceipt/recordOrder", "ascending source row")
    d.equal(
      "repair/serializationReceipt/fields",
      Vector(
        "source-row-zero-padded-to-four-digits",
        "run-id",
        "raw-start-seconds-base10-integer",
        "raw-end-seconds-base10-integer",
        "repaired-start-seconds-base10-integer",
        "repaired-end-seconds-base10-integer",
        "start-tr-base10-integer-or-empty",
        "end-tr-base10-integer-or-empty"
      )
    )
    d.equal("repair/serializationReceipt/fieldSeparator", "TAB")
    d.equal("repair/serializationReceipt/recordSeparator", "LF")
    d.equal("repair/serializationReceipt/finalRecordSeparator", true)
    val notebook = system("topic-notebook-repaired-seconds")
    d.equal(s"$notebook/kind", "derived-analysis-time")
    d.equal(s"$notebook/rowCount", retained)
    d.equal(s"$notebook/startSeconds", 0L)
    d.check(
      BigInt(d.long(s"$notebook/endSeconds")) == BigInt(offset) + runs(1).annotationEndSeconds,
      notebook,
      "notebook end disagrees"
    )
    d.equal(s"$notebook/derivationReceipt", d.str("repair/id"))
    val scanner = system("scanner-tr")
    d.equal(s"$scanner/kind", "scanner-index")
    d.equal(s"$scanner/missingBoundsImputed", false)
    d.equal(s"$scanner/rowsWithMissingBounds", dropped)
    d.check(
      d.decimal(s"$scanner/secondsPerTr") > 0 && d.long(s"$scanner/minimumTr") > 0 && d.long(
        s"$scanner/maximumTr"
      ) >= d.long(s"$scanner/minimumTr"),
      scanner,
      "invalid TR declaration"
    )
    val bounds = d.array("repair/boundaryChecks")
    d.check(
      bounds.size == 3 && bounds.indices
        .map(i => d.int(s"repair/boundaryChecks/$i/sourceRow"))
        .toVector == Vector(ordinary._2, runs(1).firstRow, inputRows),
      "repair/boundaryChecks",
      "boundary references disagree"
    )
    bounds.indices.foreach { i =>
      val p = s"repair/boundaryChecks/$i"
      val r = if i == 0 then runs.head else runs(1)
      d.equal(s"$p/runId", r.runId)
      d.check(
        d.long(s"$p/startTr") >= d.long(s"$scanner/minimumTr") &&
          d.long(s"$p/endTr") >= d.long(s"$p/startTr") &&
          d.long(s"$p/endTr") <= d.long(s"$scanner/maximumTr"),
        p,
        "TR boundary escapes scanner extent"
      )
      val off = if i == 0 then 0L else offset
      for pair <- Vector(
          ("rawStartSeconds", "repairedStartSeconds"),
          ("rawEndSeconds", "repairedEndSeconds")
        )
      do
        d.check(
          BigInt(d.long(s"$p/${pair._1}")) + off == d.long(s"$p/${pair._2}"),
          p,
          "notebook boundary formula disagrees"
        )
    }
    d.equal("repair/boundaryChecks/0/rawEndSeconds", offset)
    d.equal("repair/boundaryChecks/1/rawStartSeconds", runs(1).annotationStartSeconds)
    d.equal("repair/boundaryChecks/2/rawEndSeconds", runs(1).annotationEndSeconds)
    d.equal("missingnessAndTails/tailHandling", "observed-not-trimmed-not-imputed")
    d.check(
      d.array("missingnessAndTails/scanBreakRows").size == dropped.size,
      "missingnessAndTails",
      "missing break rows"
    )
    dropped.zipWithIndex.foreach { (row, i) =>
      val p = s"missingnessAndTails/scanBreakRows/$i"
      d.equal(s"$p/sourceRow", row)
      d.equal(s"$p/trBounds", "missing")
      val previous =
        if i == 0 then offset else d.long(s"missingnessAndTails/scanBreakRows/${i - 1}/endSeconds")
      d.equal(s"$p/startSeconds", previous)
      d.check(d.long(s"$p/endSeconds") >= previous, p, "reversed break extent")
    }
    d.equal(
      s"missingnessAndTails/scanBreakRows/${dropped.size - 1}/endSeconds",
      runs.head.annotationEndSeconds
    )
    for (part, i) <- parts.zipWithIndex do
      val tail = s"missingnessAndTails/${if i == 0 then "partA" else "partB"}"
      d.check(
        d.decimal(s"$tail/mediaEndSeconds") * BigDecimal(part.ticksPerSecond) == BigDecimal(
          part.durationTicks
        ),
        tail,
        "media end disagrees"
      )
      val endKey =
        if i == 0 then "rawAnnotationEndIncludingBreakSeconds" else "annotationEndSeconds"
      val tailKey =
        if i == 0 then "rawAnnotationToMediaTailSeconds" else "annotationToMediaTailSeconds"
      d.equal(s"$tail/$endKey", runs(i).annotationEndSeconds)
      d.check(
        d.decimal(s"$tail/$tailKey") * BigDecimal(part.ticksPerSecond) == BigDecimal(
          runs(i).uncoveredTailTicks
        ),
        tail,
        "tail disagrees"
      )
    d.equal("missingnessAndTails/partA/retainedNotebookAnnotationEndSeconds", offset)
    d.check(
      (d.decimal(
        "missingnessAndTails/partA/retainedNotebookAnnotationToMediaTailSeconds"
      ) + BigDecimal(offset)) * BigDecimal(parts.head.ticksPerSecond) == BigDecimal(
        parts.head.durationTicks
      ),
      "missingnessAndTails/partA",
      "notebook tail disagrees"
    )
    val restrictions = Map(
      "clockRepairMayEstablish" -> Vector(
        "deterministic-published-analysis-order",
        s"replayable-$retained-row-derived-axis"
      ),
      "clockRepairDoesNotEstablish" -> Vector(
        "exact-media-edition",
        "frame-exact-evidence",
        "missing-tr-imputation",
        "accepted-narrative-semantics"
      ),
      "editionIdentityEstablishes" -> Vector(
        "declared-presentation-edition",
        "per-part-playback-axis",
        "exact-annotation-to-playback-crosswalk"
      ),
      "editionIdentityDoesNotEstablish" -> Vector(
        "accepted-narrative-semantics",
        "recall-to-playback-alignment",
        "media-reachability-at-any-later-time"
      )
    )
    d.check(
      d.json("scientificRestrictions")
        .asObject
        .map(_.keys.toSet)
        .getOrElse(Set.empty[String]) == restrictions.keySet,
      "scientificRestrictions",
      "unknown restriction family"
    )
    restrictions.foreach { (key, expected) => d.equal(s"scientificRestrictions/$key", expected) }
    val nonEquivalences = d.get[Vector[String]]("nonEquivalences")
    d.check(
      nonEquivalences.nonEmpty && nonEquivalences.forall(_.trim.nonEmpty),
      "nonEquivalences",
      "empty explanatory restrictions"
    )
    val _ = d.hash("sourceDerivation/sha256")
    d.check(d.long("sourceDerivation/byteLength") > 0, "sourceDerivation", "invalid byte count")
    for key <- Vector("commit", "gitBlob") do
      d.check(
        d.str(s"sourceDerivation/$key").matches("[0-9a-f]{40}"),
        "sourceDerivation",
        s"invalid $key"
      )
    val manifest = SherlockAnnotations.MediaManifest(
      annotation,
      parts.head,
      parts(1),
      inputRows,
      runs.head.lastRow
    )
    new Record(
      annotation,
      inputRows,
      runs,
      crosswalkId,
      manifest,
      checksum,
      d.str(s"$cw/certifies"),
      d.str(s"$cw/doesNotCertify"),
      why,
      d.str(s"$cw/explicitlyNotUsed"),
      restrictions,
      nonEquivalences,
      root
    )

  /** Bind the record's external axis names to actual bundle axes, after checking their identities.
    * Production uses these repairs for every row; the receipt includes the exact record byte
    * digest.
    */
  def clockRepairs(
      record: Record,
      bundles: Map[String, SourceBundle]
  ): Either[DomainError, Vector[ClockRepair]] =
    if bundles.keySet != Set(record.manifest.partA.partId, record.manifest.partB.partId) then
      Left(failure("bundle", "bundle population differs from declared parts"))
    else
      record.runs.foldLeft[Either[DomainError, Vector[ClockRepair]]](Right(Vector.empty)) {
        (acc, run) =>
          val part =
            Vector(record.manifest.partA, record.manifest.partB).find(_.partId == run.partId).get
          for
            done <- acc
            actual <- bundles
              .get(run.partId)
              .toRight(failure("bundle", s"missing bundle for ${run.partId}"))
            expected <- SherlockAnnotations.partBundle(part)
            _ <- Either.cond(
              actual == expected,
              (),
              failure(
                "bundle",
                s"${run.partId}: byte identity, edition, extent or timebase differs"
              )
            )
            scale <- ExactRational.of(part.ticksPerSecond, 1L)
            offsetValue = BigInt(run.playbackStartTicks) - BigInt(
              run.annotationStartSeconds
            ) * part.ticksPerSecond
            _ <- Either.cond(
              offsetValue.isValidLong,
              (),
              failure("offset", "unrepresentable exact offset")
            )
            offset <- ExactRational.of(offsetValue.toLong, 1L)
            _ <- Either.cond(
              offset.isZero,
              (),
              failure("formula", "identity formula cannot carry a nonzero offset")
            )
            receipt <- SourceDerivationReceipt.of(
              record.crosswalkId,
              s"$Schema/v$SchemaVersion; run=${run.runId}; declaredAxis=${run.axisId}; targetAxis=${actual.primaryAxis.id.value}; formula=${run.formula}; certifies=${record.certifies}; doesNotCertify=${record.doesNotCertify}; explicitlyNotUsed=${record.explicitlyNotUsed}; notebookProvenance=${record.notebookProvenanceStatus}",
              Vector(record.checksum, record.annotationSha256, part.sha256)
            )
            repair <- ClockRepair.of(run.sourceAxis, actual.primaryAxis.id, scale, offset, receipt)
          yield done :+ repair
      }

  /** Reconcile the record's boundary and notebook statements against the admitted input rows. This
    * checks a local deterministic replay, not the authenticity of the upstream notebook.
    */
  private[intake] def validateRows(
      record: Record,
      rows: Vector[SherlockAnnotations.Row]
  ): Either[DomainError, Unit] =
    val d = new Fields(record.document)
    try
      val scannerIndex = d
        .array("coordinateSystems")
        .indexWhere(
          _.hcursor.get[String]("id").toOption.contains("scanner-tr")
        )
      val trBounds = rows.flatMap(r => r.startTr.toVector ++ r.endTr.toVector)
      d.check(trBounds.nonEmpty, "scanner-tr", "no observed TR bounds")
      d.equal(s"coordinateSystems/$scannerIndex/minimumTr", trBounds.min)
      d.equal(s"coordinateSystems/$scannerIndex/maximumTr", trBounds.max)
      d.check(
        rows.forall(r =>
          (r.startTr, r.endTr) match
            case (Some(start), Some(end)) => start > 0 && end >= start
            case (None, None)             => true
            case _                        => false
        ),
        "scanner-tr",
        "invalid observed TR interval"
      )
      record.runs.foreach { run =>
        val selected = rows.slice(run.firstRow - 1, run.lastRow)
        d.check(
          selected.head.rawStartSeconds.toLong == run.annotationStartSeconds && selected.last.rawEndSeconds.toLong == run.annotationEndSeconds,
          run.runId,
          "raw boundary differs from declaration"
        )
        d.check(
          selected.forall(r =>
            r.rawStartSeconds >= run.annotationStartSeconds && r.rawEndSeconds <= run.annotationEndSeconds
          ),
          run.runId,
          "row escapes declared annotation extent"
        )
        val part = if run.runId == "run-1" then record.manifest.partA else record.manifest.partB
        val axisIndex = d
          .array("coordinateSystems")
          .indexWhere(_.hcursor.get[String]("id").toOption.contains(run.axisId))
        val perFrame = d.long(s"coordinateSystems/$axisIndex/ticksPerFrame")
        d.check(
          selected.forall(r =>
            Vector(r.rawStartSeconds, r.rawEndSeconds).forall(s =>
              (BigInt(s) * part.ticksPerSecond) % perFrame == 0
            )
          ),
          run.runId,
          "row is off the declared exact frame boundary"
        )
      }
      d.array("repair/boundaryChecks").indices.foreach { i =>
        val p = s"repair/boundaryChecks/$i"
        val row = rows(d.int(s"$p/sourceRow") - 1)
        d.equal(s"$p/rawStartSeconds", row.rawStartSeconds)
        d.equal(s"$p/rawEndSeconds", row.rawEndSeconds)
        d.check(
          row.startTr.contains(d.int(s"$p/startTr")) && row.endTr.contains(d.int(s"$p/endTr")),
          p,
          "TR boundary differs from input"
        )
      }
      val drops = d.get[Vector[Int]]("repair/dropSourceRows")
      drops.zipWithIndex.foreach { (n, i) =>
        val row = rows(n - 1)
        d.equal(s"missingnessAndTails/scanBreakRows/$i/startSeconds", row.rawStartSeconds)
        d.equal(s"missingnessAndTails/scanBreakRows/$i/endSeconds", row.rawEndSeconds)
        d.check(row.startTr.isEmpty && row.endTr.isEmpty, "scanBreakRows", "TR missingness differs")
      }
      d.check(
        rows.filter(r => r.startTr.isEmpty || r.endTr.isEmpty).map(_.row) == drops,
        "scanner-tr",
        "missing TR population differs"
      )
      val replay = rows
        .filterNot(r => drops.contains(r.row))
        .map { r =>
          val run = if r.row <= record.manifest.run1EndRow then "run-1" else "run-2"
          val offset = BigInt(d.long(s"repair/offsetSeconds/$run"))
          Vector(
            f"${r.row}%04d",
            run,
            r.rawStartSeconds.toString,
            r.rawEndSeconds.toString,
            (BigInt(r.rawStartSeconds) + offset).toString,
            (BigInt(r.rawEndSeconds) + offset).toString,
            r.startTr.fold("")(_.toString),
            r.endTr.fold("")(_.toString)
          ).mkString("\t")
        }
        .mkString("", "\n", "\n")
      d.check(
        Checksum.ofText(replay) == d.hash("repair/serializationReceipt/sha256"),
        "repair/serializationReceipt",
        "replayed notebook-coordinate checksum differs"
      )
      Right(())
    catch case Invalid(p, r) => Left(failure(p, r))
