package storymodel4s.bench.sherlock

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import io.circe.Json
import io.circe.parser.parse
import storymodel4s.align.AlignWire
import storymodel4s.bench.video.{RecallTiming, RecallWordsCsv}
import storymodel4s.core.{Checksum, StorySource}
import storymodel4s.corpus.intake.SherlockAnnotations
import storymodel4s.recall.RecallSegmenter

/** Observation-only export: segmentation and word timing, without inference, predictions or gold.
  * The complete declared population is exported; partition selection belongs to evaluation.
  */
object SherlockUnitManifest:
  def main(args: Array[String]): Unit =
    require(args.length == 4, "data-root recall-lineage.json output.json code-revision")
    val root = Paths.get(args(0))
    val output = Paths.get(args(2))
    require(!Files.exists(output), "output already exists")
    require(args(3).matches("[0-9a-f]{40}"), "expected exact code revision")
    val partitionBytes = Files.readAllBytes(root.resolve("study/recall-to-video/partition.json"))
    val partition =
      parse(new String(partitionBytes, StandardCharsets.UTF_8)).fold(throw _, identity)
    val names = Vector("development", "untouchedTest")
      .flatMap { group =>
        partition.hcursor.get[Vector[String]](group).fold(throw _, identity).map(_ -> group)
      }
      .sortBy(_._1)
    require(
      names.size == 17 && names.map(_._1).distinct.size == names.size,
      "expected fixed 17 participants"
    )
    require(
      names.flatMap(n => SherlockSceneCoding.participantOf(n._1)).sorted == (1 to 17).toVector
    )
    require(names.forall(n => n._1.matches("[A-Za-z0-9_]+")), "unsafe participant name")
    val lineageBytes = Files.readAllBytes(Paths.get(args(1)))
    val lineage = parse(new String(lineageBytes, StandardCharsets.UTF_8)).fold(throw _, identity)
    val lineageRows = lineage.hcursor
      .get[Vector[Json]]("sources")
      .fold(throw _, identity)
      .map { row =>
        row.hcursor.get[String]("id").fold(throw _, identity) ->
          row.hcursor.get[String]("localCsvSha256").fold(throw _, identity)
      }
    require(lineageRows.map(_._1).distinct.size == lineageRows.size, "duplicate source ID")
    require(lineageRows.map(_._2).distinct.size == lineageRows.size, "ambiguous source receipt")
    val admitted = lineageRows.toMap
    val sourceBytes =
      Files.readAllBytes(root.resolve("sherlock/Sherlock_Segments_1000_NN_2017.tsv"))
    val _ = SherlockAnnotations
      .parse(sourceBytes)
      .fold(e => throw new IllegalArgumentException(e.message), identity)
    val sourceHash = Checksum.ofBytes(sourceBytes).hex
    val participants = names.map { (name, group) =>
      val raw = Files.readAllBytes(root.resolve(s"sherlock/recall/$name.csv"))
      val rawHash = Checksum.ofBytes(raw).hex
      val number = SherlockSceneCoding.participantOf(name).get
      require(
        admitted.get(f"recall-source-$number%02d").contains(rawHash),
        s"unadmitted participant bytes: $name"
      )
      val words = RecallWordsCsv
        .parse(new String(raw, StandardCharsets.UTF_8))
        .fold(e => throw new IllegalArgumentException(e), identity)
      val text = RecallWordsCsv.transcriptText(words)
      val source = StorySource
        .fromText(text, Some("sherlock-recall"))
        .fold(e => throw new IllegalArgumentException(e.message), identity)
      val recall = RecallSegmenter.segment(source)
      val timings = RecallTiming.unitTimings(words, recall.ordered)
      val units = recall.ordered.map { unit =>
        val time = timings(unit.id).onsetSeconds
        require(time.forall(n => n.isFinite && n >= 0.0), "invalid unit onset")
        Json.obj(
          "id" -> Json.fromString(unit.id.toString),
          "ordinal" -> Json.fromInt(unit.ordinal),
          "textSha256" -> Json.fromString(Checksum.ofText(unit.text).hex),
          "reportTextSha256" -> Json.fromString(
            Checksum.ofText(unit.text.replaceAll("[\\t\\n\\r]+", " ").trim).hex
          ),
          "onsetSeconds" -> time.fold(Json.Null)(n => Json.fromString(n.toString)),
          "sourceInputSha256" -> Json.fromString(sourceHash)
        )
      }
      Json.obj(
        "participant" -> Json.fromString(name),
        "partition" -> Json.fromString(group),
        "recallSha256" -> Json.fromString(rawHash),
        "transcriptSha256" -> Json.fromString(Checksum.ofText(text).hex),
        "recallGraphSha256" -> Json.fromString(AlignWire.recallChecksum(recall).hex),
        "units" -> Json.fromValues(units)
      )
    }
    val result = Json.obj(
      "schema" -> Json.fromString("storymodel4s.bench.recall-unit-manifest/v1"),
      "codeRevision" -> Json.fromString(args(3)),
      "sourceInputSha256" -> Json.fromString(sourceHash),
      "partitionSha256" -> Json.fromString(Checksum.ofBytes(partitionBytes).hex),
      "lineageSha256" -> Json.fromString(Checksum.ofBytes(lineageBytes).hex),
      "ruleSha256" -> Json.fromString(SherlockGoldRule.checksum.hex),
      "inferenceUnit" -> Json.fromString("RecallSegmenter idea unit; uniform evaluation weight"),
      "participants" -> Json.fromValues(participants)
    )
    val _ = Files.write(output, result.noSpaces.getBytes(StandardCharsets.UTF_8))
