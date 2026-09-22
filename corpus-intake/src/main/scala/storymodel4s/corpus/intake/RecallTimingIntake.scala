package storymodel4s.corpus.intake

import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.recall.RecallTiming.*

/** Byte-pinned, explicit-column intake. Exact text correspondence is not recording identity. */
object RecallTimingIntake:
  val Recipe: String = "external-clock-exact-replay/v1"
  val Parser: String = "strict-comma-quoted-utf8/v1"

  /** Column indices are zero based. All header cells, including duplicate labels, are explicit.
    * Only the selected onset column is interpreted; other cells stay bound by the byte pin.
    */
  final case class Columns(header: Vector[String], word: Int, onset: Int, clock: ClockKey):
    def digest: Checksum = hash(
      Vector(Parser, Recipe, word.toString, onset.toString, clock.value) ++ header
    )

  enum RecordBinding:
    case Matched(word: RecallWordId)
    case ExcludedBlankWord

  final case class Record(number: Int, binding: RecordBinding, observation: Observation)

  final class Result private[RecallTimingIntake] (
      val timing: RecallTiming,
      val records: Vector[Record],
      val columns: Columns,
      val byteLength: Int
  )

  /** Refusals deliberately omit source text and offending cell values. */
  enum Refusal:
    case ByteIdentityMismatch, InvalidColumns, InvalidUtf8, InvalidCsv, HeaderMismatch
    case ColumnCount(record: Int)
    case InvalidOnset(record: Int, reason: DecimalRefusal)
    case InventoryGraphMismatch, ReplayNeedsCharacterMap, TranscriptMismatch, WordSpansMismatch
    case InvalidTiming(reason: RecallTiming.Refusal)

  def read(
      bytes: Array[Byte],
      expected: Checksum,
      columns: Columns,
      graph: RecallGraph[Checked],
      inventory: RecallInventory
  ): Either[Refusal, Result] =
    // Own the exact bytes being verified and parsed, even if the caller reuses its array.
    val snapshot = bytes.clone()
    for
      _ <- Either.cond(Checksum.ofBytes(snapshot) == expected, (), Refusal.ByteIdentityMismatch)
      _ <- Either.cond(
        columns.header.nonEmpty && columns.word >= 0 && columns.onset >= 0 &&
          columns.word < columns.header.size && columns.onset < columns.header.size &&
          columns.word != columns.onset,
        (),
        Refusal.InvalidColumns
      )
      _ <- Either.cond(inventory.describes(graph), (), Refusal.InventoryGraphMismatch)
      text <- utf8(snapshot)
      table <- csv(text.stripPrefix("\ufeff"))
      _ <- Either.cond(table.headOption.contains(columns.header), (), Refusal.HeaderMismatch)
      rows <- table.drop(1).zipWithIndex.traverse { (cells, index) =>
        Either.cond(cells.size == columns.header.size, cells, Refusal.ColumnCount(index + 1))
      }
      values <- rows.zipWithIndex.traverse { (cells, index) =>
        val token = numericTrim(cells(columns.onset))
        if token.isEmpty then Right(Observation.Missing(MissingReason.BlankSourceCell))
        else
          RecallTiming.decimalSeconds(token).left.map(Refusal.InvalidOnset(index + 1, _)).map {
            seconds => Observation.OnsetOnly(seconds, Basis.SourceReported(expected))
          }
      }
      words = rows.map(_(columns.word).trim)
      replay = words.filter(_.nonEmpty).mkString(" ")
      _ <- Either.cond(
        StorySource.canonicalize(replay) == replay,
        (),
        Refusal.ReplayNeedsCharacterMap
      )
      _ <- Either.cond(replay == graph.transcript.canonicalText, (), Refusal.TranscriptMismatch)
      spans = replaySpans(words.filter(_.nonEmpty))
      _ <- Either.cond(spans == inventory.words.map(_.span), (), Refusal.WordSpansMismatch)
      clock = Clock.declared(
        expected,
        columns.clock,
        columns.digest,
        RecordingIdentity.Unestablished,
        Origin.Unestablished
      )
      records = bind(words, values, inventory)
      correspondence = hash(
        Vector(Recipe, Parser, expected.hex, columns.digest.hex, inventory.digest.hex) ++
          records.map(r =>
            r.binding match
              case RecordBinding.Matched(word)     => s"${r.number}:matched:${word.value}"
              case RecordBinding.ExcludedBlankWord => s"${r.number}:excluded-blank-word"
          )
      )
      entries = records.collect { case Record(_, RecordBinding.Matched(word), observation) =>
        Entry(word, clock, observation)
      }
      timing <- RecallTiming
        .checked(
          inventory,
          clock,
          entries,
          RecallTiming.Provenance(correspondence, RecordingLink.NotEstablished)
        )
        .left
        .map(Refusal.InvalidTiming(_))
    yield new Result(timing, records, columns, snapshot.length)

  private def bind(
      words: Vector[String],
      values: Vector[Observation],
      inventory: RecallInventory
  ): Vector[Record] =
    var retained = 0
    words.zip(values).zipWithIndex.map { case ((word, value), index) =>
      val binding =
        if word.isEmpty then RecordBinding.ExcludedBlankWord
        else
          val id = inventory.words(retained).id
          retained += 1
          RecordBinding.Matched(id)
      Record(index + 1, binding, value)
    }

  private def replaySpans(words: Vector[String]): Vector[TextSpan] =
    var cursor = 0
    words.map { word =>
      val span = TextSpan.unsafe(cursor, cursor + word.length)
      cursor += word.length + 1
      span
    }

  private def hash(tokens: Vector[String]): Checksum =
    val framed = tokens.size.toString + ":" + tokens.map(s => s"${s.length}:$s").mkString
    Checksum.ofText(framed.iterator.map(c => f"${c.toInt}%04x").mkString)

  private def numericTrim(text: String): String =
    def whitespace(c: Char): Boolean =
      Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085'
    text.dropWhile(whitespace).reverse.dropWhile(whitespace).reverse

  private def utf8(bytes: Array[Byte]): Either[Refusal, String] =
    try
      Right(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      )
    catch case _: CharacterCodingException => Left(Refusal.InvalidUtf8)

  /** Strict comma CSV with quoted commas/newlines and doubled quotes. A blank physical record is a
    * one-cell record, not discarded. CRLF is one separator; quoted line endings are retained.
    */
  private def csv(text: String): Either[Refusal, Vector[Vector[String]]] =
    enum State:
      case Start, Unquoted, Quoted, AfterQuote
    val out = Vector.newBuilder[Vector[String]]
    var row = Vector.empty[String]
    val cell = new StringBuilder
    var state = State.Start
    var index = 0
    var invalid = false
    var pending = false
    def field(): Unit =
      row = row :+ cell.result()
      cell.clear()
      state = State.Start
    def record(): Unit =
      field()
      out += row
      row = Vector.empty
      pending = false
    while index < text.length && !invalid do
      val c = text.charAt(index)
      if state == State.Quoted then
        if c == '"' then state = State.AfterQuote else cell.append(c): Unit
      else if c == ',' then
        field()
        pending = true
      else if c == '\n' || c == '\r' then
        record()
        if c == '\r' && index + 1 < text.length && text.charAt(index + 1) == '\n' then index += 1
      else
        state match
          case State.Start =>
            if c == '"' then state = State.Quoted
            else
              cell.append(c): Unit
              state = State.Unquoted
          case State.Unquoted =>
            if c == '"' then invalid = true else cell.append(c): Unit
          case State.AfterQuote =>
            if c == '"' then
              cell.append(c): Unit
              state = State.Quoted
            else invalid = true
          case State.Quoted => () // Handled above.
        pending = true
      index += 1
    if invalid || state == State.Quoted then Left(Refusal.InvalidCsv)
    else
      if pending then record()
      Right(out.result())
