package storymodel4s.pipeline

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import io.circe.Json
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.align.*
import storymodel4s.recall.*
import storymodel4s.codec.{
  BoundCaptionSourceCodec,
  TimedSourceArtifactCodec,
  SurfaceWorkspaceCodecs
}
import storymodel4s.media.*

/** Synthetic replay courts exercise multipart membership independently of extraction execution. */
class StrictCaptionJoinSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A =
    value.fold(e => fail(e.toString), identity)
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

  private val csv = "ordinal,text,part,onset,end,group,group_label,locations,characters\n" +
    "1,Anna arrived.,a,50,57,1,Across parts,,\n" +
    "2,Anna arrived.,b,203,217,1,Across parts,,\n"
  private val descriptor =
    """[{"part":"a","start":"50","end":"98","numerator":"1","denominator":"1000"},{"part":"b","start":"201","end":"249","numerator":"1","denominator":"2000"}]"""
  private def annotations(text: String = csv): String =
    right(TimedSourceArtifactCodec.encode(text.getBytes(UTF_8), descriptor.getBytes(UTF_8)))
  private def correspondence(
      pack: CaptionPackage,
      artifact: String,
      basis: String = "synthetic known affine clocks"
  ): Json =
    val a = right(TimedSourceArtifactCodec.decode(artifact))
    val m = right(VideoSourceInput.project(pack))
    def pair(x: Long, y: Long) = Json.obj(
      "annotationTick" -> Json.fromString(x.toString),
      "filmTick" -> Json.fromString(y.toString)
    )
    Json.obj(
      "schemaVersion" -> Json.fromString(BoundCaptionSourceCodec.CorrespondenceSchema),
      "annotationArtifact" -> Json.fromString(Checksum.ofText(artifact).hex),
      "packageManifest" -> Json.fromString(pack.manifestChecksum.hex),
      "packageIdentity" -> Json.fromString(pack.identity.hex),
      "edition" -> Json.fromString(pack.edition.value),
      "basis" -> Json.fromString(basis),
      "parts" -> Json.arr(
        Vector(("a", 50L, 98L, 0L, 48000L), ("b", 201L, 249L, 1000L, 49000L)).map {
          (name, start, end, first, last) =>
            Json.obj(
              "annotationPart" -> Json.fromString(name),
              "filmOccurrence" -> Json.fromString(name),
              "annotationAxis" -> Json.fromString(a.parts(name).primaryAxis.id.value),
              "filmAxis" -> Json.fromString(m.view.parts(name).primaryAxis.id.value),
              "landmarks" -> Json.arr(pair(start, first), pair(end, last))
            )
        }*
      )
    )
  private def bind(pack: CaptionPackage, artifact: String, c: Json) =
    BoundCaptionSourceCodec.bind(
      artifact,
      right(VideoSourceInput.project(pack)).artifact,
      c.noSpaces,
      Checksum.ofText(c.noSpaces)
    )
  private def first(c: Json)(f: Json => Json): Json =
    val rows = c.hcursor.get[Vector[Json]]("parts").toOption.get
    field(c, "parts", Json.fromValues(f(rows.head) +: rows.tail))
  private lazy val recall = RecallSegmenter.segment(right(StorySource.fromText("Anna arrived.")))
  private lazy val inventory = right(
    RecallInventory.of(
      recall,
      SurfaceSequence(recall.atlas).lexicalTokens.map(_.span),
      WordIdPolicy.inputArtifact(Checksum.ofText("strict-join-control"))
    )
  )
  private lazy val candidates = right(
    StrictCandidateConfig.of(5, TieBudgetRequest.AtMost(5), false, None)
  )
  private val mapping = MappingPipeline.Config(
    MappingPipeline.CaptionProfile,
    1.0,
    5,
    5,
    Vector(MappingPipeline.Capability.Mapping)
  )

  test("two native clocks preserve annotation identity and crossing-group missingness"):
    withFixture { f =>
      val pack = right(f.read)
      val a = annotations()
      val joined = right(bind(pack, a, correspondence(pack, a)))
      val original = right(TimedSourceArtifactCodec.decode(a))
      assertEquals(joined.view.nodes, original.nodes)
      assertEquals(joined.view.document, "Anna arrived.\nAnna arrived.")
      assertEquals(
        joined.view.segments.map(s => (s.locus.get.startTick, s.locus.get.endTick)),
        Vector((0L, 7000L), (3000L, 17000L))
      )
      assertEquals(
        joined.view.parts("a").primaryAxis.timebase,
        Some(right(RationalTimebase.of(1, 24000)))
      )
      assertEquals(
        joined.view.parts("b").primaryAxis.timebase,
        Some(right(RationalTimebase.of(1, 48000)))
      )
      assert(
        joined.view.representation.targets
          .filter(_.level == 1)
          .forall(_.sourceSupport.isInstanceOf[SourceSupportStatus.Unlocated])
      )
      assertEquals(right(BoundCaptionSourceCodec.decode(joined.artifact)), joined)
      val registration =
        right(CaptionMapping.fromPackage(pack, joined, recall, inventory, candidates, 0.8))
      val run = right(MappingPipeline.captions(registration, mapping))
      assertEquals(run.captionPackage, Some(registration))
      assertEquals(run.execution.reference.evidence, run.execution.reconstruction.evidence)
    }

  test("changed correspondence basis preserves numeric evidence but changes executed provenance"):
    withFixture { f =>
      val pack = right(f.read)
      val a = annotations()
      def run(basis: String) =
        val joined = right(bind(pack, a, correspondence(pack, a, basis)))
        right(
          MappingPipeline.captions(
            right(CaptionMapping.fromPackage(pack, joined, recall, inventory, candidates, 0.8)),
            mapping
          )
        )
      val first = run("synthetic source evidence one")
      val second = run("synthetic independently recorded evidence two")
      assertEquals(first.execution.reference.evidence, second.execution.reference.evidence)
      assertNotEquals(
        first.captionPackage.get.produced.session.identity,
        second.captionPackage.get.produced.session.identity
      )
      assertNotEquals(first.registration, second.registration)
      assertNotEquals(first.identity, second.identity)
    }

  test("wrong pins or axes cannot turn matching numbers into correspondence"):
    withFixture { f =>
      val pack = right(f.read); val a = annotations(); val c = correspondence(pack, a)
      assert(
        BoundCaptionSourceCodec
          .bind(
            a,
            right(VideoSourceInput.project(pack)).artifact,
            c.noSpaces,
            Checksum.ofText("wrong")
          )
          .isLeft
      )
      Vector("annotationArtifact", "packageManifest", "packageIdentity", "edition").foreach { key =>
        assert(bind(pack, a, field(c, key, Json.fromString("wrong"))).isLeft, key)
      }
      assert(
        bind(
          pack,
          a,
          first(c)(p => field(p, "filmAxis", p.hcursor.downField("annotationAxis").focus.get))
        ).isLeft
      )
      assert(bind(pack, a, first(c)(p => field(p, "filmOccurrence", Json.fromString("b")))).isLeft)
    }

  test("shifted exact endpoints and duplicate annotation targets refuse"):
    withFixture { f =>
      val pack = right(f.read)
      val shifted = annotations(csv.replace("a,50,57", "a,50,56"))
      assert(
        bind(pack, shifted, correspondence(pack, shifted)).left.toOption
          .exists(_.contains("unique exact"))
      )
      val duplicate = annotations(csv + "3,Anna arrived.,a,50,57,,,,\n")
      assert(
        bind(pack, duplicate, correspondence(pack, duplicate)).left.toOption
          .exists(_.contains("unique exact"))
      )
    }

  test("fractional projection and out-of-domain annotations refuse without rounding"):
    withFixture { f =>
      val pack = right(f.read); val a = annotations(); val c = correspondence(pack, a)
      val fractional = first(c) { p =>
        val points = p.hcursor.get[Vector[Json]]("landmarks").toOption.get
        field(
          p,
          "landmarks",
          Json.arr(points.head, field(points.last, "filmTick", Json.fromString("47999")))
        )
      }
      assert(bind(pack, a, fractional).left.toOption.exists(_.contains("exact representable")))
      val narrow = first(c) { p =>
        val points = p.hcursor.get[Vector[Json]]("landmarks").toOption.get
        val start = field(
          field(points.head, "annotationTick", Json.fromString("51")),
          "filmTick",
          Json.fromString("1000")
        )
        field(p, "landmarks", Json.arr(start, points.last))
      }
      assert(
        bind(pack, a, narrow).left.toOption.exists(_.contains("outside correspondence domain"))
      )
    }

  test("annotation gaps survive without borrowing another part's caption"):
    withFixture { f =>
      val pack = right(f.read)
      val a = annotations(csv + "3,Anna arrived.,a,60,61,,,,\n")
      val joined = right(bind(pack, a, correspondence(pack, a)))
      val registration =
        right(CaptionMapping.fromPackage(pack, joined, recall, inventory, candidates, 1.0))
      val gap = joined.view.nodes.find(_.ref.key.endsWith(":3")).get
      assert(!registration.produced.evidence.nominated.flatten.contains(gap.ref))
      assertEquals(joined.view.nodes.count(_.level == 0), 3)
    }

  private def changeSecondCaption(f: Fixture): Unit =
    f.change("b/outcome.json") { outcome =>
      val extents = outcome.hcursor.get[Vector[Json]]("extents").toOption.get
      field(
        outcome,
        "extents",
        Json.fromValues(extents.map(e => field(e, "text", Json.fromString("Anna arrived."))))
      )
    }
    val pin = Checksum.ofBytes(Files.readAllBytes(f.root.resolve("b/outcome.json")))
    f.change("b/caption-envelope.json")(j => field(j, "outcomeSha256", Json.fromString(pin.hex)))

  test("same-text annotation occurrences cannot borrow a changed caption from another part"):
    withFixture { f =>
      def costs(pack: CaptionPackage) =
        val a = annotations()
        val joined = right(bind(pack, a, correspondence(pack, a)))
        val run =
          right(CaptionMapping.fromPackage(pack, joined, recall, inventory, candidates, 1.0))
        val refs = joined.view.nodes.filter(_.level == 0).map(_.ref)
        refs.map(ref => run.produced.evidence.breakdowns.head(AlignState.Source(ref)).total)
      val before = costs(right(f.read))
      changeSecondCaption(f)
      val after = costs(right(f.read))
      assertEquals(before, Vector(1.0, 1.0))
      assertEquals(after, Vector(1.0, 0.0))
    }

  test("a genuine different package cannot authenticate older portable caption declarations"):
    withFixture { f =>
      val original = right(f.read); val a = annotations()
      val joined = right(bind(original, a, correspondence(original, a)))
      changeSecondCaption(f)
      assertEquals(
        CaptionMapping.fromPackage(right(f.read), joined, recall, inventory, candidates, 0.8),
        Left(CaptionMapping.Refusal.ArtifactMismatch)
      )
    }

  test("public join study mapping and workspace reverify the package"):
    withFixture { f =>
      val pack = right(f.read); val a = annotations(); val c = correspondence(pack, a)
      val _ = Files.write(f.root.resolve("manifest.json"), f.bytes)
      val _ = Files.writeString(f.root.resolve("annotations.json"), a)
      val _ = Files.writeString(f.root.resolve("correspondence.json"), c.noSpaces)
      val template = right(
        VideoSourceCli.run(
          Vector(
            "join-template",
            f.root.resolve("annotations.json").toString,
            f.root.toString,
            pack.manifestChecksum.hex,
            edition.value
          )
        )
      )
      val incomplete = template.hcursor.downField("correspondenceTemplate").focus.get
      assert(bind(pack, a, incomplete).isLeft)
      right(
        VideoSourceCli.run(
          Vector(
            "join",
            f.root.resolve("annotations.json").toString,
            f.root.toString,
            pack.manifestChecksum.hex,
            edition.value,
            f.root.resolve("correspondence.json").toString,
            Checksum.ofText(c.noSpaces).hex,
            f.root.resolve("joined").toString
          )
        )
      )
      val _ = Files.writeString(f.root.resolve("recall.txt"), "Anna arrived.")
      val config = StudyPreparation.Config(
        StudyPreparation.SourceInput
          .CheckedAV("joined/source.json", ".", pack.manifestChecksum.hex, edition.value, 0.8),
        Vector(
          StudyPreparation.RecallInput(
            "project-synthetic-control",
            "s1",
            "recall.txt",
            admission = StudyPreparation.Admission.ProjectSyntheticControl
          )
        ),
        StudyPreparation.RecallProfile
      )
      val publicConfig = Json.obj(
        "schemaVersion" -> Json.fromString(StudyPreparation.Schema),
        "profile" -> Json.fromString(StudyPreparation.RecallProfile),
        "source" -> Json.obj(
          "format" -> Json.fromString("checked-av"),
          "path" -> Json.fromString("joined/source.json"),
          "package" -> Json.fromString("."),
          "manifestSha256" -> Json.fromString(pack.manifestChecksum.hex),
          "edition" -> Json.fromString(edition.value),
          "blend" -> Json.fromString("0.8")
        ),
        "recalls" -> Json.arr(
          Json.obj(
            "participant" -> Json.fromString("project-synthetic-control"),
            "session" -> Json.fromString("s1"),
            "path" -> Json.fromString("recall.txt"),
            "timing" -> Json.Null,
            "admission" -> Json.obj("kind" -> Json.fromString("project-synthetic-control"))
          )
        )
      )
      assertEquals(right(StudyPreparation.config(publicConfig.noSpaces)), config)
      val _ = Files.writeString(f.root.resolve("study.json"), publicConfig.noSpaces)
      val _ = Files.writeString(
        f.root.resolve("mapping.json"),
        MappingCli.Template.replace(MappingPipeline.LexicalProfile, MappingPipeline.CaptionProfile)
      )
      assertEquals(
        MappingCli.run(
          List(
            "exchange",
            f.root.resolve("study.json").toString,
            f.root.resolve("mapping.json").toString,
            f.root.resolve("exchange").toString
          ),
          _ => (),
          _ => ()
        ),
        0
      )
      val prepared = right(
        StudyPreparation.prepare(f.root, right(StudyPreparation.config(publicConfig.noSpaces)))
      )
      val batch = right(MappingPipeline.batch(prepared, mapping))
      assertEquals(batch.outcomes.count(_.run.nonEmpty), 1)
      val packets = right(StudyWorkspaceProducer.artifacts(batch, "a" * 40))._1
      sys.props.get("storymodel4s.strictAV.fixture").foreach { name =>
        val _ = Files.writeString(Path.of(name), packets.head._2)
      }
      val decoded = right(SurfaceWorkspaceCodecs.decode(packets.head._2))
      assertEquals(decoded.sourceKind, SurfaceWorkspaceCodecs.SourceKind.AnnotationVideo)
      assertEquals(decoded.sourceDocument, "Anna arrived.\nAnna arrived.")
      assertEquals(decoded.sourceArtifact, prepared.source.artifact)
      val _ = Files.write(f.root.resolve("a/frames.bgr"), Array[Byte](1))
      assert(StudyPreparation.prepare(f.root, config).isLeft)
    }
