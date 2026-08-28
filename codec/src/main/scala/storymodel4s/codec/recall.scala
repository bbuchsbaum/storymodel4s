package storymodel4s.codec

import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.syntax.*
import storymodel4s.core.*
import storymodel4s.recall.*
import CanonicalPrimitives.{*, given}
import CoreCodecs.given

/** Codecs for `storymodel4s.recall`: the recall graph with its transcript source, units (including
  * the structured participant fields), and relations (including coreference).
  */
object RecallCodecs:
  given Encoder[RecallUnitId] = opaqueEncoder(RecallUnitId)
  given Decoder[RecallUnitId] = opaqueDecoder(RecallUnitId)
  given Encoder[RecallEntityId] = opaqueEncoder(RecallEntityId)
  given Decoder[RecallEntityId] = opaqueDecoder(RecallEntityId)

  given Encoder[DiscourseFunction] = enumEncoder(_.toString)
  given Decoder[DiscourseFunction] =
    enumDecoder("DiscourseFunction", DiscourseFunction.values, _.toString)

  given Encoder[ExpressedUncertainty] = Encoder.instance {
    case ExpressedUncertainty.Unmarked     => "Unmarked".asJson
    case ExpressedUncertainty.Hedged(cues) =>
      Json.obj("type" -> "Hedged".asJson, "cues" -> cues.asJson)
    case ExpressedUncertainty.Explicit(cues) =>
      Json.obj("type" -> "Explicit".asJson, "cues" -> cues.asJson)
  }
  given Decoder[ExpressedUncertainty] = Decoder.instance { c =>
    c.value.asString match
      case Some("Unmarked") => Right(ExpressedUncertainty.Unmarked)
      case Some(o)          => Left(DecodingFailure(s"unknown ExpressedUncertainty $o", c.history))
      case None             =>
        field[String](c, "type").flatMap {
          case "Hedged"   => field[SpanSet](c, "cues").map(ExpressedUncertainty.Hedged.apply)
          case "Explicit" => field[SpanSet](c, "cues").map(ExpressedUncertainty.Explicit.apply)
          case o          => Left(DecodingFailure(s"unknown ExpressedUncertainty $o", c.history))
        }
  }

  given Encoder[PolarityTag] = enumEncoder(_.toString)
  given Decoder[PolarityTag] = enumDecoder("PolarityTag", PolarityTag.values, _.toString)
  given Encoder[ModalityTag] = enumEncoder(_.toString)
  given Decoder[ModalityTag] = enumDecoder("ModalityTag", ModalityTag.values, _.toString)

  given Encoder[SketchRole] = Encoder.instance {
    case SketchRole.Other(l) => Json.obj("type" -> "Other".asJson, "label" -> l.asJson)
    case r                   => r.toString.asJson
  }
  given Decoder[SketchRole] = Decoder.instance { c =>
    c.value.asString match
      case Some(s) =>
        Vector(
          SketchRole.Agent,
          SketchRole.Patient,
          SketchRole.Theme,
          SketchRole.Experiencer,
          SketchRole.Location,
          SketchRole.Destination,
          SketchRole.Source,
          SketchRole.Instrument,
          SketchRole.Time,
          SketchRole.Beneficiary
        )
          .find(v => v.toString == s)
          .toRight(DecodingFailure(s"unknown SketchRole $s", c.history))
      case None => field[String](c, "label").map(SketchRole.Other.apply)
  }

  given Encoder[Determiner] = enumEncoder(_.toString)
  given Decoder[Determiner] = enumDecoder("Determiner", Determiner.values, _.toString)
  given Encoder[MentionNumber] = enumEncoder(_.toString)
  given Decoder[MentionNumber] = enumDecoder("MentionNumber", MentionNumber.values, _.toString)

  given Encoder[SketchParticipant] = Encoder.instance { p =>
    obj(
      "role" -> p.role.asJson,
      "entity" -> opt(p.entity),
      "label" -> p.label.asJson,
      "specified" -> p.specified.asJson,
      "aliases" -> sortedSetEncoder[String](p.aliases),
      "head" -> p.head.asJson,
      "modifiers" -> p.modifiers.asJson,
      "determiner" -> opt(p.determiner),
      "number" -> opt(p.number)
    )
  }
  given Decoder[SketchParticipant] = Decoder.instance { c =>
    for
      r <- field[SketchRole](c, "role")
      e <- field[Option[RecallEntityId]](c, "entity")
      l <- field[String](c, "label")
      sp <- field[Boolean](c, "specified")
      al <- field[Vector[String]](c, "aliases")
      h <- field[String](c, "head")
      mo <- field[Vector[String]](c, "modifiers")
      d <- field[Option[Determiner]](c, "determiner")
      n <- field[Option[MentionNumber]](c, "number")
    yield SketchParticipant(r, e, l, sp, al.toSet, h, mo, d, n)
  }

  given Encoder[PropositionSketch] = Encoder.instance { s =>
    obj(
      "predicate" -> opt(s.predicate),
      "participants" -> s.participants.asJson,
      "polarity" -> s.polarity.asJson,
      "modality" -> s.modality.asJson,
      "locations" -> s.locations.asJson,
      "times" -> s.times.asJson,
      "sensoryTerms" -> s.sensoryTerms.asJson,
      "lemmas" -> sortedSetEncoder[String](s.lemmas),
      "outcome" -> opt(s.outcome),
      "cause" -> opt(s.cause)
    )
  }
  given Decoder[PropositionSketch] = Decoder.instance { c =>
    for
      p <- field[Option[String]](c, "predicate")
      ps <- field[Vector[SketchParticipant]](c, "participants")
      po <- field[PolarityTag](c, "polarity")
      mo <- field[ModalityTag](c, "modality")
      lo <- field[Vector[String]](c, "locations")
      ti <- field[Vector[String]](c, "times")
      se <- field[Vector[String]](c, "sensoryTerms")
      le <- field[Vector[String]](c, "lemmas")
      ou <- field[Option[String]](c, "outcome")
      ca <- field[Option[String]](c, "cause")
    yield PropositionSketch(p, ps, po, mo, lo, ti, se, le.toSet, ou, ca)
  }

  given Encoder[RecallUnit] = Encoder.instance { u =>
    obj(
      "id" -> u.id.asJson,
      "ordinal" -> u.ordinal.asJson,
      "span" -> u.span.asJson,
      "text" -> u.text.asJson,
      "function" -> u.function.asJson,
      "expressedUncertainty" -> u.expressedUncertainty.asJson,
      "proposition" -> u.proposition.asJson,
      "grounding" -> opt(u.grounding)
    )
  }
  given Decoder[RecallUnit] = Decoder.instance { c =>
    for
      id <- field[RecallUnitId](c, "id")
      o <- field[Int](c, "ordinal")
      s <- field[SpanSet](c, "span")
      t <- field[String](c, "text")
      f <- field[DiscourseFunction](c, "function")
      eu <- field[ExpressedUncertainty](c, "expressedUncertainty")
      p <- field[PropositionSketch](c, "proposition")
      g <- field[Option[Probability]](c, "grounding")
    yield RecallUnit(id, o, s, t, f, eu, p, g)
  }

  given Encoder[RecallTemporalRelation] = enumEncoder(_.toString)
  given Decoder[RecallTemporalRelation] =
    enumDecoder("RecallTemporalRelation", RecallTemporalRelation.values, _.toString)

  given Encoder[RecallTemporalEdge] = Encoder.instance { e =>
    obj(
      "from" -> e.from.asJson,
      "relation" -> e.relation.asJson,
      "to" -> e.to.asJson,
      "cue" -> opt(e.cue)
    )
  }
  given Decoder[RecallTemporalEdge] = Decoder.instance { c =>
    for
      f <- field[RecallUnitId](c, "from")
      r <- field[RecallTemporalRelation](c, "relation")
      t <- field[RecallUnitId](c, "to")
      cue <- field[Option[TextSpan]](c, "cue")
    yield RecallTemporalEdge(f, r, t, cue)
  }

  given Encoder[RecallCausalEdge] = Encoder.instance(e =>
    obj("cause" -> e.cause.asJson, "effect" -> e.effect.asJson, "cue" -> opt(e.cue))
  )
  given Decoder[RecallCausalEdge] = Decoder.instance { c =>
    for
      a <- field[RecallUnitId](c, "cause")
      b <- field[RecallUnitId](c, "effect")
      cue <- field[Option[TextSpan]](c, "cue")
    yield RecallCausalEdge(a, b, cue)
  }

  given Encoder[RecallEntity] = Encoder.instance(e =>
    Json.obj("id" -> e.id.asJson, "label" -> e.label.asJson, "mentions" -> e.mentions.asJson)
  )
  given Decoder[RecallEntity] = Decoder.instance { c =>
    for
      id <- field[RecallEntityId](c, "id")
      l <- field[String](c, "label")
      m <- field[Vector[TextSpan]](c, "mentions")
    yield RecallEntity(id, l, m)
  }

  given Encoder[ElaborationEdge] =
    Encoder.instance(e => Json.obj("parent" -> e.parent.asJson, "child" -> e.child.asJson))
  given Decoder[ElaborationEdge] = Decoder.instance { c =>
    for
      p <- field[RecallUnitId](c, "parent")
      ch <- field[RecallUnitId](c, "child")
    yield ElaborationEdge(p, ch)
  }

  given Encoder[RecallCorefKind] = enumEncoder(_.toString)
  given Decoder[RecallCorefKind] =
    enumDecoder("RecallCorefKind", RecallCorefKind.values, _.toString)

  given Encoder[RecallCorefLink] = Encoder.instance { l =>
    Json.obj(
      "anaphor" -> l.anaphor.asJson,
      "antecedent" -> l.antecedent.asJson,
      "kind" -> l.kind.asJson
    )
  }
  given Decoder[RecallCorefLink] = Decoder.instance { c =>
    for
      a <- field[TextSpan](c, "anaphor")
      e <- field[RecallEntityId](c, "antecedent")
      k <- field[RecallCorefKind](c, "kind")
    yield RecallCorefLink(a, e, k)
  }

  given Encoder[RecallRelations] = Encoder.instance { r =>
    Json.obj(
      "temporal" -> r.temporal.asJson,
      "causal" -> r.causal.asJson,
      "entities" -> r.entities.asJson,
      "elaboration" -> r.elaboration.asJson,
      "coreference" -> r.coreference.asJson
    )
  }
  given Decoder[RecallRelations] = Decoder.instance { c =>
    for
      t <- field[Vector[RecallTemporalEdge]](c, "temporal")
      ca <- field[Vector[RecallCausalEdge]](c, "causal")
      e <- field[Vector[RecallEntity]](c, "entities")
      el <- field[Vector[ElaborationEdge]](c, "elaboration")
      co <- field[Vector[RecallCorefLink]](c, "coreference")
    yield RecallRelations(t, ca, e, el, co)
  }

  /** Transcripts are encoded as plain `StorySource` until ADR 0001 rev 3 D6 lands the
    * `PseudonymizedText` / `ReidentificationKey` split; a reidentification key is never encoded.
    */
  given Encoder[RecallGraph] = Encoder.instance { g =>
    Json.obj(
      "schemaVersion" -> SchemaVersions.Current.asJson,
      "transcript" -> g.transcript.asJson,
      "atlas" -> CoreCodecs.atlasUnitsEncoder(g.atlas),
      "units" -> g.units.asJson,
      "relations" -> g.relations.asJson
    )
  }
  given Decoder[RecallGraph] = Decoder.instance { c =>
    for
      _ <- SchemaVersions.check(c)
      t <- field[StorySource](c, "transcript")
      a <- c
        .downField("atlas")
        .success
        .toRight(DecodingFailure("missing atlas", c.history))
        .flatMap(ac => CoreCodecs.decodeAtlasUnits(ac, t))
      u <- field[Vector[RecallUnit]](c, "units")
      r <- field[RecallRelations](c, "relations")
      g <- RecallGraph
        .validated(RecallGraph(t, a, u, r))
        .toEither
        .left
        .map(es => DecodingFailure(es.toChain.toVector.map(_.message).mkString("; "), c.history))
    yield g
  }
