package storymodel4s.view

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import storymodel4s.align.{AlignRef, AlignState, ExternalState}
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.EntityK
import storymodel4s.document.DocRef
import storymodel4s.features.FeatureAddress
import storymodel4s.recall.{RecallRef, RecallUnitId}
import storymodel4s.story.StoryRef

class ViewRefSuite extends ScalaCheckSuite:
  private val references: Vector[ViewRef] = Vector(
    ViewRef.Core(CoreRef.Claim(ClaimId.unsafe("claim:view-ref"))),
    ViewRef.Feature(FeatureAddress.Space(FeatureSpaceId.unsafe("sensory:auditory"))),
    ViewRef.Story(StoryRef.Situation(SituationId.unsafe("situation:view-ref"))),
    ViewRef.Doc(DocRef.EntityMention(MentionId.unsafe[EntityK]("mention:view-ref"))),
    ViewRef.Recall(RecallRef.Unit(RecallUnitId.unsafe("recall:view-ref"))),
    ViewRef.Align(
      AlignRef.Cell(
        RecallUnitId.unsafe("recall:view-ref"),
        AlignState.External(ExternalState.Commentary)
      )
    )
  )

  test("every supported module address re-types without losing identity"):
    references.foreach { reference =>
      assertEquals(ViewRef.parse(reference.address), Some(reference))
      assertEquals(Address.parse(reference.address.render), Right(reference.address))
    }

  private val generatedFamilies: Gen[Vector[ViewRef]] =
    val id = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.take(48).mkString)
    for
      core <- id.map(value => ViewRef.Core(CoreRef.Claim(ClaimId.unsafe(value))))
      feature <- id.map(value =>
        ViewRef.Feature(FeatureAddress.Space(FeatureSpaceId.unsafe(value)))
      )
      story <- id.map(value => ViewRef.Story(StoryRef.Situation(SituationId.unsafe(value))))
      document <- id.map(value =>
        ViewRef.Doc(DocRef.EntityMention(MentionId.unsafe[EntityK](value)))
      )
      recallId <- id
      recall = ViewRef.Recall(RecallRef.Unit(RecallUnitId.unsafe(recallId)))
      external <- Gen.oneOf(ExternalState.values.toSeq)
      align = ViewRef.Align(
        AlignRef.Cell(RecallUnitId.unsafe(recallId), AlignState.External(external))
      )
    yield Vector(core, feature, story, document, recall, align)

  property("generated addresses round-trip through every one of the six module tags"):
    forAll(generatedFamilies) { generated =>
      generated.length == 6 &&
      generated.map(_.address.tag).distinct.length == 6 &&
      generated.forall { reference =>
        ViewRef.parse(reference.address).map(_.address).contains(reference.address)
      }
    }

  test("the supported module tags are distinct"):
    assertEquals(references.map(_.address.tag).distinct.size, references.size)

  test("foreign tags and malformed known-module keys do not re-type"):
    val foreign = Address(
      ModuleTag.unsafe("outside"),
      AddressKind.unsafe("object"),
      AddressKey.of("x")
    )
    val malformedCore = Address(CoreRef.Tag, AddressKind.unsafe("claim"), AddressKey.of("a", "b"))

    assertEquals(ViewRef.parse(foreign), None)
    assertEquals(ViewRef.parse(malformedCore), None)
