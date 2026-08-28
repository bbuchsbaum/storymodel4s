package storymodel4s.core

import munit.FunSuite

class TranscriptSuite extends FunSuite:
  private val text =
    "Tell me about a birthday you remember well.\n\n" +
      "It was my fortieth. We went to a small place on Queen Street. It rained the whole evening.\n\n" +
      "Can you tell me more about what you saw?\n\n" +
      "The windows were fogged up. I remember the red curtains."

  private val source = StorySource.fromText(text).toOption.get
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val interviewer = SpeakerId.unsafe("INT")
  private val participant = SpeakerId.unsafe("P01")
  private val probe = PromptId.unsafe("probe-specific-1")

  private def turn(
      id: String,
      sp: SpeakerId,
      para: Int,
      phase: InterviewPhase,
      prompt: Option[PromptId],
      audio: Option[AudioSpan]
  ) =
    TranscriptTurn(
      TurnId.unsafe(id),
      sp,
      SpanSet.one(atlas.paragraphs(para).span),
      audio,
      Some(phase),
      prompt
    )

  private val turns = Vector(
    turn(
      "t0",
      interviewer,
      0,
      InterviewPhase.FreeRecall,
      Some(PromptId.unsafe("cue")),
      Some(AudioSpan.unsafe(0, 4000))
    ),
    turn(
      "t1",
      participant,
      1,
      InterviewPhase.FreeRecall,
      None,
      Some(AudioSpan.unsafe(4500, 15000))
    ),
    turn(
      "t2",
      interviewer,
      2,
      InterviewPhase.SpecificProbe,
      Some(probe),
      Some(AudioSpan.unsafe(15000, 18000))
    ),
    turn(
      "t3",
      participant,
      3,
      InterviewPhase.SpecificProbe,
      None,
      Some(AudioSpan.unsafe(18500, 26000))
    )
  )
  private val roles =
    Map(interviewer -> SpeakerRole.Interviewer, participant -> SpeakerRole.Participant)
  private val transcript = TranscriptAtlas(atlas, turns, roles)

  test("valid transcript validates") {
    assert(TranscriptAtlas.validated(transcript).isRight)
  }

  test("participant tokens exclude interviewer words and cover both participant turns") {
    val words = transcript.participantTokens.map(atlas.text)
    assert(words.contains("fortieth"))
    assert(words.contains("curtains"))
    assert(!words.contains("Tell"))
    assertEquals(
      transcript.participantTokens.size,
      transcript.tokensOf(turns(1)).size + transcript.tokensOf(turns(3)).size
    )
  }

  test("tokensAfter(probe) is exactly the post-probe participant material") {
    val after = transcript.tokensAfter(probe).map(atlas.text)
    assert(after.contains("fogged"))
    assert(!after.contains("fortieth"))
    assert(!after.contains("saw"))
  }

  test("phase and speaker traversals") {
    assertEquals(transcript.turnsIn(InterviewPhase.FreeRecall).map(_.id.value), Vector("t0", "t1"))
    assertEquals(transcript.turnsBy(participant).map(_.id.value), Vector("t1", "t3"))
    assertEquals(transcript.turnsByRole(SpeakerRole.Interviewer).size, 2)
  }

  test("turnAt by text offset and by audio time agree") {
    val offset = atlas.paragraphs(3).span.start + 3
    assertEquals(transcript.turnAt(offset).map(_.id.value), Some("t3"))
    assertEquals(transcript.turnAtAudio(20000).map(_.id.value), Some("t3"))
    assertEquals(transcript.turnAtAudio(4200), None)
    assertEquals(transcript.phaseOf(offset), Some(InterviewPhase.SpecificProbe))
  }

  test("every token is covered by at most one turn") {
    val covered = turns.flatMap(t => transcript.tokensOf(t).map(_.id))
    assertEquals(covered.distinct.size, covered.size)
  }

  test("overlapping turns are rejected") {
    val bad = transcript.copy(turns =
      turns.updated(
        1,
        turns(1).copy(support =
          SpanSet.one(atlas.paragraphs(0).span.hull(atlas.paragraphs(1).span))
        )
      )
    )
    assert(TranscriptAtlas.validated(bad).isLeft)
  }

  test(
    "unordered turns, undeclared speakers, duplicate ids, and out-of-text support are rejected"
  ) {
    assert(TranscriptAtlas.validated(transcript.copy(turns = turns.reverse)).isLeft)
    assert(TranscriptAtlas.validated(transcript.copy(speakers = roles - participant)).isLeft)
    assert(TranscriptAtlas.validated(transcript.copy(turns = turns :+ turns(0))).isLeft)
    val far =
      turns(3).copy(support = SpanSet.one(TextSpan.unsafe(text.length + 5, text.length + 9)))
    assert(TranscriptAtlas.validated(transcript.copy(turns = turns.init :+ far)).isLeft)
  }

  test("audio order must agree with text order") {
    val swapped = turns.updated(3, turns(3).copy(audio = Some(AudioSpan.unsafe(100, 200))))
    assert(TranscriptAtlas.validated(transcript.copy(turns = swapped)).isLeft)
  }

  test("AudioSpan smart constructor") {
    assert(AudioSpan.of(-1, 5).isLeft)
    assert(AudioSpan.of(5, 4).isLeft)
    assertEquals(AudioSpan.unsafe(2, 7).durationMillis, 5L)
  }
