package storymodel4s.proposition

/** Sub-chart extraction. */
object ChartFragment:
  /** The sub-chart reachable from `root` by following outgoing relations and embedded content up to
    * `depth` hops. Relations, polarity, embeddings, and alignments are restricted to the kept
    * concepts, so a fragment of a checked chart is itself checked. `None` when `root` is absent.
    */
  def rooted[C <: CheckState](
      chart: PropositionChart[C],
      root: ConceptId,
      depth: Int
  ): Option[PropositionChart[C]] =
    if !chart.concepts.contains(root) then None
    else
      var frontier = Set(root)
      var kept = Set(root)
      var d = 0
      while d < depth && frontier.nonEmpty do
        val next = frontier.flatMap { id =>
          chart.relationsFrom(id).flatMap(_.to.nodeId) ++
            chart.embedded.collect { case e if e.container == id => e.content }
        } -- kept
        kept ++= next
        frontier = next
        d += 1
      val keepRel: PropositionRelation => Boolean =
        r => kept(r.from) && r.to.nodeId.forall(kept)
      val relations = chart.relations.filter(keepRel)
      val relationSet = relations.toSet
      Some(
        PropositionChart[C](
          Some(root),
          chart.concepts.filter((k, _) => kept(k)),
          relations,
          chart.polarity.filter((k, _) => kept(k)),
          chart.embedded.filter(e => kept(e.container) && kept(e.content)),
          chart.alignments.filter { a =>
            a.target match
              case AlignmentTarget.Concepts(ids) => ids.forall(kept)
              case AlignmentTarget.Relation(r)   => relationSet(r)
          },
          chart.provenance,
          chart.sentence
        )
      )

/** How concepts and roles are verbalized in a gloss. */
trait Lexicalizer:
  def concept(c: Concept): String
  def role(r: RoleAssignment): Option[String]

  /** Words the gloss may add that are not concept lemmas (negation, unknown placeholder). */
  def functionWords: Set[String] = Set("not", "?")

object Lexicalizer:
  /** Lemmas verbatim; named/normalized roles labeled, numbered roles unlabeled. */
  val identity: Lexicalizer = new Lexicalizer:
    def concept(c: Concept): String = if c.isUnknown then "?" else c.lemma.value
    def role(r: RoleAssignment): Option[String] =
      r.normalizedRole
        .map(_.toString.toLowerCase)
        .orElse(r.source match
          case SourceRole.Named(n) => Some(n)
          case _                   => None)

/** Conservative verbalization of a chart: every content word comes from a lemma or literal in the
  * chart, so a gloss can never introduce facts the chart does not contain.
  */
object Gloss:
  private def target[C <: CheckState](
      chart: PropositionChart[C],
      t: ConceptTarget,
      lex: Lexicalizer
  ) =
    t match
      case ConceptTarget.Node(id)     => lex.concept(chart.concepts(id))
      case ConceptTarget.Literal(lit) =>
        lit match
          case LiteralValue.Text(v)   => v
          case LiteralValue.Number(v) => v.toString
          case LiteralValue.Symbol(v) => v
      case ConceptTarget.Unknown => "?"

  /** Gloss one predicate: `[not] pred arg0 arg1 (role: filler)*`. */
  def predicate[C <: CheckState](
      chart: PropositionChart[C],
      id: ConceptId,
      lex: Lexicalizer = Lexicalizer.identity
  ): Option[String] =
    chart.concepts.get(id).map { c =>
      val neg = if chart.polarityOf(id) == Polarity.Negative then "not " else ""
      val rels = chart.relationsFrom(id).sortBy(r => r.role.source.render)
      val (numbered, other) = rels.partition(_.role.source match
        case SourceRole.Numbered(_) => true
        case _                      => false)
      val args = numbered.map(r => target(chart, r.to, lex))
      val mods = other.map { r =>
        val filler = target(chart, r.to, lex)
        lex.role(r.role).map(l => s"($l: $filler)").getOrElse(s"($filler)")
      }
      (Vector(neg + lex.concept(c)) ++ args ++ mods).mkString(" ")
    }

  /** Gloss the focus predicate if any, else every predicate in id order, else all concepts. */
  def conservative[C <: CheckState](
      chart: PropositionChart[C],
      lex: Lexicalizer = Lexicalizer.identity
  ): String =
    chart.focus.flatMap(f => predicate(chart, f, lex)).getOrElse {
      val preds = chart.predicates
      if preds.nonEmpty then preds.flatMap(p => predicate(chart, p, lex)).mkString("; ")
      else chart.conceptIds.map(id => lex.concept(chart.concepts(id))).mkString(" ")
    }
