package storymodel4s.fixtures.wog

import storymodel4s.core.*
import storymodel4s.story.*

/** Plain-language expectations for the difficult phenomena of *The War of the Ghosts* (design
  * record §27.1, §27.2, §52.3, §88.3). This is data for the automatic-acquisition benchmark: an
  * unattended build must reproduce these structural facts from the text alone.
  */
object WarOfTheGhostsExpectations:

  enum ExpectedKind:
    case Event, State, Entity, Context

  /** One phenomenon: which sentences carry it, what kinds of nodes it needs, in which context, with
    * what epistemic status, and the plain-language statement a reviewer checks.
    */
  final case class Expectation(
      phenomenon: String,
      sentences: Vector[Int],
      expectedKinds: Vector[ExpectedKind],
      expectedContext: Option[ContextKind],
      expectedStatus: EpistemicStatus,
      statement: String,
      fixtureNodes: Vector[SituationId]
  )

  import WarOfTheGhostsModel.{E, S}

  val all: Vector[Expectation] = Vector(
    Expectation(
      "two young men act together",
      Vector(1, 4, 6),
      Vector(ExpectedKind.Entity, ExpectedKind.Event),
      Some(ContextKind.NarratedWorld),
      EpistemicStatus.SurfaceExplicit,
      "the pair is one group entity sharing participant edges; the two individuals are separate entities",
      Vector(S.huntSeals, S.hide)
    ),
    Expectation(
      "one declines, one joins",
      Vector(15, 19, 20),
      Vector(ExpectedKind.Event),
      Some(ContextKind.NarratedWorld),
      EpistemicStatus.SurfaceExplicit,
      "the refusal and the joining are separate events with different agents",
      Vector(S.ym1Declines, S.ym2Accompanies)
    ),
    Expectation(
      "warriors announce a raid",
      Vector(12),
      Vector(ExpectedKind.Context, ExpectedKind.Event),
      Some(ContextKind.Speech(E.warriors)),
      EpistemicStatus.SurfaceExplicit,
      "the announced war is an intended future event inside the warriors' speech, not a narrated occurrence",
      Vector(S.announcedWar)
    ),
    Expectation(
      "battle occurs later",
      Vector(26, 29),
      Vector(ExpectedKind.Event),
      Some(ContextKind.NarratedWorld),
      EpistemicStatus.SurfaceExplicit,
      "exactly one battle occurrence in the narrated world; the announcement refers to it prospectively",
      Vector(S.battle)
    ),
    Expectation(
      "warriors say the man was hit",
      Vector(30, 32),
      Vector(ExpectedKind.Context, ExpectedKind.Event),
      Some(ContextKind.Speech(E.warriors)),
      EpistemicStatus.SurfaceExplicit,
      "the injury is reported inside the warriors' speech and never becomes a narrated-world fact",
      Vector(S.reportedShot)
    ),
    Expectation(
      "man does not feel injured",
      Vector(32),
      Vector(ExpectedKind.State),
      Some(ContextKind.NarratedWorld),
      EpistemicStatus.SurfaceExplicit,
      "a negated narrated-world state that conflicts with, but does not erase, the report",
      Vector(S.notFeelSick)
    ),
    Expectation(
      "man concludes they are ghosts",
      Vector(31),
      Vector(ExpectedKind.Context, ExpectedKind.State),
      Some(ContextKind.Belief(E.ym2)),
      EpistemicStatus.SurfaceExplicit,
      "'ghosts' is a belief-scoped attribute of the one warriors entity, not a narrated-world fact and not a second entity",
      Vector(S.warriorsAreGhosts)
    ),
    Expectation(
      "man recounts the battle at home",
      Vector(38, 39, 40, 41),
      Vector(ExpectedKind.Context, ExpectedKind.Event),
      Some(ContextKind.Speech(E.ym2)),
      EpistemicStatus.SurfaceExplicit,
      "the recounting is a new telling event whose content refers back to the battle; no duplicate battle",
      Vector(S.recounting, S.weFought)
    ),
    Expectation(
      "man dies later",
      Vector(47, 49),
      Vector(ExpectedKind.State),
      Some(ContextKind.NarratedWorld),
      EpistemicStatus.SurfaceExplicit,
      "death is a narrated-world state",
      Vector(S.dead)
    ),
    Expectation(
      "death cause uncertain",
      Vector(30, 47),
      Vector(ExpectedKind.Event),
      None,
      EpistemicStatus.Hypothesized,
      "no explicit or entailed cause of death is accepted; competing hypotheses remain with credences",
      Vector(S.reportedShot, S.dead)
    ),
    Expectation(
      "discourse order diverges from world order at the recounting",
      Vector(26, 37, 39),
      Vector(ExpectedKind.Event),
      Some(ContextKind.Speech(E.ym2)),
      EpistemicStatus.LinguisticallyEntailed,
      "the retold fight appears after the return home in discourse but denotes the earlier battle",
      Vector(S.weFought, S.battle, S.ym2ToHouse)
    )
  )

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
