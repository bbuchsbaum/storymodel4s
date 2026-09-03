package storymodel4s.document

import cats.data.{NonEmptySet, NonEmptyVector}
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.EntityK
import storymodel4s.proposition.{
  Checked,
  ConceptId,
  ConceptTarget,
  LiteralValue,
  PropositionChart,
  SourceRole
}
import storymodel4s.story.EntityType

/** Grammatical number of a mention, read from its chart: a `:quant` relation whose filler is a
  * number literal says how many; anything else is `Unknown`, which is compatible with everything.
  *
  * Why so little: AMR lemmas are singular and plural morphology does not survive parsing, so the
  * only number evidence a chart carries is an explicit quantity. Guessing beyond it would be a
  * number nobody measured.
  */
object ChartNumber:
  def of(chart: PropositionChart[Checked], concept: ConceptId): Number =
    chart
      .relationsFrom(concept)
      .collectFirst {
        case r if r.role.source == SourceRole.Named("quant") =>
          r.to match
            case ConceptTarget.Literal(LiteralValue.Number(n)) =>
              if n > 1 then Number.Plural else if n == 1 then Number.Singular else Number.Unknown
            case _ => Number.Unknown
      }
      .getOrElse(Number.Unknown)

  def compatible(a: Number, b: Number): Boolean =
    a == Number.Unknown || b == Number.Unknown || a == b

/** Why a non-introducing mention names no entity. Four states that must not share a mark: no
  * referent precedes the pronoun; several do and nothing in the text chooses; the pronoun is first
  * or second person, whose referent is the speaker or addressee of a speech frame rather than any
  * antecedent; the mention's form is not one the rule reads.
  */
enum OpenReference:
  case NoAntecedent
  case SeveralAntecedents
  case NeedsSpeechHolder(person: Person)
  case UnrecognizedForm(label: String)

  def render: String = this match
    case NoAntecedent            => "no-antecedent"
    case SeveralAntecedents      => "several-antecedents"
    case NeedsSpeechHolder(p)    => s"needs-speech-holder:${MentionForm.lower(p.toString)}"
    case UnrecognizedForm(label) => s"unrecognized-form:$label"

/** What the identity rule decided about one mention. */
enum MentionIdentity:
  /** A name or nominal: it introduces a referent and clusters with its exact label. */
  case Introducing

  /** A pronoun the rule tied to exactly one preceding referent. */
  case Resolved(rule: RuleId)

  /** A mention that names no entity, with the candidate referents it could have named, each given
    * by its cluster's first introducing mention, in mention-id order. The rule ranks nothing:
    * nearest-first would be the recency heuristic it declines to apply, so the set is the record.
    */
  case Open(reason: OpenReference, candidates: Vector[MentionId[EntityK]])

/** One entity's mentions after identity resolution, in discourse order. */
final case class IdentityCluster(members: NonEmptyVector[MentionId[EntityK]])

/** The result of identity resolution over a story's entity mentions. Every mention in the table has
  * exactly one identity; every cluster member is `Introducing` or `Resolved`; every `Open` mention
  * is in no cluster.
  */
final class EntityIdentityResult private[document] (
    val clusters: Vector[IdentityCluster],
    val identities: Map[MentionId[EntityK], MentionIdentity]
):
  def open: Vector[(MentionId[EntityK], OpenReference, Vector[MentionId[EntityK]])] =
    identities.toVector
      .collect { case (m, MentionIdentity.Open(reason, cs)) => (m, reason, cs) }
      .sortBy(_._1)

  def resolved: Vector[(MentionId[EntityK], RuleId)] =
    identities.toVector
      .collect { case (m, MentionIdentity.Resolved(rule)) => (m, rule) }
      .sortBy(_._1)

  override def toString: String =
    s"EntityIdentityResult(clusters=${clusters.size}, open=${open.size})"

/** Entity identity from referring form (ADR 0012).
  *
  * Until 2026-09-03 the compiler grouped every entity mention by its lowercased label and type, so
  * every `he` in a story was one entity and the man who went home was the man who died. Identity is
  * now decided by the mention's form:
  *
  *   - an **introducing** mention (a name, or a nominal) clusters with the other introducing
  *     mentions of exactly its folded label and type, under `introducing-label/v1`; a name is never
  *     merged with a pronoun by lemma, because a pronoun has no lemma of its own to merge on;
  *   - a **third-person pronoun** resolves under `pronoun-unique-antecedent/v1` when exactly one
  *     introducing cluster precedes it (no future evidence leaks: [[MentionForms.resolvableAt]])
  *     and is compatible with it in number; with none it is open with no antecedent, with several
  *     it is open with those candidates recorded as a set. Choosing the nearest would be an
  *     inference with an error rate nobody has measured here, so the alternatives are recorded and
  *     none is accepted (design contract 3, 7);
  *   - a **first- or second-person pronoun** refers to the speaker or addressee of the speech frame
  *     it sits in, which is a fact about the context and not about any antecedent; it is open until
  *     a rule reads the holder;
  *   - any other form is open as unrecognized.
  *
  * An open mention mints no entity. That is the truthful consequence: a participant edge whose
  * filler is an open pronoun becomes a recorded gap naming the candidates, not an edge to a merged
  * or an invented referent.
  */
object EntityIdentity:
  val IntroducingLabelRule: RuleId = RuleId.unsafe("introducing-label/v1")
  val UniqueAntecedentRule: RuleId = RuleId.unsafe("pronoun-unique-antecedent/v1")

  def resolve(
      story: StoryId,
      table: MentionTable[EntityK],
      forms: MentionForms,
      labelOf: MentionId[EntityK] => (String, EntityType),
      numberOf: ChartNodeRef => Number
  ): Either[DocumentError, EntityIdentityResult] =
    val introducing = forms.introducingMentions
    val groups: Vector[Vector[MentionId[EntityK]]] = introducing
      .groupBy { m =>
        val (label, entityType) = labelOf(m)
        (TextNorm.lower(label), entityType)
      }
      .values
      .toVector
      .map(_.sortBy(m => (forms.positionOf(m), m)))
      .sortBy(_.head)
    val clusters = groups.foldLeft[Either[DocumentError, Vector[ExactCorefCluster[EntityK]]]](
      Right(Vector.empty)
    ) { (acc, group) =>
      for
        built <- acc
        cluster <- ExactCorefCluster.of(
          story,
          NonEmptySet.of(group.head, group.tail*),
          table
        )
      yield built :+ cluster
    }
    for
      cs <- clusters
      partition <- CorefPartition.of(story, cs)
    yield
      val byCanonical = cs.map(c => c.canonical -> c).toMap
      val groupOf: Map[CanonicalId[EntityK], Vector[MentionId[EntityK]]] =
        groups.map(g => partition.canonical(g.head) -> g).toMap
      def numberOfCluster(canonical: CanonicalId[EntityK]): Number =
        groupOf(canonical).headOption.flatMap(table.node).map(numberOf).getOrElse(Number.Unknown)
      val decisions: Map[MentionId[EntityK], MentionIdentity] = forms.mentions.map { m =>
        forms.formOf(m) match
          case Some(f) if f.isIntroducing => m -> MentionIdentity.Introducing
          case Some(MentionForm.Pronominal(Person.Third, number)) =>
            val candidates = forms
              .resolvableAt(m, partition)
              .filter(c => ChartNumber.compatible(number, numberOfCluster(c)))
            candidates match
              case Vector(one) =>
                m -> MentionIdentity.Resolved(UniqueAntecedentRule)
              case Vector() =>
                m -> MentionIdentity.Open(OpenReference.NoAntecedent, Vector.empty)
              case several =>
                m -> MentionIdentity.Open(
                  OpenReference.SeveralAntecedents,
                  several.map(c => groupOf(c).head).sorted
                )
          case Some(MentionForm.Pronominal(person, _)) =>
            m -> MentionIdentity.Open(OpenReference.NeedsSpeechHolder(person), Vector.empty)
          case Some(MentionForm.Other(label)) =>
            m -> MentionIdentity.Open(OpenReference.UnrecognizedForm(label), Vector.empty)
          case Some(other) =>
            m -> MentionIdentity.Open(OpenReference.UnrecognizedForm(other.render), Vector.empty)
          case None =>
            m -> MentionIdentity.Open(OpenReference.UnrecognizedForm("missing"), Vector.empty)
      }.toMap
      // Attach each resolved pronoun to its one candidate's group.
      val attached: Map[CanonicalId[EntityK], Vector[MentionId[EntityK]]] = forms.mentions
        .flatMap { m =>
          decisions(m) match
            case MentionIdentity.Resolved(_) =>
              forms
                .formOf(m)
                .collect { case MentionForm.Pronominal(_, number) =>
                  forms
                    .resolvableAt(m, partition)
                    .filter(c => ChartNumber.compatible(number, numberOfCluster(c)))
                    .headOption
                    .map(_ -> m)
                }
                .flatten
            case _ => None
        }
        .groupMap(_._1)(_._2)
      val finalClusters = groups.map { g =>
        val canonical = partition.canonical(g.head)
        val members = (g ++ attached.getOrElse(canonical, Vector.empty))
          .sortBy(m => (forms.positionOf(m), m))
        IdentityCluster(NonEmptyVector.fromVectorUnsafe(members))
      }
      new EntityIdentityResult(finalClusters, decisions)
