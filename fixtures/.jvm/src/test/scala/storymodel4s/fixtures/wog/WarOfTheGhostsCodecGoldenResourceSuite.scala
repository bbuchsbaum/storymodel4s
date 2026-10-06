package storymodel4s.fixtures.wog

import io.circe.Json
import java.nio.charset.StandardCharsets
import munit.FunSuite
import storymodel4s.codec.{Canonical, CodecError, HsmmCodecError, HsmmResultCodec}
import storymodel4s.core.Checksum

/** Checks the committed golden resources on the JVM, where class-path resources are available.
  *
  * The JVM's own live artifact is compared byte for byte with its resource. For the other backends,
  * whose live bytes are pinned in [[WogGoldenBackend]] and checked on those backends, this court
  * proves each pin is the checksum of the committed file, and that each committed `hsmm/v4` golden
  * moved no number relative to the `hsmm/v3` golden of the same backend.
  */
class WarOfTheGhostsCodecGoldenResourceSuite extends FunSuite:
  import WarOfTheGhostsCodecGolden.*

  private def resource(path: String): Array[Byte] =
    val stream = Option(getClass.getResourceAsStream(path)).getOrElse {
      fail(s"test resource not available: $path")
    }
    try stream.readAllBytes()
    finally stream.close()

  private def text(path: String): String = new String(resource(path), StandardCharsets.UTF_8)

  test("the committed hsmm/v4 resource is the inferred artifact, with its terminal newline") {
    val backend = WogGoldenBackend.Portable
    assertEquals(WogGoldenPlatform.current, backend, "current numerical producer is not the qualified portable producer")
    val committed = resource(backend.goldenResource)
    assertEquals(new String(committed, StandardCharsets.UTF_8), encoded + "\n")
    assertEquals(Checksum.ofBytes(committed).hex, backend.goldenFile)
    assertEquals(Checksum.ofText(encoded).hex, backend.encodedV4)
  }

  test("every backend's pin is the checksum of its committed hsmm/v4 golden") {
    WogGoldenBackend.values.foreach { backend =>
      val committed = resource(backend.goldenResource)
      val body = new String(committed, StandardCharsets.UTF_8)
      assert(body.endsWith("}\n") && !body.endsWith("\n\n"), s"$backend: one terminal newline")
      assertEquals(Checksum.ofBytes(committed).hex, backend.goldenFile, backend.toString)
      assertEquals(Checksum.ofText(body.stripSuffix("\n")).hex, backend.encodedV4, backend.toString)
      assertEquals(Canonical.parse(body).map(Canonical.print), Right(body.stripSuffix("\n")))
    }
    // The labels are distinct where the bytes are: JVM and JS share a golden, Native has its own.
    assertEquals(WogGoldenBackend.JVM.goldenResource, WogGoldenBackend.JS.goldenResource)
    assertNotEquals(WogGoldenBackend.JVM.encodedV4, WogGoldenBackend.Native.encodedV4)
  }

  test("each hsmm/v4 golden moved no number relative to the hsmm/v3 golden of its backend") {
    WogGoldenBackend.values.foreach { backend =>
      val v3 = text(backend.v3Resource)
      val v4 = text(backend.goldenResource).stripSuffix("\n")
      assertEquals(Checksum.ofText(v3).hex, backend.v3File, s"$backend v3 golden changed")
      assertEquals(withoutSupport(v4), withoutSupport(v3), s"$backend: a non-support field moved")
      assertEquals(Checksum.ofText(withoutSupport(v3)).hex, backend.withoutSupport)

      // Support, cell by cell: every source share is bit-identical to its v3 supportWeight, and
      // every external cell that published 1.0 with no terms now publishes no number.
      val v3Cells = cells(v3, "supportWeight")
      val v4Cells = cells(v4, "support")
      assertEquals(v4Cells.map(_._1), v3Cells.map(_._1), s"$backend cell keys")
      v3Cells.zip(v4Cells).foreach { case ((key, weight), (_, support)) =>
        val c = support.hcursor
        c.get[String]("type") match
          case Right("Assessed") =>
            assertEquals(c.get[Json]("share"), Right(weight), s"$backend $key share moved")
          case Right("NotApplicable") =>
            assertEquals(c.get[String]("reason"), Right("ExternalState"), s"$backend $key")
            assertEquals(weight, Json.fromString("0x3ff0000000000000"), s"$backend $key")
            assert(key.contains("\"External\""), s"$backend $key: a source cell is NotApplicable")
          case other => fail(s"$backend $key: unexpected support $other")
      }
      assertEquals(v4Cells.count(_._2.hcursor.get[String]("type") == Right("NotApplicable")), 10)
      assertEquals(v4Cells.count(_._2.hcursor.get[String]("type") == Right("Assessed")), 8)
    }
  }

  test("the hsmm/v3 goldens are byte-identical refusal fixtures") {
    WogGoldenBackend.values.foreach { backend =>
      val v3 = text(backend.v3Resource)
      assertEquals(Checksum.ofText(v3).hex, backend.v3File)
      assertEquals(
        HsmmResultCodec.decode(v3, context.recall, context.view),
        Left(HsmmCodecError.Wire(CodecError.UnsupportedSchema("hsmm/v3", Vector("hsmm/v4"))))
      )
    }
  }

  /** `(unit + state, the cost's support field)` for every cell, in artifact order. */
  private def cells(text: String, field: String): Vector[(String, Json)] =
    val json = Canonical.parse(text).fold(e => fail(e.message), identity)
    def array(j: Option[Json]): Vector[Json] = j.flatMap(_.asArray).getOrElse(Vector.empty)
    array(json.hcursor.downField("costs").focus).flatMap { unit =>
      val u = unit.hcursor.get[String]("unit").fold(e => fail(e.message), identity)
      array(unit.hcursor.downField("costs").focus).map { stateCost =>
        val state = stateCost.hcursor.downField("state").focus.map(Canonical.print).getOrElse("")
        val support = stateCost.hcursor
          .downField("cost")
          .downField(field)
          .focus
          .getOrElse(fail(s"cell $u $state has no $field"))
        (s"$u $state", support)
      }
    }
