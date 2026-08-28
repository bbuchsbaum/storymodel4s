package storymodel4s.amr.penman

import cats.parse.{Parser => P, Parser0 => P0}

/** cats-parse PENMAN reader.
  *
  * Bare symbols in target position are resolved after parsing: a symbol naming a variable defined
  * anywhere in the tree is a `VarRef`; a symbol shaped like a variable (`[a-z]{1,2}[0-9]*`) that is
  * not defined is an `UndefinedVariable` error in strict mode and a `Sym` literal otherwise; any
  * other symbol (`-`, `+`, `imperative`, ...) is a `Sym` literal.
  */
object PenmanParser:
  final case class Options(strictVariables: Boolean)
  object Options:
    val default: Options = Options(strictVariables = true)
    val lenient: Options = Options(strictVariables = false)

  /** Conventional variables: one to three lowercase letters (corpora use `i`, `ii`, `iii` for
    * repeated pronouns) and an optional numeric suffix. Longer bare symbols (`imperative`,
    * `expressive`) are literals.
    */
  private val VariableShape = "^[a-z]{1,3}[0-9]*$".r
  private val NumberShape = "^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$".r

  /** Nesting bound: deeper input is a typed error, never a stack overflow. */
  val MaxDepth: Int = 512

  /** Alignment indices are bounded so a marker can never make the parser throw. */
  val MaxAlignmentDigits: Int = 9

  /** True when `s` is spelled like a PENMAN number literal. */
  def isNumber(s: String): Boolean = NumberShape.matches(s)

  /** True when `s` is spelled like a conventional PENMAN variable. */
  def looksLikeVariable(s: String): Boolean = VariableShape.matches(s)

  // ---- raw AST with offsets -------------------------------------------------------------

  private enum RawTarget:
    case Node(node: RawNode)
    case Atom(offset: Int, text: String, alignment: Option[AlignmentMarker])
    case Str(value: String, alignment: Option[AlignmentMarker])

  private final case class RawBranch(role: RoleToken, target: RawTarget)
  private final case class RawNode(
      offset: Int,
      variable: Variable,
      concept: Option[ConceptToken],
      branches: Vector[RawBranch]
  )

  // ---- lexical --------------------------------------------------------------------------

  private val ws: P0[Unit] = P.charsWhile0(_.isWhitespace).void
  private val symbolChar: P[Char] =
    P.charWhere(c => !c.isWhitespace && !"()/:~\"".contains(c))
  private val symbol: P[String] = symbolChar.rep.string

  private val alignment: P[AlignmentMarker] =
    val prefix = (P.charsWhile(_.isLetter) <* P.char('.')).backtrack
    val index =
      P.charsWhile(_.isDigit).filter(_.length <= MaxAlignmentDigits).map(_.toInt)
    (P.char('~') *> prefix.? ~ index.repSep(P.char(','))).map { case (pre, idx) =>
      AlignmentMarker(pre, idx.toList.toVector)
    }

  private val stringLit: P[String] =
    val escaped = P.char('\\') *> P.anyChar.map {
      case 'n'   => '\n'
      case 't'   => '\t'
      case other => other
    }
    val plain = P.charWhere(c => c != '"' && c != '\\')
    P.char('"') *> escaped.orElse(plain).rep0.map(_.mkString) <* P.char('"')

  private val roleTok: P[RoleToken] =
    (P.char(':') *> symbol ~ alignment.?).map { case (t, a) => RoleToken(t, a) }

  private val conceptTok: P[ConceptToken] =
    (symbol ~ alignment.?).map { case (t, a) => ConceptToken(t, a) }

  private lazy val node: P[RawNode] = P.defer {
    val open = P.index.with1 <* P.char('(') <* ws
    val conceptOrNone = (ws.with1 *> P.char('/') *> ws *> conceptTok).backtrack.?
    ((open ~ symbol) ~ conceptOrNone ~ branch.backtrack.rep0 <* ws <* P.char(')')).map {
      case (((off, v), c), bs) => RawNode(off, Variable(v), c, bs.toVector)
    }
  }

  private lazy val branch: P[RawBranch] = P.defer(
    ((ws.with1 *> roleTok) ~ (ws *> target)).map { case (r, t) => RawBranch(r, t) }
  )

  private lazy val target: P[RawTarget] = P.defer(
    node.map(RawTarget.Node(_)) |
      (stringLit ~ alignment.?).map { case (s, a) => RawTarget.Str(s, a) } |
      (P.index.with1 ~ symbol ~ alignment.?).map { case ((o, s), a) => RawTarget.Atom(o, s, a) }
  )

  private val restOfLine: P0[String] = P.charsWhile0(c => c != '\n' && c != '\r')

  /** Split a metadata header into `::key value` parts without regex lookaround (Scala Native has
    * none). A `::` starts a new pair only when it follows whitespace and is immediately followed by
    * a key-initial character (letter or underscore), so `# ::snt a :: b` and `2::1` keep the `::`
    * inside the value.
    */
  private def metadataParts(line: String): Vector[String] =
    def startsKey(i: Int): Boolean =
      (i == 0 || line.charAt(i - 1).isWhitespace) && i + 2 < line.length && {
        val c = line.charAt(i + 2)
        c.isLetter || c == '_'
      }
    val result = Vector.newBuilder[String]
    var start = 0
    var i = 2
    while i < line.length do
      if line.startsWith("::", i) && startsKey(i) then
        result += line.substring(start, i)
        start = i
        i += 2
      else i += 1
    result += line.substring(start)
    result.result().map(_.trim).filter(_.nonEmpty)

  private val headerLine: P[Vector[Header]] =
    (P.char('#') *> restOfLine).map { raw =>
      val line = raw.trim
      if line.startsWith("::") then
        metadataParts(line).map { p =>
          val body = p.drop(2)
          val i = body.indexWhere(_.isWhitespace)
          if i < 0 then Header.Meta(body, "") else Header.Meta(body.take(i), body.drop(i).trim)
        }
      else Vector(Header.Comment(line))
    }

  private val headers: P0[Vector[Header]] =
    (ws.with1.soft *> headerLine).backtrack.rep0.map(_.toVector.flatten)

  private val document: P0[(Vector[Header], RawNode)] =
    (headers ~ (ws.with1 *> node) <* ws <* P.end).map { case (h, n) => (h, n) }

  // ---- resolution -----------------------------------------------------------------------

  private def resolve(
      raw: RawNode,
      defined: Set[String],
      opts: Options
  ): Either[PenmanError, PenmanNode] =
    val branches =
      raw.branches.foldLeft[Either[PenmanError, Vector[Branch]]](Right(Vector.empty)) {
        case (Left(e), _)                       => Left(e)
        case (Right(acc), RawBranch(role, tgt)) =>
          val target: Either[PenmanError, Target] = tgt match
            case RawTarget.Node(n)       => resolve(n, defined, opts).map(Target.NodeTarget(_))
            case RawTarget.Str(s, a)     => Right(Target.LiteralTarget(PenmanLiteral.Str(s), a))
            case RawTarget.Atom(o, s, a) =>
              if defined.contains(s) then Right(Target.VarRef(Variable(s), a))
              else if isNumber(s) then Right(Target.LiteralTarget(PenmanLiteral.Num(s), a))
              else if opts.strictVariables && looksLikeVariable(s) then
                Left(PenmanError.UndefinedVariable(Variable(s), o))
              else Right(Target.LiteralTarget(PenmanLiteral.Sym(s), a))
          target.map(t => acc :+ Branch(role, t))
      }
    branches.map(bs => PenmanNode(raw.variable, raw.concept, bs))

  private def definedIn(raw: RawNode): Vector[(Variable, Int)] =
    (raw.variable, raw.offset) +: raw.branches.flatMap {
      case RawBranch(_, RawTarget.Node(n)) => definedIn(n)
      case _                               => Vector.empty
    }

  private def checkParens(input: String): Either[PenmanError, Unit] =
    var depth = 0
    var i = 0
    var inStr = false
    var esc = false
    var inComment = false
    var atLineStart = true
    var err: Option[PenmanError] = None
    while i < input.length && err.isEmpty do
      val c = input.charAt(i)
      if inComment then
        if c == '\n' then
          inComment = false
          atLineStart = true
      else if inStr then
        if esc then esc = false
        else if c == '\\' then esc = true
        else if c == '"' then inStr = false
      else
        c match
          case '#' if atLineStart => inComment = true
          case '"'                => inStr = true
          case '('                =>
            depth += 1
            if depth > MaxDepth then err = Some(PenmanError.TooDeep(i, MaxDepth))
          case ')' =>
            depth -= 1
            if depth < 0 then err = Some(PenmanError.UnbalancedParentheses(i))
          case _ => ()
        if c == '\n' then atLineStart = true
        else if !c.isWhitespace then atLineStart = false
      i += 1
    err.toLeft(()).flatMap { _ =>
      if depth != 0 then Left(PenmanError.UnbalancedParentheses(input.length)) else Right(())
    }

  /** Parse one PENMAN document. */
  def parse(input: String, opts: Options = Options.default): Either[PenmanError, PenmanTree] =
    if input.trim.isEmpty then Left(PenmanError.Empty)
    else
      for
        _ <- checkParens(input)
        parsed <- document.parseAll(input).left.map { e =>
          val expected = e.expected.head.toString
          PenmanError.Syntax(e.failedAtOffset, expected.take(80))
        }
        (hdrs, raw) = parsed
        defs = definedIn(raw)
        _ <- defs
          .groupBy(_._1)
          .toVector
          .sortBy(_._2.map(_._2).min)
          .collectFirst { case (v, occ) if occ.size > 1 => (v, occ.map(_._2).sorted.apply(1)) }
          .map((v, o) => PenmanError.DuplicateVariable(v, o))
          .toLeft(())
        root <- resolve(raw, defs.map(_._1.name).toSet, opts)
      yield PenmanTree(hdrs, root)

  /** Parse several documents separated by blank lines. */
  def parseMany(
      input: String,
      opts: Options = Options.default
  ): Either[PenmanError, Vector[PenmanTree]] =
    val blocks = input.split("\\n\\s*\\n").toVector.map(_.trim).filter(_.nonEmpty)
    blocks.foldLeft[Either[PenmanError, Vector[PenmanTree]]](Right(Vector.empty)) { (acc, b) =>
      acc.flatMap(v => parse(b, opts).map(v :+ _))
    }
