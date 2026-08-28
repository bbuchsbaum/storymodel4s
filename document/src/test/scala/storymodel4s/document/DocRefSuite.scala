package storymodel4s.document

import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.*
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.*
import storymodel4s.proposition.ConceptId

/** Addressable laws for [[DocRef]] (mirrors `laws.AddressableLaws`; `laws` does not depend on
  * `document`).
  */
class DocRefSuite extends ScalaCheckSuite:
  val id: Gen[String] =
    Gen
      .nonEmptyListOf(Gen.oneOf(Gen.alphaNumChar, Gen.oneOf(':', '-', '_', '.', '/', '%')))
      .map(_.take(64).mkString)

  val docRef: Gen[DocRef] = Gen.oneOf(
    for
      s <- id
      c <- id
    yield DocRef.ChartNode(ChartNodeRef(SurfaceUnitId.unsafe(s), ConceptId.unsafe(c))),
    id.map(s => DocRef.EntityMention(MentionId.unsafe[EntityK](s))),
    id.map(s => DocRef.SituationMention(MentionId.unsafe[SituationK](s))),
    id.map(s => DocRef.SegmentMention(MentionId.unsafe[SegmentK](s))),
    id.map(s => DocRef.ContextMention(MentionId.unsafe[ContextK](s))),
    id.map(s => DocRef.EntityCanonical(CanonicalId.unsafe[EntityK](s))),
    id.map(s => DocRef.SituationCanonical(CanonicalId.unsafe[SituationK](s))),
    id.map(s => DocRef.SegmentCanonical(CanonicalId.unsafe[SegmentK](s))),
    id.map(s => DocRef.ContextCanonical(CanonicalId.unsafe[ContextK](s)))
  )
  given Arbitrary[DocRef] = Arbitrary(docRef)

  val ev = Addressable[DocRef]

  property("parse(address(r)) == Some(r)"):
    forAll { (r: DocRef) => ev.parse(ev.address(r)) == Some(r) }

  property("addresses carry the document tag and round-trip the wire form"):
    forAll { (r: DocRef) =>
      val a = ev.address(r)
      a.tag == DocRef.Tag && Address.parse(a.render) == Right(a)
    }

  property("address is injective"):
    forAll { (x: DocRef, y: DocRef) => (x == y) == (ev.address(x) == ev.address(y)) }

  test("kind-indexed mentions and canonicals never cross kinds"):
    val m = ev.address(DocRef.EntityMention(MentionId.unsafe[EntityK]("m1")))
    assertEquals(m.render, "document/mention/entity/m1")
    val swapped = Address.parse("document/mention/situation/m1").toOption.get
    assertEquals(
      ev.parse(swapped),
      Some(DocRef.SituationMention(MentionId.unsafe[SituationK]("m1")))
    )
    assertNotEquals(ev.parse(swapped), ev.parse(m))
    assertEquals(ev.parse(Address.parse("document/mention/thing/m1").toOption.get), None)
    assertEquals(ev.parse(Address.parse("document/canonical/entity").toOption.get), None)
    assertEquals(ev.parse(Address.parse("story/mention/entity/m1").toOption.get), None)

  test("chart node addresses keep sentence and concept as separate parts"):
    val r = DocRef.ChartNode(ChartNodeRef(SurfaceUnitId.unsafe("s3"), ConceptId.unsafe("c/7")))
    assertEquals(ev.address(r).render, "document/chart-node/s3/c%2F7")
    assertEquals(Address.parse("document/chart-node/s3/c%2F7").toOption.flatMap(ev.parse), Some(r))
