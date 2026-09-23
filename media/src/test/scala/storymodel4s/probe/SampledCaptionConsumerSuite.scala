package storymodel4s.probe

import java.nio.charset.StandardCharsets
import munit.FunSuite
import storymodel4s.core.{Checksum, DomainError, EditionId, ObservationAuthority}
import storymodel4s.media.*

/** AC4 slice S1 seam, written from outside `storymodel4s.media` before the implementation (AGENTS.md
  * T6). A caption request addresses frames by *sample index* (a position in the frame bytes); time
  * comes only from each sample's *presentation ordinal* in the probe's packet index. The two are
  * never interchangeable. F0 v1 supplies the probe; its recorded caption request already shows 12 of
  * 45 frames, so the sampled set here selects exactly those 12 and the recorded worker text is
  * re-addressed to sample indices 0-11. That rewritten outcome tests the join, not the worker.
  */
class SampledCaptionConsumerSuite extends FunSuite:

  private def resource(name: String): Array[Byte] =
    val in = getClass.getResourceAsStream(s"/f0/$name")
    assert(in != null, s"missing test resource $name")
    try in.readAllBytes()
    finally in.close()

  private def text(name: String): String = new String(resource(name), StandardCharsets.UTF_8)

  private def right[A](e: Either[DomainError, A]): A =
    e.fold(err => fail(s"expected Right, got ${err.message}"), identity)

  private def refusedAt(e: Either[DomainError, Any], path: String): Unit =
    e match
      case Left(DomainError.InvariantViolation(p, _)) => assertEquals(p, path)
      case Left(other) => fail(s"refused at the wrong place: ${other.message}")
      case Right(v)    => fail(s"expected refusal at $path, got $v")

  private lazy val manifest: FixtureManifest =
    right(FixtureManifest.parse(text("f0-v1.manifest.json")))
  private lazy val probeEnvelope: ProbeEnvelope =
    right(ProbeEnvelope.parse(text("f0-v1.ffprobe-envelope.json")))
  private lazy val probe: MediaProbe =
    right(
      MediaProbe.join(
        manifest,
        right(manifest.verify(resource("f0-v1.mov"))),
        probeEnvelope.tool,
        probeEnvelope.args,
        right(
          FfprobeJson.parse(right(probeEnvelope.verifyStdout(resource(probeEnvelope.stdoutFile))))
        )
      )
    )
  private lazy val framesEnvelope: FramesEnvelope =
    right(FramesEnvelope.parse(text("f0-v1.frames-envelope.json")))
  private lazy val full: FrameSet =
    right(
      FrameSet.join(
        probe,
        framesEnvelope.streamIndex,
        framesEnvelope.geometry,
        framesEnvelope.tool,
        framesEnvelope.args,
        framesEnvelope.byteLength,
        framesEnvelope.framesSha256
      )
    )
  private lazy val envelope: CaptionEnvelope =
    right(CaptionEnvelope.parse(text("f0-v1.caption-envelope.json")))
  private lazy val recordedRequest: CaptionRequest =
    right(CaptionRequest.parse(text(envelope.requestFile)))
  private lazy val recordedOutcomeText: String =
    new String(right(envelope.verifyOutcome(resource(envelope.outcomeFile))), StandardCharsets.UTF_8)
  private lazy val f0: EditionId = right(EditionId.from(manifest.fixtureId))

  /** The presentation ordinals the recorded request showed, in order: [0,4,9,13,14,...,44]. */
  private lazy val shown: Vector[Int] = recordedRequest.extents.flatMap(_.ordinals)
  private val sampledSha: Checksum = Checksum.ofText("f0-v1 sampled frames (synthetic digest)")

  private def sampled(
      selected: Vector[Int] = shown,
      byteLength: Long = -1L
  ): Either[DomainError, SampledFrameSet] =
    SampledFrameSet.join(
      probe,
      framesEnvelope.streamIndex,
      framesEnvelope.geometry,
      framesEnvelope.tool,
      framesEnvelope.args,
      selected,
      if byteLength >= 0 then byteLength else framesEnvelope.geometry.frameBytes * selected.size,
      sampledSha
    )

  /** The recorded extents re-addressed to sample indices: shot k shows samples 4k..4k+3. */
  private lazy val sampledExtents: Vector[CaptionExtent] =
    recordedRequest.extents.zipWithIndex.map { (e, k) =>
      right(CaptionExtent.of(e.id, Vector.tabulate(e.ordinals.size)(i => 4 * k + i)))
    }

  private def issue(frames: SampledFrameSet, id: String = "f0-v1/caption/sampled/v1") =
    CaptionRequest.issue(frames, sampledExtents, recordedRequest.model, recordedRequest.recipe, id)

  /** The recorded outcome with its frame echo and extent ordinals rewritten for the sampled set. */
  private def sampledOutcome(requestId: String, count: Int): CaptionOutcome =
    val json = right(io.circe.parser.parse(recordedOutcomeText).left.map(e => DomainError.InvalidFormat("outcome", e.message, "json")))
    val c = json.hcursor
    val extents = c.downField("extents").focus.flatMap(_.asArray).getOrElse(Vector.empty)
    val rewritten = extents.zipWithIndex.map { (e, k) =>
      e.mapObject(_.add("ordinals", io.circe.Json.arr(Vector.tabulate(4)(i => io.circe.Json.fromInt(4 * k + i))*)))
    }
    val out = json.mapObject(
      _.add("requestId", io.circe.Json.fromString(requestId))
        .add("extents", io.circe.Json.arr(rewritten*))
        .add(
          "frames",
          c.downField("frames").focus.get.mapObject(
            _.add("count", io.circe.Json.fromInt(count))
              .add("sha256", io.circe.Json.fromString(sampledSha.hex))
          )
        )
    )
    right(CaptionOutcome.parse(out.spaces2))

  test("a sampled set keeps presentation ordinal and sample index apart and looks time up by the former"):
    val s = right(sampled())
    assertEquals(s.count, 12)
    assertEquals(s.presentationOrdinal(0), Some(0))
    assertEquals(s.presentationOrdinal(4), Some(14)) // sample 4 is presented packet 14, not 4
    assertEquals(s.presentationOrdinal(12), None)
    assertEquals(s.ptsOf(4), full.ptsOf(14))
    assertNotEquals(s.ptsOf(4), full.ptsOf(4))
    assertNotEquals(s.identity, full.identity)

  test("a selected ordinal outside the packet index refuses"):
    refusedAt(sampled(shown :+ full.count), "sampled-frames/membership")

  test("bytes must be exactly one frame per selected sample"):
    refusedAt(sampled(byteLength = framesEnvelope.byteLength), "sampled-frames/bytes")
    refusedAt(sampled(byteLength = framesEnvelope.geometry.frameBytes * 11), "sampled-frames/bytes")

  test("a selection that is empty, repeated or out of order refuses"):
    refusedAt(sampled(Vector.empty), "sampled-frames/selection")
    refusedAt(sampled(Vector(0, 4, 4)), "sampled-frames/selection")
    refusedAt(sampled(Vector(9, 4)), "sampled-frames/selection")
    refusedAt(sampled(Vector(-1, 4)), "sampled-frames/selection")

  test("a caption request cannot name a frame that was not sampled"):
    val s = right(sampled())
    val beyond = right(CaptionExtent.of("beyond", Vector(10, 12)))
    refusedAt(
      CaptionRequest.issue(s, Vector(beyond), recordedRequest.model, recordedRequest.recipe, "r"),
      "extent/ordinals"
    )
    // A request forged against the sampled bytes but naming sample 12 is refused at the join too.
    val forged = right(issue(s)).copy(extents = sampledExtents.init :+ beyond)
    refusedAt(
      CaptionSearch.join(s, forged, sampledOutcome(forged.requestId, 12), envelope.worker, f0),
      "caption/unsampled"
    )

  test("sampled extents close on their own samples' presented packets, at Draft"):
    val s = right(sampled())
    val req = right(issue(s))
    val result = right(CaptionSearch.join(s, req, sampledOutcome(req.requestId, 12), envelope.worker, f0))
    assertEquals(result.authority, ObservationAuthority.Draft)
    // Independent recomputation from the full decode's index: first shown packet's PTS to the last
    // shown packet's PTS plus its duration.
    val expected = recordedRequest.extents.map { e =>
      val first = full.index.entries(e.ordinals.head)
      val last = full.index.entries(e.ordinals.last)
      (first.pts, last.pts + last.durationTicks.get)
    }
    assertEquals(result.proposals.map(p => (p.support.start, p.support.endExclusive)), expected)
    assertEquals(result.proposals.map(_.frames), sampledExtents.map(_.ordinals))
    assertEquals(result.proposals.map(_.presentationOrdinals), recordedRequest.extents.map(_.ordinals))

  test("the join refuses another edition, for sampled and full frame sets alike"):
    val other = right(EditionId.from("f1-v1"))
    val s = right(sampled())
    val req = right(issue(s))
    refusedAt(
      CaptionSearch.join(s, req, sampledOutcome(req.requestId, 12), envelope.worker, other),
      "caption/edition"
    )
    val outcome = right(CaptionOutcome.parse(recordedOutcomeText))
    refusedAt(CaptionSearch.join(full, recordedRequest, outcome, envelope.worker, other), "caption/edition")
    assertEquals(
      right(CaptionSearch.join(full, recordedRequest, outcome, envelope.worker, f0)).proposals.size,
      3
    )
