package storymodel4s.codec

import cats.data.NonEmptyVector
import io.circe.{Decoder, DecodingFailure, Encoder, Json, KeyDecoder, KeyEncoder}
import io.circe.syntax.*
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind
import CanonicalPrimitives.{*, given}

/** Codecs for `storymodel4s.core`. Every private-constructor type is decoded through its smart
  * constructor, so a decoded value satisfies the same invariants as a constructed one.
  */
object CoreCodecs:
  // ---- identifiers ----------------------------------------------------------------------
  given Encoder[StoryId] = opaqueEncoder(StoryId)
  given Decoder[StoryId] = opaqueDecoder(StoryId)
  given Encoder[SurfaceUnitId] = opaqueEncoder(SurfaceUnitId)
  given Decoder[SurfaceUnitId] = opaqueDecoder(SurfaceUnitId)
  given KeyEncoder[SurfaceUnitId] = opaqueKeyEncoder(SurfaceUnitId)
  given KeyDecoder[SurfaceUnitId] = opaqueKeyDecoder(SurfaceUnitId)
  given Encoder[EntityId] = opaqueEncoder(EntityId)
  given Decoder[EntityId] = opaqueDecoder(EntityId)
  given KeyEncoder[EntityId] = opaqueKeyEncoder(EntityId)
  given KeyDecoder[EntityId] = opaqueKeyDecoder(EntityId)
  given Encoder[SituationId] = opaqueEncoder(SituationId)
  given Decoder[SituationId] = opaqueDecoder(SituationId)
  given KeyEncoder[SituationId] = opaqueKeyEncoder(SituationId)
  given KeyDecoder[SituationId] = opaqueKeyDecoder(SituationId)
  given Encoder[SegmentId] = opaqueEncoder(SegmentId)
  given Decoder[SegmentId] = opaqueDecoder(SegmentId)
  given KeyEncoder[SegmentId] = opaqueKeyEncoder(SegmentId)
  given KeyDecoder[SegmentId] = opaqueKeyDecoder(SegmentId)
  given Encoder[ContextId] = opaqueEncoder(ContextId)
  given Decoder[ContextId] = opaqueDecoder(ContextId)
  given KeyEncoder[ContextId] = opaqueKeyEncoder(ContextId)
  given KeyDecoder[ContextId] = opaqueKeyDecoder(ContextId)
  given Encoder[ClaimId] = opaqueEncoder(ClaimId)
  given Decoder[ClaimId] = opaqueDecoder(ClaimId)
  given Encoder[EvidenceId] = opaqueEncoder(EvidenceId)
  given Decoder[EvidenceId] = opaqueDecoder(EvidenceId)
  given Encoder[FeatureSpaceId] = opaqueEncoder(FeatureSpaceId)
  given Decoder[FeatureSpaceId] = opaqueDecoder(FeatureSpaceId)
  given KeyEncoder[FeatureSpaceId] = opaqueKeyEncoder(FeatureSpaceId)
  given KeyDecoder[FeatureSpaceId] = opaqueKeyDecoder(FeatureSpaceId)
  given Encoder[PatchId] = opaqueEncoder(PatchId)
  given Decoder[PatchId] = opaqueDecoder(PatchId)
  given Encoder[StageId] = opaqueEncoder(StageId)
  given Decoder[StageId] = opaqueDecoder(StageId)
  given Encoder[Fingerprint] = opaqueEncoder(Fingerprint)
  given Decoder[Fingerprint] = opaqueDecoder(Fingerprint)
  given Encoder[SpeakerId] = opaqueEncoder(SpeakerId)
  given Decoder[SpeakerId] = opaqueDecoder(SpeakerId)
  given KeyEncoder[SpeakerId] = opaqueKeyEncoder(SpeakerId)
  given KeyDecoder[SpeakerId] = opaqueKeyDecoder(SpeakerId)
  given Encoder[TurnId] = opaqueEncoder(TurnId)
  given Decoder[TurnId] = opaqueDecoder(TurnId)
  given Encoder[PromptId] = opaqueEncoder(PromptId)
  given Decoder[PromptId] = opaqueDecoder(PromptId)

  given [K <: NarrativeKind]: Encoder[MentionId[K]] = Encoder.encodeString.contramap(_.value)
  given [K <: NarrativeKind]: Decoder[MentionId[K]] =
    Decoder.decodeString.emap(s => MentionId.from[K](s).left.map(_.message))
  given [K <: NarrativeKind]: Encoder[CanonicalId[K]] = Encoder.encodeString.contramap(_.value)
  given [K <: NarrativeKind]: Decoder[CanonicalId[K]] =
    Decoder.decodeString.emap(s => CanonicalId.from[K](s).left.map(_.message))

  given Encoder[LanguageTag] = Encoder.encodeString.contramap(_.value)
  given Decoder[LanguageTag] =
    Decoder.decodeString.emap(s => LanguageTag.from(s).left.map(_.message))

  // ---- universal addresses ---------------------------------------------------------------
  private def canonicalAddress(rendered: String): Either[String, Address] =
    Address.parse(rendered).left.map(_.message).flatMap { address =>
      Either.cond(
        address.render == rendered,
        address,
        s"non-canonical Address; expected ${address.render}"
      )
    }

  given Encoder[Address] = Encoder.encodeString.contramap(_.render)
  given Decoder[Address] = Decoder.decodeString.emap(canonicalAddress)
  given KeyEncoder[Address] = KeyEncoder.instance(_.render)
  given KeyDecoder[Address] = KeyDecoder.instance(rendered => canonicalAddress(rendered).toOption)

  // ---- spans ----------------------------------------------------------------------------
  given Encoder[TextSpan] =
    Encoder.instance(s => Json.obj("start" -> s.start.asJson, "end" -> s.endExclusive.asJson))
  given Decoder[TextSpan] = Decoder.instance { c =>
    for
      s <- field[Int](c, "start")
      e <- field[Int](c, "end")
      r <- domain(c, TextSpan.of(s, e))
    yield r
  }

  given Encoder[SpanRef] =
    Encoder.instance(r => obj("unit" -> opt(r.unit), "span" -> r.span.asJson))
  given Decoder[SpanRef] = Decoder.instance { c =>
    for
      u <- field[Option[SurfaceUnitId]](c, "unit")
      s <- field[TextSpan](c, "span")
    yield SpanRef(u, s)
  }

  given Encoder[SpanSet] = Encoder.encodeVector[SpanRef].contramap(_.refs.toVector)
  given Decoder[SpanSet] = Decoder
    .decodeVector[SpanRef]
    .emap(v => SpanSet.of(v).toRight("SpanSet requires at least one span"))

  // ---- source and atlas -----------------------------------------------------------------
  given Encoder[StorySource] = Encoder.instance { s =>
    obj(
      "id" -> s.id.asJson,
      "title" -> opt(s.title),
      "language" -> s.language.asJson,
      "rawText" -> s.rawText.asJson,
      "canonicalText" -> s.canonicalText.asJson,
      "rawChecksum" -> s.rawChecksum.asJson,
      "canonicalChecksum" -> s.canonicalChecksum.asJson,
      "metadata" -> s.metadata.asJson
    )
  }

  /** Rebuilds through `StorySource.fromText` and rejects a document whose recorded canonical text
    * or checksums disagree with the recomputed ones: text identity is never trusted from the wire.
    */
  given Decoder[StorySource] = Decoder.instance { c =>
    for
      id <- field[StoryId](c, "id")
      title <- field[Option[String]](c, "title")
      lang <- field[LanguageTag](c, "language")
      raw <- field[String](c, "rawText")
      canonical <- field[String](c, "canonicalText")
      rawSum <- field[Checksum](c, "rawChecksum")
      canonSum <- field[Checksum](c, "canonicalChecksum")
      meta <- field[Map[String, String]](c, "metadata")
      src <- domain(c, StorySource.fromText(raw, title, lang, meta, Some(id)))
      _ <-
        if src.canonicalText == canonical && src.rawChecksum == rawSum &&
          src.canonicalChecksum == canonSum
        then Right(())
        else Left(DecodingFailure("source text or checksums do not match recomputation", c.history))
    yield src
  }

  given Encoder[SurfaceUnitKind] = enumEncoder(_.toString)
  given Decoder[SurfaceUnitKind] =
    enumDecoder("SurfaceUnitKind", SurfaceUnitKind.values, _.toString)

  given Encoder[SurfaceUnit] = Encoder.instance { u =>
    obj(
      "id" -> u.id.asJson,
      "kind" -> u.kind.asJson,
      "span" -> u.span.asJson,
      "ordinal" -> u.ordinal.asJson,
      "parent" -> opt(u.parent)
    )
  }
  given Decoder[SurfaceUnit] = Decoder.instance { c =>
    for
      id <- field[SurfaceUnitId](c, "id")
      k <- field[SurfaceUnitKind](c, "kind")
      s <- field[TextSpan](c, "span")
      o <- field[Int](c, "ordinal")
      p <- field[Option[SurfaceUnitId]](c, "parent")
    yield SurfaceUnit(id, k, s, o, p)
  }

  given Encoder[SurfaceAtlas] =
    Encoder.instance(a => Json.obj("source" -> a.source.asJson, "units" -> a.units.asJson))
  given Decoder[SurfaceAtlas] = Decoder.instance { c =>
    for
      s <- field[StorySource](c, "source")
      u <- field[Vector[SurfaceUnit]](c, "units")
      a <- domain(c, SurfaceAtlas.of(s, u))
    yield a
  }

  /** Atlas without its source, for artifacts that already carry the source exactly once (the story
    * model and the recall graph): the text must never be copied inside one document.
    */
  val atlasUnitsEncoder: Encoder[SurfaceAtlas] =
    Encoder.instance(a => Json.obj("units" -> a.units.asJson))

  def decodeAtlasUnits(c: io.circe.HCursor, source: StorySource): Decoder.Result[SurfaceAtlas] =
    for
      u <- c.downField("units").as[Vector[SurfaceUnit]]
      a <- domain(c, SurfaceAtlas.of(source, u))
    yield a

  // ---- claims ---------------------------------------------------------------------------
  given Encoder[EpistemicStatus] = enumEncoder(_.toString)
  given Decoder[EpistemicStatus] =
    enumDecoder("EpistemicStatus", EpistemicStatus.values, _.toString)

  given Encoder[Credence] = Encoder.instance { cr =>
    obj(
      "rawScore" -> cr.rawScore.asJson,
      "calibrated" -> opt(cr.calibrated),
      "calibrationModel" -> opt(cr.calibrationModel)
    )
  }
  given Decoder[Credence] = Decoder.instance { c =>
    for
      raw <- field[Double](c, "rawScore")
      cal <- field[Option[Probability]](c, "calibrated")
      m <- field[Option[String]](c, "calibrationModel")
      cr <- domain(c, Credence.from(raw, cal, m))
    yield cr
  }

  given Encoder[Evidence] = Encoder.instance { e =>
    obj(
      "id" -> e.id.asJson,
      "spans" -> opt(e.spans),
      "upstream" -> sortedSetEncoder[ClaimId](e.upstream),
      "extractor" -> e.extractor.asJson,
      "stage" -> e.stage.asJson
    )
  }
  given Decoder[Evidence] = Decoder.instance { c =>
    for
      id <- field[EvidenceId](c, "id")
      s <- field[Option[SpanSet]](c, "spans")
      up <- field[Vector[ClaimId]](c, "upstream")
      x <- field[Fingerprint](c, "extractor")
      st <- field[StageId](c, "stage")
    yield Evidence(id, s, up.toSet, x, st)
  }

  given Encoder[ProviderCall] = Encoder.instance { p =>
    obj(
      "provider" -> p.provider.asJson,
      "model" -> p.model.asJson,
      "version" -> p.version.asJson,
      "promptTemplateVersion" -> opt(p.promptTemplateVersion),
      "inputChecksum" -> p.inputChecksum.asJson,
      "outputChecksum" -> p.outputChecksum.asJson,
      "params" -> p.params.asJson,
      "seed" -> opt(p.seed),
      "cached" -> p.cached.asJson
    )
  }
  given Decoder[ProviderCall] = Decoder.instance { c =>
    for
      pr <- field[String](c, "provider")
      m <- field[String](c, "model")
      v <- field[String](c, "version")
      pt <- field[Option[String]](c, "promptTemplateVersion")
      i <- field[Checksum](c, "inputChecksum")
      o <- field[Checksum](c, "outputChecksum")
      ps <- field[Map[String, String]](c, "params")
      seed <- field[Option[Long]](c, "seed")
      cached <- field[Boolean](c, "cached")
    yield ProviderCall(pr, m, v, pt, i, o, ps, seed, cached)
  }

  given Encoder[Provenance] = Encoder.instance { p =>
    Json.obj(
      "calls" -> p.calls.asJson,
      "softwareVersion" -> p.softwareVersion.asJson,
      "configHash" -> p.configHash.asJson
    )
  }
  given Decoder[Provenance] = Decoder.instance { c =>
    for
      calls <- field[Vector[ProviderCall]](c, "calls")
      sv <- field[String](c, "softwareVersion")
      h <- field[Checksum](c, "configHash")
    yield Provenance(calls, sv, h)
  }

  given Encoder[ClaimMeta] = Encoder.instance { m =>
    Json.obj(
      "id" -> m.id.asJson,
      "status" -> m.status.asJson,
      "credence" -> m.credence.asJson,
      "evidence" -> m.evidence.toVector.asJson,
      "provenance" -> m.provenance.asJson
    )
  }
  given Decoder[ClaimMeta] = Decoder.instance { c =>
    for
      id <- field[ClaimId](c, "id")
      st <- field[EpistemicStatus](c, "status")
      cr <- field[Credence](c, "credence")
      ev <- field[Vector[Evidence]](c, "evidence")
      nev <- NonEmptyVector
        .fromVector(ev)
        .toRight(DecodingFailure("claim requires at least one evidence", c.history))
      pv <- field[Provenance](c, "provenance")
      m <- domain(c, ClaimMeta.of(id, st, cr, nev, pv))
    yield m
  }

  given [A](using e: Encoder[A]): Encoder[Resolved[A]] = Encoder.instance { r =>
    Json.obj(
      "value" -> e(r.value),
      "meta" -> r.meta.asJson,
      "alternatives" -> r.alternatives
        .map((a, c) => Json.obj("value" -> e(a), "credence" -> c.asJson))
        .asJson
    )
  }
  given [A](using d: Decoder[A]): Decoder[Resolved[A]] = Decoder.instance { c =>
    val alt: Decoder[(A, Credence)] = Decoder.instance { ac =>
      for
        v <- ac.downField("value").as[A]
        cr <- ac.downField("credence").as[Credence]
      yield (v, cr)
    }
    for
      v <- c.downField("value").as[A]
      m <- field[ClaimMeta](c, "meta")
      as <- c
        .downField("alternatives")
        .as[Vector[(A, Credence)]](using Decoder.decodeVector(using alt))
    yield Resolved(v, m, as)
  }

  given Encoder[BuildReceipt] = Encoder.instance { r =>
    Json.obj(
      "storyId" -> r.storyId.asJson,
      "sourceChecksum" -> r.sourceChecksum.asJson,
      "schemaVersion" -> r.schemaVersion.asJson,
      "stages" -> r.stages
        .map((s, c) => Json.obj("stage" -> s.asJson, "checksum" -> c.asJson))
        .asJson,
      "createdAtEpochMillis" -> r.createdAtEpochMillis.asJson
    )
  }
  given Decoder[BuildReceipt] = Decoder.instance { c =>
    val stage: Decoder[(StageId, Checksum)] = Decoder.instance { sc =>
      for
        s <- sc.downField("stage").as[StageId]
        h <- sc.downField("checksum").as[Checksum]
      yield (s, h)
    }
    for
      id <- field[StoryId](c, "storyId")
      sum <- field[Checksum](c, "sourceChecksum")
      sv <- field[String](c, "schemaVersion")
      st <- c
        .downField("stages")
        .as[Vector[(StageId, Checksum)]](using Decoder.decodeVector(using stage))
      t <- field[Long](c, "createdAtEpochMillis")
    yield BuildReceipt(id, sum, sv, st, t)
  }

  // ---- transcript overlay ---------------------------------------------------------------
  given Encoder[InterviewPhase] = Encoder.instance {
    case InterviewPhase.Other(label) => Json.obj("type" -> "Other".asJson, "label" -> label.asJson)
    case p                           => p.toString.asJson
  }
  given Decoder[InterviewPhase] = Decoder.instance { c =>
    c.value.asString match
      case Some("FreeRecall")    => Right(InterviewPhase.FreeRecall)
      case Some("GeneralProbe")  => Right(InterviewPhase.GeneralProbe)
      case Some("SpecificProbe") => Right(InterviewPhase.SpecificProbe)
      case Some(other) => Left(DecodingFailure(s"unknown InterviewPhase $other", c.history))
      case None        => c.downField("label").as[String].map(InterviewPhase.Other.apply)
  }

  given Encoder[SpeakerRole] = enumEncoder(_.toString)
  given Decoder[SpeakerRole] = enumDecoder("SpeakerRole", SpeakerRole.values, _.toString)

  given Encoder[AudioSpan] = Encoder.instance(a =>
    Json.obj("startMillis" -> a.startMillis.asJson, "endMillis" -> a.endMillis.asJson)
  )
  given Decoder[AudioSpan] = Decoder.instance { c =>
    for
      s <- field[Long](c, "startMillis")
      e <- field[Long](c, "endMillis")
      a <- domain(c, AudioSpan.of(s, e))
    yield a
  }

  given Encoder[TranscriptTurn] = Encoder.instance { t =>
    obj(
      "id" -> t.id.asJson,
      "speaker" -> t.speaker.asJson,
      "support" -> t.support.asJson,
      "audio" -> opt(t.audio),
      "phase" -> opt(t.phase),
      "prompt" -> opt(t.prompt)
    )
  }
  given Decoder[TranscriptTurn] = Decoder.instance { c =>
    for
      id <- field[TurnId](c, "id")
      sp <- field[SpeakerId](c, "speaker")
      su <- field[SpanSet](c, "support")
      au <- field[Option[AudioSpan]](c, "audio")
      ph <- field[Option[InterviewPhase]](c, "phase")
      pr <- field[Option[PromptId]](c, "prompt")
    yield TranscriptTurn(id, sp, su, au, ph, pr)
  }

  given Encoder[TranscriptAtlas] = Encoder.instance { t =>
    Json.obj(
      "atlas" -> t.atlas.asJson,
      "turns" -> t.turns.asJson,
      "speakers" -> t.speakers.asJson
    )
  }
  given Decoder[TranscriptAtlas] = Decoder.instance { c =>
    for
      a <- field[SurfaceAtlas](c, "atlas")
      t <- field[Vector[TranscriptTurn]](c, "turns")
      s <- field[Map[SpeakerId, SpeakerRole]](c, "speakers")
      r <- domain(c, TranscriptAtlas.of(a, t, s))
    yield r
  }

  // ---- window coordinates ---------------------------------------------------------------
  given Encoder[TokenIndex] = Encoder.encodeInt.contramap(_.value)
  given Decoder[TokenIndex] = Decoder.decodeInt.emap(i => TokenIndex.from(i).left.map(_.message))
  given Encoder[TokenRange] = Encoder.instance(r =>
    Json.obj("start" -> r.start.value.asJson, "end" -> r.endExclusive.value.asJson)
  )
  given Decoder[TokenRange] = Decoder.instance { c =>
    for
      s <- field[Int](c, "start")
      e <- field[Int](c, "end")
      r <- domain(c, TokenRange.of(s, e))
    yield r
  }
  given Encoder[PositiveInt] = Encoder.encodeInt.contramap(_.value)
  given Decoder[PositiveInt] = Decoder.decodeInt.emap(i => PositiveInt.from(i).left.map(_.message))
  given Encoder[WindowBasis] = enumEncoder(_.toString)
  given Decoder[WindowBasis] = enumDecoder("WindowBasis", WindowBasis.values, _.toString)
  given Encoder[EdgePolicy] = enumEncoder(_.toString)
  given Decoder[EdgePolicy] = enumDecoder("EdgePolicy", EdgePolicy.values, _.toString)
  given Encoder[WindowPlan] = Encoder.instance { w =>
    Json.obj(
      "width" -> w.width.asJson,
      "step" -> w.step.asJson,
      "basis" -> w.basis.asJson,
      "edgePolicy" -> w.edgePolicy.asJson
    )
  }
  given Decoder[WindowPlan] = Decoder.instance { c =>
    for
      w <- field[PositiveInt](c, "width")
      s <- field[PositiveInt](c, "step")
      b <- field[WindowBasis](c, "basis")
      e <- field[EdgePolicy](c, "edgePolicy")
    yield WindowPlan(w, s, b, e)
  }
