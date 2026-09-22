package storymodel4s.codec

import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import FeatureCodecs.given
import MappingJson.*

private[codec] object MappingSourceWire:
  def spans(value: SpanSet): Json = array(value.refs.toVector.map { ref =>
    obj(
      "unit" -> optional(ref.unit)(id => str(id.value)),
      "start" -> int(ref.span.start),
      "end_exclusive" -> int(ref.span.endExclusive)
    )
  })
  def inventory(value: RecallInventory): Json =
    val words = value.words.map { w =>
      val membership = value.membership(w.id) match
        case WordMembership.Member(unit)       => tagged("member", "unit" -> str(unit.value))
        case WordMembership.Unassigned(reason) =>
          tagged("unassigned", "reason" -> str(reason.toString))
      obj(
        "id" -> str(w.id.value),
        "index" -> int(w.index),
        "start" -> int(w.span.start),
        "end_exclusive" -> int(w.span.endExclusive),
        "membership" -> membership
      )
    }
    val units = value.units.map { u =>
      val decomposition = u.decomposition match
        case DecompositionStatus.NotAssessed(reason) =>
          tagged("not-assessed", "reason" -> str(reason.toString))
      obj(
        "id" -> str(u.id.value),
        "ordinal" -> int(u.ordinal),
        "spans" -> spans(u.span),
        "words" -> strings(u.words.map(_.value)),
        "decomposition" -> decomposition,
        "semantics" -> str(u.semantics.toString)
      )
    }
    obj(
      "digest" -> str(value.digest.hex),
      "transcript_checksum" -> str(value.transcript.hex),
      "segmentation_id" -> str(value.segmentation.digest.hex),
      "word_id_policy" -> obj(
        "name" -> str(value.idPolicy.name),
        "input_artifact" -> str(value.idPolicy.artifact.hex)
      ),
      "words" -> array(words),
      "units" -> array(units)
    )
  private def rational(value: ExactRational): Json =
    obj("numerator" -> long(value.numerator), "denominator" -> long(value.denominator))
  private def extent(value: AxisExtent): Json = value match
    case v: AxisExtent.TextChars     => tagged("text-chars", "length" -> int(v.length))
    case v: AxisExtent.PlaybackTicks =>
      tagged(
        "playback-ticks",
        "start_tick" -> long(v.start),
        "end_exclusive_tick" -> long(v.endExclusive),
        "timebase" -> rational(v.timebase.scale)
      )
    case AxisExtent.Unknown => tagged("unknown")
  private def sourceKind(value: SourceKind): Json = value match
    case SourceKind.Custom(ns, label) =>
      tagged("custom", "namespace" -> str(ns), "label" -> str(label))
    case other => tagged(other.toString)
  private def streamKind(value: StreamKind): Json = value match
    case StreamKind.Custom(ns, label) =>
      tagged("custom", "namespace" -> str(ns), "label" -> str(label))
    case other => tagged(other.toString)
  private def axis(value: PresentationAxis): Json = obj(
    "id" -> str(value.id.value),
    "bundle" -> str(value.bundle.value),
    "edition" -> optional(value.edition)(id => str(id.value)),
    "kind" -> str(value.kind.toString),
    "source_kind" -> sourceKind(value.sourceKind),
    "extent" -> extent(value.extent),
    "origin" -> optional(value.extent match
      case _: AxisExtent.TextChars     => Some(0L)
      case v: AxisExtent.PlaybackTicks => Some(v.start)
      case AxisExtent.Unknown          => None)(long),
    "timebase" -> optional(value.timebase)(v => rational(v.scale)),
    "fingerprint" -> str(value.fingerprint.hex)
  )
  private def stream(value: SourceStream): Json = obj(
    "id" -> str(value.id.value),
    "kind" -> streamKind(value.kind),
    "checksum" -> str(value.checksum.hex),
    "native_axis" -> str(value.nativeAxis.value),
    "extent" -> extent(value.extent),
    "timebase" -> optional(value.timebase)(v => rational(v.scale)),
    "derived_from" -> strings(value.derivedFrom.map(_.value))
  )
  private def receipt(value: SourceDerivationReceipt): Json = obj(
    "algorithm" -> str(value.algorithm),
    "parameters" -> str(value.parameters),
    "input_checksums" -> strings(value.inputChecksums.map(_.hex)),
    "identity" -> str(value.identity.hex),
    "binding_identity" -> str(value.bindingIdentity.hex)
  )
  private def interval(value: PlaybackInterval): Json = obj(
    "axis" -> str(value.axis.value),
    "start_tick" -> long(value.start),
    "end_exclusive_tick" -> long(value.endExclusive)
  )
  private def mapping(value: CheckedMapping): Json =
    val relation = value match
      case v: ClockRepair           => v.relation
      case v: TrackComposition      => v.relation
      case v: EditionCorrespondence => v.relation
    val fields = value match
      case v: ClockRepair => Vector("scale" -> rational(v.scale), "offset" -> rational(v.offset))
      case v: TrackComposition =>
        Vector(
          "segments" -> array(
            v.segments.toVector
              .map(s =>
                obj(
                  "source" -> interval(s.source),
                  "target" -> interval(s.target),
                  "occurrence" -> str(s.occurrence.value)
                )
              )
              .sortBy(print)
          )
        )
      case v: EditionCorrespondence =>
        Vector(
          "source_edition" -> str(v.sourceEdition.value),
          "target_edition" -> str(v.targetEdition.value),
          "pairs" -> array(
            v.pairs.toVector
              .map((a, b) => obj("source" -> str(a.value), "target" -> str(b.value)))
              .sortBy(print)
          )
        )
    obj(
      (Vector(
        "family" -> str(value.family.toString),
        "identity" -> str(value.identity.hex),
        "source_axis" -> str(relation.sourceAxis.value),
        "target_axis" -> str(relation.targetAxis.value),
        "relation_id" -> str(relation.id.value),
        "relation_identity" -> str(relation.identity.hex),
        "receipt" -> receipt(value.receipt)
      ) ++ fields)*
    )
  private def bundle(value: SourceBundle, role: String): Json = tagged(
    "media",
    "id" -> str(value.id.value),
    "identity" -> str(value.identity.hex),
    "role" -> str(role),
    "edition" -> optional(value.edition)(v => str(v.value)),
    "source_kind" -> sourceKind(value.sourceKind),
    "primary_axis" -> str(value.primaryAxis.id.value),
    "authority_tracks" -> strings(value.authorityTracks.map(_.value)),
    "streams" -> array(value.streams.sortBy(_.id.value).map(stream))
  )
  private def anchor(value: EvidenceAnchor): Json =
    val fields = value match
      case EvidenceAnchor.Text(_, _, value) =>
        Vector("kind" -> str("text"), "spans" -> spans(value))
      case EvidenceAnchor.MediaTime(_, _, axis, values) =>
        Vector(
          "kind" -> str("media-time"),
          "axis" -> str(axis.value),
          "intervals" -> array(values.intervals.toVector.map(interval))
        )
      case EvidenceAnchor.MediaPoint(_, _, at) =>
        Vector("kind" -> str("media-point"), "axis" -> str(at.axis.value), "tick" -> long(at.at))
      case EvidenceAnchor.Shot(_, _, shot, at) =>
        Vector("kind" -> str("shot"), "shot" -> str(shot.value), "interval" -> interval(at))
      case EvidenceAnchor.Track(_, _, track, values) =>
        Vector(
          "kind" -> str("track"),
          "track" -> str(track.value),
          "intervals" -> array(values.intervals.toVector.map(interval))
        )
    obj(
      (Vector(
        "bundle" -> str(value.anchorBundle.value),
        "stream" -> str(value.anchorStream.value)
      ) ++ fields)*
    )
  def support(value: TypedSupport): Json = value match
    case TypedSupport.Text(value)     => tagged("text", "spans" -> spans(value))
    case TypedSupport.Anchored(value) =>
      tagged(
        "anchored",
        "bundle_identity" -> str(value.bundleIdentity.hex),
        "anchors" -> array(value.anchors.toVector.map(anchor).sortBy(print))
      )
  private def target(value: MappingTarget): Json = obj(
    "target_id" -> str(value.ref.key),
    "level" -> int(value.level),
    "parent" -> optional(value.parent)(v => str(v.key)),
    "support_status" -> (value.sourceSupport match
      case located: SourceSupportStatus.Located =>
        tagged(
          "located",
          "support_relation" -> str("evidence-support"),
          "source_support" -> support(located.support)
        )
      case unlocated: SourceSupportStatus.Unlocated =>
        tagged("unlocated", "reason" -> str(unlocated.reason.toString))),
    "axis_membership" -> array(
      value.axisMembership.toVector.sortBy(_._1.value).map { (axis, role) =>
        obj(
          "axis" -> str(axis.value),
          "role" -> (role match
            case BundleRole.Part(id)    => tagged("part", "bundle" -> str(id.value))
            case BundleRole.Composition => tagged("composition"))
        )
      }
    ),
    "support_coverage" -> (value.supportCoverage match
      case _: SupportCoverage.Complete => tagged("complete")
      case v: SupportCoverage.Partial  =>
        tagged("partial", "missing" -> strings(v.missing.toSortedSet.toVector.map(_.key)))
      case v: SupportCoverage.Unknown => tagged("unknown", "reason" -> str(v.reason.toString))),
    "propositional_scope" -> (value.propositional match
      case PropositionalScope.Declared           => tagged("declared")
      case PropositionalScope.Undeclared(reason) => tagged("undeclared", "reason" -> reason.asJson))
  )
  private def media(value: SourceRepresentation): Vector[SourceBundle] =
    value.bundles.toVector.collect { case b: BundleEntry.Media =>
      b.bundle
    } ++ value.composition.toVector.map(_.composed)
  def source(value: SourceRepresentation): Json =
    val bundles = value.bundles.toVector.map {
      case v: BundleEntry.Media      => bundle(v.bundle, "part")
      case v: BundleEntry.TextSource =>
        tagged("text-source", "canonical_text" -> str(v.canonicalText.hex))
    } ++ value.composition.toVector.map(c => bundle(c.composed, "composition"))
    obj(
      "digest" -> str(value.digest.hex),
      "view_fingerprint" -> str(value.viewFingerprint.checksum.hex),
      "scope_digest" -> str(value.scopeDigest.hex),
      "target_id_scheme" -> str("target-id/source-node-ref/v1"),
      "bundles" -> array(bundles.sortBy(print)),
      "axes" -> array(media(value).map(v => axis(v.primaryAxis)).sortBy(print)),
      "coordinate_mappings" -> array(media(value).flatMap(_.mappings).map(mapping).sortBy(print)),
      "composition" -> optional(value.composition)(c =>
        obj(
          "identity" -> str(c.composed.identity.hex),
          "parts" -> strings(c.parts.toVector.map(_.identity.hex))
        )
      ),
      "targets" -> array(value.targets.sortBy(_.ref.key).map(target))
    )

  /** Rebuild physical evidence against complete checked bundles before the full context match. */
  def validatePhysical(json: Json, expected: SourceRepresentation): Result[Unit] =
    def readSpans(value: Json): Result[SpanSet] =
      read[Vector[Json]](value)
        .flatMap(_.traverse { r =>
          for
            a <- field[Int](r, "start")
            b <- field[Int](r, "end_exclusive")
            span <- domain(TextSpan.of(a, b))
            optionalUnit <- field[Json](r, "unit").flatMap(j =>
              readOptional(j)(v => read[String](v).flatMap(s => domain(SurfaceUnitId.from(s))))
            )
          yield SpanRef(optionalUnit, span)
        })
        .flatMap(refs =>
          SpanSet
            .of(refs)
            .toRight(MappingCodecError.Wire(CodecError.Decode("spans", "empty support")))
        )
    def lookupAxis(id: String): Result[PresentationAxis] = media(expected)
      .map(_.primaryAxis)
      .find(_.id.value == id)
      .toRight(MappingCodecError.ValueMismatch("support.axis"))
    def readInterval(value: Json): Result[PlaybackInterval] = for
      id <- field[String](value, "axis")
      axis <- lookupAxis(id)
      start <- field[Json](value, "start_tick").flatMap(decimal(_, "interval.start_tick"))
      end <- field[Json](value, "end_exclusive_tick").flatMap(
        decimal(_, "interval.end_exclusive_tick")
      )
      interval <- domain(PlaybackInterval.on(axis, start, end))
    yield interval
    def intervals(value: Json): Result[PlaybackIntervalSet] = read[Vector[Json]](value)
      .flatMap(_.traverse(readInterval))
      .flatMap(v => domain(PlaybackIntervalSet.of(v)))
    def readAnchor(value: Json): Result[EvidenceAnchor] = for
      kind <- field[String](value, "kind")
      b <- field[String](value, "bundle").flatMap(v => domain(SourceBundleId.from(v)))
      s <- field[String](value, "stream").flatMap(v => domain(StreamId.from(v)))
      result <- kind match
        case "text" =>
          field[Json](value, "spans").flatMap(readSpans).map(EvidenceAnchor.Text(b, s, _))
        case "media-time" =>
          for
            a <- field[String](value, "axis").flatMap(v => domain(PresentationAxisId.from(v)))
            spans <- field[Json](value, "intervals").flatMap(intervals)
          yield EvidenceAnchor.MediaTime(b, s, a, spans)
        case "media-point" =>
          for
            id <- field[String](value, "axis")
            axis <- lookupAxis(id)
            tick <- field[Json](value, "tick").flatMap(decimal(_, "anchor.tick"))
            at <- domain(PlaybackInstant.on(axis, tick))
          yield EvidenceAnchor.MediaPoint(b, s, at)
        case "shot" =>
          for
            shot <- field[String](value, "shot").flatMap(v => domain(ShotId.from(v)))
            at <- field[Json](value, "interval").flatMap(readInterval)
          yield EvidenceAnchor.Shot(b, s, shot, at)
        case "track" =>
          for
            track <- field[String](value, "track").flatMap(v => domain(TrackId.from(v)))
            at <- field[Json](value, "intervals").flatMap(intervals)
          yield EvidenceAnchor.Track(b, s, track, at)
        case other => invalid("anchor.kind", s"unknown anchor $other")
    yield result
    def rebuild(value: Json): Result[TypedSupport] = status(value).flatMap {
      case "text"     => field[Json](value, "spans").flatMap(readSpans).map(TypedSupport.Text(_))
      case "anchored" =>
        for
          identity <- field[String](value, "bundle_identity")
          bundle <- media(expected)
            .find(_.identity.hex == identity)
            .toRight(MappingCodecError.ValueMismatch("support.bundle_identity"))
          anchors <- field[Vector[Json]](value, "anchors").flatMap(_.traverse(readAnchor))
          support <- domain(EvidenceSupport.of(bundle, anchors))
        yield TypedSupport.Anchored(support)
      case other => invalid("source_support", s"unknown support $other")
    }
    field[Vector[Json]](json, "targets").flatMap(_.traverse_ { row =>
      field[Json](row, "support_status").flatMap { physical =>
        status(physical).flatMap {
          case "unlocated" => Right(())
          case "located"   =>
            for
              relation <- field[String](physical, "support_relation")
              _ <- Either.cond(
                relation == "evidence-support",
                (),
                MappingCodecError.Reserved(relation)
              )
              value <- field[Json](physical, "source_support")
              rebuilt <- rebuild(value)
              _ <- exact(value, support(rebuilt), "source_support")
            yield ()
          case other => invalid("support_status", s"unknown status $other")
        }
      }
    })

  /** Tick-shaped fields reject JSON numbers even before the expected-source comparison. */
  def exactCoordinates(value: Json): Result[Unit] =
    val coordinates = Set("start_tick", "end_exclusive_tick", "tick", "numerator", "denominator")
    def loop(value: Json, path: String): Result[Unit] = value.asObject match
      case Some(fields) =>
        fields.toVector.traverse_ { (key, child) =>
          val next = s"$path.$key"
          if coordinates(key) then decimal(child, next).map(_ => ())
          else if key == "origin" then readOptional(child)(decimal(_, s"$next.value")).map(_ => ())
          else loop(child, next)
        }
      case None =>
        value.asArray.toVector.flatten.zipWithIndex.traverse_((child, i) =>
          loop(child, s"$path[$i]")
        )
    loop(value, "source")
