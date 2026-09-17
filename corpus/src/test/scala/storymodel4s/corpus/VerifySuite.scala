package storymodel4s.corpus

import cats.data.NonEmptyVector

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

  test("a manifest with no artifacts, or a duplicate, is refused at construction") {
    assertEquals(
      SourceManifest
        .of(
          corpus,
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
