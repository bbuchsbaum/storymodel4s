package storymodel4s.media

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import munit.FunSuite
import storymodel4s.core.{
  CallerRuntimePacketRecord,
  Checksum,
  DomainError,
  FixturePacketNormalizer,
  ObservationAuthority,
  RationalTimebase
}

/** The first acquisition court over F0 v1 (movie plan §9.1, admission ledger §6). Every test is a
  * predeclared failure the join must produce, or a replay property it must hold. The live court at
  * the end runs only when an ffprobe is present; it never obtains one.
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

  private lazy val bytes: Array[Byte] = resource("f0-v1.mov")
  private lazy val manifest: FixtureManifest = right(
    FixtureManifest.parse(text("f0-v1.manifest.json"))
  )
  private lazy val envelope: ProbeEnvelope = right(
    ProbeEnvelope.parse(text("f0-v1.ffprobe-envelope.json"))
  )
  private lazy val stdout: String = right(envelope.verifyStdout(resource(envelope.stdoutFile)))
  private lazy val output: FfprobeOutput = right(FfprobeJson.parse(stdout))
  private lazy val input: Checksum = right(manifest.verify(bytes))
  private lazy val probe: MediaProbe = right(
    MediaProbe.join(manifest, input, envelope.tool, envelope.args, output)
  )

  private def video: ProbedStream = probe.stream(0).get
  private def audio: ProbedStream = probe.stream(1).get

  test("F0 bytes match the manifest; altered or truncated bytes are not the fixture"):
    assertEquals(input, manifest.sha256)
    val flipped = bytes.clone()
    flipped(1000) = (flipped(1000) ^ 0x01).toByte
    refusedAt(manifest.verify(flipped), "fixture/sha256")
    refusedAt(manifest.verify(bytes.take(bytes.length - 1)), "fixture/bytes")
    assertEquals(envelope.inputSha256, manifest.sha256)

  test(
    "the recorded envelope replays into a draft probe whose picture index is the generator's declaration"
  ):
    assertEquals(probe.authority, ObservationAuthority.Draft)
    val declared = manifest.stream(0).flatMap(_.declaredFrames).get
    val index = right(PacketIndex.of(video))
    assertEquals(index.entries.size, 45)
    assertEquals(index.entries.map(_.pts), declared.expectedPts)
    assert(index.variableFrameRate, "VFR must be read from the packet table")
    assertEquals(index.deltas.distinct.sorted, Vector(1000L, 2000L, 3000L))
    val gap = index.entries.find(_.pts == 4000L).get
    assertEquals(gap.durationTicks, Some(3000L))
    val hold = index.entries.find(_.pts == 19000L).get
    assertEquals(hold.durationTicks, Some(2000L))
    assertEquals(index.timebase, right(RationalTimebase.of(1L, 24000L)))
    assertEquals(index.endExclusive, Some(48000L))

  test("audio packets tile the declared sample count with no gap and no overlap"):
    val index = right(PacketIndex.of(audio))
    assertEquals(index.firstPts, 0L)
    assert(index.contiguous)
    assertEquals(index.endExclusive, manifest.stream(1).flatMap(_.declaredSamples))
    assertEquals(index.timebase, right(RationalTimebase.of(1L, 8000L)))

  test("replay is identical; a different tool, or different arguments, is a different derivation"):
    val again = right(MediaProbe.join(manifest, input, envelope.tool, envelope.args, output))
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
    assertNotEquals(underOtherTool.stream(0).get.packets.head.identity, video.packets.head.identity)
    val underOtherArgs = right(
      MediaProbe.join(manifest, input, envelope.tool, envelope.args :+ "-count_frames", output)
    )
    assertNotEquals(underOtherArgs.identity, probe.identity)

  test("an input that is not the fixture refuses before any packet is read"):
    refusedAt(
      MediaProbe
        .join(manifest, Checksum.ofText("not the fixture"), envelope.tool, envelope.args, output),
      "probe/input"
    )

  test("an observed stream with no declaration refuses; a declared stream the tool omits refuses"):
    val extra = RawStream(2, "subtitle", "mov_text", "1/1000", None, None, None, None, None)
    refusedAt(
      MediaProbe.join(
        manifest,
        input,
        envelope.tool,
        envelope.args,
        output.copy(streams = output.streams :+ extra)
      ),
      "probe/stream"
    )
    refusedAt(
      MediaProbe.join(
        manifest,
        input,
        envelope.tool,
        envelope.args,
        output.copy(streams = output.streams.filter(_.index != 1))
      ),
      "probe/stream"
    )
    val stray = output.packets.head.copy(streamIndex = 7)
    refusedAt(
      MediaProbe.join(
        manifest,
        input,
        envelope.tool,
        envelope.args,
        output.copy(packets = stray +: output.packets)
      ),
      "probe/packet"
    )

  test("codec, kind, and timebase must agree with the declaration"):
    def withStream0(f: RawStream => RawStream): FfprobeOutput =
      output.copy(streams = output.streams.map(s => if s.index == 0 then f(s) else s))
    refusedAt(
      MediaProbe.join(
        manifest,
        input,
        envelope.tool,
        envelope.args,
        withStream0(_.copy(codecName = "h264"))
      ),
      "probe/codec"
    )
    refusedAt(
      MediaProbe.join(
        manifest,
        input,
        envelope.tool,
        envelope.args,
        withStream0(_.copy(timeBase = "1/1000"))
      ),
      "probe/timebase"
    )
    refusedAt(
      MediaProbe.join(
        manifest,
        input,
        envelope.tool,
        envelope.args,
        withStream0(_.copy(codecType = "audio"))
      ),
      "probe/kind"
    )

  test("a present PTS before a present DTS refuses and repairs nothing"):
    val bad = output.packets.map(p =>
      if p.streamIndex == 0 && p.pts.contains(7000L) then
        p.copy(pts = Some(1000L), dts = Some(2000L))
      else p
    )
    val refused =
      MediaProbe.join(manifest, input, envelope.tool, envelope.args, output.copy(packets = bad))
    refusedAt(refused, "probe/packet-timestamps")
    val message = refused.left.map(_.message).left.getOrElse("")
    assert(message.contains("1000") && message.contains("2000"), message)
    assert(message.contains("may not swap"), message)

  test(
    "a missing PTS stays a typed absence on a draft record, and the index refuses to invent one"
  ):
    val absent = output.packets.map(p =>
      if p.streamIndex == 0 && p.pts.contains(0L) then p.copy(pts = None) else p
    )
    val joined = right(
      MediaProbe.join(manifest, input, envelope.tool, envelope.args, output.copy(packets = absent))
    )
    val first = joined.stream(0).get.packets.head
    assertEquals(first.rawPts, FixturePacketNormalizer.AvNoptsValue)
    assertEquals(first.authority, ObservationAuthority.Draft)
    refusedAt(PacketIndex.of(joined.stream(0).get), "index/pts")

  test("two packets at one PTS refuse the index"):
    val dup = output.packets.map(p =>
      if p.streamIndex == 0 && p.pts.contains(1000L) then
        p.copy(pts = Some(2000L), dts = Some(2000L))
      else p
    )
    val joined = right(
      MediaProbe.join(manifest, input, envelope.tool, envelope.args, output.copy(packets = dup))
    )
    refusedAt(PacketIndex.of(joined.stream(0).get), "index/pts")

  test("no draft record can become runtime-observed"):
    (video.packets ++ audio.packets).foreach { p =>
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

  test("a recorded stdout that does not hash to the envelope is not believed"):
    refusedAt(
      envelope.verifyStdout(resource(envelope.stdoutFile) :+ 0x20.toByte),
      "envelope/stdout"
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
      def table(p: MediaProbe): Vector[(Int, Long, Long, Long)] =
        p.streams.flatMap(s => s.packets.map(k => (s.index, k.rawPts, k.rawDts, k.durationTicks)))
      assertEquals(table(liveProbe), table(probe))
      if tool == envelope.tool then assertEquals(liveProbe.identity, probe.identity)
      else
        println(
          s"[media] live tool ${tool.versionLine} (${tool.binarySha256.short()}) differs from the recorded ${envelope.tool.versionLine} (${envelope.tool.binarySha256.short()}); packet table identical, identities differ by design"
        )
        assertNotEquals(liveProbe.identity, probe.identity)
    finally
      Files.deleteIfExists(path)
      ()
