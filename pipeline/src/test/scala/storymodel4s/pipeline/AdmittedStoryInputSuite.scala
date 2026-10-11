package storymodel4s.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.codec.TextSourceArtifactCodec
import storymodel4s.fixtures.wog.WarOfTheGhostsText

/** Existing admitted Boas text plus parser-only synthetic tokens; no new narrative fixture. */
class AdmittedStoryInputSuite extends FunSuite:
  private def right[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)
  private def bytes(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)
  private val body = "tokenA tokenB.\n"
  private def document(text: String = body): String =
    s"Format: ${AdmittedStoryInput.Format}\nTitle: parser control\nSource: synthetic\nBasis: parser control\nModifications: none\nTradition: not applicable\nAdmitted by: test control\nText-SHA256: ${Checksum.ofText(text).hex}\n${AdmittedStoryInput.Boundary}$text"
  private def inTemp(f: Path => Unit): Unit =
    val root = Files.createTempDirectory("admitted-story-")
    try f(root)
    finally
      val stream = Files.walk(root)
      try
        stream
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(p => { val _ = Files.deleteIfExists(p) })
      finally stream.close()

  test("Boas authority and Scala mirror have identical exact body bytes and body-only segments") {
    val file = Path.of("docs/design/war-of-the-ghosts-boas1901.txt")
    val path = if Files.exists(file) then file else Path.of("..").resolve(file)
    val loaded = right(AdmittedStoryInput.load(Files.readAllBytes(path)))
    assertEquals(loaded.sourcePackage.source.rawText, WarOfTheGhostsText.text)
    assertEquals(
      loaded.record.textChecksum.hex,
      "cb56d78e0c2410ab6372e9220048577cc0a73a20a28fdb603b30c01dc9c18402"
    )
    val plain = right(TextSourcePackage.fromText(WarOfTheGhostsText.text))
    assertEquals(
      loaded.sourcePackage.segments.map(s => (s.id, s.unit)),
      plain.segments.map(s => (s.id, s.unit))
    )
    assertEquals(loaded.sourcePackage.source.id, plain.source.id)
    assertEquals(loaded.record.title, WarOfTheGhostsText.title)
    assert(!loaded.sourcePackage.source.rawText.contains("Admitted by:"))
  }

  test("exact body hash refuses changed content and final newline with valid sibling") {
    assert(AdmittedStoryInput.load(bytes(document())).isRight)
    assertEquals(
      AdmittedStoryInput.load(bytes(document().dropRight(1))),
      Left(AdmittedStoryInput.Refusal.DigestMismatch)
    )
    assertEquals(
      AdmittedStoryInput.load(bytes(document().replace(body, "tokenC.\n"))),
      Left(AdmittedStoryInput.Refusal.DigestMismatch)
    )
  }

  test("header boundary, required fields, duplicate fields and UTF8 refuse separately") {
    val original = document()
    assert(AdmittedStoryInput.load(bytes(original)).isRight)
    assertEquals(
      AdmittedStoryInput.load(bytes(original.replace(AdmittedStoryInput.Boundary, ""))),
      Left(AdmittedStoryInput.Refusal.MissingBoundary)
    )
    assertEquals(
      AdmittedStoryInput.load(bytes(original.replace("Source: synthetic\n", ""))),
      Left(AdmittedStoryInput.Refusal.InvalidHeader)
    )
    assertEquals(
      AdmittedStoryInput.load(
        bytes(original.replace("Source: synthetic\n", "Source: synthetic\nSource: duplicate\n"))
      ),
      Left(AdmittedStoryInput.Refusal.InvalidHeader)
    )
    assertEquals(
      AdmittedStoryInput.load(bytes(original.replace("Source: synthetic", "Unknown: synthetic"))),
      Left(AdmittedStoryInput.Refusal.InvalidHeader)
    )
    assertEquals(
      AdmittedStoryInput.load(bytes(original) ++ Array(0xc3.toByte, 0x28.toByte)),
      Left(AdmittedStoryInput.Refusal.InvalidUtf8)
    )
    assertEquals(
      AdmittedStoryInput.load(bytes(document(""))),
      Left(AdmittedStoryInput.Refusal.InvalidText)
    )
  }

  test("plain mode refuses versioned and historical admission headers with ordinary control") {
    assert(AdmittedStoryInput.plain(bytes(body)).isRight)
    assertEquals(
      AdmittedStoryInput.plain(bytes(document())),
      Left(AdmittedStoryInput.Refusal.RequiresAdmittedMode)
    )
    assertEquals(
      AdmittedStoryInput.plain(bytes("title\n\nSource: synthetic\nAdmitted by: test\n\n" + body)),
      Left(AdmittedStoryInput.Refusal.RequiresAdmittedMode)
    )
    assertEquals(
      AdmittedStoryInput.plain(
        bytes("title\n\nSource: " + ("x" * 17000) + "\nAdmitted by: test\n\n" + body)
      ),
      Left(AdmittedStoryInput.Refusal.RequiresAdmittedMode)
    )
  }

  test("checked artifact retains separate admission metadata and body identity") {
    val loaded = right(AdmittedStoryInput.load(bytes(document())))
    val decoded = right(
      TextSourceArtifactCodec.decode(bytes(TextSourceArtifactCodec.encode(loaded.sourcePackage)))
    )
    assertEquals(
      TextSourceArtifactCodec.encode(decoded),
      TextSourceArtifactCodec.encode(loaded.sourcePackage)
    )
    assertEquals(decoded.source.metadata("admitted-story.Basis"), "parser control")
    assertEquals(
      decoded.source.metadata("admitted-story.document-sha256"),
      Checksum.ofText(document()).hex
    )
    assertEquals(decoded.source.rawChecksum, loaded.record.textChecksum)
    assertEquals(right(AdmittedStoryInput.load(bytes(document()))), loaded)
    assertEquals(right(AdmittedStoryInput.load(bytes(document()))).hashCode, loaded.hashCode)
  }

  test("public CLI explicitly admits a file and refuses it in plain mode") {
    inTemp { root =>
      val input = root.resolve("input.txt")
      val _ = Files.writeString(input, document())
      val out = Vector.newBuilder[String]
      val err = Vector.newBuilder[String]
      assertEquals(
        TextSourceCli.run(
          List(input.toString, root.resolve("bad").toString),
          s => { val _ = out += s },
          s => { val _ = err += s }
        ),
        2
      )
      assert(!Files.exists(root.resolve("bad")))
      assertEquals(
        TextSourceCli.run(
          List(input.toString, root.resolve("good").toString, "--admitted"),
          s => { val _ = out += s },
          s => { val _ = err += s }
        ),
        0
      )
      val decoded =
        right(TextSourceArtifactCodec.decode(Files.readAllBytes(root.resolve("good/source.json"))))
      assertEquals(decoded.source.rawText, body)
      assertEquals(out.result().size, 1)
      assertEquals(err.result().size, 1)
    }
  }

  test("study admitted-text input and checked cache preserve edition and segments") {
    import StudyPreparation.*
    inTemp { root =>
      val _ = Files.writeString(root.resolve("input.txt"), document())
      val json =
        s"""{"schemaVersion":"$Schema","source":{"format":"admitted-text","path":"input.txt","kind":"Sentence"},"recalls":[],"profile":"$RecallProfile"}"""
      val c = right(config(json))
      val admitted = right(prepare(root, c))
      assertEquals(
        prepare(root, c.copy(source = SourceInput.Text("input.txt", SurfaceUnitKind.Sentence))),
        Left(Refusal.InvalidSource)
      )
      val _ = Files.writeString(root.resolve("source.json"), admitted.source.artifact)
      val cached = right(
        prepare(
          root,
          c.copy(source = SourceInput.CheckedText("source.json", SurfaceUnitKind.Sentence))
        )
      )
      assertEquals(cached.source.edition, admitted.source.edition)
      assertEquals(cached.source.artifact, admitted.source.artifact)
    }
  }
