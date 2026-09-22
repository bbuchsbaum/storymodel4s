package storymodel4s.corpus.intake

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallTiming.{Refusal as _, *}
import storymodel4s.corpus.intake.RecallTimingIntake.*

class RecallTimingIntakeSuite extends FunSuite:
  private val header = Vector("Words", "princeton", "openneuro")
  private val columns = Columns(header, 0, 2, ClockKey.unsafe("openneuro-word-onset-seconds"))
  private def context(words: Vector[String], splitWords: Boolean = false) =
    val text = words.mkString(" ")
    val source = StorySource.fromText(text).toOption.get
    val unit = RecallUnit(
      RecallUnitId.unsafe("u"),
      0,
      SpanSet.one(TextSpan.unsafe(0, words.head.length)),
      words.head,
      DiscourseFunction.EpisodicAssertion,
      ExpressedUncertainty.Unmarked,
      PropositionSketch.empty,
      None
    )
    val graph = RecallGraph
      .validated(source, SurfaceAnalyzer.analyze(source), Vector(unit), RecallRelations.empty)
      .toOption
      .get
    val spans =
      if splitWords then
        "\\S+".r.findAllMatchIn(text).map(m => TextSpan.unsafe(m.start, m.end)).toVector
      else
        words.zip(words.scanLeft(0)((n, w) => n + w.length + 1)).map { (w, start) =>
          TextSpan.unsafe(start, start + w.length)
        }
    val inventory = RecallInventory
      .of(graph, spans, WordIdPolicy.inputArtifact(Checksum.ofText("original-parser-input")))
      .toOption
      .get
    (graph, inventory)
  private def run(
      csv: String,
      words: Vector[String],
      cols: Columns = columns,
      splitWords: Boolean = false
  ): Either[Refusal, Result] =
    val (graph, inventory) = context(words, splitWords)
    val bytes = csv.getBytes(UTF_8)
    read(bytes, Checksum.ofBytes(bytes), cols, graph, inventory)
  private val csv =
    "Words,princeton,openneuro\nAnna,0,\n,1,8.5\narrived.,1.25,8.75\nBob,2.5,10\nleft.,3.5,9\n"
  private val words = Vector("Anna", "arrived.", "Bob", "left.")

  test("explicit clock selection preserves all records words and exact missingness") {
    val result = run(csv, words).toOption.get
    assertEquals(result.records.size, 5)
    assertEquals(result.records(1).binding, RecordBinding.ExcludedBlankWord)
    assertEquals(result.timing.entries.size, 4)
    assertEquals(
      result.timing.entries.head.observation,
      Observation.Missing(MissingReason.BlankSourceCell)
    )
    assertEquals(
      result.timing.entries(1).observation,
      Observation.OnsetOnly(
        ExactRational.of(35, 4).toOption.get,
        Basis.SourceReported(Checksum.ofBytes(csv.getBytes(UTF_8)))
      )
    )
    assertEquals(
      result.timing.inventory.membership.values.count(_.isInstanceOf[WordMembership.Unassigned]),
      3
    )
    assertEquals(result.timing.clock.recording, RecordingIdentity.Unestablished)
    assertEquals(result.timing.clock.origin, Origin.Unestablished)
    assertEquals(result.timing.provenance.recordingLink, RecordingLink.NotEstablished)
    val alternative =
      run(csv, words, columns.copy(onset = 1, clock = ClockKey.unsafe("princeton"))).toOption.get
    assertEquals(alternative.timing.inventory.digest, result.timing.inventory.digest)
    assertNotEquals(alternative.timing.digest, result.timing.digest)
    assertNotEquals(
      alternative.timing.provenance.correspondence,
      result.timing.provenance.correspondence
    )
    assertNotEquals(result.timing.inventory.idPolicy.artifact, result.timing.clock.artifact)
  }
  test("byte pins full transcript graph and exact parsed spans are checked independently") {
    val (graph, inventory) = context(words)
    assertEquals(
      read(csv.getBytes(UTF_8), Checksum.ofText("wrong"), columns, graph, inventory),
      Left(Refusal.ByteIdentityMismatch)
    )
    assertEquals(run(csv.replace("Anna", "Anne"), words), Left(Refusal.TranscriptMismatch))
    val (other, _) = context(Vector("Elsewhere"))
    assertEquals(
      read(csv.getBytes(UTF_8), Checksum.ofBytes(csv.getBytes(UTF_8)), columns, other, inventory),
      Left(Refusal.InventoryGraphMismatch)
    )
    assertEquals(
      run("Words,princeton,openneuro\n\"two words\",0,1\n", Vector("two words"), splitWords = true),
      Left(Refusal.WordSpansMismatch)
    )
  }
  test("quoted commas newlines doubled quotes non-BMP BOM and CRLF retain UTF-16 spans") {
    val ws = Vector("😀,a", "line\nnext", "a\"b", "\u00a0")
    val input =
      "\ufeffWords,princeton,openneuro\r\n\"😀,a\",0,9007199254740993\r\n\"line\nnext\",0,-1\r\n\"a\"\"b\",0,.1\r\n\u00a0,0,1.e2\r\n"
    val result = run(input, ws).toOption.get
    assertEquals(result.timing.inventory.words.head.span, TextSpan.unsafe(0, 4))
    assertEquals(
      result.timing.entries.head.observation,
      Observation.OnsetOnly(
        ExactRational.integer(9007199254740993L),
        Basis.SourceReported(Checksum.ofBytes(input.getBytes(UTF_8)))
      )
    )
    assertEquals(result.records.size, 4)
  }
  test("changed canonicalization refuses rather than guessing offsets") {
    assertEquals(
      run("Words,princeton,openneuro\n\"a\r\nb\",0,1", Vector("a\nb")),
      Left(Refusal.ReplayNeedsCharacterMap)
    )
  }
  test("blank selected cells remain missing while malformed and unrepresentable cells refuse") {
    val blank = run("Words,princeton,openneuro\na,0,\u00a0", Vector("a")).toOption.get
    assertEquals(
      blank.timing.entries.head.observation,
      Observation.Missing(MissingReason.BlankSourceCell)
    )
    Vector(
      "NaN" -> DecimalRefusal.Malformed,
      "1e129" -> DecimalRefusal.ResourceLimit,
      "9223372036854775808" -> DecimalRefusal.Unrepresentable
    ).foreach { (token, reason) =>
      assertEquals(
        run(s"Words,princeton,openneuro\na,0,$token", Vector("a")),
        Left(Refusal.InvalidOnset(1, reason))
      )
    }
    assertEquals(
      run("Words,princeton,openneuro\na,0,1\n,0,NaN", Vector("a")),
      Left(Refusal.InvalidOnset(2, DecimalRefusal.Malformed))
    )
  }
  test("strict CSV rejects broken quoting wrong headers ragged rows and blank records") {
    Vector("a\"b,0,1", "\"a\"x,0,1", "\"a,0,1").foreach { line =>
      assertEquals(run(s"Words,princeton,openneuro\n$line", Vector("a")), Left(Refusal.InvalidCsv))
    }
    assertEquals(run("Words,princeton,openneuro\na,0", Vector("a")), Left(Refusal.ColumnCount(1)))
    assertEquals(
      run("Words,princeton,openneuro\na,0,1\n\n", Vector("a")),
      Left(Refusal.ColumnCount(2))
    )
    assertEquals(run(csv.replace("openneuro", "other"), words), Left(Refusal.HeaderMismatch))
    assertEquals(run(csv, words, columns.copy(word = 2)), Left(Refusal.InvalidColumns))
    assertEquals(run(csv, words, columns.copy(onset = -1)), Left(Refusal.InvalidColumns))
    val (graph, inventory) = context(words)
    val invalid = Array(0xc3.toByte, 0x28.toByte)
    assertEquals(
      read(invalid, Checksum.ofBytes(invalid), columns, graph, inventory),
      Left(Refusal.InvalidUtf8)
    )
  }
