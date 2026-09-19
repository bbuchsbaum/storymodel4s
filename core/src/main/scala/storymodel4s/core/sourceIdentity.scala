package storymodel4s.core

/** Full identities added by D1A. Historical bundle/receipt IDs remain unchanged. */
private[core] object SourceIdentity:
  // Hash each field before joining: an embedded delimiter cannot move a field boundary.
  def digest(fields: Vector[String]): Checksum =
    ContentAddress.digest(fields.map(value => Checksum.ofText(value).hex))

  private def optional(value: Option[String]): Vector[String] = value match
    case None        => Vector("none")
    case Some(value) => Vector("some", value)

  private def rational(value: ExactRational): Vector[String] =
    Vector(value.numerator.toString, value.denominator.toString)

  private def extent(value: AxisExtent): Vector[String] = value match
    case text: AxisExtent.TextChars      => Vector("text", text.length.toString)
    case ticks: AxisExtent.PlaybackTicks =>
      Vector("ticks", ticks.start.toString, ticks.endExclusive.toString) ++
        rational(ticks.timebase.scale)
    case AxisExtent.Unknown => Vector("unknown")

  private def interval(value: PlaybackInterval): Vector[String] =
    Vector(value.axis.value, value.start.toString, value.endExclusive.toString)

  def receipt(value: SourceDerivationReceipt): Checksum =
    digest(
      Vector(
        "source-receipt-binding/v1",
        value.algorithm,
        value.parameters,
        value.inputChecksums.size.toString
      ) ++ value.inputChecksums.map(_.hex)
    )

  def mapping(value: CheckedMapping): Checksum =
    val payload = value match
      case repair: ClockRepair =>
        Vector(
          "clock-repair",
          repair.relation.sourceAxis.value,
          repair.relation.targetAxis.value
        ) ++
          rational(repair.scale) ++ rational(repair.offset)
      case composition: TrackComposition =>
        val segments = composition.segments.toVector.map { segment =>
          digest(
            Vector("segment", segment.occurrence.value) ++ interval(segment.source) ++
              interval(segment.target)
          ).hex
        }.sorted
        Vector(
          "track-composition",
          composition.relation.sourceAxis.value,
          composition.relation.targetAxis.value,
          segments.size.toString
        ) ++ segments
      case correspondence: EditionCorrespondence =>
        val pairs = correspondence.pairs.toVector.map { (source, target) =>
          digest(Vector("pair", source.value, target.value)).hex
        }.sorted
        Vector(
          "edition-correspondence",
          correspondence.relation.sourceAxis.value,
          correspondence.relation.targetAxis.value,
          correspondence.sourceEdition.value,
          correspondence.targetEdition.value,
          pairs.size.toString
        ) ++ pairs
    digest(Vector("checked-mapping/v1") ++ payload ++ Vector(value.receipt.bindingIdentity.hex))

  def bundle(value: SourceBundle): Checksum =
    val kind = value.sourceKind match
      case SourceKind.Custom(namespace, label) => Vector("Custom", namespace, label)
      case standard                            => Vector(standard.toString)
    val streams = value.streams.sortBy(_.id.value).map { stream =>
      val streamKind = stream.kind match
        case StreamKind.Custom(namespace, label) => Vector("Custom", namespace, label)
        case standard                            => Vector(standard.toString)
      digest(
        Vector("stream", stream.id.value, stream.checksum.hex, stream.nativeAxis.value) ++
          streamKind ++ extent(stream.extent) ++ optional(stream.timebase.map(_.scale.toString)) ++
          Vector(stream.derivedFrom.size.toString) ++ stream.derivedFrom.map(_.value)
      ).hex
    }
    digest(
      Vector("source-bundle/v1", value.id.value) ++ optional(value.edition.map(_.value)) ++ kind ++
        Vector(value.primaryAxis.fingerprint.hex, streams.size.toString) ++ streams ++
        Vector(value.authorityTracks.size.toString) ++ value.authorityTracks.map(_.value) ++
        Vector(value.mappings.size.toString) ++ value.mappings.map(_.identity.hex).sorted
    )

  def surface(value: SurfaceAtlas): Checksum =
    val units = value.units.sortBy(_.id.value).map { unit =>
      digest(
        Vector(
          "unit",
          unit.id.value,
          unit.kind.toString,
          unit.span.start.toString,
          unit.span.endExclusive.toString,
          unit.ordinal.toString
        ) ++ optional(unit.parent.map(_.value))
      ).hex
    }
    digest(
      Vector(
        "proposal-surface/v1",
        value.source.id.value,
        value.source.canonicalChecksum.hex,
        units.size.toString
      ) ++ units
    )
