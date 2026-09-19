package storymodel4s.pipeline

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scala.util.Using
import storymodel4s.codec.{Canonical, CoreCodecs, DerivationRecordCodec, StoryModelCodec}
import storymodel4s.core.*
import storymodel4s.document.{ChartProposalProvider, NarrativeCompiler}
import storymodel4s.fixtures.wog.WarOfTheGhostsText
import storymodel4s.provider.agent.*
import storymodel4s.view.*

/** S0 freezes the existing JVM text path before D1A changes its support vocabulary. These are
  * compatibility witnesses, not assertions that the machine interpretation is correct. The complete
  * captured replay is deliberately partial: it must stay partial.
  */
class TextParitySuite extends FunSuite:
  import CoreCodecs.given

  private val now = 1700000000000L
  private lazy val work = Files.createTempDirectory("storymodel4s-text-parity-")
  private def resource(name: String): Path = Paths.get(getClass.getResource(name).toURI)
  private def read(path: Path): String = Files.readString(path, UTF_8)
  private def json(text: String): Json = parse(text).fold(error => fail(error.message), identity)
  private def digest(path: Path): String = Checksum.ofBytes(Files.readAllBytes(path)).hex

  override def afterAll(): Unit =
    val paths = Using.resource(Files.walk(work))(_.iterator().asScala.toVector)
    paths.sorted(using Ordering[Path].reverse).foreach { path =>
      val _ = Files.deleteIfExists(path)
    }

  private lazy val text =
    val path = work.resolve("war-of-the-ghosts.txt")
    Files.writeString(path, WarOfTheGhostsText.text, UTF_8)
    path

  private lazy val parsed = ClaudeParseDriver
    .parse(DriverMode.Replay, text, resource("/recordings/wog-captured"), Map.empty, now)
    .fold(error => fail(error.message), identity)
  private lazy val input = ChartProposalProvider
    .input(parsed.story, parsed.atlas, parsed.charts, Some(parsed.parserStage._1), now)
    .fold(error => fail(error.message), identity)
  private lazy val compilation =
    NarrativeCompiler.compile(input).fold(error => fail(error.message), identity)

  // Exercise the production writer as well as the pure compiler. No title or features requested.
  private lazy val built = StoryPipeline
    .run(
      DriverMode.Replay,
      text,
      resource("/recordings/wog-captured"),
      work.resolve("bundle"),
      Map.empty,
      now
    )
    .fold(error => fail(error.message), identity)
  private lazy val model =
    StoryModelCodec.decode(read(built.files.model)).fold(error => fail(error.message), identity)
  private lazy val derivation = DerivationRecordCodec
    .decode(read(built.files.derivation))
    .fold(error => fail(error.message), identity)

  /** These two canonical renderers are private. A JVM-only test adapter reads their actual output
    * rather than copying their algorithm or adding a public production API just for the freeze. A
    * renamed/removed renderer requires an explicit test-adapter update, never new golden bytes.
    */
  private def compilerRender(name: String, arguments: AnyRef*): AnyRef =
    val method = NarrativeCompiler.getClass.getDeclaredMethods
      .find(_.getName == name)
      .getOrElse(fail(s"missing compiler parity seam: $name"))
    method.setAccessible(true)
    method.invoke(NarrativeCompiler, arguments*)

  private lazy val exemplar =
    val attempt = input.situations.head
    val evidence = input.evidence.values.toVector.sortBy(_.id.value).head
    val entity = model.graph.entities.values.toVector.sortBy(_.id.value).head
    val renderUnused: Any => String = _ => "unused-proposal"
    val bundleFields = compilerRender("renderBundle", attempt.bundle, renderUnused)
      .asInstanceOf[Vector[String]]
    Json.obj(
      "sourceSupportTarget" -> attempt.source.key.asJson,
      "sourceSupport" -> bundleFields.find(_.startsWith("17:source-support/v2")).get.asJson,
      "evidence" -> compilerRender("renderEvidence", evidence).asInstanceOf[String].asJson,
      "evidenceWire" -> evidence.asJson,
      "entity" -> entity.id.value.asJson,
      "mentionId" -> entity.mentions.head.value.asJson,
      "textSupportWire" -> entity.support.asJson
    )

  private lazy val orders =
    val graph = model.graph
    def bySupport(nodes: Vector[(String, SpanSet)]): Json =
      nodes
        .sortBy { case (id, support) =>
          val span = support.minSpan
          (span.start, span.endExclusive, id)
        }
        .map(_._1)
        .asJson
    Json.obj(
      "discourseOrder" -> graph.discourseOrder.map(_.value).asJson,
      "entitiesBySupport" -> bySupport(
        graph.entities.values.toVector.map(n => n.id.value -> n.support)
      ),
      "situationsBySupport" -> bySupport(
        graph.situations.values.toVector.map(n => n.id.value -> n.support)
      ),
      "segmentsBySupport" -> bySupport(
        graph.segments.values.toVector.map(n => n.id.value -> n.support)
      ),
      "contextsBySupport" -> bySupport(
        graph.contexts.values.toVector.map(n => n.id.value -> n.support)
      )
    )

  private lazy val view =
    val draft = DraftModel.of(model, compilation.validation, derivation.record)
    val state = CommonViewState.empty
    val spec =
      AtlasSpec(ZoomLevel(NarrativeLevel.Scene, SurfaceDetail.Hidden), ThreadPolicy.Selected)
    val provenance = ViewProvenance
      .draftBuild(draft, "d1a-s0-text-parity", AtlasCompiler.configurationChecksum(state, spec))
      .fold(error => fail(error.message), identity)
    AtlasCompiler(provenance)
      .compileDraft(draft, state, spec)
      .fold(error => fail(error.message), identity)

  private lazy val historical =
    val dir = resource("/runs/2026-09-02-wog-record-1")
    val receipts = json(read(dir.resolve("receipts.json"))).hcursor
    val parserReceipt = receipts
      .downField("parser")
      .downField("receipt")
      .as[BuildReceipt]
      .fold(error => fail(error.message), identity)
    val compilerReceipt = receipts
      .downField("buildReceipt")
      .as[BuildReceipt]
      .fold(error => fail(error.message), identity)
    Json.obj(
      "files" -> Using
        .resource(Files.list(dir))(_.iterator().asScala.map(_.getFileName.toString).toVector.sorted)
        .asJson,
      "receiptsFileSha256" -> digest(dir.resolve("receipts.json")).asJson,
      "reportFileSha256" -> digest(dir.resolve("compilation-report.json")).asJson,
      "parserReceiptChecksum" -> parserReceipt.contentChecksum.hex.asJson,
      "compilerReceiptChecksum" -> compilerReceipt.contentChecksum.hex.asJson
    )

  private def actual(section: String): Json = section match
    case "compile" =>
      Json.obj(
        "fingerprint" -> compilation.fingerprint.hex.asJson,
        "candidateSet" -> compilation.derivation.candidateSet.hex.asJson,
        "modelContentChecksum" -> StoryModelCodec.contentChecksum(model).hex.asJson,
        "modelFileSha256" -> digest(built.files.model).asJson,
        "derivationFileSha256" -> digest(built.files.derivation).asJson
      )
    case "exemplars"  => exemplar
    case "nodeOrders" => orders
    case "view"       =>
      Json.obj(
        "artifact" -> "NarrativeScene.textualTwin, UTF-8, no extra newline".asJson,
        "sha256" -> Checksum.ofText(view.textualTwin).hex.asJson
      )
    case "historicalReceipts" => historical
    case other                => fail(s"unknown parity section: $other")

  private lazy val expected = json(read(resource("/golden/d1a-s0-text-parity.json")))

  for section <- Vector("compile", "exemplars", "nodeOrders", "view", "historicalReceipts") do
    test(s"S0 text parity: $section") {
      assertEquals(
        actual(section),
        expected.hcursor.downField(section).focus.getOrElse(fail(section))
      )
    }

  test("historical receipts remain a receipt-only record") {
    assertEquals(
      historical.hcursor.get[Vector[String]]("files"),
      Right(Vector("compilation-report.json", "receipts.json"))
    )
  }

  test("the full replay remains partial and the writer binds its own model") {
    assertEquals(parsed.atlas.sentences.size, 50)
    assertEquals(model.graph.situations.size, 65)
    assertEquals(model.graph.entities.size, 23)
    assertEquals(model.graph.contexts.size, 6)
    assertEquals(model.graph.segments.size, 1)
    assertEquals(compilation.validated, None)
    assertEquals(compilation.derivation.gaps.size, 84)
    assertEquals(compilation.validation.report.violations.size, 3)
    assertEquals(built.fingerprint, compilation.fingerprint)
    assertEquals(built.candidateSet, compilation.derivation.candidateSet)
    assertEquals(built.encodingDigest, StoryModelCodec.contentChecksum(model))
    assert(derivation.describes(model))
    assertEquals(derivation.compilationFingerprint, compilation.fingerprint)
    assertEquals(derivation.candidateSet, compilation.derivation.candidateSet)
  }

  test("text wire omits anchors and preserves canonical model bytes") {
    val encoded = read(built.files.model)
    assert(!encoded.contains("\"anchors\""))
    assert(!encoded.contains(":null"))
    assertEquals(Canonical.parse(encoded).map(Canonical.print), Right(encoded))
    assertEquals(Checksum.ofText(encoded), StoryModelCodec.contentChecksum(model))
  }
