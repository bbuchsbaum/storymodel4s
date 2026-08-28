package storymodel4s.codec

import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.syntax.*
import storymodel4s.core.*
import storymodel4s.features.*
import CanonicalPrimitives.*
import CoreCodecs.given
import FeatureCodecs.given

/** Closed evidence that a Scala numeric value type agrees with its runtime feature schema.
  *
  * Why: generic decoding must not let a scalar wire masquerade as `Vector[Double]`, or a vector
  * wire masquerade as `Double`, merely because the type parameter is erased.
  */
sealed trait SidecarValue[V]:
  private[codec] def dimension(schema: FeatureValueSchema): Either[CodecError, Int]
  private[codec] def toRow(value: V): Vector[Double]
  private[codec] def fromRow(row: Vector[Double]): Either[CodecError, V]

object SidecarValue:
  private val Path = "$.sidecarTrack.space.valueSchema"

  given scalar: SidecarValue[Double] with
    def dimension(schema: FeatureValueSchema): Either[CodecError, Int] = schema match
      case FeatureValueSchema.Scalar(_) => Right(1)
      case other                        => invalid(s"Double requires Scalar schema, found $other")
    def toRow(value: Double): Vector[Double] = Vector(value)
    def fromRow(row: Vector[Double]): Either[CodecError, Double] = row match
      case Vector(value) => Right(value)
      case _             => invalid(s"Double requires a dimension-one row, found ${row.size}")

  given vector: SidecarValue[Vector[Double]] with
    def dimension(schema: FeatureValueSchema): Either[CodecError, Int] = schema match
      case FeatureValueSchema.Vector(d) if d > 0 => Right(d)
      case FeatureValueSchema.Vector(d)          => invalid(s"non-positive vector dimension $d")
      case other => invalid(s"Vector[Double] requires Vector schema, found $other")
    def toRow(value: Vector[Double]): Vector[Double] = value
    def fromRow(row: Vector[Double]): Either[CodecError, Vector[Double]] = Right(row)

  private def invalid[A](detail: String): Either[CodecError, A] =
    Left(CodecError.Decode(Path, detail))

/** A logical feature track whose observed values are stored as checked sidecar row references.
  *
  * Why: the wire document must retain the true `FeatureSpace[V]`; substituting `FeatureRef` for `V`
  * in `FeatureTrack` would falsely claim that storage identities inhabit the numeric space.
  */
final case class SidecarTrack[T <: FeatureTarget, V] private (
    space: FeatureSpace[V],
    observations: Vector[FeatureObservation[T, FeatureRef]],
    derivation: Option[FeatureDerivation],
    provenance: TrackProvenance,
    manifest: SidecarManifest
)

object SidecarTrack:
  private val Path = "$.sidecarTrack"

  /** Check the logical track/manifest seam without reading the separately stored numeric bytes. */
  def validated[T <: FeatureTarget, V](
      space: FeatureSpace[V],
      observations: Vector[FeatureObservation[T, FeatureRef]],
      derivation: Option[FeatureDerivation],
      provenance: TrackProvenance,
      manifest: SidecarManifest
  )(using value: SidecarValue[V]): Either[CodecError, SidecarTrack[T, V]] =
    val targets = observations.map(o => o.target: FeatureTarget)

    for
      _ <- SidecarManifest.validated(manifest).left.map(CodecError.Domain.apply)
      dimension <- value.dimension(space.valueSchema)
      _ <- require(manifest.space == space.id, "manifest space differs from track space")
      _ <- require(
        manifest.dimension == dimension,
        s"manifest dimension ${manifest.dimension} differs from declared $dimension"
      )
      _ <- require(targets.distinct.size == targets.size, "duplicate targets")
      _ <- require(targets == targets.sorted, "observations not in canonical target order")
      _ <- require(
        observations.forall(o => o.coverage.forall(c => c.observed <= c.eligible)),
        "coverage observed exceeds eligible"
      )
      _ <- require(
        !derivation.exists(_.inputs.toVector.contains(space.id)),
        "derived track lists itself as an input"
      )
      _ <- derivation match
        case None    => Right(())
        case Some(d) =>
          FeatureDerivation.validated(d).left.map(CodecError.Domain.apply).map(_ => ())
      _ <- validateRefs(space.id, observations, manifest)
    yield new SidecarTrack(space, observations, derivation, provenance, manifest)

  private def validateRefs[T <: FeatureTarget](
      space: FeatureSpaceId,
      observations: Vector[FeatureObservation[T, FeatureRef]],
      manifest: SidecarManifest
  ): Either[CodecError, Unit] =
    val observed = observations.collect {
      case o @ FeatureObservation(_, Estimate.Observed(r, _), _, _) =>
        o -> r
    }
    val malformed = observed.zipWithIndex.collectFirst {
      case ((o, ref), _) if ref.target != o.target => "reference target differs from observation"
      case ((_, ref), _) if ref.space != space     => "reference space differs from track space"
      case ((_, ref), expected) if ref.row != expected =>
        s"observed rows must be compact; expected $expected, found ${ref.row}"
    }
    malformed match
      case Some(detail) => invalid(detail)
      case None         =>
        require(
          observed.size == manifest.rowCount,
          s"manifest rowCount ${manifest.rowCount} differs from observed count ${observed.size}"
        )

  private def require(condition: Boolean, detail: => String): Either[CodecError, Unit] =
    Either.cond(condition, (), CodecError.Decode(Path, detail))

  private def invalid[A](detail: String): Either[CodecError, A] =
    Left(CodecError.Decode(Path, detail))

  given [T <: FeatureTarget, V](using Encoder[T]): Encoder[SidecarTrack[T, V]] =
    Encoder.instance { track =>
      obj(
        "schemaVersion" -> SchemaVersions.Current.asJson,
        "space" -> track.space.asJson,
        "observations" -> track.observations.map(encodeObservation).asJson,
        "derivation" -> opt(track.derivation),
        "provenance" -> track.provenance.asJson,
        "manifest" -> track.manifest.asJson
      )
    }

  given [T <: FeatureTarget, V](using
      Decoder[T],
      SidecarValue[V]
  ): Decoder[SidecarTrack[T, V]] =
    Decoder.instance { c =>
      given Decoder[FeatureObservation[T, FeatureRef]] = Decoder.instance { oc =>
        for
          target <- field[T](oc, "target")
          estimate <- field[Estimate[FeatureRef]](oc, "estimate")
          support <- field[Option[SpanSet]](oc, "support")
          coverage <- field[Option[Coverage]](oc, "coverage")
        yield FeatureObservation(target, estimate, support, coverage)
      }
      for
        _ <- SchemaVersions.check(c)
        space <- field[FeatureSpace[V]](c, "space")
        observations <- field[Vector[FeatureObservation[T, FeatureRef]]](c, "observations")
        derivation <- field[Option[FeatureDerivation]](c, "derivation")
        provenance <- field[TrackProvenance](c, "provenance")
        manifest <- field[SidecarManifest](c, "manifest")
        track <- validated(space, observations, derivation, provenance, manifest).left
          .map(e => DecodingFailure(e.message, c.history))
      yield track
    }

  private def encodeObservation[T <: FeatureTarget](o: FeatureObservation[T, FeatureRef])(using
      Encoder[T]
  ): Json =
    obj(
      "target" -> o.target.asJson,
      "estimate" -> o.estimate.asJson,
      "support" -> opt(o.support),
      "coverage" -> opt(o.coverage)
    )

/** Checked byte-layout metadata for zero-copy sidecar readers.
  *
  * Why: Scala.js consumers need exact aligned offsets and strides without repeating overflow-prone
  * manifest arithmetic or coupling `NarrativeScene` to the binary codec.
  */
final case class SidecarLayout private (
    payloadOffset: Int,
    dtype: Dtype,
    dimension: Int,
    rowCount: Int,
    valueCount: Long,
    rowStride: Long,
    payloadByteLength: Long
):
  /** Exact byte offset of `row`, suitable for a typed-array or `DataView` reader. */
  def rowByteOffset(row: Int): Either[CodecError, Long] =
    Either.cond(
      row >= 0 && row < rowCount,
      payloadOffset.toLong + row.toLong * rowStride,
      CodecError.Decode("$.sidecar.layout", s"row $row outside [0, $rowCount)")
    )

object SidecarLayout:
  private[codec] def checked(
      payloadOffset: Int,
      dtype: Dtype,
      dimension: Int,
      rowCount: Int,
      valueCount: Long,
      rowStride: Long,
      payloadByteLength: Long
  ): SidecarLayout =
    new SidecarLayout(
      payloadOffset,
      dtype,
      dimension,
      rowCount,
      valueCount,
      rowStride,
      payloadByteLength
    )

/** Portable binary codec for typed numeric feature sidecars.
  *
  * Format `SM4SFT01`: eight ASCII magic/version bytes, an unsigned 64-bit little-endian payload
  * byte count, then finite row-major IEEE-754 values in the manifest dtype. The checksum covers the
  * complete file. It proves full-fetch integrity only, not confidentiality or block/range
  * integrity.
  */
object SidecarCodec:
  val Magic: String = "SM4SFT01"
  val HeaderBytes: Int = 16
  private val MagicBytes: Array[Byte] = Magic.toVector.map(_.toByte).toArray
  private val Path = "$.sidecar"

  /** Encode already ordered rows and return their content-addressed manifest plus exact bytes. */
  def encodeRows(
      space: FeatureSpaceId,
      dimension: Int,
      dtype: Dtype,
      rows: Vector[Vector[Double]]
  ): Either[CodecError, (SidecarManifest, Array[Byte])] =
    for
      payloadLength <- safePayloadLength(dimension, rows.size, bytesPerValue(dtype))
      _ <- validateRows(rows, dimension, dtype)
      bytes <- allocate(payloadLength)
      _ = writeHeader(bytes, payloadLength)
      _ = writeRows(bytes, rows, dtype)
      manifest = SidecarManifest(
        space,
        dimension,
        rows.size,
        dtype,
        Checksum.ofBytes(bytes),
        Layout.RowMajor
      )
      checked <- SidecarManifest.validated(manifest).left.map(CodecError.Domain.apply)
    yield checked -> bytes

  /** Check a manifest's exact offsets and sizes without reading a sidecar file. */
  def layout(manifest: SidecarManifest): Either[CodecError, SidecarLayout] =
    for
      _ <- SidecarManifest.validated(manifest).left.map(CodecError.Domain.apply)
      _ <- require(manifest.layout == Layout.RowMajor, "unsupported sidecar layout")
      payloadLength <- safePayloadLength(
        manifest.dimension,
        manifest.rowCount,
        manifest.bytesPerValue
      )
      valueCount = manifest.dimension.toLong * manifest.rowCount.toLong
      rowStride = manifest.dimension.toLong * manifest.bytesPerValue
    yield SidecarLayout.checked(
      HeaderBytes,
      manifest.dtype,
      manifest.dimension,
      manifest.rowCount,
      valueCount,
      rowStride,
      payloadLength
    )

  /** Validate header, manifest, exact length, full-file checksum, and every numeric payload value;
    * return checked offsets for zero-copy readers.
    */
  def validateBytes(
      manifest: SidecarManifest,
      bytes: Array[Byte]
  ): Either[CodecError, SidecarLayout] =
    for
      checked <- layout(manifest)
      _ <- require(bytes.length >= HeaderBytes, s"file shorter than $HeaderBytes-byte header")
      _ <- require(hasMagic(bytes), s"wrong magic/version; expected $Magic")
      declared <- readPayloadLength(bytes)
      _ <- require(
        declared == checked.payloadByteLength,
        s"header length $declared differs from manifest ${checked.payloadByteLength}"
      )
      _ <- require(
        bytes.length.toLong == checked.payloadOffset.toLong + checked.payloadByteLength,
        s"file length ${bytes.length} differs from expected " +
          s"${checked.payloadOffset.toLong + checked.payloadByteLength}"
      )
      _ <- require(
        Checksum.ofBytes(bytes) == manifest.checksum,
        "full-file checksum differs from manifest"
      )
      _ <- validatePayloadFinite(manifest, bytes)
    yield checked

  /** Decode finite rows. Float32 values are returned as their exact widened Double values. */
  def decodeRows(
      manifest: SidecarManifest,
      bytes: Array[Byte]
  ): Either[CodecError, Vector[Vector[Double]]] =
    validateBytes(manifest, bytes).map { checked =>
      Vector.tabulate(manifest.rowCount, manifest.dimension) { (row, column) =>
        val offset =
          checked.payloadOffset + (row.toLong * checked.rowStride).toInt +
            column * manifest.bytesPerValue
        manifest.dtype match
          case Dtype.Float32 => java.lang.Float.intBitsToFloat(readIntLE(bytes, offset)).toDouble
          case Dtype.Float64 => java.lang.Double.longBitsToDouble(readLongLE(bytes, offset))
      }
    }

  /** Store a vector track; observed rows are compact and Missing observations consume no bytes. */
  def encodeVectorTrack[T <: FeatureTarget](
      track: FeatureTrack[T, Vector[Double]],
      dtype: Dtype
  ): Either[CodecError, (SidecarTrack[T, Vector[Double]], Array[Byte])] =
    encodeTrack(track, dtype)

  /** Store a scalar track as a dimension-one numeric sidecar. */
  def encodeScalarTrack[T <: FeatureTarget](
      track: FeatureTrack[T, Double],
      dtype: Dtype
  ): Either[CodecError, (SidecarTrack[T, Double], Array[Byte])] =
    FeatureTrack
      .validatedScores(track)
      .left
      .map(CodecError.Domain.apply)
      .flatMap(encodeTrack(_, dtype))

  /** Verify and materialize a logical vector track through `FeatureTrack.validated`. */
  def materializeVectorTrack[T <: FeatureTarget](
      track: SidecarTrack[T, Vector[Double]],
      bytes: Array[Byte]
  ): Either[CodecError, FeatureTrack[T, Vector[Double]]] =
    materializeTrack(track, bytes)

  /** Verify and materialize a dimension-one logical scalar track. */
  def materializeScalarTrack[T <: FeatureTarget](
      track: SidecarTrack[T, Double],
      bytes: Array[Byte]
  ): Either[CodecError, FeatureTrack[T, Double]] =
    materializeTrack(track, bytes).flatMap(
      FeatureTrack.validatedScores(_).left.map(CodecError.Domain.apply)
    )

  private def encodeTrack[T <: FeatureTarget, V](
      track: FeatureTrack[T, V],
      dtype: Dtype
  )(using value: SidecarValue[V]): Either[CodecError, (SidecarTrack[T, V], Array[Byte])] =
    for
      checked <- FeatureTrack.validated(track).left.map(CodecError.Domain.apply)
      dimension <- value.dimension(checked.space.valueSchema)
      packed <- encodeRows(
        checked.space.id,
        dimension,
        dtype,
        checked.observed.map((_, observed) => value.toRow(observed))
      )
      (manifest, bytes) = packed
      proxy <- SidecarTrack.validated(
        checked.space,
        referenceObservations(checked.observations, checked.space.id),
        checked.derivation,
        checked.provenance,
        manifest
      )
    yield proxy -> bytes

  private def materializeTrack[T <: FeatureTarget, V](
      track: SidecarTrack[T, V],
      bytes: Array[Byte]
  )(using value: SidecarValue[V]): Either[CodecError, FeatureTrack[T, V]] =
    for
      _ <- value.dimension(track.space.valueSchema)
      rows <- decodeRows(track.manifest, bytes)
      observations <- materializeObservations(track.observations, rows, value.fromRow)
      logical <- FeatureTrack
        .validated(FeatureTrack(track.space, observations, track.derivation, track.provenance))
        .left
        .map(CodecError.Domain.apply)
    yield logical

  private def referenceObservations[T <: FeatureTarget, V](
      observations: Vector[FeatureObservation[T, V]],
      space: FeatureSpaceId
  ): Vector[FeatureObservation[T, FeatureRef]] =
    var row = 0
    observations.map { observation =>
      val estimate: Estimate[FeatureRef] = observation.estimate match
        case Estimate.Observed(_, credence) =>
          val ref = FeatureRef(observation.target, space, row)
          row += 1
          Estimate.Observed(ref, credence)
        case Estimate.Missing(reason) => Estimate.Missing(reason)
      FeatureObservation(observation.target, estimate, observation.support, observation.coverage)
    }

  private def materializeObservations[T <: FeatureTarget, V](
      observations: Vector[FeatureObservation[T, FeatureRef]],
      rows: Vector[Vector[Double]],
      value: Vector[Double] => Either[CodecError, V]
  ): Either[CodecError, Vector[FeatureObservation[T, V]]] =
    observations.foldLeft[Either[CodecError, Vector[FeatureObservation[T, V]]]](
      Right(Vector.empty)
    ) { (acc, observation) =>
      for
        built <- acc
        estimate <- observation.estimate match
          case Estimate.Missing(reason)         => Right(Estimate.Missing(reason))
          case Estimate.Observed(ref, credence) =>
            for
              row <- rows
                .lift(ref.row)
                .toRight(CodecError.Decode(Path, s"missing decoded row ${ref.row}"))
              decoded <- value(row)
            yield Estimate.Observed(decoded, credence)
      yield built :+ FeatureObservation(
        observation.target,
        estimate,
        observation.support,
        observation.coverage
      )
    }

  private def safePayloadLength(
      dimension: Int,
      rowCount: Int,
      valueBytes: Int
  ): Either[CodecError, Long] =
    if dimension <= 0 then invalid(s"non-positive dimension $dimension")
    else if rowCount < 0 then invalid(s"negative row count $rowCount")
    else
      val cells = dimension.toLong * rowCount.toLong
      if cells > Long.MaxValue / valueBytes then invalid("payload byte length overflows Long")
      else
        val length = cells * valueBytes
        if length > Int.MaxValue.toLong - HeaderBytes then
          invalid(s"payload $length exceeds portable Array[Byte] capacity")
        else Right(length)

  private def validateRows(
      rows: Vector[Vector[Double]],
      dimension: Int,
      dtype: Dtype
  ): Either[CodecError, Unit] =
    rows.zipWithIndex.collectFirst {
      case (row, index) if row.size != dimension =>
        s"row $index has dimension ${row.size}, expected $dimension"
      case (row, index) if row.exists(v => v.isNaN || v.isInfinite) =>
        s"row $index contains a non-finite value"
      case (row, index)
          if dtype == Dtype.Float32 && row.exists(v => v.toFloat.isNaN || v.toFloat.isInfinite) =>
        s"row $index overflows Float32"
    } match
      case Some(detail) => invalid(detail)
      case None         => Right(())

  private def validatePayloadFinite(
      manifest: SidecarManifest,
      bytes: Array[Byte]
  ): Either[CodecError, Unit] =
    val values = manifest.dimension.toLong * manifest.rowCount.toLong
    var index = 0L
    var bad = -1L
    while bad < 0 && index < values do
      val offset = HeaderBytes + (index * manifest.bytesPerValue).toInt
      val finite = manifest.dtype match
        case Dtype.Float32 =>
          val value = java.lang.Float.intBitsToFloat(readIntLE(bytes, offset))
          !value.isNaN && !value.isInfinite
        case Dtype.Float64 =>
          val value = java.lang.Double.longBitsToDouble(readLongLE(bytes, offset))
          !value.isNaN && !value.isInfinite
      if !finite then bad = index
      index += 1
    require(bad < 0, s"payload contains non-finite value at flat index $bad")

  private def allocate(payloadLength: Long): Either[CodecError, Array[Byte]] =
    val total = HeaderBytes.toLong + payloadLength
    if total > Int.MaxValue then invalid(s"file length $total exceeds portable Array capacity")
    else Right(new Array[Byte](total.toInt))

  private def writeHeader(bytes: Array[Byte], payloadLength: Long): Unit =
    var i = 0
    while i < MagicBytes.length do
      bytes(i) = MagicBytes(i)
      i += 1
    writeLongLE(bytes, 8, payloadLength)

  private def writeRows(bytes: Array[Byte], rows: Vector[Vector[Double]], dtype: Dtype): Unit =
    var offset = HeaderBytes
    rows.foreach(_.foreach { value =>
      dtype match
        case Dtype.Float32 =>
          writeIntLE(bytes, offset, java.lang.Float.floatToRawIntBits(value.toFloat))
          offset += 4
        case Dtype.Float64 =>
          writeLongLE(bytes, offset, java.lang.Double.doubleToRawLongBits(value))
          offset += 8
    })

  private def hasMagic(bytes: Array[Byte]): Boolean =
    MagicBytes.indices.forall(i => bytes(i) == MagicBytes(i))

  private def readPayloadLength(bytes: Array[Byte]): Either[CodecError, Long] =
    val value = readLongLE(bytes, 8)
    if value < 0 then invalid("payload length exceeds signed 64-bit range") else Right(value)

  private def bytesPerValue(dtype: Dtype): Int = dtype match
    case Dtype.Float32 => 4
    case Dtype.Float64 => 8

  private def writeIntLE(bytes: Array[Byte], offset: Int, value: Int): Unit =
    var i = 0
    while i < 4 do
      bytes(offset + i) = (value >>> (8 * i)).toByte
      i += 1

  private def readIntLE(bytes: Array[Byte], offset: Int): Int =
    var value = 0
    var i = 0
    while i < 4 do
      value |= (bytes(offset + i) & 0xff) << (8 * i)
      i += 1
    value

  private def writeLongLE(bytes: Array[Byte], offset: Int, value: Long): Unit =
    var i = 0
    while i < 8 do
      bytes(offset + i) = (value >>> (8 * i)).toByte
      i += 1

  private def readLongLE(bytes: Array[Byte], offset: Int): Long =
    var value = 0L
    var i = 0
    while i < 8 do
      value |= (bytes(offset + i) & 0xffL) << (8 * i)
      i += 1
    value

  private def require(condition: Boolean, detail: => String): Either[CodecError, Unit] =
    Either.cond(condition, (), CodecError.Decode(Path, detail))

  private def invalid[A](detail: String): Either[CodecError, A] =
    Left(CodecError.Decode(Path, detail))
