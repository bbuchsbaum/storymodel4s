package storymodel4s.probe

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import io.circe.Json
import munit.FunSuite
import storymodel4s.core.{Checksum, DomainError, EditionId, ObservationAuthority}
import storymodel4s.media.*

/** Synthetic replay courts exercise multipart membership independently of extraction execution. */
class CaptionPackageSuite extends FunSuite:
  private def right[A](value: Either[DomainError, A]): A =
    value.fold(e => fail(e.message), identity)
  private def resource(name: String): Array[Byte] =
    val in = getClass.getResourceAsStream(s"/f0/$name")
    assert(in != null, s"missing test fixture $name")
    try in.readAllBytes()
    finally in.close()
  private def json(name: String): Json =
    io.circe.parser.parse(new String(resource(name), UTF_8)).toOption.get
  private def field(j: Json, name: String, value: Json): Json = j.mapObject(_.add(name, value))
  private val edition = right(EditionId.from("multipart-test"))

  private final class Fixture(val root: Path, var document: Json):
    def bytes: Array[Byte] = document.noSpaces.getBytes(UTF_8)
    def read: Either[DomainError, CaptionPackage] =
      CaptionPackage.load(root, bytes, Checksum.ofBytes(bytes), edition)
    def repin(name: String, bytes: Array[Byte]): Unit =
      val _ = Files.write(root.resolve(name), bytes)
      val pins = document.hcursor.downField("files").focus.get
      document =
        field(document, "files", field(pins, name, Json.fromString(Checksum.ofBytes(bytes).hex)))
    def change(name: String)(f: Json => Json): Unit =
      val before = io.circe.parser.parse(Files.readString(root.resolve(name))).toOption.get
      repin(name, f(before).noSpaces.getBytes(UTF_8))

  private def withFixture(test: Fixture => Unit): Unit =
    val root = Files.createTempDirectory("caption-package-court-")
    try test(build(root))
    finally
      val paths = Files.walk(root)
      try
        paths.iterator().asScala.toVector.reverse.foreach(p => { val _ = Files.deleteIfExists(p) })
      finally paths.close()

  private def build(root: Path): Fixture =
    var pins = Map.empty[String, Json]
    def put(name: String, bytes: Array[Byte]): Unit =
      val _ = Files.createDirectories(root.resolve(name).getParent)
      val _ = Files.write(root.resolve(name), bytes)
      pins = pins.updated(name, Json.fromString(Checksum.ofBytes(bytes).hex))
    def putJson(name: String, value: Json): Unit = put(name, value.noSpaces.getBytes(UTF_8))
    val baseline = right(
      CaptionRequest.parse(new String(resource("f0-v1.caption-request.json"), UTF_8))
    )
    val baseEnvelope = right(
      CaptionEnvelope.parse(new String(resource("f0-v1.caption-envelope.json"), UTF_8))
    )
    val partDefs = Vector(("a", Vector(0, 4, 9), Vector(0, 1)), ("b", Vector(2, 13), Vector(0, 1)))
    val parts = partDefs.map { (name, selected, examined) =>
      val localId = s"local-$name"
      val manifestBase = field(
        field(json("f0-v1.manifest.json"), "fixtureId", Json.fromString(localId)),
        "file",
        Json.fromString(s"$name/media.mov")
      )
      // Part b is a counterfactual replay clock, not an executed probe of the original video.
      val manifestJson =
        if name == "a" then manifestBase
        else
          field(
            manifestBase,
            "streams",
            Json.arr(manifestBase.hcursor.downField("streams").focus.get.asArray.get.map { stream =>
              if stream.hcursor.get[Int]("index").toOption.contains(0) then
                field(stream, "timebase", Json.fromString("1/48000"))
              else stream
            }*)
          )
      val manifest = right(FixtureManifest.parse(manifestJson.noSpaces))
      val peBase = field(
        field(json("f0-v1.ffprobe-envelope.json"), "fixtureId", Json.fromString(localId)),
        "stdoutFile",
        Json.fromString(s"$name/probe.json")
      )
      val stdoutBase = json("f0-v1.ffprobe-stdout.json")
      val stdoutJson =
        if name == "a" then stdoutBase
        else
          field(
            field(
              stdoutBase,
              "streams",
              Json.arr(stdoutBase.hcursor.downField("streams").focus.get.asArray.get.map { stream =>
                if stream.hcursor.get[Int]("index").toOption.contains(0) then
                  field(
                    field(stream, "time_base", Json.fromString("1/48000")),
                    "start_pts",
                    Json.fromLong(1000L)
                  )
                else stream
              }*)
            ),
            "packets",
            Json.arr(stdoutBase.hcursor.downField("packets").focus.get.asArray.get.map { packet =>
              if packet.hcursor.get[Int]("stream_index").toOption.contains(0) then
                field(
                  field(
                    packet,
                    "pts",
                    Json.fromLong(packet.hcursor.get[Long]("pts").toOption.get + 1000L)
                  ),
                  "dts",
                  Json.fromLong(packet.hcursor.get[Long]("dts").toOption.get + 1000L)
                )
              else packet
            }*)
          )
      val stdout = stdoutJson.noSpaces.getBytes(UTF_8)
      val peJson = field(
        field(peBase, "stdoutSha256", Json.fromString(Checksum.ofBytes(stdout).hex)),
        "tool",
        field(
          peBase.hcursor.downField("tool").focus.get,
          "versionLine",
          Json.fromString("synthetic replay clock court; not an executed probe")
        )
      )
      val pe = right(ProbeEnvelope.parse(peJson.noSpaces))
      val probe = right(
        MediaProbe.join(
          manifest,
          right(manifest.verify(resource("f0-v1.mov"))),
          pe.tool,
          pe.args,
          right(FfprobeJson.parse(new String(stdout, UTF_8)))
        )
      )
      val fe =
        right(FramesEnvelope.parse(new String(resource("f0-v1.frames-envelope.json"), UTF_8)))
      // Project-authored colour bytes; this is a declared replay, not an extractor run.
      val frameBytes = selected.flatMap { ordinal =>
        val generated = manifest.streams.head.declaredFrames.get.expectedPts(ordinal) / 1000
        val bgr =
          if generated < 16 then Vector[Byte](30, 30, 200.toByte)
          else if generated < 32 then Vector[Byte](30, 200.toByte, 30)
          else Vector[Byte](200.toByte, 30, 30)
        Vector.fill(32 * 18)(bgr).flatten
      }.toArray
      val sha = Checksum.ofBytes(frameBytes)
      val frames = right(
        SampledFrameSet.join(
          probe,
          0,
          fe.geometry,
          fe.tool,
          fe.args,
          selected,
          frameBytes.length.toLong,
          sha
        )
      )
      val extent = right(CaptionExtent.of("cross-part", examined))
      val request = right(
        CaptionRequest.issue(
          frames,
          Vector(extent),
          baseline.model,
          baseline.recipe,
          s"request-$name"
        )
      )
      val outcome0 = json("f0-v1.caption-outcome.json")
      val result0 = outcome0.hcursor.downField("extents").focus.get.asArray.get.head
      val result = field(
        field(
          field(result0, "id", Json.fromString(extent.id)),
          "ordinals",
          Json.arr(examined.map(Json.fromInt)*)
        ),
        "imageGridThw",
        Json.arr(examined.map(_ => Json.arr(Json.fromInt(1), Json.fromInt(6), Json.fromInt(12)))*)
      )
      val outcome = field(
        field(
          field(
            field(outcome0, "requestId", Json.fromString(request.requestId)),
            "frames",
            field(
              field(
                outcome0.hcursor.downField("frames").focus.get,
                "count",
                Json.fromInt(selected.size)
              ),
              "sha256",
              Json.fromString(sha.hex)
            )
          ),
          "extents",
          Json.arr(result)
        ),
        "coverage",
        Json.obj("requested" -> Json.fromInt(1), "observed" -> Json.fromInt(1))
      )
      val captionEnvelope = field(
        field(
          field(
            field(json("f0-v1.caption-envelope.json"), "fixtureId", Json.fromString(localId)),
            "requestFile",
            Json.fromString(s"$name/request.json")
          ),
          "outcomeFile",
          Json.fromString(s"$name/outcome.json")
        ),
        "outcomeSha256",
        Json.fromString(Checksum.ofText(outcome.noSpaces).hex)
      )
      val frameEnvelope = field(
        field(
          field(json("f0-v1.frames-envelope.json"), "fixtureId", Json.fromString(localId)),
          "frames",
          Json.obj(
            "count" -> Json.fromInt(selected.size),
            "width" -> Json.fromInt(32),
            "height" -> Json.fromInt(18),
            "pixelFormat" -> Json.fromString("bgr24"),
            "byteLength" -> Json.fromLong(frameBytes.length.toLong),
            "sha256" -> Json.fromString(sha.hex)
          )
        ),
        "inputSha256",
        Json.fromString(manifest.sha256.hex)
      )
      assertEquals(
        baseEnvelope.worker,
        right(CaptionEnvelope.parse(captionEnvelope.noSpaces)).worker
      )
      put(s"$name/media.mov", resource("f0-v1.mov"))
      putJson(s"$name/manifest.json", manifestJson)
      putJson(s"$name/probe-envelope.json", peJson)
      put(s"$name/probe.json", stdout)
      putJson(s"$name/frames-envelope.json", frameEnvelope)
      put(s"$name/frames.bgr", frameBytes)
      putJson(s"$name/caption-envelope.json", captionEnvelope)
      put(
        s"$name/request.json",
        request.render(s"$name/frames.bgr", "unused-model").getBytes(UTF_8)
      )
      putJson(s"$name/outcome.json", outcome)
      Json.obj(
        "occurrence" -> Json.fromString(name),
        "manifest" -> Json.fromString(s"$name/manifest.json"),
        "probeEnvelope" -> Json.fromString(s"$name/probe-envelope.json"),
        "framesEnvelope" -> Json.fromString(s"$name/frames-envelope.json"),
        "framesFile" -> Json.fromString(s"$name/frames.bgr"),
        "captionEnvelope" -> Json.fromString(s"$name/caption-envelope.json"),
        "selectedPresentationOrdinals" -> Json.arr(selected.map(Json.fromInt)*)
      )
    }
    new Fixture(
      root,
      Json.obj(
        "schema" -> Json.fromString(CaptionPackage.Schema),
        "schemaVersion" -> Json.fromInt(2),
        "edition" -> Json.fromString(edition.value),
        "files" -> Json.obj(pins.toVector*),
        "parts" -> Json.arr(parts*),
        "extents" -> Json.arr(
          Json.obj(
            "id" -> Json.fromString("cross-part"),
            "ordinals" -> Json.arr(Vector(0, 1, 3, 4).map(Json.fromInt)*)
          )
        )
      )
    )

  private def refused(result: Either[DomainError, CaptionPackage], path: String): Unit =
    result match
      case Left(DomainError.InvariantViolation(found, _)) => assertEquals(found, path)
      case other                                          => fail(s"expected $path, got $other")

  test(
    "ordered parts keep global sample indices, native packet ordinals and local editions distinct"
  ):
    withFixture { f =>
      val p = right(f.read)
      assertEquals(p.authority, ObservationAuthority.Draft)
      assertEquals(p.sampleCount, 5)
      val loc = p.locate(3).get
      assertEquals(
        (loc.partIndex, loc.occurrence, loc.localSampleIndex, loc.presentationOrdinal),
        (1, "b", 0, 2)
      )
      assertEquals(loc.packageIdentity, p.identity)
      assertEquals(
        p.parts.map(_.captions.frames.probe.manifest.fixtureId),
        Vector("local-a", "local-b")
      )
      assertEquals(p.parts(0).sampledEntries.map(_.pts), Vector(0L, 4000L, 11000L))
      assertEquals(p.parts(1).sampledEntries.map(_.pts), Vector(3000L, 16000L))
      assertNotEquals(p.parts(0).frames.index.timebase, p.parts(1).frames.index.timebase)
      assertEquals(p.parts.map(_.frames.index.firstPts), Vector(0L, 1000L))
      assertEquals(p.extents.head._2.map(_._1), Vector(0, 1))
      assertEquals(p.extents.head._2.map(_._2.frames), Vector(Vector(0, 1), Vector(0, 1)))
      assertEquals(p.locate(-1), None)
      assertEquals(p.locate(5), None)
    }

  test("sampled-but-unrequested frames remain unexamined; density is an explicit count ratio"):
    withFixture { f =>
      val part = right(f.read).parts.head
      assertEquals(part.sampleCount, 3)
      assertEquals(part.examinedFraction, (2, 45))
      assertEquals(part.examinedEntries.map(_.presentationOrdinal), Vector(0, 4))
      assertEquals(part.unexaminedOrdinalRanges, Vector(1 until 4, 5 until 45))
      assertEquals(part.examinedEntries.map(_.durationTicks), Vector(Some(1000L), Some(3000L)))
    }

  test("the caller pin binds metadata and part ordering"):
    withFixture { f =>
      val old = f.bytes
      val parts = f.document.hcursor.downField("parts").focus.get.asArray.get
      f.document = field(f.document, "parts", Json.arr(parts.reverse*))
      refused(
        CaptionPackage.load(f.root, f.bytes, Checksum.ofBytes(old), edition),
        "caption-package/manifest"
      )
    }

  test("changed request metadata is refused before its self-consistent content is parsed"):
    withFixture { f =>
      val _ = Files.writeString(f.root.resolve("a/request.json"), "{}")
      refused(f.read, "caption-package/bytes")
    }

  test("changed raw frame bytes are refused"):
    withFixture { f =>
      val _ = Files.write(f.root.resolve("a/frames.bgr"), Array[Byte](1, 2, 3))
      refused(f.read, "caption-package/bytes")
    }

  test("repinned wrong envelope edition still refuses its contextual join"):
    withFixture { f =>
      f.change("a/caption-envelope.json")(j => field(j, "fixtureId", Json.fromString("foreign")))
      refused(f.read, "caption-package/caption")
    }

  test("repinned wrong frame count refuses"):
    withFixture { f =>
      f.change("a/frames-envelope.json") { j =>
        field(j, "frames", field(j.hcursor.downField("frames").focus.get, "count", Json.fromInt(4)))
      }
      refused(f.read, "caption-package/frames")
    }

  test("cross-part extent membership cannot omit or duplicate a local request sample"):
    withFixture { f =>
      f.document = field(
        f.document,
        "extents",
        Json.arr(
          Json.obj(
            "id" -> Json.fromString("cross-part"),
            "ordinals" -> Json.arr(Vector(0, 3, 4).map(Json.fromInt)*)
          )
        )
      )
      refused(f.read, "caption-package/membership")
    }

  test("missing receipts refuse instead of admitting unverified caption text"):
    withFixture { f =>
      Files.delete(f.root.resolve("a/caption-envelope.json"))
      refused(f.read, "caption-package/io")
    }

  test("an envelope cannot escape through a nested symlink"):
    withFixture { f =>
      val outside = Files.createTempFile("caption-outside-", ".json")
      try
        val _ = Files.write(outside, Files.readAllBytes(f.root.resolve("a/outcome.json")))
        Files.delete(f.root.resolve("a/outcome.json"))
        val _ = Files.createSymbolicLink(f.root.resolve("a/outcome.json"), outside)
        refused(f.read, "caption-package/path")
      finally Files.delete(outside)
    }

  test("repinned frame and probe input identities must match the verified media"):
    withFixture { f =>
      f.change("a/frames-envelope.json")(j =>
        field(j, "inputSha256", Json.fromString(Checksum.ofText("other").hex))
      )
      refused(f.read, "caption-package/frames")
    }
    withFixture { f =>
      f.change("a/probe-envelope.json")(j =>
        field(j, "inputSha256", Json.fromString(Checksum.ofText("other").hex))
      )
      refused(f.read, "caption-package/probe")
    }

  test("rehashing a corrupt outcome in the package does not bypass its caption receipt"):
    withFixture { f =>
      f.change("a/outcome.json")(j => field(j, "requestId", Json.fromString("other")))
      refused(f.read, "envelope/outcome")
    }

  test("the selection is bound even when changed samples have identical bytes"):
    withFixture { f =>
      val parts = f.document.hcursor.downField("parts").focus.get.asArray.get
      val changed = field(
        parts.head,
        "selectedPresentationOrdinals",
        Json.arr(Vector(1, 4, 9).map(Json.fromInt)*)
      )
      f.document = field(f.document, "parts", Json.arr((changed +: parts.tail)*))
      refused(f.read, "caption/frame-selection")
    }

  test("repinned reordered parts change global locations and package identity"):
    withFixture { f =>
      val before = right(f.read)
      val parts = f.document.hcursor.downField("parts").focus.get.asArray.get
      f.document = field(f.document, "parts", Json.arr(parts.reverse*))
      f.document = field(
        f.document,
        "extents",
        Json.arr(
          Json.obj(
            "id" -> Json.fromString("cross-part"),
            "ordinals" -> Json.arr(Vector(0, 1, 2, 3).map(Json.fromInt)*)
          )
        )
      )
      val after = right(f.read)
      assertNotEquals(before.identity, after.identity)
      assertEquals(after.locate(0).get.occurrence, "b")
      assertEquals(after.locate(2).get.occurrence, "a")
      assertNotEquals(before.locate(0), after.locate(0))
    }

  test("malformed outcome UTF-8 refuses even with matching package and envelope hashes"):
    withFixture { f =>
      val original = Files.readAllBytes(f.root.resolve("a/outcome.json"))
      val altered = original.clone()
      val pos = new String(original, UTF_8).indexOf("solid")
      assert(pos >= 0)
      altered(pos) = 0xff.toByte
      f.repin("a/outcome.json", altered)
      f.change("a/caption-envelope.json")(j =>
        field(j, "outcomeSha256", Json.fromString(Checksum.ofBytes(altered).hex))
      )
      refused(f.read, "caption-package/utf8")
    }

  test("malformed manifest UTF-8 refuses with a matching external pin"):
    withFixture { f =>
      val b = f.bytes
      val pos = new String(b, UTF_8).indexOf("\"occurrence\":\"a\"") + "\"occurrence\":\"".length
      assert(pos >= "\"occurrence\":\"".length)
      b(pos) = 0xff.toByte
      refused(CaptionPackage.load(f.root, b, Checksum.ofBytes(b), edition), "caption-package/utf8")
    }

  test("malformed probe UTF-8 refuses with matching package and probe envelope hashes"):
    withFixture { f =>
      val b = Files.readAllBytes(f.root.resolve("a/probe.json"))
      val pos = new String(b, UTF_8).indexOf("raw video")
      assert(pos >= 0)
      b(pos) = 0xff.toByte
      f.repin("a/probe.json", b)
      f.change("a/probe-envelope.json")(j =>
        field(j, "stdoutSha256", Json.fromString(Checksum.ofBytes(b).hex))
      )
      refused(f.read, "caption-package/utf8")
    }

  test("legitimate UTF-8 replacement characters remain legitimate content"):
    withFixture { f =>
      val parts = f.document.hcursor.downField("parts").focus.get.asArray.get
      f.document = field(
        f.document,
        "parts",
        Json.arr((field(parts.head, "occurrence", Json.fromString("\uFFFD")) +: parts.tail)*)
      )
      assertEquals(right(f.read).parts.head.occurrence, "\uFFFD")
    }

  test("overlapping extents retain both proposals but count examined samples once"):
    withFixture { f =>
      val original = f.document.hcursor.downField("extents").focus.get.asArray.get
      f.document = field(
        f.document,
        "extents",
        Json.arr(
          (original :+ Json.obj(
            "id" -> Json.fromString("overlap"),
            "ordinals" -> Json.arr(Json.fromInt(1), Json.fromInt(4))
          ))*
        )
      )
      Vector("a", "b").foreach { part =>
        f.change(s"$part/request.json") { j =>
          val ex = j.hcursor.downField("extents").focus.get.asArray.get
          field(
            j,
            "extents",
            Json.arr(
              (ex :+ Json
                .obj("id" -> Json.fromString("overlap"), "ordinals" -> Json.arr(Json.fromInt(1))))*
            )
          )
        }
        f.change(s"$part/outcome.json") { j =>
          val ex = j.hcursor.downField("extents").focus.get.asArray.get
          val extra = field(
            field(
              field(ex.head, "id", Json.fromString("overlap")),
              "ordinals",
              Json.arr(Json.fromInt(1))
            ),
            "imageGridThw",
            Json.arr(Json.arr(Json.fromInt(1), Json.fromInt(6), Json.fromInt(12)))
          )
          field(
            field(j, "extents", Json.arr((ex :+ extra)*)),
            "coverage",
            Json.obj("requested" -> Json.fromInt(2), "observed" -> Json.fromInt(2))
          )
        }
        val sha = Checksum.ofBytes(Files.readAllBytes(f.root.resolve(s"$part/outcome.json")))
        f.change(s"$part/caption-envelope.json")(j =>
          field(j, "outcomeSha256", Json.fromString(sha.hex))
        )
      }
      val loaded = right(f.read)
      assertEquals(loaded.parts.map(_.examinedCount), Vector(2, 2))
      assertEquals(loaded.parts.map(_.captions.proposals.size), Vector(2, 2))
    }
