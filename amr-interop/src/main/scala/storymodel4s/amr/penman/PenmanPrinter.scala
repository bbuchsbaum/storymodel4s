package storymodel4s.amr.penman

/** Deterministic PENMAN writer. Layout follows the common one-branch-per-line convention; the
  * output re-parses to an equal tree.
  */
object PenmanPrinter:
  final case class Options(indent: Int, compact: Boolean)
  object Options:
    val default: Options = Options(indent = 3, compact = false)
    val oneLine: Options = Options(indent = 0, compact = true)

  def print(tree: PenmanTree, opts: Options = Options.default): String =
    val sb = new StringBuilder
    tree.headers.foreach {
      case Header.Meta(k, v) => sb.append("# ::").append(k).append(' ').append(v).append('\n')
      case Header.Comment(t) => sb.append("# ").append(t).append('\n')
    }
    printNode(tree.root, 0, sb, opts)
    sb.toString

  private def mark(a: Option[AlignmentMarker]): String = a.map(_.render).getOrElse("")

  private def printNode(n: PenmanNode, depth: Int, sb: StringBuilder, opts: Options): Unit =
    sb.append('(').append(n.variable.name)
    n.concept.foreach(c => sb.append(" / ").append(c.text).append(mark(c.alignment)))
    val pad = " " * (depth * opts.indent + opts.indent)
    n.branches.foreach { case Branch(role, target) =>
      if opts.compact then sb.append(' ') else sb.append('\n').append(pad)
      sb.append(':').append(role.text).append(mark(role.alignment)).append(' ')
      target match
        case Target.NodeTarget(child)     => printNode(child, depth + 1, sb, opts)
        case Target.VarRef(v, a)          => sb.append(v.name).append(mark(a))
        case Target.LiteralTarget(lit, a) => sb.append(lit.render).append(mark(a))
    }
    sb.append(')')
    ()
