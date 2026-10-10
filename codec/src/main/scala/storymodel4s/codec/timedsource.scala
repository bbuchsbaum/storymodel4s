package storymodel4s.codec

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.jawn.JawnParser
import java.nio.charset.StandardCharsets.UTF_8
import storymodel4s.align.bridge.{MediaLocus, TimedSegment, TimedSourceView}
import storymodel4s.core.*
import storymodel4s.recall.Lexical

/** Replays exact annotation bytes against declared part clocks without inferring media facts. */
object TimedSourceArtifactCodec:
  val Header: Vector[String] = Vector(
    "ordinal",
    "text",
    "part",
    "onset",
    "end",
    "group",
    "group_label",
    "locations",
    "characters"
  )

  private val parser = new JawnParser(maxValueSize = None, allowDuplicateKeys = false)

  private def field[A: Decoder](j: Json, name: String): Either[String, A] =
    j.hcursor.get[A](name).left.map(_.message)

  private def long(s: String): Either[String, Long] =
    scala.util.Try(s.toLong).toEither.left.map(_ => "invalid integer tick")

  private def int(s: String): Either[String, Int] =
    scala.util.Try(s.toInt).toEither.left.map(_ => "invalid ordinal")

  private def utf8(bytes: Array[Byte]): Either[String, String] =
    val text = new String(bytes, UTF_8)
    Either.cond(text.getBytes(UTF_8).sameElements(bytes), text, "invalid UTF-8")

  private def exact(j: Json, keys: Set[String]): Either[String, Unit] =
    Either.cond(j.asObject.exists(_.keys.toSet == keys), (), "unknown or missing fields")

  /** Annotation clocks describe annotation coverage, never an unobserved movie edition. */
  private def parts(
      text: String,
      table: Vector[Vector[String]]
  ): Either[String, Map[String, SourceBundle]] =
    for
      json <- parser.parse(text).left.map(_.message)
      rows <- json.asArray.map(_.toVector).toRight("parts must be an array")
      entries <- rows.traverse { row =>
        for
          _ <- exact(row, Set("part", "start", "end", "numerator", "denominator"))
          part <- field[String](row, "part")
          partArtifact = Canonical.print(
            Json.obj(
              "part" -> Json.fromString(part),
              "header" -> Json.fromValues(Header.map(Json.fromString)),
              "rows" -> Json.fromValues(
                table.drop(1).filter(_(2) == part).map(r => Json.fromValues(r.map(Json.fromString)))
              )
            )
          )
          checksum = Checksum.ofText(partArtifact)
          start <- field[String](row, "start").flatMap(long)
          end <- field[String](row, "end").flatMap(long)
          numerator <- field[String](row, "numerator").flatMap(long)
          denominator <- field[String](row, "denominator").flatMap(long)
          timebase <- RationalTimebase.of(numerator, denominator).left.map(_.message)
          bundle <- SourceBundle.annotationTable(checksum, start, end, timebase).left.map(_.message)
        yield part -> bundle
      }
      _ <- Either.cond(
        entries.nonEmpty && entries.map(_._1).distinct.size == entries.size &&
          entries.forall(_._1.trim.nonEmpty),
        (),
        "duplicate or empty parts"
      )
    yield entries.toMap

  /** Reconstructs a checked timed source from the exact CSV and descriptor bytes. */
  def prepare(csv: Array[Byte], descriptor: Array[Byte]): Either[String, TimedSourceView] =
    for
      csvText <- utf8(csv)
      partsText <- utf8(descriptor)
      table <- records(csvText.stripPrefix("\ufeff"))
      _ <- Either.cond(
        table.headOption.contains(Header) && table.drop(1).forall(_.size == Header.size),
        (),
        "invalid annotation header or row"
      )
      declared <- parts(partsText, table)
      segments <- table.drop(1).traverse { row =>
        for
          ordinal <- int(row(0))
          locus <-
            if row(2).isEmpty && row(3).isEmpty && row(4).isEmpty then Right(None)
            else
              for
                bundle <- declared.get(row(2)).toRight("foreign part")
                onset <- long(row(3))
                value <-
                  if row(4).isEmpty then
                    PlaybackInstant
                      .onTimeAxis(bundle.primaryAxis, onset)
                      .left
                      .map(_.message)
                      .map(p => MediaLocus.Instant(row(2), p))
                  else
                    long(row(4)).flatMap(end =>
                      PlaybackInterval
                        .onTimeAxis(bundle.primaryAxis, onset, end)
                        .left
                        .map(_.message)
                        .map(iv => MediaLocus.Extent(row(2), iv))
                    )
              yield Some(value)
          group <-
            if row(5).isEmpty && row(6).isEmpty then Right(None)
            else
              int(row(5)).flatMap(n =>
                Either.cond(
                  row(6).trim.nonEmpty,
                  Some(TimedSegment.Group(n, row(6))),
                  "group needs label"
                )
              )
          locations = row(7).split("\\|", -1).toVector.filter(_.nonEmpty)
          characters = row(8).split("\\|", -1).toVector.filter(_.nonEmpty)
        yield TimedSegment(
          ordinal,
          row(1),
          locus,
          group,
          (locations ++ characters).flatMap(Lexical.stemSet).toSet,
          locations
        )
      }
      identity = ContentAddress.digest(
        Vector("timed-annotations/v1", Checksum.ofText(csvText).hex, Checksum.ofText(partsText).hex)
      )
      view <- TimedSourceView.of(segments, declared, identity).left.map(_.message)
    yield view

  /** Produces the stable prepared-timed-source/v1 artifact after replay validation. */
  def encode(csv: Array[Byte], descriptor: Array[Byte]): Either[String, String] =
    for
      _ <- prepare(csv, descriptor)
      c <- utf8(csv)
      p <- utf8(descriptor)
    yield Canonical.print(
      Json.obj(
        "schemaVersion" -> Json.fromString("prepared-timed-source/v1"),
        "csv" -> Json.fromString(c),
        "parts" -> Json.fromString(p)
      )
    )

  /** Refuses any prepared artifact whose checked replay cannot be reconstructed exactly. */
  def decode(text: String): Either[String, TimedSourceView] =
    for
      json <- parser.parse(text).left.map(_.message)
      _ <- exact(json, Set("schemaVersion", "csv", "parts"))
      schema <- field[String](json, "schemaVersion")
      _ <- Either.cond(schema == "prepared-timed-source/v1", (), "unsupported timed source")
      csv <- field[String](json, "csv")
      descriptor <- field[String](json, "parts")
      view <- prepare(csv.getBytes(UTF_8), descriptor.getBytes(UTF_8))
    yield view

  /** Strict comma CSV with quoted commas/newlines and doubled quotes. */
  private def records(text: String): Either[String, Vector[Vector[String]]] =
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
          case State.Quoted => ()
        pending = true
      index += 1
    if invalid || state == State.Quoted then Left("InvalidCsv")
    else
      if pending then record()
      Right(out.result())
