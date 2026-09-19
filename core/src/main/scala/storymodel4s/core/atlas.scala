package storymodel4s.core

import cats.syntax.all.*

/** BCP-47-style language tag such as `en` or `en-CA`. */
object LanguageTag:
  opaque type LanguageTag = String
  private val Re = "^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*$".r
  def from(raw: String): Either[DomainError, LanguageTag] =
    if Re.matches(raw) then Right(raw)
    else Left(DomainError.InvalidFormat("LanguageTag", raw, "expected BCP-47-like tag"))
  def unsafe(raw: String): LanguageTag =
    from(raw).fold(e => throw new IllegalArgumentException(e.message), identity)
  val English: LanguageTag = "en"
  extension (t: LanguageTag) def value: String = t
  given cats.Show[LanguageTag] = cats.Show.show(identity)
  given cats.Eq[LanguageTag] = cats.Eq.fromUniversalEquals
type LanguageTag = LanguageTag.LanguageTag

/** Locale-independent, code-point-aware text helpers shared by the surface layer.
  *
  * Why: `String.toLowerCase` uses the default locale on the JVM (Turkish dotless-i) and the
  * tokenizer must never cut inside a surrogate pair; both would make offsets and normalized forms
  * platform-dependent.
  */
object TextNorm:
  /** Lowercase by code point, independent of the default locale. */
  def lower(s: String): String =
    val sb = new java.lang.StringBuilder(s.length)
    var i = 0
    while i < s.length do
      val cp = s.codePointAt(i)
      sb.appendCodePoint(Character.toLowerCase(cp))
      i += Character.charCount(cp)
    sb.toString

  /** Code points of `s` in order. */
  def codePoints(s: String): Vector[Int] =
    val b = Vector.newBuilder[Int]
    var i = 0
    while i < s.length do
      val cp = s.codePointAt(i)
      b += cp
      i += Character.charCount(cp)
    b.result()

  def isWordCodePoint(cp: Int): Boolean =
    Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK ||
      Character.getType(cp) == Character.COMBINING_SPACING_MARK || cp == '_'

  def isDigitCodePoint(cp: Int): Boolean = Character.isDigit(cp)

  /** `true` for strings shaped like a number: digit runs optionally separated by single `.` or `,`
    * characters (`3.5`, `1,000`, `2024`).
    */
  def isNumberShaped(s: String): Boolean =
    val cps = codePoints(s)
    cps.nonEmpty && cps.forall(cp => isDigitCodePoint(cp) || cp == '.' || cp == ',' || cp == '_') &&
    cps.exists(isDigitCodePoint) &&
    cps.indices.forall { i =>
      val cp = cps(i)
      if cp == '.' || cp == ',' then
        i > 0 && i + 1 < cps.length && isDigitCodePoint(cps(i - 1)) && isDigitCodePoint(cps(i + 1))
      else true
    }

/** How a story's title came to be known.
  *
  * Why typed and why only one case: a title is a claim about the work, and the only basis this
  * project has ever been able to establish for one is that a caller stated it. A title a tool
  * derived from something that is not the work — a filename, a directory, a request id — is not on
  * this list, so there is no case to record it under and no way to publish it as established. The
  * enum widens when a real derivation exists (a title line the text itself carries, say), and each
  * new case names the basis rather than sharing an unlabelled default with the others.
  */
enum TitleProvenance:
  /** A caller stated this title. The claim is the caller's, and the model records it as theirs. */
  case CallerSupplied

  def render: String = this match
    case CallerSupplied => TitleProvenance.CallerSuppliedTag

object TitleProvenance:
  private[core] val CallerSuppliedTag: String = "caller-supplied"

  /** Key under which [[StorySource.metadata]] carries the provenance of `title`. Metadata and not a
    * constructor field so that a source built before this rule existed reads back as a title with
    * *no recorded provenance*, which is exactly what it is — not as one silently promoted to
    * caller-supplied.
    */
  val MetadataKey: String = "title.provenance"

  def parse(raw: String): Option[TitleProvenance] =
    if raw == CallerSuppliedTag then Some(CallerSupplied) else None

/** A story title together with the provenance that entitles the model to carry it.
  *
  * Why a checked type rather than a `String`: the defect this closes is a pipeline that handed
  * `StorySource` the input file's name and got a summary claim at credence 1.0 asserting the
  * narrative was called `wog.txt`. A bare `String` cannot tell a title someone stated from a string
  * some caller happened to have; this type can only be obtained by naming the basis.
  *
  * Non-case so neither `copy` nor `fromProduct` can pair a value with a provenance it did not pass
  * the constructor with.
  */
final class StoryTitle private (val value: String, val provenance: TitleProvenance):
  /** The metadata entry that records this title's provenance on a [[StorySource]]. */
  def metadataEntry: (String, String) = TitleProvenance.MetadataKey -> provenance.render

  override def equals(other: Any): Boolean = other match
    case that: StoryTitle => value == that.value && provenance == that.provenance
    case _                => false

  override def hashCode(): Int = (value, provenance).hashCode()

  override def toString: String = s"StoryTitle($value, ${provenance.render})"

object StoryTitle:
  /** Longest title this project will carry. A title is a name, not a paragraph; a caller passing
    * prose has passed the wrong thing and gets told so rather than having it published.
    */
  val MaxLength: Int = 200

  /** A title a caller states, as their claim. Refused when blank, when it spans lines, when it
    * carries a control character, when it is longer than [[MaxLength]], or when it holds a path
    * separator — the last because a path is the shape of the defect this type exists to close, and
    * a caller who means it can state the name without the directory.
    */
  def callerSupplied(raw: String): Either[DomainError, StoryTitle] =
    val trimmed = raw.trim
    def refuse(why: String) = Left(DomainError.InvalidFormat("StoryTitle", raw, why))
    if trimmed.isEmpty then refuse("blank title")
    else if trimmed.length > MaxLength then refuse(s"title longer than $MaxLength characters")
    else if trimmed.exists(c => c.isControl) then refuse("title contains a control character")
    else if trimmed.exists(c => c == '/' || c == '\\') then
      refuse("title contains a path separator")
    else Right(new StoryTitle(trimmed, TitleProvenance.CallerSupplied))

/** The immutable source text of a story with raw and canonical forms and their checksums.
  *
  * All offsets in the model are interpreted against `canonicalText` only. Non-case so `fromProduct`
  * cannot mint empty text or checksums that do not match the text.
  */
final class StorySource private (
    val id: StoryId,
    val title: Option[String],
    val language: LanguageTag,
    val rawText: String,
    val canonicalText: String,
    val rawChecksum: Checksum,
    val canonicalChecksum: Checksum,
    val metadata: Map[String, String]
):
  override def equals(other: Any): Boolean = other match
    case that: StorySource =>
      id == that.id &&
      title == that.title &&
      language == that.language &&
      rawText == that.rawText &&
      canonicalText == that.canonicalText &&
      rawChecksum == that.rawChecksum &&
      canonicalChecksum == that.canonicalChecksum &&
      metadata == that.metadata
    case _ => false

  override def hashCode(): Int =
    (id, title, language, rawText, canonicalText, rawChecksum, canonicalChecksum, metadata)
      .hashCode()

  override def toString: String =
    s"StorySource(${id.value}, title=$title, lang=${language.value}, rawChars=${rawText.length})"

  /** The recorded provenance of `title`, or nothing when the source records none. */
  def titleProvenance: Option[TitleProvenance] =
    metadata.get(TitleProvenance.MetadataKey).flatMap(TitleProvenance.parse)

  /** The title *and* the basis for carrying it, or nothing.
    *
    * Why consumers must read this and not `title`: `title` is whatever a caller put there, and a
    * caller with no basis is exactly how `wog.txt` became a summary claim. A title whose provenance
    * the source does not record is not established, and this method is the difference between the
    * two — visibly, at every call site, rather than in a comment.
    */
  def establishedTitle: Option[StoryTitle] =
    titleProvenance.flatMap { case TitleProvenance.CallerSupplied =>
      title.flatMap(raw => StoryTitle.callerSupplied(raw).toOption)
    }

object StorySource:
  /** Line endings to `\n`, trailing whitespace stripped per line, runs of more than two newlines
    * collapsed to two, leading/trailing blank lines removed.
    */
  def canonicalize(raw: String): String =
    val unixLines = raw.replace("\r\n", "\n").replace('\r', '\n')
    val stripped = unixLines.split("\n", -1).map(_.replaceAll("[ \t ]+$", "")).mkString("\n")
    val collapsed = stripped.replaceAll("\n{3,}", "\n\n")
    collapsed.replaceAll("^\n+", "").replaceAll("\n+$", "")

  def fromText(
      rawText: String,
      title: Option[String] = None,
      language: LanguageTag = LanguageTag.English,
      metadata: Map[String, String] = Map.empty,
      explicitId: Option[StoryId] = None
  ): Either[DomainError, StorySource] =
    if rawText.trim.isEmpty then Left(DomainError.InvalidFormat("StorySource", "", "empty text"))
    else
      val canonical = canonicalize(rawText)
      val canonSum = Checksum.ofText(canonical)
      val id = explicitId.getOrElse(StoryId.unsafe(ContentAddress.of("story", canonSum.hex)))
      Right(
        new StorySource(
          id,
          title,
          language,
          rawText,
          canonical,
          Checksum.ofText(rawText),
          canonSum,
          metadata
        )
      )

  /** A source whose title is established: the value and its provenance are recorded together, so
    * [[StorySource.establishedTitle]] can return it. The only way to build one, and the reason a
    * caller cannot get an established title by writing the metadata key by hand and hoping.
    */
  def titled(
      rawText: String,
      title: StoryTitle,
      language: LanguageTag = LanguageTag.English,
      metadata: Map[String, String] = Map.empty,
      explicitId: Option[StoryId] = None
  ): Either[DomainError, StorySource] =
    fromText(rawText, Some(title.value), language, metadata + title.metadataEntry, explicitId)

/** Kinds of deterministic surface units. `Clause` is an extraction anchor produced by later stages,
  * never by the surface analyzer.
  */
enum SurfaceUnitKind:
  case Paragraph, Sentence, Clause, Token

/** A surface unit with a span into the canonical text and an ordinal within its kind. */
final case class SurfaceUnit(
    id: SurfaceUnitId,
    kind: SurfaceUnitKind,
    span: TextSpan,
    ordinal: Int,
    parent: Option[SurfaceUnitId]
):
  def text(source: StorySource): String =
    source.canonicalText.substring(span.start, span.endExclusive)

/** Exact surface decomposition of a source text.
  *
  * Why a non-case class: unique IDs, bounded spans, coherent ordinals, non-overlap, and valid
  * parentage must hold for every atlas that exists. Construct with [[SurfaceAtlas.of]] (checked) or
  * [[SurfaceAtlas.unsafe]] (throws on violation); there is no unchecked `copy` or `fromProduct`
  * path.
  */
final class SurfaceAtlas private (
    val source: StorySource,
    val units: Vector[SurfaceUnit]
):
  lazy val byId: Map[SurfaceUnitId, SurfaceUnit] = units.iterator.map(u => u.id -> u).toMap

  lazy val byKind: Map[SurfaceUnitKind, Vector[SurfaceUnit]] =
    units.groupBy(_.kind).view.mapValues(_.sortBy(_.ordinal)).toMap

  private lazy val children: Map[SurfaceUnitId, Vector[SurfaceUnit]] =
    units
      .flatMap(u => u.parent.map(_ -> u))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.sortBy(_.span))
      .toMap

  def paragraphs: Vector[SurfaceUnit] = byKind.getOrElse(SurfaceUnitKind.Paragraph, Vector.empty)
  def sentences: Vector[SurfaceUnit] = byKind.getOrElse(SurfaceUnitKind.Sentence, Vector.empty)
  def tokens: Vector[SurfaceUnit] = byKind.getOrElse(SurfaceUnitKind.Token, Vector.empty)

  def childrenOf(id: SurfaceUnitId): Vector[SurfaceUnit] = children.getOrElse(id, Vector.empty)
  def parentOf(u: SurfaceUnit): Option[SurfaceUnit] = u.parent.flatMap(byId.get)

  /** The unit of `kind` containing `offset`, by binary search over discourse order (valid because
    * units of one kind never overlap).
    */
  def unitAt(offset: Int, kind: SurfaceUnitKind): Option[SurfaceUnit] =
    val vs = byKind.getOrElse(kind, Vector.empty)
    var lo = 0
    var hi = vs.length - 1
    var found: Option[SurfaceUnit] = None
    while lo <= hi && found.isEmpty do
      val mid = (lo + hi) >>> 1
      val u = vs(mid)
      if u.span.contains(offset) then found = Some(u)
      else if offset < u.span.start then hi = mid - 1
      else lo = mid + 1
    found

  /** Units of `kind` overlapping `span`, in discourse order. */
  def unitsOverlapping(span: TextSpan, kind: SurfaceUnitKind): Vector[SurfaceUnit] =
    byKind.getOrElse(kind, Vector.empty).filter(_.span.overlaps(span))

  def text(u: SurfaceUnit): String = u.text(source)

  override def equals(other: Any): Boolean = other match
    case that: SurfaceAtlas => source == that.source && units == that.units
    case _                  => false

  override def hashCode(): Int = (source, units).hashCode()

  override def toString: String =
    s"SurfaceAtlas(${source.id.value}, units=${units.size})"

object SurfaceAtlas:
  private val coarseness: Map[SurfaceUnitKind, Int] = Map(
    SurfaceUnitKind.Paragraph -> 0,
    SurfaceUnitKind.Sentence -> 1,
    SurfaceUnitKind.Clause -> 2,
    SurfaceUnitKind.Token -> 3
  )

  private def validate(atlas: SurfaceAtlas): Either[DomainError, SurfaceAtlas] =
    val textLen = atlas.source.canonicalText.length
    val dupId = atlas.units.groupBy(_.id).collectFirst { case (id, us) if us.size > 1 => id }
    val checks: Either[DomainError, Unit] = for
      _ <- dupId.toLeft(()).leftMap(id => DomainError.DuplicateId("SurfaceUnitId", id.value))
      _ <- atlas.units.traverse_ { u =>
        if u.span.endExclusive <= textLen then Right(())
        else
          Left(
            DomainError.InvariantViolation(
              s"atlas/units/${u.id.value}",
              s"span ${u.span} exceeds text length $textLen"
            )
          )
      }
      _ <- atlas.byKind.toVector.traverse_ { (kind, us) =>
        val ords = us.map(_.ordinal)
        val byOrd = us.sortBy(_.ordinal)
        if ords.distinct.size != ords.size then
          Left(DomainError.InvariantViolation(s"atlas/$kind", "duplicate ordinals"))
        else if ords != ords.sorted then
          Left(DomainError.InvariantViolation(s"atlas/$kind", "ordinals not increasing"))
        else if byOrd.map(_.span) != byOrd.map(_.span).sorted then
          Left(
            DomainError
              .InvariantViolation(s"atlas/$kind", "ordinal order disagrees with span order")
          )
        else
          byOrd
            .sliding(2)
            .collectFirst {
              case Vector(a, b) if b.span.start < a.span.endExclusive && !a.span.isEmpty =>
                DomainError.InvariantViolation(
                  s"atlas/$kind/${b.id.value}",
                  s"unit ${b.span} overlaps preceding unit ${a.span}"
                )
            }
            .toLeft(())
      }
      _ <- atlas.units.traverse_ { u =>
        u.parent match
          case None      => Right(())
          case Some(pid) =>
            atlas.byId.get(pid) match
              case None =>
                Left(
                  DomainError.InvariantViolation(
                    s"atlas/units/${u.id.value}",
                    s"missing parent ${pid.value}"
                  )
                )
              case Some(p) if !p.span.contains(u.span) =>
                Left(
                  DomainError.InvariantViolation(
                    s"atlas/units/${u.id.value}",
                    s"span ${u.span} escapes parent ${p.span}"
                  )
                )
              case Some(p) if coarseness(p.kind) >= coarseness(u.kind) =>
                Left(
                  DomainError.InvariantViolation(
                    s"atlas/units/${u.id.value}",
                    s"parent kind ${p.kind} is not coarser than ${u.kind}"
                  )
                )
              case _ => Right(())
      }
    yield ()
    checks.as(atlas)

  /** Checked constructor enforcing all surface-atlas invariants. */
  def of(
      source: StorySource,
      units: Vector[SurfaceUnit]
  ): Either[DomainError, SurfaceAtlas] =
    validate(new SurfaceAtlas(source, units))

  /** Throws `IllegalArgumentException` when any surface-atlas invariant is violated. */
  def unsafe(source: StorySource, units: Vector[SurfaceUnit]): SurfaceAtlas =
    of(source, units).fold(e => throw new IllegalArgumentException(e.message), identity)

  /** Re-checks an existing value; always `Right` for values built through [[of]], kept for
    * aggregate validators that recursively validate their members.
    */
  def validated(atlas: SurfaceAtlas): Either[DomainError, SurfaceAtlas] = validate(atlas)

/** Deterministic, dependency-free surface analysis: paragraphs, sentences, tokens.
  *
  * Why deterministic: the atlas is the universal offset basis for every later claim, so it must be
  * reproducible bit-for-bit without any model.
  */
object SurfaceAnalyzer:
  /** Abbreviations that end with a period and never end a sentence on their own. Ordinary words
    * that are occasionally abbreviated (`no`, `co`, `st`, `gen`, month names that are also words)
    * are deliberately excluded: a missed split costs more downstream than a rare spurious one.
    */
  private val Abbreviations: Set[String] = Set(
    "mr",
    "mrs",
    "ms",
    "dr",
    "prof",
    "sr",
    "jr",
    "vs",
    "etc",
    "e.g",
    "i.e",
    "inc",
    "ltd",
    "fig",
    "vol",
    "pp",
    "eds",
    "cf",
    "approx",
    "dept",
    "gov",
    "sgt",
    "capt",
    "hon",
    "ave",
    "blvd",
    "jan",
    "feb",
    "apr",
    "jun",
    "jul",
    "aug",
    "sept",
    "oct",
    "nov"
  )

  /** Words that overwhelmingly begin a sentence when capitalized after a period. Used to decide
    * that `grade A. Then …` or `the U.S. Then …` ends a sentence although `A.`/`U.S.` look like
    * initials.
    */
  private val SentenceStarters: Set[String] = Set(
    "the",
    "then",
    "he",
    "she",
    "it",
    "they",
    "we",
    "i",
    "you",
    "but",
    "and",
    "so",
    "after",
    "before",
    "when",
    "while",
    "now",
    "there",
    "this",
    "that",
    "these",
    "those",
    "his",
    "her",
    "my",
    "our",
    "their",
    "its",
    "in",
    "on",
    "at",
    "a",
    "an",
    "one",
    "later",
    "next",
    "suddenly",
    "meanwhile",
    "however",
    "yes",
    "no",
    "what",
    "who",
    "why",
    "how",
    "where",
    "if",
    "as",
    "for",
    "by",
    "with",
    "to",
    "of",
    "from",
    "some",
    "all",
    "each",
    "every",
    "nobody",
    "everyone",
    "someone",
    "nothing",
    "something"
  )

  private val Terminal = Set('.', '!', '?')
  private val Closers = Set('"', '”', '’', '\'', ')', ']', '}', '»')
  private val BracketOpeners = Set('(', '[', '{', '«')
  private val BracketClosers = Set(')', ']', '}', '»')

  /** An unclosed bracket suppresses sentence boundaries only this many characters past it, so a
    * stray `(` cannot swallow the rest of a paragraph.
    */
  private val BracketReach = 300

  def analyze(source: StorySource): SurfaceAtlas =
    val text = source.canonicalText
    val sid = source.id.value
    val paragraphs = Vector.newBuilder[SurfaceUnit]
    val sentences = Vector.newBuilder[SurfaceUnit]
    val tokens = Vector.newBuilder[SurfaceUnit]
    var pOrd = 0
    var sOrd = 0
    var tOrd = 0
    paragraphSpans(text).foreach { pSpan =>
      val pId = SurfaceUnitId.unsafe(s"$sid:p$pOrd")
      paragraphs += SurfaceUnit(pId, SurfaceUnitKind.Paragraph, pSpan, pOrd, None)
      pOrd += 1
      sentenceSpans(text, pSpan).foreach { sSpan =>
        val sUid = SurfaceUnitId.unsafe(s"$sid:s$sOrd")
        sentences += SurfaceUnit(sUid, SurfaceUnitKind.Sentence, sSpan, sOrd, Some(pId))
        sOrd += 1
        tokenSpans(text, sSpan).foreach { tSpan =>
          tokens += SurfaceUnit(
            SurfaceUnitId.unsafe(s"$sid:t$tOrd"),
            SurfaceUnitKind.Token,
            tSpan,
            tOrd,
            Some(sUid)
          )
          tOrd += 1
        }
      }
    }
    SurfaceAtlas.unsafe(source, paragraphs.result() ++ sentences.result() ++ tokens.result())

  /** Paragraphs are maximal runs separated by one or more blank lines; leading/trailing whitespace
    * is excluded from the span.
    */
  def paragraphSpans(text: String): Vector[TextSpan] =
    val out = Vector.newBuilder[TextSpan]
    var i = 0
    val n = text.length
    while i < n do
      while i < n && text.charAt(i).isWhitespace do i += 1
      if i < n then
        val start = i
        var end = i
        var done = false
        while !done && i < n do
          if text.charAt(i) == '\n' then
            var j = i + 1
            while j < n && (text.charAt(j) == ' ' || text.charAt(j) == '\t') do j += 1
            if j < n && text.charAt(j) == '\n' then done = true
            else if j >= n then done = true
            else i += 1
          else
            i += 1
            if !text.charAt(i - 1).isWhitespace then end = i
        out += TextSpan.unsafe(start, end)
    out.result()

  /** The letters of the word starting at `from` (after whitespace), lowercased. */
  private def nextWord(text: String, from: Int, end: Int): String =
    var q = from
    while q < end && !text.charAt(q).isLetter do q += 1
    val s = q
    while q < end && text.charAt(q).isLetter do q += 1
    TextNorm.lower(text.substring(s, q))

  /** Whether the period at `dotIndex` ends an abbreviation, an initial (`J.`), or an uppercase
    * initialism (`U.S.`) rather than a sentence. Initials and initialisms are still sentence ends
    * when the following word is a common sentence starter.
    */
  private def isAbbreviation(text: String, dotIndex: Int, end: Int): Boolean =
    var j = dotIndex - 1
    while j >= 0 && (text.charAt(j).isLetter || text.charAt(j) == '.') do j -= 1
    val raw = text.substring(j + 1, dotIndex)
    val word = TextNorm.lower(raw)
    if word.isEmpty then false
    else if Abbreviations.contains(word) then true
    else
      val segments = raw.split('.').toVector
      val initialLike =
        segments.nonEmpty && segments.forall(s => s.length == 1 && s.charAt(0).isUpper)
      initialLike && !SentenceStarters.contains(nextWord(text, dotIndex + 1, end))

  private def startsSentence(c: Char): Boolean =
    c.isUpper || c.isDigit || c == '"' || c == '“' || c == '‘' || c == '(' || c == '[' ||
      c == '«' || c == '\''

  /** Conservative rule-based sentence splitting inside one paragraph.
    *
    * A boundary needs terminal punctuation (plus any closing quotes/brackets), whitespace, and a
    * following sentence-initial character. Splits never occur after known abbreviations or
    * initials, before a lowercase continuation such as `"Go home!" she shouted`, or shortly after
    * an unclosed bracket. Quotation marks never gate a split: multi-sentence dialogue must yield
    * one unit per sentence, and an unbalanced quote (typographic paragraph-continuation) must not
    * merge a paragraph.
    */
  def sentenceSpans(text: String, para: TextSpan): Vector[TextSpan] =
    val out = Vector.newBuilder[TextSpan]
    var i = para.start
    val end = para.endExclusive
    var openBrackets: List[Int] = Nil
    var start = -1
    while i < end do
      val c = text.charAt(i)
      if start < 0 && !c.isWhitespace then start = i
      if BracketOpeners.contains(c) then openBrackets = i :: openBrackets
      else if BracketClosers.contains(c) then openBrackets = openBrackets.drop(1)
      if Terminal.contains(c) then
        // consume the run of terminal punctuation (e.g. "..." or "?!")
        var k = i
        while k + 1 < end && Terminal.contains(text.charAt(k + 1)) do k += 1
        val abbrev = c == '.' && k == i && isAbbreviation(text, i, end)
        // consume closing quotes / brackets
        var m = k
        while m + 1 < end && Closers.contains(text.charAt(m + 1)) do
          m += 1
          if BracketClosers.contains(text.charAt(m)) then openBrackets = openBrackets.drop(1)
        // look ahead past whitespace
        var q = m + 1
        while q < end && text.charAt(q).isWhitespace do q += 1
        val insideBracket = openBrackets.headOption.exists(p => i - p <= BracketReach)
        val boundary =
          !abbrev && !insideBracket &&
            (q >= end || (q > m + 1 && startsSentence(text.charAt(q))))
        if boundary then
          out += TextSpan.unsafe(start, m + 1)
          start = -1
        i = m + 1
      else i += 1
    if start >= 0 then
      var e = end
      while e > start && text.charAt(e - 1).isWhitespace do e -= 1
      if e > start then out += TextSpan.unsafe(start, e)
    out.result()

  /** Tokens: maximal letter/digit runs (allowing internal apostrophes and hyphens between word
    * characters and `.`/`,` between digits, so `3.5` and `1,000` are single tokens), every other
    * non-whitespace code point as its own token. Spans never cut inside a surrogate pair.
    */
  def tokenSpans(text: String, within: TextSpan): Vector[TextSpan] =
    val out = Vector.newBuilder[TextSpan]
    var i = within.start
    val end = within.endExclusive
    inline def cpAt(k: Int): Int = text.codePointAt(k)
    while i < end do
      val cp = cpAt(i)
      val w = Character.charCount(cp)
      if Character.isWhitespace(cp) then i += w
      else if TextNorm.isWordCodePoint(cp) then
        val start = i
        var cont = true
        while cont && i < end do
          val d = cpAt(i)
          val dw = Character.charCount(d)
          if TextNorm.isWordCodePoint(d) then i += dw
          else if i > start && i + dw < end then
            val prev = text.codePointBefore(i)
            val next = cpAt(i + dw)
            val joiner =
              ((d == '\'' || d == '’' || d == '-') && TextNorm.isWordCodePoint(next)) ||
                ((d == '.' || d == ',') && TextNorm.isDigitCodePoint(prev) &&
                  TextNorm.isDigitCodePoint(next))
            if joiner then i += dw else cont = false
          else cont = false
        out += TextSpan.unsafe(start, i)
      else
        out += TextSpan.unsafe(i, i + w)
        i += w
    out.result()

/** Checked proposal units and typed support consumed by later compiler slices.
  *
  * Why a trait: text keeps [[SurfaceAtlas]] as the exact UTF-16 implementation, while a film
  * adapter can supply the same compiler-facing surface without changing text constructors.
  */
sealed trait NarrativeSourceAtlas:
  def bundle: SourceBundle
  def units: Vector[NarrativeProposalUnit]
  def unit(id: NarrativeProposalUnitId): Option[NarrativeProposalUnit]
  def supportOf(id: NarrativeProposalUnitId): Option[EvidenceSupport]

/** Text conformance adapter. Equality and lookup of the wrapped [[SurfaceAtlas]] are unchanged. */
final class TextNarrativeAtlas private (
    val atlas: SurfaceAtlas,
    val bundle: SourceBundle,
    val units: Vector[NarrativeProposalUnit]
) extends NarrativeSourceAtlas:
  private lazy val byId = units.iterator.map(unit => unit.id -> unit).toMap
  def unit(id: NarrativeProposalUnitId): Option[NarrativeProposalUnit] = byId.get(id)
  def supportOf(id: NarrativeProposalUnitId): Option[EvidenceSupport] = unit(id).map(_.support)

  override def equals(other: Any): Boolean = other match
    case that: TextNarrativeAtlas =>
      atlas == that.atlas && bundle == that.bundle && units == that.units
    case _ => false
  override def hashCode(): Int = (atlas, bundle, units).hashCode()
  override def toString: String =
    s"TextNarrativeAtlas(${atlas.source.id.value}, units=${units.size})"

object TextNarrativeAtlas:
  def of(atlas: SurfaceAtlas): Either[DomainError, TextNarrativeAtlas] =
    SourceBundle.writtenText(atlas.source).flatMap { bundle =>
      val stream = bundle.streams.head
      atlas.units
        .foldLeft[Either[DomainError, Vector[NarrativeProposalUnit]]](Right(Vector.empty)) {
          case (acc, unit) =>
            acc.flatMap { us =>
              val unitId = NarrativeProposalUnitId.unsafe(
                ContentAddress.of("npu", atlas.source.id.value, unit.id.value)
              )
              EvidenceSupport.text(bundle, stream.id, SpanSet.one(unit.span)).map { support =>
                us :+ NarrativeProposalUnit.of(unitId, support, Some(unit.id))
              }
            }
        }
        .map(units => new TextNarrativeAtlas(atlas, bundle, units))
    }

/** SurfaceAtlas conformance: existing constructors and lookups stay the text implementation. */
object SurfaceAtlasConformance:
  def narrativeAtlas(atlas: SurfaceAtlas): Either[DomainError, NarrativeSourceAtlas] =
    TextNarrativeAtlas.of(atlas)

/** A derived proposal surface associated with a supplied receipt, not an output attestation. */
final class BoundProposalSurface private (
    val surface: SurfaceAtlas,
    val checksum: Checksum,
    val receipt: SourceDerivationReceipt,
    val identity: Checksum
):
  override def equals(other: Any): Boolean = other match
    case that: BoundProposalSurface => surface == that.surface && receipt == that.receipt
    case _                          => false
  override def hashCode(): Int = (surface, receipt).hashCode()

object BoundProposalSurface:
  def of(surface: SurfaceAtlas, receipt: SourceDerivationReceipt): BoundProposalSurface =
    val checksum = SourceIdentity.surface(surface)
    val identity = SourceIdentity.digest(
      Vector(
        "bound-proposal-surface/v1",
        checksum.hex,
        surface.source.canonicalChecksum.hex,
        receipt.bindingIdentity.hex
      )
    )
    new BoundProposalSurface(surface, checksum, receipt, identity)

/** Checked film proposal inventory. Text derivations remain outside the film bundle. */
final class AnchoredNarrativeAtlas private (
    val bundle: SourceBundle,
    val units: Vector[NarrativeProposalUnit],
    val surface: Option[BoundProposalSurface]
) extends NarrativeSourceAtlas:
  private lazy val byId = units.iterator.map(unit => unit.id -> unit).toMap
  def unit(id: NarrativeProposalUnitId): Option[NarrativeProposalUnit] = byId.get(id)
  def supportOf(id: NarrativeProposalUnitId): Option[EvidenceSupport] = unit(id).map(_.support)
  override def equals(other: Any): Boolean = other match
    case that: AnchoredNarrativeAtlas =>
      bundle == that.bundle && units == that.units && surface == that.surface
    case _ => false
  override def hashCode(): Int = (bundle, units, surface).hashCode()

object AnchoredNarrativeAtlas:
  def of(
      bundle: SourceBundle,
      units: Vector[NarrativeProposalUnit],
      surface: Option[BoundProposalSurface] = None
  ): Either[DomainError, AnchoredNarrativeAtlas] =
    val ids = units.map(_.id)
    val surfaces = units.flatMap(_.surface)
    if bundle.primaryAxis.kind != AxisKind.EditionPlayback then
      Left(
        SourceCanon.inv(
          "atlas/primary-axis",
          "anchored atlas requires edition playback; other kinds remain unsupported"
        )
      )
    else if ids.distinct.size != ids.size then
      Left(SourceCanon.inv("atlas/units", "duplicate narrative proposal unit identity"))
    else if surfaces.distinct.size != surfaces.size then
      Left(SourceCanon.inv("atlas/surface", "duplicate proposal surface sentence"))
    else if surfaces.exists(id =>
        !surface.exists(_.surface.byId.get(id).exists(_.kind == SurfaceUnitKind.Sentence))
      )
    then
      Left(
        SourceCanon.inv("atlas/surface", "proposal surface is not a sentence of the bound surface")
      )
    else
      units
        .foldLeft[Either[DomainError, Unit]](Right(())) { (result, unit) =>
          result
            .flatMap(_ => EvidenceSupport.of(bundle, unit.support.anchors.toVector).map(_ => ()))
        }
        .map(_ => new AnchoredNarrativeAtlas(bundle, units, surface))

  def bundleOf(source: StorySource): Either[DomainError, SourceBundle] =
    SourceBundle.writtenText(source)
