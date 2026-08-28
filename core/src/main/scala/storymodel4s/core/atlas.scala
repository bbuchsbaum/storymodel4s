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

/** The immutable source text of a story with raw and canonical forms and their checksums.
  *
  * All offsets in the model are interpreted against `canonicalText` only.
  */
final case class StorySource private (
    id: StoryId,
    title: Option[String],
    language: LanguageTag,
    rawText: String,
    canonicalText: String,
    rawChecksum: Checksum,
    canonicalChecksum: Checksum,
    metadata: Map[String, String]
)

object StorySource:
  /** Line endings to `\n`, trailing whitespace stripped per line, runs of more than two newlines
    * collapsed to two, leading/trailing blank lines removed.
    */
  def canonicalize(raw: String): String =
    val unixLines = raw.replace("\r\n", "\n").replace('\r', '\n')
    val stripped = unixLines.split("\n", -1).map(_.replaceAll("[ \t ]+$", "")).mkString("\n")
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
  * Invariants (checked by [[SurfaceAtlas.validated]]): unique IDs; spans within the text; ordinals
  * unique and increasing in discourse order per kind; children within parent spans; parents exist
  * and are of a coarser kind.
  */
final case class SurfaceAtlas(source: StorySource, units: Vector[SurfaceUnit]):
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

  /** The unit of `kind` containing `offset`, by binary search over discourse order. */
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

object SurfaceAtlas:
  private val coarseness: Map[SurfaceUnitKind, Int] = Map(
    SurfaceUnitKind.Paragraph -> 0,
    SurfaceUnitKind.Sentence -> 1,
    SurfaceUnitKind.Clause -> 2,
    SurfaceUnitKind.Token -> 3
  )

  def validated(atlas: SurfaceAtlas): Either[DomainError, SurfaceAtlas] =
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
        if ords.distinct.size != ords.size then
          Left(DomainError.InvariantViolation(s"atlas/$kind", "duplicate ordinals"))
        else if ords != ords.sorted then
          Left(DomainError.InvariantViolation(s"atlas/$kind", "ordinals not increasing"))
        else if us.sortBy(_.ordinal).map(_.span) != us.sortBy(_.ordinal).map(_.span).sorted then
          Left(
            DomainError
              .InvariantViolation(s"atlas/$kind", "ordinal order disagrees with span order")
          )
        else Right(())
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

/** Deterministic, dependency-free surface analysis: paragraphs, sentences, tokens.
  *
  * Why deterministic: the atlas is the universal offset basis for every later claim, so it must be
  * reproducible bit-for-bit without any model.
  */
object SurfaceAnalyzer:
  private val Abbreviations: Set[String] = Set(
    "mr",
    "mrs",
    "ms",
    "dr",
    "prof",
    "sr",
    "jr",
    "st",
    "vs",
    "etc",
    "e.g",
    "i.e",
    "inc",
    "ltd",
    "co",
    "no",
    "fig",
    "vol",
    "pp",
    "ed",
    "eds",
    "cf",
    "approx",
    "dept",
    "est",
    "gen",
    "gov",
    "lt",
    "col",
    "sgt",
    "capt",
    "rev",
    "hon",
    "mt",
    "ft",
    "ave",
    "blvd",
    "rd",
    "jan",
    "feb",
    "mar",
    "apr",
    "jun",
    "jul",
    "aug",
    "sep",
    "sept",
    "oct",
    "nov",
    "dec"
  )

  private val Terminal = Set('.', '!', '?')
  private val Closers = Set('"', '”', '’', '\'', ')', ']', '}', '»')
  private val OpenersCurly = Set('“', '(', '[', '{', '«')
  private val ClosersCurly = Set('”', ')', ']', '}', '»')

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
    SurfaceAtlas(source, paragraphs.result() ++ sentences.result() ++ tokens.result())

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

  private def isAbbreviation(text: String, dotIndex: Int): Boolean =
    var j = dotIndex - 1
    while j >= 0 && (text.charAt(j).isLetter || text.charAt(j) == '.') do j -= 1
    val word = text.substring(j + 1, dotIndex).toLowerCase
    word.nonEmpty && (Abbreviations.contains(word) || (word.length == 1 && word.forall(_.isLetter)))

  private def startsSentence(c: Char): Boolean =
    c.isUpper || c.isDigit || c == '"' || c == '“' || c == '‘' || c == '(' || c == '[' ||
      c == '«' || c == '\''

  /** Conservative rule-based sentence splitting inside one paragraph.
    *
    * A boundary needs terminal punctuation (plus any closing quotes/brackets), whitespace, and a
    * following sentence-initial character; splits never occur inside unclosed brackets, after known
    * abbreviations or initials, or before a lowercase continuation such as
    * `"Go home!" she shouted`.
    */
  def sentenceSpans(text: String, para: TextSpan): Vector[TextSpan] =
    val out = Vector.newBuilder[TextSpan]
    var i = para.start
    val end = para.endExclusive
    var depth = 0
    var start = -1
    while i < end do
      val c = text.charAt(i)
      if start < 0 && !c.isWhitespace then start = i
      if OpenersCurly.contains(c) then depth += 1
      else if ClosersCurly.contains(c) then depth = math.max(0, depth - 1)
      if Terminal.contains(c) then
        // consume the run of terminal punctuation (e.g. "..." or "?!")
        var k = i
        while k + 1 < end && Terminal.contains(text.charAt(k + 1)) do k += 1
        val abbrev = c == '.' && k == i && isAbbreviation(text, i)
        // consume closing quotes / brackets
        var m = k
        while m + 1 < end && Closers.contains(text.charAt(m + 1)) do
          m += 1
          if ClosersCurly.contains(text.charAt(m)) then depth = math.max(0, depth - 1)
        // look ahead past whitespace
        var q = m + 1
        while q < end && text.charAt(q).isWhitespace do q += 1
        val boundary =
          !abbrev && depth == 0 && (q >= end || (q > m + 1 && startsSentence(text.charAt(q))))
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

  private def isWordChar(c: Char): Boolean =
    c.isLetterOrDigit || Character.getType(c) == Character.NON_SPACING_MARK ||
      Character.getType(c) == Character.COMBINING_SPACING_MARK || c == '_'

  /** Tokens: maximal letter/digit runs (allowing internal apostrophes and hyphens between letters),
    * every other non-whitespace character as its own token.
    */
  def tokenSpans(text: String, within: TextSpan): Vector[TextSpan] =
    val out = Vector.newBuilder[TextSpan]
    var i = within.start
    val end = within.endExclusive
    while i < end do
      val c = text.charAt(i)
      if c.isWhitespace then i += 1
      else if isWordChar(c) then
        val start = i
        var cont = true
        while cont && i < end do
          val d = text.charAt(i)
          if isWordChar(d) then i += 1
          else if (d == '\'' || d == '’' || d == '-') && i + 1 < end && isWordChar(
              text.charAt(i + 1)
            ) && i > start
          then i += 1
          else cont = false
        out += TextSpan.unsafe(start, i)
      else
        out += TextSpan.unsafe(i, i + 1)
        i += 1
    out.result()
