package storymodel4s.pipeline.agentmapping

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import storymodel4s.corpus.intake.{SherlockAnnotations, TimebaseRepair}

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
