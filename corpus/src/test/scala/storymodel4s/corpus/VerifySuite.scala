package storymodel4s.corpus

import io.circe.Json
import storymodel4s.core.Checksum

import munit.FunSuite

class VerifySuite extends FunSuite:
  private val corpus = CorpusId.unsafe("friends")

  /** A store that KEEPS its arrays, so a caller can mutate what it already handed out. Real stores
    * do this: a cache, a memory-mapped file, a test double built the obvious way.
    */
  private final class RetainingStore(var payloads: Map[ArtifactId, Array[Byte]])
      extends ArtifactStore:
    def list: Vector[ArtifactId] = payloads.keys.toVector
    def bytes(id: ArtifactId): Either[IntakeRefusal, Array[Byte]] =
      payloads.get(id).toRight(IntakeRefusal.MissingArtifact(id))

  private def path(s: String) = RelativeArtifactPath.from(s).toOption.get

  private def record(id: String, payload: Array[Byte], role: String = "data"): ArtifactRecord =
    ArtifactRecord(
      ArtifactId.unsafe(id),
      path(s"friends/$id"),
      payload.length.toLong,
      Checksum.ofBytes(payload),
      role
    )

  private def manifestOf(
      artifacts: Vector[ArtifactRecord],
      records: Vector[RecordRef] = Vector.empty
  ): SourceManifest =
    SourceManifest
      .of(
        corpus,
        SourceManifest.Schema,
        SourceManifest.SchemaVersion,
        artifacts,
        records,
        AdmissionStatus(AdmissionState.Proposed, courtOpened = false, Vector("owner decision")),
        ContentPolicy(true, true, Vector("participant-recall-prose")),
        Vector("admission-court-opened"),
        Map("note" -> Json.fromString("synthetic"))
      )
      .fold(f => fail(f.message), identity)

  private val payload = "storyboard bytes".getBytes("UTF-8")
  private val rec = record("friendsStoryBoard.xlsx", payload)
  private def store = new RetainingStore(Map(rec.id -> payload.clone()))

  test("a correct snapshot verifies and carries the bytes it hashed") {
    val v = Verify.verify(manifestOf(Vector(rec)), store).fold(f => fail(f.head.message), identity)
    assertEquals(v.artifacts.size, 1)
    assertEquals(v.artifact(rec.id).get.checksum, Checksum.ofBytes(payload))
    assertEquals(v.artifact(rec.id).get.toArray.toVector, payload.toVector)
  }

  test("ALIASING ROUTE 1: the store cannot change what was verified afterwards") {
    val s = store
    val v = Verify.verify(manifestOf(Vector(rec)), s).fold(f => fail(f.head.message), identity)
    val before = v.artifact(rec.id).get.toArray.toVector
    // the store still holds the array it handed over, and now writes to it
    s.payloads(rec.id)(0) = 'X'.toByte
    assertEquals(v.artifact(rec.id).get.toArray.toVector, before)
    assertEquals(v.artifact(rec.id).get.checksum, Checksum.ofBytes(payload))
  }

  test("ALIASING ROUTE 2: a caller cannot write through the public accessor") {
    val v = Verify.verify(manifestOf(Vector(rec)), store).fold(f => fail(f.head.message), identity)
    val a = v.artifact(rec.id).get
    val handed = a.toArray
    handed(0) = 'X'.toByte
    assertEquals(a.toArray.toVector, payload.toVector)
    assertNotEquals(handed.toVector, a.toArray.toVector)
  }

  test("a byte-length mismatch is caught") {
    val wrong = rec.copy(byteLength = 999L)
    Verify.verify(manifestOf(Vector(wrong)), store) match
      case Left(f)  => assert(f.exists(_.isInstanceOf[VerificationFailure.ByteLengthMismatch]))
      case Right(_) => fail("expected a failure")
  }

  test("a checksum mismatch is caught even when the length is right") {
    val other = "storyboard bytez".getBytes("UTF-8")
    assertEquals(other.length, payload.length)
    val wrong = rec.copy(sha256 = Checksum.ofBytes(other))
    Verify.verify(manifestOf(Vector(wrong)), store) match
      case Left(f)  => assert(f.exists(_.isInstanceOf[VerificationFailure.ChecksumMismatch]))
      case Right(_) => fail("expected a failure")
  }

  test("declared-but-absent is caught") {
    val empty = new RetainingStore(Map.empty)
    Verify.verify(manifestOf(Vector(rec)), empty) match
      case Left(f)  => assert(f.exists(_ == VerificationFailure.MissingFromStore(rec.id)))
      case Right(_) => fail("expected a failure")
  }

  test("present-but-undeclared is caught -- the direction that needs an enumerable store") {
    val extra = ArtifactId.unsafe("unaccounted.xlsx")
    val s = new RetainingStore(Map(rec.id -> payload.clone(), extra -> "x".getBytes("UTF-8")))
    Verify.verify(manifestOf(Vector(rec)), s) match
      case Left(f)  => assert(f.exists(_ == VerificationFailure.NotInManifest(extra)))
      case Right(_) => fail("expected a failure")
  }

  test("every failure is reported, not just the first") {
    val extra = ArtifactId.unsafe("unaccounted.xlsx")
    val missing = record("absent.xlsx", "nope".getBytes("UTF-8"))
    val s = new RetainingStore(Map(rec.id -> payload.clone(), extra -> "x".getBytes("UTF-8")))
    Verify.verify(manifestOf(Vector(rec, missing)), s) match
      case Left(f)  => assert(f.length >= 2, s"expected several failures, got ${f.toVector}")
      case Right(_) => fail("expected failures")
  }

  test("a wrong schema or version is refused where the manifest is BUILT, not later") {
    def build(schema: String, version: Int) =
      SourceManifest.of(
        corpus,
        schema,
        version,
        Vector(rec),
        Vector.empty,
        AdmissionStatus(AdmissionState.Proposed, false, Vector.empty),
        ContentPolicy(false, false, Vector.empty),
        Vector.empty,
        Map.empty
      )
    assertEquals(
      build("some.other.schema", 2),
      Left(VerificationFailure.WrongSchema("some.other.schema"))
    )
    assertEquals(build(SourceManifest.Schema, 1), Left(VerificationFailure.WrongSchemaVersion(1)))
    assert(build(SourceManifest.Schema, SourceManifest.SchemaVersion).isRight)
  }

  test("a store that throws is a refusal, not an escaping exception") {
    val throwing = new ArtifactStore:
      def list: Vector[ArtifactId] = Vector(rec.id)
      def bytes(id: ArtifactId): Either[IntakeRefusal, Array[Byte]] =
        throw new RuntimeException("disk went away")
    Verify.verify(manifestOf(Vector(rec)), throwing) match
      case Left(f)  => assert(f.exists(_.isInstanceOf[VerificationFailure.Unreadable]))
      case Right(_) => fail("expected a failure")
  }

  test("a manifest with no artifacts, or a duplicate, is refused at construction") {
    assertEquals(
      SourceManifest
        .of(
          corpus,
          SourceManifest.Schema,
          SourceManifest.SchemaVersion,
          Vector.empty,
          Vector.empty,
          AdmissionStatus(AdmissionState.Proposed, false, Vector.empty),
          ContentPolicy(false, false, Vector.empty),
          Vector.empty,
          Map.empty
        ),
      Left(VerificationFailure.EmptyManifest)
    )
    assert(
      SourceManifest
        .of(
          corpus,
          SourceManifest.Schema,
          SourceManifest.SchemaVersion,
          Vector(rec, rec),
          Vector.empty,
          AdmissionStatus(AdmissionState.Proposed, false, Vector.empty),
          ContentPolicy(false, false, Vector.empty),
          Vector.empty,
          Map.empty
        )
        .isLeft
    )
  }

  test("ALIASING ROUTE 3: verified bytes cannot be rewritten through the stdlib") {
    val v = Verify.verify(manifestOf(Vector(rec)), store).fold(f => fail(f.head.message), identity)
    val a = v.artifact(rec.id).get
    val before = a.toArray.toVector
    // IArray is an opaque type over Array, and the stdlib hands the backing array straight back.
    // There is no accessor that returns the array, so the stdlib route has nothing to grab.
    val copy = a.toArray
    copy(0) = 99.toByte
    assertEquals(a.toArray.toVector, before, "verified bytes were rewritten after verification")
    assert(a.toArray ne a.toArray, "toArray must hand out a fresh copy each call")
    assertEquals(a.iterator.toVector, before)
    assertEquals(a.checksum, Checksum.ofBytes(payload))
  }

  /** One problem, one failure.
    *
    * A reviewer's mutation run found that removing the `filter(present.contains)` before hashing
    * SURVIVED, and judged it benign: a declared-but-absent artifact would simply be reported twice,
    * once as `MissingFromStore` and again as `Unreadable`. Benign is not the same as intended, and
    * an unpinned behaviour drifts -- a verification report that double-counts is a report someone
    * will eventually read as two problems.
    */
  test("a declared-but-absent artifact produces exactly ONE failure, not two") {
    val missing = record("absent.xlsx", "nope".getBytes("UTF-8"))
    Verify.verify(manifestOf(Vector(rec, missing)), store) match
      case Left(f) =>
        val forMissing = f.toVector.filter {
          case VerificationFailure.MissingFromStore(id) => id == missing.id
          case VerificationFailure.Unreadable(id, _)    => id == missing.id
          case _                                        => false
        }
        assertEquals(forMissing.size, 1, s"expected one failure, got ${forMissing.map(_.message)}")
        assertEquals(forMissing.head, VerificationFailure.MissingFromStore(missing.id))
      case Right(_) => fail("expected a failure")
  }

  /** Behaviours a reviewer confirmed CORRECT but which nothing pinned.
    *
    * A reviewer's attack suite established three properties of `verify` by inspection and probe:
    * bytes are fetched once per artifact, an unlisted id is never fetched at all, and one array
    * served under two ids yields two independent copies. All three were right. None had a test, and
    * a confirmed-but-unpinned behaviour is one that drifts on the next edit.
    */
  private final class CountingStore(payloads: Map[ArtifactId, Array[Byte]]) extends ArtifactStore:
    var fetches: Vector[ArtifactId] = Vector.empty
    def list: Vector[ArtifactId] = payloads.keys.toVector
    def bytes(id: ArtifactId): Either[IntakeRefusal, Array[Byte]] =
      fetches = fetches :+ id
      payloads.get(id).toRight(IntakeRefusal.MissingArtifact(id))

  test("verify fetches each artifact's bytes EXACTLY once") {
    val second = record("second.xlsx", "other bytes".getBytes("UTF-8"))
    val s = new CountingStore(
      Map(rec.id -> payload.clone(), second.id -> "other bytes".getBytes("UTF-8"))
    )
    Verify.verify(manifestOf(Vector(rec, second)), s).fold(f => fail(f.head.message), identity)
    assertEquals(s.fetches.size, 2)
    assertEquals(s.fetches.distinct.size, 2)
  }

  test("an artifact the store does not list is never FETCHED, only reported missing") {
    val absent = record("absent.xlsx", "nope".getBytes("UTF-8"))
    val s = new CountingStore(Map(rec.id -> payload.clone()))
    Verify.verify(manifestOf(Vector(rec, absent)), s) match
      case Left(f) =>
        assert(f.exists(_ == VerificationFailure.MissingFromStore(absent.id)))
        // reading a file the snapshot does not have is work, and worse, a second failure mode
        assert(!s.fetches.contains(absent.id), s"absent artifact was fetched: ${s.fetches}")
      case Right(_) => fail("expected a failure")
  }

  test("one array served under two ids yields two INDEPENDENT verified copies") {
    val shared = payload.clone()
    val a = record("a.xlsx", payload)
    val b = record("b.xlsx", payload)
    val s = new RetainingStore(Map(a.id -> shared, b.id -> shared))
    val v = Verify.verify(manifestOf(Vector(a, b)), s).fold(f => fail(f.head.message), identity)
    // mutating the store's single array must not move either verified copy
    shared(0) = 'Z'.toByte
    assertEquals(v.artifact(a.id).get.toArray.toVector, payload.toVector)
    assertEquals(v.artifact(b.id).get.toArray.toVector, payload.toVector)
    // and the two copies are not the same object
    assert(v.artifact(a.id).get.toArray ne v.artifact(b.id).get.toArray)
  }
