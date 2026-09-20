package storymodel4s.fixtures.wog

import storymodel4s.core.*
import storymodel4s.story.*

/** Plain-language expectations for the difficult phenomena of *The War of the Ghosts* (design
  * record §27.1, §27.2, §52.3, §88.3), each backed by mechanically checkable structural facts. This
  * is data for the automatic-acquisition benchmark: an unattended build must reproduce these facts
  * from the text alone, and the hand fixture must satisfy every one of them.
  */
object WarOfTheGhostsExpectations:

  import WarOfTheGhostsModel.{E, S}

  /** One structural fact about a model, evaluable without reading test names. */
  enum Check:
    case IsEvent(s: SituationId)
    case IsState(s: SituationId)
    case InContext(s: SituationId, kind: ContextKind)
    case HasModality(s: SituationId, modality: Modality)
    case HasPolarity(s: SituationId, polarity: Polarity)
    case HasStatus(s: SituationId, status: EpistemicStatus)
    case Participant(s: SituationId, role: ParticipantRole, e: EntityId)
    case EntityDistinct(a: EntityId, b: EntityId)
    case EntityAttributeIn(e: EntityId, key: String, value: String, kind: ContextKind)
    case NoEntityAttributeAtRoot(e: EntityId, key: String, value: String)
    case Reference(from: SituationId, mode: NarrativeReference, to: SituationId)
    case NoReference(from: SituationId, to: SituationId)
    case CausalStatus(cause: SituationId, effect: SituationId, status: EpistemicStatus)
    case NoCausalWithStatus(effect: SituationId, forbidden: Set[EpistemicStatus])
    case Member(e: EntityId, group: EntityId)
    case DiscourseBefore(a: SituationId, b: SituationId)
    case WorldTimeReaches(a: SituationId, b: SituationId)
    case OnlyAssertedRootEventWithLemma(lemma: String, s: SituationId)
    case HasHypothesisWithAlternatives(s: SituationId)

  /** One phenomenon: which sentences carry it, the plain-language statement a reviewer reads, and
    * the checks that make it mechanical.
    */
  final case class Expectation(
      phenomenon: String,
      sentences: Vector[Int],
      statement: String,
      checks: Vector[Check]
  )

  import Check.*

  val all: Vector[Expectation] = Vector(
    Expectation(
      "two young men act together",
      Vector(1, 4, 6, 15, 19),
      "the pair is one group entity whose joint actions carry group participant edges; the two individuals are separate entities and members of the pair",
      Vector(
        EntityDistinct(E.ym1, E.ym2),
        Member(E.ym1, E.youngMen),
        Member(E.ym2, E.youngMen),
        Participant(S.huntSeals, ParticipantRole.Agent, E.youngMen),
        Participant(S.hide, ParticipantRole.Agent, E.youngMen)
      )
    ),
    Expectation(
      "one declines, one joins",
      Vector(15, 19, 20),
      "the refusal and the joining are separate narrated-world events with different agents",
      Vector(
        InContext(S.ym1Declines, ContextKind.NarratedWorld),
        InContext(S.ym2Accompanies, ContextKind.NarratedWorld),
        Participant(S.ym1Declines, ParticipantRole.Agent, E.ym1),
        Participant(S.ym2Accompanies, ParticipantRole.Agent, E.ym2)
      )
    ),
    Expectation(
      "warriors announce a raid",
      Vector(12),
      "the announced war is an intended future event inside the warriors' speech, not a narrated occurrence",
      Vector(
        InContext(S.announcedWar, ContextKind.Speech(ContextHolder.Named(E.warriors))),
        HasModality(S.announcedWar, Modality.Intended),
        Reference(S.announcedWar, NarrativeReference.Prospective, S.battle)
      )
    ),
    Expectation(
      "battle occurs later",
      Vector(26, 29),
      "exactly one asserted narrated-world fight; the announcement refers to it prospectively",
      Vector(
        OnlyAssertedRootEventWithLemma("fight", S.battle),
        InContext(S.battle, ContextKind.NarratedWorld),
        HasModality(S.battle, Modality.Asserted)
      )
    ),
    Expectation(
      "warriors say the man was hit",
      Vector(30, 32),
      "the injury is reported inside the warriors' speech; its narrated-world truth is an open hypothesis with both readings retained, never an explicit fact",
      Vector(
        InContext(S.reportedShot, ContextKind.Speech(ContextHolder.Named(E.warriors))),
        HasModality(S.reportedShot, Modality.Reported),
        InContext(S.ym2Injured, ContextKind.NarratedWorld),
        HasStatus(S.ym2Injured, EpistemicStatus.Hypothesized),
        HasHypothesisWithAlternatives(S.ym2Injured),
        Reference(S.reportedShot, NarrativeReference.Partial, S.ym2Injured)
      )
    ),
    Expectation(
      "man does not feel injured",
      Vector(32),
      "a negated narrated-world state that conflicts with, but does not erase, the report",
      Vector(
        IsState(S.notFeelSick),
        HasPolarity(S.notFeelSick, Polarity.Negative),
        InContext(S.notFeelSick, ContextKind.NarratedWorld),
        HasStatus(S.notFeelSick, EpistemicStatus.SurfaceExplicit)
      )
    ),
    Expectation(
      "man concludes they are ghosts",
      Vector(31),
      "'ghosts' is a belief-scoped attribute of the one warriors entity, not a narrated-world fact and not a second entity",
      Vector(
        InContext(S.warriorsAreGhosts, ContextKind.Belief(ContextHolder.Named(E.ym2))),
        Participant(S.warriorsAreGhosts, ParticipantRole.Theme, E.warriors),
        Participant(S.warriorsArePeople, ParticipantRole.Theme, E.warriors),
        EntityAttributeIn(
          E.warriors,
          "kind",
          "ghosts",
          ContextKind.Belief(ContextHolder.Named(E.ym2))
        ),
        NoEntityAttributeAtRoot(E.warriors, "kind", "ghosts")
      )
    ),
    Expectation(
      "man recounts the battle at home",
      Vector(38, 39, 40, 41),
      "the recounting is a narrated-world telling event whose content lives in his speech and refers back to the battle; no duplicate battle",
      Vector(
        InContext(S.recounting, ContextKind.NarratedWorld),
        Participant(S.recounting, ParticipantRole.Agent, E.ym2),
        InContext(S.weFought, ContextKind.Speech(ContextHolder.Named(E.ym2))),
        Reference(S.weFought, NarrativeReference.Retrospective, S.battle),
        Reference(S.recounting, NarrativeReference.Summary, S.battle),
        OnlyAssertedRootEventWithLemma("fight", S.battle)
      )
    ),
    Expectation(
      "man dies later",
      Vector(47, 49),
      "death is a narrated-world state",
      Vector(
        IsState(S.dead),
        InContext(S.dead, ContextKind.NarratedWorld),
        HasStatus(S.dead, EpistemicStatus.SurfaceExplicit)
      )
    ),
    Expectation(
      "death cause uncertain",
      Vector(30, 47),
      "no explicit or entailed cause of death is accepted; competing hypotheses remain, the wound hypothesis running from the open narrated-world injury",
      Vector(
        NoCausalWithStatus(
          S.dead,
          Set(
            EpistemicStatus.SurfaceExplicit,
            EpistemicStatus.LinguisticallyEntailed,
            EpistemicStatus.HumanAdjudicated
          )
        ),
        CausalStatus(S.ym2Injured, S.dead, EpistemicStatus.Hypothesized),
        CausalStatus(S.ym2Accompanies, S.dead, EpistemicStatus.Hypothesized)
      )
    ),
    Expectation(
      "discourse order diverges from world order at the recounting",
      Vector(26, 37, 39),
      "the retold fight appears after the return home in discourse but denotes the earlier battle, which precedes the return in narrated-world time",
      Vector(
        DiscourseBefore(S.ym2ToHouse, S.weFought),
        DiscourseBefore(S.battle, S.ym2ToHouse),
        WorldTimeReaches(S.battle, S.ym2ToHouse),
        Reference(S.weFought, NarrativeReference.Retrospective, S.battle),
        NoReference(S.battle, S.weFought)
      )
    ),
    Expectation(
      "nested report inside the retelling",
      Vector(41),
      "the warriors' words as retold are speech within speech; the retold injury refers to the original report, not to a new event",
      Vector(
        InContext(S.iWasShot, ContextKind.Speech(ContextHolder.Named(E.warriors))),
        HasModality(S.iWasShot, Modality.Reported),
        Reference(S.iWasShot, NarrativeReference.Retrospective, S.reportedShot)
      )
    )
  )

  /** Evaluate every check of every expectation against a model; empty result means all pass. */
  def evaluate(model: StoryModel[?]): Vector[(Expectation, Check, String)] =
    val g = model.graph
    val src = new StoryAlignmentSourceView(model)
    def ctxKind(s: SituationId): Option[ContextKind] =
      g.situations.get(s).flatMap(x => g.contexts.get(x.context)).map(_.kind)
    def fail(e: Expectation, c: Check, why: String) = (e, c, why)
    all.flatMap { e =>
      e.checks.flatMap { c =>
        val ok: Either[String, Unit] = c match
          case IsEvent(s) =>
            Either.cond(g.situations.get(s).exists(_.isEvent), (), s"${s.value} is not an event")
          case IsState(s) =>
            Either.cond(g.situations.get(s).exists(_.isState), (), s"${s.value} is not a state")
          case InContext(s, kind) =>
            Either.cond(ctxKind(s).contains(kind), (), s"${s.value} context is ${ctxKind(s)}")
          case HasModality(s, m) =>
            Either.cond(g.situations.get(s).exists(_.modality == m), (), s"${s.value} modality")
          case HasPolarity(s, p) =>
            Either.cond(g.situations.get(s).exists(_.polarity == p), (), s"${s.value} polarity")
          case HasStatus(s, st) =>
            Either.cond(g.situations.get(s).exists(_.meta.status == st), (), s"${s.value} status")
          case Participant(s, r, ent) =>
            Either.cond(
              g.participantsOf.getOrElse(s, Vector.empty).contains((r, ent)),
              (),
              s"${s.value} lacks ${r.render} ${ent.value}"
            )
          case EntityDistinct(a, b) =>
            Either.cond(
              a != b && g.entities.contains(a) && g.entities.contains(b),
              (),
              "same entity"
            )
          case EntityAttributeIn(ent, k, v, kind) =>
            Either.cond(
              g.entities
                .get(ent)
                .exists(
                  _.attributes.exists(a =>
                    a.key == k && a.value == v && g.contexts.get(a.context).exists(_.kind == kind)
                  )
                ),
              (),
              s"${ent.value} has no $k=$v in $kind"
            )
          case NoEntityAttributeAtRoot(ent, k, v) =>
            Either.cond(
              !g.entities
                .get(ent)
                .exists(
                  _.attributes
                    .exists(a => a.key == k && a.value == v && g.rootContext.contains(a.context))
                ),
              (),
              s"${ent.value} asserts $k=$v at the root"
            )
          case Reference(from, mode, to) =>
            Either.cond(
              g.referencesOut
                .getOrElse(from, Vector.empty)
                .exists(r => r.mode == mode && r.to == to),
              (),
              s"no $mode reference ${from.value} -> ${to.value}"
            )
          case NoReference(from, to) =>
            Either.cond(
              !g.referencesOut.getOrElse(from, Vector.empty).exists(_.to == to),
              (),
              s"unexpected reference ${from.value} -> ${to.value}"
            )
          case CausalStatus(cause, effect, st) =>
            Either.cond(
              g.causalIn
                .getOrElse(effect, Vector.empty)
                .exists(c => c.cause == cause && c.meta.status == st),
              (),
              s"no $st causal ${cause.value} -> ${effect.value}"
            )
          case NoCausalWithStatus(effect, forbidden) =>
            val bad = g.causalIn
              .getOrElse(effect, Vector.empty)
              .filter(c => forbidden.contains(c.meta.status))
            Either.cond(
              bad.isEmpty,
              (),
              s"${bad.size} causal edges into ${effect.value} with forbidden status"
            )
          case Member(ent, group) =>
            Either.cond(
              g.groupsOf(ent).contains(group),
              (),
              s"${ent.value} not a member of ${group.value}"
            )
          case DiscourseBefore(a, b) =>
            Either.cond(
              (for x <- model.discoursePosition.get(a); y <- model.discoursePosition.get(b)
              yield x < y).getOrElse(false),
              (),
              s"${a.value} not before ${b.value} in discourse"
            )
          case WorldTimeReaches(a, b) =>
            Either.cond(
              src.reaches(a, b),
              (),
              s"${a.value} does not reach ${b.value} in narrated-world time"
            )
          case OnlyAssertedRootEventWithLemma(lemma, s) =>
            val hits = g.situations.values.toVector.filter(x =>
              x.isEvent && x.predicate.lemma == lemma && x.modality == Modality.Asserted &&
                g.rootContext.contains(x.context)
            )
            Either.cond(
              hits.map(_.id) == Vector(s),
              (),
              s"asserted root `$lemma` events: ${hits.map(_.id.value)}"
            )
          case HasHypothesisWithAlternatives(s) =>
            Either.cond(
              model.hypotheses.exists(h => h.subject == s && h.reading.alternatives.nonEmpty),
              (),
              s"no hypothesis with alternatives about ${s.value}"
            )
        ok.left.toOption.map(why => fail(e, c, why))
      }
    }

  /** Narrated-world strict-precedence reachability over the root-scoped WorldTime view. */
  private final class StoryAlignmentSourceView(model: StoryModel[?]):
    private val g = model.graph
    private val edges: Map[SituationId, Vector[SituationId]] =
      g.rootContext
        .map(root => g.temporalEdgesIn(root).filter(_.relation.isStrictPrecedence))
        .getOrElse(Vector.empty)
        .groupMap(_.from)(_.to)
    def reaches(a: SituationId, b: SituationId): Boolean =
      var frontier = Set(a)
      var seen = Set.empty[SituationId]
      var found = false
      while frontier.nonEmpty && !found do
        val next = frontier.flatMap(x => edges.getOrElse(x, Vector.empty))
        if next.contains(b) then found = true
        seen ++= frontier
        frontier = next -- seen
      found

  /** Hierarchy level of a recall paraphrase's intended target. */
  enum TargetLevel:
    case Situation, Scene, Episode, Story

  enum ParaphraseKind:
    case Precise, Vague, Summary, Sensory, Retrospective, RoleSwappedFoil, NegatedFoil, Blended,
      ExternalAssociation, Inference

  /** A manual recall statement with its intended source target(s). Foils have no legitimate target:
    * the aligner is expected to gate them (design record §52.4).
    */
  final case class RecallParaphrase(
      kind: ParaphraseKind,
      text: String,
      targets: Vector[NarrativeNodeId],
      level: TargetLevel,
      note: String
  )

  import WarOfTheGhostsModel.G

  val recallParaphrases: Vector[RecallParaphrase] = Vector(
    RecallParaphrase(
      ParaphraseKind.Precise,
      "One of the men with him got hit by an arrow and they lifted him into the boat.",
      Vector(NarrativeNodeId.Situation(S.fellowShot), NarrativeNodeId.Situation(S.carryIntoCanoe)),
      TargetLevel.Situation,
      "two leaf events, correct roles"
    ),
    RecallParaphrase(
      ParaphraseKind.Vague,
      "Then there was some kind of fight and things went badly.",
      Vector(NarrativeNodeId.Situation(S.battle), NarrativeNodeId.Segment(G.sc2c)),
      TargetLevel.Situation,
      "mass may split between the battle and its scene"
    ),
    RecallParaphrase(
      ParaphraseKind.Summary,
      "Two guys were out on the water at night when a boat full of strangers recruited one of them for a raid.",
      Vector(NarrativeNodeId.Segment(G.ep1), NarrativeNodeId.Segment(G.sc2a)),
      TargetLevel.Episode,
      "summary should land on segments, not an arbitrary leaf"
    ),
    RecallParaphrase(
      ParaphraseKind.Sensory,
      "It was misty and completely still on the water.",
      Vector(NarrativeNodeId.Situation(S.fogCalm)),
      TargetLevel.Situation,
      "sensory state, correct leaf"
    ),
    RecallParaphrase(
      ParaphraseKind.Retrospective,
      "When he got back he told his family about going off with the ghosts.",
      Vector(
        NarrativeNodeId.Situation(S.recounting),
        NarrativeNodeId.Situation(S.accompaniedGhosts)
      ),
      TargetLevel.Situation,
      "targets the telling, not the earlier joining"
    ),
    RecallParaphrase(
      ParaphraseKind.RoleSwappedFoil,
      "The young man told the strangers that one of them had been hit.",
      Vector.empty,
      TargetLevel.Situation,
      "speaker and addressee reversed relative to the warriors' report; must be gated"
    ),
    RecallParaphrase(
      ParaphraseKind.NegatedFoil,
      "He felt sick after he was hit.",
      Vector.empty,
      TargetLevel.Situation,
      "polarity reversed relative to the not-feeling-sick state; must be gated"
    ),
    RecallParaphrase(
      ParaphraseKind.Blended,
      "Back home he lit a fire and everyone burst into tears.",
      Vector(NarrativeNodeId.Situation(S.makeFire), NarrativeNodeId.Situation(S.peopleCry)),
      TargetLevel.Situation,
      "two distant events merged; expect bimodal mass"
    ),
    RecallParaphrase(
      ParaphraseKind.ExternalAssociation,
      "It reminded me of a ghost story my grandmother used to tell.",
      Vector.empty,
      TargetLevel.Story,
      "association with a global thematic anchor; external state, not intrusion"
    ),
    RecallParaphrase(
      ParaphraseKind.Inference,
      "The wound from the fight must be what killed him.",
      Vector(NarrativeNodeId.Situation(S.dead)),
      TargetLevel.Situation,
      "source-consistent inference; the causal link is only hypothesized in the source"
    )
  )
