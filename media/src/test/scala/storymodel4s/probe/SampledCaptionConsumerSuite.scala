package storymodel4s.probe

import java.nio.charset.StandardCharsets
import munit.FunSuite
import storymodel4s.core.{Checksum, DomainError, EditionId, ObservationAuthority}
import storymodel4s.media.*

/** AC4 slice S1 seam, written from outside `storymodel4s.media` before the implementation
  * (AGENTS.md T6). A caption request addresses frames by *sample index* (a position in the frame
  * bytes); time comes only from each sample's *presentation ordinal* in the probe's packet index.
  * The two are never interchangeable. F0 v1 supplies the probe; its recorded caption request
  * already shows 12 of 45 frames, so the sampled set here selects exactly those 12 and the recorded
  * worker text is re-addressed to sample indices 0-11. That rewritten outcome tests the join, not
  * the worker.
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
    right(envelope.verifyOutcome(resource(envelope.outcomeFile)))
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
    val json = right(
      io.circe.parser
        .parse(recordedOutcomeText)
        .left
        .map(e => DomainError.InvalidFormat("outcome", e.message, "json"))
    )
    val c = json.hcursor
    val extents = c.downField("extents").focus.flatMap(_.asArray).getOrElse(Vector.empty)
    val rewritten = extents.zipWithIndex.map { (e, k) =>
      e.mapObject(
        _.add(
          "ordinals",
          io.circe.Json.arr(Vector.tabulate(4)(i => io.circe.Json.fromInt(4 * k + i))*)
        )
      )
    }
    val out = json.mapObject(
      _.add("requestId", io.circe.Json.fromString(requestId))
        .add("extents", io.circe.Json.arr(rewritten*))
        .add(
          "frames",
          c.downField("frames")
            .focus
            .get
            .mapObject(
              _.add("count", io.circe.Json.fromInt(count))
                .add("sha256", io.circe.Json.fromString(sampledSha.hex))
            )
        )
    )
    right(CaptionOutcome.parse(out.spaces2))

  test(
    "a sampled set keeps presentation ordinal and sample index apart and looks time up by the former"
  ):
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
    // A request forged against the sampled bytes but naming sample 12 is refused at the join too,
    // before the outcome is read: the outcome here also answers another request, so a join that
    // skipped the request check would refuse at caption/request-id instead.
    val forged = right(issue(s)).copy(extents = sampledExtents.init :+ beyond)
    refusedAt(
      CaptionSearch.join(s, forged, sampledOutcome("another-request", 12), envelope.worker, f0),
      "caption/unsampled"
    )

  test("sampled extents close on their own samples' presented packets, at Draft"):
    val s = right(sampled())
    val req = right(issue(s))
    val result =
      right(CaptionSearch.join(s, req, sampledOutcome(req.requestId, 12), envelope.worker, f0))
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
    assertEquals(
      result.proposals.map(_.presentationOrdinals),
      recordedRequest.extents.map(_.ordinals)
    )

  test("the join refuses another edition, for sampled and full frame sets alike"):
    val other = right(EditionId.from("f1-v1"))
    val s = right(sampled())
    val req = right(issue(s))
    refusedAt(
      CaptionSearch.join(s, req, sampledOutcome(req.requestId, 12), envelope.worker, other),
      "caption/edition"
    )
    val outcome = right(CaptionOutcome.parse(recordedOutcomeText))
    refusedAt(
      CaptionSearch.join(full, recordedRequest, outcome, envelope.worker, other),
      "caption/edition"
    )
    assertEquals(
      right(CaptionSearch.join(full, recordedRequest, outcome, envelope.worker, f0)).proposals.size,
      3
    )

  test(
    "a forged extent that is out of order, empty or repeated is refused at the join, never admitted"
  ):
    // CaptionExtent is a case class, so apply and copy skip CaptionExtent.of; the join re-admits.
    // Out of order, the hull of the first and last named frames would leave out frame 11, which the
    // model was shown (PTS 47000); empty, it would have no first frame at all.
    val s = right(sampled())
    val outcome = sampledOutcome("another-request", 12)
    for bad <- Vector(Vector(0, 11, 1, 2), Vector.empty[Int], Vector(1, 1)) do
      val forged =
        right(issue(s)).copy(extents = CaptionExtent("shot-1", bad) +: sampledExtents.tail)
      refusedAt(CaptionSearch.join(s, forged, outcome, envelope.worker, f0), "caption/extent-shape")
    val twice = right(issue(s))
      .copy(extents = sampledExtents.head +: sampledExtents.tail.map(_.copy(id = "shot-1")))
    refusedAt(CaptionSearch.join(s, twice, outcome, envelope.worker, f0), "caption/extent-shape")
    // The full-decode path had the same door.
    val fullForged = recordedRequest.copy(extents =
      CaptionExtent("shot-1", Vector(13, 0)) +: recordedRequest.extents.tail
    )
    refusedAt(
      CaptionSearch.join(
        full,
        fullForged,
        right(CaptionOutcome.parse(recordedOutcomeText)),
        envelope.worker,
        f0
      ),
      "caption/extent-shape"
    )

  test("the selection is bound into the frame set's identity and receipt"):
    // Two selections of the same size over the same declared bytes digest differ only in which
    // packets they name; their identities and receipts must differ too.
    val a = right(sampled())
    val b = right(sampled(shown.init :+ 43))
    assertEquals(a.count, b.count)
    assertEquals(a.framesSha256, b.framesSha256)
    assertNotEquals(a.identity, b.identity)
    assertNotEquals(a.receipt.identity, b.receipt.identity)

  test("a full-decode request naming a frame beyond the decode refuses as unsampled"):
    val outcome = right(CaptionOutcome.parse(recordedOutcomeText))
    val beyond = recordedRequest.copy(extents =
      recordedRequest.extents.init :+ CaptionExtent("shot-3", Vector(29, 45))
    )
    refusedAt(CaptionSearch.join(full, beyond, outcome, envelope.worker, f0), "caption/unsampled")

  /** F0 re-declared with a second Decoded picture stream: stream 1 is stream 0's packets shifted by
    * +500 ticks, in place of the audio stream. Same geometry, same selection, same bytes digest;
    * only the stream, and therefore every PTS, differs. Probed without the envelope's stdout
    * digest, since the ffprobe document is rewritten here on purpose.
    */
  private lazy val twoPictureProbe: MediaProbe =
    import io.circe.Json
    def parse(t: String): Json =
      right(
        io.circe.parser.parse(t).left.map(e => DomainError.InvalidFormat("json", e.message, "json"))
      )
    val m = parse(text("f0-v1.manifest.json"))
    val picture = m.hcursor.downField("streams").downArray.focus.get
    val m2 = m.mapObject(
      _.add("streams", Json.arr(picture, picture.mapObject(_.add("index", Json.fromInt(1)))))
    )
    val p = parse(new String(resource(probeEnvelope.stdoutFile), StandardCharsets.UTF_8))
    val videoStream = p.hcursor.downField("streams").downArray.focus.get
    val shifted =
      videoStream.mapObject(_.add("index", Json.fromInt(1)).add("start_pts", Json.fromLong(500)))
    val video = p.hcursor
      .downField("packets")
      .focus
      .flatMap(_.asArray)
      .get
      .filter(_.hcursor.downField("stream_index").as[Int].toOption.contains(0))
    val copies = video.map(pk =>
      pk.mapObject(o =>
        o.add("stream_index", Json.fromInt(1))
          .add("pts", Json.fromLong(pk.hcursor.downField("pts").as[Long].toOption.get + 500))
          .add("dts", Json.fromLong(pk.hcursor.downField("dts").as[Long].toOption.get + 500))
      )
    )
    val p2 = p.mapObject(
      _.add("streams", Json.arr(videoStream, shifted)).add("packets", Json.arr((video ++ copies)*))
    )
    val manifest2 = right(FixtureManifest.parse(m2.spaces2))
    right(
      MediaProbe.join(
        manifest2,
        right(manifest2.verify(resource("f0-v1.mov"))),
        probeEnvelope.tool,
        probeEnvelope.args,
        right(FfprobeJson.parse(p2.spaces2))
      )
    )

  test("the stream is bound into the sampled set's identity and receipt"):
    def on(stream: Int) = right(
      SampledFrameSet.join(
        twoPictureProbe,
        stream,
        framesEnvelope.geometry,
        framesEnvelope.tool,
        framesEnvelope.args,
        shown,
        framesEnvelope.geometry.frameBytes * shown.size,
        sampledSha
      )
    )
    val (a, b) = (on(0), on(1))
    // The precondition that makes this test able to fail: same selection and bytes, different time.
    assertEquals((a.selected, a.framesSha256), (b.selected, b.framesSha256))
    assertEquals(b.ptsOf(0).map(_ - 500), a.ptsOf(0))
    assertNotEquals(a.identity, b.identity)
    assertNotEquals(a.receipt.identity, b.receipt.identity)
