package storymodel4s.codec

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.core.*
import storymodel4s.features.*
import FeatureCodecs.given
import SidecarTrack.given

class SidecarSuite extends ScalaCheckSuite:

  private val vectorSpace = FeatureSpace[Vector[Double]](
    FeatureSpaceId.unsafe("space:sidecar-vector"),
    "sidecar vector",
    FeatureValueSchema.Vector(3),
    None,
    CodecGens.fingerprint,
    false
  )
  private val scalarSpace = FeatureSpace[Double](
    FeatureSpaceId.unsafe("space:sidecar-scalar"),
    "sidecar scalar",
    FeatureValueSchema.Scalar(Some("score")),
    Some("score"),
    CodecGens.fingerprint,
    false
  )
  private val provenance = TrackProvenance(CodecGens.provenance, None)
  private def target(i: Int): FeatureTarget =
    FeatureTarget.Sentence(SurfaceUnitId.unsafe(f"unit:$i%03d"))

  private val vectorTrack: FeatureTrack[FeatureTarget, Vector[Double]] = FeatureTrack.raw(
    vectorSpace,
    Vector(
      FeatureObservation(
        target(0),
        Estimate.Observed(
          Vector(1.0, -0.0, Float.MinPositiveValue.toDouble),
          Some(Credence.unsafeRaw(0.5))
        ),
        Some(SpanSet.one(TextSpan.unsafe(0, 2))),
        Some(Coverage.unsafe(3, 3))
      ),
      FeatureObservation(
        target(1),
        Estimate.Missing(MissingReason.ProviderAbstained),
        None,
        Some(Coverage.unsafe(3, 0))
      ),
      FeatureObservation(
        target(2),
        Estimate.Observed(Vector(-2.5, 0.25, Float.MaxValue.toDouble), None),
        None,
        Some(Coverage.unsafe(3, 3))
      )
    ),
    provenance
  )

  private val scalarTrack: FeatureTrack[FeatureTarget, Double] = FeatureTrack.raw(
    scalarSpace,
    Vector(
      FeatureObservation(target(0), Estimate.Observed(-0.0, None), None, None),
      FeatureObservation(target(1), Estimate.Missing(MissingReason.Excluded), None, None),
      FeatureObservation(target(2), Estimate.Observed(3.25, None), None, None)
    ),
    provenance
  )

  test("SM4SFT01 Float32 bytes have the exact versioned little-endian layout") {
    val (manifest, bytes) = SidecarCodec
      .encodeRows(vectorSpace.id, 2, Dtype.Float32, Vector(Vector(1.0, -2.5)))
      .toOption
      .get
    val expected =
      "SM4SFT01".toVector.map(_.toInt) ++
        Vector(8, 0, 0, 0, 0, 0, 0, 0) ++
        Vector(0, 0, 128, 63, 0, 0, 32, 192)
    assertEquals(bytes.map(_ & 0xff).toVector, expected)
    assertEquals(manifest.expectedByteLength, Right(8L))
    assertEquals(manifest.checksum, Checksum.ofBytes(bytes))
    val layout = SidecarCodec.validateBytes(manifest, bytes).toOption.get
    assertEquals(layout.payloadOffset, 16)
    assertEquals(layout.dtype, Dtype.Float32)
    assertEquals(layout.dimension, 2)
    assertEquals(layout.rowCount, 1)
    assertEquals(layout.valueCount, 2L)
    assertEquals(layout.rowStride, 8L)
    assertEquals(layout.payloadByteLength, 8L)
    assertEquals(layout.rowByteOffset(0), Right(16L))
    assert(layout.rowByteOffset(-1).isLeft)
    assert(layout.rowByteOffset(1).isLeft)
    assertEquals(SidecarCodec.decodeRows(manifest, bytes), Right(Vector(Vector(1.0, -2.5))))
  }

  test("SM4SFT02 has the exact independent golden header, placed digests, and payload") {
    val blockRows = SidecarBlockRows.from(1).toOption.get
    val (manifest, bytes) = SidecarCodec
      .encodeBlockedRows(
        vectorSpace.id,
        1,
        Dtype.Float32,
        Vector(Vector(1.0), Vector(2.0)),
        blockRows
      )
      .toOption
      .get
    val expectedHex =
      "534d345346543032080000000000000001000000000000000200000000000000" +
        "01000000000000000100000000000000872d990522c5f7f6fbd1d6c410f36b96" +
        "db7aa7bccb7efb187fb272682bdedb780792162eb715a032bd7c59fc3726cda1" +
        "c22290d55c3845bd90cb188b9a0f71b70000803f00000040"
    val checked = SidecarCodec.blockedLayout(manifest).toOption.get

    assertEquals(Sha256.hex(bytes), expectedHex)
    assertEquals(checked.headerBytes, 48)
    assertEquals(checked.blockCount, 2)
    assertEquals(checked.indexByteLength, 64L)
    assertEquals(checked.payloadOffset, 112L)
    assertEquals(checked.payloadByteLength, 8L)
    assertEquals(checked.fileByteLength, 120L)
    assertEquals(
      manifest.checksum,
      Checksum.unsafe("81b7112f298706759f69f8b0d3b6be304c9c4c7d72207e11630bb9fb17dc64d2")
    )
    manifest.layout match
      case Layout.BlockedRowMajor(rows, indexChecksum) =>
        assertEquals(rows, blockRows)
        assertEquals(
          indexChecksum,
          Checksum.unsafe(
            "e9a920aad4ce1b16f507faf55d74eee787a905b406eab6e07242f58b61ba7ced"
          )
        )
      case other => fail(s"expected blocked layout, found $other")
    assertEquals(
      Canonical.decode[SidecarManifest](Canonical.encode(manifest)),
      Right(manifest)
    )
    assertEquals(SidecarCodec.decodeRows(manifest, bytes), Right(Vector(Vector(1.0), Vector(2.0))))
  }

  test("one checked prelude makes each requested block independently verifiable") {
    val rows = Vector.tabulate(5)(i => Vector(i.toDouble, (i + 10).toDouble))
    val blockRows = SidecarBlockRows.from(2).toOption.get
    val (manifest, bytes) = SidecarCodec
      .encodeBlockedRows(vectorSpace.id, 2, Dtype.Float64, rows, blockRows)
      .toOption
      .get
    val layout = SidecarCodec.blockedLayout(manifest).toOption.get
    val preludeBytes = bytes.slice(0, layout.preludeByteLength.toInt)
    val prelude = SidecarCodec.validateBlockedPrelude(manifest, preludeBytes).toOption.get
    val requested = prelude.blocksForRows(Vector(4, 2, 3, 2)).toOption.get

    assertEquals(requested.map(_.block), Vector(1, 2))
    val second = layout.blockRange(1).toOption.get
    val secondBytes = blockBytes(bytes, second)
    val checkedSecond = SidecarCodec.validateBlockedBlock(prelude, 1, secondBytes).toOption.get
    assertEquals(checkedSecond.rows, rows.slice(2, 4))
    assertEquals(checkedSecond.row(2), Some(rows(2)))
    assertEquals(checkedSecond.row(4), None)

    val third = layout.blockRange(2).toOption.get
    val corruptedThird = blockBytes(bytes, third)
    corruptedThird(0) = (corruptedThird(0) ^ 1).toByte
    assert(SidecarCodec.validateBlockedBlock(prelude, 2, corruptedThird).isLeft)
    assertEquals(
      SidecarCodec.validateBlockedBlock(prelude, 1, secondBytes).map(_.rows),
      Right(rows.slice(2, 4))
    )
  }

  test("placed block digests reject swaps and manifest-rooted prelude rejects index forgery") {
    val rows = Vector(Vector(1.0), Vector(2.0), Vector(3.0), Vector(4.0))
    val blockRows = SidecarBlockRows.from(2).toOption.get
    val (manifest, bytes) = SidecarCodec
      .encodeBlockedRows(vectorSpace.id, 1, Dtype.Float64, rows, blockRows)
      .toOption
      .get
    val layout = SidecarCodec.blockedLayout(manifest).toOption.get
    val preludeBytes = bytes.slice(0, layout.preludeByteLength.toInt)
    val prelude = SidecarCodec.validateBlockedPrelude(manifest, preludeBytes).toOption.get
    val first = layout.blockRange(0).toOption.get
    val second = layout.blockRange(1).toOption.get
    val firstBytes = blockBytes(bytes, first)
    val secondBytes = blockBytes(bytes, second)

    assert(SidecarCodec.validateBlockedBlock(prelude, 0, secondBytes).isLeft)
    assert(SidecarCodec.validateBlockedBlock(prelude, 1, firstBytes).isLeft)

    val forgedPrelude = preludeBytes.clone()
    forgedPrelude(SidecarCodec.BlockedHeaderBytes) =
      (forgedPrelude(SidecarCodec.BlockedHeaderBytes) ^ 1).toByte
    assert(SidecarCodec.validateBlockedPrelude(manifest, forgedPrelude).isLeft)
  }

  test("block digest identity binds placement even when two payloads are byte-identical") {
    val repeated = Vector(Vector(1.0, -2.0), Vector(1.0, -2.0))
    val (manifest, bytes) = SidecarCodec
      .encodeBlockedRows(
        vectorSpace.id,
        2,
        Dtype.Float64,
        repeated,
        SidecarBlockRows.from(1).toOption.get
      )
      .toOption
      .get
    val layout = SidecarCodec.blockedLayout(manifest).toOption.get
    val firstDigest = bytes.slice(
      SidecarCodec.BlockedHeaderBytes,
      SidecarCodec.BlockedHeaderBytes + SidecarCodec.BlockDigestBytes
    )
    val secondDigest = bytes.slice(
      SidecarCodec.BlockedHeaderBytes + SidecarCodec.BlockDigestBytes,
      SidecarCodec.BlockedHeaderBytes + 2 * SidecarCodec.BlockDigestBytes
    )
    val firstPayload = blockBytes(bytes, layout.blockRange(0).toOption.get)
    val secondPayload = blockBytes(bytes, layout.blockRange(1).toOption.get)

    assert(firstPayload.sameElements(secondPayload))
    assert(!firstDigest.sameElements(secondDigest))
  }

  test("re-blocking the same logical rows changes physical identity") {
    val rows = Vector.tabulate(6)(i => Vector(i.toDouble, -i.toDouble))
    val one = SidecarCodec
      .encodeBlockedRows(
        vectorSpace.id,
        2,
        Dtype.Float64,
        rows,
        SidecarBlockRows.from(1).toOption.get
      )
      .toOption
      .get
    val three = SidecarCodec
      .encodeBlockedRows(
        vectorSpace.id,
        2,
        Dtype.Float64,
        rows,
        SidecarBlockRows.from(3).toOption.get
      )
      .toOption
      .get

    assertNotEquals(one._1.checksum, three._1.checksum)
    assertNotEquals(one._1.layout, three._1.layout)
    assert(!one._2.sameElements(three._2))
    assertEquals(SidecarCodec.decodeRows(one._1, one._2), Right(rows))
    assertEquals(SidecarCodec.decodeRows(three._1, three._2), Right(rows))
  }

  test("Float64 preserves raw bits at signed-zero, subnormal, normal, and finite boundaries") {
    val samples = Vector(
      0.0,
      -0.0,
      Double.MinPositiveValue,
      -Double.MinPositiveValue,
      java.lang.Double.longBitsToDouble(0x000fffffffffffffL),
      java.lang.Double.longBitsToDouble(0x0010000000000000L),
      Double.MaxValue,
      -Double.MaxValue
    )
    val (manifest, bytes) = SidecarCodec
      .encodeRows(vectorSpace.id, samples.size, Dtype.Float64, Vector(samples))
      .toOption
      .get
    val decoded = SidecarCodec.decodeRows(manifest, bytes).toOption.get.head
    assertEquals(
      decoded.map(java.lang.Double.doubleToRawLongBits),
      samples.map(java.lang.Double.doubleToRawLongBits)
    )
    val reencoded = SidecarCodec
      .encodeRows(vectorSpace.id, samples.size, Dtype.Float64, Vector(decoded))
      .toOption
      .get
      ._2
    assert(bytes.sameElements(reencoded))
  }

  test("Float32 quantization preserves raw Float bits, including silent signed underflow") {
    val samples = Vector(
      0.0,
      -0.0,
      Double.MinPositiveValue,
      -Double.MinPositiveValue,
      Float.MinPositiveValue.toDouble,
      -Float.MinPositiveValue.toDouble,
      java.lang.Float.intBitsToFloat(0x007fffff).toDouble,
      java.lang.Float.intBitsToFloat(0x00800000).toDouble,
      Float.MaxValue.toDouble,
      -Float.MaxValue.toDouble,
      1.00000006
    )
    val (manifest, bytes) = SidecarCodec
      .encodeRows(vectorSpace.id, samples.size, Dtype.Float32, Vector(samples))
      .toOption
      .get
    val decoded = SidecarCodec.decodeRows(manifest, bytes).toOption.get.head
    assertEquals(
      decoded.map(v => java.lang.Float.floatToRawIntBits(v.toFloat)),
      samples.map(v => java.lang.Float.floatToRawIntBits(v.toFloat))
    )
    assertEquals(decoded, samples.map(_.toFloat.toDouble))
    assertEquals(java.lang.Float.floatToRawIntBits(decoded(2).toFloat), 0)
    assertEquals(java.lang.Float.floatToRawIntBits(decoded(3).toFloat), Int.MinValue)
    val reencoded = SidecarCodec
      .encodeRows(vectorSpace.id, samples.size, Dtype.Float32, Vector(decoded))
      .toOption
      .get
      ._2
    assert(bytes.sameElements(reencoded))
  }

  test("vector SidecarTrack preserves logical metadata and explicit missingness") {
    val (proxy, bytes) = SidecarCodec.encodeVectorTrack(vectorTrack, Dtype.Float32).toOption.get
    assertEquals(proxy.space, vectorTrack.space)
    assertEquals(proxy.manifest.rowCount, 2)
    assertEquals(proxy.observations(1).estimate, Estimate.Missing(MissingReason.ProviderAbstained))
    assertEquals(proxy.observations.flatMap(_.estimate.toOption).map(_.row), Vector(0, 1))

    val wire = Canonical.encode(proxy)
    assertEquals(
      Canonical.decode[SidecarTrack[FeatureTarget, Vector[Double]]](wire),
      Right(proxy)
    )
    val materialized = SidecarCodec.materializeVectorTrack(proxy, bytes).toOption.get
    assertEquals(materialized.space, vectorTrack.space)
    assertEquals(materialized.derivation, vectorTrack.derivation)
    assertEquals(materialized.provenance, vectorTrack.provenance)
    assertEquals(materialized.observations(1), vectorTrack.observations(1))
    assertVectorBits(vectorTrack, materialized, Dtype.Float32)

    val (again, bytesAgain) =
      SidecarCodec.encodeVectorTrack(materialized, Dtype.Float32).toOption.get
    assertEquals(again, proxy)
    assert(bytes.sameElements(bytesAgain))
  }

  test("blocked SidecarTrack keeps global compact refs while Missing consumes no block row") {
    val blockRows = SidecarBlockRows.from(1).toOption.get
    val (proxy, bytes) = SidecarCodec
      .encodeBlockedVectorTrack(vectorTrack, Dtype.Float32, blockRows)
      .toOption
      .get
    val layout = SidecarCodec.blockedLayout(proxy.manifest).toOption.get

    assertEquals(proxy.observations.flatMap(_.estimate.toOption).map(_.row), Vector(0, 1))
    assertEquals(proxy.observations(1).estimate, Estimate.Missing(MissingReason.ProviderAbstained))
    assertEquals(layout.blockCount, 2)
    val materialized = SidecarCodec.materializeVectorTrack(proxy, bytes).toOption.get
    assertEquals(materialized.observations(1), vectorTrack.observations(1))
    assertVectorBits(vectorTrack, materialized, Dtype.Float32)
  }

  test("dimension-one scalar SidecarTrack preserves Missing and signed zero") {
    val (proxy, bytes) = SidecarCodec.encodeScalarTrack(scalarTrack, Dtype.Float64).toOption.get
    assertEquals(proxy.manifest.dimension, 1)
    assertEquals(proxy.manifest.rowCount, 2)
    val materialized = SidecarCodec.materializeScalarTrack(proxy, bytes).toOption.get
    assertEquals(materialized, scalarTrack)
    val first = materialized.observations.head.estimate.toOption.get
    assertEquals(
      java.lang.Double.doubleToRawLongBits(first),
      java.lang.Double.doubleToRawLongBits(-0.0)
    )
  }

  test("closed value evidence rejects scalar/vector cross-typed wire decoding") {
    val (scalarProxy, _) =
      SidecarCodec.encodeScalarTrack(scalarTrack, Dtype.Float64).toOption.get
    val scalarWire = Canonical.encode(scalarProxy)
    assert(
      Canonical.decode[SidecarTrack[FeatureTarget, Vector[Double]]](scalarWire).isLeft
    )

    val vectorOneSpace = FeatureSpace[Vector[Double]](
      FeatureSpaceId.unsafe("space:vector-one"),
      "one-dimensional vector",
      FeatureValueSchema.Vector(1),
      None,
      CodecGens.fingerprint,
      false
    )
    val vectorOne = FeatureTrack.raw[FeatureTarget, Vector[Double]](
      vectorOneSpace,
      Vector(FeatureObservation(target(0), Estimate.observed(Vector(1.0)), None, None)),
      provenance
    )
    val (vectorProxy, _) =
      SidecarCodec.encodeVectorTrack(vectorOne, Dtype.Float64).toOption.get
    val vectorWire = Canonical.encode(vectorProxy)
    assert(Canonical.decode[SidecarTrack[FeatureTarget, Double]](vectorWire).isLeft)
  }

  property("finite vector tracks round-trip under the declared dtype quantization") {
    forAll(vectorTrackGen, Gen.oneOf(Dtype.Float32, Dtype.Float64)) { (track, dtype) =>
      val (proxy, bytes) = SidecarCodec.encodeVectorTrack(track, dtype).toOption.get
      val decoded = SidecarCodec.materializeVectorTrack(proxy, bytes).toOption.get
      assertVectorBits(track, decoded, dtype)
      assertEquals(
        Canonical.decode[SidecarTrack[FeatureTarget, Vector[Double]]](Canonical.encode(proxy)),
        Right(proxy)
      )
      val (proxy2, bytes2) = SidecarCodec.encodeVectorTrack(decoded, dtype).toOption.get
      assertEquals(proxy2, proxy)
      assert(bytes.sameElements(bytes2))
    }
  }

  property("each SM4SFT02 block independently yields the original quantized compact rows") {
    forAll(
      vectorTrackGen,
      Gen.oneOf(Dtype.Float32, Dtype.Float64),
      Gen.choose(1, 6)
    ) { (track, dtype, rawBlockRows) =>
      val blockRows = SidecarBlockRows.from(rawBlockRows).toOption.get
      val (proxy, bytes) =
        SidecarCodec.encodeBlockedVectorTrack(track, dtype, blockRows).toOption.get
      val layout = SidecarCodec.blockedLayout(proxy.manifest).toOption.get
      val prelude = SidecarCodec
        .validateBlockedPrelude(proxy.manifest, bytes.slice(0, layout.preludeByteLength.toInt))
        .toOption
        .get
      val expected = track.observed.map { (_, row) =>
        dtype match
          case Dtype.Float32 => row.map(_.toFloat.toDouble)
          case Dtype.Float64 => row
      }
      val actual = (0 until layout.blockCount).toVector.flatMap { block =>
        val range = layout.blockRange(block).toOption.get
        SidecarCodec
          .validateBlockedBlock(prelude, block, blockBytes(bytes, range))
          .toOption
          .get
          .rows
      }

      assertEquals(actual, expected)
      assertEquals(SidecarCodec.decodeRows(proxy.manifest, bytes), Right(expected))
    }
  }

  test("binary decoder rejects every prohibited corruption mode") {
    val (manifest, bytes) = SidecarCodec
      .encodeRows(vectorSpace.id, 1, Dtype.Float64, Vector(Vector(1.0)))
      .toOption
      .get

    val wrongMagic = bytes.clone()
    wrongMagic(0) = 'X'.toByte
    assert(SidecarCodec.validateBytes(manifest, wrongMagic).isLeft)

    val wrongLength = bytes.clone()
    wrongLength(8) = 7
    assert(SidecarCodec.validateBytes(manifest, wrongLength).isLeft)
    assert(SidecarCodec.validateBytes(manifest, bytes.dropRight(1)).isLeft)
    assert(SidecarCodec.validateBytes(manifest, bytes :+ 0.toByte).isLeft)

    val badChecksum = bytes.clone()
    badChecksum(SidecarCodec.HeaderBytes) = (badChecksum(SidecarCodec.HeaderBytes) ^ 1).toByte
    assert(SidecarCodec.validateBytes(manifest, badChecksum).isLeft)

    val nan = bytes.clone()
    writeLongLE(nan, SidecarCodec.HeaderBytes, java.lang.Double.doubleToRawLongBits(Double.NaN))
    val nanManifest = manifest.copy(checksum = Checksum.ofBytes(nan))
    assert(SidecarCodec.validateBytes(nanManifest, nan).isLeft)

    val (floatManifest, floatBytes) = SidecarCodec
      .encodeRows(vectorSpace.id, 1, Dtype.Float32, Vector(Vector(1.0)))
      .toOption
      .get
    val infinity = floatBytes.clone()
    writeIntLE(infinity, SidecarCodec.HeaderBytes, 0x7f800000)
    val infinityManifest = floatManifest.copy(checksum = Checksum.ofBytes(infinity))
    assert(SidecarCodec.validateBytes(infinityManifest, infinity).isLeft)

    val unsignedOverflow = bytes.clone()
    unsignedOverflow(15) = 0x80.toByte
    assert(SidecarCodec.validateBytes(manifest, unsignedOverflow).isLeft)

    val enormous = manifest.copy(dimension = Int.MaxValue, rowCount = Int.MaxValue)
    assert(SidecarCodec.validateBytes(enormous, Array.emptyByteArray).isLeft)
  }

  test("SM4SFT02 rejects inconsistent headers before trusting their sizes") {
    val blockRows = SidecarBlockRows.from(2).toOption.get
    val (manifest, bytes) = SidecarCodec
      .encodeBlockedRows(
        vectorSpace.id,
        2,
        Dtype.Float64,
        Vector(Vector(1.0, 2.0), Vector(3.0, 4.0), Vector(5.0, 6.0)),
        blockRows
      )
      .toOption
      .get
    val layout = SidecarCodec.blockedLayout(manifest).toOption.get
    val prelude = bytes.slice(0, layout.preludeByteLength.toInt)

    Vector(
      8 -> 47L,
      16 -> 3L,
      24 -> 4L,
      32 -> 1L,
      40 -> 0L,
      40 -> 3L
    ).foreach { (offset, replacement) =>
      val forged = prelude.clone()
      writeLongLE(forged, offset, replacement)
      val forgedManifest = withIndexChecksum(manifest, Checksum.ofBytes(forged))
      assert(
        SidecarCodec.validateBlockedPrelude(forgedManifest, forged).isLeft,
        s"accepted forged header field at $offset = $replacement"
      )
    }

    assert(SidecarCodec.validateBlockedPrelude(manifest, prelude.dropRight(1)).isLeft)
    assert(SidecarCodec.validateBlockedPrelude(manifest, prelude :+ 0.toByte).isLeft)
    assert(
      SidecarCodec
        .validateBlockedBlock(
          SidecarCodec.validateBlockedPrelude(manifest, prelude).toOption.get,
          -1,
          Array.emptyByteArray
        )
        .isLeft
    )
  }

  test("SM4SFT02 rejects non-finite values even under recomputed structural digests") {
    val blockRows = SidecarBlockRows.from(1).toOption.get
    val (manifest, original) = SidecarCodec
      .encodeBlockedRows(vectorSpace.id, 1, Dtype.Float64, Vector(Vector(1.0)), blockRows)
      .toOption
      .get
    val layout = SidecarCodec.blockedLayout(manifest).toOption.get
    val range = layout.blockRange(0).toOption.get
    val forged = original.clone()
    writeLongLE(forged, range.byteOffset.toInt, java.lang.Double.doubleToRawLongBits(Double.NaN))
    val forgedBlock = blockBytes(forged, range)
    val digest = testBlockDigest(range, forgedBlock)
    writeChecksum(forged, SidecarCodec.BlockedHeaderBytes, digest)
    val forgedIndexChecksum = Checksum.ofBytes(forged.slice(0, layout.preludeByteLength.toInt))
    val forgedManifest = withIndexChecksum(
      manifest.copy(checksum = Checksum.ofBytes(forged)),
      forgedIndexChecksum
    )
    val checkedPrelude = SidecarCodec
      .validateBlockedPrelude(
        forgedManifest,
        forged.slice(0, layout.preludeByteLength.toInt)
      )
      .toOption
      .get

    assert(SidecarCodec.validateBlockedBlock(checkedPrelude, 0, forgedBlock).isLeft)
    assert(SidecarCodec.validateBlockedBytes(forgedManifest, forged).isLeft)
  }

  test("blocked offset arithmetic accepts the last safe file and rejects the next") {
    val dimension = Int.MaxValue
    val rowStride = dimension.toLong * 8L
    val perRowWithIndex = rowStride + SidecarCodec.BlockDigestBytes.toLong
    val lastSafeRows = ((Long.MaxValue - SidecarCodec.BlockedHeaderBytes) / perRowWithIndex).toInt
    val blockRows = SidecarBlockRows.from(1).toOption.get
    val base = SidecarManifest(
      vectorSpace.id,
      dimension,
      lastSafeRows,
      Dtype.Float64,
      Checksum.ofText("last-safe-blocked-file"),
      Layout.BlockedRowMajor(blockRows, Checksum.ofText("last-safe-index"))
    )
    val lastSafe = SidecarCodec.blockedLayout(base).toOption.get
    val firstOverflow = base.copy(rowCount = lastSafeRows + 1)

    assert(lastSafe.fileByteLength <= Long.MaxValue)
    assert(Long.MaxValue - lastSafe.fileByteLength < perRowWithIndex)
    assert(SidecarManifest.validated(firstOverflow).isRight)
    assert(SidecarCodec.blockedLayout(firstOverflow).isLeft)
  }

  test("encoder rejects ragged, non-finite, and Float32-overflowing rows") {
    assert(
      SidecarCodec.encodeRows(vectorSpace.id, 2, Dtype.Float64, Vector(Vector(1.0))).isLeft
    )
    assert(
      SidecarCodec
        .encodeRows(vectorSpace.id, 1, Dtype.Float64, Vector(Vector(Double.NaN)))
        .isLeft
    )
    assert(
      SidecarCodec
        .encodeRows(vectorSpace.id, 1, Dtype.Float64, Vector(Vector(Double.PositiveInfinity)))
        .isLeft
    )
    assert(
      SidecarCodec
        .encodeRows(vectorSpace.id, 1, Dtype.Float32, Vector(Vector(Double.MaxValue)))
        .isLeft
    )
    assert(SidecarCodec.encodeRows(vectorSpace.id, 0, Dtype.Float32, Vector.empty).isLeft)
  }

  test("SidecarTrack rejects false target/space bindings and non-compact or orphan rows") {
    val (proxy, _) = SidecarCodec.encodeVectorTrack(vectorTrack, Dtype.Float64).toOption.get
    val first = proxy.observations.head
    val ref = first.estimate.toOption.get

    def check(observations: Vector[FeatureObservation[FeatureTarget, FeatureRef]], rows: Int) =
      SidecarTrack.validated(
        proxy.space,
        observations,
        proxy.derivation,
        proxy.provenance,
        proxy.manifest.copy(rowCount = rows)
      )

    val wrongTarget = first.copy(
      estimate = Estimate.Observed(ref.copy(target = target(9)), None)
    ) +: proxy.observations.tail
    assert(check(wrongTarget, 2).isLeft)

    val wrongSpace = first.copy(
      estimate = Estimate.Observed(ref.copy(space = FeatureSpaceId.unsafe("space:other")), None)
    ) +: proxy.observations.tail
    assert(check(wrongSpace, 2).isLeft)

    val gap = first.copy(estimate = Estimate.Observed(ref.copy(row = 1), None)) +:
      proxy.observations.tail
    assert(check(gap, 2).isLeft)
    assert(check(proxy.observations, 3).isLeft)

    val duplicate = proxy.observations.updated(1, proxy.observations(1).copy(target = target(0)))
    assert(check(duplicate, 2).isLeft)
    assert(check(proxy.observations.reverse, 2).isLeft)
  }

  test("SidecarTrack cannot bypass its checked factory through apply or copy") {
    val constructorErrors = typeCheckErrors("""
      import storymodel4s.codec.*
      import storymodel4s.features.*
      val forged = SidecarTrack[FeatureTarget, Double](???, ???, ???, ???, ???)
    """)
    val copyErrors = typeCheckErrors("""
      import storymodel4s.codec.*
      import storymodel4s.features.*
      def forge(t: SidecarTrack[FeatureTarget, Double]): SidecarTrack[FeatureTarget, Double] =
        t.copy(manifest = ???)
    """)
    assert(constructorErrors.nonEmpty)
    assert(copyErrors.nonEmpty)

    val materializeErrors = typeCheckErrors("""
      import storymodel4s.codec.*
      import storymodel4s.features.*
      def wrongScalar(t: SidecarTrack[FeatureTarget, Double], bytes: Array[Byte]) =
        SidecarCodec.materializeVectorTrack(t, bytes)
      def wrongVector(t: SidecarTrack[FeatureTarget, Vector[Double]], bytes: Array[Byte]) =
        SidecarCodec.materializeScalarTrack(t, bytes)
    """)
    val witnessErrors = typeCheckErrors("""
      import storymodel4s.codec.*
      val forged: SidecarValue[String] = new SidecarValue[String] {}
      val illegal = summon[SidecarValue[Int]]
    """)
    val layoutErrors = typeCheckErrors("""
      import storymodel4s.codec.*
      import storymodel4s.features.*
      val forged = SidecarLayout(16, Dtype.Float32, 1, 1, 1L, 4L, 4L)
      def recast(layout: SidecarLayout): SidecarLayout = layout.copy(rowStride = 0L)
    """)
    val blockedErrors = typeCheckErrors("""
      package storymodel4s.codecprobe
      import storymodel4s.codec.*
      import storymodel4s.features.*
      val range = SidecarBlockRange(0, 0, 1, 80L, 4L)
      val block = CheckedSidecarBlock(range, Vector(Vector(1.0)))
      def recast(checked: CheckedSidecarBlock): CheckedSidecarBlock =
        checked.copy(rows = Vector.empty)
    """)
    assert(materializeErrors.nonEmpty)
    assert(
      witnessErrors.exists(_.message.toLowerCase.contains("sealed")),
      witnessErrors.map(_.message).mkString("; ")
    )
    assert(layoutErrors.nonEmpty)
    assert(blockedErrors.nonEmpty)
  }

  private val safeDouble: Gen[Double] = Gen.oneOf(
    Gen.choose(-1e20, 1e20),
    Gen.oneOf(
      0.0,
      -0.0,
      Float.MinPositiveValue.toDouble,
      java.lang.Float.intBitsToFloat(0x007fffff).toDouble,
      Float.MaxValue.toDouble,
      -Float.MaxValue.toDouble
    )
  )

  private val vectorTrackGen: Gen[FeatureTrack[FeatureTarget, Vector[Double]]] = for
    dimension <- Gen.choose(1, 8)
    count <- Gen.choose(0, 10)
    observed <- Gen.listOfN(count, Gen.oneOf(true, false))
    values <- Gen.listOfN(count, Gen.listOfN(dimension, safeDouble).map(_.toVector))
  yield
    val space: FeatureSpace[Vector[Double]] =
      vectorSpace.copy(valueSchema = FeatureValueSchema.Vector(dimension))
    val observations = (0 until count).toVector.map { index =>
      val estimate: Estimate[Vector[Double]] =
        if observed(index) then Estimate.Observed(values(index), None)
        else Estimate.Missing(MissingReason.ProviderAbstained)
      FeatureObservation(target(index), estimate, None, None)
    }
    FeatureTrack.raw(space, observations, provenance)

  private def assertVectorBits(
      expected: FeatureTrack[FeatureTarget, Vector[Double]],
      actual: FeatureTrack[FeatureTarget, Vector[Double]],
      dtype: Dtype
  ): Unit =
    assertEquals(actual.space, expected.space)
    assertEquals(actual.derivation, expected.derivation)
    assertEquals(actual.provenance, expected.provenance)
    expected.observations.zip(actual.observations).foreach { (left, right) =>
      assertEquals(right.target, left.target)
      assertEquals(right.support, left.support)
      assertEquals(right.coverage, left.coverage)
      (left.estimate, right.estimate) match
        case (Estimate.Missing(a), Estimate.Missing(b))           => assertEquals(b, a)
        case (Estimate.Observed(a, ac), Estimate.Observed(b, bc)) =>
          assertEquals(bc, ac)
          dtype match
            case Dtype.Float32 =>
              assertEquals(
                b.map(v => java.lang.Float.floatToRawIntBits(v.toFloat)),
                a.map(v => java.lang.Float.floatToRawIntBits(v.toFloat))
              )
            case Dtype.Float64 =>
              assertEquals(
                b.map(java.lang.Double.doubleToRawLongBits),
                a.map(java.lang.Double.doubleToRawLongBits)
              )
        case other => fail(s"missingness changed: $other")
    }

  private def blockBytes(bytes: Array[Byte], range: SidecarBlockRange): Array[Byte] =
    bytes.slice(range.byteOffset.toInt, range.byteEndExclusive.toInt)

  private def withIndexChecksum(
      manifest: SidecarManifest,
      checksum: Checksum
  ): SidecarManifest =
    manifest.layout match
      case Layout.BlockedRowMajor(rows, _) =>
        manifest.copy(layout = Layout.BlockedRowMajor(rows, checksum))
      case other => fail(s"expected blocked layout, found $other")

  private def testBlockDigest(
      range: SidecarBlockRange,
      payload: Array[Byte]
  ): Checksum =
    val domain = "SM4SFT02/block/v1".toVector.map(_.toByte).toArray
    val material = new Array[Byte](domain.length + 24 + payload.length)
    System.arraycopy(domain, 0, material, 0, domain.length)
    writeLongLE(material, domain.length, range.block.toLong)
    writeLongLE(material, domain.length + 8, range.firstRow.toLong)
    writeLongLE(material, domain.length + 16, range.rowCount.toLong)
    System.arraycopy(payload, 0, material, domain.length + 24, payload.length)
    Checksum.ofBytes(material)

  private def writeChecksum(bytes: Array[Byte], offset: Int, checksum: Checksum): Unit =
    (0 until SidecarCodec.BlockDigestBytes).foreach { index =>
      bytes(offset + index) =
        Integer.parseInt(checksum.hex.substring(index * 2, index * 2 + 2), 16).toByte
    }

  private def writeLongLE(bytes: Array[Byte], offset: Int, value: Long): Unit =
    var index = 0
    while index < 8 do
      bytes(offset + index) = (value >>> (8 * index)).toByte
      index += 1

  private def writeIntLE(bytes: Array[Byte], offset: Int, value: Int): Unit =
    var index = 0
    while index < 4 do
      bytes(offset + index) = (value >>> (8 * index)).toByte
      index += 1
