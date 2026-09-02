package storymodel4s.core

import cats.data.NonEmptyVector

/** Why a canonical text has no derivable quotation structure.
  *
  * Why typed and why the scan refuses rather than repairs: a quotation mark the scan cannot pair is
  * the one place where guessing would fabricate exactly the license this family exists to withhold.
  * Extending an unclosed opening to the end of the text asserts a quotation the text never closed;
  * discarding it asserts that everything after it is narration. Both are claims the marks do not
  * support, so the scan makes neither and hands the caller a named defect with the offset of the
  * mark that could not be paired.
  */
enum QuotationDefect:
  /** A closing mark at `offset` with no quotation open. */
  case UnopenedClose(offset: Int)

  /** A curly closing mark at `offset` whose innermost open quotation was opened by a straight mark,
    * so the two do not name the same quotation.
    */
  case MismatchedClose(offset: Int)

  /** The text ended with the quotation opened at `offset` still open. */
  case UnclosedOpen(offset: Int)

  /** The offset of the mark that could not be paired. */
  def markOffset: Int = this match
    case UnopenedClose(o)   => o
    case MismatchedClose(o) => o
    case UnclosedOpen(o)    => o

  def render: String = this match
    case UnopenedClose(o)   => s"unopened-close@$o"
    case MismatchedClose(o) => s"mismatched-close@$o"
    case UnclosedOpen(o)    => s"unclosed-open@$o"

/** Where a span sits relative to the quotations of one text.
  *
  * Why three cases and not a `Boolean`: a span that starts inside a quotation and ends outside it
  * is neither quoted nor narrated, and calling it either one is a claim the marks do not support.
  * The third case is the fail-closed branch — a caller that cannot place a span must say so rather
  * than fall back to the narrated world (design contract 4).
  */
enum QuotationContainment:
  /** The span overlaps no quotation. */
  case Outside

  /** Every quotation wholly containing the span, outermost first. The order is recoverable from the
    * spans themselves — quotations of one scan are properly nested, so the widest is the outermost
    * — which is why the vector carries no separate depth field to disagree with them.
    */
  case Inside(enclosing: NonEmptyVector[TextSpan])

  /** The span overlaps `quotation` without lying wholly inside it, so the marks decide nothing. */
  case Straddling(quotation: TextSpan)

  /** The innermost quotation containing the span, when it lies wholly inside one. */
  def innermost: Option[TextSpan] = this match
    case Inside(enclosing) => Some(enclosing.last)
    case _                 => None

  def render: String = this match
    case Outside           => "outside"
    case Inside(enclosing) =>
      enclosing.toVector.map(s => s"${s.start}:${s.endExclusive}").mkString("inside(", ",", ")")
    case Straddling(q) => s"straddling(${q.start}:${q.endExclusive})"

/** Every quotation of one canonical text, in discourse order.
  *
  * Why this exists: `vision.md` names the exact surface text as the observational coordinate
  * system, and quotation marks are part of that text. A sentence lying wholly inside a quotation is
  * reported content whatever its own words say, and nothing but the marks records that once each
  * sentence is parsed alone. This is the observation; deciding what a quotation means for a
  * situation, and who spoke it, are separate questions answered elsewhere.
  *
  * Why a non-case class with a private constructor: the spans of a scan are sorted, properly
  * nested, and inside their text — a joined claim over the whole vector that a caller could
  * otherwise forge through `copy` or `fromProduct`. Build one with [[QuotationScan.of]].
  *
  * What it recognises, exactly: the double quotation marks `"` (U+0022), `“` (U+201C) and `”`
  * (U+201D). A straight mark opens a quotation, or closes the innermost one when that was itself
  * opened by a straight mark. `“` always opens and `”` always closes. Single quotation marks — `'`
  * (U+0027), `‘` (U+2018) and `’` (U+2019) — are not marks here at all, because an apostrophe in
  * `don't` and an opening single quotation are the same character and the text carries nothing that
  * separates them; treating them as marks would mint quotations the writer did not write. The
  * consequence is stated rather than hidden: speech reported inside single quotation marks is
  * invisible to this scan, and a caller must not read `Outside` as "the narration asserts this".
  */
final class QuotationScan private (val textLength: Int, val spans: Vector[TextSpan]):

  /** Whether the text contains any quotation at all. */
  def isEmpty: Boolean = spans.isEmpty

  def size: Int = spans.size

  /** Where `span` sits relative to the quotations, as a three-way decision.
    *
    * Fail-closed by construction: an overlap that is not containment yields [[Straddling]], never
    * [[Outside]], so a caller cannot silently promote an undecidable span to narrated-world fact.
    */
  def containment(span: TextSpan): QuotationContainment =
    val enclosing = spans.filter(_.contains(span))
    NonEmptyVector.fromVector(enclosing.sortBy(s => (s.start, -s.endExclusive))) match
      case Some(chain) => QuotationContainment.Inside(chain)
      case None        =>
        spans.find(_.overlaps(span)) match
          case Some(q) => QuotationContainment.Straddling(q)
          case None    => QuotationContainment.Outside

  /** The quotation containing `offset`, innermost first, empty when the offset is not quoted. */
  def enclosingAt(offset: Int): Vector[TextSpan] =
    spans.filter(_.contains(offset)).sortBy(s => (-s.start, s.endExclusive))

  override def equals(other: Any): Boolean = other match
    case that: QuotationScan => textLength == that.textLength && spans == that.spans
    case _                   => false

  override def hashCode(): Int = (textLength, spans).hashCode()

  override def toString: String = s"QuotationScan(chars=$textLength, quotations=${spans.size})"

object QuotationScan:
  /** Rule name recorded on receipts that cite a quotation span as evidence. */
  val RuleName: String = "quotation-span-rule-v1"

  /** U+0022. Opens a quotation, or closes the innermost straight-opened one. */
  val StraightMark: Char = '"'

  /** U+201C. Always opens. */
  val CurlyOpenMark: Char = '“'

  /** U+201D. Always closes. */
  val CurlyCloseMark: Char = '”'

  /** The closed set of characters this scan reads. Nothing else is a quotation mark here. */
  val Marks: Set[Char] = Set(StraightMark, CurlyOpenMark, CurlyCloseMark)

  private final case class Open(offset: Int, straight: Boolean)

  /** Scan `text` for quotations, or refuse with the mark that could not be paired.
    *
    * The returned spans run from the opening mark through the closing mark inclusive, so a span
    * sliced out of the text still shows the marks that licensed it.
    */
  def of(text: String): Either[QuotationDefect, QuotationScan] =
    var stack: List[Open] = Nil
    val closed = Vector.newBuilder[TextSpan]
    var defect: Option[QuotationDefect] = None
    var i = 0
    while i < text.length && defect.isEmpty do
      val c = text.charAt(i)
      if c == StraightMark then
        stack match
          case Open(start, true) :: rest =>
            closed += TextSpan.unsafe(start, i + 1)
            stack = rest
          case rest => stack = Open(i, true) :: rest
      else if c == CurlyOpenMark then stack = Open(i, false) :: stack
      else if c == CurlyCloseMark then
        stack match
          case Open(start, false) :: rest =>
            closed += TextSpan.unsafe(start, i + 1)
            stack = rest
          case Open(_, true) :: _ => defect = Some(QuotationDefect.MismatchedClose(i))
          case Nil                => defect = Some(QuotationDefect.UnopenedClose(i))
      i += 1
    defect
      .orElse(stack.lastOption.map(o => QuotationDefect.UnclosedOpen(o.offset)))
      .toLeft(
        new QuotationScan(text.length, closed.result().sortBy(s => (s.start, s.endExclusive)))
      )
