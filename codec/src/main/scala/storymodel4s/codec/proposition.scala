package storymodel4s.codec

import cats.data.NonEmptySet
import io.circe.{Decoder, DecodingFailure, Encoder, Json, KeyDecoder, KeyEncoder}
import io.circe.syntax.*
import scala.collection.immutable.SortedSet
import storymodel4s.core.*
import storymodel4s.proposition.*
import CanonicalPrimitives.{*, given}
import CoreCodecs.given

/** Codecs for `storymodel4s.proposition`. A chart decodes to `Unchecked` and is validated before it
  * is returned as `Checked`; the wire never grants check status.
  */
object PropositionCodecs:
  given Encoder[ConceptId] = opaqueEncoder(ConceptId)
  given Decoder[ConceptId] = opaqueDecoder(ConceptId)
  given KeyEncoder[ConceptId] = opaqueKeyEncoder(ConceptId)
  given KeyDecoder[ConceptId] = opaqueKeyDecoder(ConceptId)
  given Encoder[Lemma] = opaqueEncoder(Lemma)
  given Decoder[Lemma] = opaqueDecoder(Lemma)

  given Encoder[FrameRef] = Encoder.instance { f =>
    obj(
      "namespace" -> f.namespace.asJson,
      "id" -> f.id.asJson,
      "senseCredence" -> opt(f.senseCredence)
    )
  }
  given Decoder[FrameRef] = Decoder.instance { c =>
    for
      ns <- field[String](c, "namespace")
      id <- field[String](c, "id")
      sc <- field[Option[Credence]](c, "senseCredence")
    yield FrameRef(ns, id, sc)
  }

  given Encoder[ConceptKind] = enumEncoder(_.toString)
  given Decoder[ConceptKind] = enumDecoder("ConceptKind", ConceptKind.values, _.toString)

  given Encoder[Concept] = Encoder.instance { k =>
    obj(
      "lemma" -> k.lemma.asJson,
      "gloss" -> opt(k.gloss),
      "frame" -> opt(k.frame),
      "kind" -> k.kind.asJson
    )
  }
  given Decoder[Concept] = Decoder.instance { c =>
    for
      l <- field[Lemma](c, "lemma")
      g <- field[Option[String]](c, "gloss")
      f <- field[Option[FrameRef]](c, "frame")
      k <- field[ConceptKind](c, "kind")
    yield Concept(l, g, f, k)
  }

  given Encoder[ParticipantRole] = Encoder.instance {
    case ParticipantRole.Custom(ns, l) =>
      Json.obj("type" -> "Custom".asJson, "namespace" -> ns.asJson, "label" -> l.asJson)
    case r => r.toString.asJson
  }
  given Decoder[ParticipantRole] = Decoder.instance { c =>
    c.value.asString match
      case Some(s) =>
        Vector(
          ParticipantRole.Agent,
          ParticipantRole.Patient,
          ParticipantRole.Theme,
          ParticipantRole.Experiencer,
          ParticipantRole.Stimulus,
          ParticipantRole.Instrument,
          ParticipantRole.Beneficiary,
          ParticipantRole.Source,
          ParticipantRole.Destination,
          ParticipantRole.Location,
          ParticipantRole.Time,
          ParticipantRole.Manner,
          ParticipantRole.Cause,
          ParticipantRole.Result
        )
          .find(v => v.toString == s)
          .toRight(DecodingFailure(s"unknown ParticipantRole $s", c.history))
      case None =>
        for
          ns <- field[String](c, "namespace")
          l <- field[String](c, "label")
        yield ParticipantRole.Custom(ns, l)
  }

  given Encoder[SourceRole] = Encoder.instance {
    case SourceRole.Numbered(i)      => Json.obj("type" -> "Numbered".asJson, "index" -> i.asJson)
    case SourceRole.Named(n)         => Json.obj("type" -> "Named".asJson, "name" -> n.asJson)
    case SourceRole.Operand(i)       => Json.obj("type" -> "Operand".asJson, "index" -> i.asJson)
    case SourceRole.Extension(ns, n) =>
      Json.obj("type" -> "Extension".asJson, "namespace" -> ns.asJson, "name" -> n.asJson)
  }
  given Decoder[SourceRole] = Decoder.instance { c =>
    field[String](c, "type").flatMap {
      case "Numbered"  => field[Int](c, "index").map(SourceRole.Numbered.apply)
      case "Named"     => field[String](c, "name").map(SourceRole.Named.apply)
      case "Operand"   => field[Int](c, "index").map(SourceRole.Operand.apply)
      case "Extension" =>
        for
          ns <- field[String](c, "namespace")
          n <- field[String](c, "name")
        yield SourceRole.Extension(ns, n)
      case o => Left(DecodingFailure(s"unknown SourceRole $o", c.history))
    }
  }

  given Encoder[RoleAssignment] = Encoder.instance { r =>
    obj(
      "source" -> r.source.asJson,
      "normalized" -> opt(
        r.normalized.map((p, cr) => Json.obj("role" -> p.asJson, "credence" -> cr.asJson))
      )
    )
  }
  given Decoder[RoleAssignment] = Decoder.instance { c =>
    val norm: Decoder[(ParticipantRole, Credence)] = Decoder.instance { nc =>
      for
        p <- nc.downField("role").as[ParticipantRole]
        cr <- nc.downField("credence").as[Credence]
      yield (p, cr)
    }
    for
      s <- field[SourceRole](c, "source")
      n <- c
        .downField("normalized")
        .as[Option[(ParticipantRole, Credence)]](using Decoder.decodeOption(using norm))
    yield RoleAssignment(s, n)
  }

  given Encoder[LiteralValue] = Encoder.instance {
    case LiteralValue.Text(v)   => Json.obj("type" -> "Text".asJson, "value" -> v.asJson)
    case LiteralValue.Number(v) => Json.obj("type" -> "Number".asJson, "value" -> v.asJson)
    case LiteralValue.Symbol(v) => Json.obj("type" -> "Symbol".asJson, "value" -> v.asJson)
  }
  given Decoder[LiteralValue] = Decoder.instance { c =>
    field[String](c, "type").flatMap {
      case "Text"   => field[String](c, "value").map(LiteralValue.Text.apply)
      case "Number" => field[BigDecimal](c, "value").map(LiteralValue.Number.apply)
      case "Symbol" => field[String](c, "value").map(LiteralValue.Symbol.apply)
      case o        => Left(DecodingFailure(s"unknown LiteralValue $o", c.history))
    }
  }

  given Encoder[ConceptTarget] = Encoder.instance {
    case ConceptTarget.Node(id)   => Json.obj("type" -> "Node".asJson, "id" -> id.asJson)
    case ConceptTarget.Literal(v) => Json.obj("type" -> "Literal".asJson, "value" -> v.asJson)
    case ConceptTarget.Unknown    => "Unknown".asJson
  }
  given Decoder[ConceptTarget] = Decoder.instance { c =>
    c.value.asString match
      case Some("Unknown") => Right(ConceptTarget.Unknown)
      case Some(o)         => Left(DecodingFailure(s"unknown ConceptTarget $o", c.history))
      case None            =>
        field[String](c, "type").flatMap {
          case "Node"    => field[ConceptId](c, "id").map(ConceptTarget.Node.apply)
          case "Literal" => field[LiteralValue](c, "value").map(ConceptTarget.Literal.apply)
          case o         => Left(DecodingFailure(s"unknown ConceptTarget $o", c.history))
        }
  }

  given Encoder[PropositionRelation] = Encoder.instance(r =>
    Json.obj("from" -> r.from.asJson, "role" -> r.role.asJson, "to" -> r.to.asJson)
  )
  given Decoder[PropositionRelation] = Decoder.instance { c =>
    for
      f <- field[ConceptId](c, "from")
      r <- field[RoleAssignment](c, "role")
      t <- field[ConceptTarget](c, "to")
    yield PropositionRelation(f, r, t)
  }

  given Encoder[Polarity] = enumEncoder(_.toString)
  given Decoder[Polarity] = enumDecoder("Polarity", Polarity.values, _.toString)
  given Encoder[EmbeddingKind] = enumEncoder(_.toString)
  given Decoder[EmbeddingKind] = enumDecoder("EmbeddingKind", EmbeddingKind.values, _.toString)

  given Encoder[EmbeddedProposition] = Encoder.instance(e =>
    Json.obj(
      "container" -> e.container.asJson,
      "kind" -> e.kind.asJson,
      "content" -> e.content.asJson
    )
  )
  given Decoder[EmbeddedProposition] = Decoder.instance { c =>
    for
      ct <- field[ConceptId](c, "container")
      k <- field[EmbeddingKind](c, "kind")
      cn <- field[ConceptId](c, "content")
    yield EmbeddedProposition(ct, k, cn)
  }

  given Encoder[AlignmentTarget] = Encoder.instance {
    case AlignmentTarget.Concepts(ids) =>
      Json.obj("type" -> "Concepts".asJson, "ids" -> ids.toSortedSet.toVector.asJson)
    case AlignmentTarget.Relation(r) =>
      Json.obj("type" -> "Relation".asJson, "relation" -> r.asJson)
  }
  given Decoder[AlignmentTarget] = Decoder.instance { c =>
    field[String](c, "type").flatMap {
      case "Concepts" =>
        field[Vector[ConceptId]](c, "ids").flatMap { ids =>
          NonEmptySet
            .fromSet(SortedSet.from(ids)(using summon[cats.Order[ConceptId]].toOrdering))
            .map(AlignmentTarget.Concepts.apply)
            .toRight(DecodingFailure("alignment target needs a concept", c.history))
        }
      case "Relation" =>
        field[PropositionRelation](c, "relation").map(AlignmentTarget.Relation.apply)
      case o => Left(DecodingFailure(s"unknown AlignmentTarget $o", c.history))
    }
  }

  given Encoder[PropositionAlignment] = Encoder.instance { a =>
    Json.obj(
      "target" -> a.target.asJson,
      "spans" -> a.spans.asJson,
      "credence" -> a.credence.asJson,
      "meta" -> a.meta.asJson
    )
  }
  given Decoder[PropositionAlignment] = Decoder.instance { c =>
    for
      t <- field[AlignmentTarget](c, "target")
      s <- field[SpanSet](c, "spans")
      cr <- field[Credence](c, "credence")
      m <- field[ClaimMeta](c, "meta")
    yield PropositionAlignment(t, s, cr, m)
  }

  given Encoder[ChartOrigin] = Encoder.instance {
    case ChartOrigin.Hand      => "Hand".asJson
    case ChartOrigin.Resolved  => "Resolved".asJson
    case ChartOrigin.Parser(f) => Json.obj("type" -> "Parser".asJson, "fingerprint" -> f.asJson)
    case ChartOrigin.Agent(f)  => Json.obj("type" -> "Agent".asJson, "fingerprint" -> f.asJson)
    case ChartOrigin.Converted(from, f) =>
      Json.obj("type" -> "Converted".asJson, "from" -> from.asJson, "fingerprint" -> f.asJson)
  }
  given Decoder[ChartOrigin] = Decoder.instance { c =>
    c.value.asString match
      case Some("Hand")     => Right(ChartOrigin.Hand)
      case Some("Resolved") => Right(ChartOrigin.Resolved)
      case Some(o)          => Left(DecodingFailure(s"unknown ChartOrigin $o", c.history))
      case None             =>
        field[String](c, "type").flatMap {
          case "Parser"    => field[Fingerprint](c, "fingerprint").map(ChartOrigin.Parser.apply)
          case "Agent"     => field[Fingerprint](c, "fingerprint").map(ChartOrigin.Agent.apply)
          case "Converted" =>
            for
              from <- field[String](c, "from")
              f <- field[Fingerprint](c, "fingerprint")
            yield ChartOrigin.Converted(from, f)
          case o => Left(DecodingFailure(s"unknown ChartOrigin $o", c.history))
        }
  }

  given Encoder[ChartProvenance] = Encoder.instance { p =>
    Json.obj(
      "origin" -> p.origin.asJson,
      "receipts" -> p.receipts.asJson,
      "alternatives" -> p.alternatives
        .map((h, cr) => Json.obj("checksum" -> h.asJson, "credence" -> cr.asJson))
        .asJson
    )
  }
  given Decoder[ChartProvenance] = Decoder.instance { c =>
    val alt: Decoder[(Checksum, Credence)] = Decoder.instance { ac =>
      for
        h <- ac.downField("checksum").as[Checksum]
        cr <- ac.downField("credence").as[Credence]
      yield (h, cr)
    }
    for
      o <- field[ChartOrigin](c, "origin")
      r <- field[Vector[ProviderCall]](c, "receipts")
      a <- c
        .downField("alternatives")
        .as[Vector[(Checksum, Credence)]](using Decoder.decodeVector(using alt))
    yield ChartProvenance(o, r, a)
  }

  /** Charts are encoded from any check state and decoded as `Checked` only after validation. */
  given chartEncoder[C <: CheckState]: Encoder[PropositionChart[C]] = Encoder.instance { ch =>
    obj(
      "schemaVersion" -> SchemaVersions.Current.asJson,
      "focus" -> opt(ch.focus),
      "concepts" -> ch.concepts.asJson,
      "relations" -> ch.relations.asJson,
      "polarity" -> ch.polarity.asJson,
      "embedded" -> ch.embedded.asJson,
      "alignments" -> ch.alignments.asJson,
      "provenance" -> ch.provenance.asJson,
      "sentence" -> opt(ch.sentence)
    )
  }

  given uncheckedChartDecoder: Decoder[PropositionChart[Unchecked]] = Decoder.instance { c =>
    for
      _ <- SchemaVersions.check(c)
      f <- field[Option[ConceptId]](c, "focus")
      ks <- field[Map[ConceptId, Concept]](c, "concepts")
      rs <- field[Vector[PropositionRelation]](c, "relations")
      po <- field[Map[ConceptId, Polarity]](c, "polarity")
      em <- field[Vector[EmbeddedProposition]](c, "embedded")
      al <- field[Vector[PropositionAlignment]](c, "alignments")
      pv <- field[ChartProvenance](c, "provenance")
      se <- field[Option[SurfaceUnitId]](c, "sentence")
    yield PropositionChart.unchecked(f, ks, rs, po, em, al, pv, se)
  }

  given checkedChartDecoder: Decoder[PropositionChart[Checked]] =
    uncheckedChartDecoder.emap(ch =>
      ChartValidator.check(ch).left.map(vs => vs.map(_.message).mkString("; "))
    )

  /** Evidence carries its own provenance beside the chart's.
    *
    * Why both are written: `PropositionEvidence.hand` sets `ChartProvenance.hand` while the chart
    * keeps the origin it was built with, so the outer provenance is not recoverable from the inner
    * one. Encoding only the chart would silently relabel hand-authored evidence as whatever the
    * chart claims, which is the distinction ADR 0001 rev 3 §D4b exists to preserve.
    */
  given Encoder[PropositionEvidence] = Encoder.instance { e =>
    obj(
      "chart" -> e.chart.asJson,
      "provenance" -> e.provenance.asJson
    )
  }
  given Decoder[PropositionEvidence] = Decoder.instance { c =>
    for
      ch <- field[PropositionChart[Checked]](c, "chart")
      pv <- field[ChartProvenance](c, "provenance")
    yield PropositionEvidence(ch, pv)
  }
