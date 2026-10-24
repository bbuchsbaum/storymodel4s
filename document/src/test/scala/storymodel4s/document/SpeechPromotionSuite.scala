package storymodel4s.document

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.{EntityK, SituationK}
import storymodel4s.story.*

/** Laws of ADR 0026: a speech assertion is promoted only when every condition holds. The fixture is
  * the motivating case: someone says she is dead, in a film whose speech is anchored in media time.
  */
class SpeechPromotionSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private val film: SourceBundle = right(
    SourceBundle.filmEdition(
      EditionId.unsafe("promotion-film"),
      Checksum.ofText("promotion-picture"),
      0L,
      1000L,
      RationalTimebase.Millisecond
    )
  )
  private val stream = film.streams.head.id
  private def ivs(start: Long, end: Long): PlaybackIntervalSet =
    PlaybackIntervalSet.one(right(PlaybackInterval.on(film.primaryAxis, start, end)))
  private def evidence(anchors: EvidenceAnchor*): EvidenceSupport =
    right(EvidenceSupport.of(film, anchors.toVector))
  private def spoken(start: Long, end: Long): TypedSupport = TypedSupport.Anchored(
    evidence(EvidenceAnchor.MediaTime(film.id, stream, film.primaryAxis.id, ivs(start, end)))
  )
  private def onTrack(track: String, start: Long, end: Long): TypedSupport = TypedSupport.Anchored(
    evidence(EvidenceAnchor.Track(film.id, stream, TrackId.unsafe(track), ivs(start, end)))
  )

  private val fp = Fingerprint.unsafe("test:promotion")
  private val stage = StageId.unsafe("test")
  private val prov = Provenance.deterministic("test", Checksum.ofText("test"))
  private def meta(
      key: String,
      status: EpistemicStatus = EpistemicStatus.Hypothesized,
      anchors: Option[EvidenceSupport] = None
  ): ClaimMeta = ClaimMeta.unsafe(
    ClaimId.unsafe(s"c:$key"),
    status,
    Credence.unmeasured,
    NonEmptyVector.one(Evidence(EvidenceId.unsafe(s"e:$key"), None, Set.empty, fp, stage, anchors)),
    prov
  )

  private val world = ContextId.unsafe("ctx:world")
  private val speech = ContextId.unsafe("ctx:speech")
  private val rival = ContextId.unsafe("ctx:rival")
  private val belief = ContextId.unsafe("ctx:belief")
  private val nested = ContextId.unsafe("ctx:nested")
  private val woman = EntityId.unsafe("ent:woman")
  private val speaker = ContextHolder.Unattributed(HolderGap.NoCandidate)
  private def frame(id: ContextId, parent: Option[ContextId], kind: ContextKind, at: TypedSupport) =
    id -> ContextFrame(id, parent, kind, at, meta(id.value))
  private val contexts = Map(
    frame(world, None, ContextKind.NarratedWorld, spoken(0L, 1000L)),
    frame(speech, Some(world), ContextKind.Speech(speaker), spoken(340L, 390L)),
    frame(rival, Some(world), ContextKind.Speech(speaker), spoken(600L, 650L)),
    frame(belief, Some(world), ContextKind.Belief(speaker), spoken(700L, 800L)),
    frame(nested, Some(belief), ContextKind.Speech(speaker), spoken(720L, 760L))
  )
  private val entity = EntityNode(
    woman,
    Resolved("the woman", meta("ent:woman:label"), Vector.empty),
    EntityType.Person,
    NonEmptyVector.one(MentionId.unsafe[EntityK]("m:woman")),
    Vector.empty,
    spoken(300L, 800L),
    meta("ent:woman")
  )
  private def state(
      key: String,
      context: ContextId,
      at: TypedSupport,
      polarity: Polarity = Polarity.Positive,
      modality: Modality = Modality.Reported,
      lemma: String = "dead"
  ): SituationNode = SituationNode.State(
    StateNode(
      SituationId.unsafe(key),
      Predicate(lemma, None, lemma),
      key,
      context,
      polarity,
      modality,
      at,
      NonEmptyVector.one(MentionId.unsafe[SituationK](s"m:$key")),
      meta(key)
    )
  )
  private val twin = state("sit:dead", speech, spoken(350L, 380L))
  private def theme(s: SituationNode) =
    ParticipantEdge(s.id, ParticipantRole.Theme, woman, meta(s"p:${s.id.value}"))

  /** A validated model holding the twin plus `extra` situations, each with the woman as theme. */
  private def model(
      extra: Vector[SituationNode] = Vector.empty,
      hypotheses: Vector[HypothesisClaim] = Vector.empty,
      base: SituationNode = twin,
      person: EntityNode = entity
  ): StoryModel[ModelStatus.Validated] =
    val situations = base +: extra
    val graph = NarrativeGraph(
      Map(woman -> person),
      situations.map(s => s.id -> s).toMap,
      Map.empty,
      contexts,
      RelationLayers.empty.copy(participants = situations.map(theme))
    )
    val draft = right(
      StoryModel.draft(
        right(AnchoredNarrativeAtlas.of(film, Vector.empty)),
        graph,
        NarrativeHierarchy.empty,
        DiscourseTrajectory.empty,
        hypotheses = hypotheses
      )
    )
    val outcome = StoryValidator.validate(draft)
    outcome.validated.getOrElse(fail(outcome.report.violations.mkString("\n")))

  private def rid(raw: String) = ReferentMentionId.unsafe(raw)
  private def dialogue(raw: String, context: ContextId, at: TypedSupport): ReferentMention =
    at match
      case TypedSupport.Anchored(s) =>
        right(ReferentMention.anchored(rid(raw), MentionOrigin.Dialogue(context), film, s, None))
      case TypedSupport.Text(_) => fail("dialogue fixtures are anchored")
  private val she = dialogue("d:she", speech, spoken(355L, 365L))
  private def resolution(
      mentions: Vector[ReferentMention] = Vector(she),
      identities: ReferentContext => Vector[IdentityLink] = _ => Vector.empty,
      distinctions: ReferentContext => Vector[DistinctLink] = _ => Vector.empty
  ): ReferentResolution =
    val ctx = right(ReferentContext.of(mentions, Vector.empty, Vector.empty))
    right(ReferentIdentity.resolve(ctx, identities(ctx), distinctions(ctx), Vector.empty))
  private val bound = Map(woman -> she.id)
  private def promote(
      m: StoryModel[ModelStatus.Validated] = model(),
      target: SituationId = twin.id,
      bindings: Map[EntityId, ReferentMentionId] = bound,
      r: ReferentResolution = resolution(),
      contestations: Vector[Contestation] = Vector.empty
  ) = ContestationSet.of(m, contestations).flatMap(SpeechPromotion.of(m, target, bindings, r, _))
  private def refused(result: Either[PromotionError, ?], fragment: String): Unit = result match
    case Left(e)  => assert(e.message.contains(fragment), e.message)
    case Right(v) => fail(s"expected a refusal containing '$fragment', got $v")
  private def contest(basis: ContestationBasis, target: SituationId = twin.id): Contestation =
    right(Contestation.of(target, basis, meta(s"contest:$basis", anchors = Some(evidence(
      EvidenceAnchor.MediaTime(film.id, stream, film.primaryAxis.id, ivs(900L, 950L))
    )))))

  test("control: an uncontested assertion by an unattributed speaker is promoted"):
    val p = right(promote())
    assertEquals(p.twin, twin.id)
    assertEquals(p.speech, speech)
    assertEquals(p.polarity, Polarity.Positive)
    assertEquals(p.participants, Vector((ParticipantRole.Theme, woman, she.id)))
    assertEquals(p.rule, "uncontested-assertion/v1")

  test("a promotion is WorldKnowledgeInferred, cites its twin and stays out of the model"):
    val m = model()
    val p = right(promote(m))
    assertEquals(p.meta.status, EpistemicStatus.WorldKnowledgeInferred)
    assertEquals(p.meta.evidence.toVector.flatMap(_.upstream), Vector(twin.meta.id))
    assert(!m.claims.exists(_.id == p.meta.id))
    assert(!m.graph.situations.values.exists(_.context == world))

  test("promotion is deterministic"):
    assertEquals(right(promote()).meta.id, right(promote()).meta.id)

  test("a situation in the narrated world is not a speech assertion"):
    val root = state("sit:root", world, spoken(100L, 120L), lemma = "fall")
    refused(promote(model(Vector(root)), root.id), "not in a speech context")

  test("an unknown situation is refused"):
    refused(promote(target = SituationId.unsafe("sit:none")), "no situation")

  test("speech nested in a belief is refused"):
    val inner = state("sit:inner", nested, spoken(725L, 750L), lemma = "gone")
    val m = model(Vector(inner))
    val heard = dialogue("d:inner", nested, spoken(730L, 740L))
    refused(
      promote(m, inner.id, Map(woman -> heard.id), resolution(Vector(she, heard))),
      "embedded in Belief"
    )

  test("a possible assertion is refused"):
    val maybe = state("sit:dead", speech, spoken(350L, 380L), modality = Modality.Possible)
    refused(promote(model(base = maybe)), "not an asserted claim")

  test("an assertion of unknown polarity is refused"):
    val unsure = state("sit:dead", speech, spoken(350L, 380L), polarity = Polarity.Unknown)
    refused(promote(model(base = unsure)), "not an asserted claim")

  test("an unbound participant is refused"):
    refused(promote(bindings = Map.empty), "not bound")

  test("a binding for an entity that does not take part is refused"):
    refused(
      promote(bindings = bound + (EntityId.unsafe("ent:other") -> she.id)),
      "is bound but is not a participant"
    )

  test("a mention missing from the referent context is refused"):
    refused(promote(bindings = Map(woman -> rid("d:absent"))), "not in the referent context")

  test("a dialogue mention of another speech context is refused"):
    val elsewhere = dialogue("d:else", rival, spoken(355L, 365L))
    refused(
      promote(bindings = Map(woman -> elsewhere.id), r = resolution(Vector(she, elsewhere))),
      "not a dialogue mention of this speech context"
    )

  test("a dialogue mention outside the utterance is refused"):
    val late = dialogue("d:late", speech, spoken(382L, 388L))
    refused(
      promote(bindings = Map(woman -> late.id), r = resolution(Vector(she, late))),
      "outside the utterance"
    )

  test("a dialogue mention outside the entity's own support is refused"):
    val far = entity.copy(support = spoken(0L, 300L))
    refused(promote(model(person = far)), "outside the entity's own support")

  test("a mention whose identity is open is refused"):
    // Two simultaneous visual tracks are distinct; a shared name joins "she" to both, so her class
    // is open.
    val name = right(ProperName.of("Ana"))
    def visual(raw: String, track: String): ReferentMention = onTrack(track, 340L, 390L) match
      case TypedSupport.Anchored(s) =>
        right(ReferentMention.anchored(rid(raw), MentionOrigin.Visual, film, s, Some(name)))
      case TypedSupport.Text(_) => fail("visual fixtures are anchored")
    val named = right(
      ReferentMention.anchored(
        rid("d:ana"),
        MentionOrigin.Dialogue(speech),
        film,
        evidence(EvidenceAnchor.MediaTime(film.id, stream, film.primaryAxis.id, ivs(355L, 365L))),
        Some(name)
      )
    )
    val (v1, v2) = (visual("v:1", "t1"), visual("v:2", "t2"))
    val r = resolution(
      Vector(named, v1, v2),
      ctx =>
        Vector(v1, v2).map(v =>
          right(IdentityLink.of(ctx, named.id, v.id, IdentityBasis.NamedReference(name)))
        ),
      ctx => Vector(right(DistinctLink.of(ctx, v1.id, v2.id, DistinctnessBasis.SimultaneousTracks)))
    )
    refused(promote(bindings = Map(woman -> named.id), r = r), "is open")

  test("an opposite assertion in the narrated world contests the claim"):
    val alive = state("sit:alive", world, spoken(500L, 520L), polarity = Polarity.Negative)
    refused(promote(model(Vector(alive))), "the narrated world holds sit:alive")

  test("an opposite assertion in another speech contests the claim"):
    val denial = state("sit:denial", rival, spoken(610L, 640L), polarity = Polarity.Negative)
    refused(promote(model(Vector(denial))), "speech sit:denial asserts the opposite")

  test("control: a same-polarity root claim or another predicate does not contest it"):
    val agree = state("sit:agree", world, spoken(500L, 520L))
    val other = state("sit:other", world, spoken(530L, 540L), Polarity.Negative, lemma = "asleep")
    assert(promote(model(Vector(agree, other))).isRight)

  test("a hypothesis on the same content keeps it open"):
    val hyp = state("sit:hyp", world, spoken(500L, 520L), modality = Modality.Asserted)
      match
        case SituationNode.State(n) =>
          SituationNode.State(n.copy(meta = meta("sit:hyp", EpistemicStatus.Hypothesized)))
        case other => other
    val reading = Resolved("dead", meta("hyp:dead"), Vector(("alive", Credence.unmeasured)))
    val m = model(Vector(hyp), Vector(HypothesisClaim(hyp.id, reading)))
    refused(promote(m), "hypothesis on sit:hyp keeps it open")

  test("a deception recorded against the utterance contests it"):
    refused(promote(contestations = Vector(contest(ContestationBasis.Deception))), "Deception")

  test("control: a contestation of another situation does not contest it"):
    val other = state("sit:other", rival, spoken(610L, 640L), lemma = "asleep")
    val m = model(Vector(other))
    assert(promote(m, contestations = Vector(contest(ContestationBasis.Retraction, other.id))).isRight)

  test("a contestation of a situation outside speech is refused"):
    val root = state("sit:root", world, spoken(100L, 120L), lemma = "fall")
    refused(
      ContestationSet.of(model(Vector(root)), Vector(contest(ContestationBasis.Correction, root.id))),
      "which is not speech"
    )

  test("a contestation set checked against another model is refused"):
    val other = state("sit:other", rival, spoken(610L, 640L), lemma = "asleep")
    val foreign = right(ContestationSet.of(model(Vector(other)), Vector.empty))
    refused(SpeechPromotion.of(model(), twin.id, bound, resolution(), foreign), "another model")

  test("a promotion holds only in the model it was checked against"):
    val p = right(promote())
    assert(p.holdsIn(model()))
    val alive = state("sit:alive", world, spoken(500L, 520L), polarity = Polarity.Negative)
    assert(!p.holdsIn(model(Vector(alive))))

  test("a root claim of undetermined polarity contests it"):
    val open = state("sit:open", world, spoken(500L, 520L), polarity = Polarity.Unknown)
    refused(promote(model(Vector(open))), "the narrated world holds sit:open")

  test("a contestation of a situation absent from the model is refused"):
    refused(
      promote(contestations = Vector(contest(ContestationBasis.Correction, SituationId.unsafe("x")))),
      "absent from the model"
    )

  test("a contestation must cite evidence"):
    assertEquals(
      Contestation.of(twin.id, ContestationBasis.Deception, meta("bare")).map(_.target),
      Left(PromotionError.UncitedContestation(twin.id))
    )

  test("the contestation set enters the promotion's identity, in any order"):
    val other = state("sit:other", rival, spoken(610L, 640L), lemma = "asleep")
    val m = model(Vector(other))
    val a = contest(ContestationBasis.Retraction, other.id)
    val b = contest(ContestationBasis.Correction, other.id)
    val none = right(promote(m)).meta.id
    val ab = right(promote(m, contestations = Vector(a, b)))
    val ba = right(promote(m, contestations = Vector(b, a)))
    assertNotEquals(ab.meta.id, none)
    assertEquals(ab.meta.id, ba.meta.id)
    assertEquals(ab.contestations, ba.contestations)
