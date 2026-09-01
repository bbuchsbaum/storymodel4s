package storymodel4s.media

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import storymodel4s.core.{
  BoundaryLayer,
  Checksum,
  DomainError,
  ObservationAuthority,
  RationalTimebase,
  TimestampField
}

/** The F1 rung of the first acquisition court (ledger §6 item 2): a checksummed *Big Buck Bunny*
  * excerpt tests real container and codec behaviour. Everything here is structural. The excerpt
  * admits no ground truth, so no test says where a shot is; the courts say only that the recorded
  * probe, decode, and detector run replay exactly, that every coordinate comes from the packet
  * index, and that nothing is more than `Draft`.
  */
class F1ExcerptSuite extends FunSuite:

  private def resource(dir: String, name: String): Array[Byte] =
    val in = getClass.getResourceAsStream(s"/$dir/$name")
    assert(in != null, s"missing test resource $dir/$name")
    try in.readAllBytes()
    finally in.close()

  private def text(dir: String, name: String): String =
    new String(resource(dir, name), StandardCharsets.UTF_8)

  private def right[A](e: Either[DomainError, A]): A =
    e.fold(err => fail(s"expected Right, got ${err.message}"), identity)

  private def refusedAt(e: Either[DomainError, Any], path: String): Unit =
    e match
      case Left(DomainError.InvariantViolation(p, _)) => assertEquals(p, path)
      case Left(other) => fail(s"refused at the wrong place: ${other.message}")
      case Right(v)    => fail(s"expected refusal at $path, got $v")

  /** Replay one fixture directory from its recorded envelopes alone; no tool runs. */
  final case class Replayed(
      manifest: FixtureManifest,
      probeEnvelope: ProbeEnvelope,
      probe: MediaProbe,
      framesEnvelope: FramesEnvelope,
      frames: FrameSet,
      detectorEnvelope: DetectorEnvelope,
      request: DetectorRequest,
      outcome: DetectorOutcome,
      result: BoundarySearchResult
  )

  private def replay(dir: String, id: String): Replayed =
    val manifest = right(FixtureManifest.parse(text(dir, s"$id.manifest.json")))
    val probeEnvelope = right(ProbeEnvelope.parse(text(dir, s"$id.ffprobe-envelope.json")))
    val stdout = right(probeEnvelope.verifyStdout(resource(dir, probeEnvelope.stdoutFile)))
    // The bytes are external; the envelope's input identity stands in for them, and the manifest
    // must agree with it. Nothing in this replay hashes media bytes.
    assertEquals(probeEnvelope.inputSha256, manifest.sha256)
    val probe = right(
      MediaProbe.join(
        manifest,
        probeEnvelope.inputSha256,
        probeEnvelope.tool,
        probeEnvelope.args,
        right(FfprobeJson.parse(stdout))
      )
    )
    val framesEnvelope = right(FramesEnvelope.parse(text(dir, s"$id.frames-envelope.json")))
    val frames = right(
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
    val detectorEnvelope = right(DetectorEnvelope.parse(text(dir, s"$id.detector-envelope.json")))
    val request = right(DetectorRequest.parse(text(dir, detectorEnvelope.requestFile)))
    val outcome = right(
      DetectorOutcome.parse(
        right(detectorEnvelope.verifyOutcome(resource(dir, detectorEnvelope.outcomeFile)))
      )
    )
    val result = right(BoundarySearch.join(frames, request, outcome, detectorEnvelope.worker))
    Replayed(
      manifest,
      probeEnvelope,
      probe,
      framesEnvelope,
      frames,
      detectorEnvelope,
      request,
      outcome,
      result
    )

  private lazy val f1: Replayed = replay("f1", "f1-v1")
  private lazy val f0: Replayed = replay("f0", "f0-v1")

  test(
    "the excerpt's recorded probe replays: 720 picture packets at 512 ticks on 1/12288, extent [0, 368640)"
  ):
    assertEquals(f1.probe.authority, ObservationAuthority.Draft)
    assertEquals(f1.manifest.tier, FixtureTier.F1)
    val video = right(PacketIndex.of(f1.probe.stream(0).get))
    assertEquals(video.entries.size, 720)
    assertEquals(video.timebase, right(RationalTimebase.of(1L, 12288L)))
    assertEquals(video.deltas.distinct, Vector(512L))
    assert(!video.variableFrameRate)
    assert(video.contiguous)
    assertEquals(video.firstPts, 0L)
    assertEquals(video.endExclusive, Some(368640L))
    assertEquals(f1.probe.stream(0).get.startPts, Some(0L))
    assert(
      f1.probe.stream(0).get.packets.forall(p => p.pts == p.dts),
      "no B-frames: PTS equals DTS"
    )
    assertEquals(video.discarded, 0)
    assert(f1.probe.stream(0).get.packets.forall(!_.flags.corrupt))
    assertEquals(f1.probe.stream(0).get.declared.disposition, StreamDisposition.Decoded)

  test(
    "the container's audio packets start at a negative PTS under an edit list this module does not represent; the packet table is recorded as found"
  ):
    val audio = right(PacketIndex.of(f1.probe.stream(1).get))
    assertEquals(audio.entries.size, 1407)
    assertEquals(audio.firstPts, -512L)
    assertEquals(f1.probe.stream(1).get.startPts, Some(0L))
    // The tool reports the stream start at 0 while the first packet sits at -512: that is the AAC
    // priming packet under the excerpt's edit list. The index records the container as found; it
    // makes no claim that the packet at -512 is audience-facing, and no axis is built from it.
    assertNotEquals(f1.probe.stream(1).get.startPts, Some(audio.firstPts))
    assert(audio.contiguous)
    // The recorded probe is the realized FFmpeg 9.0.1 build, which reports the last audio packet's
    // full 1024-sample duration; Homebrew 7.1.1 trimmed it to 1008 under the edit list. The
    // difference is the tool's, not the container's, and it lives in the tool identity.
    assertEquals(audio.endExclusive, Some(1440256L))
    assertEquals(audio.timebase, right(RationalTimebase.of(1L, 48000L)))
    assertEquals(f1.probe.stream(1).get.declared.disposition, StreamDisposition.PacketIndexedOnly)

  test(
    "one decoded frame per packet; the recorded detector run yields eight draft proposals whose instants come from the index"
  ):
    assertEquals(f1.frames.count, 720)
    assertEquals(f1.frames.geometry, PictureGeometry(320, 180))
    assertEquals(f1.result.authority, ObservationAuthority.Draft)
    assertEquals(f1.result.proposals.size, 8)
    assertEquals(f1.outcome.cuts, Vector(125, 199, 300, 346, 413, 461, 532, 569))
    assertEquals(f1.result.proposals.map(_.at.at), f1.outcome.cuts.map(k => f1.frames.ptsOf(k).get))
    assert(f1.result.proposals.forall(p => p.window.endExclusive - p.window.start == 512L))
    assert(f1.result.proposals.forall(p => p.window.endExclusive == p.at.at))
    assertEquals(
      f1.result.proposals.map(_.score),
      f1.outcome.cuts.map(k => f1.outcome.metrics(k).values(DetectorOutcome.ScoreKey))
    )
    assert(f1.result.proposals.forall(_.score >= f1.request.recipe.threshold))
    assert(f1.result.proposals.forall(_.layer == BoundaryLayer.Shot))
    assert(f1.result.proposals.forall(_.at.axis == f1.result.axis.id))
    assertEquals((f1.result.examined.start, f1.result.examined.endExclusive), (0L, 368640L))
    assertEquals(f1.result.noCandidate, None)
    assertEquals(f1.result.applied.kernelSize, 5)
    assertEquals(f1.result.applied.threshold, 27.0)
    assertEquals(f1.result.applied.minSceneLen, 15)

  test(
    "the excerpt is a derivation with a recorded source, licence, and attribution, and a new identity"
  ):
    val json = right(MediaJson.parseDocument("manifest", text("f1", "f1-v1.manifest.json")))
    val source = json.hcursor.downField("source")
    assertEquals(right(MediaJson.string(source.downField("license"), "license")), "CC BY 3.0")
    assert(
      right(MediaJson.string(source.downField("attribution"), "attribution"))
        .contains("Blender Foundation")
    )
    val inner =
      right(MediaJson.string(source.downField("innerSha256"), "innerSha256").flatMap(Checksum.from))
    val archive = right(
      MediaJson.string(source.downField("archiveSha256"), "archiveSha256").flatMap(Checksum.from)
    )
    assertNotEquals(f1.manifest.sha256, inner)
    assertNotEquals(f1.manifest.sha256, archive)
    assertEquals(
      right(MediaJson.string(json.hcursor.downField("derivation").downField("script"), "script")),
      "media/tools/derive_f1.sh"
    )

  test("F0 and F1 are distinct derivations; an F0 request cannot be answered with F1 output"):
    assertNotEquals(f1.request.recipe.identity, f0.request.recipe.identity)
    assertNotEquals(f1.result.receipt.identity, f0.result.receipt.identity)
    assertNotEquals(f1.frames.identity, f0.frames.identity)
    refusedAt(
      BoundarySearch.join(f1.frames, f0.request, f1.outcome, f1.detectorEnvelope.worker),
      "boundary/request"
    )
    refusedAt(
      BoundarySearch.join(f0.frames, f1.request, f1.outcome, f1.detectorEnvelope.worker),
      "boundary/request"
    )

  test(
    "live tools, when this machine has them and the excerpt, reproduce the recorded packet table and proposals"
  ):
    val excerpt = F1ExcerptSuite.excerpt
    val ffprobe = Ffprobe.locate()
    val ffmpeg = Ffmpeg.locate()
    val python = BoundarySearchSuite.workerPython
    val script = BoundarySearchSuite.workerScript
    assume(
      excerpt.isDefined,
      "F1 excerpt not present locally (tmp/f1/f1-v1.mp4 or STORYMODEL4S_F1_EXCERPT); live court not run, nothing is downloaded"
    )
    assume(ffprobe.isDefined && ffmpeg.isDefined, "no ffprobe/ffmpeg on PATH; live court not run")
    assume(
      python.isDefined && script.isDefined,
      "worker environment not installed; live court not run"
    )
    val bytes = Files.readAllBytes(excerpt.get)
    val input = right(f1.manifest.verify(bytes))
    val probeTool = right(ToolRealization.observe(Ffprobe.ToolName, ffprobe.get))
    val liveProbe = right(
      MediaProbe.join(
        f1.manifest,
        input,
        probeTool,
        Ffprobe.CanonicalArgs,
        right(FfprobeJson.parse(right(Ffprobe.invoke(ffprobe.get, excerpt.get))))
      )
    )
    def table(p: MediaProbe): Vector[(Int, TimestampField, TimestampField, Option[Long], String)] =
      p.streams.flatMap(s =>
        s.packets.map(k => (s.index, k.pts, k.dts, k.durationReported, k.flags.raw))
      )
    // The picture packet table is what boundaries stand on; it must reproduce under any admitted
    // tool. The audio table may differ across tool versions at the edit-list edge (7.1.1 trims the
    // last packet's duration, 9.0.1 does not), so the full table and the identity are compared only
    // when the same binary ran.
    assertEquals(table(liveProbe).filter(_._1 == 0), table(f1.probe).filter(_._1 == 0))
    if probeTool == f1.probeEnvelope.tool then
      assertEquals(table(liveProbe), table(f1.probe))
      assertEquals(liveProbe.identity, f1.probe.identity)
    val dir = Files.createTempDirectory("f1-boundary-")
    try
      val raw = dir.resolve("f1-v1.frames.bgr")
      val decodeTool = right(ToolRealization.observe(Ffmpeg.ToolName, ffmpeg.get))
      right(Ffmpeg.extract(ffmpeg.get, excerpt.get, 0, raw))
      val decoded = Files.readAllBytes(raw)
      val live = right(
        FrameSet.join(
          liveProbe,
          0,
          f1.framesEnvelope.geometry,
          decodeTool,
          Ffmpeg.canonicalArgs(0),
          decoded.length.toLong,
          Checksum.ofBytes(decoded)
        )
      )
      val sameFrames = live.framesSha256 == f1.framesEnvelope.framesSha256
      if decodeTool == f1.framesEnvelope.tool then
        assert(sameFrames, "same decoder build must decode to the recorded bytes")
      val issued = DetectorRequest.issue(live, f1.request.recipe, f1.request.requestId)
      val reqPath = dir.resolve("request.json")
      val outPath = dir.resolve("outcome.json")
      Files.writeString(reqPath, issued.render(raw.toString))
      val run = right(
        Subprocess.run(
          Vector(python.get.toString, script.get.toString, reqPath.toString, outPath.toString)
        )
      )
      assertEquals(run.exitCode, 0, run.stderr)
      val liveOutcome = right(DetectorOutcome.parse(Files.readString(outPath)))
      val worker = right(WorkerRealization.observe(python.get, script.get))
      val liveResult = right(BoundarySearch.join(live, issued, liveOutcome, worker))
      // Under decoder drift the frames differ and the proposals are not compared; the court then
      // establishes only that the pipeline joins.
      if sameFrames then
        assertEquals(liveResult.proposals.map(_.at.at), f1.result.proposals.map(_.at.at))
        assertEquals(liveResult.proposals.map(_.score), f1.result.proposals.map(_.score))
    finally
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))
      ()

object F1ExcerptSuite:
  /** The locally derived excerpt, if present. Never downloaded by the court. */
  def excerpt: Option[Path] =
    Option(System.getenv("STORYMODEL4S_F1_EXCERPT"))
      .map(Path.of(_))
      .filter(Files.isRegularFile(_))
      .orElse(
        Vector(Path.of("tmp/f1/f1-v1.mp4"), Path.of("../tmp/f1/f1-v1.mp4"))
          .find(Files.isRegularFile(_))
      )
