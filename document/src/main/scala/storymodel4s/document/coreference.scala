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

/** Why a non-introducing mention names no entity. Seven states that must not share a mark:
  *
  *   - no referent precedes a third-person pronoun; several do and nothing in the text chooses;
  *   - a first- or second-person pronoun sits in a held frame (speech, belief, desire, intention,
  *     memory, imagination) whose holder the entity layer could not name as one cluster;
  *   - a first-person plural pronoun sits in a frame whose holder is named: its referent is a group
  *     that includes the holder, which is neither the holder nor any single entity;
  *   - a second-person pronoun sits in a frame whose holder is named: its referent is the
  *     addressee, whom the model does not represent;
  *   - a first- or second-person pronoun sits in no held frame at all (a narrator's "I", a reader's
  *     "you"), which is a fact about the discourse the model has no vocabulary for;
  *   - the mention's form is not one the rule reads.
  */
enum OpenReference:
  case NoAntecedent
  case SeveralAntecedents
  case NeedsSpeechHolder(person: Person)
  case SpeakerGroup
  case NeedsAddressee
  case OutsideSpeech(person: Person)
  case UnrecognizedForm(label: String)

  def render: String = this match
    case NoAntecedent            => "no-antecedent"
    case SeveralAntecedents      => "several-antecedents"
    case NeedsSpeechHolder(p)    => s"needs-speech-holder:${MentionForm.lower(p.toString)}"
    case SpeakerGroup            => "speaker-group"
    case NeedsAddressee          => "needs-addressee"
    case OutsideSpeech(p)        => s"outside-speech:${MentionForm.lower(p.toString)}"
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
  *   - a **first-person singular pronoun** refers to the holder of the innermost held frame it sits
  *     in (the speaker of a quotation, the thinker of a belief), under `speech-holder/v1`, when the
  *     frame's offered holder nodes all lie in exactly one cluster of the first pass (introducing
  *     mentions and resolved third-person pronouns); a first-person plural refers to a group that
  *     includes the holder and is open as `SpeakerGroup` with the holder recorded as its candidate;
  *     a second-person pronoun refers to the addressee, whom the model does not represent, and is
  *     open as `NeedsAddressee` with the holder as candidate; either person in a frame whose holder
  *     is not one cluster is open as `NeedsSpeechHolder`, and in no held frame at all as
  *     `OutsideSpeech`;
  *   - any other form is open as unrecognized.
  *
  * An open mention mints no entity. That is the truthful consequence: a participant edge whose
  * filler is an open pronoun becomes a recorded gap naming the candidates, not an edge to a merged
  * or an invented referent.
  */
object EntityIdentity:
  val IntroducingLabelRule: RuleId = RuleId.unsafe("introducing-label/v1")
  val UniqueAntecedentRule: RuleId = RuleId.unsafe("pronoun-unique-antecedent/v1")
  val SpeechHolderRule: RuleId = RuleId.unsafe("speech-holder/v1")

  /** `holderOf` gives, for a mention, the holder candidate of the innermost held frame the
    * mention's situation sits in (the chart nodes offered as its holder, or the gap that says why
    * none was), or nothing when that situation is in no held frame. The compiler reads it off the
    * accepted context placements; a caller without contexts passes nothing, and every first- or
    * second-person pronoun is then `OutsideSpeech`.
    */
  def resolve(
      story: StoryId,
      table: MentionTable[EntityK],
      forms: MentionForms,
      labelOf: MentionId[EntityK] => (String, EntityType),
      numberOf: ChartNodeRef => Number,
      holderOf: MentionId[EntityK] => Option[HolderCandidate] = _ => None
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
      val groupOf: Map[CanonicalId[EntityK], Vector[MentionId[EntityK]]] =
        groups.map(g => partition.canonical(g.head) -> g).toMap
      def numberOfCluster(canonical: CanonicalId[EntityK]): Number =
        groupOf(canonical).headOption.flatMap(table.node).map(numberOf).getOrElse(Number.Unknown)
      val mentionAt: Map[ChartNodeRef, MentionId[EntityK]] =
        table.mentions.flatMap(m => table.node(m).map(_ -> m)).toMap
      // Pass one: introducing mentions and third-person pronouns, which need no holder. A
      // third-person pronoun with exactly one number-compatible preceding cluster names it.
      def antecedentOf(m: MentionId[EntityK], number: Number): Option[CanonicalId[EntityK]] =
        forms
          .resolvableAt(m, partition)
          .filter(c => ChartNumber.compatible(number, numberOfCluster(c))) match
          case Vector(one) => Some(one)
          case _           => None
      val passOne: Map[MentionId[EntityK], CanonicalId[EntityK]] = forms.mentions.flatMap { m =>
        forms.formOf(m) match
          case Some(f) if f.isIntroducing => Some(m -> partition.canonical(m))
          case Some(MentionForm.Pronominal(Person.Third, number)) =>
            antecedentOf(m, number).map(m -> _)
          case _ => None
      }.toMap
      // Pass two reads holders off pass one, so a speaker that is itself a resolved pronoun ("he
      // said") still names the frame's holder. The holder is one cluster or it is nothing.
      def holderClusterOf(m: MentionId[EntityK]): Either[OpenReference, CanonicalId[EntityK]] =
        holderOf(m) match
          case None                             => Left(OpenReference.OutsideSpeech(personOf(m)))
          case Some(HolderCandidate.Missing(_)) =>
            Left(OpenReference.NeedsSpeechHolder(personOf(m)))
          case Some(HolderCandidate.Fillers(refs)) =>
            val clusters = refs.toVector.map(ref => mentionAt.get(ref).flatMap(passOne.get))
            if clusters.exists(_.isEmpty) then Left(OpenReference.NeedsSpeechHolder(personOf(m)))
            else
              clusters.flatten.distinct match
                case Vector(one) => Right(one)
                case _           => Left(OpenReference.NeedsSpeechHolder(personOf(m)))
      def personOf(m: MentionId[EntityK]): Person =
        forms.formOf(m) match
          case Some(MentionForm.Pronominal(person, _)) => person
          case _                                       => Person.Third
      val decisions: Map[MentionId[EntityK], MentionIdentity] = forms.mentions.map { m =>
        forms.formOf(m) match
          case Some(f) if f.isIntroducing => m -> MentionIdentity.Introducing
          case Some(MentionForm.Pronominal(Person.Third, number)) =>
            val candidates = forms
              .resolvableAt(m, partition)
              .filter(c => ChartNumber.compatible(number, numberOfCluster(c)))
            candidates match
              case Vector(_) =>
                m -> MentionIdentity.Resolved(UniqueAntecedentRule)
              case Vector() =>
                m -> MentionIdentity.Open(OpenReference.NoAntecedent, Vector.empty)
              case several =>
                m -> MentionIdentity.Open(
                  OpenReference.SeveralAntecedents,
                  several.map(c => groupOf(c).head).sorted
                )
          case Some(MentionForm.Pronominal(person, number)) =>
            (person, number, holderClusterOf(m)) match
              case (_, _, Left(reason)) => m -> MentionIdentity.Open(reason, Vector.empty)
              case (Person.First, Number.Plural, Right(holder)) =>
                m -> MentionIdentity.Open(OpenReference.SpeakerGroup, Vector(groupOf(holder).head))
              case (Person.First, _, Right(_)) =>
                m -> MentionIdentity.Resolved(SpeechHolderRule)
              case (_, _, Right(holder)) =>
                m -> MentionIdentity.Open(
                  OpenReference.NeedsAddressee,
                  Vector(groupOf(holder).head)
                )
          case Some(MentionForm.Other(label)) =>
            m -> MentionIdentity.Open(OpenReference.UnrecognizedForm(label), Vector.empty)
          case Some(other) =>
            m -> MentionIdentity.Open(OpenReference.UnrecognizedForm(other.render), Vector.empty)
          case None =>
            m -> MentionIdentity.Open(OpenReference.UnrecognizedForm("missing"), Vector.empty)
      }.toMap
      // Attach each resolved pronoun to its one cluster: the unique antecedent, or the holder.
      val attached: Map[CanonicalId[EntityK], Vector[MentionId[EntityK]]] = forms.mentions
        .flatMap { m =>
          decisions(m) match
            case MentionIdentity.Resolved(rule) if rule == SpeechHolderRule =>
              holderClusterOf(m).toOption.map(_ -> m)
            case MentionIdentity.Resolved(_) => passOne.get(m).map(_ -> m)
            case _                           => None
        }
        .groupMap(_._1)(_._2)
      val finalClusters = groups.map { g =>
        val canonical = partition.canonical(g.head)
        val members = (g ++ attached.getOrElse(canonical, Vector.empty))
          .sortBy(m => (forms.positionOf(m), m))
        IdentityCluster(NonEmptyVector.fromVectorUnsafe(members))
      }
      new EntityIdentityResult(finalClusters, decisions)
