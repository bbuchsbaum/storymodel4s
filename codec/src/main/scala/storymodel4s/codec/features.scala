package storymodel4s.codec

import cats.data.NonEmptyVector
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.syntax.*
import storymodel4s.core.*
import storymodel4s.features.*
import CanonicalPrimitives.{*, given}
import CoreCodecs.given

/** Codecs for `storymodel4s.features`. Scalar and categorical observations are inline; vectors are
  * never inline (they live behind a [[SidecarManifest]]). `Estimate.Missing(reason)` survives the
  * round trip as `Missing(reason)` — never as `null`, `0`, or `NaN`.
  */
object FeatureCodecs:
  given Encoder[ReducerId] = opaqueEncoder(ReducerId)
  given Decoder[ReducerId] = opaqueDecoder(ReducerId)

  given Encoder[FeatureValueSchema] = Encoder.instance {
    case FeatureValueSchema.Scalar(units) =>
      obj("type" -> "Scalar".asJson, "units" -> opt(units))
    case FeatureValueSchema.Vector(d) =>
      Json.obj("type" -> "Vector".asJson, "dimension" -> d.asJson)
    case FeatureValueSchema.Categorical(ls) =>
      Json.obj("type" -> "Categorical".asJson, "labels" -> ls.asJson)
    case FeatureValueSchema.Distribution(s) =>
      Json.obj("type" -> "Distribution".asJson, "support" -> s.asJson)
  }
  given Decoder[FeatureValueSchema] = Decoder.instance { c =>
    field[String](c, "type").flatMap {
      case "Scalar"      => field[Option[String]](c, "units").map(FeatureValueSchema.Scalar.apply)
      case "Vector"      => field[Int](c, "dimension").map(FeatureValueSchema.Vector.apply)
      case "Categorical" =>
        field[Vector[String]](c, "labels").map(FeatureValueSchema.Categorical.apply)
      case "Distribution" =>
        field[Vector[String]](c, "support").map(FeatureValueSchema.Distribution.apply)
      case other => Left(DecodingFailure(s"unknown FeatureValueSchema $other", c.history))
    }
  }

  given [V]: Encoder[FeatureSpace[V]] = Encoder.instance { s =>
    obj(
      "id" -> s.id.asJson,
      "description" -> s.description.asJson,
      "valueSchema" -> s.valueSchema.asJson,
      "units" -> opt(s.units),
      "provider" -> s.provider.asJson,
      "normalized" -> s.normalized.asJson,
      "normalizationPopulation" -> opt(s.normalizationPopulation)
    )
  }
  given [V]: Decoder[FeatureSpace[V]] = Decoder.instance { c =>
    for
      id <- field[FeatureSpaceId](c, "id")
      d <- field[String](c, "description")
      vs <- field[FeatureValueSchema](c, "valueSchema")
      u <- field[Option[String]](c, "units")
      p <- field[Fingerprint](c, "provider")
      n <- field[Boolean](c, "normalized")
      np <- field[Option[String]](c, "normalizationPopulation")
    yield FeatureSpace[V](id, d, vs, u, p, n, np)
  }

  /** Existential spaces (a `StoryModel` holds `FeatureSpace[?]`) decode as `FeatureSpace[Any]`. */
  val spaceExistentialEncoder: Encoder[FeatureSpace[?]] =
    Encoder.instance(s => summon[Encoder[FeatureSpace[Any]]](s.asInstanceOf[FeatureSpace[Any]]))
  val spaceExistentialDecoder: Decoder[FeatureSpace[?]] =
    summon[Decoder[FeatureSpace[Any]]].map(s => s: FeatureSpace[?])

  given Encoder[FeatureTarget] = Encoder.instance {
    case FeatureTarget.Token(i)     => Json.obj("type" -> "Token".asJson, "index" -> i.asJson)
    case FeatureTarget.Sentence(u)  => Json.obj("type" -> "Sentence".asJson, "unit" -> u.asJson)
    case FeatureTarget.Situation(i) => Json.obj("type" -> "Situation".asJson, "id" -> i.asJson)
    case FeatureTarget.Segment(i)   => Json.obj("type" -> "Segment".asJson, "id" -> i.asJson)
    case FeatureTarget.Boundary(u) => Json.obj("type" -> "Boundary".asJson, "afterUnit" -> u.asJson)
    case FeatureTarget.Turn(i)     => Json.obj("type" -> "Turn".asJson, "id" -> i.asJson)
    case FeatureTarget.Window(r)   => Json.obj("type" -> "Window".asJson, "range" -> r.asJson)
  }
  given Decoder[FeatureTarget] = Decoder.instance { c =>
    field[String](c, "type").flatMap {
      case "Token"     => field[TokenIndex](c, "index").map(FeatureTarget.Token.apply)
      case "Sentence"  => field[SurfaceUnitId](c, "unit").map(FeatureTarget.Sentence.apply)
      case "Situation" => field[SituationId](c, "id").map(FeatureTarget.Situation.apply)
      case "Segment"   => field[SegmentId](c, "id").map(FeatureTarget.Segment.apply)
      case "Boundary"  => field[SurfaceUnitId](c, "afterUnit").map(FeatureTarget.Boundary.apply)
      case "Turn"      => field[TurnId](c, "id").map(FeatureTarget.Turn.apply)
      case "Window"    => field[TokenRange](c, "range").map(FeatureTarget.Window.apply)
      case other       => Left(DecodingFailure(s"unknown FeatureTarget $other", c.history))
    }
  }
  given Encoder[FeatureTarget.Boundary] =
    summon[Encoder[FeatureTarget]].contramap[FeatureTarget.Boundary](identity)
  given Decoder[FeatureTarget.Boundary] = summon[Decoder[FeatureTarget]].emap {
    case b: FeatureTarget.Boundary => Right(b)
    case other                     => Left(s"expected Boundary target, got $other")
  }

  given Encoder[Dtype] = enumEncoder(_.toString)
  given Decoder[Dtype] = enumDecoder("Dtype", Dtype.values, _.toString)
  given Encoder[Layout] = enumEncoder(_.toString)
  given Decoder[Layout] = enumDecoder("Layout", Layout.values, _.toString)

  given Encoder[SidecarManifest] = Encoder.instance { m =>
    Json.obj(
      "space" -> m.space.asJson,
      "dimension" -> m.dimension.asJson,
      "rowCount" -> m.rowCount.asJson,
      "dtype" -> m.dtype.asJson,
      "checksum" -> m.checksum.asJson,
      "layout" -> m.layout.asJson
    )
  }
  given Decoder[SidecarManifest] = Decoder.instance { c =>
    for
      s <- field[FeatureSpaceId](c, "space")
      d <- field[Int](c, "dimension")
      r <- field[Int](c, "rowCount")
      t <- field[Dtype](c, "dtype")
      h <- field[Checksum](c, "checksum")
      l <- field[Layout](c, "layout")
      m <- domain(c, SidecarManifest.validated(SidecarManifest(s, d, r, t, h, l)))
    yield m
  }

  given Encoder[FeatureRef] = Encoder.instance(r =>
    Json.obj("target" -> r.target.asJson, "space" -> r.space.asJson, "row" -> r.row.asJson)
  )
  given Decoder[FeatureRef] = Decoder.instance { c =>
    for
      t <- field[FeatureTarget](c, "target")
      s <- field[FeatureSpaceId](c, "space")
      r <- field[Int](c, "row")
    yield FeatureRef(t, s, r)
  }

  given Encoder[Coverage] = Encoder.instance(cv =>
    Json.obj("eligible" -> cv.eligible.asJson, "observed" -> cv.observed.asJson)
  )
  given Decoder[Coverage] = Decoder.instance { c =>
    for
      e <- field[Int](c, "eligible")
      o <- field[Int](c, "observed")
      cv <- domain(c, Coverage.of(e, o))
    yield cv
  }

  given Encoder[UndefinedReason] = Encoder.instance {
    case UndefinedReason.Custom(ns, n) =>
      Json.obj("type" -> "Custom".asJson, "namespace" -> ns.asJson, "name" -> n.asJson)
    case r => r.toString.asJson
  }
  given Decoder[UndefinedReason] = Decoder.instance { c =>
    c.value.asString match
      case Some(s) =>
        Vector(
          UndefinedReason.SlopeNeedsTwoPositions,
          UndefinedReason.ZeroTotalWeight,
          UndefinedReason.OutsideKernelSupport,
          UndefinedReason.NotFinite
        )
          .find(v => v.toString == s)
          .toRight(DecodingFailure(s"unknown UndefinedReason $s", c.history))
      case None =>
        for
          ns <- field[String](c, "namespace")
          n <- field[String](c, "name")
        yield UndefinedReason.Custom(ns, n)
  }

  given Encoder[MissingReason] = Encoder.instance {
    case MissingReason.Undefined(r) => Json.obj("type" -> "Undefined".asJson, "reason" -> r.asJson)
    case r                          => r.toString.asJson
  }
  given Decoder[MissingReason] = Decoder.instance { c =>
    c.value.asString match
      case Some(s) =>
        Vector(
          MissingReason.NotInLexicon,
          MissingReason.OutOfVocabulary,
          MissingReason.ProviderAbstained,
          MissingReason.Excluded,
          MissingReason.AllMissing,
          MissingReason.Unknown
        )
          .find(v => v.toString == s)
          .toRight(DecodingFailure(s"unknown MissingReason $s", c.history))
      case None => field[UndefinedReason](c, "reason").map(MissingReason.Undefined.apply)
  }

  given [V](using e: Encoder[V]): Encoder[Estimate[V]] = Encoder.instance {
    case Estimate.Observed(v, cr) => obj("observed" -> e(v), "credence" -> opt(cr))
    case Estimate.Missing(r)      => Json.obj("missing" -> r.asJson)
  }
  given [V](using d: Decoder[V]): Decoder[Estimate[V]] = Decoder.instance { c =>
    if c.downField("missing").succeeded then
      field[MissingReason](c, "missing").map(Estimate.Missing.apply)
    else
      for
        v <- c.downField("observed").as[V]
        cr <- field[Option[Credence]](c, "credence")
      yield Estimate.Observed(v, cr)
  }

  given [V](using Encoder[V]): Encoder[FeatureObservation[FeatureTarget, V]] = Encoder.instance {
    o =>
      obj(
        "target" -> o.target.asJson,
        "estimate" -> o.estimate.asJson,
        "support" -> opt(o.support),
        "coverage" -> opt(o.coverage)
      )
  }
  given [V](using Decoder[V]): Decoder[FeatureObservation[FeatureTarget, V]] = Decoder.instance {
    c =>
      for
        t <- field[FeatureTarget](c, "target")
        e <- field[Estimate[V]](c, "estimate")
        s <- field[Option[SpanSet]](c, "support")
        cv <- field[Option[Coverage]](c, "coverage")
      yield FeatureObservation(t, e, s, cv)
  }

  given Encoder[TrackProvenance] = Encoder.instance(p =>
    obj("provenance" -> p.provenance.asJson, "storyChecksum" -> opt(p.storyChecksum))
  )
  given Decoder[TrackProvenance] = Decoder.instance { c =>
    for
      p <- field[Provenance](c, "provenance")
      s <- field[Option[Checksum]](c, "storyChecksum")
    yield TrackProvenance(p, s)
  }

  given Encoder[WeightingPolicy] = Encoder.instance {
    case WeightingPolicy.Uniform      => "Uniform".asJson
    case WeightingPolicy.Kernel(s, b) =>
      Json.obj("type" -> "Kernel".asJson, "shape" -> s.asJson, "bandwidth" -> b.asJson)
    case WeightingPolicy.Provided(d) =>
      Json.obj("type" -> "Provided".asJson, "description" -> d.asJson)
  }
  given Decoder[WeightingPolicy] = Decoder.instance { c =>
    c.value.asString match
      case Some("Uniform") => Right(WeightingPolicy.Uniform)
      case Some(o)         => Left(DecodingFailure(s"unknown WeightingPolicy $o", c.history))
      case None            =>
        field[String](c, "type").flatMap {
          case "Kernel" =>
            for
              s <- field[String](c, "shape")
              b <- field[Double](c, "bandwidth")
            yield WeightingPolicy.Kernel(s, b)
          case "Provided" => field[String](c, "description").map(WeightingPolicy.Provided.apply)
          case o          => Left(DecodingFailure(s"unknown WeightingPolicy $o", c.history))
        }
  }

  given Encoder[MissingValuePolicy] = Encoder.instance {
    case MissingValuePolicy.IgnoreMissing         => "IgnoreMissing".asJson
    case MissingValuePolicy.Fail                  => "Fail".asJson
    case MissingValuePolicy.RequireMinCoverage(f) =>
      Json.obj("type" -> "RequireMinCoverage".asJson, "fraction" -> f.asJson)
  }
  given Decoder[MissingValuePolicy] = Decoder.instance { c =>
    c.value.asString match
      case Some("IgnoreMissing") => Right(MissingValuePolicy.IgnoreMissing)
      case Some("Fail")          => Right(MissingValuePolicy.Fail)
      case Some(o) => Left(DecodingFailure(s"unknown MissingValuePolicy $o", c.history))
      case None    => field[Double](c, "fraction").map(MissingValuePolicy.RequireMinCoverage.apply)
  }

  given Encoder[NormalizationPolicy] = Encoder.instance {
    case NormalizationPolicy.UnitLength => "UnitLength".asJson
    case NormalizationPolicy.ZScore(p)  =>
      Json.obj("type" -> "ZScore".asJson, "populationId" -> p.asJson)
    case NormalizationPolicy.MinMax(p) =>
      Json.obj("type" -> "MinMax".asJson, "populationId" -> p.asJson)
  }
  given Decoder[NormalizationPolicy] = Decoder.instance { c =>
    c.value.asString match
      case Some("UnitLength") => Right(NormalizationPolicy.UnitLength)
      case Some(o)            => Left(DecodingFailure(s"unknown NormalizationPolicy $o", c.history))
      case None               =>
        field[String](c, "type").flatMap {
          case "ZScore" => field[String](c, "populationId").map(NormalizationPolicy.ZScore.apply)
          case "MinMax" => field[String](c, "populationId").map(NormalizationPolicy.MinMax.apply)
          case o        => Left(DecodingFailure(s"unknown NormalizationPolicy $o", c.history))
        }
  }

  given Encoder[Eligibility] = enumEncoder(_.toString)
  given Decoder[Eligibility] = enumDecoder("Eligibility", Eligibility.values, _.toString)
  given Encoder[TargetFamily] = enumEncoder(_.toString)
  given Decoder[TargetFamily] = enumDecoder("TargetFamily", TargetFamily.values, _.toString)

  given Encoder[FeatureDerivation] = Encoder.instance { d =>
    obj(
      "inputs" -> d.inputs.toVector.asJson,
      "window" -> opt(d.window),
      "reducer" -> d.reducer.asJson,
      "weighting" -> d.weighting.asJson,
      "missing" -> d.missing.asJson,
      "normalization" -> opt(d.normalization),
      "implementationVersion" -> d.implementationVersion.asJson,
      "eligibility" -> d.eligibility.asJson,
      "targetFamily" -> opt(d.targetFamily)
    )
  }
  given Decoder[FeatureDerivation] = Decoder.instance { c =>
    for
      ins <- field[Vector[FeatureSpaceId]](c, "inputs")
      nev <- NonEmptyVector
        .fromVector(ins)
        .toRight(DecodingFailure("derivation requires an input", c.history))
      w <- field[Option[WindowPlan]](c, "window")
      r <- field[ReducerId](c, "reducer")
      wp <- field[WeightingPolicy](c, "weighting")
      m <- field[MissingValuePolicy](c, "missing")
      n <- field[Option[NormalizationPolicy]](c, "normalization")
      iv <- field[String](c, "implementationVersion")
      el <- field[Eligibility](c, "eligibility")
      tf <- field[Option[TargetFamily]](c, "targetFamily")
    yield FeatureDerivation(nev, w, r, wp, m, n, iv, el, tf)
  }

  /** Inline tracks over any target, for scalar (`Double`) and categorical (`String`) values. */
  given [V](using Encoder[V]): Encoder[FeatureTrack[FeatureTarget, V]] = Encoder.instance { t =>
    obj(
      "schemaVersion" -> SchemaVersions.Current.asJson,
      "space" -> t.space.asJson,
      "observations" -> t.observations.asJson,
      "derivation" -> opt(t.derivation),
      "provenance" -> t.provenance.asJson
    )
  }
  given [V](using Decoder[V]): Decoder[FeatureTrack[FeatureTarget, V]] = Decoder.instance { c =>
    for
      _ <- SchemaVersions.check(c)
      s <- field[FeatureSpace[V]](c, "space")
      o <- field[Vector[FeatureObservation[FeatureTarget, V]]](c, "observations")
      d <- field[Option[FeatureDerivation]](c, "derivation")
      p <- field[TrackProvenance](c, "provenance")
      t <- domain(c, FeatureTrack.validated(FeatureTrack(s, o, d, p)))
    yield t
  }

  // ---- boundary and world-time vocabulary ----------------------------------------------
  given Encoder[BoundarySignal] = Encoder.instance {
    case BoundarySignal.Custom(ns, n) =>
      Json.obj("type" -> "Custom".asJson, "namespace" -> ns.asJson, "name" -> n.asJson)
    case s => s.toString.asJson
  }
  given Decoder[BoundarySignal] = Decoder.instance { c =>
    c.value.asString match
      case Some(s) =>
        Vector(
          BoundarySignal.Semantic,
          BoundarySignal.Proposition,
          BoundarySignal.Entities,
          BoundarySignal.Location,
          BoundarySignal.Context,
          BoundarySignal.WorldTime,
          BoundarySignal.Goals,
          BoundarySignal.Sensory,
          BoundarySignal.Affect,
          BoundarySignal.Imageability,
          BoundarySignal.DiscourseCue,
          BoundarySignal.ModelVote
        )
          .find(v => v.toString == s)
          .toRight(DecodingFailure(s"unknown BoundarySignal $s", c.history))
      case None =>
        for
          ns <- field[String](c, "namespace")
          n <- field[String](c, "name")
        yield BoundarySignal.Custom(ns, n)
  }

  given Encoder[DurationUnit] = enumEncoder(_.toString)
  given Decoder[DurationUnit] = enumDecoder("DurationUnit", DurationUnit.values, _.toString)

  given Encoder[DurationEstimate] = Encoder.instance { d =>
    obj("magnitude" -> d.magnitude.asJson, "unit" -> d.unit.asJson, "cue" -> opt(d.cue))
  }
  given Decoder[DurationEstimate] = Decoder.instance { c =>
    for
      m <- field[Estimate[Double]](c, "magnitude")
      u <- field[DurationUnit](c, "unit")
      cue <- field[Option[SpanSet]](c, "cue")
    yield DurationEstimate(m, u, cue)
  }

  given Encoder[TemporalRelationTag] = Encoder.instance {
    case TemporalRelationTag.Custom(ns, n) =>
      Json.obj("type" -> "Custom".asJson, "namespace" -> ns.asJson, "name" -> n.asJson)
    case t => t.toString.asJson
  }
  given Decoder[TemporalRelationTag] = Decoder.instance { c =>
    c.value.asString match
      case Some(s) =>
        Vector(
          TemporalRelationTag.Before,
          TemporalRelationTag.Meets,
          TemporalRelationTag.Overlaps,
          TemporalRelationTag.During,
          TemporalRelationTag.Contains,
          TemporalRelationTag.Starts,
          TemporalRelationTag.Finishes,
          TemporalRelationTag.Equal,
          TemporalRelationTag.Unclear
        )
          .find(v => v.toString == s)
          .toRight(DecodingFailure(s"unknown TemporalRelationTag $s", c.history))
      case None =>
        for
          ns <- field[String](c, "namespace")
          n <- field[String](c, "name")
        yield TemporalRelationTag.Custom(ns, n)
  }

  given Encoder[TemporalHypothesis] = Encoder.instance(h =>
    Json.obj("relation" -> h.relation.asJson, "credence" -> h.credence.asJson)
  )
  given Decoder[TemporalHypothesis] = Decoder.instance { c =>
    for
      r <- field[TemporalRelationTag](c, "relation")
      cr <- field[Credence](c, "credence")
    yield TemporalHypothesis(r, cr)
  }

  given Encoder[WorldTimeTransition] = Encoder.instance {
    case WorldTimeTransition.JumpForward(m) =>
      obj("type" -> "JumpForward".asJson, "magnitude" -> opt(m))
    case WorldTimeTransition.JumpBackward(m) =>
      obj("type" -> "JumpBackward".asJson, "magnitude" -> opt(m))
    case WorldTimeTransition.Unresolved(alts) =>
      Json.obj("type" -> "Unresolved".asJson, "alternatives" -> alts.asJson)
    case t => t.toString.asJson
  }
  given Decoder[WorldTimeTransition] = Decoder.instance { c =>
    c.value.asString match
      case Some("Continues")                => Right(WorldTimeTransition.Continues)
      case Some("ReturnFromEarlierFrame")   => Right(WorldTimeTransition.ReturnFromEarlierFrame)
      case Some("SimultaneousThreadSwitch") => Right(WorldTimeTransition.SimultaneousThreadSwitch)
      case Some("Atemporal")                => Right(WorldTimeTransition.Atemporal)
      case Some(o) => Left(DecodingFailure(s"unknown WorldTimeTransition $o", c.history))
      case None    =>
        field[String](c, "type").flatMap {
          case "JumpForward" =>
            field[Option[DurationEstimate]](c, "magnitude")
              .map(WorldTimeTransition.JumpForward.apply)
          case "JumpBackward" =>
            field[Option[DurationEstimate]](c, "magnitude")
              .map(WorldTimeTransition.JumpBackward.apply)
          case "Unresolved" =>
            field[Vector[TemporalHypothesis]](c, "alternatives")
              .map(WorldTimeTransition.Unresolved.apply)
          case o => Left(DecodingFailure(s"unknown WorldTimeTransition $o", c.history))
        }
  }

  given Encoder[BoundaryEvidence] = Encoder.instance { b =>
    Json.obj(
      "target" -> b.target.asJson,
      "signals" -> b.signals.toVector
        .sortBy((k, _) => Canonical.encode(k))
        .map((k, v) => Json.obj("signal" -> k.asJson, "estimate" -> v.asJson))
        .asJson,
      "inputSpaces" -> b.inputSpaces.toVector
        .sortBy((k, _) => Canonical.encode(k))
        .map((k, v) => Json.obj("signal" -> k.asJson, "spaces" -> v.toVector.sorted.asJson))
        .asJson,
      "level" -> b.level.asJson,
      "claims" -> b.claims.asJson
    )
  }
  given Decoder[BoundaryEvidence] = Decoder.instance { c =>
    val sig: Decoder[(BoundarySignal, Estimate[Double])] = Decoder.instance { sc =>
      for
        k <- sc.downField("signal").as[BoundarySignal]
        v <- sc.downField("estimate").as[Estimate[Double]]
      yield (k, v)
    }
    val ins: Decoder[(BoundarySignal, Set[FeatureSpaceId])] = Decoder.instance { sc =>
      for
        k <- sc.downField("signal").as[BoundarySignal]
        v <- sc.downField("spaces").as[Vector[FeatureSpaceId]]
      yield (k, v.toSet)
    }
    for
      t <- field[FeatureTarget.Boundary](c, "target")
      ss <- c
        .downField("signals")
        .as[Vector[(BoundarySignal, Estimate[Double])]](using Decoder.decodeVector(using sig))
      is <- c
        .downField("inputSpaces")
        .as[Vector[(BoundarySignal, Set[FeatureSpaceId])]](using Decoder.decodeVector(using ins))
      l <- field[Int](c, "level")
      cl <- field[Vector[ClaimId]](c, "claims")
    yield BoundaryEvidence(t, ss.toMap, is.toMap, l, cl)
  }

/** Schema versions this codec can read; the current version is written on every artifact. */
object SchemaVersions:
  val Current: String = "0.1.0"
  val Supported: Vector[String] = Vector(Current)

  def check(c: io.circe.HCursor): Decoder.Result[String] =
    c.downField("schemaVersion").as[String].flatMap { v =>
      if Supported.contains(v) then Right(v)
      else
        Left(
          DecodingFailure(
            CodecError.UnsupportedSchema(v, Supported).message,
            c.history
          )
        )
    }
