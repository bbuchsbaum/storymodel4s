package storymodel4s.document

import cats.data.NonEmptyVector
import storymodel4s.core.*
import storymodel4s.story.*

/** Why the narrative contests one utterance (ADR 0026 decision 3). Deception is something the
  * narrative establishes about an utterance, not a place where content holds, so it is a basis here
  * rather than a context kind.
  */
enum ContestationBasis:
  /** The narrative reveals that the speaker lied. */
  case Deception

  /** The speaker takes the assertion back. */
  case Retraction

  /** Another source corrects it. */
  case Correction

/** A recall-blind record that the narrative contests one speech-scoped situation. Why a checked
  * type: a contestation blocks promotion, so it must cite evidence like any accepted claim (rule 3).
  */
final class Contestation private (
    val target: SituationId,
    val basis: ContestationBasis,
    val meta: ClaimMeta
):
  override def equals(other: Any): Boolean = other match
    case that: Contestation => target == that.target && basis == that.basis && meta == that.meta
    case _                  => false
  override def hashCode(): Int = (target, basis, meta).##
  override def toString: String = s"Contestation(${target.value}, $basis)"

object Contestation:
  /** Refuses a record whose evidence cites no span, anchor or upstream claim. */
  def of(
      target: SituationId,
      basis: ContestationBasis,
      meta: ClaimMeta
  ): Either[PromotionError, Contestation] =
    val cited = meta.evidence.exists(e => e.hasSpans || e.anchors.nonEmpty || e.upstream.nonEmpty)
    if cited then Right(new Contestation(target, basis, meta))
    else Left(PromotionError.UncitedContestation(target))

/** The recall-blind contestations of one model (ADR 0026 §3). Why a checked set: a record is valid
  * only against a model, where its target must be a speech-scoped situation, and the set records the
  * model it was checked against, so a set built for one model cannot vouch for another. An empty set
  * states that its producer found none; it is bound to its model all the same.
  */
final class ContestationSet private (
    val model: Checksum,
    val records: Vector[Contestation],
    val digest: Checksum
):
  override def equals(other: Any): Boolean = other match
    case that: ContestationSet => digest == that.digest
    case _                     => false
  override def hashCode(): Int = digest.##
  override def toString: String = s"ContestationSet(${records.size} records)"

object ContestationSet:
  def of(
      model: StoryModel[ModelStatus.Validated],
      records: Vector[Contestation]
  ): Either[PromotionError, ContestationSet] =
    val g = model.graph
    def speechScoped(id: SituationId) = g.situations
      .get(id)
      .flatMap(s => g.contexts.get(s.context))
      .exists(_.kind match
        case ContextKind.Speech(_) => true
        case _                     => false)
    records.find(c => !g.situations.contains(c.target)) match
      case Some(c) => Left(PromotionError.UnknownContestationTarget(c.target))
      case None    =>
        records.find(c => !speechScoped(c.target)) match
          case Some(c) => Left(PromotionError.ContestationNotSpeech(c.target))
          case None    =>
            val m = SpeechPromotion.modelDigest(model)
            Right(new ContestationSet(m, records, digestOf(m, records)))

  // Every field is hashed before joining and every list is length-prefixed, as in
  // `ReferentContext.digest`; order is by content so the caller's order does not matter.
  private def digestOf(model: Checksum, contestations: Vector[Contestation]): Checksum =
    val entries = contestations
      .map { c =>
        val ev = c.meta.evidence.toVector.flatMap { e =>
          Vector(
            e.id.value,
            e.spans.fold("")(TypedSupport.Text(_).identity.hex),
            e.anchors.fold("")(TypedSupport.Anchored(_).identity.hex),
            e.upstream.size.toString
          ) ++ e.upstream.toVector.sorted.map(_.value) ++ Vector(e.extractor.value, e.stage.value)
        }
        Vector(c.target.value, c.basis.toString, c.meta.id.value, c.meta.status.toString) ++
          Vector(c.meta.evidence.length.toString) ++ ev
      }
      .map(_.map(f => Checksum.ofText(f).hex))
      .sortBy(ContentAddress.digest(_).hex)
    val header =
      Vector("contestations/v1", model.hex, entries.length.toString).map(Checksum.ofText(_).hex)
    ContentAddress.digest(header ++ entries.flatMap(e => Checksum.ofText(e.length.toString).hex +: e))

/** Why a speech assertion was not promoted. Each condition of ADR 0026 §2 has its own case, so a
  * refusal names the condition that failed.
  */
enum PromotionError:
  case UnknownSituation(id: SituationId)
  case NotSpeech(id: SituationId)
  case Embedded(id: SituationId, enclosing: String)
  case NotAsserted(id: SituationId, modality: Modality, polarity: Polarity)
  case Unbound(entity: EntityId)
  case UnknownBinding(entity: EntityId)
  case BindingOutsideUtterance(entity: EntityId, mention: ReferentMentionId, reason: String)
  case OpenReferent(mention: ReferentMentionId)
  case UnknownContestationTarget(target: SituationId)
  case ContestationNotSpeech(target: SituationId)
  case ForeignContestations
  case UncitedContestation(target: SituationId)
  case Contested(id: SituationId, reason: String)

  def message: String = this match
    case UnknownSituation(id) => s"no situation ${id.value} in the model"
    case NotSpeech(id)        => s"${id.value} is not in a speech context"
    case Embedded(id, e)      => s"the speech of ${id.value} is embedded in $e"
    case NotAsserted(id, m, p) =>
      s"${id.value} is not an asserted claim (modality $m, polarity $p)"
    case Unbound(e)        => s"participant ${e.value} is not bound to a referent mention"
    case UnknownBinding(e) => s"${e.value} is bound but is not a participant"
    case BindingOutsideUtterance(e, m, why) =>
      s"mention ${m.value} cannot denote participant ${e.value}: $why"
    case OpenReferent(m)              => s"the identity of mention ${m.value} is open"
    case UnknownContestationTarget(t) => s"a contestation targets ${t.value}, absent from the model"
    case ContestationNotSpeech(t) => s"a contestation targets ${t.value}, which is not speech"
    case ForeignContestations     => "the contestation set was checked against another model"
    case UncitedContestation(t)   => s"the contestation of ${t.value} cites no evidence"
    case Contested(id, why)           => s"${id.value} is contested: $why"

/** A speech assertion that the rule `uncontested-assertion/v1` adds to the narrated world (ADR 0026).
  *
  * Why an overlay and not a situation in the model: a promoted claim stays out of `StoryModel`, its
  * graph and its claim ledger, so no graph consumer can read it as depicted or surface-explicit.
  * Analyses include promotions by reading them and exclude them by not reading them. Construction
  * is private to [[SpeechPromotion.of]], which checks every condition against the artifacts given.
  */
final class SpeechPromotion private (
    val story: StoryId,
    val source: Checksum,
    val model: Checksum,
    val twin: SituationId,
    val speech: ContextId,
    val predicate: Predicate,
    val polarity: Polarity,
    val participants: Vector[(ParticipantRole, EntityId, ReferentMentionId)],
    val referents: Checksum,
    val contestations: Checksum,
    val meta: ClaimMeta
):
  /** The rule that licensed this promotion. */
  def rule: String = SpeechPromotion.Rule

  /** Whether `m` is the model this promotion was checked against. Why: `source` covers only the
    * atlas, and two graphs over one source can differ in a rival negation; consumers holding a
    * model check it before reading the promotion.
    */
  def holdsIn(m: StoryModel[?]): Boolean = SpeechPromotion.modelDigest(m) == model

  override def equals(other: Any): Boolean = other match
    case that: SpeechPromotion => meta.id == that.meta.id && meta == that.meta
    case _                     => false
  override def hashCode(): Int = meta.id.##
  override def toString: String = s"SpeechPromotion(${twin.value} -> NarratedWorld, $rule)"

object SpeechPromotion:
  val Rule: String = "uncontested-assertion/v1"

  /** Promotes `twin` when its speech is unembedded, its content is asserted, every participant is
    * bound to a settled dialogue mention of the utterance, and nothing contests it (ADR 0026 §2).
    *
    * `bindings` names, per participant entity, the referent mention that denotes it. `contestations`
    * is the recall-blind set built for this model; it enters the promotion's identity.
    */
  def of(
      model: StoryModel[ModelStatus.Validated],
      twin: SituationId,
      bindings: Map[EntityId, ReferentMentionId],
      resolution: ReferentResolution,
      contestations: ContestationSet
  ): Either[PromotionError, SpeechPromotion] =
    val g = model.graph
    val digest = modelDigest(model)
    for
      _ <- Either.cond(contestations.model == digest, (), PromotionError.ForeignContestations)
      s <- g.situations.get(twin).toRight(PromotionError.UnknownSituation(twin))
      _ <- unembedded(g, s)
      _ <- asserted(s)
      participants <- bound(g, s, bindings, resolution)
      _ <- uncontested(model, s, contestations.records)
    yield
      val referents = resolution.context.digest
      val contested = contestations.digest
      val identity = ContentAddress.digest(
        (Vector(
          Rule,
          model.storyId.value,
          model.sourceChecksum.hex,
          digest.hex,
          s.id.value,
          s.meta.id.value,
          s.context.value,
          s.predicate.lemma,
          s.predicate.frame.getOrElse(""),
          s.polarity.toString,
          participants.length.toString
        ) ++ participants.flatMap((r, e, m) => Vector(r.toString, e.value, m.value)) ++
          Vector(referents.hex, contested.hex)).map(f => Checksum.ofText(f).hex)
      )
      val evidence = Evidence(
        EvidenceId.unsafe(s"promotion:${identity.hex}"),
        None,
        Set(s.meta.id),
        Fingerprint.unsafe(Rule),
        StageId.unsafe("speech-promotion"),
        None
      )
      val meta = ClaimMeta.unsafe(
        ClaimId.unsafe(s"promotion:${identity.hex}"),
        EpistemicStatus.WorldKnowledgeInferred,
        Credence.unmeasured,
        NonEmptyVector.one(evidence),
        Provenance.deterministic(Rule, Checksum.ofText(Rule))
      )
      new SpeechPromotion(
        model.storyId,
        model.sourceChecksum,
        digest,
        s.id,
        s.context,
        s.predicate,
        s.polarity,
        participants,
        referents,
        contested,
        meta
      )

  // 1. The twin's frame is Speech, directly under the narrated-world root.
  private def unembedded(g: NarrativeGraph, s: SituationNode): Either[PromotionError, Unit] =
    val frame = g.contexts.get(s.context)
    frame.map(_.kind) match
      case Some(ContextKind.Speech(_)) =>
        frame.flatMap(_.parent).flatMap(g.contexts.get) match
          case Some(p) if p.kind == ContextKind.NarratedWorld && p.parent.isEmpty => Right(())
          case Some(p) => Left(PromotionError.Embedded(s.id, p.kind.label))
          case None    => Left(PromotionError.Embedded(s.id, "no narrated-world root"))
      case _ => Left(PromotionError.NotSpeech(s.id))

  // 2. "She is dead", not "she might be dead"; a polarity nobody determined promotes nothing.
  private def asserted(s: SituationNode): Either[PromotionError, Unit] =
    val modal = s.modality == Modality.Asserted || s.modality == Modality.Reported
    if modal && s.polarity != Polarity.Unknown then Right(())
    else Left(PromotionError.NotAsserted(s.id, s.modality, s.polarity))

  // 3 and 4. Every participant is bound to a settled dialogue mention of this utterance.
  private def bound(
      g: NarrativeGraph,
      s: SituationNode,
      bindings: Map[EntityId, ReferentMentionId],
      resolution: ReferentResolution
  ): Either[PromotionError, Vector[(ParticipantRole, EntityId, ReferentMentionId)]] =
    val roles = g.participantsOf.getOrElse(s.id, Vector.empty).distinct.sortBy((r, e) =>
      (e, r.toString)
    )
    val entities = roles.map(_._2).toSet
    bindings.keys.toVector.sorted.find(!entities.contains(_)) match
      case Some(extra) => Left(PromotionError.UnknownBinding(extra))
      case None        =>
        roles.foldLeft[Either[PromotionError, Vector[(ParticipantRole, EntityId, ReferentMentionId)]]](
          Right(Vector.empty)
        ) { case (acc, (role, entity)) =>
          for
            done <- acc
            id <- bindings.get(entity).toRight(PromotionError.Unbound(entity))
            m <- denotes(g, s, entity, id, resolution)
          yield done :+ (role, entity, m)
        }

  private def denotes(
      g: NarrativeGraph,
      s: SituationNode,
      entity: EntityId,
      id: ReferentMentionId,
      resolution: ReferentResolution
  ): Either[PromotionError, ReferentMentionId] =
    def outside(why: String) = Left(PromotionError.BindingOutsideUtterance(entity, id, why))
    resolution.context.mention(id) match
      case Left(_)                                                 => outside("not in the referent context")
      case Right(m) if m.origin != MentionOrigin.Dialogue(s.context) =>
        outside("not a dialogue mention of this speech context")
      case Right(m) if !overlap(m.support, s.support) => outside("outside the utterance")
      case Right(m) if !g.entities.get(entity).exists(e => overlap(m.support, e.support)) =>
        outside("outside the entity's own support")
      case Right(m) =>
        resolution.relation(m.id, m.id) match
          case Right(ReferentRelation.Same) => Right(m.id)
          case _                            => Left(PromotionError.OpenReferent(m.id))

  // Text supports compare by span; anchored supports by playback extent, which carries its bundle.
  // A text span carries no document identity, so text overlap trusts that both sides index the
  // model's own text (ADR 0026, known limits).
  private def overlap(a: TypedSupport, b: TypedSupport): Boolean = (a, b) match
    case (TypedSupport.Text(x), TypedSupport.Text(y)) =>
      x.spans.exists(p => y.spans.exists(p.overlaps))
    case (TypedSupport.Anchored(_), TypedSupport.Anchored(_)) =>
      val ys = ReferentExtents.of(b)
      ReferentExtents.of(a).exists(x => ys.exists(x.overlaps))
    case _ => false

  // 5. Nothing in the source contests the claim (ADR 0026 §3).
  private def uncontested(
      model: StoryModel[ModelStatus.Validated],
      s: SituationNode,
      contestations: Vector[Contestation]
  ): Either[PromotionError, Unit] =
    val g = model.graph
    def roleSet(id: SituationId) = g.participantsOf.getOrElse(id, Vector.empty).toSet
    def sameContent(t: SituationNode) =
      t.id != s.id && t.predicate.lemma == s.predicate.lemma && roleSet(t.id) == roleSet(s.id)
    val opposite = if s.polarity == Polarity.Positive then Polarity.Negative else Polarity.Positive
    def kindOf(t: SituationNode) = g.contexts.get(t.context).map(_.kind)
    val others = g.situations.values.toVector.sortBy(_.id).filter(sameContent)
    // An undetermined polarity at the root also contests: the narrated world leaves it open.
    val rootRival = others.find(t =>
      (t.polarity == opposite || t.polarity == Polarity.Unknown) &&
        kindOf(t).contains(ContextKind.NarratedWorld)
    )
    val speechRival = others.find(t =>
      t.polarity == opposite && kindOf(t).exists {
        case ContextKind.Speech(_) => true
        case _                     => false
      }
    )
    val open = model.hypotheses.find(h =>
      h.subject == s.id || g.situations.get(h.subject).exists(sameContent)
    )
    val marked = contestations.find(_.target == s.id)
    (rootRival, speechRival, open, marked) match
      case (Some(t), _, _, _) =>
        Left(PromotionError.Contested(s.id, s"the narrated world holds ${t.id.value}"))
      case (_, Some(t), _, _) =>
        Left(PromotionError.Contested(s.id, s"speech ${t.id.value} asserts the opposite"))
      case (_, _, Some(h), _) =>
        Left(PromotionError.Contested(s.id, s"hypothesis on ${h.subject.value} keeps it open"))
      case (_, _, _, Some(c)) =>
        Left(PromotionError.Contested(s.id, s"${c.basis} recorded against it"))
      case _ => Right(())

  /** Digest of everything in a model that the rule reads: contexts, situations, participants, entity
    * supports and hypotheses, with the source identity. Why not `sourceChecksum`: it covers the atlas
    * alone. Fields are hashed before joining and lists are length-prefixed (rule 10).
    */
  private[document] def modelDigest(m: StoryModel[?]): Checksum =
    val g = m.graph
    def list[A](xs: Vector[A])(f: A => Vector[String]): Vector[String] =
      xs.length.toString +: xs.flatMap(f)
    val contexts = list(g.contexts.values.toVector.sortBy(_.id)) { c =>
      Vector(c.id.value, c.parent.fold("")(_.value), c.kind.label, c.support.identity.hex)
    }
    val situations = list(g.situations.values.toVector.sortBy(_.id)) { s =>
      Vector(
        s.id.value,
        s.meta.id.value,
        s.context.value,
        s.predicate.lemma,
        s.predicate.frame.getOrElse(""),
        s.polarity.toString,
        s.modality.toString,
        s.support.identity.hex
      ) ++ list(g.participantsOf.getOrElse(s.id, Vector.empty).distinct.sortBy((r, e) =>
        (e, r.toString)
      ))((r, e) => Vector(r.toString, e.value))
    }
    val entities = list(g.entities.values.toVector.sortBy(_.id)) { e =>
      Vector(e.id.value, e.support.identity.hex)
    }
    val hypotheses = list(m.hypotheses.sortBy(h => (h.subject, h.meta.id))) { h =>
      Vector(h.subject.value, h.meta.id.value)
    }
    val fields = Vector("promotion-model/v1", m.storyId.value, m.sourceChecksum.hex) ++ contexts ++
      situations ++ entities ++ hypotheses
    ContentAddress.digest(fields.map(f => Checksum.ofText(f).hex))
