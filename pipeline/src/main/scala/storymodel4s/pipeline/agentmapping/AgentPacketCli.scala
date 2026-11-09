package storymodel4s.pipeline.agentmapping

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import storymodel4s.core.Checksum
import storymodel4s.provider.agent.{HarnessCacheImport, Recordings, TextExchange}

private def sha(s: String): String = Checksum.ofBytes(s.getBytes(StandardCharsets.UTF_8)).hex

private def die(msg: String): Nothing =
  System.err.println(s"refused: $msg")
  sys.exit(2)

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
  val out = Path.of(output)
  if chunk <= 0 then die("chunk must be positive")
  if Files.exists(out) then die("output exists")
  val in = AgentPackets
    .load(annotation, transcriptA, transcriptB, captions, partition, unitsDir)
    .fold(die, identity)
  outlineCopy.headOption.foreach(p => Files.writeString(Path.of(p), in.outline): scala.Unit)
  val system = AgentPrompts.system(AgentPrompts.Version.V2)
  val prompts = in.development.map { name =>
    val units = AgentPackets.units(in, name).fold(die, identity)
    Json.obj(
      "participant" -> Json.fromString(name),
      "units" -> Json.fromInt(units.size),
      "promptSha256" -> Json.fromValues(
        AgentPrompts.chunks(in.outline, units, chunk).map(p => Json.fromString(sha(p)))
      )
    )
  }
  val doc = Json.obj(
    "schema" -> Json.fromString("storymodel4s.agentmapping.packet-identities/v1"),
    "outlineSha256" -> Json.fromString(sha(in.outline)),
    "systemSha256" -> Json.fromString(sha(system)),
    "chunk" -> Json.fromInt(chunk),
    "participants" -> Json.fromValues(prompts),
    "skippedNotDevelopment" -> Json.fromValues(in.skipped.map(Json.fromString)),
    "developmentWithoutUnitTable" -> Json.fromValues(in.missing.map(Json.fromString))
  )
  Files.writeString(out, doc.spaces2 + "\n"): scala.Unit
  println(
    s"outline ${sha(in.outline)} participants=${in.development.size} " +
      s"skipped=${in.skipped.size} missing=${in.missing.size}"
  )

/** CLI: `agentMappingReplay ANNOTATION TRANSCRIPT_A TRANSCRIPT_B CAPTIONS PARTITION UNITS_DIR
  * RECORDINGS MODEL CHUNK OUTPUT_DIR`.
  *
  * Replays every development participant's v2 mapping from text-exchange recordings, with no model
  * client, and writes one reply file per participant (`agentMappingArm` reads them) plus a receipt
  * of the attempts each chunk used. Any exchange the replay needs and cannot find refuses the run,
  * and so does a development participant without a unit table: the replay never maps a smaller
  * population than the partition names. Replies keep only the `narrative` choice.
  */
@main def agentMappingReplay(
    annotation: String,
    transcriptA: String,
    transcriptB: String,
    captions: String,
    partition: String,
    unitsDir: String,
    recordingsDir: String,
    model: String,
    chunk: Int,
    outputDir: String
): scala.Unit =
  val out = Path.of(outputDir)
  if chunk <= 0 then die("chunk must be positive")
  if Files.exists(out) then die("output directory exists")
  val in = AgentPackets
    .load(annotation, transcriptA, transcriptB, captions, partition, unitsDir)
    .fold(die, identity)
  if in.missing.nonEmpty then die(s"development without a unit table: ${in.missing.mkString(",")}")
  if in.development.isEmpty then die("no development participants to replay")
  val recordings = Recordings.open(Path.of(recordingsDir)).fold(e => die(e.message), identity)
  val system = AgentPrompts.system(AgentPrompts.Version.V2)
  val provider = TextExchange.SubscriptionCliProvider
  val results = in.development.map { name =>
    val units = AgentPackets.units(in, name).fold(die, identity)
    val ids = AgentPackets.unitIds(in, name).fold(die, identity)
    val (reply, receipts) = AgentReplay
      .participant(
        name,
        in.outline,
        units,
        ids,
        chunk,
        p => TextExchange.replay(recordings, provider, model, system, p)
      )
      .fold(f => die(f.toString), identity)
    name -> (reply, receipts)
  }
  Files.createDirectories(out): scala.Unit
  results.foreach { case (name, (reply, _)) =>
    Files.writeString(out.resolve(s"$name.json"), reply.spaces2 + "\n"): scala.Unit
  }
  val receipt = Json.obj(
    "schema" -> Json.fromString("storymodel4s.agentmapping.replay-receipt/v1"),
    "provider" -> Json.fromString(provider),
    "model" -> Json.fromString(model),
    "outlineSha256" -> Json.fromString(sha(in.outline)),
    "systemSha256" -> Json.fromString(sha(system)),
    "chunk" -> Json.fromInt(chunk),
    "modelCalls" -> Json.fromInt(0),
    "participants" -> Json.fromValues(results.map { case (name, (_, rs)) =>
      Json.obj(
        "participant" -> Json.fromString(name),
        "chunks" -> Json.fromValues(rs.map { r =>
          Json.obj(
            "chunk" -> Json.fromInt(r.chunk),
            "attempts" -> Json.fromInt(r.attempts),
            "accepted" -> Json.fromBoolean(r.accepted)
          )
        })
      )
    })
  )
  Files.writeString(out.resolve("replay-receipt.json"), receipt.spaces2 + "\n"): scala.Unit
  val exchanges = results.map(_._2._2.map(_.attempts).sum).sum
  println(s"replayed participants=${results.size} exchanges=$exchanges modelCalls=0")

/** CLI: `agentHarnessImport CACHE_DIR RECORDINGS_DIR`: import the research harness's reply cache as
  * text-exchange recordings (see `HarnessCacheImport`).
  */
@main def agentHarnessImport(cacheDir: String, recordingsDir: String): scala.Unit =
  val recordings = Recordings.at(Path.of(recordingsDir)).fold(e => die(e.message), identity)
  HarnessCacheImport.run(Path.of(cacheDir), recordings) match
    case Right(done) =>
      println(s"imported ${done.records} " + done.models.toVector.sorted.mkString(" "))
    case Left(r) => die(r.toString)

/** CLI: `agentMappingBundle ANNOTATION TRANSCRIPT_A TRANSCRIPT_B CAPTIONS PARTITION UNITS_DIR
  * RECALL_DIR RECORDINGS MODEL CHUNK OUTPUT_DIR`.
  *
  * Replays every development participant from recordings, with no model client, and writes one
  * bundle per participant (see `AgentBundle`): both interpretations as checked categorical records,
  * every unit's full answer, and the source dictionary. Each bundle is read back and re-checked
  * before the command reports. `RECALL_DIR` holds `<participant>.csv` word tables. The output holds
  * film and recall-derived text, so keep it outside the repository.
  */
@main def agentMappingBundle(
    annotation: String,
    transcriptA: String,
    transcriptB: String,
    captions: String,
    partition: String,
    unitsDir: String,
    recallDir: String,
    recordingsDir: String,
    model: String,
    chunk: Int,
    outputDir: String
): scala.Unit =
  import AgentMappingRecord.{Interpretation, Producer}
  val out = Path.of(outputDir)
  if chunk <= 0 then die("chunk must be positive")
  if Files.exists(out) then die("output directory exists")
  val in = AgentPackets
    .load(annotation, transcriptA, transcriptB, captions, partition, unitsDir)
    .fold(die, identity)
  if in.missing.nonEmpty then die(s"development without a unit table: ${in.missing.mkString(",")}")
  if in.development.isEmpty then die("no development participants to replay")
  val recordings = Recordings.open(Path.of(recordingsDir)).fold(e => die(e.message), identity)
  val system = AgentPrompts.system(AgentPrompts.Version.V2)
  val provider = TextExchange.SubscriptionCliProvider
  val film = AgentMappingRecord.film(in.atlas, in.annotationSha256).fold(die, identity)
  val producer = Producer(
    provider,
    model,
    Checksum.ofBytes(system.getBytes(StandardCharsets.UTF_8)),
    Checksum.ofBytes(in.outline.getBytes(StandardCharsets.UTF_8))
  )
  val (scenes, rows) = AgentBundleSource.dictionary(in.atlas, film)
  val bundles = in.development.map { name =>
    val units = AgentPackets.units(in, name).fold(die, identity)
    val ids = AgentPackets.unitIds(in, name).fold(die, identity)
    val csv =
      try Files.readAllBytes(Path.of(recallDir).resolve(s"$name.csv"))
      catch case scala.util.control.NonFatal(_) => die(s"$name: cannot read recall table")
    val inventory = AgentMappingRecord.inventory(csv).fold(e => die(s"$name: $e"), identity)
    val (outcomes, replays) = AgentAnswers
      .participant(
        name,
        in.outline,
        units,
        chunk,
        in.atlas.scenes.size,
        p => TextExchange.replay(recordings, provider, model, system, p)
      )
      .fold(f => die(f.toString), identity)
    val named = outcomes.map((u, o) => ids.getOrElse(u.index, die(s"$name: no id for ${u.index}")) -> o)
    if named.map(_._1) != inventory.units.map(_.id.value) then
      die(s"$name: unit table ids do not match the rebuilt recall inventory")
    val keyed = inventory.units.map(_.id).zip(named.map(_._2))
    def record(i: Interpretation) = AgentMappingRecord
      .record(i, inventory, film, producer, keyed)
      .fold(e => die(s"$name ${i.field}: ${e.message}"), identity)
    val narrative = record(Interpretation.Narrative)
    val referent = record(Interpretation.Referent)
    val unitRows = outcomes.zip(named).map { case ((u, o), (id, _)) =>
      AgentBundle.unitRow(
        u.index,
        id,
        o,
        p => TextExchange.key(provider, model, system, p).checksum.hex
      )
    }
    val doc = AgentBundle.encode(
      AgentBundle.Provenance(
        name,
        provider,
        model,
        producer.systemSha256.hex,
        producer.outlineSha256.hex,
        in.annotationSha256.hex,
        Checksum.ofBytes(csv).hex,
        chunk
      ),
      narrative,
      referent,
      unitRows,
      scenes,
      rows
    )
    val reread = io.circe.jawn
      .parse(doc.spaces2)
      .left
      .map(_.message)
      .flatMap(AgentBundle.decode(_, inventory, film))
      .fold(e => die(s"$name: bundle does not reload: $e"), identity)
    if reread.narrative.digest != narrative.digest || reread.referent.digest != referent.digest ||
      reread.units != unitRows
    then die(s"$name: reloaded bundle differs")
    (name, doc, unitRows, replays.map(_.attempts).sum)
  }
  Files.createDirectories(out): scala.Unit
  bundles.foreach { (name, doc, _, _) =>
    Files.writeString(out.resolve(s"$name.bundle.json"), doc.spaces2 + "\n"): scala.Unit
  }
  val all = bundles.flatMap(_._3)
  def count(status: String) = all.count(_.status == status)
  println(
    s"bundles=${bundles.size} units=${all.size} answered=${count("answered")} " +
      s"omitted=${count("omitted")} unparsable=${count("unparsable")} " +
      s"exchanges=${bundles.map(_._4).sum} modelCalls=0"
  )
