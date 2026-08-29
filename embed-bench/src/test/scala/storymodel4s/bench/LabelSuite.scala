package storymodel4s.bench

import munit.FunSuite

import storymodel4s.core.Checksum

/** Label discipline (protocol §7, ADR 0001 L6): only a verified frozen set under the current
  * protocol can yield `Calibrated`; the FREEZE rule fails closed on any checksum mismatch.
  */
class LabelSuite extends FunSuite:
  private val protocol = ProtocolDocument.pinned
  private val storyBytes = "not a story: a placeholder blob".getBytes("UTF-8")
  private val modelBytes = "{}".getBytes("UTF-8")
  private val store: Map[String, Array[Byte]] = Map(
    "stories/s1/text.txt" -> storyBytes,
    "stories/s1/model.json" -> modelBytes
  )

  private def manifest(protocolChecksum: Checksum = protocol, version: Int = 1): FrozenManifest =
    val m = FrozenManifest(
      setId = "f-20260828-PLACEHOLDER",
      protocolVersion = version,
      protocolChecksum = protocolChecksum,
      files = store.view.mapValues(Checksum.ofBytes).toMap,
      stories = Vector("s1"),
      partitions = Map("s1" -> Partition.Calibration)
    )
    m.copy(setId = s"f-20260828-${m.idSuffix}")

  private def reId(m: FrozenManifest): FrozenManifest = m.copy(setId = s"f-20260828-${m.idSuffix}")

  test("a conforming manifest over a matching store verifies") {
    val r = FrozenSet.verify(manifest(), store.get)
    assert(r.isRight, r.left.map(_.message))
    val origin = r.toOption.get.origin("s1")
    assert(origin.exists(_.partition == Partition.Calibration))
    assertEquals(r.toOption.get.origin("nope"), None)
  }

  test("FREEZE rule: a changed file fails closed with the recorded and actual checksums") {
    val tampered = store.updated("stories/s1/text.txt", "edited".getBytes("UTF-8"))
    FrozenSet.verify(manifest(), tampered.get) match
      case Left(FreezeError.ChecksumMismatch(_, path, recorded, actual)) =>
        assertEquals(path, "stories/s1/text.txt")
        assertEquals(recorded, Checksum.ofBytes(storyBytes))
        assertNotEquals(recorded, actual)
      case other => fail(s"expected ChecksumMismatch, got $other")
  }

  test("FREEZE rule: a missing file, a wrong set id, an empty set, a story without partition") {
    val missing = FrozenSet.verify(manifest(), store.removed("stories/s1/model.json").get)
    assert(missing.left.exists(_.isInstanceOf[FreezeError.MissingFile]), missing.toString)
    val badId = FrozenSet.verify(manifest().copy(setId = "f-20260828-deadbeef"), store.get)
    assert(badId.left.exists(_.isInstanceOf[FreezeError.SetIdMismatch]), badId.toString)
    val empty = FrozenSet.verify(reId(manifest().copy(stories = Vector.empty)), store.get)
    assert(empty.left.exists(_.isInstanceOf[FreezeError.EmptySet]), empty.toString)
    val noPart = FrozenSet.verify(reId(manifest().copy(partitions = Map.empty)), store.get)
    assert(noPart.left.exists(_.isInstanceOf[FreezeError.NoStoryPartition]), noPart.toString)
  }

  test("the manifest checksum is order-independent over its maps and changes with any field") {
    val a = manifest()
    val b = a.copy(files = a.files.toVector.reverse.toMap)
    assertEquals(a.checksum, b.checksum)
    assertNotEquals(a.checksum, a.copy(protocolVersion = 2).checksum)
    assertNotEquals(a.checksum, a.copy(partitions = Map("s1" -> Partition.UntouchedTest)).checksum)
  }

  private def caseWith(id: String, origin: Origin): BenchCase =
    WogDiagnostic.paraphraseCases.head.copy(id = id, origin = origin)

  test("a diagnostic case can never be Calibrated, whatever the protocol says") {
    val r =
      BenchReport.label(Vector(caseWith("d", WogDiagnostic.origin)), Vector.empty, protocol, 0L)
    r match
      case BenchReport.Diagnostic(DiagnosticReason.DiagnosticOrigin(ids), _, _) =>
        assertEquals(ids, Vector("d"))
      case other => fail(s"expected DiagnosticOrigin, got ${other.label}")
    assertEquals(r.label, "diagnostic")
  }

  test("one verified frozen set under the current protocol is Calibrated") {
    val set = FrozenSet.verify(manifest(), store.get).toOption.get
    val origin = set.origin("s1").get
    val r = BenchReport.label(Vector(caseWith("f", origin)), Vector.empty, protocol, 0L)
    assertEquals(r.label, "calibrated")
    r match
      case BenchReport.Calibrated(setId, p, _, _) =>
        assertEquals(setId, manifest().setId)
        assertEquals(p, protocol)
      case _ => fail("expected Calibrated")
  }

  test("protocol drift fails closed to Diagnostic with both checksums") {
    val set = FrozenSet.verify(manifest(), store.get).toOption.get
    val origin = set.origin("s1").get
    val edited = ProtocolDocument.checksumOf("the protocol, edited after the freeze")
    BenchReport.label(Vector(caseWith("f", origin)), Vector.empty, edited, 0L) match
      case BenchReport.Diagnostic(DiagnosticReason.ProtocolDrift(_, recorded, current), _, _) =>
        assertEquals(recorded, protocol)
        assertEquals(current, edited)
      case other => fail(s"expected ProtocolDrift, got ${other.label}")
  }

  test("a set frozen under another protocol version, or cases from two sets, are Diagnostic") {
    val v2 = FrozenSet.verify(manifest(version = 2), store.get).toOption.get.origin("s1").get
    BenchReport.label(Vector(caseWith("f", v2)), Vector.empty, protocol, 0L) match
      case BenchReport.Diagnostic(DiagnosticReason.ProtocolVersion(_, 2, 1), _, _) => ()
      case other => fail(s"expected ProtocolVersion, got ${other.label}")
    val one = FrozenSet.verify(manifest(), store.get).toOption.get.origin("s1").get
    val other = one.copy(setId = "f-20260828-other")
    BenchReport.label(
      Vector(caseWith("a", one), caseWith("b", other)),
      Vector.empty,
      protocol,
      0L
    ) match
      case BenchReport.Diagnostic(DiagnosticReason.MixedSets(ids), _, _) =>
        assertEquals(ids.size, 2)
      case r => fail(s"expected MixedSets, got ${r.label}")
  }

  test("no cases is Diagnostic(NoCases)") {
    BenchReport.label(Vector.empty, Vector.empty, protocol, 0L) match
      case BenchReport.Diagnostic(DiagnosticReason.NoCases, _, _) => ()
      case r => fail(s"expected NoCases, got ${r.label}")
  }
