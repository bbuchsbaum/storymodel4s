package storymodel4s.pipeline.agentmapping

import munit.FunSuite
import com.sun.net.httpserver.HttpServer
import io.circe.Json
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.core.Checksum
import storymodel4s.provider.agent.{LiveAuthorization, TextExchange, TextExecution}

/** The live exchange contract is consumed outside its defining package. */
class StudyExecutionSuite extends FunSuite:
  private def right[A](value: Either[?, A]): A = value.fold(e => fail(e.toString), identity)

  test("a live text profile is consumed outside provider-agent without borrowing a legacy key"):
    val authorization = right(
      LiveAuthorization.from(
        Map(
          "STORYMODEL4S_AGENT_BACKEND" -> "openai",
          "STORYMODEL4S_AGENT_LIVE" -> "1",
          "STORYMODEL4S_OPENAI_BASE_URL" -> "http://127.0.0.1:1/v1",
          "STORYMODEL4S_OPENAI_MODEL" -> "synthetic-model"
        )
      )
    ).asInstanceOf[LiveAuthorization.OpenAiCompatible]
    val profile = right(TextExecution.Profile.from(authorization, 128L, 1000L))
    val larger = right(TextExecution.Profile.from(authorization, 256L, 1000L))
    assertEquals(right(TextExecution.Profile.decode(profile.json)), profile)
    assertNotEquals(profile.key("system", "prompt"), larger.key("system", "prompt"), "token budget")
    assertNotEquals(
      profile.key("system", "prompt"),
      TextExchange.key(profile.provider, profile.model, "system", "prompt"),
      "legacy key domain"
    )

  private def read(path: Path): Json = right(io.circe.jawn.parse(Files.readString(path)))
  private def str(s: String): Json = Json.fromString(s)

  /** Authored geometric events only: no story excerpt, real recall or participant material. */
  private def fixture(dir: Path): Path =
    val out = Files.createDirectory(dir.resolve("export"))
    val texts = Vector("The square moved.", "The circle rested.")
    val numbers = Vector(0, 7)
    val lines = numbers.zip(texts).map((n, t) => s"[$n] $t").mkString("\n")
    val prompts = numbers.map(n => s"$lines\nmap $n-$n")
    Files.writeString(
      out.resolve("source.csv"),
      "ordinal,text,part,onset,end,group,group_label,locations,characters\n" +
        "11,square moves,part,0,10,10,first,,\n22,circle rests,part,10,20,30,second,,\n"
    ): Unit
    Files.writeString(
      out.resolve("parts.json"),
      """[{"part":"part","start":"0","end":"20","numerator":"1","denominator":"1"}]"""
    ): Unit
    val session = Json.obj(
      "participant" -> str("synthetic"),
      "session" -> str("trial"),
      "units" -> Json.fromValues(numbers.zip(texts).map { (n, t) =>
        Json.obj("u" -> Json.fromInt(n), "row" -> str(s"row-$n"), "text" -> str(t))
      }),
      "chunks" -> Json.fromValues(numbers.zip(prompts).map { (n, prompt) =>
        Json.obj("lo" -> Json.fromInt(n), "hi" -> Json.fromInt(n), "prompt" -> str(prompt))
      })
    )
    Files.writeString(out.resolve("session.json"), session.noSpaces): Unit
    val index = Json.obj(
      "schema" -> str(StudyExport.Schema),
      "model" -> str("synthetic-model"),
      "condition" -> str("synthetic-live"),
      "system" -> str("Return the requested unit mappings."),
      "systemSha256" -> str(Checksum.ofText("Return the requested unit mappings.").hex),
      "replyProtocol" -> str("coercing-open/v1"),
      "attempts" -> Json.fromInt(3),
      "retrySuffix" -> str("\nretry {attempt}"),
      "unitLine" -> str("[{u}] {text}"),
      "chunkLine" -> str("map {lo}-{hi}"),
      "manifestSha256" -> str("0" * 64),
      "extras" -> Json.arr(str("conf")),
      "sessions" -> Json.arr(
        Json.obj(
          "participant" -> str("synthetic"),
          "session" -> str("trial"),
          "file" -> str("session.json"),
          "promptSha256" -> Json.fromValues(prompts.map(p => str(Checksum.ofText(p).hex)))
        )
      )
    )
    Files.writeString(out.resolve("index.json"), index.noSpaces): Unit
    out

  private def withDirectory(body: Path => Unit): Unit =
    val dir = Files.createTempDirectory("synthetic-live-study-")
    try body(dir)
    finally
      val walk = Files.walk(dir)
      try walk.iterator.asScala.toVector.reverse.foreach(p => { Files.delete(p); () })
      finally walk.close()

  private def completion(text: String): String = Json
    .obj(
      "model" -> str("synthetic-reported-model"),
      "choices" -> Json.arr(
        Json.obj("finish_reason" -> str("stop"), "message" -> Json.obj("content" -> str(text)))
      ),
      "usage" -> Json
        .obj("prompt_tokens" -> Json.fromInt(17), "completion_tokens" -> Json.fromInt(11))
    )
    .noSpaces

  private val first = """{"units":[{"u":0,"narrative":10,"referent":30,"conf":null}]}"""
  private val second = """{"units":[{"u":7,"narrative":30,"referent":30}]}"""

  private def withServer(responses: Vector[(Int, String)], onRequest: Int => Unit = _ => ())(
      body: (Map[String, String], AtomicInteger) => Unit
  ): Unit =
    val count = new AtomicInteger(0)
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/v1/chat/completions",
      exchange => {
        val request = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        assertEquals(
          right(io.circe.jawn.parse(request)).hcursor.get[String]("model"),
          Right("synthetic-model")
        )
        val i = count.getAndIncrement()
        onRequest(i)
        val (status, text) = responses.lift(i).getOrElse(500 -> "unexpected request")
        val raw = text.getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, raw.length.toLong)
        try exchange.getResponseBody.write(raw)
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
    try body(env, count)
    finally server.stop(0)

  private def run(
      dir: Path,
      exportDir: Path,
      env: Map[String, String],
      output: String,
      mode: StudyExecution.Mode = StudyExecution.Mode.Live,
      budget: Int = 100
  ): Either[String, Json] =
    StudyExecution.run(
      exportDir.resolve("source.csv"),
      exportDir.resolve("parts.json"),
      exportDir,
      dir.resolve("run"),
      dir.resolve(output),
      mode,
      env,
      128L,
      2000L,
      budget
    )

  test(
    "live transport, format retry, budget stop, resume and offline replay preserve checked mappings"
  ):
    withDirectory { dir =>
      val exportDir = fixture(dir)
      withServer(
        Vector(
          429 -> "limited",
          500 -> "retry",
          200 -> completion("not JSON"),
          200 -> completion(first),
          200 -> completion(second)
        )
      ) { (env, calls) =>
        val stopped = run(dir, exportDir, env, "live", budget = 2)
        assert(stopped.left.exists(_.contains("new-call budget exhausted")))
        assertEquals(calls.get(), 4)
        assert(!Files.exists(dir.resolve("live")))
        val resumed = right(run(dir, exportDir, env, "live", budget = 1))
        assertEquals(resumed.hcursor.get[Int]("modelCalls"), Right(1))
        assertEquals(resumed.hcursor.get[Int]("httpAttempts"), Right(1))
        assertEquals(resumed.hcursor.get[Int]("cacheHits"), Right(2))
        assertEquals(calls.get(), 5)
        val offline = right(run(dir, exportDir, Map.empty, "offline", StudyExecution.Mode.Replay))
        assertEquals(offline.hcursor.get[Int]("modelCalls"), Right(0))
        assertEquals(offline.hcursor.get[Int]("httpAttempts"), Right(0))
        assertEquals(calls.get(), 5)
        val live = read(dir.resolve("live/bundles/synthetic_trial.bundle.json"))
        val replay = read(dir.resolve("offline/bundles/synthetic_trial.bundle.json"))
        assertEquals(live.hcursor.downField("units").focus, replay.hcursor.downField("units").focus)
        assertEquals(
          live.hcursor.downField("interpretations").focus,
          replay.hcursor.downField("interpretations").focus
        )
        assertEquals(live.hcursor.downField("provenance").get[Int]("modelCalls"), Right(1))
        assertEquals(replay.hcursor.downField("provenance").get[Int]("modelCalls"), Right(0))
        val changed = run(
          dir,
          exportDir,
          env.updated(
            "STORYMODEL4S_OPENAI_BASE_URL",
            env("STORYMODEL4S_OPENAI_BASE_URL") + "/changed"
          ),
          "changed"
        )
        assert(changed.left.exists(_.contains("configuration changed")))
        assertEquals(calls.get(), 5)
        Files.createDirectories(Path.of("pipeline/target")): Unit
        Files.writeString(Path.of("pipeline/target/synthetic-live.bundle.json"), live.noSpaces): Unit
      }
    }

  test("preflight checks later sessions before the first live request"):
    withDirectory { dir =>
      val exportDir = fixture(dir)
      val index = read(exportDir.resolve("index.json"))
      val entry = index.hcursor.get[Vector[Json]]("sessions").toOption.get.head
      val invalid =
        entry.mapObject(_.add("participant", str("missing")).add("file", str("missing.json")))
      Files.writeString(
        exportDir.resolve("index.json"),
        index.mapObject(_.add("sessions", Json.arr(entry, invalid))).noSpaces
      ): Unit
      withServer(Vector.empty) { (env, calls) =>
        assert(run(dir, exportDir, env, "output").isLeft)
        assertEquals(calls.get(), 0)
        assert(!Files.exists(dir.resolve("run/run.json")))
      }
    }

  test("preflight refuses oversized policy identifiers before any dispatch or run descriptor"):
    withDirectory { dir =>
      val exportDir = fixture(dir)
      val path = exportDir.resolve("index.json")
      Files.writeString(path, read(path).mapObject(_.add("condition", str("x" * 256))).noSpaces): Unit
      withServer(Vector.empty) { (env, calls) =>
        assert(run(dir, exportDir, env, "output").left.exists(_.contains("policy identifier")))
        assertEquals(calls.get(), 0)
        assert(!Files.exists(dir.resolve("run/run.json")))
      }
    }

  test("a failed invocation receipt never publishes output and recorded replies remain reusable"):
    withDirectory { dir =>
      val exportDir = fixture(dir)
      val receipts = dir.resolve("run/invocations")
      withServer(Vector(200 -> completion(first), 200 -> completion(second)), i => {
        if i == 0 then
          Files.delete(receipts)
          Files.writeString(receipts, "blocked"): Unit
      }) { (env, calls) =>
        assert(run(dir, exportDir, env, "output").isLeft)
        assertEquals(calls.get(), 2)
        assert(!Files.exists(dir.resolve("output")))
        Files.delete(receipts)
        val resumed = right(run(dir, exportDir, env, "output"))
        assertEquals(resumed.hcursor.get[Int]("modelCalls"), Right(0))
        assertEquals(calls.get(), 2)
        assertEquals(read(dir.resolve("output/receipt.json")), resumed)
      }
    }

  test("bundle readback binds execution configuration and per-session call accounting"):
    withDirectory { dir =>
      val exportDir = fixture(dir)
      withServer(Vector(200 -> completion(first), 200 -> completion(second))) { (env, _) =>
        right(run(dir, exportDir, env, "output"))
        val doc = read(dir.resolve("output/bundles/synthetic_trial.bundle.json"))
        val view = right(storymodel4s.codec.TimedSourceArtifactCodec.prepareChecked(
          Files.readAllBytes(exportDir.resolve("source.csv")), Files.readAllBytes(exportDir.resolve("parts.json"))))
        val index = right(StudyExport.parseIndex(read(exportDir.resolve("index.json"))))
        val entry = index.sessions.head
        val session = right(StudyExport.parseSession(read(exportDir.resolve(entry.file)), entry))
        val recall = right(PreparedRecall.supplied(entry.name, session.units.map(_.text), StudyExport.unitsChecksum(session.units)))
        val profile = right(TextExecution.Profile.decode(read(dir.resolve("run/run.json")).hcursor.downField("execution").focus.get))
        val producer = StudyRecord.producer(profile.provider, index.model, index.system, view, recall)
        val expected = StudyBundle.Expected(entry.participant, entry.session, session.units, producer,
          execution = Some(profile), modelCalls = 2)
        assert(StudyBundle.decode(doc, view, recall, expected).isRight)
        def changed(key: String, value: Json): Json = doc.mapObject(o => o.add("provenance",
          o("provenance").get.mapObject(p => p.add("execution", p("execution").get.mapObject(_.add(key, value))))))
        assert(StudyBundle.decode(changed("endpointSha256", str("f" * 64)), view, recall, expected).isLeft)
        assert(StudyBundle.decode(changed("maxTokens", Json.fromInt(129)), view, recall, expected).isLeft)
        assert(StudyBundle.decode(doc, view, recall, expected.copy(modelCalls = 1)).isLeft)
        assert(StudyBundle.decode(doc, view, recall, expected.copy(execution = None)).isLeft)
      }
    }

  test("terminal transport failure remains bounded and is not reset by resume"):
    withDirectory { dir =>
      val exportDir = fixture(dir)
      withServer(Vector.fill(3)(503 -> "unavailable")) { (env, calls) =>
        assert(run(dir, exportDir, env, "output").isLeft)
        assertEquals(calls.get(), 3)
        assert(run(dir, exportDir, env, "output").isLeft)
        assertEquals(calls.get(), 3)
        assert(!Files.exists(dir.resolve("output")))
      }
    }

  test("the checked profile closes construction doors outside provider-agent"):
    assert(
      typeCheckErrors("""
      import storymodel4s.provider.agent.{ModelRequest, TextExecution}
      def request(p: TextExecution.Profile) = ModelRequest.text(p, "system", "prompt")
    """).nonEmpty
    )
    assert(
      typeCheckErrors("""
      import storymodel4s.provider.agent.TextExecution
      object ModelRequest:
        def text(p: TextExecution.Profile, system: String, prompt: String): String = system + prompt
      def request(p: TextExecution.Profile) = ModelRequest.text(p, "system", "prompt")
    """).isEmpty
    )
    assert(typeCheckErrors("""
      import storymodel4s.provider.agent.TextExecution
      import storymodel4s.core.Checksum
      new TextExecution.Profile("p", "m", Checksum.ofText("endpoint"), 1L, 1L)
    """).nonEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.core.Checksum
      final class Profile(p: String, m: String, h: Checksum, n: Long, t: Long)
      new Profile("p", "m", Checksum.ofText("endpoint"), 1L, 1L)
    """).isEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.provider.agent.TextExecution
      import storymodel4s.core.Checksum
      TextExecution.Profile("p", "m", Checksum.ofText("endpoint"), 1L, 1L)
    """).nonEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.core.Checksum
      final case class Profile(p: String, m: String, h: Checksum, n: Long, t: Long)
      Profile("p", "m", Checksum.ofText("endpoint"), 1L, 1L)
    """).isEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.provider.agent.TextExecution
      def change(p: TextExecution.Profile) = p.copy(maxTokens = 0L)
    """).nonEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.core.Checksum
      final case class Profile(provider: String, model: String, endpoint: Checksum,
        maxTokens: Long, timeoutMillis: Long)
      def change(p: Profile) = p.copy(maxTokens = 0L)
    """).isEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.provider.agent.TextExecution
      import storymodel4s.core.Checksum
      TextExecution.Profile.fromProduct(("p", "m", Checksum.ofText("endpoint"), 1L, 1L))
    """).nonEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.core.Checksum
      final case class Profile(provider: String, model: String, endpoint: Checksum,
        maxTokens: Long, timeoutMillis: Long)
      object Profile:
        def fromProduct(p: Product): Profile = summon[scala.deriving.Mirror.ProductOf[Profile]].fromProduct(p)
      Profile.fromProduct(("p", "m", Checksum.ofText("endpoint"), 1L, 1L))
    """).isEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.provider.agent.TextExecution
      summon[scala.deriving.Mirror.ProductOf[TextExecution.Profile]]
    """).nonEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.core.Checksum
      final case class Profile(provider: String, model: String, endpoint: Checksum,
        maxTokens: Long, timeoutMillis: Long)
      summon[scala.deriving.Mirror.ProductOf[Profile]]
    """).isEmpty)
