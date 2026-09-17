package storymodel4s.corpus

import io.circe.Json
import storymodel4s.core.Checksum

import munit.FunSuite

/** Sidecars are inside the wall, not beside it.
  *
  * A `RecordRef` that merely named a path would reopen "verify one thing, read another" for exactly
  * the records that carry the crosswalks -- `timebase-repair`, `alias-map`, `annotation-lineage`.
  * These tests are the reason `RecordRef` names an `ArtifactId`.
  */
class SidecarSuite extends FunSuite:
  private val corpus = CorpusId.unsafe("sherlock")

  private final class MapStore(payloads: Map[ArtifactId, Array[Byte]]) extends ArtifactStore:
    def list: Vector[ArtifactId] = payloads.keys.toVector
    def bytes(id: ArtifactId): Either[IntakeRefusal, Array[Byte]] =
      payloads.get(id).toRight(IntakeRefusal.MissingArtifact(id))

  private val crosswalkBytes =
    """{"schema":"storymodel4s.sherlock.timebase-repair"}""".getBytes("UTF-8")
  private val annotationBytes = "segment\ttext\n1\tx\n".getBytes("UTF-8")

  private val crosswalkId = ArtifactId.unsafe("timebase-repair.json")
  private val annotationId = ArtifactId.unsafe("Sherlock_Segments_1000_NN_2017.tsv")
  private val recordId = RecordId.unsafe("annotation-raw-to-part-playback-v1")

  private def rec(id: ArtifactId, payload: Array[Byte], role: String) =
    ArtifactRecord(
      id,
      RelativeArtifactPath.from(s"sherlock/${id.value}").toOption.get,
      payload.length.toLong,
      Checksum.ofBytes(payload),
      role
    )

  private def manifest(records: Vector[RecordRef]): SourceManifest =
    SourceManifest
      .of(
        corpus,
        Vector(
          rec(crosswalkId, crosswalkBytes, "timebase-crosswalk"),
          rec(annotationId, annotationBytes, "annotation")
        ),
        records,
        AdmissionStatus(AdmissionState.Admitted, courtOpened = true, Vector.empty),
        ContentPolicy(false, false, Vector.empty),
        Vector.empty,
        Map.empty[String, Json]
      )
      .fold(f => fail(f.message), identity)

  private val ref = RecordRef(recordId, "storymodel4s.sherlock.timebase-repair", 2, crosswalkId)

  private def store =
    new MapStore(
      Map(crosswalkId -> crosswalkBytes.clone(), annotationId -> annotationBytes.clone())
    )

  private def verified(records: Vector[RecordRef]) =
    Verify.verify(manifest(records), store).fold(f => fail(f.head.message), identity)

  test("a sidecar resolves to bytes that were verified in this snapshot") {
    val v = verified(Vector(ref))
    val got = v
      .record(recordId, "storymodel4s.sherlock.timebase-repair", 2)
      .fold(f => fail(f.message), identity)
    assertEquals(got.id, crosswalkId)
    assertEquals(got.checksum, Checksum.ofBytes(crosswalkBytes))
  }

  test("a record naming an undeclared artifact is refused AT CONSTRUCTION") {
    val dangling = RecordRef(recordId, "s", 1, ArtifactId.unsafe("never-declared.json"))
    SourceManifest.of(
      corpus,
      Vector(rec(crosswalkId, crosswalkBytes, "x")),
      Vector(dangling),
      AdmissionStatus(AdmissionState.Proposed, false, Vector.empty),
      ContentPolicy(false, false, Vector.empty),
      Vector.empty,
      Map.empty[String, Json]
    ) match
      case Left(VerificationFailure.DanglingRecordRef(r, a)) =>
        assertEquals(r, recordId)
        assertEquals(a, ArtifactId.unsafe("never-declared.json"))
      case other => fail(s"expected DanglingRecordRef, got $other")
  }

  test("a sidecar's SCHEMA is checked inside the snapshot, not trusted from the reference") {
    val v = verified(Vector(ref))
    v.record(recordId, "storymodel4s.sherlock.SOMETHING-ELSE", 2) match
      case Left(VerificationFailure.WrongSchema(found)) =>
        assertEquals(found, "storymodel4s.sherlock.timebase-repair")
      case other => fail(s"expected WrongSchema, got $other")
  }

  test("a sidecar's VERSION is checked too") {
    val v = verified(Vector(ref))
    v.record(recordId, "storymodel4s.sherlock.timebase-repair", 1) match
      case Left(VerificationFailure.WrongSchemaVersion(found)) => assertEquals(found, 2)
      case other => fail(s"expected WrongSchemaVersion, got $other")
  }

  test("an unknown record id does not resolve") {
    val v = verified(Vector(ref))
    assert(v.record(RecordId.unsafe("no-such-record"), "x", 1).isLeft)
  }

  test("changed sidecar bytes fail the snapshot, so a sidecar cannot drift after declaration") {
    val tampered = new MapStore(
      Map(
        crosswalkId -> """{"schema":"storymodel4s.sherlock.timebase-repair","x":1}""".getBytes(
          "UTF-8"
        ),
        annotationId -> annotationBytes.clone()
      )
    )
    Verify.verify(manifest(Vector(ref)), tampered) match
      case Left(f) =>
        assert(
          f.exists {
            case _: VerificationFailure.ChecksumMismatch   => true
            case _: VerificationFailure.ByteLengthMismatch => true
            case _                                         => false
          },
          s"expected a byte failure, got ${f.toVector.map(_.message)}"
        )
      case Right(_) => fail("tampered sidecar bytes verified")
  }
