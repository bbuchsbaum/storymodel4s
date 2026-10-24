package storymodel4s.pipeline

import io.circe.Json
import java.nio.file.Files
import munit.FunSuite
import storymodel4s.core.Checksum

/** Courts for ADR 0027 §5 on a synthetic film: two people, Ana in two shots and Ben in the second,
  * with reviewed presence over both shots. No story text enters these fixtures (rule 14d).
  */
class SourceIdentitySuite extends FunSuite:
  private def s(v: String): Json = Json.fromString(v)
  private def span(a: Long, z: Long): Json = Json.arr(Json.fromLong(a), Json.fromLong(z))
  private def mention(
      id: String,
      origin: String,
      stream: String,
      a: Long,
      z: Long,
      name: Option[String] = None,
      context: Option[String] = None
  ): Json = Json.obj(
    Vector(
      "id" -> s(id),
      "origin" -> s(origin),
      "stream" -> s(stream),
      "intervals" -> Json.arr(span(a, z))
    ) ++ name.map("name" -> s(_)) ++ context.map("context" -> s(_))*
  )
  private def presence(a: Long, z: Long, basis: String = "human-reviewed"): Json = Json.obj(
    "basis" -> s(basis),
    "stream" -> s("picture"),
    "intervals" -> Json.arr(span(a, z)),
    "receipt" -> Json.obj(
      "algorithm" -> s("annotation-presence/v1"),
      "parameters" -> s(s"row:$a"),
      "inputs" -> Json.arr(s(Checksum.ofText("annotation").hex))
    )
  )
  private def admitted: Json = Json.obj("admitted" -> Json.True)
  private def refusedBy(reason: String): Json =
    Json.obj("admitted" -> Json.False, "reason" -> s(reason))
  private def proposal(
      id: String,
      kind: String,
      left: String,
      right: String,
      producer: Json = admitted,
      extra: Vector[(String, Json)] = Vector.empty
  ): Json = Json.obj(
    Vector(
      "id" -> s(id),
      "kind" -> s(kind),
      "left" -> s(left),
      "right" -> s(right),
      "producer" -> producer
    ) ++ extra*
  )
  private def named(id: String, l: String, r: String, n: String) =
    proposal(id, "named", l, r, extra = Vector("name" -> s(n)))

  private val mentions = Vector(
    mention("v-ana-1", "visual", "picture", 1000, 2000, Some("Ana")),
    mention("v-ana-2", "visual", "picture", 5000, 6000, Some("Ana")),
    mention("v-ben", "visual", "picture", 5000, 6000, Some("Ben")),
    mention("d-ana", "dialogue", "audio", 500, 800, Some("Ana"), Some("ctx-s1")),
    mention("d-she", "dialogue", "audio", 1200, 1400, context = Some("ctx-s2")),
    mention("d-he", "dialogue", "audio", 1500, 1600, context = Some("ctx-s2")),
    mention("d-woman", "dialogue", "audio", 5200, 5400, context = Some("ctx-s3"))
  )
  private val proposals = Vector(
    named("p1", "v-ana-1", "v-ana-2", "Ana"),
    named("p2", "d-ana", "v-ana-1", "Ana"),
    proposal("p3", "deictic", "d-she", "v-ana-1"),
    // The resolver would license this one: only the producer's speaker guard stops it.
    proposal("p4", "deictic", "d-he", "v-ana-1", refusedBy("target is the speaker")),
    proposal(
      "p5",
      "hint",
      "d-woman",
      "v-ana-2",
      extra = Vector("basis" -> s("CompatibleDescription"), "direction" -> s("Toward"))
    ),
    proposal("p6", "deictic", "d-woman", "v-ana-2")
  )
  private def edition(streams: Vector[(String, String, Long)]): Json = Json.obj(
    "edition" -> s("synthetic-film"),
    "timebase" -> Json.arr(Json.fromLong(1), Json.fromLong(1000)),
    "start" -> Json.fromLong(0),
    "end" -> Json.fromLong(10000),
    "streams" -> Json.fromValues(streams.map { (k, sum, end) =>
      Json.obj(
        "kind" -> s(k),
        "checksum" -> s(Checksum.ofText(sum).hex),
        "end" -> Json.fromLong(end)
      )
    })
  )
  private val twoStreams = Vector(("picture", "pic", 10000L), ("audio", "aud", 9500L))
  private def inputs(
      ms: Vector[Json] = mentions,
      ps: Vector[Json] = proposals,
      pr: Vector[Json] = Vector(presence(1000, 2000), presence(5000, 6000)),
      roles: Vector[String] = Vector("media", "annotation", "asr-words"),
      streams: Vector[(String, String, Long)] = twoStreams
  ): String = Json
    .obj(
      "schemaVersion" -> s(SourceIdentity.InputSchema),
      "inputs" -> Json.fromValues(
        roles.map(r => Json.obj("role" -> s(r), "sha256" -> s(Checksum.ofText(r).hex)))
      ),
      "edition" -> edition(streams),
      "mentions" -> Json.fromValues(ms),
      "presence" -> Json.fromValues(pr),
      "proposals" -> Json.fromValues(ps)
    )
    .spaces2

  private def graph(text: String = inputs()): Json =
    SourceIdentity.graph(text).fold(e => fail(e), identity)
  private def relation(g: Json, d: String, v: String): Json =
    g.hcursor
      .downField("relations")
      .as[Vector[Json]]
      .toOption
      .get
      .find(r =>
        r.hcursor.get[String]("dialogue").contains(d) && r.hcursor.get[String]("visual").contains(v)
      )
      .getOrElse(fail(s"no relation $d / $v"))
  private def rel(g: Json, d: String, v: String): String =
    relation(g, d, v).hcursor.get[String]("relation").toOption.get
  private def outcomeOf(g: Json, field: String, key: String, id: String): Json =
    g.hcursor
      .downField(field)
      .as[Vector[Json]]
      .toOption
      .get
      .find(_.hcursor.get[String](key).contains(id))
      .flatMap(_.hcursor.downField("outcome").focus)
      .getOrElse(fail(s"no $field $id"))
  private def status(o: Json): String = o.hcursor.get[String]("status").toOption.get
  private def reason(o: Json): String = o.hcursor.get[String]("reason").getOrElse("")

  test("control: a spoken name and an annotated name join the depicted person") {
    val g = graph()
    assertEquals(rel(g, "d-ana", "v-ana-1"), "Same")
    assertEquals(
      g.hcursor
        .downField("classes")
        .as[Vector[Vector[String]]]
        .toOption
        .get
        .find(_.contains("v-ana-1")),
      Some(Vector("d-ana", "d-she", "v-ana-1", "v-ana-2"))
    )
    assertEquals(status(outcomeOf(g, "proposals", "id", "p1")), "licensed")
  }

  test("deixis under reviewed presence with one person present is licensed") {
    assertEquals(rel(graph(), "d-she", "v-ana-1"), "Same")
  }

  test("a proposal the producer refused is recorded and never reaches the resolver") {
    val g = graph()
    val o = outcomeOf(g, "proposals", "id", "p4")
    assertEquals(status(o), "refused-by-producer")
    assertEquals(reason(o), "target is the speaker")
    assertEquals(rel(g, "d-he", "v-ana-1"), "Unknown")
  }

  test("a resolver refusal is recorded with its reason") {
    val o = outcomeOf(graph(), "proposals", "id", "p6")
    assertEquals(status(o), "refused")
    assert(reason(o).contains("more than one person is present"), reason(o))
  }

  test("a hint is recorded between classes and decides nothing") {
    val g = graph()
    assertEquals(rel(g, "d-woman", "v-ana-1"), "Unknown")
    assertEquals(
      relation(g, "d-woman", "v-ana-1").hcursor.downField("hints").as[Vector[Json]].toOption.get,
      Vector(Json.obj("basis" -> s("CompatibleDescription"), "direction" -> s("Toward")))
    )
    assertEquals(status(outcomeOf(g, "proposals", "id", "p5")), "recorded-hint")
  }

  test("deixis without presence is refused as uncertified") {
    val g = graph(inputs(pr = Vector(presence(5000, 6000))))
    val o = outcomeOf(g, "proposals", "id", "p3")
    assertEquals(status(o), "refused")
    assert(reason(o).contains("presence is not certified"), reason(o))
    assertEquals(rel(g, "d-she", "v-ana-1"), "Unknown")
  }

  test("a mention the factories refuse is recorded, and links to it are refused") {
    val onAudio = mention("v-bad", "visual", "audio", 1000, 2000, Some("Ana"))
    val g = graph(
      inputs(ms = mentions :+ onAudio, ps = proposals :+ named("p7", "v-bad", "v-ana-1", "Ana"))
    )
    val m = outcomeOf(g, "mentions", "id", "v-bad")
    assertEquals(status(m), "refused")
    assert(reason(m).contains("picture"), reason(m))
    val p = outcomeOf(g, "proposals", "id", "p7")
    assertEquals(status(p), "refused")
    assert(reason(p).contains("UnknownMention"), reason(p))
    // Off the picture it is still possibly a person in the shot, so it withholds that shot's claim.
    assertEquals(rel(g, "d-she", "v-ana-1"), "Unknown")
  }

  test("a refused visual mention withholds the presence it overlaps, so deixis is not licensed") {
    // A second person listed in the first shot whose name the factories refuse.
    val blank = mention("v-blank", "visual", "picture", 1000, 2000, Some(" "))
    val g = graph(inputs(ms = mentions :+ blank))
    assertEquals(status(outcomeOf(g, "mentions", "id", "v-blank")), "refused")
    val withheld = g.hcursor.downField("presence").downN(0).downField("outcome").focus.get
    assertEquals(status(withheld), "refused")
    assert(reason(withheld).contains("v-blank"), reason(withheld))
    val o = outcomeOf(g, "proposals", "id", "p3")
    assertEquals(status(o), "refused")
    assert(reason(o).contains("presence is not certified"), reason(o))
    assertEquals(rel(g, "d-she", "v-ana-1"), "Unknown")
    // The second shot's claim does not overlap and stays admitted.
    val other = g.hcursor.downField("presence").downN(1).downField("outcome").focus.get
    assertEquals(status(other), "admitted")
  }

  test("a refused visual mention with an unreadable interval withholds every presence claim") {
    val unreadable = Json.obj(
      "id" -> s("v-odd"),
      "origin" -> s("visual"),
      "stream" -> s("picture"),
      "intervals" -> Json.arr(Json.arr(Json.fromLong(1000)))
    )
    val g = graph(inputs(ms = mentions :+ unreadable))
    val statuses = (0 to 1).map(i =>
      status(g.hcursor.downField("presence").downN(i).downField("outcome").focus.get)
    )
    assertEquals(statuses, Vector("refused", "refused"))
  }

  test("a refused visual mention with an empty span withholds every presence claim") {
    val empty = mention("v-empty", "visual", "picture", 1000, 1000, Some("Ana"))
    val g = graph(inputs(ms = mentions :+ empty))
    assertEquals(status(outcomeOf(g, "mentions", "id", "v-empty")), "refused")
    assertEquals(rel(g, "d-she", "v-ana-1"), "Unknown")
  }

  test("a refused mention withholds a claim through any of its intervals") {
    val twoSpans = Json.obj(
      "id" -> s("v-two"),
      "origin" -> s("visual"),
      "stream" -> s("picture"),
      "intervals" -> Json.arr(span(7000, 8000), span(1500, 1600)),
      "name" -> s(" ")
    )
    val g = graph(inputs(ms = mentions :+ twoSpans))
    assertEquals(rel(g, "d-she", "v-ana-1"), "Unknown")
  }

  test("control: a refused mention that only touches a claim withholds nothing") {
    // Intervals are half-open, so [2000, 3000) shares no instant with [1000, 2000).
    val touching = mention("v-touch", "visual", "picture", 2000, 3000, Some(" "))
    val g = graph(inputs(ms = mentions :+ touching))
    assertEquals(status(outcomeOf(g, "mentions", "id", "v-touch")), "refused")
    assertEquals(rel(g, "d-she", "v-ana-1"), "Same")
  }

  test("control: a refused dialogue mention withholds no presence") {
    val noContext = mention("d-loose", "dialogue", "audio", 1000, 2000)
    val g = graph(inputs(ms = mentions :+ noContext))
    assertEquals(status(outcomeOf(g, "mentions", "id", "d-loose")), "refused")
    assertEquals(rel(g, "d-she", "v-ana-1"), "Same")
  }

  test("a presence basis other than human review is refused, with its reason") {
    val g =
      graph(inputs(pr = Vector(presence(1000, 2000, "measured-detector"), presence(5000, 6000))))
    val o = g.hcursor.downField("presence").downN(0).downField("outcome").focus.get
    assertEquals(status(o), "refused")
    assertEquals(rel(g, "d-she", "v-ana-1"), "Unknown")
  }

  test("an input role outside the recall-blind list refuses the whole run") {
    val run = SourceIdentity.graph(inputs(roles = Vector("media", "recall")))
    assertEquals(run.left.toOption, Some("input role recall is not admitted"))
  }

  test("control: every admitted role passes") {
    assert(SourceIdentity.graph(inputs(roles = SourceIdentity.InputRoles.toVector.sorted)).isRight)
  }

  test("the edition needs one picture and one audio stream") {
    val run = SourceIdentity.graph(inputs(streams = Vector(("picture", "pic", 10000L))))
    assertEquals(
      run.left.toOption,
      Some("the edition needs exactly one picture and one audio stream")
    )
  }

  test("the graph does not depend on the order of mentions or proposals") {
    def body(g: Json): Json = g.mapObject(_.remove("inputSha256").remove("inputs"))
    assertEquals(
      body(graph(inputs(ms = mentions.reverse, ps = proposals.reverse))),
      body(graph())
    )
  }

  test("build writes the graph through a temporary directory and refuses an existing output") {
    val root = Files.createTempDirectory("source-identity-suite")
    val in = root.resolve("inputs.json")
    val _ = Files.writeString(in, inputs())
    val out = root.resolve("out")
    val first = SourceIdentity.run(Vector("build", in.toString, out.toString))
    assert(first.isRight, first.toString)
    assert(Files.exists(out.resolve("graph.json")))
    assertEquals(
      SourceIdentity.run(Vector("build", in.toString, out.toString)).left.toOption,
      Some("output already exists")
    )
  }
