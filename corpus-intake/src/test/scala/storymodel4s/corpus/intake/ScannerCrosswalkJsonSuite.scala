package storymodel4s.corpus.intake

import io.circe.Json
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.recall.RecallTiming

object ScannerCrosswalkFixture:
  import ScannerCrosswalk.*
  val key = RunKey("synthetic", "v1", "p1", None, "recall", "1")
  val evidence = Checksum.ofText("synthetic-evidence")
  val run: Run = Run
    .regular(
      key,
      Checksum.ofText("synthetic-image"),
      Checksum.ofText("synthetic-header"),
      8,
      ExactRational.of(3L, 2L).toOption.get,
      Origin.FirstStoredSample,
      AppliedHistory.Unknown
    )
    .toOption
    .get
  val layout: Layout = Layout
    .declared(
      run,
      Checksum.ofText("synthetic-analysis"),
      5,
      Vector(
        Slot.Padding("leading"),
        Slot.Padding("leading"),
        Slot.Acquired(2, Censoring.Included),
        Slot.Acquired(4, Censoring.Censored(evidence)),
        Slot.Acquired(7, Censoring.Unknown)
      ),
      evidence
    )
    .toOption
    .get
  val reference: Reference = Reference.recall(
    RecallTiming.Clock.declared(
      Checksum.ofText("synthetic-clock"),
      RecallTiming.ClockKey.unsafe("openneuro-word-onset-seconds"),
      Checksum.ofText("columns"),
      RecallTiming.RecordingIdentity.Unestablished,
      RecallTiming.Origin.Unestablished
    )
  )
  val binding: Binding = Binding
    .declared(
      reference,
      run,
      Window.of(ExactRational.Zero, ExactRational.integer(20L)).toOption.get,
      ExactRational.of(3L, 2L).toOption.get,
      ExactRational.integer(-2L),
      Evidence(reference.digest, run.digest, evidence, Vector(run.header))
    )
    .toOption
    .get
  def main(args: Array[String]): Unit =
    require(args.isEmpty, "no arguments")
    Vector(
      "run" -> ScannerCrosswalkJson.encodeRun(run),
      "layout" -> ScannerCrosswalkJson.encodeLayout(layout),
      "binding" -> ScannerCrosswalkJson.encodeBinding(binding)
    ).foreach((kind, json) => println("SCANNER_FIXTURE\t" + kind + "\t" + json))
    Vector(0L, 1L, 4L, 19L).foreach { value =>
      val mapped = binding.project(reference.at(ExactRational.integer(value))).toOption.get
      println(
        s"SCANNER_LANDMARK\t$value\t${mapped.seconds.numerator}\t${mapped.seconds.denominator}"
      )
    }

class ScannerCrosswalkJsonSuite extends FunSuite:
  import ScannerCrosswalkFixture.*
  test("scanner artifacts roundtrip declarations without promoting their authority") {
    val runText = ScannerCrosswalkJson.encodeRun(run)
    assertEquals(
      ScannerCrosswalkJson.encodeRun(ScannerCrosswalkJson.decodeRun(runText).toOption.get),
      runText
    )
    val layoutText = ScannerCrosswalkJson.encodeLayout(layout)
    assertEquals(
      ScannerCrosswalkJson.encodeLayout(
        ScannerCrosswalkJson.decodeLayout(layoutText, run).toOption.get
      ),
      layoutText
    )
    val bindingText = ScannerCrosswalkJson.encodeBinding(binding)
    assertEquals(
      ScannerCrosswalkJson.encodeBinding(
        ScannerCrosswalkJson.decodeBinding(bindingText, reference, run).toOption.get
      ),
      bindingText
    )
    assert(runText.contains("declared-not-independently-verified"))
  }
  test("foreign context tampered sample times missingness authority and duplicate fields refuse") {
    val other = ScannerCrosswalk.Run
      .regular(
        key.copy(participant = "other"),
        run.image,
        run.header,
        8,
        ExactRational.of(3L, 2L).toOption.get,
        run.origin,
        run.history
      )
      .toOption
      .get
    val encoded = ScannerCrosswalkJson.encodeLayout(layout)
    assert(ScannerCrosswalkJson.decodeLayout(encoded, other).isLeft)
    val json = ScannerCrosswalkJson.layoutToJson(layout)
    val obj = json.asObject.get
    Vector(
      Json.fromJsonObject(obj.add("samples", Json.arr())),
      Json.fromJsonObject(obj.add("dropped_stored_indices", Json.arr())),
      Json.fromJsonObject(obj.add("authority", Json.fromString("verified"))),
      Json.fromJsonObject(obj.add("unknown", Json.Null))
    ).foreach(j => assert(ScannerCrosswalkJson.decodeLayout(j.noSpaces, run).isLeft))
    assert(
      ScannerCrosswalkJson.decodeLayout("{\"digest\":\"wrong\"," + encoded.drop(1), run).isLeft
    )
    assert(
      ScannerCrosswalkJson
        .decodeBinding(ScannerCrosswalkJson.encodeBinding(binding), reference, other)
        .isLeft
    )
    val rawRun = ScannerCrosswalkJson.encodeRun(run)
    assert(
      ScannerCrosswalkJson
        .decodeRun(rawRun.replace("\"numerator\":\"0\"", "\"numerator\":0"))
        .isLeft
    )
    assert(
      ScannerCrosswalkJson
        .decodeRun(
          rawRun.replace("\"status\":\"unknown\"", "\"status\":\"declared\",\"receipts\":[]")
        )
        .isLeft
    )
  }
