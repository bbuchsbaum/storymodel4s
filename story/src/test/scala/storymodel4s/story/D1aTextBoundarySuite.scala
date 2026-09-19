package storymodel4s.story

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*

class D1aTextBoundarySuite extends FunSuite:
  private val built = Small.build(2, 1)
  private val film = SourceBundle
    .filmEdition(
      EditionId.unsafe("boundary-film"),
      Checksum.ofText("film"),
      0L,
      100L,
      RationalTimebase.Millisecond
    )
    .toOption
    .get
  private val anchors = EvidenceSupport
    .media(
      film,
      film.streams.head.id,
      PlaybackIntervalSet.one(PlaybackInterval.on(film.primaryAxis, 1L, 2L).toOption.get)
    )
    .toOption
    .get
  private val context = built.graph.contexts(built.world)
  private val anchoredEvidence = context.meta.evidence.map(_.copy(anchors = Some(anchors)))

  test("accepting control: ordinary text draft and internal copy remain constructible"):
    val draft = built.draft()
    assertEquals(draft.copy[ModelStatus.Draft](), draft)
    assert(StoryValidator.validate(draft).validated.nonEmpty)

  test("text draft refuses anchored ordinary claim evidence before export"):
    val changed = context.copy(meta = context.meta.withEvidence(anchoredEvidence).toOption.get)
    val graph = built.graph.copy(contexts = built.graph.contexts.updated(built.world, changed))
    interceptMessage[IllegalArgumentException](
      "requirement failed: text StoryModel cannot carry anchored evidence"
    ) {
      built.draft(graph = graph)
    }

  test("text draft refuses anchored boundary evidence outside the claim ledger"):
    val hierarchy = built.hierarchy.copy(boundaryBeliefs =
      Vector(
        BoundaryBelief(
          built.atlas.sentences.head.id,
          0,
          0.5,
          None,
          NonEmptyVector.one(anchoredEvidence.head)
        )
      )
    )
    intercept[IllegalArgumentException](built.draft(hierarchy = hierarchy))

  test("internal copy cannot bypass anchored evidence refusal"):
    val draft = built.draft()
    val hierarchy = built.hierarchy.copy(boundaryBeliefs =
      Vector(BoundaryBelief(built.atlas.sentences.head.id, 0, 0.5, None, anchoredEvidence))
    )
    intercept[IllegalArgumentException](draft.copy[ModelStatus.Draft](hierarchy = hierarchy))
