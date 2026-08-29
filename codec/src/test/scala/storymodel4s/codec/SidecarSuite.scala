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
    assertEquals(manifest.expectedByteLength, 8L)
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

  test("Float32 quantization preserves raw Float bits and has a byte fixed point") {
    val samples = Vector(
      0.0,
      -0.0,
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
    assert(materializeErrors.nonEmpty)
    assert(witnessErrors.nonEmpty)
    assert(layoutErrors.nonEmpty)
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
