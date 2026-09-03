package storymodel4s.codec

import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.syntax.*
import storymodel4s.align.{AlignError, AlignState, AlignmentMatrix, AlignmentRow, SourceNodeRef}
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

import CanonicalPrimitives.*
import CanonicalPrimitives.given
import HsmmResultCodec.given
import OutputCodecs.given
import RecallCodecs.given

/** Canonical codec for a [[RecallVoyageDocument]] (ADR 0002 §14): the proven input and its
  * provenance, never the compiled scene.
  *
  * Why the input and not the marks: a renderer that loads this document compiles the scene itself
  * through `VoyageCompiler`, so the evidence law runs wherever marks are drawn. Decoding goes back
  * through every checked constructor (`Seconds.of`, `ClockSpan.of`, `SourceTimeline.of`,
  * `AlignmentRow.of`, `RecallVoyageInput.of`, `ViewProvenance.of`); a document whose join does not
  * hold is refused with the strand named, not materialised.
  */
object VoyageCodecs:
  val schema: String = "storymodel4s.view.recall-voyage"
  val schemaVersion: Int = 1

  /** Encode a document in the canonical form the pipeline writes. */
  def encode(document: RecallVoyageDocument): String = Canonical.encode(document)

  /** Decode a pipeline-written document, refusing a text whose parsed JSON, canonically printed, is
    * not the canonical rendering of what it decodes to: an unknown key or a non-canonical value
    * would otherwise be accepted silently, and a checksum of the text would name a document the
    * decoder never saw. The comparison is made after parsing, so string escapes and key order are
    * normalised (a page that embeds the document with `<` escaped is the same document), while an
    * extra field or a plain-number value is not. The same guard the surface-atlas and derivation
    * artifacts apply.
    */
  def decode(text: String): Either[CodecError, RecallVoyageDocument] =
    for
      json <- Canonical.parse(text)
      document <- Canonical.decodeJson[RecallVoyageDocument](json)
      _ <- Either.cond(
        Canonical.print(json) == encode(document),
        (),
        CodecError.Decode(
          "$",
          "the text is not the canonical rendering of the document it decodes to " +
            "(an unknown field or a non-canonical value)"
        )
      )
    yield document

  private def fail[A](c: HCursor, message: String): Decoder.Result[A] =
    Left(DecodingFailure(message, c.history))

  private def domain[A](c: HCursor, e: Either[DomainError, A]): Decoder.Result[A] =
    e.left.map(err => DecodingFailure(err.message, c.history))

  private def align[A](c: HCursor, e: Either[AlignError, A]): Decoder.Result[A] =
    e.left.map(err => DecodingFailure(err.toString, c.history))

  given Encoder[Seconds] = doubleEncoder.contramap(_.value)
  given Decoder[Seconds] =
    Decoder.instance(c => doubleDecoder(c).flatMap(v => domain(c, Seconds.of(v))))

  given Encoder[ClockSpan] =
    Encoder.instance(s => Json.obj("start" -> s.start.value.asJson, "end" -> s.end.value.asJson))
  given Decoder[ClockSpan] = Decoder.instance { c =>
    for
      start <- field[Double](c, "start")
      end <- field[Double](c, "end")
      span <- domain(c, ClockSpan.of(start, end))
    yield span
  }

  given Encoder[AnchorOrigin] = enumEncoder {
    case AnchorOrigin.PosteriorArgmax => "posterior_argmax"
    case AnchorOrigin.DecodeBound     => "decode_bound"
    case AnchorOrigin.DecodeFilled    => "decode_filled"
  }
  given Decoder[AnchorOrigin] = Decoder.decodeString.emap {
    case "posterior_argmax" => Right(AnchorOrigin.PosteriorArgmax)
    case "decode_bound"     => Right(AnchorOrigin.DecodeBound)
    case "decode_filled"    => Right(AnchorOrigin.DecodeFilled)
    case other              => Left(s"unknown AnchorOrigin $other")
  }

  given Encoder[SourceTimelineNode] = Encoder.instance(n =>
    obj(
      "ref" -> n.ref.asJson,
      "level" -> n.level.asJson,
      "group" -> n.group.asJson,
      "span" -> n.span.asJson,
      "label" -> n.label.asJson
    )
  )
  given Decoder[SourceTimelineNode] = Decoder.instance { c =>
    for
      ref <- field[SourceNodeRef](c, "ref")
      level <- field[Int](c, "level")
      group <- c.downField("group").as[Option[Int]]
      span <- field[ClockSpan](c, "span")
      label <- field[String](c, "label")
    yield SourceTimelineNode(ref, level, group, span, label)
  }

  given Encoder[SourceTimelineGroup] = Encoder.instance(g =>
    Json.obj("ordinal" -> g.ordinal.asJson, "label" -> g.label.asJson, "span" -> g.span.asJson)
  )
  given Decoder[SourceTimelineGroup] = Decoder.instance { c =>
    for
      ordinal <- field[Int](c, "ordinal")
      label <- field[String](c, "label")
      span <- field[ClockSpan](c, "span")
    yield SourceTimelineGroup(ordinal, label, span)
  }

  given Encoder[SourceTimeline] =
    Encoder.instance(t => Json.obj("nodes" -> t.nodes.asJson, "groups" -> t.groups.asJson))
  given Decoder[SourceTimeline] = Decoder.instance { c =>
    for
      nodes <- field[Vector[SourceTimelineNode]](c, "nodes")
      groups <- field[Vector[SourceTimelineGroup]](c, "groups")
      timeline <- domain(c, SourceTimeline.of(nodes, groups))
    yield timeline
  }

  given Encoder[VoyageUnit] = Encoder.instance(u =>
    obj(
      "id" -> u.id.asJson,
      "ordinal" -> u.ordinal.asJson,
      "text" -> u.text.asJson,
      "onset" -> u.onset.asJson,
      "lastWordOnset" -> u.lastWordOnset.asJson
    )
  )
  given Decoder[VoyageUnit] = Decoder.instance { c =>
    for
      id <- field[RecallUnitId](c, "id")
      ordinal <- field[Int](c, "ordinal")
      text <- field[String](c, "text")
      onset <- c.downField("onset").as[Option[Seconds]]
      last <- c.downField("lastWordOnset").as[Option[Seconds]]
    yield VoyageUnit(id, ordinal, text, onset, last)
  }

  given Encoder[VoyageDecision] = Encoder.instance(d =>
    obj(
      "unit" -> d.unit.asJson,
      "anchor" -> d.anchor.asJson,
      "group" -> d.group.asJson,
      "origin" -> d.origin.asJson
    )
  )
  given Decoder[VoyageDecision] = Decoder.instance { c =>
    for
      unit <- field[RecallUnitId](c, "unit")
      anchor <- c.downField("anchor").as[Option[SourceNodeRef]]
      group <- c.downField("group").as[Option[Int]]
      origin <- field[AnchorOrigin](c, "origin")
    yield VoyageDecision(unit, anchor, group, origin)
  }

  given Encoder[CodedInterval] =
    Encoder.instance(i => Json.obj("recall" -> i.recall.asJson, "group" -> i.group.asJson))
  given Decoder[CodedInterval] = Decoder.instance { c =>
    for
      recall <- field[ClockSpan](c, "recall")
      group <- field[Int](c, "group")
    yield CodedInterval(recall, group)
  }

  given Encoder[IndependentCoding] = Encoder.instance(k =>
    Json.obj(
      "name" -> k.name.asJson,
      "checksum" -> k.checksum.asJson,
      "intervals" -> k.intervals.asJson
    )
  )
  given Decoder[IndependentCoding] = Decoder.instance { c =>
    for
      name <- field[String](c, "name")
      checksum <- field[Checksum](c, "checksum")
      intervals <- field[Vector[CodedInterval]](c, "intervals")
    yield IndependentCoding(name, checksum, intervals)
  }

  /** One posterior row on the wire: the unit and its states with mass, states in key order. */
  private def rowJson(row: AlignmentRow): Json =
    Json.obj(
      "unit" -> row.unit.asJson,
      "mass" -> row.mass.toVector
        .sortBy(_._1.key)
        .map { case (state, m) => Json.obj("state" -> state.asJson, "mass" -> m.asJson) }
        .asJson
    )

  private def rowFromJson(c: HCursor): Decoder.Result[AlignmentRow] =
    for
      unit <- field[RecallUnitId](c, "unit")
      entries <- field[Vector[Json]](c, "mass")
      pairs <- entries.foldLeft[Decoder.Result[Vector[(AlignState, Double)]]](Right(Vector.empty)) {
        (acc, entry) =>
          for
            got <- acc
            state <- entry.hcursor.downField("state").as[AlignState]
            m <- entry.hcursor.downField("mass").as[Double]
          yield got :+ (state -> m)
      }
      _ <- Either.cond(
        pairs.map(_._1).distinct.size == pairs.size,
        (),
        DecodingFailure("AlignmentRow.mass repeats a state", c.history)
      )
      row <- align(c, AlignmentRow.of(unit, pairs.toMap))
    yield row

  given Encoder[ViewProvenance] = Encoder.instance(p =>
    obj(
      "sourceChecksum" -> p.sourceChecksum.asJson,
      "modelReceiptChecksum" -> p.modelReceiptChecksum.asJson,
      "basis" -> p.basis.asJson,
      "compilerVersion" -> p.compilerVersion.asJson,
      "configChecksum" -> p.configChecksum.asJson
    )
  )

  /** Provenance without a draft promotion: a voyage never draws a draft model. */
  given Decoder[ViewProvenance] = Decoder.instance { c =>
    for
      source <- field[Checksum](c, "sourceChecksum")
      receipt <- c.downField("modelReceiptChecksum").as[Option[Checksum]]
      basis <- field[ViewBasis](c, "basis")
      version <- field[String](c, "compilerVersion")
      config <- field[Checksum](c, "configChecksum")
      p <- domain(c, ViewProvenance.of(source, receipt, basis, version, config))
    yield p
  }

  given Encoder[RecallVoyageDocument] = Encoder.instance { d =>
    val in = d.input
    obj(
      "schema" -> schema.asJson,
      "schemaVersion" -> schemaVersion.asJson,
      "provenance" -> d.provenance.asJson,
      "recallLength" -> in.recallLength.asJson,
      "units" -> in.units.asJson,
      "rows" -> in.matrix.rows.map(rowJson).asJson,
      "timeline" -> in.timeline.asJson,
      "decisions" -> in.decisions.asJson,
      "coding" -> in.coding.asJson
    )
  }

  given Decoder[RecallVoyageDocument] = Decoder.instance { c =>
    for
      s <- field[String](c, "schema")
      _ <- if s == schema then Right(()) else fail(c, s"unknown schema $s")
      v <- field[Int](c, "schemaVersion")
      _ <- if v == schemaVersion then Right(()) else fail(c, s"unsupported schemaVersion $v")
      provenance <- field[ViewProvenance](c, "provenance")
      recallLength <- field[Seconds](c, "recallLength")
      units <- field[Vector[VoyageUnit]](c, "units")
      rowJsons <- field[Vector[Json]](c, "rows")
      rows <- rowJsons.foldLeft[Decoder.Result[Vector[AlignmentRow]]](Right(Vector.empty)) {
        (acc, j) => acc.flatMap(got => rowFromJson(j.hcursor).map(got :+ _))
      }
      matrix <- align(c, AlignmentMatrix.of(rows))
      timeline <- field[SourceTimeline](c, "timeline")
      decisions <- field[Vector[VoyageDecision]](c, "decisions")
      coding <- c.downField("coding").as[Option[IndependentCoding]]
      input <- domain(
        c,
        RecallVoyageInput.of(units, matrix, timeline, decisions, coding, recallLength)
      )
    yield RecallVoyageDocument(input, provenance)
  }
