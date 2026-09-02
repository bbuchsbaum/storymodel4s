package storymodel4s.core

import cats.data.NonEmptyVector
import munit.FunSuite

/** Courts for the quotation scan: what it recognises, what it refuses, and the three-way placement
  * decision that keeps an undecidable span out of the narrated world.
  */
class QuotationSuite extends FunSuite:

  private def scan(text: String): QuotationScan =
    QuotationScan.of(text).fold(d => fail(s"refused: ${d.render}"), identity)

  private def sliced(text: String): Vector[String] =
    scan(text).spans.map(s => text.substring(s.start, s.endExclusive))

  test("a straight-quoted stretch is one span, marks included"):
    val text = """He said: "Behold, I accompanied the ghosts," and he told everything."""
    assertEquals(sliced(text), Vector("\"Behold, I accompanied the ghosts,\""))

  test("several quotations of one text are found in discourse order"):
    val text = """A: "one." B: "two." C: "three.""""
    assertEquals(sliced(text), Vector("\"one.\"", "\"two.\"", "\"three.\""))

  test("a quotation spanning several sentences is one span"):
    val text = """"We fought. Many were killed. I did not feel sick." He became quiet."""
    assertEquals(sliced(text), Vector("\"We fought. Many were killed. I did not feel sick.\""))

  test("curly marks open and close, and nest inside a straight quotation"):
    val text = "He said: \"She said “hello” to me.\" Then he left."
    val s = scan(text)
    assertEquals(s.size, 2)
    assertEquals(
      text.substring(s.spans(0).start, s.spans(0).endExclusive),
      "\"She said “hello” to me.\""
    )
    assertEquals(text.substring(s.spans(1).start, s.spans(1).endExclusive), "“hello”")

  test("an apostrophe is not a quotation mark, and neither is a single quotation"):
    val text = "He said he didn't go, and 'that' was that."
    assertEquals(scan(text).spans, Vector.empty[TextSpan])
    assert(scan(text).isEmpty)

  test("an unclosed opening refuses the whole scan and names its offset"):
    val text = """He said: "Behold, I accompanied the ghosts."""
    assertEquals(QuotationScan.of(text), Left(QuotationDefect.UnclosedOpen(9)))

  test("a closing mark with nothing open refuses the scan"):
    val text = "nothing was open” here"
    assertEquals(QuotationScan.of(text), Left(QuotationDefect.UnopenedClose(16)))

  test("a curly close over a straight open refuses rather than pairing them"):
    val text = "He said: \"hello” and left."
    assertEquals(QuotationScan.of(text), Left(QuotationDefect.MismatchedClose(15)))

  test("the outermost unclosed opening is the one reported"):
    val text = "“outer “inner” and on"
    assertEquals(QuotationScan.of(text), Left(QuotationDefect.UnclosedOpen(0)))

  test("a text with no marks scans to an empty structure, not a refusal"):
    val s = scan("There were people at Egulac. One night two young men went to hunt seals.")
    assert(s.isEmpty)
    assertEquals(s.containment(TextSpan.unsafe(0, 5)), QuotationContainment.Outside)

  test("containment separates quoted, narrated, and undecidable spans"):
    val text = """He said: "we fought." He became quiet."""
    val s = scan(text)
    val q = s.spans.head
    assertEquals(s.containment(TextSpan.unsafe(3, 7)), QuotationContainment.Outside)
    assertEquals(
      s.containment(TextSpan.unsafe(13, 19)),
      QuotationContainment.Inside(NonEmptyVector.one(q))
    )
    assertEquals(s.containment(TextSpan.unsafe(3, 19)), QuotationContainment.Straddling(q))
    assertEquals(s.containment(q), QuotationContainment.Inside(NonEmptyVector.one(q)))

  test("a straddling span is never Outside, so a caller cannot read it as narration"):
    val text = """He said: "we fought." He became quiet."""
    val s = scan(text)
    val straddle = s.containment(TextSpan.unsafe(15, 30))
    assertNotEquals(straddle, QuotationContainment.Outside)
    assertEquals(straddle.innermost, None)

  test("enclosing quotations come back outermost first and innermost last"):
    val text = "“outer said “inner” end” tail"
    val s = scan(text)
    val inner = TextSpan.unsafe(text.indexOf('i', 12), text.indexOf('i', 12) + 5)
    s.containment(inner) match
      case QuotationContainment.Inside(chain) =>
        assertEquals(chain.length, 2)
        assert(chain.head.length > chain.last.length)
        assertEquals(chain.last, s.containment(inner).innermost.get)
      case other => fail(s"expected Inside, got ${other.render}")

  test("enclosingAt reports the innermost quotation first"):
    val text = "“outer “inner” end”"
    val s = scan(text)
    val at = s.enclosingAt(text.indexOf("inner") + 1)
    assertEquals(at.size, 2)
    assert(at.head.length < at.last.length)

  test("the War of the Ghosts retelling is one quotation covering three sentences"):
    val text =
      """He said: "Behold, I accompanied the ghosts," and he told everything. "We did such and """ +
        """such a thing: we fought. Many of our fellows were killed, and many of those who were """ +
        """attacked were killed. They said that I was shot, and I did not feel sick." He told it """ +
        """all, and then he became quiet."""
    val s = scan(text)
    assertEquals(s.size, 2)
    val retelling = s.spans(1)
    val quoted = text.substring(retelling.start, retelling.endExclusive)
    assert(quoted.startsWith("\"We did such"), quoted.take(20))
    assert(quoted.endsWith("did not feel sick.\""), quoted.takeRight(20))
    assertEquals(quoted.count(_ == '.'), 3)
    val telling =
      TextSpan.unsafe(text.indexOf("told everything"), text.indexOf("told everything") + 4)
    assertEquals(s.containment(telling), QuotationContainment.Outside)
    val fought = TextSpan.unsafe(text.indexOf("we fought"), text.indexOf("we fought") + 9)
    assertEquals(s.containment(fought).innermost, Some(retelling))
