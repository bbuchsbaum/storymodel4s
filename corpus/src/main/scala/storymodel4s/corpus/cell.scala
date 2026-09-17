package storymodel4s.corpus

import scala.util.control.NonFatal

/** How a column's literals are to be read. Declared by the profile, never inferred.
  *
  * Inference is the defect this type exists to remove. An Excel serial such as `1.000983796296296`
  * parses perfectly well as a decimal, and `6.10` is a valid decimal and a valid `min.sec`; a
  * reader that guesses is right on one corpus and silently wrong on the next.
  */
enum CellEncoding:
  /** Digits only. `'35'` reads as 35; `'35.0'` does not read at all. */
  case IntegerText

  /** An integer that the source may have written as a whole decimal.
    *
    * Measured need: every Friends `WhichStoryline` code appears as both `'1'` and `1.0` in one
    * column, and `WhichEvent` carries 60 string cells among 20,677 numeric ones. Both render the
    * same code, and a reader that admits one and refuses the other splits one code into two.
    */
  case IntegerOrWholeDecimalText

  /** A finite decimal, read exactly. */
  case DecimalText

  /** Excel serial days converted to whole seconds, from a DECLARED day origin.
    *
    * The origin is a parameter because two corpora disagree about it, which is exactly the kind of
    * thing a second corpus is supposed to break. Measured:
    *   - Friends `friendsStoryBoard!FriendsNarrComb.Time` starts at 1.0, so seconds are
    *     `(v - 1) x 86400`: 1.000983796296296 is 85 s.
    *   - Memento `MementoStoryBoard!storyBoard.Time` starts at 0, so seconds are `v x 86400`:
    *     1.9560185185185184E-3 is 169 s.
    * Applying either rule to the other corpus gives nonsense (-86,231 s and 86,485 s respectively),
    * so a single hardcoded origin is wrong for one of them whichever is chosen.
    */
  case ExcelSerialDays(originDay: Int)

  /** `minutes.seconds` written as a decimal: `6.31` means 6:31, i.e. 391 seconds.
    *
    * Measured need: the Film Festival annotations write run-relative times this way, and they
    * arrive as dirty floats. The value is formatted to two decimals and SPLIT -- never treated as a
    * number, which would make 6.31 into 6.31 seconds and 6.5 into a different time than 6.50.
    */
  case MinuteDotSecond

  /** Text carried through unchanged apart from trimming. */
  case PlainText

  /** Design contract rule 2: unknown ontologies enter by an explicit case, never a raw string. */
  case Custom(namespace: String, label: String)

  def render: String = this match
    case IntegerText               => "integer"
    case IntegerOrWholeDecimalText => "integer-or-whole-decimal"
    case DecimalText               => "decimal"
    case ExcelSerialDays(origin)   => s"excel-serial-days@$origin"
    case MinuteDotSecond           => "minute-dot-second"
    case PlainText                 => "text"
    case Custom(ns, label)         => s"$ns:$label"

/** Why a cell could not be read. Every case carries the coordinate, so a refusal is a location. */
enum CellRefusal:
  /** The profile declares no encoding for this column, so the reader refuses rather than guesses.
    *
    * Unconditional, and deliberately not "only when two encodings would fit": a column whose cells
    * all happen to parse under one encoding is exactly the case where guessing looks safe and is
    * not. An earlier design refused only on ambiguity, which left every single-fit column silently
    * inferred.
    */
  case NoDeclaredEncoding(at: SourceCoordinate, column: String)
  case NotAnInteger(at: SourceCoordinate, literal: String)
  case NotFinite(at: SourceCoordinate, literal: String)
  case OutOfRange(at: SourceCoordinate, literal: String, lo: Long, hi: Long)
  case Malformed(at: SourceCoordinate, literal: String, encoding: CellEncoding, reason: String)

  /** Named `coordinate` rather than `at`, because every case already has an `at` field and a
    * same-named method on the enum would shadow it.
    */
  def coordinate: SourceCoordinate = this match
    case NoDeclaredEncoding(a, _) => a
    case NotAnInteger(a, _)       => a
    case NotFinite(a, _)          => a
    case OutOfRange(a, _, _, _)   => a
    case Malformed(a, _, _, _)    => a

  def message: String = this match
    case NoDeclaredEncoding(a, c) => s"$a: no encoding declared for column '$c'"
    case NotAnInteger(a, l)       => s"$a: '$l' is not an integer"
    case NotFinite(a, l)          => s"$a: '$l' is not a finite number"
    case OutOfRange(a, l, lo, hi) => s"$a: '$l' is outside [$lo, $hi]"
    case Malformed(a, l, e, r)    => s"$a: '$l' is not ${e.render}: $r"

/** Reads one source cell under a declared encoding.
  *
  * Every entry point takes the declared encoding as an `Option`, and `None` refuses. The option is
  * the profile's answer for that column, so "the profile did not say" is a value the reader must
  * handle rather than a case it can forget.
  *
  * A blank cell yields `None` rather than a refusal: absence is a legitimate observation, and
  * distinguishing it from an unreadable value is the whole point of the surrounding types.
  */
object Cell:

  private def blank(literal: String): Boolean = literal.trim.isEmpty

  /** Whole seconds under a time-shaped encoding, or `None` when the cell is blank. */
  private[corpus] def seconds(
      at: SourceCoordinate,
      column: String,
      literal: String,
      declared: Option[CellEncoding]
  ): Either[CellRefusal, Option[Raw[Long]]] =
    declared match
      case None      => Left(CellRefusal.NoDeclaredEncoding(at, column))
      case Some(enc) =>
        if blank(literal) then Right(None)
        else
          enc match
            case CellEncoding.ExcelSerialDays(origin) =>
              excelSeconds(at, literal, origin).map(Some(_))
            case CellEncoding.MinuteDotSecond => minuteDotSecond(at, literal).map(Some(_))
            case CellEncoding.IntegerText | CellEncoding.IntegerOrWholeDecimalText =>
              integerUnder(at, literal, enc).map(Some(_))
            case other =>
              Left(
                CellRefusal.Malformed(at, literal, other, "not a time-shaped encoding")
              )

  /** An integer, or `None` when the cell is blank. */
  private[corpus] def integer(
      at: SourceCoordinate,
      column: String,
      literal: String,
      declared: Option[CellEncoding]
  ): Either[CellRefusal, Option[Raw[Long]]] =
    declared match
      case None      => Left(CellRefusal.NoDeclaredEncoding(at, column))
      case Some(enc) =>
        if blank(literal) then Right(None)
        else
          enc match
            case CellEncoding.IntegerText | CellEncoding.IntegerOrWholeDecimalText =>
              integerUnder(at, literal, enc).map(Some(_))
            case other =>
              Left(CellRefusal.Malformed(at, literal, other, "not an integer encoding"))

  /** Text, or `None` when the cell is blank. */
  private[corpus] def text(
      at: SourceCoordinate,
      column: String,
      literal: String,
      declared: Option[CellEncoding]
  ): Either[CellRefusal, Option[Raw[String]]] =
    declared match
      case None                         => Left(CellRefusal.NoDeclaredEncoding(at, column))
      case Some(CellEncoding.PlainText) =>
        if blank(literal) then Right(None)
        else Right(Some(Raw.of(literal.trim, at, literal)))
      case Some(other) =>
        Left(CellRefusal.Malformed(at, literal, other, "not a text encoding"))

  private def integerUnder(
      at: SourceCoordinate,
      literal: String,
      enc: CellEncoding
  ): Either[CellRefusal, Raw[Long]] =
    val t = literal.trim
    try
      val d = BigDecimal(t)
      enc match
        case CellEncoding.IntegerText =>
          if t.contains('.') then Left(CellRefusal.NotAnInteger(at, literal))
          else Right(Raw.of(d.toLongExact, at, literal))
        case _ =>
          if d.isWhole then Right(Raw.of(d.toLongExact, at, literal))
          else Left(CellRefusal.NotAnInteger(at, literal))
    catch case NonFatal(_) => Left(CellRefusal.NotAnInteger(at, literal))

  /** `(serial - originDay) * 86400`, rounded to the nearest second. */
  private def excelSeconds(
      at: SourceCoordinate,
      literal: String,
      originDay: Int
  ): Either[CellRefusal, Raw[Long]] =
    try
      val d = BigDecimal(literal.trim)
      val secs =
        ((d - originDay) * 86400).setScale(0, BigDecimal.RoundingMode.HALF_UP).toLongExact
      if secs < 0 then
        Left(
          CellRefusal.Malformed(
            at,
            literal,
            CellEncoding.ExcelSerialDays(originDay),
            s"before day $originDay"
          )
        )
      else Right(Raw.of(secs, at, literal))
    catch case NonFatal(_) => Left(CellRefusal.NotFinite(at, literal))

  /** Formatted to two decimals and split; never read as a number. */
  private def minuteDotSecond(
      at: SourceCoordinate,
      literal: String
  ): Either[CellRefusal, Raw[Long]] =
    try
      val d = BigDecimal(literal.trim)
      if d < 0 then
        Left(CellRefusal.Malformed(at, literal, CellEncoding.MinuteDotSecond, "negative"))
      else
        val fixed = d.setScale(2, BigDecimal.RoundingMode.HALF_UP)
        val minutes = fixed.toBigInt.toLong
        val secs = ((fixed - BigDecimal(minutes)) * 100)
          .setScale(0, BigDecimal.RoundingMode.HALF_UP)
          .toLongExact
        if secs >= 60 then
          Left(
            CellRefusal.Malformed(
              at,
              literal,
              CellEncoding.MinuteDotSecond,
              s"$secs is not a second of a minute"
            )
          )
        else Right(Raw.of(minutes * 60 + secs, at, literal))
    catch case NonFatal(_) => Left(CellRefusal.NotFinite(at, literal))

  /** The canonical rendering of a cell under its declared encoding, or `None` when blank.
    *
    * This is the contract `Applicability` depends on (ADR 0018 §2): a condition compares a column's
    * NORMALIZED value, so two literals that mean one code must render identically. Measured need:
    * Friends `WhichEvent` carries 60 string cells among 20,677 numeric ones and `Detail` carries 8,
    * so `'35'` and `35.0` must both render `35` or a condition would hold on some rows and not
    * others for no reason in the data.
    */
  private[corpus] def normalize(
      at: SourceCoordinate,
      column: String,
      literal: String,
      declared: Option[CellEncoding]
  ): Either[CellRefusal, Option[String]] =
    declared match
      case None      => Left(CellRefusal.NoDeclaredEncoding(at, column))
      case Some(enc) =>
        if blank(literal) then Right(None)
        else
          enc match
            case CellEncoding.PlainText => Right(Some(literal.trim))
            case CellEncoding.IntegerText | CellEncoding.IntegerOrWholeDecimalText =>
              integerUnder(at, literal, enc).map(r => Some(r.value.toString))
            case CellEncoding.ExcelSerialDays(origin) =>
              excelSeconds(at, literal, origin).map(r => Some(r.value.toString))
            case CellEncoding.MinuteDotSecond =>
              minuteDotSecond(at, literal).map(r => Some(r.value.toString))
            case CellEncoding.DecimalText =>
              try Right(Some(BigDecimal(literal.trim).underlying.stripTrailingZeros.toPlainString))
              catch case NonFatal(_) => Left(CellRefusal.NotFinite(at, literal))
            case c @ CellEncoding.Custom(_, _) =>
              Left(CellRefusal.Malformed(at, literal, c, "custom encodings declare no rendering"))
