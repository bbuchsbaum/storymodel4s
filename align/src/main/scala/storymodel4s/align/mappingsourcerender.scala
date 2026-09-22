package storymodel4s.align

import storymodel4s.core.*
import storymodel4s.features.{MalformedReason, MissingReason, UndefinedReason}

/** Complete mapping-side identity renders, preserving strings and tagged optional values. */
private[align] object MappingSourceRender:
  import MappingRender.{optional, sequence}

  def missing(value: MissingReason): String = value match
    case MissingReason.Custom(ns, label) => sequence(Vector("custom", ns, label))
    case MissingReason.Malformed(MalformedReason.Custom(ns, label)) =>
      sequence(Vector("malformed-custom", ns, label))
    case MissingReason.Undefined(UndefinedReason.Custom(ns, label)) =>
      sequence(Vector("undefined-custom", ns, label))
    case other => other.toString
  def propositional(value: PropositionalScope): String = value match
    case PropositionalScope.Declared           => "declared"
    case PropositionalScope.Undeclared(reason) => sequence(Vector("undeclared", missing(reason)))
  def scope(nodes: Vector[NodeSummary]): Checksum = MappingRender.digest(
    Vector(
      "mapping-source-scope/v1",
      sequence(nodes.sortBy(_.ref.key).map { n =>
        sequence(
          Vector(
            n.ref.key,
            propositional(n.propositional),
            optional(n.predicate),
            optional(n.outcome),
            optional(n.cause),
            sequence(
              n.participants.map(p =>
                sequence(Vector(p.role.toString, p.label, sequence(p.aliases.toVector.sorted)))
              )
            ),
            sequence(n.locations),
            n.context.toString,
            n.polarity.toString,
            n.modality.toString
          )
        )
      })
    )
  )
  def spans(value: SpanSet): String = sequence(value.refs.toVector.map { ref =>
    sequence(
      Vector(
        optional(ref.unit.map(_.value)),
        ref.span.start.toString,
        ref.span.endExclusive.toString
      )
    )
  })
  def interval(value: PlaybackInterval): String = sequence(
    Vector(value.axis.value, value.start.toString, value.endExclusive.toString)
  )
  def support(value: TypedSupport): String = value match
    case TypedSupport.Text(value)     => sequence(Vector("text", spans(value)))
    case TypedSupport.Anchored(value) =>
      sequence(
        Vector(
          "anchored",
          value.bundleIdentity.hex,
          sequence(value.anchors.toVector.map {
            case EvidenceAnchor.Text(bundle, stream, value) =>
              sequence(Vector("text", bundle.value, stream.value, spans(value)))
            case EvidenceAnchor.MediaTime(bundle, stream, axis, values) =>
              sequence(
                Vector(
                  "media-time",
                  bundle.value,
                  stream.value,
                  axis.value,
                  sequence(values.intervals.toVector.map(interval))
                )
              )
            case EvidenceAnchor.MediaPoint(bundle, stream, at) =>
              sequence(
                Vector("media-point", bundle.value, stream.value, at.axis.value, at.at.toString)
              )
            case EvidenceAnchor.Shot(bundle, stream, shot, at) =>
              sequence(Vector("shot", bundle.value, stream.value, shot.value, interval(at)))
            case EvidenceAnchor.Track(bundle, stream, track, values) =>
              sequence(
                Vector(
                  "track",
                  bundle.value,
                  stream.value,
                  track.value,
                  sequence(values.intervals.toVector.map(interval))
                )
              )
          }.sorted)
        )
      )
  def rational(value: ExactRational): String = sequence(
    Vector(value.numerator.toString, value.denominator.toString)
  )
  def extent(value: AxisExtent): String = value match
    case v: AxisExtent.TextChars     => sequence(Vector("text", v.length.toString))
    case v: AxisExtent.PlaybackTicks =>
      sequence(
        Vector("ticks", v.start.toString, v.endExclusive.toString, rational(v.timebase.scale))
      )
    case AxisExtent.Unknown => "unknown"
  def sourceKind(value: SourceKind): String = value match
    case SourceKind.Custom(ns, label) => sequence(Vector("custom", ns, label))
    case other                        => other.toString
  def streamKind(value: StreamKind): String = value match
    case StreamKind.Custom(ns, label) => sequence(Vector("custom", ns, label))
    case other                        => other.toString
  def axis(value: PresentationAxis): String = sequence(
    Vector(
      value.id.value,
      value.bundle.value,
      optional(value.edition.map(_.value)),
      value.kind.toString,
      sourceKind(value.sourceKind),
      extent(value.extent),
      optional(value.timebase.map(t => rational(t.scale))),
      value.fingerprint.hex
    )
  )
  def stream(value: SourceStream): String = sequence(
    Vector(
      value.id.value,
      streamKind(value.kind),
      value.checksum.hex,
      value.nativeAxis.value,
      extent(value.extent),
      optional(value.timebase.map(t => rational(t.scale))),
      sequence(value.derivedFrom.map(_.value))
    )
  )
  def receipt(value: SourceDerivationReceipt): String = sequence(
    Vector(
      value.algorithm,
      value.parameters,
      sequence(value.inputChecksums.map(_.hex)),
      value.identity.hex,
      value.bindingIdentity.hex
    )
  )
  def mapping(value: CheckedMapping): String =
    val fields = value match
      case v: ClockRepair =>
        Vector(
          "clock-repair",
          v.relation.sourceAxis.value,
          v.relation.targetAxis.value,
          rational(v.scale),
          rational(v.offset)
        )
      case v: TrackComposition =>
        Vector(
          "track-composition",
          v.relation.sourceAxis.value,
          v.relation.targetAxis.value,
          sequence(
            v.segments.toVector
              .map(s =>
                sequence(Vector(s.occurrence.value, interval(s.source), interval(s.target)))
              )
              .sorted
          )
        )
      case v: EditionCorrespondence =>
        Vector(
          "edition-correspondence",
          v.relation.sourceAxis.value,
          v.relation.targetAxis.value,
          v.sourceEdition.value,
          v.targetEdition.value,
          sequence(v.pairs.toVector.map((a, b) => sequence(Vector(a.value, b.value))).sorted)
        )
    sequence(fields :+ receipt(value.receipt))
  def bundle(value: SourceBundle): String = sequence(
    Vector(
      value.id.value,
      value.identity.hex,
      optional(value.edition.map(_.value)),
      sourceKind(value.sourceKind),
      axis(value.primaryAxis),
      sequence(value.streams.sortBy(_.id.value).map(stream)),
      sequence(value.authorityTracks.map(_.value)),
      sequence(value.mappings.map(mapping).sorted)
    )
  )
  def bundleEntry(value: BundleEntry): String = value match
    case m: BundleEntry.Media      => sequence(Vector("media", bundle(m.bundle)))
    case t: BundleEntry.TextSource => sequence(Vector("text-source", t.canonicalText.hex))
  def target(value: MappingTarget): String = sequence(
    Vector(
      value.ref.key,
      value.level.toString,
      optional(value.parent.map(_.key)),
      value.sourceSupport match
        case s: SourceSupportStatus.Located   => sequence(Vector("located", support(s.support)))
        case s: SourceSupportStatus.Unlocated => sequence(Vector("unlocated", s.reason.toString))
      ,
      sequence(value.axisMembership.toVector.sortBy(_._1.value).map { case (axis, role) =>
        sequence(
          Vector(
            axis.value,
            role match
              case BundleRole.Part(id)    => sequence(Vector("part", id.value))
              case BundleRole.Composition => "composition"
          )
        )
      }),
      value.supportCoverage match
        case _: SupportCoverage.Complete => "complete"
        case c: SupportCoverage.Partial  =>
          sequence(Vector("partial", sequence(c.missing.toSortedSet.toVector.map(_.key))))
        case c: SupportCoverage.Unknown => sequence(Vector("unknown", c.reason.toString))
      ,
      propositional(value.propositional)
    )
  )
