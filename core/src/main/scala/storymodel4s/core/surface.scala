package storymodel4s.core

import cats.{Order, Show}

/** Lexical class of a surface token.
  *
  * Why: feature providers (imageability, frequency, embeddings) need a lawful notion of "word" so
  * punctuation never silently receives a lexical value or inflates a word count.
  */
enum TokenClass:
  case Word, Number, Punctuation, Symbol, Other

/** A token together with its class and a normalized form (lowercase for words). */
final case class TokenView(unit: SurfaceUnit, tokenClass: TokenClass, normalized: Option[String]):
  def isLexical: Boolean = tokenClass == TokenClass.Word || tokenClass == TokenClass.Number
  def span: TextSpan = unit.span

/** Zero-based position of a token in the surface axis. */
object TokenIndex:
  opaque type TokenIndex = Int
  def from(i: Int): Either[DomainError, TokenIndex] =
    if i < 0 then Left(DomainError.InvalidFormat("TokenIndex", i.toString, "negative"))
    else Right(i)
  def unsafe(i: Int): TokenIndex =
    from(i).fold(e => throw new IllegalArgumentException(e.message), identity)
  val Zero: TokenIndex = 0
  extension (i: TokenIndex)
    def value: Int = i
    def +(n: Int): TokenIndex = i + n
    def -(n: Int): TokenIndex = i - n
  given Order[TokenIndex] = Order[Int]
  given Ordering[TokenIndex] = Ordering.Int
  given Show[TokenIndex] = Show.show(_.toString)
type TokenIndex = TokenIndex.TokenIndex

/** Half-open range of token positions `[start, endExclusive)`. */
final case class TokenRange private (start: TokenIndex, endExclusive: TokenIndex):
  def length: Int = endExclusive.value - start.value
  def isEmpty: Boolean = length == 0
  def contains(i: TokenIndex): Boolean = i.value >= start.value && i.value < endExclusive.value
  def indices: Range = start.value until endExclusive.value
  override def toString: String = s"tokens[${start.value}, ${endExclusive.value})"

object TokenRange:
  def of(start: Int, endExclusive: Int): Either[DomainError, TokenRange] =
    if start < 0 then
      Left(DomainError.InvalidFormat("TokenRange", s"[$start,$endExclusive)", "negative start"))
    else if endExclusive < start then
      Left(DomainError.InvalidFormat("TokenRange", s"[$start,$endExclusive)", "end precedes start"))
    else Right(new TokenRange(TokenIndex.unsafe(start), TokenIndex.unsafe(endExclusive)))
  def unsafe(start: Int, endExclusive: Int): TokenRange =
    of(start, endExclusive).fold(e => throw new IllegalArgumentException(e.message), identity)
  given Order[TokenRange] = Order.by(r => (r.start.value, r.endExclusive.value))
  given Ordering[TokenRange] = Order[TokenRange].toOrdering

/** A strictly positive integer, used for window widths and steps. */
object PositiveInt:
  opaque type PositiveInt = Int
  def from(i: Int): Either[DomainError, PositiveInt] =
    if i <= 0 then Left(DomainError.InvalidFormat("PositiveInt", i.toString, "not positive"))
    else Right(i)
  def unsafe(i: Int): PositiveInt =
    from(i).fold(e => throw new IllegalArgumentException(e.message), identity)
  extension (p: PositiveInt) def value: Int = p
type PositiveInt = PositiveInt.PositiveInt

/** What a window counts over. */
enum WindowBasis:
  case AllTokens, LexicalTokens, Sentences

/** What to do with windows that would run past the end of the sequence. */
enum EdgePolicy:
  /** Emit only windows with the full width. */
  case DropPartial

  /** Emit trailing windows that are shorter than the width, marked incomplete. */
  case KeepPartial

  /** Like `KeepPartial`; the window additionally records how many positions were missing so a
    * consumer can pad numerically. No text is invented.
    */
  case Pad

/** A lawful window recipe over the surface axis: `width` units every `step` units of `basis`.
  *
  * Why: every windowed feature must be able to state exactly which tokens it covered, so the plan
  * itself is data that ends up in derivation receipts.
  */
final case class WindowPlan(
    width: PositiveInt,
    step: PositiveInt,
    basis: WindowBasis,
    edgePolicy: EdgePolicy
):
  def canonicalString: String =
    s"window(width=${width.value},step=${step.value},basis=$basis,edge=$edgePolicy)"

object WindowPlan:
  /** `width` lexical words every `step` words. */
  def words(width: Int, step: Int, edgePolicy: EdgePolicy = EdgePolicy.KeepPartial): WindowPlan =
    WindowPlan(
      PositiveInt.unsafe(width),
      PositiveInt.unsafe(step),
      WindowBasis.LexicalTokens,
      edgePolicy
    )

  /** `width` tokens (including punctuation) every `step` tokens. */
  def tokens(width: Int, step: Int, edgePolicy: EdgePolicy = EdgePolicy.KeepPartial): WindowPlan =
    WindowPlan(
      PositiveInt.unsafe(width),
      PositiveInt.unsafe(step),
      WindowBasis.AllTokens,
      edgePolicy
    )

  /** One window per `width` sentences. */
  def sentences(width: Int = 1, step: Int = 1): WindowPlan =
    WindowPlan(
      PositiveInt.unsafe(width),
      PositiveInt.unsafe(step),
      WindowBasis.Sentences,
      EdgePolicy.KeepPartial
    )

  /** Sliding context of `±halfWidth` around every position: width `2h+1`, step 1, partial edges
    * kept so that the sequence is covered end to end.
    */
  def centered(halfWidth: Int, basis: WindowBasis = WindowBasis.LexicalTokens): WindowPlan =
    WindowPlan(
      PositiveInt.unsafe(2 * halfWidth + 1),
      PositiveInt.unsafe(1),
      basis,
      EdgePolicy.KeepPartial
    )

/** A window as a view over the token axis: no text is copied.
  *
  * `tokenRange` spans all tokens from the first to the last covered basis unit; `lexicalTokenCount`
  * counts only words/numbers inside it; `support` is the exact text region; `complete` is false for
  * edge windows narrower than the plan width; `padding` is the number of basis positions the plan
  * would have needed past the end (nonzero only under `EdgePolicy.Pad`).
  */
final case class SurfaceWindow(
    ordinal: Int,
    tokenRange: TokenRange,
    lexicalTokenCount: Int,
    support: SpanSet,
    complete: Boolean,
    padding: Int
)

/** Typed lexical view over a [[SurfaceAtlas]] with stable token coordinates.
  *
  * The atlas's `tokens` vector remains the authoritative primitive; this adds token classes, a
  * lexical (word/number) projection, previous/next navigation, span-to-token lookup, and lawful
  * windows. Building it costs one pass over the tokens.
  */
final class SurfaceSequence private (val atlas: SurfaceAtlas, val tokens: Vector[TokenView]):
  /** Positions of word/number tokens, in order. */
  lazy val lexicalIndices: Vector[TokenIndex] =
    tokens.zipWithIndex.collect { case (t, i) if t.isLexical => TokenIndex.unsafe(i) }

  def lexicalTokens: Vector[TokenView] = lexicalIndices.map(i => tokens(i.value))

  def size: Int = tokens.size
  def lexicalSize: Int = lexicalIndices.size

  def at(index: TokenIndex): Option[TokenView] = tokens.lift(index.value)
  def previous(index: TokenIndex): Option[TokenView] =
    if index.value <= 0 then None else tokens.lift(index.value - 1)
  def next(index: TokenIndex): Option[TokenView] = tokens.lift(index.value + 1)

  /** Position of a token unit in the axis, if it is a token of this atlas. */
  def indexOf(unit: SurfaceUnitId): Option[TokenIndex] = indexById.get(unit)

  private lazy val indexById: Map[SurfaceUnitId, TokenIndex] =
    tokens.zipWithIndex.map((t, i) => t.unit.id -> TokenIndex.unsafe(i)).toMap

  /** Tokens whose spans overlap any span of `support`, in discourse order, without duplicates. */
  def covering(support: SpanSet): Vector[TokenView] =
    coveringIndices(support).map(i => tokens(i.value))

  def coveringIndices(support: SpanSet): Vector[TokenIndex] =
    val hits = support.spans.toVector.flatMap(s => rangeOverlapping(s))
    hits.distinct.sorted

  /** Token positions whose spans overlap `span`, by binary search on span starts. */
  private def rangeOverlapping(span: TextSpan): Vector[TokenIndex] =
    if span.isEmpty || tokens.isEmpty then Vector.empty
    else
      // first token whose end is > span.start
      var lo = 0
      var hi = tokens.length
      while lo < hi do
        val mid = (lo + hi) >>> 1
        if tokens(mid).span.endExclusive <= span.start then lo = mid + 1 else hi = mid
      val b = Vector.newBuilder[TokenIndex]
      var i = lo
      while i < tokens.length && tokens(i).span.start < span.endExclusive do
        if tokens(i).span.overlaps(span) then b += TokenIndex.unsafe(i)
        i += 1
      b.result()

  /** Tokens in `range`. */
  def slice(range: TokenRange): Vector[TokenView] =
    tokens.slice(range.start.value, range.endExclusive.value)

  private def supportOf(range: TokenRange): Option[SpanSet] =
    if range.isEmpty then None
    else
      val first = tokens(range.start.value).span
      val last = tokens(range.endExclusive.value - 1).span
      Some(SpanSet.one(first.hull(last)))

  private def lexicalCount(range: TokenRange): Int =
    range.indices.count(i => tokens(i).isLexical)

  /** Windows according to `plan`; empty when the basis has no units. */
  def windows(plan: WindowPlan): Iterator[SurfaceWindow] =
    val positions: Vector[TokenRange] = plan.basis match
      case WindowBasis.AllTokens =>
        tokens.indices.map(i => TokenRange.unsafe(i, i + 1)).toVector
      case WindowBasis.LexicalTokens =>
        lexicalIndices.map(i => TokenRange.unsafe(i.value, i.value + 1))
      case WindowBasis.Sentences =>
        atlas.sentences.flatMap { s =>
          val idx = coveringIndices(SpanSet.one(s.span))
          if idx.isEmpty then None
          else Some(TokenRange.unsafe(idx.head.value, idx.last.value + 1))
        }
    val n = positions.length
    val w = plan.width.value
    val st = plan.step.value
    val starts = Iterator.from(0, st).takeWhile { s =>
      plan.edgePolicy match
        case EdgePolicy.DropPartial => s + w <= n
        case _                      => s < n
    }
    starts.zipWithIndex.flatMap { (s, k) =>
      val e = math.min(s + w, n)
      if e <= s then None
      else
        val range = TokenRange.unsafe(positions(s).start.value, positions(e - 1).endExclusive.value)
        supportOf(range).map { sup =>
          val missing = s + w - e
          SurfaceWindow(
            k,
            range,
            lexicalCount(range),
            sup,
            complete = missing == 0,
            padding = if plan.edgePolicy == EdgePolicy.Pad then missing else 0
          )
        }
    }

  /** The window of `±halfWidth` basis units around a token, clipped at the edges. */
  def contextAround(index: TokenIndex, halfWidth: Int, basis: WindowBasis): Option[SurfaceWindow] =
    if index.value < 0 || index.value >= tokens.length then None
    else
      basis match
        case WindowBasis.Sentences => None
        case _                     =>
          val axis: Vector[Int] = basis match
            case WindowBasis.LexicalTokens => lexicalIndices.map(_.value)
            case _                         => tokens.indices.toVector
          // position of `index` on the axis (for lexical basis: nearest lexical at or before it)
          val pos = axis.lastIndexWhere(_ <= index.value)
          if pos < 0 then None
          else
            val lo = math.max(0, pos - halfWidth)
            val hi = math.min(axis.length - 1, pos + halfWidth)
            val range = TokenRange.unsafe(axis(lo), axis(hi) + 1)
            supportOf(range).map(sup =>
              SurfaceWindow(
                pos,
                range,
                lexicalCount(range),
                sup,
                complete = (hi - lo) == 2 * halfWidth,
                padding = 0
              )
            )

object SurfaceSequence:
  def apply(atlas: SurfaceAtlas): SurfaceSequence =
    new SurfaceSequence(atlas, atlas.tokens.map(u => classify(u, atlas.source.canonicalText)))

  /** Deterministic token classification from the token's own characters. */
  def classify(unit: SurfaceUnit, text: String): TokenView =
    val s = text.substring(unit.span.start, unit.span.endExclusive)
    val cls =
      if s.isEmpty then TokenClass.Other
      else if s.exists(_.isLetter) then TokenClass.Word
      else if s.forall(c => c.isDigit) then TokenClass.Number
      else if s.length == 1 then
        Character.getType(s.charAt(0)) match
          case Character.CONNECTOR_PUNCTUATION | Character.DASH_PUNCTUATION |
              Character.START_PUNCTUATION | Character.END_PUNCTUATION |
              Character.INITIAL_QUOTE_PUNCTUATION | Character.FINAL_QUOTE_PUNCTUATION |
              Character.OTHER_PUNCTUATION =>
            TokenClass.Punctuation
          case Character.MATH_SYMBOL | Character.CURRENCY_SYMBOL | Character.MODIFIER_SYMBOL |
              Character.OTHER_SYMBOL =>
            TokenClass.Symbol
          case _ => TokenClass.Other
      else if s.forall(c => c.isDigit || c == '_') then TokenClass.Number
      else TokenClass.Other
    val normalized = cls match
      case TokenClass.Word   => Some(s.toLowerCase)
      case TokenClass.Number => Some(s)
      case _                 => None
    TokenView(unit, cls, normalized)
