package storymodel4s.pipeline.agentmapping

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import storymodel4s.corpus.intake.{SherlockAnnotations, TimebaseRepair}
import storymodel4s.codec.TimedSourceArtifactCodec
import storymodel4s.core.Checksum

/** Loads what every agent-mapping command needs: the rendered film packet and the development
  * participants whose unit tables are present.
  *
  * Why one loader: the packet command, the replay command and later the record command must render
  * byte-identical prompts, so they share one path from files to text. The development partition is
  * an allowlist; a participant listed as both development and untouched refuses the whole run.
  */
object AgentPackets:
  final case class Inputs(
      outline: String,
      development: Vector[String],
      skipped: Vector[String],
      missing: Vector[String],
      unitsDir: Path,
      atlas: SherlockAnnotations.Atlas,
      annotationSha256: storymodel4s.core.Checksum
  )

  private def bytes(p: String): Either[String, Array[Byte]] =
    try Right(Files.readAllBytes(Path.of(p)))
    catch case NonFatal(_) => Left(s"cannot read $p")

  def load(
      annotation: String,
      transcriptA: String,
      transcriptB: String,
      captions: String,
      partition: String,
      unitsDir: String
  ): Either[String, Inputs] =
    for
      record <- TimebaseRepair.loadCommitted().left.map(_.message)
      table <- bytes(annotation)
      atlas <- SherlockAnnotations.parse(table, record).left.map(_.message)
      a <- bytes(transcriptA).flatMap(FilmOutline.transcript(_).left.map(_.toString))
      b <- bytes(transcriptB).flatMap(FilmOutline.transcript(_).left.map(_.toString))
      caps <- bytes(captions).flatMap(FilmOutline.captions(_).left.map(_.toString))
      outline <- FilmOutline
        .render(
          atlas,
          Map(atlas.manifest.partA.partId -> a, atlas.manifest.partB.partId -> b),
          caps
        )
        .left
        .map(_.toString)
      part <- bytes(partition).flatMap(b =>
        io.circe.jawn.parse(new String(b, StandardCharsets.UTF_8)).left.map(_ => "partition")
      )
      development <- part.hcursor
        .get[Vector[String]]("development")
        .left
        .map(_ => "partition has no development list")
      untouched <- part.hcursor
        .get[Vector[String]]("untouchedTest")
        .left
        .map(_ => "partition has no untouchedTest list")
      _ <- Either.cond(
        development.forall(d => !untouched.contains(d)),
        (),
        "a participant is listed as both development and untouched"
      )
      dir = Path.of(unitsDir)
      tables <-
        try
          val listing = Files.list(dir)
          try
            Right(
              listing.iterator.asScala
                .map(_.getFileName.toString)
                .collect { case s"recall-map-$name.tsv" => name }
                .toVector
                .sorted
            )
          finally listing.close()
        catch case NonFatal(_) => Left(s"cannot list $unitsDir")
    yield
      val (dev, skipped) = tables.partition(development.contains)
      Inputs(
        outline,
        dev,
        skipped,
        development.sorted.filterNot(tables.contains),
        dir,
        atlas,
        storymodel4s.core.Checksum.ofBytes(table)
      )

  /** One participant's recall units, in table order. */
  def units(in: Inputs, name: String): Either[String, Vector[AgentPrompts.RecallUnit]] =
    try
      AgentPrompts
        .units(Files.readString(in.unitsDir.resolve(s"recall-map-$name.tsv")))
        .left
        .map(r => s"$name: $r")
    catch case NonFatal(_) => Left(s"$name: cannot read unit table")

  /** Unit index to manifest unit id, from the arm's stage record beside the unit table. */
  def unitIds(in: Inputs, name: String): Either[String, Map[Int, String]] =
    val path = in.unitsDir.resolve(s"recall-map-$name.tsv.stages.json")
    for
      raw <-
        try Right(Files.readString(path))
        catch case NonFatal(_) => Left(s"$name: cannot read stage record")
      doc <- io.circe.jawn.parse(raw).left.map(_ => s"$name: stage record is not JSON")
      units <- doc.hcursor.get[Vector[io.circe.Json]]("units").left.map(_ => s"$name: no units")
      pairs <- units.foldLeft[Either[String, Map[Int, String]]](Right(Map.empty)) { (acc, u) =>
        acc.flatMap { m =>
          (u.hcursor.get[Int]("unit").toOption, u.hcursor.get[String]("unitId").toOption) match
            case (Some(i), Some(id)) if !m.contains(i) => Right(m.updated(i, id))
            case _                                     => Left(s"$name: bad stage unit")
        }
      }
    yield pairs

  /** Export admitted development inputs for the common recorded pipeline. Frozen packet identities
    * must match every prompt and participant. Original unit numbers and checked media ticks are
    * kept; the generic CSV gets its own artifact identity, not the original annotation's hash.
    */
  def studyExport(
      in: Inputs,
      frozenPackets: String,
      model: String
  ): Either[String, Map[String, String]] =
    val system = AgentPrompts.system(AgentPrompts.Version.V2)
    def str(s: String) = Json.fromString(s)
    def csv(row: Vector[String]) = row.map(s => "\"" + s.replace("\"", "\"\"") + "\"").mkString(",")
    for
      frozen <- io.circe.jawn.parse(frozenPackets).left.map(_ => "packet identities are not JSON")
      chunk <- frozen.hcursor
        .get[Int]("chunk")
        .left
        .map(_ => "packet identities have no chunk size")
      _ <- Either.cond(chunk > 0 && model.nonEmpty, (), "chunk and model must be usable")
      _ <- Either.cond(
        in.development.nonEmpty && in.missing.isEmpty && in.development.distinct == in.development,
        (),
        "development population is empty, repeated or missing a unit table"
      )
      sessions <- in.development
        .foldLeft[Either[String, Vector[(String, Json)]]](Right(Vector.empty)) { (acc, name) =>
          for
            done <- acc
            us <- units(in, name)
            _ <- Either.cond(us.nonEmpty, (), s"$name: no units")
          yield
            val chunks = us
              .grouped(chunk)
              .map { group =>
                Json.obj(
                  "lo" -> Json.fromInt(group.head.index),
                  "hi" -> Json.fromInt(group.last.index),
                  "prompt" -> str(
                    AgentPrompts.prompt(in.outline, us, group.head.index, group.last.index)
                  )
                )
              }
              .toVector
            done :+ (name -> Json.obj(
              "participant" -> str(name),
              "session" -> str("recall"),
              "units" -> Json.fromValues(
                us.map(u =>
                  Json.obj(
                    "u" -> Json.fromInt(u.index),
                    "row" -> Json.fromInt(u.index),
                    "text" -> str(u.text)
                  )
                )
              ),
              "chunks" -> Json.fromValues(chunks)
            ))
        }
      entries = sessions.map { (name, doc) =>
        val c = doc.hcursor
        val hashes = c.get[Vector[Json]]("chunks").toOption.get.map { chunk =>
          str(Checksum.ofText(chunk.hcursor.get[String]("prompt").toOption.get).hex)
        }
        Json.obj(
          "participant" -> str(name),
          "units" -> Json.fromInt(c.get[Vector[Json]]("units").toOption.get.size),
          "promptSha256" -> Json.fromValues(hashes)
        )
      }
      expected = Json.obj(
        "schema" -> str("storymodel4s.agentmapping.packet-identities/v1"),
        "outlineSha256" -> str(Checksum.ofText(in.outline).hex),
        "systemSha256" -> str(Checksum.ofText(system).hex),
        "chunk" -> Json.fromInt(chunk),
        "participants" -> Json.fromValues(entries),
        "skippedNotDevelopment" -> Json.fromValues(in.skipped.map(str)),
        "developmentWithoutUnitTable" -> Json.arr()
      )
      _ <- Either.cond(frozen == expected, (), "inputs differ from the frozen packet identities")
      sourceRows = in.atlas.rows.map { row =>
        val at = in.atlas.mediaByRow.get(row.row)
        val scene = in.atlas.sceneOf(row.row)
        Vector(
          row.row.toString,
          row.description,
          at.fold("")(_.part),
          at.fold("")(_.startTick.toString),
          at.filter(m => m.endTick != m.startTick).fold("")(_.endTick.toString),
          scene.fold("")(_.ordinal.toString),
          scene.fold("")(_.label),
          "",
          ""
        )
      }
      source = (TimedSourceArtifactCodec.Header +: sourceRows).map(csv).mkString("", "\n", "\n")
      parts = Json
        .fromValues(Vector(in.atlas.manifest.partA, in.atlas.manifest.partB).map { p =>
          Json.obj(
            "part" -> str(p.partId),
            "start" -> str("0"),
            "end" -> str(p.durationTicks.toString),
            "numerator" -> str("1"),
            "denominator" -> str(p.ticksPerSecond.toString)
          )
        })
        .spaces2 + "\n"
      _ <- TimedSourceArtifactCodec.prepare(
        source.getBytes(StandardCharsets.UTF_8),
        parts.getBytes(StandardCharsets.UTF_8)
      )
      index = Json.obj(
        "schema" -> str(StudyExport.Schema),
        "study" -> str("sherlock"),
        "condition" -> str("annotation"),
        "model" -> str(model),
        "system" -> str(system),
        "systemSha256" -> str(Checksum.ofText(system).hex),
        "replyProtocol" -> str("coercing-open/v1"),
        "attempts" -> Json.fromInt(AgentReplay.Attempts),
        "retrySuffix" -> str("\n\nReply with strictly valid JSON only (attempt {attempt})."),
        "extras" -> Json.arr(str("alts"), str("rows"), str("conf")),
        "unitLine" -> str("[{u}] {text}"),
        "chunkLine" -> str("TARGET UNITS: map every unit numbered {lo} through {hi} inclusive."),
        "manifestSha256" -> str(Checksum.ofText(frozenPackets).hex),
        "sessions" -> Json.fromValues(
          entries.map(e =>
            e.mapObject { o =>
              val name = o("participant").get.asString.get
              o.add("session", str("recall")).add("file", str(s"sessions/${name}_recall.json"))
            }
          )
        )
      )
      checked <- StudyExport.parseIndex(index)
      _ <- sessions.zip(checked.sessions).foldLeft[Either[String, Unit]](Right(())) {
        case (acc, ((_, doc), entry)) =>
          acc.flatMap(_ => StudyExport.parseSession(doc, entry).map(_ => ()))
      }
    yield sessions
      .map((name, doc) => s"sessions/${name}_recall.json" -> (doc.spaces2 + "\n"))
      .toMap ++
      Map(
        "source.csv" -> source,
        "parts.json" -> parts,
        "manifest.json" -> frozenPackets,
        "index.json" -> (index.spaces2 + "\n")
      )
