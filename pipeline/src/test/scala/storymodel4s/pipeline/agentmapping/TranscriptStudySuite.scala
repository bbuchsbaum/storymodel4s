package storymodel4s.pipeline.agentmapping

import munit.FunSuite
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import storymodel4s.codec.TimedSourceArtifactCodec
import storymodel4s.core.Checksum
import storymodel4s.provider.agent.TextExecution

/** Source: project-authored geometric diagnostics from StudyExecutionSuite, 2026. Basis: repository
  * Apache-2.0; no participant material or story excerpt. Jurisdiction: Toronto, Canada. No human
  * narrator, participant or traditional source. Modifications: repeated sentence, whitespace, CRLF,
  * emoji and decomposed accent to test offsets; source-label substitution tests a mismatched
  * source. Tradition: none. Admitted by: independent /root/live_design_review, 2026-11-15,
  * pre-admission file edition SHA-256
  * 1fe41612e43a4dc4636fb48282928f87980f11f09e59327050bb603c493fe999.
  */
class TranscriptStudySuite extends FunSuite:
  private def right[A](v: Either[?, A]): A = v.fold(e => fail(e.toString), identity)
  private def str(s: String): Json = Json.fromString(s)
  private def read(p: Path): Json = right(io.circe.jawn.parse(Files.readString(p)))
  private val raw =
    "\r\nThe square\r\nmoved 😀 e\u0301.  \r\n\r\n\r\nThe circle rested. The circle rested.\r\n"
  private val canonical = "The square\nmoved 😀 e\u0301.\n\nThe circle rested. The circle rested."
  private def prepared = right(PreparedRecall.segmented(raw.getBytes(UTF_8)))
  private def withDir(f: Path => Unit): Unit =
    val dir = Files.createTempDirectory("transcript-study-test-")
    try f(dir)
    finally
      val walk = Files.walk(dir)
      try walk.iterator.asScala.toVector.reverse.foreach(p => { Files.delete(p); () })
      finally walk.close()

  private def fixture(dir: Path): Path =
    Files.writeString(dir.resolve("recall.txt"), raw): Unit
    Files.writeString(
      dir.resolve("source.csv"),
      "ordinal,text,part,onset,end,group,group_label,locations,characters\n" +
        "11,square moves,part,0,10,10,first,,\n22,circle rests,part,10,20,30,second,,\n"
    ): Unit
    Files.writeString(
      dir.resolve("parts.json"),
      """[{"part":"part","start":"0","end":"20","numerator":"1","denominator":"1"}]"""
    ): Unit
    val manifest = Json.obj(
      "schema" -> str(TranscriptStudy.Schema),
      "model" -> str("synthetic-model"),
      "condition" -> str("transcript-test"),
      "chunkSize" -> Json.fromInt(50),
      "sessions" -> Json.arr(
        Json.obj(
          "participant" -> str("synthetic"),
          "session" -> str("trial"),
          "transcript" -> str("recall.txt")
        )
      )
    )
    Files.writeString(dir.resolve("manifest.json"), manifest.noSpaces): Unit
    val out = dir.resolve("export")
    right(
      TranscriptStudy.prepare(
        dir.resolve("source.csv"),
        dir.resolve("parts.json"),
        dir.resolve("manifest.json"),
        out
      )
    )
    out

  test("segmentation retains exact canonical UTF16 spans, repeated phrases and raw bytes"):
    val recall = prepared
    assertEquals(recall.graph.transcript.canonicalText, canonical)
    assertEquals(
      recall.graph.transcript.rawText.getBytes(UTF_8).toVector,
      raw.getBytes(UTF_8).toVector
    )
    assertEquals(recall.segmentation.artifactSha256, Checksum.ofBytes(raw.getBytes(UTF_8)))
    assertEquals(
      recall.texts,
      Vector("The square\nmoved 😀 e\u0301.", "The circle rested.", "The circle rested.")
    )
    val expected = Vector((0, 23), (25, 43), (44, 62))
    // Explicit offsets below are in UTF-16, including two code units for the emoji.
    val actual = recall.graph.ordered.map(_.span.spans.toVector.map(s => (s.start, s.endExclusive)))
    assertEquals(actual, expected.map(Vector(_)))
    recall.graph.ordered.foreach { u =>
      assertEquals(
        u.span.spans.toVector.map(s => canonical.substring(s.start, s.endExclusive)).mkString,
        u.text
      )
    }
    assertNotEquals(recall.unitIds(1), recall.unitIds(2))
    assertEquals(
      recall.transcriptDescriptor.get.hcursor.get[String]("offsetUnit"),
      Right("utf-16-code-units")
    )

  test("strict UTF8 and empty transcript refusals do not create a plausible segmentation"):
    Vector(Array(0xc3.toByte, 0x28.toByte), Array.emptyByteArray, "\uFEFF \r\n".getBytes(UTF_8))
      .foreach { bytes =>
        assert(PreparedRecall.segmented(bytes).isLeft)
      }

  test("raw variants retain canonical units but distinct raw provenance and word identities"):
    val a = prepared
    val b = right(PreparedRecall.segmented(canonical.getBytes(UTF_8)))
    assertEquals(a.texts, b.texts)
    assertEquals(a.graph.transcript.canonicalChecksum, b.graph.transcript.canonicalChecksum)
    assertNotEquals(a.segmentation.artifactSha256, b.segmentation.artifactSha256)
    assertNotEquals(a.inventory.words.head.id, b.inventory.words.head.id)

  test("preparation is local and multiline prompt text is lossless JSON on one line"):
    withDir { dir =>
      val out = fixture(dir)
      val index = right(StudyExport.index(out))
      val session = right(StudyExport.session(out, index.sessions.head, transcript = true))
      assertEquals(session.preparedRecall("x"), Right(prepared))
      val line = index.line(session.units.head)
      assert(!line.contains('\n'))
      assertEquals(
        right(io.circe.jawn.parse(line.stripPrefix("[1] "))).asString,
        Some(prepared.texts.head)
      )
      assertEquals(
        StudyReplay
          .validate(session.units, session.chunks, index.line, c => s"Map units ${c.lo}-${c.hi}."),
        Right(())
      )
      assertEquals(
        Files.readAllBytes(out.resolve("source.csv")).toVector,
        Files.readAllBytes(dir.resolve("source.csv")).toVector
      )
      assert(
        TranscriptStudy
          .prepare(
            dir.resolve("source.csv"),
            dir.resolve("parts.json"),
            dir.resolve("manifest.json"),
            out
          )
          .isLeft
      )
    }

  test("preparation refuses a condition that cannot become a mapping policy"):
    withDir { dir =>
      val out = fixture(dir)
      val manifest =
        read(dir.resolve("manifest.json")).mapObject(_.add("condition", str("x" * 256)))
      Files.writeString(dir.resolve("manifest.json"), manifest.noSpaces): Unit
      val target = dir.resolve("unusable-export")
      val result = TranscriptStudy.prepare(
        out.resolve("source.csv"),
        out.resolve("parts.json"),
        dir.resolve("manifest.json"),
        target
      )
      assert(result.left.exists(_.contains("policy identifier")))
      assert(!Files.exists(target))
    }

  test("export readback regenerates every descriptor field, text and unit key"):
    withDir { dir =>
      val out = fixture(dir)
      val index = right(StudyExport.index(out))
      val entry = index.sessions.head
      val original = read(out.resolve(entry.file))
      val descriptor = original.hcursor.downField("transcript").focus.get
      Vector(
        "recipe",
        "rawSha256",
        "canonicalSha256",
        "canonicalization",
        "offsetUnit",
        "canonicalText"
      )
        .foreach { key =>
          val changed =
            original.mapObject(_.add("transcript", descriptor.mapObject(_.add(key, str("wrong")))))
          assert(
            StudyExport
              .parseSession(changed, entry, transcript = true)
              .left
              .exists(_.contains("descriptor differs")),
            key
          )
        }
      val wrongSpan = descriptor.mapObject(
        _.add(
          "units",
          descriptor.hcursor
            .downField("units")
            .focus
            .get
            .mapArray(us =>
              us.updated(
                0,
                us.head.mapObject(
                  _.add(
                    "spans",
                    Json
                      .arr(Json.obj("start" -> Json.fromInt(1), "endExclusive" -> Json.fromInt(23)))
                  )
                )
              )
            )
        )
      )
      assert(
        StudyExport
          .parseSession(original.mapObject(_.add("transcript", wrongSpan)), entry, true)
          .isLeft
      )
      val wrongKeys = original.mapObject(
        _.add(
          "units",
          original.hcursor
            .downField("units")
            .focus
            .get
            .mapArray(us => us.updated(0, us.head.mapObject(_.add("u", Json.fromInt(99)))))
        )
      )
      assert(
        StudyExport.parseSession(wrongKeys, entry, true).left.exists(_.contains("units differ"))
      )
      assert(StudyExport.parseSession(original, entry).isLeft)
      val badEncoding =
        read(out.resolve("index.json")).mapObject(_.add("unitTextEncoding", str("flattened/v1")))
      assert(StudyExport.parseIndex(badEncoding).isLeft)
    }

  private def execute(
      dir: Path,
      out: Path,
      name: String,
      mode: StudyExecution.Mode,
      env: Map[String, String]
  ): Either[String, Json] =
    StudyExecution.run(
      out.resolve("source.csv"),
      out.resolve("parts.json"),
      out,
      dir.resolve("run"),
      dir.resolve(name),
      mode,
      env,
      512L,
      1000L,
      1
    )

  test("automatic transcript live and offline replay use the common checked mapping path"):
    withDir { dir =>
      val out = fixture(dir)
      val calls = new AtomicInteger(0)
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext(
        "/v1/chat/completions",
        exchange => {
          calls.incrementAndGet(): Unit
          val request =
            right(io.circe.jawn.parse(new String(exchange.getRequestBody.readAllBytes(), UTF_8)))
          assert(
            request.noSpaces.contains("\\\\n"),
            "the prompt contains an encoded internal newline"
          )
          val reply = Json.obj("units" -> Json.fromValues((1 to 3).map { n =>
            Json.obj(
              "u" -> Json.fromInt(n),
              "narrative" -> Json.fromInt(if n == 1 then 10 else 30),
              "narrative_film" -> Json.Null,
              "referent" -> Json.fromInt(30),
              "referent_film" -> Json.Null
            )
          }))
          val response = Json.obj(
            "model" -> str("synthetic-model"),
            "choices" -> Json.arr(
              Json.obj(
                "finish_reason" -> str("stop"),
                "message" -> Json.obj("content" -> str(reply.noSpaces))
              )
            ),
            "usage" -> Json.obj(
              "prompt_tokens" -> Json.fromInt(50),
              "completion_tokens" -> Json.fromInt(25)
            )
          )
          val bytes = response.noSpaces.getBytes(UTF_8)
          exchange.sendResponseHeaders(200, bytes.length.toLong)
          try exchange.getResponseBody.write(bytes)
          finally exchange.close()
        }
      ): Unit
      server.start()
      val env = Map(
        "STORYMODEL4S_AGENT_BACKEND" -> "openai",
        "STORYMODEL4S_AGENT_LIVE" -> "1",
        "STORYMODEL4S_OPENAI_BASE_URL" -> s"http://127.0.0.1:${server.getAddress.getPort}/v1",
        "STORYMODEL4S_OPENAI_MODEL" -> "synthetic-model"
      )
      try
        val liveReceipt = right(execute(dir, out, "live", StudyExecution.Mode.Live, env))
        assertEquals(liveReceipt.hcursor.get[Int]("modelCalls"), Right(1))
        right(execute(dir, out, "offline", StudyExecution.Mode.Replay, Map.empty))
        assertEquals(calls.get(), 1)
        val live = read(dir.resolve("live/bundles/synthetic_trial.bundle.json"))
        val offline = read(dir.resolve("offline/bundles/synthetic_trial.bundle.json"))
        Vector("units", "interpretations", "recall").foreach(k =>
          assertEquals(live.hcursor.downField(k).focus, offline.hcursor.downField(k).focus)
        )
        val view = right(
          TimedSourceArtifactCodec.prepareChecked(
            Files.readAllBytes(out.resolve("source.csv")),
            Files.readAllBytes(out.resolve("parts.json"))
          )
        )
        val index = right(StudyExport.index(out))
        val recall = prepared
        val profile = right(
          TextExecution.Profile.decode(
            live.hcursor.downField("provenance").downField("execution").focus.get
          )
        )
        val expected = StudyBundle.Expected(
          "synthetic",
          "trial",
          TranscriptStudy.units(recall),
          StudyRecord.producer(profile.provider, index.model, index.system, view, recall),
          None,
          Some(profile),
          1
        )
        assert(StudyBundle.decode(live, view, recall, expected).isRight)
        val changed = live.mapObject(
          _.add(
            "recall",
            live.hcursor
              .downField("recall")
              .focus
              .get
              .mapObject(_.add("canonicalText", str("wrong")))
          )
        )
        assert(StudyBundle.decode(changed, view, recall, expected).isLeft)
        val recipe = live.mapObject(
          _.add(
            "provenance",
            live.hcursor
              .downField("provenance")
              .focus
              .get
              .mapObject(o =>
                o.add(
                  "segmentation",
                  o("segmentation").get.mapObject(_.add("recipe", str("wrong")))
                )
              )
          )
        )
        assert(StudyBundle.decode(recipe, view, recall, expected).isLeft)
        Files.createDirectories(Path.of("pipeline/target")): Unit
        Files.writeString(
          Path.of("pipeline/target/synthetic-transcript.bundle.json"),
          live.noSpaces
        ): Unit
      finally server.stop(0)
    }

  test("changed source and transcript provenance refuse before opening a live execution"):
    withDir { dir =>
      val out = fixture(dir)
      Files.writeString(
        out.resolve("source.csv"),
        Files.readString(out.resolve("source.csv")).replace("square moves", "square rests")
      ): Unit
      val result = execute(dir, out, "bad", StudyExecution.Mode.Live, Map.empty)
      assert(result.left.exists(_.contains("another source")))
      assert(!Files.exists(dir.resolve("run")))
      val index = read(out.resolve("index.json")).mapObject(
        _.add(
          "sourceSha256",
          str(Checksum.ofBytes(Files.readAllBytes(out.resolve("source.csv"))).hex)
        )
      )
      Files.writeString(out.resolve("index.json"), index.noSpaces): Unit
      val relabelled = execute(dir, out, "relabelled", StudyExecution.Mode.Live, Map.empty)
      assert(relabelled.left.exists(_.contains("prompt differs from the checked source")))
      assert(!Files.exists(dir.resolve("run")))
    }

  test("a later malformed transcript prevents publication of the entire prepared study"):
    withDir { dir =>
      val out = fixture(dir)
      val m = read(dir.resolve("manifest.json"))
      val first = m.hcursor.get[Vector[Json]]("sessions").toOption.get.head
      val changed = m.mapObject(
        _.add(
          "sessions",
          Json.arr(
            first,
            first.mapObject(_.add("session", str("bad")).add("transcript", str("bad.txt")))
          )
        )
      )
      Files.writeString(dir.resolve("manifest.json"), changed.noSpaces): Unit
      Files.write(dir.resolve("bad.txt"), Array(0xc3.toByte)): Unit
      val target = dir.resolve("bad-export")
      assert(
        TranscriptStudy
          .prepare(
            out.resolve("source.csv"),
            out.resolve("parts.json"),
            dir.resolve("manifest.json"),
            target
          )
          .isLeft
      )
      assert(!Files.exists(target))
    }
