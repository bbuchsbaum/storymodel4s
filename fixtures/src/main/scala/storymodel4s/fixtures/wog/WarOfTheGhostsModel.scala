package storymodel4s.fixtures.wog

import cats.data.NonEmptyVector
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}
import storymodel4s.story.*

// File scope is deliberate: the public nested E/C/S/G namespaces must initialize without forcing
// the enclosing model while one of those namespaces is still only partly initialized.
private def eid(k: String): EntityId = EntityId.unsafe(s"wog:ent:$k")
private def sid(k: String): SituationId = SituationId.unsafe(s"wog:sit:$k")
private def gid(k: String): SegmentId = SegmentId.unsafe(s"wog:seg:$k")
private def cid(k: String): ContextId = ContextId.unsafe(s"wog:ctx:$k")

/** Hand-authored narrative acceptance fixture for *The War of the Ghosts* (design record §27).
  *
  * This is a researcher-reviewed model in narrative types — entities, situations, contexts, typed
  * relations, hierarchy — with every span taken from the deterministic surface atlas. It is not
  * gold AMR and contains no charts; it exists so the ontology, validators, renderer, and alignment
  * contract can be exercised on one hard story before any automatic acquisition runs.
  *
  * Identifiers are readable (`wog:<key>`) rather than content-addressed because the fixture is
  * authored, not built; machine builds use [[storymodel4s.core.DeterministicId]].
  *
  * Situations are described in the fixture's own words; sentences are cited by ordinal.
  */
object WarOfTheGhostsModel:
  /** Scorer of every hand-authored credence in this fixture: a person graded it, no model did. */
  val HandScorer: ScorerId = ScorerId.unsafe("fixture:hand")

  /** The title is caller-supplied: the fixture's author states it, from the published source the
    * text came from. It is not derived from the file the text lives in, and the source records that
    * basis so the summary rule may carry it.
    */
  val title: StoryTitle =
    StoryTitle
      .callerSupplied(WarOfTheGhostsText.title)
      .fold(e => throw new IllegalStateException(e.message), identity)

  val source: StorySource =
    StorySource
      .titled(WarOfTheGhostsText.text, title)
      .fold(e => throw new IllegalStateException(e.message), identity)

  val atlas: SurfaceAtlas = SurfaceAnalyzer.analyze(source)

  // ---------------------------------------------------------------------------------------------
  // Evidence helpers
  // ---------------------------------------------------------------------------------------------

  val Annotator: Fingerprint = Fingerprint.unsafe("human:wog-fixture:0.1")
  val Stage: StageId = StageId.unsafe("hand-fixture")
  private val prov: Provenance = Provenance.human("wog-fixture", StoryModel.SchemaVersion)

  /** Sentence by ordinal. */
  def sent(n: Int): SurfaceUnit = atlas.sentences(n)

  /** Support made of whole sentences. */
  def sp(ns: Int*): SpanSet =
    SpanSet.of(ns.map(n => SpanRef(Some(sent(n).id), sent(n).span))).get

  /** The `occurrence`-th token (0-based) in sentence `n` whose text equals `word`
    * (case-insensitive). Used to anchor situations that share a sentence, so discourse order within
    * the sentence follows the predicate position rather than an arbitrary identifier.
    */
  def tok(n: Int, word: String, occurrence: Int = 0): SpanRef =
    val s = sent(n)
    val hits = atlas.childrenOf(s.id).filter(t => atlas.text(t).equalsIgnoreCase(word))
    val t = hits
      .lift(occurrence)
      .getOrElse(
        throw new IllegalStateException(s"fixture: token '$word' #$occurrence not in sentence $n")
      )
    SpanRef(Some(s.id), t.span)

  /** Support anchored at a token of the first sentence, plus any further whole sentences. */
  private def anchoredSupport(sentences: Vector[Int], anchor: Option[(String, Int)]): SpanSet =
    anchor match
      case None           => sp(sentences*)
      case Some((w, occ)) =>
        val first = tok(sentences.head, w, occ)
        val rest = sentences.tail.map(n => SpanRef(Some(sent(n).id), sent(n).span))
        SpanSet.of(first +: rest).get

  def meta(
      key: String,
      status: EpistemicStatus,
      spans: Option[SpanSet],
      raw: Double = 1.0,
      upstream: Set[ClaimId] = Set.empty
  ): ClaimMeta =
    ClaimMeta.unsafe(
      ClaimId.unsafe(s"wog:claim:$key"),
      status,
      Credence.unsafeRaw(raw, HandScorer),
      NonEmptyVector.one(
        Evidence(EvidenceId.unsafe(s"wog:ev:$key"), spans, upstream, Annotator, Stage)
      ),
      prov
    )

  private def explicit(key: String, spans: SpanSet, raw: Double = 1.0): ClaimMeta =
    meta(key, EpistemicStatus.SurfaceExplicit, Some(spans), raw)

  private def resolved(key: String, value: String, spans: SpanSet): Resolved[String] =
    Resolved(value, explicit(key, spans), Vector.empty)

  private def emention(k: String, n: Int): MentionId[EntityK] =
    MentionId.unsafe[EntityK](s"wog:m:ent:$k:s$n")
  private def smention(k: String, n: Int): MentionId[SituationK] =
    MentionId.unsafe[SituationK](s"wog:m:sit:$k:s$n")

  // ---------------------------------------------------------------------------------------------
  // Entities
  // ---------------------------------------------------------------------------------------------

  object E:
    val youngMen = eid("young-men")
    val ym1 = eid("young-man-1") // declines, goes home, reports at midnight
    val ym2 = eid("young-man-2") // joins the war party; the protagonist
    val warriors = eid("warriors") // the five men in the canoe; later believed to be ghosts
    val enemy = eid("kalama-people")
    val relatives = eid("relatives")
    val fellow = eid("shot-fellow")
    val canoe = eid("canoe")
    val house = eid("house")
    val egulac = eid("egulac")
    val kalama = eid("kalama")
    val river = eid("river")
    val log = eid("log")
    val arrows = eid("arrows")

  private def entity(
      key: String,
      id: EntityId,
      label: String,
      tpe: EntityType,
      mentionSentences: Vector[Int],
      attributes: Vector[ScopedAttribute] = Vector.empty,
      status: EpistemicStatus = EpistemicStatus.SurfaceExplicit,
      raw: Double = 1.0
  ): EntityNode =
    val support = sp(mentionSentences*)
    EntityNode(
      id,
      resolved(s"ent:$key:label", label, support),
      tpe,
      NonEmptyVector.fromVectorUnsafe(mentionSentences.map(n => emention(key, n))),
      attributes,
      support,
      meta(s"ent:$key", status, Some(support), raw)
    )

  // ---------------------------------------------------------------------------------------------
  // Contexts
  // ---------------------------------------------------------------------------------------------

  object C:
    val world = cid("world")
    val thoughtWarParty = cid("thought-war-party") // the pair's conjecture, s5
    val speechWarriors1 = cid("speech-warriors-1") // invitation and war announcement, s10-12
    val speechYm1a = cid("speech-ym1-a") // "no arrows", s13
    val speechWarriors2 = cid("speech-warriors-2") // arrows are in the canoe, s14
    val speechYm1b = cid("speech-ym1-b") // the refusal, s15-17
    val speechYm1c = cid("speech-ym1-c") // the midnight report, s21-22
    val beliefPeople = cid("belief-people") // ym2 takes the warriors for ordinary people, s27
    val speechWarriors3 = cid("speech-warriors-3") // "let us go home; he has been shot", s30 (+s32)
    val beliefGhosts = cid("belief-ghosts") // ym2 concludes they are ghosts, s31
    val speechYm2 = cid("speech-ym2") // the recounting at home, s38-41
    val speechYm2Inner = cid("speech-ym2-inner") // the warriors' words as retold, s41

  private def context(
      id: ContextId,
      parent: Option[ContextId],
      kind: ContextKind,
      sentences: Int*
  ): ContextFrame =
    ContextFrame(id, parent, kind, sp(sentences*), explicit(s"ctx:${id.value}", sp(sentences*)))

  // ---------------------------------------------------------------------------------------------
  // Situations
  // ---------------------------------------------------------------------------------------------

  private def ev(
      key: String,
      sentences: Vector[Int],
      ctx: ContextId,
      lemma: String,
      description: String,
      polarity: Polarity = Polarity.Positive,
      modality: Modality = Modality.Asserted,
      raw: Double = 1.0,
      anchor: Option[(String, Int)] = None,
      status: EpistemicStatus = EpistemicStatus.SurfaceExplicit
  ): SituationNode =
    val support = anchoredSupport(sentences, anchor)
    SituationNode.Event(
      EventNode(
        sid(key),
        Predicate(lemma, None, description),
        description,
        ctx,
        polarity,
        modality,
        None,
        support,
        NonEmptyVector.fromVectorUnsafe(sentences.map(n => smention(key, n))),
        meta(s"sit:$key", status, Some(sp(sentences*)), raw)
      )
    )

  private def st(
      key: String,
      sentences: Vector[Int],
      ctx: ContextId,
      lemma: String,
      description: String,
      polarity: Polarity = Polarity.Positive,
      modality: Modality = Modality.Asserted,
      raw: Double = 1.0,
      anchor: Option[(String, Int)] = None,
      status: EpistemicStatus = EpistemicStatus.SurfaceExplicit
  ): SituationNode =
    val support = anchoredSupport(sentences, anchor)
    SituationNode.State(
      StateNode(
        sid(key),
        Predicate(lemma, None, description),
        description,
        ctx,
        polarity,
        modality,
        support,
        NonEmptyVector.fromVectorUnsafe(sentences.map(n => smention(key, n))),
        meta(s"sit:$key", status, Some(sp(sentences*)), raw)
      )
    )

  /** Readable situation identifiers used by tests and downstream fixtures. */
  object S:
    val peopleAtEgulac = sid("people-at-egulac")
    val huntSeals = sid("hunt-seals")
    val downRiver = sid("down-river")
    val fogCalm = sid("fog-calm")
    val hearWarCries = sid("hear-war-cries")
    val thinkWarParty = sid("think-war-party")
    val warPartyPresent = sid("war-party-present")
    val hide = sid("hide")
    val canoesComeUp = sid("canoes-come-up")
    val hearPaddles = sid("hear-paddles")
    val seeCanoe = sid("see-canoe")
    val fiveMenInCanoe = sid("five-men-in-canoe")
    val warriorsSpeak = sid("warriors-speak")
    val wishTakeAlong = sid("wish-take-along")
    val announcedWar = sid("announced-war")
    val ym1SaysNoArrows = sid("ym1-says-no-arrows")
    val lackArrows = sid("lack-arrows")
    val warriorsSayArrows = sid("warriors-say-arrows")
    val arrowsInCanoe = sid("arrows-in-canoe")
    val ym1Declines = sid("ym1-declines")
    val notGoAlong = sid("not-go-along")
    val mightBeKilled = sid("might-be-killed")
    val relativesNotKnow = sid("relatives-not-know")
    val youMayGo = sid("you-may-go")
    val ym2Accompanies = sid("ym2-accompanies")
    val ym2EntersCanoe = sid("ym2-enters-canoe")
    val ym1GoesHome = sid("ym1-goes-home")
    val ym1ReturnsMidnight = sid("ym1-returns-midnight")
    val ym1Reports = sid("ym1-reports")
    val relativeLeftMe = sid("relative-left-me")
    val ym2WentWithWarriors = sid("ym2-went-with-warriors")
    val warriorsGo = sid("warriors-go")
    val talkInCanoe = sid("talk-in-canoe")
    val arriveKalama = sid("arrive-kalama")
    val enemyComeToWater = sid("enemy-come-to-water")
    val battle = sid("battle")
    val ym2ThinksPeople = sid("ym2-thinks-people")
    val warriorsArePeople = sid("warriors-are-people")
    val fellowShot = sid("fellow-shot")
    val carryIntoCanoe = sid("carry-into-canoe")
    val warriorsSayGoHome = sid("warriors-say-go-home")
    val letUsGoHome = sid("let-us-go-home")
    val reportedShot = sid("reported-shot")
    val ym2ConcludesGhosts = sid("ym2-concludes-ghosts")
    val warriorsAreGhosts = sid("warriors-are-ghosts")
    val notFeelSick = sid("not-feel-sick")
    val warriorsGoHome = sid("warriors-go-home")
    val arriveEgulac = sid("arrive-egulac")
    val canoeLands = sid("canoe-lands")
    val ym2Ashore = sid("ym2-ashore")
    val warriorsDownRiver = sid("warriors-down-river")
    val ym2ToHouse = sid("ym2-to-house")
    val makeFire = sid("make-fire")
    val recounting = sid("recounting")
    val accompaniedGhosts = sid("accompanied-ghosts")
    val weFought = sid("we-fought")
    val manyFellowsKilled = sid("many-fellows-killed")
    val manyEnemyKilled = sid("many-enemy-killed")
    val theySaidShot = sid("they-said-shot")
    val iWasShot = sid("i-was-shot")
    val iDidNotFeelSick = sid("i-did-not-feel-sick")
    val becomesQuiet = sid("becomes-quiet")
    val nearlyDaylight = sid("nearly-daylight")
    val sunRises = sid("sun-rises")
    val fallsDown = sid("falls-down")
    val blackFromMouth = sid("black-from-mouth")
    val bloodFromAnus = sid("blood-from-anus")
    val faceContorted = sid("face-contorted")
    val dead = sid("dead")
    val peopleCry = sid("people-cry")

    /** The narrated-world truth of the reported injury, left open (design record §27.2). */
    val ym2Injured = sid("ym2-injured")

  /** Readable segment identifiers. */
  object G:
    val story = gid("story")
    val ep1 = gid("ep1-river")
    val ep2 = gid("ep2-expedition")
    val ep3 = gid("ep3-return")
    val sc1a = gid("sc1a-hunting")
    val sc1b = gid("sc1b-war-cries")
    val sc1c = gid("sc1c-canoe-arrives")
    val sc2a = gid("sc2a-invitation")
    val sc2b = gid("sc2b-parting")
    val sc2c = gid("sc2c-journey-battle")
    val sc3a = gid("sc3a-retreat")
    val sc3b = gid("sc3b-recounting")
    val sc3c = gid("sc3c-death")

  // ---------------------------------------------------------------------------------------------
  // Graph assembly
  // ---------------------------------------------------------------------------------------------

  import E.*
  import C.*

  private val ghostAttr = ScopedAttribute(
    beliefGhosts,
    "kind",
    "ghosts",
    explicit("attr:warriors:ghosts", sp(31))
  )
  private val peopleAttr = ScopedAttribute(
    beliefPeople,
    "kind",
    "people",
    explicit("attr:warriors:people", sp(27))
  )

  val entities: Vector[EntityNode] = Vector(
    entity("young-men", youngMen, "the two young men", EntityType.Group, Vector(1, 4, 6)),
    entity("young-man-1", ym1, "the young man who declined", EntityType.Person, Vector(15, 20, 21)),
    entity("young-man-2", ym2, "the young man who went", EntityType.Person, Vector(19, 27, 47)),
    entity(
      "warriors",
      warriors,
      "the five men in the canoe (the warriors)",
      EntityType.Group,
      Vector(9, 23, 31),
      Vector(peopleAttr, ghostAttr)
    ),
    entity(
      "kalama-people",
      enemy,
      "the people attacked at Kalama",
      EntityType.Group,
      Vector(12, 26)
    ),
    // identifying the decliner's "relatives" (his own words) with the household the survivor
    // returns to is an inference, not something the text states
    entity(
      "relatives",
      relatives,
      "the young men's people at Egulac",
      EntityType.Group,
      Vector(0, 16, 48),
      status = EpistemicStatus.WorldKnowledgeInferred,
      raw = 0.7
    ),
    entity("shot-fellow", fellow, "the warrior who was shot", EntityType.Person, Vector(28)),
    entity("canoe", canoe, "the warriors' canoe", EntityType.Object, Vector(8, 14, 35)),
    entity("house", house, "the house at Egulac", EntityType.Location, Vector(37)),
    entity("egulac", egulac, "Egulac", EntityType.Location, Vector(0, 34)),
    entity("kalama", kalama, "Kalama", EntityType.Location, Vector(25)),
    entity("river", river, "the river", EntityType.Location, Vector(2, 12)),
    entity("log", log, "the log on the shore", EntityType.Object, Vector(6)),
    entity("arrows", arrows, "arrows", EntityType.Object, Vector(13, 14))
  )

  val contexts: Vector[ContextFrame] = Vector(
    context(world, None, ContextKind.NarratedWorld, (0 to 49)*),
    context(thoughtWarParty, Some(world), ContextKind.Belief(ContextHolder.Named(youngMen)), 5),
    context(
      speechWarriors1,
      Some(world),
      ContextKind.Speech(ContextHolder.Named(warriors)),
      10,
      11,
      12
    ),
    context(speechYm1a, Some(world), ContextKind.Speech(ContextHolder.Named(ym1)), 13),
    context(speechWarriors2, Some(world), ContextKind.Speech(ContextHolder.Named(warriors)), 14),
    context(speechYm1b, Some(world), ContextKind.Speech(ContextHolder.Named(ym1)), 15, 16, 17),
    context(speechYm1c, Some(world), ContextKind.Speech(ContextHolder.Named(ym1)), 21, 22),
    context(beliefPeople, Some(world), ContextKind.Belief(ContextHolder.Named(ym2)), 27),
    context(
      speechWarriors3,
      Some(world),
      ContextKind.Speech(ContextHolder.Named(warriors)),
      30,
      32
    ),
    context(beliefGhosts, Some(world), ContextKind.Belief(ContextHolder.Named(ym2)), 31),
    context(speechYm2, Some(world), ContextKind.Speech(ContextHolder.Named(ym2)), 38, 39, 40, 41),
    context(speechYm2Inner, Some(speechYm2), ContextKind.Speech(ContextHolder.Named(warriors)), 41)
  )

  import Polarity.Negative
  import Modality.{Desired, Intended, Possible, Reported}

  val situations: Vector[SituationNode] = Vector(
    st("people-at-egulac", Vector(0), world, "live", "people live at Egulac"),
    ev("hunt-seals", Vector(1), world, "hunt", "the two young men go seal hunting one night"),
    ev("down-river", Vector(2), world, "go", "they travel down the river"),
    st("fog-calm", Vector(3), world, "foggy", "fog and calm settle on the river"),
    ev("hear-war-cries", Vector(4), world, "hear", "while paddling they hear war cries"),
    ev(
      "think-war-party",
      Vector(5),
      world,
      "think",
      "they conjecture about a war party",
      anchor = Some(("thought", 0))
    ),
    st(
      "war-party-present",
      Vector(5),
      thoughtWarParty,
      "exist",
      "a war party is nearby (their conjecture)",
      modality = Possible,
      anchor = Some(("party", 0))
    ),
    ev("hide", Vector(6), world, "hide", "they flee to the shore and hide behind a log"),
    ev(
      "canoes-come-up",
      Vector(7),
      world,
      "come",
      "canoes come up the river",
      anchor = Some(("came", 0))
    ),
    ev("hear-paddles", Vector(7), world, "hear", "they hear paddling", anchor = Some(("heard", 0))),
    ev("see-canoe", Vector(8), world, "see", "they see one canoe approach them"),
    st("five-men-in-canoe", Vector(9), world, "be-in", "five men are in the canoe"),
    ev(
      "warriors-speak",
      Vector(10),
      world,
      "speak",
      "the men in the canoe address the pair",
      anchor = Some(("spoke", 0))
    ),
    st(
      "wish-take-along",
      Vector(11),
      speechWarriors1,
      "want",
      "the men want to take the pair along",
      modality = Desired
    ),
    ev(
      "announced-war",
      Vector(12),
      speechWarriors1,
      "fight",
      "the men announce they are going upriver to make war on the people",
      modality = Intended
    ),
    ev(
      "ym1-says-no-arrows",
      Vector(13),
      world,
      "say",
      "one young man says he has no arrows",
      anchor = Some(("said", 0))
    ),
    st(
      "lack-arrows",
      Vector(13),
      speechYm1a,
      "have",
      "he has no arrows",
      polarity = Negative,
      anchor = Some(("arrows", 0))
    ),
    ev("warriors-say-arrows", Vector(14), world, "say", "the men reply about arrows"),
    st("arrows-in-canoe", Vector(14), speechWarriors2, "be-in", "arrows are in the canoe"),
    ev(
      "ym1-declines",
      Vector(15, 18),
      world,
      "say",
      "one young man refuses to go, speaking to his fellow",
      anchor = Some(("said", 0))
    ),
    ev(
      "not-go-along",
      Vector(15),
      speechYm1b,
      "go",
      "he will not go along",
      polarity = Negative,
      modality = Intended,
      anchor = Some(("go", 0))
    ),
    ev(
      "might-be-killed",
      Vector(15),
      speechYm1b,
      "kill",
      "he might be killed",
      modality = Possible,
      anchor = Some(("killed", 0))
    ),
    st(
      "relatives-not-know",
      Vector(16),
      speechYm1b,
      "know",
      "his relatives do not know where he has gone",
      polarity = Negative
    ),
    ev(
      "you-may-go",
      Vector(17),
      speechYm1b,
      "go",
      "the other may go with them",
      modality = Possible
    ),
    ev("ym2-accompanies", Vector(19), world, "accompany", "the other young man joins the men"),
    ev(
      "ym2-enters-canoe",
      Vector(20),
      world,
      "enter",
      "he boards their canoe",
      anchor = Some(("went", 0))
    ),
    ev(
      "ym1-goes-home",
      Vector(20),
      world,
      "go",
      "the one who declined goes home",
      anchor = Some(("went", 1))
    ),
    ev(
      "ym1-returns-midnight",
      Vector(21),
      world,
      "return",
      "at midnight the decliner returns",
      anchor = Some(("returned", 0))
    ),
    ev(
      "ym1-reports",
      Vector(21),
      world,
      "say",
      "the decliner tells what happened",
      anchor = Some(("said", 0))
    ),
    ev(
      "relative-left-me",
      Vector(21),
      speechYm1c,
      "leave",
      "his relative left him",
      anchor = Some(("left", 0))
    ),
    ev(
      "ym2-went-with-warriors",
      Vector(22),
      speechYm1c,
      "accompany",
      "his relative went with the warriors going upriver to make war"
    ),
    ev("warriors-go", Vector(23), world, "go", "the warriors set off"),
    ev("talk-in-canoe", Vector(24), world, "talk", "the people in the canoe talk together"),
    ev("arrive-kalama", Vector(25), world, "arrive", "they reach a place beyond Kalama"),
    ev(
      "enemy-come-to-water",
      Vector(26),
      world,
      "come",
      "the people there come down to the water",
      anchor = Some(("went", 0))
    ),
    ev(
      "battle",
      Vector(26, 29),
      world,
      "fight",
      "the battle is fought",
      anchor = Some(("fight", 0))
    ),
    ev(
      "ym2-thinks-people",
      Vector(27),
      world,
      "think",
      "the young man takes his companions for ordinary people",
      anchor = Some(("thought", 0))
    ),
    st(
      "warriors-are-people",
      Vector(27),
      beliefPeople,
      "be",
      "the warriors are ordinary people",
      anchor = Some(("people", 0))
    ),
    ev(
      "fellow-shot",
      Vector(28),
      world,
      "shoot",
      "one of the warriors is shot",
      anchor = Some(("shot", 0))
    ),
    ev(
      "carry-into-canoe",
      Vector(28),
      world,
      "carry",
      "the warriors carry the shot man into the canoe",
      anchor = Some(("carried", 0))
    ),
    ev(
      "warriors-say-go-home",
      Vector(30, 32),
      world,
      "say",
      "the warriors call for going home and speak of the young man being shot",
      anchor = Some(("said", 0))
    ),
    ev(
      "let-us-go-home",
      Vector(30),
      speechWarriors3,
      "go",
      "let us go home quickly",
      modality = Desired,
      anchor = Some(("go", 0))
    ),
    ev(
      "reported-shot",
      Vector(30, 32),
      speechWarriors3,
      "shoot",
      "the young man has been shot (as the warriors say)",
      modality = Reported,
      anchor = Some(("shot", 0))
    ),
    ev(
      "ym2-concludes-ghosts",
      Vector(31),
      world,
      "think",
      "the young man concludes something about his companions",
      anchor = Some(("thought", 0))
    ),
    st(
      "warriors-are-ghosts",
      Vector(31),
      beliefGhosts,
      "be",
      "the warriors are ghosts",
      anchor = Some(("ghosts", 0))
    ),
    st(
      "not-feel-sick",
      Vector(32),
      world,
      "feel-sick",
      "the young man does not feel sick",
      polarity = Negative
    ),
    ev("warriors-go-home", Vector(33), world, "go", "the warriors go home"),
    ev("arrive-egulac", Vector(34), world, "arrive", "they arrive at Egulac"),
    ev("canoe-lands", Vector(35), world, "land", "one canoe lands", anchor = Some(("landed", 0))),
    ev(
      "ym2-ashore",
      Vector(35),
      world,
      "go",
      "the young man goes ashore",
      anchor = Some(("ashore", 0))
    ),
    ev("warriors-down-river", Vector(36), world, "go", "the warriors go on down the river"),
    ev(
      "ym2-to-house",
      Vector(37),
      world,
      "go",
      "the young man goes up to the house",
      anchor = Some(("went", 0))
    ),
    ev("make-fire", Vector(37), world, "make", "he makes a fire", anchor = Some(("made", 0))),
    ev(
      "recounting",
      Vector(38, 42),
      world,
      "tell",
      "he tells everything that happened",
      anchor = Some(("said", 0))
    ),
    ev(
      "accompanied-ghosts",
      Vector(38),
      speechYm2,
      "accompany",
      "he went with the ghosts (his words)",
      anchor = Some(("accompanied", 0))
    ),
    ev("we-fought", Vector(39), speechYm2, "fight", "we fought (his words)"),
    ev(
      "many-fellows-killed",
      Vector(40),
      speechYm2,
      "kill",
      "many of our companions were killed (his words)",
      anchor = Some(("killed", 0))
    ),
    ev(
      "many-enemy-killed",
      Vector(40),
      speechYm2,
      "kill",
      "many of those attacked were killed (his words)",
      anchor = Some(("killed", 1))
    ),
    ev(
      "they-said-shot",
      Vector(41),
      speechYm2,
      "say",
      "they told him he was shot (his words)",
      anchor = Some(("said", 0))
    ),
    ev(
      "i-was-shot",
      Vector(41),
      speechYm2Inner,
      "shoot",
      "he was shot (the warriors' words, retold)",
      modality = Reported,
      anchor = Some(("shot", 0))
    ),
    st(
      "i-did-not-feel-sick",
      Vector(41),
      speechYm2,
      "feel-sick",
      "he did not feel sick (his words)",
      polarity = Negative,
      anchor = Some(("feel", 0))
    ),
    ev(
      "becomes-quiet",
      Vector(42, 43),
      world,
      "quiet",
      "he falls silent",
      anchor = Some(("quiet", 0))
    ),
    st("nearly-daylight", Vector(43), world, "daylight", "it is nearly daylight"),
    ev("sun-rises", Vector(44), world, "rise", "the sun rises", anchor = Some(("rose", 0))),
    ev("falls-down", Vector(44), world, "fall", "he collapses", anchor = Some(("fell", 0))),
    ev(
      "black-from-mouth",
      Vector(45),
      world,
      "emerge",
      "something black comes from his mouth",
      anchor = Some(("black", 0))
    ),
    ev(
      "blood-from-anus",
      Vector(45),
      world,
      "emerge",
      "blood comes from his body",
      anchor = Some(("blood", 0))
    ),
    ev("face-contorted", Vector(46), world, "contort", "his face contorts"),
    st("dead", Vector(47, 49), world, "dead", "he is dead"),
    ev("people-cry", Vector(48), world, "cry", "the people jump up and cry"),
    // the positive ambiguity §27.2 demands: whether the young man was in fact injured is a
    // narrated-world hypothesis with two readings (see `hypotheses`), never an explicit fact
    ev(
      "ym2-injured",
      Vector(30, 32),
      world,
      "shoot",
      "the young man was in fact wounded (open hypothesis)",
      modality = Possible,
      raw = 0.5,
      anchor = Some(("shot", 0)),
      status = EpistemicStatus.Hypothesized
    )
  )

  // participants ------------------------------------------------------------------------------

  import ParticipantRole.*

  private def part(
      s: SituationId,
      role: ParticipantRole,
      e: EntityId,
      n: Int,
      status: EpistemicStatus = EpistemicStatus.SurfaceExplicit,
      raw: Double = 1.0
  ): ParticipantEdge =
    ParticipantEdge(
      s,
      role,
      e,
      meta(s"part:${s.value}:${role.render}:${e.value}", status, Some(sp(n)), raw)
    )

  val participants: Vector[ParticipantEdge] = Vector(
    part(S.peopleAtEgulac, Theme, relatives, 0),
    // the conjectured war party and the canoes are identified with the warriors only by inference
    part(S.warPartyPresent, Theme, warriors, 5, EpistemicStatus.WorldKnowledgeInferred, 0.6),
    part(S.canoesComeUp, Theme, canoe, 7, EpistemicStatus.WorldKnowledgeInferred, 0.6),
    part(S.ym2Injured, Patient, ym2, 30, EpistemicStatus.Hypothesized, 0.5),
    part(S.peopleAtEgulac, Location, egulac, 0),
    part(S.huntSeals, Agent, youngMen, 1),
    part(S.downRiver, Agent, youngMen, 2),
    part(S.downRiver, Location, river, 2),
    part(S.hearWarCries, Experiencer, youngMen, 4),
    part(S.thinkWarParty, Agent, youngMen, 5),
    part(S.hide, Agent, youngMen, 6),
    part(S.hide, Location, log, 6),
    part(S.hearPaddles, Experiencer, youngMen, 7),
    part(S.seeCanoe, Experiencer, youngMen, 8),
    part(S.seeCanoe, Stimulus, canoe, 8),
    part(S.fiveMenInCanoe, Theme, warriors, 9),
    part(S.fiveMenInCanoe, Location, canoe, 9),
    part(S.warriorsSpeak, Agent, warriors, 10),
    part(S.warriorsSpeak, Patient, youngMen, 10),
    part(S.wishTakeAlong, Experiencer, warriors, 11),
    part(S.wishTakeAlong, Theme, youngMen, 11),
    part(S.announcedWar, Agent, warriors, 12),
    part(S.announcedWar, Patient, enemy, 12),
    part(S.announcedWar, Location, river, 12),
    // which of the two speaks is underdetermined by the text: the uncertainty is about the speaker
    part(S.ym1SaysNoArrows, Agent, ym1, 13, EpistemicStatus.WorldKnowledgeInferred, 0.6),
    part(S.lackArrows, Theme, arrows, 13),
    part(S.lackArrows, Experiencer, ym1, 13),
    part(S.warriorsSayArrows, Agent, warriors, 14),
    part(S.arrowsInCanoe, Theme, arrows, 14),
    part(S.arrowsInCanoe, Location, canoe, 14),
    part(S.ym1Declines, Agent, ym1, 15),
    part(S.ym1Declines, Patient, ym2, 18),
    part(S.notGoAlong, Agent, ym1, 15),
    part(S.mightBeKilled, Patient, ym1, 15),
    part(S.relativesNotKnow, Experiencer, relatives, 16),
    part(S.youMayGo, Agent, ym2, 17),
    part(S.youMayGo, Theme, warriors, 17),
    part(S.ym2Accompanies, Agent, ym2, 19),
    part(S.ym2Accompanies, Theme, warriors, 19),
    part(S.ym2EntersCanoe, Agent, ym2, 20),
    part(S.ym2EntersCanoe, Destination, canoe, 20),
    part(S.ym1GoesHome, Agent, ym1, 20),
    part(S.ym1ReturnsMidnight, Agent, ym1, 21),
    part(S.ym1Reports, Agent, ym1, 21),
    part(S.relativeLeftMe, Agent, ym2, 21),
    part(S.relativeLeftMe, Patient, ym1, 21),
    part(S.ym2WentWithWarriors, Agent, ym2, 22),
    part(S.ym2WentWithWarriors, Theme, warriors, 22),
    part(S.warriorsGo, Agent, warriors, 23),
    part(S.talkInCanoe, Agent, warriors, 24),
    part(S.arriveKalama, Agent, warriors, 25),
    part(S.arriveKalama, Destination, kalama, 25),
    part(S.enemyComeToWater, Agent, enemy, 26),
    part(S.battle, Agent, warriors, 26),
    part(S.battle, Patient, enemy, 26),
    part(S.ym2ThinksPeople, Agent, ym2, 27),
    part(S.warriorsArePeople, Theme, warriors, 27),
    part(S.fellowShot, Patient, fellow, 28),
    part(S.carryIntoCanoe, Agent, warriors, 28),
    part(S.carryIntoCanoe, Patient, fellow, 28),
    part(S.carryIntoCanoe, Destination, canoe, 28),
    part(S.warriorsSayGoHome, Agent, warriors, 30),
    part(S.letUsGoHome, Agent, warriors, 30),
    part(S.reportedShot, Patient, ym2, 30),
    part(S.ym2ConcludesGhosts, Agent, ym2, 31),
    part(S.warriorsAreGhosts, Theme, warriors, 31),
    part(S.notFeelSick, Experiencer, ym2, 32),
    part(S.warriorsGoHome, Agent, warriors, 33),
    part(S.arriveEgulac, Agent, warriors, 34),
    part(S.arriveEgulac, Destination, egulac, 34),
    part(S.canoeLands, Theme, canoe, 35),
    part(S.ym2Ashore, Agent, ym2, 35),
    part(S.warriorsDownRiver, Agent, warriors, 36),
    part(S.warriorsDownRiver, Location, river, 36),
    part(S.ym2ToHouse, Agent, ym2, 37),
    part(S.ym2ToHouse, Destination, house, 37),
    part(S.makeFire, Agent, ym2, 37),
    part(S.recounting, Agent, ym2, 38),
    part(S.recounting, Beneficiary, relatives, 38),
    part(S.accompaniedGhosts, Agent, ym2, 38),
    part(S.accompaniedGhosts, Theme, warriors, 38),
    part(S.weFought, Agent, warriors, 39),
    part(S.weFought, Agent, ym2, 39),
    part(S.manyFellowsKilled, Patient, warriors, 40),
    part(S.manyEnemyKilled, Patient, enemy, 40),
    part(S.theySaidShot, Agent, warriors, 41),
    part(S.theySaidShot, Beneficiary, ym2, 41),
    part(S.iWasShot, Patient, ym2, 41),
    part(S.iDidNotFeelSick, Experiencer, ym2, 41),
    part(S.becomesQuiet, Agent, ym2, 42),
    part(S.fallsDown, Agent, ym2, 44),
    part(S.blackFromMouth, Source, ym2, 45),
    part(S.bloodFromAnus, Source, ym2, 45),
    part(S.faceContorted, Patient, ym2, 46),
    part(S.dead, Theme, ym2, 47),
    part(S.peopleCry, Agent, relatives, 48)
  )

  // temporal ----------------------------------------------------------------------------------

  private def before(a: SituationId, b: SituationId, n: Int, ctx: ContextId = world): TemporalEdge =
    TemporalEdge(a, TemporalRelation.Before, b, ctx, explicit(s"time:${a.value}:${b.value}", sp(n)))

  private def temporal(
      a: SituationId,
      rel: TemporalRelation,
      b: SituationId,
      n: Int,
      status: EpistemicStatus,
      ctx: ContextId = world
  ): TemporalEdge =
    TemporalEdge(a, rel, b, ctx, meta(s"time:${a.value}:${b.value}", status, Some(sp(n))))

  val temporal: Vector[TemporalEdge] = Vector(
    before(S.huntSeals, S.downRiver, 2),
    temporal(
      S.fogCalm,
      TemporalRelation.Overlaps,
      S.hearWarCries,
      4,
      EpistemicStatus.LinguisticallyEntailed
    ),
    before(S.downRiver, S.hearWarCries, 4),
    before(S.hearWarCries, S.thinkWarParty, 5),
    before(S.thinkWarParty, S.hide, 6),
    before(S.hide, S.canoesComeUp, 7),
    temporal(
      S.canoesComeUp,
      TemporalRelation.Overlaps,
      S.hearPaddles,
      7,
      EpistemicStatus.LinguisticallyEntailed
    ),
    before(S.canoesComeUp, S.seeCanoe, 8),
    before(S.seeCanoe, S.warriorsSpeak, 10),
    before(S.warriorsSpeak, S.ym1SaysNoArrows, 13),
    before(S.ym1SaysNoArrows, S.warriorsSayArrows, 14),
    before(S.warriorsSayArrows, S.ym1Declines, 15),
    before(S.ym1Declines, S.ym2Accompanies, 19),
    temporal(
      S.ym2Accompanies,
      TemporalRelation.Meets,
      S.ym2EntersCanoe,
      20,
      EpistemicStatus.LinguisticallyEntailed
    ),
    before(S.ym2EntersCanoe, S.ym1GoesHome, 20),
    before(S.ym1GoesHome, S.ym1ReturnsMidnight, 21),
    temporal(
      S.ym1ReturnsMidnight,
      TemporalRelation.Meets,
      S.ym1Reports,
      21,
      EpistemicStatus.LinguisticallyEntailed
    ),
    before(S.ym2EntersCanoe, S.warriorsGo, 23),
    temporal(S.ym1Reports, TemporalRelation.Unclear, S.battle, 23, EpistemicStatus.Hypothesized),
    temporal(
      S.warriorsGo,
      TemporalRelation.Overlaps,
      S.talkInCanoe,
      24,
      EpistemicStatus.LinguisticallyEntailed
    ),
    before(S.warriorsGo, S.arriveKalama, 25),
    before(S.arriveKalama, S.enemyComeToWater, 26),
    temporal(
      S.enemyComeToWater,
      TemporalRelation.Meets,
      S.battle,
      26,
      EpistemicStatus.LinguisticallyEntailed
    ),
    temporal(
      S.battle,
      TemporalRelation.Contains,
      S.fellowShot,
      28,
      EpistemicStatus.LinguisticallyEntailed
    ),
    temporal(
      S.battle,
      TemporalRelation.Contains,
      S.ym2ThinksPeople,
      27,
      EpistemicStatus.WorldKnowledgeInferred
    ),
    before(S.fellowShot, S.carryIntoCanoe, 28),
    before(S.carryIntoCanoe, S.warriorsSayGoHome, 30),
    before(S.warriorsSayGoHome, S.ym2ConcludesGhosts, 31),
    temporal(
      S.ym2ConcludesGhosts,
      TemporalRelation.Overlaps,
      S.notFeelSick,
      32,
      EpistemicStatus.WorldKnowledgeInferred
    ),
    before(S.battle, S.warriorsGoHome, 33),
    // closes the narrated-world chain from the battle's internal events to the return
    before(S.ym2ConcludesGhosts, S.warriorsGoHome, 33),
    // the hypothesized injury, if it happened, happened during the battle
    temporal(
      S.battle,
      TemporalRelation.Contains,
      S.ym2Injured,
      30,
      EpistemicStatus.Hypothesized
    ),
    before(S.warriorsGoHome, S.arriveEgulac, 34),
    temporal(
      S.arriveEgulac,
      TemporalRelation.Meets,
      S.canoeLands,
      35,
      EpistemicStatus.LinguisticallyEntailed
    ),
    before(S.canoeLands, S.ym2Ashore, 35),
    before(S.ym2Ashore, S.warriorsDownRiver, 36),
    before(S.ym2Ashore, S.ym2ToHouse, 37),
    before(S.ym2ToHouse, S.makeFire, 37),
    before(S.makeFire, S.recounting, 38),
    temporal(
      S.recounting,
      TemporalRelation.Meets,
      S.becomesQuiet,
      42,
      EpistemicStatus.LinguisticallyEntailed
    ),
    temporal(
      S.becomesQuiet,
      TemporalRelation.During,
      S.nearlyDaylight,
      43,
      EpistemicStatus.LinguisticallyEntailed
    ),
    before(S.becomesQuiet, S.sunRises, 44),
    temporal(
      S.sunRises,
      TemporalRelation.Meets,
      S.fallsDown,
      44,
      EpistemicStatus.LinguisticallyEntailed
    ),
    before(S.fallsDown, S.blackFromMouth, 45),
    temporal(
      S.blackFromMouth,
      TemporalRelation.Overlaps,
      S.bloodFromAnus,
      45,
      EpistemicStatus.LinguisticallyEntailed
    ),
    before(S.blackFromMouth, S.faceContorted, 46),
    before(S.faceContorted, S.dead, 47),
    temporal(
      S.dead,
      TemporalRelation.Overlaps,
      S.peopleCry,
      48,
      EpistemicStatus.LinguisticallyEntailed
    ),
    // inside the warriors' speech: the reported shooting motivates the call to go home
    before(S.reportedShot, S.letUsGoHome, 30, speechWarriors3),
    // inside the young man's recounting: the retold order of events
    before(S.weFought, S.manyFellowsKilled, 40, speechYm2),
    before(S.manyFellowsKilled, S.theySaidShot, 41, speechYm2),
    temporal(
      S.theySaidShot,
      TemporalRelation.Overlaps,
      S.iDidNotFeelSick,
      41,
      EpistemicStatus.LinguisticallyEntailed,
      speechYm2
    )
  )

  // causal ------------------------------------------------------------------------------------

  private def causal(
      a: SituationId,
      rel: CausalRelation,
      b: SituationId,
      status: EpistemicStatus,
      raw: Double,
      n: Int*
  ): CausalEdge =
    CausalEdge(a, rel, b, meta(s"cause:${a.value}:${b.value}", status, Some(sp(n*)), raw))

  val causal: Vector[CausalEdge] = Vector(
    causal(
      S.hearWarCries,
      CausalRelation.Causes,
      S.thinkWarParty,
      EpistemicStatus.WorldKnowledgeInferred,
      0.9,
      4,
      5
    ),
    causal(
      S.thinkWarParty,
      CausalRelation.Causes,
      S.hide,
      EpistemicStatus.WorldKnowledgeInferred,
      0.9,
      5,
      6
    ),
    causal(
      S.mightBeKilled,
      CausalRelation.Causes,
      S.notGoAlong,
      EpistemicStatus.WorldKnowledgeInferred,
      0.8,
      15
    ),
    causal(
      S.reportedShot,
      CausalRelation.Causes,
      S.letUsGoHome,
      EpistemicStatus.LinguisticallyEntailed,
      0.9,
      30
    ),
    causal(
      S.warriorsSayGoHome,
      CausalRelation.Causes,
      S.ym2ConcludesGhosts,
      EpistemicStatus.WorldKnowledgeInferred,
      0.7,
      30,
      31
    ),
    causal(
      S.notFeelSick,
      CausalRelation.Enables,
      S.ym2ConcludesGhosts,
      EpistemicStatus.Hypothesized,
      0.6,
      31,
      32
    ),
    causal(
      S.fellowShot,
      CausalRelation.Causes,
      S.carryIntoCanoe,
      EpistemicStatus.LinguisticallyEntailed,
      0.95,
      28
    ),
    // the cause of death is left open: two competing hypotheses, neither explicit. The wound
    // hypothesis runs from the *narrated-world* hypothesized injury, not from the warriors' words
    // and not from the belief content; the rival is that going with the ghosts itself killed him.
    causal(
      S.ym2Injured,
      CausalRelation.Causes,
      S.dead,
      EpistemicStatus.Hypothesized,
      0.5,
      30,
      47
    ),
    causal(
      S.ym2Accompanies,
      CausalRelation.Causes,
      S.dead,
      EpistemicStatus.Hypothesized,
      0.3,
      19,
      47
    )
  )

  // goals -------------------------------------------------------------------------------------

  val goals: Vector[GoalEdge] = Vector(
    GoalEdge(
      S.announcedWar,
      GoalRelation.Motivates,
      S.warriorsGo,
      meta("goal:war:go", EpistemicStatus.WorldKnowledgeInferred, Some(sp(12, 23)), 0.8)
    ),
    GoalEdge(
      S.battle,
      GoalRelation.Achieves,
      S.announcedWar,
      meta("goal:battle:war", EpistemicStatus.StructurallyDerived, Some(sp(12, 26)), 0.9)
    ),
    GoalEdge(
      S.wishTakeAlong,
      GoalRelation.Motivates,
      S.warriorsSpeak,
      meta("goal:wish:speak", EpistemicStatus.WorldKnowledgeInferred, Some(sp(10, 11)), 0.7)
    )
  )

  // state changes -----------------------------------------------------------------------------

  val stateChanges: Vector[StateChangeEdge] = Vector(
    StateChangeEdge(
      S.fallsDown,
      StateChangeKind.Initiates,
      S.dead,
      meta("sc:fall:dead", EpistemicStatus.WorldKnowledgeInferred, Some(sp(44, 47)), 0.7)
    ),
    StateChangeEdge(
      S.sunRises,
      StateChangeKind.Terminates,
      S.nearlyDaylight,
      meta("sc:sun:daylight", EpistemicStatus.LinguisticallyEntailed, Some(sp(43, 44)), 0.9)
    ),
    // the belief change: concluding they are ghosts ends the belief that they are people
    StateChangeEdge(
      S.ym2ConcludesGhosts,
      StateChangeKind.Terminates,
      S.warriorsArePeople,
      meta("sc:conclude:people", EpistemicStatus.WorldKnowledgeInferred, Some(sp(27, 31)), 0.8)
    ),
    StateChangeEdge(
      S.ym2ConcludesGhosts,
      StateChangeKind.Initiates,
      S.warriorsAreGhosts,
      meta("sc:conclude:ghosts", EpistemicStatus.LinguisticallyEntailed, Some(sp(31)), 0.9)
    )
  )

  // references --------------------------------------------------------------------------------

  private def ref(
      a: SituationId,
      mode: NarrativeReference,
      b: SituationId,
      n: Int*
  ): ReferenceEdge =
    ReferenceEdge(
      a,
      mode,
      b,
      meta(s"ref:${a.value}:${b.value}", EpistemicStatus.LinguisticallyEntailed, Some(sp(n*)), 0.9)
    )

  val references: Vector[ReferenceEdge] = Vector(
    ref(S.announcedWar, NarrativeReference.Prospective, S.battle, 12, 26),
    ref(S.relativeLeftMe, NarrativeReference.Retrospective, S.ym2Accompanies, 21, 19),
    ref(S.ym2WentWithWarriors, NarrativeReference.Retrospective, S.ym2Accompanies, 22, 19),
    ref(S.accompaniedGhosts, NarrativeReference.Retrospective, S.ym2Accompanies, 38, 19),
    ref(S.recounting, NarrativeReference.Summary, S.battle, 38, 26),
    ref(S.weFought, NarrativeReference.Retrospective, S.battle, 39, 26),
    ref(S.manyFellowsKilled, NarrativeReference.Partial, S.fellowShot, 40, 28),
    ref(S.theySaidShot, NarrativeReference.Retrospective, S.warriorsSayGoHome, 41, 30),
    ref(S.iWasShot, NarrativeReference.Retrospective, S.reportedShot, 41, 30),
    ref(S.iDidNotFeelSick, NarrativeReference.Retrospective, S.notFeelSick, 41, 32),
    // the warriors' report is *about* the (open) narrated-world injury
    ref(S.reportedShot, NarrativeReference.Partial, S.ym2Injured, 30)
  )

  // entity relations ---------------------------------------------------------------------------

  private def member(e: EntityId, group: EntityId, n: Int*): EntityEdge =
    EntityEdge(
      e,
      EntityRelation.MemberOf,
      group,
      meta(
        s"member:${e.value}:${group.value}",
        EpistemicStatus.LinguisticallyEntailed,
        Some(sp(n*)),
        0.95
      )
    )

  /** The two individuals are members of the pair; their joint actions attach to the group and reach
    * them through membership (§27.1: separate entities, shared participation).
    */
  val entityRelations: Vector[EntityEdge] = Vector(
    member(ym1, youngMen, 1, 13, 15),
    member(ym2, youngMen, 1, 17, 19),
    EntityEdge(
      fellow,
      EntityRelation.MemberOf,
      warriors,
      meta("member:fellow:warriors", EpistemicStatus.LinguisticallyEntailed, Some(sp(28)), 0.9)
    )
  )

  // hypotheses ----------------------------------------------------------------------------------

  /** The narrated-world truth of the injury stays open with both readings retained. */
  val hypotheses: Vector[HypothesisClaim] = Vector(
    HypothesisClaim(
      S.ym2Injured,
      Resolved(
        "the young man was in fact wounded in the fight",
        meta("hyp:ym2-injured", EpistemicStatus.Hypothesized, Some(sp(30, 32)), 0.5),
        Vector(
          ("no ordinary injury occurred; he felt nothing", Credence.unsafeRaw(0.5, HandScorer))
        )
      )
    )
  )

  // segments and hierarchy --------------------------------------------------------------------

  private def segment(
      id: SegmentId,
      kind: SegmentKind,
      level: Int,
      summary: String,
      from: Int,
      to: Int
  ): SegmentNode =
    val support = sp((from to to)*)
    SegmentNode(
      id,
      kind,
      level,
      explicit(s"seg:${id.value}", support),
      SegmentSummary.Stated(resolved(s"seg:${id.value}:summary", summary, support)),
      support
    )

  val segments: Vector[SegmentNode] = Vector(
    segment(
      G.story,
      SegmentKind.Story,
      3,
      "a young man goes to war with strangers who prove to be ghosts, tells of it, and dies at sunrise",
      0,
      49
    ),
    segment(
      G.ep1,
      SegmentKind.Episode,
      2,
      "two young men hunting on the river hear war cries and are approached by a canoe",
      0,
      9
    ),
    segment(
      G.ep2,
      SegmentKind.Episode,
      2,
      "invitation, one refusal and one acceptance, the journey and the battle",
      10,
      29
    ),
    segment(
      G.ep3,
      SegmentKind.Episode,
      2,
      "the retreat, the realization, the recounting at home, and the death",
      30,
      49
    ),
    segment(G.sc1a, SegmentKind.Scene, 1, "night seal hunt on a foggy, calm river", 0, 3),
    segment(G.sc1b, SegmentKind.Scene, 1, "war cries, hiding behind a log, canoes approach", 4, 7),
    segment(G.sc1c, SegmentKind.Scene, 1, "one canoe with five men comes up to them", 8, 9),
    segment(
      G.sc2a,
      SegmentKind.Scene,
      1,
      "the invitation to war, the excuse, and the refusal",
      10,
      18
    ),
    segment(
      G.sc2b,
      SegmentKind.Scene,
      1,
      "one goes with the strangers, the other goes home and reports",
      19,
      22
    ),
    segment(G.sc2c, SegmentKind.Scene, 1, "the journey to Kalama and the fight", 23, 29),
    segment(
      G.sc3a,
      SegmentKind.Scene,
      1,
      "the call to go home, the ghost conclusion, the return to Egulac",
      30,
      36
    ),
    segment(
      G.sc3b,
      SegmentKind.Scene,
      1,
      "at the house he tells everything and falls silent as daylight nears",
      37,
      43
    ),
    segment(G.sc3c, SegmentKind.Scene, 1, "at sunrise he collapses and dies", 44, 49)
  )

  private def contain(member: NarrativeMember, parent: SegmentId): ContainmentEdge =
    ContainmentEdge(
      member,
      parent,
      HierarchyKind.PrimarySegmentation,
      1.0,
      meta(
        s"contain:${member.render}:${parent.value}",
        EpistemicStatus.HumanAdjudicated,
        None,
        1.0
      )
    )

  private def sceneOf(id: SituationId): SegmentId =
    val n = atlas.sentences.indexWhere(u =>
      u.span.contains(situations.find(_.id == id).get.support.textSpans.get.refs.head.span.start)
    )
    if n <= 3 then G.sc1a
    else if n <= 7 then G.sc1b
    else if n <= 9 then G.sc1c
    else if n <= 18 then G.sc2a
    else if n <= 22 then G.sc2b
    else if n <= 29 then G.sc2c
    else if n <= 36 then G.sc3a
    else if n <= 43 then G.sc3b
    else G.sc3c

  val containment: Vector[ContainmentEdge] =
    Vector(G.ep1, G.ep2, G.ep3).map(e => contain(NarrativeMember.Segment(e), G.story)) ++
      Vector(G.sc1a, G.sc1b, G.sc1c).map(s => contain(NarrativeMember.Segment(s), G.ep1)) ++
      Vector(G.sc2a, G.sc2b, G.sc2c).map(s => contain(NarrativeMember.Segment(s), G.ep2)) ++
      Vector(G.sc3a, G.sc3b, G.sc3c).map(s => contain(NarrativeMember.Segment(s), G.ep3)) ++
      situations.map(s => contain(NarrativeMember.Situation(s.id), sceneOf(s.id))) :+
      // auxiliary thread: the battle motif recurs from announcement to recounting
      ContainmentEdge(
        NarrativeMember.Situation(S.weFought),
        G.sc2c,
        HierarchyKind.Theme,
        0.5,
        meta("contain:theme:we-fought", EpistemicStatus.Hypothesized, Some(sp(39)), 0.5)
      )

  private def boundary(afterSentence: Int, level: Int, raw: Double): BoundaryBelief =
    BoundaryBelief(
      sent(afterSentence).id,
      level,
      raw,
      None,
      NonEmptyVector.one(
        Evidence(
          EvidenceId.unsafe(s"wog:ev:boundary:$afterSentence:$level"),
          Some(sp(afterSentence, afterSentence + 1)),
          Set.empty,
          Annotator,
          Stage
        )
      )
    )

  val boundaryBeliefs: Vector[BoundaryBelief] = Vector(
    boundary(9, 2, 0.9),
    boundary(29, 2, 0.95),
    boundary(3, 1, 0.7),
    boundary(7, 1, 0.6),
    boundary(18, 1, 0.8),
    boundary(22, 1, 0.9),
    boundary(36, 1, 0.8),
    boundary(43, 1, 0.85),
    // a rejected candidate: a scene break at the start of the fight
    boundary(25, 1, 0.3)
  )

  val hierarchy: NarrativeHierarchy = NarrativeHierarchy(containment, boundaryBeliefs)

  val descriptors: Vector[DescriptorClaim] = Vector(
    DescriptorClaim(
      G.story,
      DescriptorKind.Theme,
      "the supernatural is recognized only after the fact",
      meta("desc:theme:supernatural", EpistemicStatus.Hypothesized, Some(sp(27, 31)), 0.7)
    ),
    DescriptorClaim(
      G.story,
      DescriptorKind.Motif,
      "travel on the river",
      meta("desc:motif:river", EpistemicStatus.Hypothesized, Some(sp(2, 12, 36)), 0.7)
    ),
    DescriptorClaim(
      G.ep3,
      DescriptorKind.Summary,
      "he returns, tells the story, and dies at dawn",
      meta("desc:summary:ep3", EpistemicStatus.HumanAdjudicated, Some(sp(30, 49)), 1.0)
    )
  )

  val graph: NarrativeGraph = NarrativeGraph(
    entities.map(e => e.id -> e).toMap,
    situations.map(s => s.id -> s).toMap,
    segments.map(s => s.id -> s).toMap,
    contexts.map(c => c.id -> c).toMap,
    RelationLayers(participants, temporal, causal, goals, stateChanges, references, entityRelations)
  )

  val trajectory: DiscourseTrajectory = DiscourseTrajectory
    .derive(graph, hierarchy, atlas)
    .fold(error => throw new IllegalArgumentException(error.message), identity)

  val draft: StoryModel[ModelStatus.Draft] =
    StoryModel
      .draft(
        source,
        atlas,
        graph,
        hierarchy,
        trajectory,
        descriptors = descriptors,
        hypotheses = hypotheses
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)

  val validation: ValidationOutcome = StoryValidator.validate(draft, ValidationPolicy.default)

  /** The validated fixture. Throws with the full report if the hand model violates a law, which is
    * the intended failure mode for a fixture that must always be law-abiding.
    */
  val model: StoryModel[ModelStatus.Validated] =
    validation.validated.getOrElse(
      throw new IllegalStateException(
        "War of the Ghosts fixture failed validation:\n" + validation.report.render
      )
    )

  val alignmentSource: AlignmentSource = AlignmentSource(model)
