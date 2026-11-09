package storymodel4s.pipeline.agentmapping

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import storymodel4s.core.Checksum
import storymodel4s.corpus.intake.{SherlockAnnotations, TimebaseRepair}

/** CLI: `agentMappingPackets ANNOTATION TRANSCRIPT_A TRANSCRIPT_B CAPTIONS PARTITION UNITS_DIR
  * CHUNK OUTPUT [OUTLINE_COPY]`.
  *
  * Renders the film packet and every development participant's v2 prompts, and writes only their
  * SHA-256 identities (never the text) so a recorded exchange can be matched by content.
  * Participants outside the development partition are skipped and listed. `OUTLINE_COPY`, for
  * diagnosis, writes the rendered film packet (film annotation text, so keep it outside the
  * repository).
  */
@main def agentMappingPackets(
    annotation: String,
    transcriptA: String,
    transcriptB: String,
    captions: String,
    partition: String,
    unitsDir: String,
    chunk: Int,
    output: String,
    outlineCopy: String*
): scala.Unit =
  def bytes(p: String) = Files.readAllBytes(Path.of(p))
  def sha(s: String) = Checksum.ofBytes(s.getBytes(StandardCharsets.UTF_8)).hex
  def die(msg: String): Nothing =
    System.err.println(s"refused: $msg")
    sys.exit(2)
  val out = Path.of(output)
  if chunk <= 0 then die("chunk must be positive")
  if Files.exists(out) then die("output exists")
  val record = TimebaseRepair.loadCommitted().fold(e => die(e.message), identity)
  val atlas =
    SherlockAnnotations.parse(bytes(annotation), record).fold(e => die(e.message), identity)
  val speech = Map(
    atlas.manifest.partA.partId ->
      FilmOutline.transcript(bytes(transcriptA)).fold(r => die(r.toString), identity),
    atlas.manifest.partB.partId ->
      FilmOutline.transcript(bytes(transcriptB)).fold(r => die(r.toString), identity)
  )
  val caps = FilmOutline.captions(bytes(captions)).fold(r => die(r.toString), identity)
  val outline = FilmOutline.render(atlas, speech, caps).fold(r => die(r.toString), identity)
  outlineCopy.headOption.foreach(p => Files.writeString(Path.of(p), outline): scala.Unit)
  val development = io.circe.jawn
    .parse(new String(bytes(partition), StandardCharsets.UTF_8))
    .toOption
    .flatMap(_.hcursor.get[Vector[String]]("development").toOption)
    .getOrElse(die("partition has no development list"))
    .toSet
  val listing = Files.list(Path.of(unitsDir))
  val tables =
    try
      listing.iterator.asScala
        .map(_.getFileName.toString)
        .collect { case s"recall-map-$name.tsv" => name }
        .toVector
        .sorted
    finally listing.close()
  val (dev, skipped) = tables.partition(development.contains)
  val missing = development.toVector.sorted.filterNot(tables.contains)
  val system = AgentPrompts.system(AgentPrompts.Version.V2)
  val prompts = dev.map { name =>
    val table = Files.readString(Path.of(unitsDir).resolve(s"recall-map-$name.tsv"))
    val units = AgentPrompts.units(table).fold(r => die(s"$name: $r"), identity)
    Json.obj(
      "participant" -> Json.fromString(name),
      "units" -> Json.fromInt(units.size),
      "promptSha256" -> Json.fromValues(
        AgentPrompts.chunks(outline, units, chunk).map(p => Json.fromString(sha(p)))
      )
    )
  }
  val doc = Json.obj(
    "schema" -> Json.fromString("storymodel4s.agentmapping.packet-identities/v1"),
    "outlineSha256" -> Json.fromString(sha(outline)),
    "systemSha256" -> Json.fromString(sha(system)),
    "chunk" -> Json.fromInt(chunk),
    "participants" -> Json.fromValues(prompts),
    "skippedNotDevelopment" -> Json.fromValues(skipped.map(Json.fromString)),
    "developmentWithoutUnitTable" -> Json.fromValues(missing.map(Json.fromString))
  )
  Files.writeString(out, doc.spaces2 + "\n"): scala.Unit
  println(s"outline ${sha(outline)} participants=${dev.size} skipped=${skipped.size} " +
    s"missing=${missing.size}")
