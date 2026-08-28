package storymodel4s.fixtures.interview

import storymodel4s.core.*
import storymodel4s.features.Estimate
import storymodel4s.interview.*
import storymodel4s.interview.scoring.*
import storymodel4s.story.ModelStatus

/** A short synthetic Autobiographical-Interview transcript (design record §66) with invented,
  * neutral content: a target dinner at a named restaurant, weather and visual detail, a habitual
  * family statement, a different prior-year trip, an explicit retrieval failure, and a cake →
  * singing → embarrassment causal chain, across free recall, a general probe, and a specific probe.
  *
  * Speaker labels sit in their own paragraphs so that every utterance paragraph is exactly one
  * turn's support in the shared transcript atlas.
  */
object BirthdayInterview:
  val interviewer: SpeakerId = SpeakerId.unsafe("interviewer")
  val participant: SpeakerId = SpeakerId.unsafe("participant")

  val generalProbe: PromptId = PromptId.unsafe("probe:general:1")
  val specificProbe: PromptId = PromptId.unsafe("probe:specific:1")

  val cueText = "Tell me about a specific birthday, one particular birthday you remember well."

  val freeRecall: String =
    "My fortieth birthday. We went out for dinner at a small French restaurant called Maison " +
      "Bleue on Queen Street. It was raining that night and the windows were all fogged up. " +
      "My family always goes out for birthdays, that is just what we do. The year before we had " +
      "gone to Montreal for a long weekend. I can't remember what I ordered. Then the waiter " +
      "came out with a cake with candles and everyone in the room started singing, so I felt " +
      "horribly embarrassed."

  val generalProbeText = "Is there anything else you can tell me about that birthday?"

  val generalResponse: String =
    "It was a really nice evening. My sister Claire had organised it and she gave a little " +
      "speech. I think we walked home afterwards but I'm not sure."

  val specificProbeText = "Can you tell me more about what you saw and heard at the restaurant?"

  val specificResponse: String =
    "I can still see the candles on the cake and the fogged windows behind Claire. The room " +
      "smelled of butter and garlic. The cake was chocolate, I remember that clearly."

  /** Utterances in order with their speaker, phase, and the prompt they deliver or answer. */
  private val script: Vector[(SpeakerId, String, InterviewPhase, Option[PromptId])] = Vector(
    (interviewer, cueText, InterviewPhase.FreeRecall, None),
    (participant, freeRecall, InterviewPhase.FreeRecall, None),
    (interviewer, generalProbeText, InterviewPhase.GeneralProbe, Some(generalProbe)),
    (participant, generalResponse, InterviewPhase.GeneralProbe, Some(generalProbe)),
    (interviewer, specificProbeText, InterviewPhase.SpecificProbe, Some(specificProbe)),
    (participant, specificResponse, InterviewPhase.SpecificProbe, Some(specificProbe))
  )

  private def label(s: SpeakerId): String =
    if s == interviewer then "Interviewer:" else "Participant:"

  val text: String =
    script.map { case (s, u, _, _) => s"${label(s)}\n\n$u" }.mkString("\n\n")

  val source: StorySource =
    StorySource
      .fromText(text, Some("Birthday interview (synthetic)"), metadata = Map("synthetic" -> "true"))
      .fold(e => throw new IllegalStateException(e.message), identity)

  val atlas: SurfaceAtlas = SurfaceAnalyzer.analyze(source)

  val turns: Vector[TranscriptTurn] =
    val canonical = source.canonicalText
    var searchFrom = 0
    var audioAt = 0L
    script.zipWithIndex.map { case ((speaker, utterance, phase, prompt), i) =>
      val start = canonical.indexOf(utterance, searchFrom)
      require(start >= 0, s"utterance $i not found in canonical text")
      val span = TextSpan.unsafe(start, start + utterance.length)
      searchFrom = span.endExclusive
      val durationMillis = utterance.split("\\s+").length * 400L
      val audio = AudioSpan.unsafe(audioAt, audioAt + durationMillis)
      audioAt += durationMillis + 500L
      TranscriptTurn(
        TurnId.unsafe(s"turn:$i"),
        speaker,
        SpanSet.one(span),
        Some(audio),
        Some(phase),
        prompt
      )
    }

  val transcript: TranscriptAtlas =
    TranscriptAtlas(
      atlas,
      turns,
      Map(interviewer -> SpeakerRole.Interviewer, participant -> SpeakerRole.Participant)
    )

  val interviewSource: InterviewSource =
    InterviewSource
      .validated(
        InterviewSource(
          transcript,
          Cue(cueText, None, Some("fortieth birthday dinner")),
          Vector(
            Probe(generalProbe, ProbeKind.General, TurnId.unsafe("turn:2")),
            Probe(specificProbe, ProbeKind.Specific, TurnId.unsafe("turn:4"))
          ),
          Some(SubjectiveRatings(Some(Estimate.observed(0.8)), Some(Estimate.observed(0.7)), None))
        )
      )
      .fold(e => throw new IllegalStateException(e.message), identity)

  /** Run the whole v0.1 pipeline; deterministic. */
  def build(config: InductionConfig = InductionConfig()): InterviewModel[ModelStatus.Validated] =
    val segmented = InterviewSegmenter.segment(interviewSource)
    val graph = segmented.graph
    val unitDetails = graph.ordered.flatMap(u => AtomProjection.fromUnit(u, segmented.turnOf(u.id)))
    val relational = AtomProjection.fromRelations(graph, segmented.turnOf.get)
    val details = unitDetails ++ relational
    val result = TargetInduction.induce(graph, details, interviewSource.cue, config)
    val assessments = TargetInduction.assess(interviewSource, graph, details, result, config)
    val ledger = ClaimLedger.empty
      .addAll(assessments.map(_.meta))
      .fold(e => throw new IllegalStateException(e.message), identity)
    val draft = InterviewModel.draft(
      interviewSource,
      graph,
      details,
      assessments,
      result.target,
      result.alternatives,
      result.otherEpisodes,
      ledger
    )
    InterviewModel
      .validate(draft)
      .fold(
        errs => throw new IllegalStateException(errs.toNonEmptyList.toList.mkString("; ")),
        identity
      )

  lazy val model: InterviewModel[ModelStatus.Validated] = build()
  lazy val scores: AiCompatibleScores = TraditionalScoring.score(model)
  lazy val profile: Profile = ProfileScoring.profile(model)
