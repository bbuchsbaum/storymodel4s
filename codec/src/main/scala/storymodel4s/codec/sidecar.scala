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
  * in `FeatureTrack` would falsely claim that storage identities inhabit the numeric space. Float32
  * materialization currently leaves the logical derivation/space identity unchanged and records
  * quantization only in the manifest dtype; first-class storage-quantization provenance is an
  * explicit follow-up.
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

/** One complete SM4SFT02 block's derived global-row and byte range.
  *
  * Why: HTTP adapters need checked range coordinates without accepting offsets or lengths from an
  * attacker-controlled table.
  */
final case class SidecarBlockRange private[codec] (
    block: Int,
    firstRow: Int,
    rowCount: Int,
    byteOffset: Long,
    byteLength: Long
):
  def rowEndExclusive: Long = firstRow.toLong + rowCount.toLong
  def byteEndExclusive: Long = byteOffset + byteLength

/** Checked SM4SFT02 arithmetic derived only from the trusted manifest.
  *
  * Why: range readers must know the exact prelude and block ranges before inspecting file-supplied
  * bytes, with every multiplication and addition checked once in the portable codec.
  */
final case class BlockedSidecarLayout private (
    headerBytes: Int,
    dtype: Dtype,
    dimension: Int,
    rowCount: Int,
    rowsPerBlock: SidecarBlockRows,
    blockCount: Int,
    indexByteLength: Long,
    payloadOffset: Long,
    rowStride: Long,
    payloadByteLength: Long,
    fileByteLength: Long
):
  /** The exact initial byte range whose checksum is supplied by the manifest. */
  def preludeByteLength: Long = payloadOffset

  /** Derive one block's complete byte range; no file-supplied offset participates. */
  def blockRange(block: Int): Either[CodecError, SidecarBlockRange] =
    if block < 0 || block >= blockCount then
      Left(CodecError.Decode("$.sidecar.blocked.layout", s"block $block outside [0, $blockCount)"))
    else
      val first = block.toLong * rowsPerBlock.value.toLong
      val count = math.min(rowsPerBlock.value.toLong, rowCount.toLong - first)
      Right(
        new SidecarBlockRange(
          block,
          first.toInt,
          count.toInt,
          payloadOffset + first * rowStride,
          count * rowStride
        )
      )

  /** Map arbitrary global compact rows to the minimal canonical set of complete storage blocks. */
  def blocksForRows(rows: Iterable[Int]): Either[CodecError, Vector[SidecarBlockRange]] =
    rows.iterator.toVector.distinct.sorted
      .foldLeft[Either[CodecError, Vector[Int]]](
        Right(Vector.empty)
      ) { (acc, row) =>
        for
          blocks <- acc
          _ <- Either.cond(
            row >= 0 && row < rowCount,
            (),
            CodecError.Decode(
              "$.sidecar.blocked.layout",
              s"row $row outside [0, $rowCount)"
            )
          )
          block = row / rowsPerBlock.value
        yield if blocks.lastOption.contains(block) then blocks else blocks :+ block
      }
      .flatMap(_.foldLeft[Either[CodecError, Vector[SidecarBlockRange]]](Right(Vector.empty)) {
        (acc, block) =>
          for
            ranges <- acc
            range <- blockRange(block)
          yield ranges :+ range
      })

object BlockedSidecarLayout:
  private[codec] def checked(
      headerBytes: Int,
      dtype: Dtype,
      dimension: Int,
      rowCount: Int,
      rowsPerBlock: SidecarBlockRows,
      blockCount: Int,
      indexByteLength: Long,
      payloadOffset: Long,
      rowStride: Long,
      payloadByteLength: Long,
      fileByteLength: Long
  ): BlockedSidecarLayout =
    new BlockedSidecarLayout(
      headerBytes,
      dtype,
      dimension,
      rowCount,
      rowsPerBlock,
      blockCount,
      indexByteLength,
      payloadOffset,
      rowStride,
      payloadByteLength,
      fileByteLength
    )

/** A verified SM4SFT02 header and digest index rooted in the manifest's index checksum.
  *
  * Why: individual block checks are trustworthy only after the small prelude has been verified
  * against evidence that did not come from the sidecar file itself.
  */
final case class CheckedSidecarPrelude private[codec] (
    layout: BlockedSidecarLayout,
    private[codec] val blockDigests: Vector[Checksum]
):
  def blocksForRows(rows: Iterable[Int]): Either[CodecError, Vector[SidecarBlockRange]] =
    layout.blocksForRows(rows)

/** One independently verified SM4SFT02 block decoded at its global compact-row position.
  *
  * Why: a partial fetch must never masquerade as validation of the complete sidecar or a fully
  * materialized `FeatureTrack`.
  */
final case class CheckedSidecarBlock private[codec] (
    range: SidecarBlockRange,
    rows: Vector[Vector[Double]]
):
  /** Read a decoded row by its global compact index. */
  def row(globalRow: Int): Option[Vector[Double]] =
    Option.when(globalRow >= range.firstRow && globalRow < range.rowEndExclusive)(
      rows(globalRow - range.firstRow)
    )

/** Portable binary codec for typed numeric feature sidecars.
  *
  * Format `SM4SFT01`: eight ASCII magic/version bytes, an unsigned 64-bit little-endian payload
  * byte count, then finite row-major IEEE-754 values in the manifest dtype. The checksum covers the
  * complete file. Format `SM4SFT02` adds a manifest-rooted digest prelude and complete-row blocks
  * whose offsets are derived, never stored. Both prove integrity relative to a trusted manifest;
  * neither supplies authenticity, confidentiality, or safe non-public receipt identity.
  */
object SidecarCodec:
  val Magic: String = "SM4SFT01"
  val HeaderBytes: Int = 16
  val BlockedMagic: String = "SM4SFT02"
  val BlockedHeaderBytes: Int = 48
  val BlockDigestBytes: Int = 32
  private val MagicBytes: Array[Byte] = Magic.toVector.map(_.toByte).toArray
  private val BlockedMagicBytes: Array[Byte] = BlockedMagic.toVector.map(_.toByte).toArray
  private val BlockDomainBytes: Array[Byte] =
    "SM4SFT02/block/v1".toVector.map(_.toByte).toArray
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

  /** Encode one range-loadable monolith whose complete compact rows form independent blocks.
    *
    * Re-blocking is a physical rewrite: `rowsPerBlock` participates in both the bytes and the
    * manifest, so the same logical values written at another granularity have another identity.
    */
  def encodeBlockedRows(
      space: FeatureSpaceId,
      dimension: Int,
      dtype: Dtype,
      rows: Vector[Vector[Double]],
      rowsPerBlock: SidecarBlockRows
  ): Either[CodecError, (SidecarManifest, Array[Byte])] =
    for
      _ <- validateRows(rows, dimension, dtype)
      checkedLayout <- blockedLayoutFor(dimension, rows.size, dtype, rowsPerBlock)
      bytes <- allocateFile(checkedLayout.fileByteLength)
      _ = writeBlockedHeader(bytes, checkedLayout)
      _ = writeRowsAt(bytes, checkedLayout.payloadOffset.toInt, rows, dtype)
      digests <- (0 until checkedLayout.blockCount).foldLeft[
        Either[CodecError, Vector[Checksum]]
      ](Right(Vector.empty)) { (acc, block) =>
        for
          built <- acc
          range <- checkedLayout.blockRange(block)
          digest <- digestBlock(bytes, range)
        yield built :+ digest
      }
      _ = digests.zipWithIndex.foreach { (digest, block) =>
        writeChecksum(bytes, BlockedHeaderBytes + block * BlockDigestBytes, digest)
      }
      indexChecksum = Checksum.ofBytes(bytes.slice(0, checkedLayout.payloadOffset.toInt))
      manifest = SidecarManifest(
        space,
        dimension,
        rows.size,
        dtype,
        Checksum.ofBytes(bytes),
        Layout.BlockedRowMajor(rowsPerBlock, indexChecksum)
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

  /** Derive all SM4SFT02 ranges from the manifest, without trusting any file-supplied offset. */
  def blockedLayout(manifest: SidecarManifest): Either[CodecError, BlockedSidecarLayout] =
    for
      _ <- SidecarManifest.validated(manifest).left.map(CodecError.Domain.apply)
      rowsPerBlock <- manifest.layout match
        case Layout.BlockedRowMajor(rows, _) => Right(rows)
        case Layout.RowMajor                 => invalid("manifest is not BlockedRowMajor")
      checked <- blockedLayoutFor(
        manifest.dimension,
        manifest.rowCount,
        manifest.dtype,
        rowsPerBlock
      )
    yield checked

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

  /** Verify the exact SM4SFT02 header/index range against the checksum supplied by the manifest.
    *
    * The expected root is intentionally absent from the file format. A fetched prelude cannot
    * certify itself; its authority must arrive in the separately trusted manifest.
    */
  def validateBlockedPrelude(
      manifest: SidecarManifest,
      bytes: Array[Byte]
  ): Either[CodecError, CheckedSidecarPrelude] =
    for
      checked <- blockedLayout(manifest)
      expectedIndexChecksum <- manifest.layout match
        case Layout.BlockedRowMajor(_, checksum) => Right(checksum)
        case Layout.RowMajor                     => invalid("manifest is not BlockedRowMajor")
      _ <- require(
        checked.preludeByteLength <= Int.MaxValue.toLong,
        s"prelude ${checked.preludeByteLength} exceeds portable Array capacity"
      )
      _ <- require(
        bytes.length.toLong == checked.preludeByteLength,
        s"prelude length ${bytes.length} differs from expected ${checked.preludeByteLength}"
      )
      _ <- validateBlockedHeader(manifest, checked, bytes)
      _ <- require(
        Checksum.ofBytes(bytes) == expectedIndexChecksum,
        "header/index checksum differs from manifest"
      )
      digests <- (0 until checked.blockCount).foldLeft[
        Either[CodecError, Vector[Checksum]]
      ](Right(Vector.empty)) { (acc, block) =>
        for
          built <- acc
          digest <- readChecksum(bytes, BlockedHeaderBytes + block * BlockDigestBytes)
        yield built :+ digest
      }
    yield new CheckedSidecarPrelude(checked, digests)

  /** Verify and decode one complete block using only the checked prelude and this block's bytes. */
  def validateBlockedBlock(
      prelude: CheckedSidecarPrelude,
      block: Int,
      bytes: Array[Byte]
  ): Either[CodecError, CheckedSidecarBlock] =
    for
      range <- prelude.layout.blockRange(block)
      _ <- require(
        range.byteLength <= Int.MaxValue.toLong,
        s"block ${range.block} length ${range.byteLength} exceeds portable Array capacity"
      )
      _ <- require(
        bytes.length.toLong == range.byteLength,
        s"block ${range.block} length ${bytes.length} differs from expected ${range.byteLength}"
      )
      expected <- prelude.blockDigests
        .lift(block)
        .toRight(CodecError.Decode(Path, s"missing digest for block $block"))
      actual <- digestBlockPayload(bytes, 0, range)
      _ <- require(actual == expected, s"block $block checksum differs from checked index")
      _ <- validateFiniteValues(
        prelude.layout.dtype,
        bytes,
        0,
        range.rowCount.toLong * prelude.layout.dimension.toLong
      )
      rows = decodePayloadRows(
        prelude.layout.dtype,
        prelude.layout.dimension,
        range.rowCount,
        bytes,
        0
      )
    yield new CheckedSidecarBlock(range, rows)

  /** Validate a complete SM4SFT02 file, including its retained whole-file identity. */
  def validateBlockedBytes(
      manifest: SidecarManifest,
      bytes: Array[Byte]
  ): Either[CodecError, BlockedSidecarLayout] =
    validateBlockedFile(manifest, bytes).map(_._1.layout)

  /** Decode finite rows. Float32 values are returned as their exact widened Double values. */
  def decodeRows(
      manifest: SidecarManifest,
      bytes: Array[Byte]
  ): Either[CodecError, Vector[Vector[Double]]] =
    manifest.layout match
      case Layout.RowMajor =>
        validateBytes(manifest, bytes).map { checked =>
          decodePayloadRows(
            manifest.dtype,
            manifest.dimension,
            manifest.rowCount,
            bytes,
            checked.payloadOffset
          )
        }
      case Layout.BlockedRowMajor(_, _) =>
        validateBlockedFile(manifest, bytes).map(_._2.flatMap(_.rows))

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

  /** Store a vector track in independently verifiable complete-row blocks. */
  def encodeBlockedVectorTrack[T <: FeatureTarget](
      track: FeatureTrack[T, Vector[Double]],
      dtype: Dtype,
      rowsPerBlock: SidecarBlockRows
  ): Either[CodecError, (SidecarTrack[T, Vector[Double]], Array[Byte])] =
    encodeBlockedTrack(track, dtype, rowsPerBlock)

  /** Store a dimension-one scalar track in independently verifiable complete-row blocks. */
  def encodeBlockedScalarTrack[T <: FeatureTarget](
      track: FeatureTrack[T, Double],
      dtype: Dtype,
      rowsPerBlock: SidecarBlockRows
  ): Either[CodecError, (SidecarTrack[T, Double], Array[Byte])] =
    FeatureTrack
      .validatedScores(track)
      .left
      .map(CodecError.Domain.apply)
      .flatMap(encodeBlockedTrack(_, dtype, rowsPerBlock))

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

  private def encodeBlockedTrack[T <: FeatureTarget, V](
      track: FeatureTrack[T, V],
      dtype: Dtype,
      rowsPerBlock: SidecarBlockRows
  )(using value: SidecarValue[V]): Either[CodecError, (SidecarTrack[T, V], Array[Byte])] =
    for
      checked <- FeatureTrack.validated(track).left.map(CodecError.Domain.apply)
      dimension <- value.dimension(checked.space.valueSchema)
      packed <- encodeBlockedRows(
        checked.space.id,
        dimension,
        dtype,
        checked.observed.map((_, observed) => value.toRow(observed)),
        rowsPerBlock
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

  private def blockedLayoutFor(
      dimension: Int,
      rowCount: Int,
      dtype: Dtype,
      rowsPerBlock: SidecarBlockRows
  ): Either[CodecError, BlockedSidecarLayout] =
    val valueBytes = bytesPerValue(dtype).toLong
    for
      _ <- require(dimension > 0, s"non-positive dimension $dimension")
      _ <- require(rowCount >= 0, s"negative row count $rowCount")
      _ <- require(rowsPerBlock.value > 0, "rows per block must be positive")
      rowStride <- checkedMultiply(dimension.toLong, valueBytes, "row stride")
      payloadLength <- checkedMultiply(rowCount.toLong, rowStride, "payload byte length")
      blockCount =
        if rowCount == 0 then 0
        else 1 + (rowCount - 1) / rowsPerBlock.value
      indexLength <- checkedMultiply(blockCount.toLong, BlockDigestBytes.toLong, "index length")
      payloadOffset <- checkedAdd(BlockedHeaderBytes.toLong, indexLength, "payload offset")
      fileLength <- checkedAdd(payloadOffset, payloadLength, "file length")
    yield BlockedSidecarLayout.checked(
      BlockedHeaderBytes,
      dtype,
      dimension,
      rowCount,
      rowsPerBlock,
      blockCount,
      indexLength,
      payloadOffset,
      rowStride,
      payloadLength,
      fileLength
    )

  private def validateBlockedFile(
      manifest: SidecarManifest,
      bytes: Array[Byte]
  ): Either[CodecError, (CheckedSidecarPrelude, Vector[CheckedSidecarBlock])] =
    for
      checked <- blockedLayout(manifest)
      _ <- require(
        checked.fileByteLength <= Int.MaxValue.toLong,
        s"file ${checked.fileByteLength} exceeds portable Array capacity"
      )
      _ <- require(
        bytes.length.toLong == checked.fileByteLength,
        s"file length ${bytes.length} differs from expected ${checked.fileByteLength}"
      )
      _ <- validateBlockedHeader(manifest, checked, bytes)
      _ <- require(
        Checksum.ofBytes(bytes) == manifest.checksum,
        "full-file checksum differs from manifest"
      )
      prelude <- validateBlockedPrelude(manifest, bytes.slice(0, checked.payloadOffset.toInt))
      blocks <- (0 until checked.blockCount).foldLeft[
        Either[CodecError, Vector[CheckedSidecarBlock]]
      ](Right(Vector.empty)) { (acc, block) =>
        for
          built <- acc
          range <- checked.blockRange(block)
          blockBytes = bytes.slice(range.byteOffset.toInt, range.byteEndExclusive.toInt)
          verified <- validateBlockedBlock(prelude, block, blockBytes)
        yield built :+ verified
      }
    yield prelude -> blocks

  private def validateBlockedHeader(
      manifest: SidecarManifest,
      checked: BlockedSidecarLayout,
      bytes: Array[Byte]
  ): Either[CodecError, Unit] =
    for
      _ <- require(bytes.length >= BlockedHeaderBytes, "file shorter than SM4SFT02 header")
      _ <- require(hasBlockedMagic(bytes), s"wrong magic/version; expected $BlockedMagic")
      payloadBytes <- readNonNegativeLong(bytes, 8, "payload byte length")
      dimension <- readNonNegativeLong(bytes, 16, "dimension")
      rowCount <- readNonNegativeLong(bytes, 24, "row count")
      dtypeCode <- readNonNegativeLong(bytes, 32, "dtype code")
      rowsPerBlock <- readNonNegativeLong(bytes, 40, "rows per block")
      _ <- require(
        payloadBytes == checked.payloadByteLength,
        s"header payload $payloadBytes differs from manifest ${checked.payloadByteLength}"
      )
      _ <- require(dimension == manifest.dimension.toLong, "header dimension differs from manifest")
      _ <- require(rowCount == manifest.rowCount.toLong, "header row count differs from manifest")
      _ <- require(dtypeCode == codeOf(manifest.dtype), "header dtype differs from manifest")
      _ <- require(rowsPerBlock > 0L, "header rows per block is not positive")
      _ <- require(
        rowsPerBlock == checked.rowsPerBlock.value.toLong,
        "header rows per block differs from manifest"
      )
      expected <- checkedMultiply(rowCount, dimension, "header value count")
        .flatMap(checkedMultiply(_, bytesPerValue(manifest.dtype).toLong, "header payload"))
      _ <- require(
        payloadBytes == expected,
        "header payload is not exactly rowCount * dimension * dtype size"
      )
    yield ()

  private def checkedMultiply(left: Long, right: Long, label: String): Either[CodecError, Long] =
    if left < 0L || right < 0L then invalid(s"negative $label operand")
    else if left != 0L && right > Long.MaxValue / left then invalid(s"$label overflows Long")
    else Right(left * right)

  private def checkedAdd(left: Long, right: Long, label: String): Either[CodecError, Long] =
    if left < 0L || right < 0L then invalid(s"negative $label operand")
    else if left > Long.MaxValue - right then invalid(s"$label overflows Long")
    else Right(left + right)

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
    validateFiniteValues(manifest.dtype, bytes, HeaderBytes, values)

  private def validateFiniteValues(
      dtype: Dtype,
      bytes: Array[Byte],
      payloadOffset: Int,
      values: Long
  ): Either[CodecError, Unit] =
    var index = 0L
    var bad = -1L
    while bad < 0 && index < values do
      val offset = payloadOffset + (index * bytesPerValue(dtype)).toInt
      val finite = dtype match
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

  private def allocateFile(fileLength: Long): Either[CodecError, Array[Byte]] =
    if fileLength > Int.MaxValue then
      invalid(s"file length $fileLength exceeds portable Array capacity")
    else Right(new Array[Byte](fileLength.toInt))

  private def writeHeader(bytes: Array[Byte], payloadLength: Long): Unit =
    var i = 0
    while i < MagicBytes.length do
      bytes(i) = MagicBytes(i)
      i += 1
    writeLongLE(bytes, 8, payloadLength)

  private def writeBlockedHeader(bytes: Array[Byte], layout: BlockedSidecarLayout): Unit =
    var index = 0
    while index < BlockedMagicBytes.length do
      bytes(index) = BlockedMagicBytes(index)
      index += 1
    writeLongLE(bytes, 8, layout.payloadByteLength)
    writeLongLE(bytes, 16, layout.dimension.toLong)
    writeLongLE(bytes, 24, layout.rowCount.toLong)
    writeLongLE(bytes, 32, codeOf(layout.dtype))
    writeLongLE(bytes, 40, layout.rowsPerBlock.value.toLong)

  private def writeRows(bytes: Array[Byte], rows: Vector[Vector[Double]], dtype: Dtype): Unit =
    writeRowsAt(bytes, HeaderBytes, rows, dtype)

  private def writeRowsAt(
      bytes: Array[Byte],
      initialOffset: Int,
      rows: Vector[Vector[Double]],
      dtype: Dtype
  ): Unit =
    var offset = initialOffset
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

  private def hasBlockedMagic(bytes: Array[Byte]): Boolean =
    BlockedMagicBytes.indices.forall(i => bytes(i) == BlockedMagicBytes(i))

  private def readPayloadLength(bytes: Array[Byte]): Either[CodecError, Long] =
    val value = readLongLE(bytes, 8)
    if value < 0 then invalid("payload length exceeds signed 64-bit range") else Right(value)

  private def readNonNegativeLong(
      bytes: Array[Byte],
      offset: Int,
      label: String
  ): Either[CodecError, Long] =
    val value = readLongLE(bytes, offset)
    if value < 0L then invalid(s"$label exceeds signed 64-bit range") else Right(value)

  private def codeOf(dtype: Dtype): Long = dtype match
    case Dtype.Float32 => 1L
    case Dtype.Float64 => 2L

  private def decodePayloadRows(
      dtype: Dtype,
      dimension: Int,
      rowCount: Int,
      bytes: Array[Byte],
      payloadOffset: Int
  ): Vector[Vector[Double]] =
    Vector.tabulate(rowCount, dimension) { (row, column) =>
      val offset = payloadOffset + (row.toLong * dimension.toLong * bytesPerValue(dtype)).toInt +
        column * bytesPerValue(dtype)
      dtype match
        case Dtype.Float32 => java.lang.Float.intBitsToFloat(readIntLE(bytes, offset)).toDouble
        case Dtype.Float64 => java.lang.Double.longBitsToDouble(readLongLE(bytes, offset))
    }

  private def digestBlock(
      fileBytes: Array[Byte],
      range: SidecarBlockRange
  ): Either[CodecError, Checksum] =
    require(range.byteOffset <= Int.MaxValue.toLong, "block offset exceeds portable Array capacity")
      .flatMap(_ => digestBlockPayload(fileBytes, range.byteOffset.toInt, range))

  private def digestBlockPayload(
      bytes: Array[Byte],
      payloadOffset: Int,
      range: SidecarBlockRange
  ): Either[CodecError, Checksum] =
    for
      envelopeLength <- checkedAdd(
        BlockDomainBytes.length.toLong + 24L,
        range.byteLength,
        "block digest material length"
      )
      _ <- require(
        envelopeLength <= Int.MaxValue.toLong,
        s"block digest material $envelopeLength exceeds portable Array capacity"
      )
      _ <- require(
        payloadOffset >= 0 && payloadOffset.toLong + range.byteLength <= bytes.length.toLong,
        s"block ${range.block} payload lies outside supplied bytes"
      )
      material = new Array[Byte](envelopeLength.toInt)
      _ = System.arraycopy(BlockDomainBytes, 0, material, 0, BlockDomainBytes.length)
      _ = writeLongLE(material, BlockDomainBytes.length, range.block.toLong)
      _ = writeLongLE(material, BlockDomainBytes.length + 8, range.firstRow.toLong)
      _ = writeLongLE(material, BlockDomainBytes.length + 16, range.rowCount.toLong)
      _ = System.arraycopy(
        bytes,
        payloadOffset,
        material,
        BlockDomainBytes.length + 24,
        range.byteLength.toInt
      )
    yield Checksum.ofBytes(material)

  private def writeChecksum(bytes: Array[Byte], offset: Int, checksum: Checksum): Unit =
    var index = 0
    while index < BlockDigestBytes do
      val pair = checksum.hex.substring(index * 2, index * 2 + 2)
      bytes(offset + index) = Integer.parseInt(pair, 16).toByte
      index += 1

  private def readChecksum(bytes: Array[Byte], offset: Int): Either[CodecError, Checksum] =
    val digest = bytes.slice(offset, offset + BlockDigestBytes)
    Checksum.from(Sha256.hex(digest)).left.map(CodecError.Domain.apply)

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
