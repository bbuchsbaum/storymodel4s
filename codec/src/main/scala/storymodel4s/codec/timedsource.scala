package storymodel4s.codec

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import java.nio.charset.StandardCharsets.UTF_8
import storymodel4s.align.MappingRefusal
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

  /** The second accepted header: the first one plus a collection above the group and a per-row
    * target mark. A file is read under the header it carries and under no other.
    */
  val HeaderV2: Vector[String] = Header ++ Vector("collection", "collection_label", "target")

  /** The only value a `target` cell may hold besides empty: the row is not a target. */
  val NotTarget = "no"

  /** Why annotation bytes were not admitted. `record` counts data rows from 1. */
  enum Refusal:
    /** Bytes, CSV syntax, clocks or parts that fail before any row has a meaning. */
    case Malformed(detail: String)

    /** The first record is neither accepted header, cell for cell. */
    case UnknownHeader

    /** A row whose width is not its own header's, so a cell would be read under another name. */
    case RowWidth(record: Int)

    /** A row cell the nine shared columns cannot read. */
    case InvalidRow(record: Int, detail: String)

    /** A collection needs both an integer ordinal and a label. */
    case InvalidCollection(record: Int)

    /** A `target` cell that is neither empty nor `no`. */
    case InvalidTarget(record: Int, cell: String)

    /** The rows parsed, and the checked source refused them. */
    case Source(refusal: MappingRefusal)

    def message: String = this match
      case Malformed(detail)         => detail
      case UnknownHeader             => "invalid annotation header or row"
      case RowWidth(_)               => "invalid annotation header or row"
      case InvalidRow(_, detail)     => detail
      case InvalidCollection(record) => s"row $record: collection needs an ordinal and a label"
      case InvalidTarget(record, _)  => s"row $record: target must be empty or `$NotTarget`"
      case Source(refusal)           => refusal.message

  private def parse(text: String): Either[String, Json] =
    for
      _ <- MappingJson.uniqueObjectKeys(text).left.map(_.toString)
      json <- Canonical.parse(text).left.map(_.toString)
    yield json

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
      header: Vector[String],
      table: Vector[Vector[String]]
  ): Either[String, Map[String, SourceBundle]] =
    for
      json <- parse(text)
      rows <- json.asArray.map(_.toVector).toRight("parts must be an array")
      entries <- rows.traverse { row =>
        for
          _ <- exact(row, Set("part", "start", "end", "numerator", "denominator"))
          part <- field[String](row, "part")
          partArtifact = Canonical.print(
            Json.obj(
              "part" -> Json.fromString(part),
              "header" -> Json.fromValues(header.map(Json.fromString)),
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
    prepareChecked(csv, descriptor).left.map(_.message)

  /** As `prepare`, saying which rule refused.
    *
    * The header a file carries enters its identity as the adapter tag, so the same rows under the
    * two headers are two artifacts and their node ids differ. Files under the first header are
    * tagged exactly as before the second one existed.
    */
  def prepareChecked(
      csv: Array[Byte],
      descriptor: Array[Byte]
  ): Either[Refusal, TimedSourceView] =
    for
      csvText <- utf8(csv).left.map(Refusal.Malformed(_))
      partsText <- utf8(descriptor).left.map(Refusal.Malformed(_))
      table <- records(csvText.stripPrefix("\ufeff")).left.map(Refusal.Malformed(_))
      layout <- table.headOption match
        case Some(Header)   => Right(Header -> "timed-annotations/v1")
        case Some(HeaderV2) => Right(HeaderV2 -> "timed-annotations/v2")
        case _              => Left(Refusal.UnknownHeader)
      (header, adapter) = layout
      rows = table.drop(1).zipWithIndex.map((row, index) => (row, index + 1))
      _ <- rows.find(_._1.size != header.size).map(r => Refusal.RowWidth(r._2)).toLeft(())
      declared <- parts(partsText, header, table).left.map(Refusal.Malformed(_))
      segments <- rows.traverse { (row, record) =>
        for
          shared <- segment(row, declared).left.map(Refusal.InvalidRow(record, _))
          extended <-
            if header == Header then Right(shared)
            else
              for
                collection <-
                  if row(9).isEmpty && row(10).isEmpty then Right(None)
                  else
                    int(row(9)).toOption
                      .filter(_ => row(10).trim.nonEmpty)
                      .map(n => Some(TimedSegment.Collection(n, row(10))))
                      .toRight(Refusal.InvalidCollection(record))
                target <- row(11) match
                  case ""          => Right(true)
                  case `NotTarget` => Right(false)
                  case other       => Left(Refusal.InvalidTarget(record, other))
              yield shared.copy(collection = collection, target = target)
        yield extended
      }
      identity = ContentAddress.digest(
        Vector(adapter, Checksum.ofText(csvText).hex, Checksum.ofText(partsText).hex)
      )
      view <- TimedSourceView.of(segments, declared, identity).left.map(Refusal.Source(_))
    yield view

  /** The nine columns both headers share. */
  private def segment(
      row: Vector[String],
      declared: Map[String, SourceBundle]
  ): Either[String, TimedSegment] =
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
      json <- parse(text)
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
