package storymodel4s.bench.sherlock

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import io.circe.Json

import storymodel4s.align.{AlignWire, ViewFingerprint}
import storymodel4s.bench.video.{RecallTiming, RecallWordsCsv}
import storymodel4s.core.{AxisExtent, Checksum, StorySource}
import storymodel4s.corpus.intake.{SherlockAnnotations, TimebaseRepair}
import storymodel4s.recall.RecallSegmenter

/** Test-scope inventory of the existing adapter, independent of which targets inference selects. It
  * emits coordinates and identities only. It does not run inference or read scene coding.
  */
object SherlockBaselineCapture:
  private def str(s: String): Json = Json.fromString(s)
  private def int(n: Int): Json = Json.fromInt(n)
  private def tick(n: Long): Json = str(n.toString)

  private[sherlock] def inventory(annotation: Array[Byte], recallCsv: Array[Byte]): Json =
    val record = TimebaseRepair
      .loadCommitted()
      .fold(e => throw new IllegalArgumentException(e.message), identity)
    val atlas = SherlockAnnotations
      .parse(annotation, record)
      .fold(e => throw new IllegalArgumentException(e.message), identity)
    val built = SherlockAnnotationView
      .build(atlas)
      .fold(e => throw new IllegalArgumentException(e.message), identity)
    val words = RecallWordsCsv
      .parse(new String(recallCsv, StandardCharsets.UTF_8))
      .fold(e => throw new IllegalArgumentException(e), identity)
    val transcriptText = RecallWordsCsv.transcriptText(words)
    val transcript = StorySource
      .fromText(transcriptText, Some("sherlock-recall"))
      .fold(e => throw new IllegalArgumentException(e.message), identity)
    val recall = RecallSegmenter.segment(transcript)
    val units = recall.ordered
    val spans = RecallTiming.wordSpans(words)
    val nodeTexts = built.nodeTexts.toMap
    val recallHash = Checksum.ofBytes(recallCsv).hex
    val rowLoci = atlas.rows.map { r =>
      val locus = atlas.mediaByRow(r.row)
      Json.obj(
        "row" -> int(r.row),
        "part" -> str(locus.part),
        "startTick" -> tick(locus.startTick),
        "endTick" -> tick(locus.endTick),
        "kind" -> str(if locus.startTick == locus.endTick then "instant" else "extent")
      )
    }
    val axes = SherlockAnnotationView.axes(atlas).toVector.sortBy(_._1).map { (part, axis) =>
      val extent = axis.extent match
        case p: AxisExtent.PlaybackTicks => p
        case _ => throw new IllegalArgumentException("expected playback extent")
      val timebase = axis.timebase.getOrElse(
        throw new IllegalArgumentException("expected playback timebase")
      )
      Json.obj(
        "part" -> str(part),
        "axis" -> str(axis.id.value),
        "bundle" -> str(axis.bundle.value),
        "kind" -> str(axis.kind.toString),
        "startTick" -> tick(extent.start),
        "endTick" -> tick(extent.endExclusive),
        "secondsNumerator" -> tick(timebase.scale.numerator),
        "secondsDenominator" -> tick(timebase.scale.denominator)
      )
    }
    Json.obj(
      "schema" -> str("storymodel4s.bench.baseline-inventory/v1"),
      "annotationSha256" -> str(Checksum.ofBytes(annotation).hex),
      "recallSha256" -> str(recallHash),
      "transcriptSha256" -> str(Checksum.ofText(transcriptText).hex),
      "recallGraphSha256" -> str(AlignWire.recallChecksum(recall).hex),
      "runtime" -> Json.obj(
        Vector("java.version", "java.vendor", "os.name", "os.arch", "os.version")
          .map(k => k -> str(System.getProperty(k)))*
      ),
      "sourceFingerprint" -> str(ViewFingerprint.of(built.view).checksum.hex),
      "worldOrder" -> str(built.worldOrder.render),
      "wordIdPolicy" -> str("input-artifact-sha256+zero-based-parsed-word-index/v1"),
      "axes" -> Json.fromValues(axes),
      "rowLoci" -> Json.fromValues(rowLoci),
      "targets" -> Json.fromValues(built.view.nodes.sortBy(_.ref.key).map { n =>
        val locus = built.media.get(n.ref)
        Json.obj(
          "id" -> str(n.ref.key),
          "level" -> int(n.level),
          "renderSha256" -> str(Checksum.ofText(nodeTexts(n.ref)).hex),
          "supportStartUtf16" -> int(n.support.minSpan.start),
          "supportEndUtf16" -> int(n.support.minSpan.endExclusive),
          "part" -> locus.fold(Json.Null)(l => str(l.part)),
          "startTick" -> locus.fold(Json.Null)(l => tick(l.startTick)),
          "endTick" -> locus.fold(Json.Null)(l => tick(l.endTick))
        )
      }),
      "words" -> Json.fromValues(words.zip(spans).zipWithIndex.map { case ((w, span), i) =>
        Json.obj(
          "id" -> str(s"$recallHash:word:$i"),
          "index" -> int(i),
          "startUtf16" -> int(span.start),
          "endUtf16" -> int(span.endExclusive),
          "onsetSeconds" -> w.onsetSeconds.fold(Json.Null)(n => str(n.toString))
        )
      }),
      "units" -> Json.fromValues(units.map { u =>
        Json.obj(
          "id" -> str(u.id.toString),
          "ordinal" -> int(u.ordinal),
          "startUtf16" -> int(u.minSpan.start),
          "endUtf16" -> int(u.minSpan.endExclusive),
          "textSha256" -> str(Checksum.ofText(u.text).hex),
          "reportTextSha256" -> str(
            Checksum.ofText(u.text.replaceAll("[\\t\\n\\r]+", " ").trim).hex
          ),
          "function" -> str(u.function.toString),
          "wordIndices" -> Json.fromValues(spans.zipWithIndex.collect {
            case (s, i) if s.start < u.minSpan.endExclusive && s.endExclusive > u.minSpan.start =>
              int(i)
          })
        )
      })
    )

  def main(args: Array[String]): Unit =
    require(args.length == 3, "annotation.tsv recall.csv inventory.json")
    val result =
      inventory(Files.readAllBytes(Paths.get(args(0))), Files.readAllBytes(Paths.get(args(1))))
    val _ = Files.write(Paths.get(args(2)), result.noSpaces.getBytes(StandardCharsets.UTF_8))
