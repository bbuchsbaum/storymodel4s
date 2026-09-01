package storymodel4s.media

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import munit.FunSuite
import scala.concurrent.duration.*
import storymodel4s.core.{
  CallerRuntimePacketRecord,
  Checksum,
  DomainError,
  MediaDuration,
  ObservationAuthority,
  RationalTimebase,
  TimestampField
}

/** The ingest half of the first acquisition court over F0 v1 (movie plan §9.1, admission ledger §6
  * item 1). Every test is a predeclared failure the join must produce, or a replay property it must
  * hold. Refusals that the fixture cannot produce from real tool output (a missing PTS, a corrupt
  * flag, a gap) are exercised on edited parses and say so. The live court at the end runs only when
  * an ffprobe is present; it never obtains one.
  */
class MediaProbeSuite extends FunSuite:

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

  private def invalidFormat(e: Either[DomainError, Any], kind: String): Unit =
    e match
      case Left(DomainError.InvalidFormat(k, _, _)) => assertEquals(k, kind)
      case Left(other) => fail(s"refused at the wrong place: ${other.message}")
      case Right(v)    => fail(s"expected InvalidFormat $kind, got $v")

  private lazy val bytes: Array[Byte] = resource("f0-v1.mov")
  private lazy val manifest: FixtureManifest =
    right(FixtureManifest.parse(text("f0-v1.manifest.json")))
  private lazy val envelope: ProbeEnvelope =
    right(ProbeEnvelope.parse(text("f0-v1.ffprobe-envelope.json")))
  private lazy val stdout: String = right(envelope.verifyStdout(resource(envelope.stdoutFile)))
  private lazy val output: FfprobeOutput = right(FfprobeJson.parse(stdout))
  private lazy val input: Checksum = right(manifest.verify(bytes))
  private lazy val probe: MediaProbe =
    right(MediaProbe.join(manifest, input, envelope.tool, envelope.args, output))

  private def join(out: FfprobeOutput): Either[DomainError, MediaProbe] =
    MediaProbe.join(manifest, input, envelope.tool, envelope.args, out)

  private def video: ProbedStream = probe.stream(0).get
  private def audio: ProbedStream = probe.stream(1).get

  /** Edit the video packet at `pts`. */
  private def editVideo(pts: Long)(f: RawPacket => RawPacket): FfprobeOutput =
    output.copy(packets =
      output.packets.map(p => if p.streamIndex == 0 && p.pts.contains(pts) then f(p) else p)
    )

  private def withStream0(f: RawStream => RawStream): FfprobeOutput =
    output.copy(streams = output.streams.map(s => if s.index == 0 then f(s) else s))

  test("F0 bytes match the manifest; altered or truncated bytes are not the fixture"):
    assertEquals(input, manifest.sha256)
    assertEquals(manifest.tier, FixtureTier.F0)
    val flipped = bytes.clone()
    flipped(1000) = (flipped(1000) ^ 0x01).toByte
    refusedAt(manifest.verify(flipped), "fixture/sha256")
    refusedAt(manifest.verify(bytes.take(bytes.length - 1)), "fixture/bytes")
    assertEquals(envelope.inputSha256, manifest.sha256)
    assertEquals(envelope.args, Ffprobe.CanonicalArgs)

  test(
    "the recorded envelope replays into a draft probe whose picture index is the generator's declaration"
  ):
    assertEquals(probe.authority, ObservationAuthority.Draft)
    val declared = manifest.stream(0).flatMap(_.declaredFrames).get
    val index = right(PacketIndex.of(video))
    assertEquals(index.entries.size, 45)
    assertEquals(index.discarded, 0)
    assertEquals(index.entries.map(_.pts), declared.expectedPts)
    assert(index.variableFrameRate, "VFR must be read from the packet table")
    assertEquals(index.deltas.distinct.sorted, Vector(1000L, 2000L, 3000L))
    // Frames 5 and 6 were dropped with PTS passthrough: the frame at 4000 holds for three
    // frame-times. It is a hold, not a gap; the packets remain contiguous.
    assertEquals(index.entries.find(_.pts == 4000L).get.durationTicks, Some(3000L))
    assertEquals(index.entries.find(_.pts == 19000L).get.durationTicks, Some(2000L))
    assert(index.contiguous)
    assertEquals(index.timebase, right(RationalTimebase.of(1L, 24000L)))
    assertEquals(index.endExclusive, Some(48000L))
    assert(video.packets.forall(_.flags.keyframe))
    assert(video.packets.forall(p => p.pts == p.dts), "rawvideo: PTS equals DTS on every packet")

  test(
    "audio packets tile the declared sample count with no gap and no overlap, at constant spacing"
  ):
    val index = right(PacketIndex.of(audio))
    assertEquals(index.firstPts, 0L)
    assert(index.contiguous)
    assertEquals(index.endExclusive, manifest.stream(1).flatMap(_.declaredSamples))
    assertEquals(index.timebase, right(RationalTimebase.of(1L, 8000L)))
    assert(!index.variableFrameRate, "constant packet spacing is read, not assumed")

  test("a duration that does not reach the next PTS is a gap: contiguity is measured, not assumed"):
    val gapped = editVideo(4000L)(_.copy(duration = Some(1000L)))
    val index = right(PacketIndex.of(right(join(gapped)).stream(0).get))
    assert(!index.contiguous)
    assertEquals(index.entries.find(_.pts == 4000L).get.durationTicks, Some(1000L))

  test("replay is identical; a different tool, or different arguments, is a different derivation"):
    val again = right(join(output))
    assertEquals(again.identity, probe.identity)
    assertEquals(again.receipt, probe.receipt)
    val otherTool = right(
      ToolRealization.of(
        "ffprobe",
        "ffprobe version 9.0.1 Copyright (c) 2007-2026 the FFmpeg developers",
        Checksum.ofText("other binary")
      )
    )
    val underOtherTool = right(MediaProbe.join(manifest, input, otherTool, envelope.args, output))
    assertNotEquals(underOtherTool.receipt.identity, probe.receipt.identity)
    assertNotEquals(underOtherTool.identity, probe.identity)
    assertNotEquals(underOtherTool.stream(0).get.records.head.identity, video.records.head.identity)
    val underOtherArgs =
      right(
        MediaProbe.join(manifest, input, envelope.tool, envelope.args :+ "-count_frames", output)
      )
    assertNotEquals(underOtherArgs.identity, probe.identity)
    // The parameter encoding is injective over argument boundaries.
    assertNotEquals(
      MediaProbe.parameters(envelope.tool, Vector("a b")),
      MediaProbe.parameters(envelope.tool, Vector("a", "b"))
    )

  test("an input that is not the fixture refuses before any packet is read"):
    refusedAt(
      MediaProbe
        .join(manifest, Checksum.ofText("not the fixture"), envelope.tool, envelope.args, output),
      "probe/input"
    )

  test("an observed stream with no declaration refuses; a declared stream the tool omits refuses"):
    val extra = RawStream(2, "subtitle", "mov_text", "1/1000", None, None, None, None, None)
    refusedAt(join(output.copy(streams = output.streams :+ extra)), "probe/stream")
    refusedAt(join(output.copy(streams = output.streams.filter(_.index != 1))), "probe/stream")
    val stray = output.packets.head.copy(streamIndex = 7)
    refusedAt(join(output.copy(packets = stray +: output.packets)), "probe/packet")

  test(
    "codec, kind, timebase, picture format and geometry, and audio parameters must agree with the declaration"
  ):
    refusedAt(join(withStream0(_.copy(codecName = "h264"))), "probe/codec")
    refusedAt(join(withStream0(_.copy(timeBase = "1/1000"))), "probe/timebase")
    refusedAt(join(withStream0(_.copy(codecType = "audio"))), "probe/kind")
    refusedAt(join(withStream0(_.copy(width = Some(64), height = Some(36)))), "probe/picture")
    refusedAt(join(withStream0(_.copy(pixFmt = Some("yuv420p")))), "probe/picture")
    val resampled = output.copy(streams =
      output.streams.map(s => if s.index == 1 then s.copy(sampleRate = Some(44100)) else s)
    )
    refusedAt(join(resampled), "probe/audio")

  test("a present PTS before a present DTS refuses and repairs nothing"):
    val refused = join(editVideo(7000L)(_.copy(pts = Some(1000L), dts = Some(2000L))))
    refusedAt(refused, "probe/packet-timestamps")
    val message = refused.left.map(_.message).left.getOrElse("")
    assert(message.contains("1000") && message.contains("2000"), message)
    assert(message.contains("may not swap"), message)

  test("a missing PTS is the typed absence on the packet, and the index refuses to invent one"):
    val joined = right(join(editVideo(0L)(_.copy(pts = None))))
    val first = joined.stream(0).get.packets.head
    assertEquals(first.pts, TimestampField.Missing)
    assertEquals(first.dts, TimestampField.Present(0L))
    assertEquals(first.record.authority, ObservationAuthority.Draft)
    refusedAt(PacketIndex.of(joined.stream(0).get), "index/pts")

  test("a literal AV_NOPTS_VALUE in the JSON is refused at parse; only absence is missing"):
    val sentinel = stdout.replaceFirst("\"pts\": 0,", s"\"pts\": ${Long.MinValue},")
    assert(sentinel != stdout)
    invalidFormat(FfprobeJson.parse(sentinel), "packets[].pts")

  test("a negative duration refuses; absent and zero durations are unknown, never measured empty"):
    refusedAt(join(editVideo(1000L)(_.copy(duration = Some(-1L)))), "probe/packet-duration")
    val absent = right(join(editVideo(1000L)(_.copy(duration = None)))).stream(0).get
    val zero = right(join(editVideo(1000L)(_.copy(duration = Some(0L))))).stream(0).get
    val packetAt1000 = (s: ProbedStream) => s.packets(1)
    assertEquals(packetAt1000(absent).duration, MediaDuration.Unknown)
    assertEquals(packetAt1000(zero).duration, MediaDuration.Unknown)
    assertEquals(packetAt1000(absent).durationReported, None)
    assertEquals(packetAt1000(zero).durationReported, Some(0L))
    assertEquals(right(PacketIndex.of(absent)).entries(1).durationTicks, None)
    assert(!right(PacketIndex.of(absent)).contiguous, "an unknown duration is not contiguity")

  test(
    "packet flags are typed: a corrupt packet refuses the index, a discarded packet is not presented"
  ):
    invalidFormat(PacketFlags.parse("KDX"), "packets[].flags")
    val corrupt =
      right(join(editVideo(2000L)(_.copy(flags = PacketFlags(true, false, true, "K_C")))))
    refusedAt(PacketIndex.of(corrupt.stream(0).get), "index/corrupt")
    val discard =
      right(join(editVideo(2000L)(_.copy(flags = PacketFlags(true, true, false, "KD_")))))
    val index = right(PacketIndex.of(discard.stream(0).get))
    assertEquals(index.discarded, 1)
    assertEquals(index.entries.size, 44)
    assert(!index.entries.exists(_.pts == 2000L))
    assertNotEquals(discard.identity, probe.identity, "flags enter the probe identity")

  test("two presented packets at one PTS refuse the index"):
    val dup = editVideo(1000L)(_.copy(pts = Some(2000L), dts = Some(2000L)))
    refusedAt(PacketIndex.of(right(join(dup)).stream(0).get), "index/pts")

  test("an Unsupported stream is declared and probed but never indexed"):
    val declaredUnsupported = right(
      FixtureManifest.parse(
        text("f0-v1.manifest.json").replaceFirst(
          "\"disposition\": \"PacketIndexedOnly\"",
          "\"disposition\": \"Unsupported\""
        )
      )
    )
    val joined =
      right(MediaProbe.join(declaredUnsupported, input, envelope.tool, envelope.args, output))
    assertEquals(joined.stream(1).get.packets.size, 16)
    refusedAt(PacketIndex.of(joined.stream(1).get), "index/disposition")
    assertNotEquals(joined.identity, probe.identity, "disposition enters the probe identity")

  test("no draft record can become runtime-observed; the receipt binds algorithm, tool, and input"):
    (video.records ++ audio.records).foreach { p =>
      assertEquals(p.authority, ObservationAuthority.Draft)
      refusedAt(CallerRuntimePacketRecord.promoteToRuntime(p), "observation/authority")
    }
    assertEquals(probe.receipt.algorithm, MediaProbe.Algorithm)
    assertEquals(probe.receipt.inputChecksums, Vector(input))
    assert(probe.receipt.parameters.contains(envelope.tool.identity.hex))

  test("a timebase is an exact reduced rational, never decimal seconds"):
    assertEquals(FixtureManifest.parseTimebase("1/24000"), RationalTimebase.of(1L, 24000L))
    assertEquals(FixtureManifest.parseTimebase("2/48000"), RationalTimebase.of(1L, 24000L))
    assert(FixtureManifest.parseTimebase("0.041666").isLeft)
    assert(FixtureManifest.parseTimebase("1/0").isLeft)
    assert(FixtureManifest.parseTimebase("24000").isLeft)

  test("checked constructors refuse: declared frames, tool realizations, schemas, envelopes"):
    assert(DeclaredFrames.of(0, 1000L, Vector.empty).isLeft)
    assert(DeclaredFrames.of(48, 1000L, Vector(48)).isLeft)
    assert(DeclaredFrames.of(48, 1000L, Vector(5, 5)).isLeft)
    assertEquals(right(DeclaredFrames.of(48, 1000L, Vector(20, 5, 6))).expectedPts.size, 45)
    assert(ToolRealization.of("", "v", Checksum.ofText("x")).isLeft)
    assert(ToolRealization.of("ffprobe", "line one\nline two", Checksum.ofText("x")).isLeft)
    invalidFormat(
      FixtureManifest.parse(
        text("f0-v1.manifest.json").replaceFirst("fixture-manifest", "other-schema")
      ),
      "schema"
    )
    invalidFormat(
      FixtureManifest.parse(
        text("f0-v1.manifest.json").replaceFirst("\"tier\": \"F0\"", "\"tier\": \"F9\"")
      ),
      "tier"
    )
    refusedAt(
      envelope.verifyStdout(resource(envelope.stdoutFile) :+ 0x20.toByte),
      "envelope/stdout"
    )

  test(
    "a subprocess that exits non-zero, or overruns its timeout, refuses rather than returning output"
  ):
    val failing = right(Subprocess.run(Vector("sh", "-c", "echo partial; exit 3")))
    assertEquals(failing.exitCode, 3)
    refusedAt(
      Subprocess.run(Vector("sh", "-c", "echo partial; exit 3")).flatMap { o =>
        if o.exitCode == 0 then Right(o.stdout)
        else Left(DomainError.InvariantViolation("ffprobe/exit", o.stderr))
      },
      "ffprobe/exit"
    )
    refusedAt(Subprocess.run(Vector("sh", "-c", "sleep 5"), 200.millis), "subprocess/timeout")
    refusedAt(Subprocess.run(Vector("/nonexistent/tool-for-this-court")), "subprocess/start")
    refusedAt(
      ToolRealization.observe("ffprobe", java.nio.file.Path.of("/nonexistent/ffprobe")),
      "tool/path"
    )

  test("live ffprobe, when this machine has one, reproduces the recorded packet table"):
    val located = Ffprobe.locate()
    assume(located.isDefined, "no ffprobe on PATH; live court not run (nothing is downloaded)")
    val tool = right(ToolRealization.observe(Ffprobe.ToolName, located.get))
    val path = Files.createTempFile("f0-v1-", ".mov")
    try
      Files.write(path, bytes)
      val live = right(FfprobeJson.parse(right(Ffprobe.invoke(located.get, path))))
      val liveProbe = right(MediaProbe.join(manifest, input, tool, Ffprobe.CanonicalArgs, live))
      def table(
          p: MediaProbe
      ): Vector[(Int, TimestampField, TimestampField, Option[Long], String)] =
        p.streams.flatMap(s =>
          s.packets.map(k => (s.index, k.pts, k.dts, k.durationReported, k.flags.raw))
        )
      assertEquals(table(liveProbe), table(probe))
      // Same binary: same derivation. Another binary: the packet table may agree, the identity may not.
      if tool == envelope.tool then assertEquals(liveProbe.identity, probe.identity)
      else assertNotEquals(liveProbe.identity, probe.identity)
    finally
      Files.deleteIfExists(path)
      ()
