package storymodel4s.codec

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite

/** Annotation replay and strict JSON admission must work on every portable codec platform. */
class TimedSourceArtifactSuite extends FunSuite:
  private val csv = TimedSourceArtifactCodec.Header.mkString(",") +
    "\n1,Anna arrived.,a,10,20,1,Scene,,Anna\n2,Bob left.,a,30,40,1,Scene,,Bob\n"
  private val parts =
    """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"}]"""

  test("portable annotation replay preserves the checked source digest"):
    val prepared =
      TimedSourceArtifactCodec.prepare(csv.getBytes(UTF_8), parts.getBytes(UTF_8)).toOption.get
    val encoded =
      TimedSourceArtifactCodec.encode(csv.getBytes(UTF_8), parts.getBytes(UTF_8)).toOption.get
    val decoded = TimedSourceArtifactCodec.decode(encoded).toOption.get
    assertEquals(decoded.representation.digest, prepared.representation.digest)
    assertEquals(decoded.document, prepared.document)

  test("duplicate descriptor keys refuse even when both values agree"):
    val duplicate = parts.replace("\"part\":\"a\"", "\"part\":\"a\",\"part\":\"a\"")
    assert(TimedSourceArtifactCodec.prepare(csv.getBytes(UTF_8), duplicate.getBytes(UTF_8)).isLeft)

  test("duplicate artifact keys refuse even when the last value is lawful"):
    val encoded =
      TimedSourceArtifactCodec.encode(csv.getBytes(UTF_8), parts.getBytes(UTF_8)).toOption.get
    val duplicate = "{\"schemaVersion\":\"prepared-timed-source/v1\"," + encoded.drop(1)
    assert(TimedSourceArtifactCodec.decode(duplicate).isLeft)

  test("malformed UTF-8 refuses before annotation parsing"):
    assert(TimedSourceArtifactCodec.prepare(Array(0xc3.toByte), parts.getBytes(UTF_8)).isLeft)
