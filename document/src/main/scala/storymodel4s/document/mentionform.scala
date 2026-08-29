package storymodel4s.document

import cats.{Order, Show}
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.EntityK
import storymodel4s.proposition.{
  Checked,
  ConceptId,
  ConceptKind,
  ConceptTarget,
  PropositionChart,
  SourceRole
}

/** Definiteness of a nominal mention, read from determiner evidence in the surface text. */
enum Definiteness:
  case Definite, Indefinite, Demonstrative, Unknown

/** Grammatical person of a pronominal mention. */
enum Person:
  case First, Second, Third

/** Grammatical number of a pronominal mention. */
enum Number:
  case Singular, Plural, Unknown

/** The referring form of an entity mention.
  *
  * Why: reader-time coreference and the entity lens need to know whether a mention introduces a
  * referent by name, describes it nominally, or merely points back to it. A pronoun carries no
  * introducing power, so it may resolve only to referents already introduced at or before its
  * discourse position (see [[MentionForms.resolvableAt]]); a name or nominal may introduce one.
  */
enum MentionForm:
  case Name
  case Nominal(definite: Definiteness)
  case Pronominal(person: Person, number: Number)
  case Other(label: String)

  def isPronominal: Boolean = this match
    case Pronominal(_, _) => true
    case _                => false

  /** A form that can introduce a referent (name or nominal); pronouns and `Other` cannot. */
  def isIntroducing: Boolean = this match
    case Name | Nominal(_) => true
    case _                 => false

  /** Canonical rendering; `MentionForm.parse` inverts it. `Other` labels are rendered verbatim
    * after the tag, so a label may contain any character including `:`.
    */
  def render: String = this match
    case Name             => "name"
    case Nominal(d)       => s"nominal:${MentionForm.lower(d.toString)}"
    case Pronominal(p, n) =>
      s"pronominal:${MentionForm.lower(p.toString)}:${MentionForm.lower(n.toString)}"
    case Other(label) => s"other:$label"

object MentionForm:
  private[document] def lower(s: String): String = TextNorm.lower(s)

  private def definiteness(s: String): Option[Definiteness] =
    Definiteness.values.find(d => lower(d.toString) == s)
  private def person(s: String): Option[Person] = Person.values.find(p => lower(p.toString) == s)
  private def number(s: String): Option[Number] = Number.values.find(n => lower(n.toString) == s)

  def parse(s: String): Either[DomainError, MentionForm] =
    val bad: DomainError = DomainError.InvalidFormat("MentionForm", s, "unrecognized rendering")
    if s == "name" then Right(Name)
    else if s.startsWith("other:") then Right(Other(s.drop("other:".length)))
    else if s.startsWith("nominal:") then
      definiteness(s.drop("nominal:".length)).map(Nominal(_)).toRight(bad)
    else if s.startsWith("pronominal:") then
      s.drop("pronominal:".length).split(":", -1).toList match
        case p :: n :: Nil =>
          (person(p), number(n)) match
            case (Some(pp), Some(nn)) => Right(Pronominal(pp, nn))
            case _                    => Left(bad)
        case _ => Left(bad)
    else Left(bad)

  given Show[MentionForm] = Show.show(_.render)
  given Order[MentionForm] = Order.by(_.render)
  given Ordering[MentionForm] = Order[MentionForm].toOrdering

/** Deterministic, conservative inference of a mention's form from its chart concept and, when
  * available, the aligned surface text. No frame lexicon is consulted (roadmap §3, Part 3 item 8).
  */
object MentionFormInference:
  /** Pronoun lemmas (lowercase) with person and number; possessives included. */
  val pronouns: Map[String, (Person, Number)] =
    val first1 = Vector("i", "me", "my", "mine", "myself").map(_ -> (Person.First, Number.Singular))
    val first2 =
      Vector("we", "us", "our", "ours", "ourselves").map(_ -> (Person.First, Number.Plural))
    val second =
      Vector("you", "your", "yours", "yourself").map(_ -> (Person.Second, Number.Unknown)) :+
        ("yourselves" -> (Person.Second, Number.Plural))
    val third1 = Vector(
      "he",
      "him",
      "his",
      "himself",
      "she",
      "her",
      "hers",
      "herself",
      "it",
      "its",
      "itself"
    ).map(_ -> (Person.Third, Number.Singular))
    val third2 =
      Vector("they", "them", "their", "theirs", "themselves").map(
        _ -> (Person.Third, Number.Plural)
      )
    (first1 ++ first2 ++ second ++ third1 ++ third2).toMap

  private val definite = Set("the")
  private val indefinite = Set("a", "an")
  private val demonstrative = Set("this", "that", "these", "those")
  private val possessive = Set("my", "our", "your", "his", "her", "its", "their")

  /** First word of a surface span, lowercased by code point; empty when there is none. */
  private def firstWord(surface: String): String =
    val t = surface.trim
    val end = t.indexWhere(c => !Character.isLetter(c))
    MentionForm.lower(if end < 0 then t else t.take(end))

  /** Definiteness from determiner evidence in the aligned surface text. */
  def definitenessOf(surface: Option[String]): Definiteness =
    surface.map(firstWord) match
      case Some(w) if definite(w) || possessive(w) => Definiteness.Definite
      case Some(w) if indefinite(w)                => Definiteness.Indefinite
      case Some(w) if demonstrative(w)             => Definiteness.Demonstrative
      case _                                       => Definiteness.Unknown

  /** Whether `concept` carries a `:name` relation to a `Name` concept (AMR
    * `person :name (name …)`).
    */
  private def hasNameRelation(chart: PropositionChart[Checked], concept: ConceptId): Boolean =
    chart.relationsFrom(concept).exists { r =>
      r.role.source == SourceRole.Named("name") && (r.to match
        case ConceptTarget.Node(id) => chart.concept(id).exists(_.kind == ConceptKind.Name)
        case _                      => false)
    }

  /** Infer the form of the mention at `concept` in `chart`.
    *
    *   - a `Name` concept, or a concept naming itself through a `:name` relation → `Name`;
    *   - a pronoun lemma → `Pronominal(person, number)`;
    *   - any other entity-like concept → `Nominal(definiteness from surface)`;
    *   - a concept that is not in the chart → `Other("missing")`.
    */
  def fromChart(
      chart: PropositionChart[Checked],
      concept: ConceptId,
      surface: Option[String]
  ): MentionForm =
    chart.concept(concept) match
      case None    => MentionForm.Other("missing")
      case Some(c) =>
        val lemma = MentionForm.lower(c.lemma.value)
        if c.kind == ConceptKind.Name || hasNameRelation(chart, concept) then MentionForm.Name
        else
          pronouns.get(lemma) match
            case Some((p, n)) => MentionForm.Pronominal(p, n)
            case None         => MentionForm.Nominal(definitenessOf(surface))

/** Discourse position of a mention: rank of its sentence in the mention graph's sentence order,
  * then its concept id. The tie-break within a sentence is concept-id order because charts do not
  * carry token offsets for concepts; two mentions in one sentence are therefore ordered
  * deterministically but not by surface position.
  */
final case class MentionPosition(sentenceRank: Int, concept: ConceptId)

object MentionPosition:
  given Order[MentionPosition] = Order.by(p => (p.sentenceRank, p.concept))
  given Ordering[MentionPosition] = Order[MentionPosition].toOrdering

/** Referring forms for the entity mentions of one mention table, with reader-time accessors.
  *
  * Every mention in the table has exactly one form (validated at construction), so `formOf` is
  * total over the table. Positions come from the mention graph's sentence order (see
  * [[MentionPosition]]); `MentionForms.of` fails if a mention's node is absent from the graph.
  */
final class MentionForms private (
    val forms: Map[MentionId[EntityK], MentionForm],
    val positions: Map[MentionId[EntityK], MentionPosition]
):
  def formOf(m: MentionId[EntityK]): Option[MentionForm] = forms.get(m)
  def positionOf(m: MentionId[EntityK]): Option[MentionPosition] = positions.get(m)
  def mentions: Vector[MentionId[EntityK]] = forms.keys.toVector.sorted

  def mentionsByForm(form: MentionForm): Vector[MentionId[EntityK]] =
    forms.collect { case (m, f) if f == form => m }.toVector.sorted

  def namedMentions: Vector[MentionId[EntityK]] = mentionsByForm(MentionForm.Name)
  def pronominalMentions: Vector[MentionId[EntityK]] =
    forms.collect { case (m, f) if f.isPronominal => m }.toVector.sorted
  def introducingMentions: Vector[MentionId[EntityK]] =
    forms.collect { case (m, f) if f.isIntroducing => m }.toVector.sorted

  override def equals(other: Any): Boolean = other match
    case that: MentionForms => forms == that.forms && positions == that.positions
    case _                  => false

  override def hashCode(): Int = (forms, positions).##

  override def toString: String = s"MentionForms(mentions=${forms.size})"

  /** Mentions in discourse order (position, then id). */
  def inDiscourseOrder: Vector[MentionId[EntityK]] =
    positions.toVector.sortBy { case (m, p) => (p, m) }.map(_._1)

  /** The discourse-first `Name` mention of a cluster, if any (position, then id tie-break). */
  def firstNamedMention(cluster: ExactCorefCluster[EntityK]): Option[MentionId[EntityK]] =
    firstOfForm(cluster, _ == MentionForm.Name)

  /** The discourse-first introducing (name or nominal) mention of a cluster, if any. */
  def firstIntroducingMention(cluster: ExactCorefCluster[EntityK]): Option[MentionId[EntityK]] =
    firstOfForm(cluster, _.isIntroducing)

  private def firstOfForm(
      cluster: ExactCorefCluster[EntityK],
      pred: MentionForm => Boolean
  ): Option[MentionId[EntityK]] =
    cluster.mentions.toSortedSet.toVector
      .flatMap(m => for f <- forms.get(m) if pred(f); p <- positions.get(m) yield (p, m))
      .sorted
      .headOption
      .map(_._2)

  /** Reader-time resolution targets for `mention`: the canonical ids of clusters whose first
    * introducing (non-pronominal) mention is at or before `mention`'s position. Pronouns therefore
    * never resolve forward to a referent the reader has not yet met; no future evidence leaks. An
    * unknown mention yields no targets.
    */
  def resolvableAt(
      mention: MentionId[EntityK],
      partition: CorefPartition[EntityK]
  ): Vector[CanonicalId[EntityK]] =
    positions.get(mention) match
      case None       => Vector.empty
      case Some(here) =>
        partition.clusters.flatMap { c =>
          firstIntroducingMention(c).flatMap(positions.get).collect {
            case p if Order[MentionPosition].lteqv(p, here) => c.canonical
          }
        }.sorted

object MentionForms:
  /** Build from explicit forms; every table mention needs a form and every form a table mention.
    */
  def of(
      table: MentionTable[EntityK],
      forms: Map[MentionId[EntityK], MentionForm],
      graph: MentionGraph
  ): Either[DocumentError, MentionForms] =
    val missingForm = table.mentions.find(m => !forms.contains(m))
    val unknown = forms.keys.toVector.sorted.find(m => !table.contains(m))
    (missingForm, unknown) match
      case (Some(m), _) => Left(DocumentError.UnknownMention(m.value, "MentionForms.form"))
      case (_, Some(m)) => Left(DocumentError.UnknownMention(m.value, "MentionForms.table"))
      case _            =>
        val rank = graph.sentences.zipWithIndex.toMap
        val positioned =
          table.mentions.foldLeft[Either[DocumentError, Map[MentionId[EntityK], MentionPosition]]](
            Right(Map.empty)
          ) {
            case (Left(e), _)    => Left(e)
            case (Right(acc), m) =>
              val node = table.node(m).get
              rank.get(node.sentence) match
                case None    => Left(DocumentError.MentionNotInGraph(m.value, node, "MentionForms"))
                case Some(r) => Right(acc.updated(m, MentionPosition(r, node.concept)))
          }
        positioned.map(new MentionForms(forms, _))

  /** Infer every mention's form from its chart concept and optional aligned surface text. */
  def infer(
      table: MentionTable[EntityK],
      graph: MentionGraph,
      surfaceOf: ChartNodeRef => Option[String]
  ): Either[DocumentError, MentionForms] =
    val forms = table.mentions.flatMap { m =>
      table.node(m).flatMap { node =>
        graph.chart(node.sentence).map { chart =>
          m -> MentionFormInference.fromChart(chart, node.concept, surfaceOf(node))
        }
      }
    }.toMap
    of(table, forms, graph)
