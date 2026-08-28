package storymodel4s.story

import munit.FunSuite
import storymodel4s.core.*

/** `covering` / `supporting` read API (ADR 0002 §4): text -> references, reference -> evidence. */
class CoveringSuite extends FunSuite:
  val b = Small.build(4, 2)
  val g = b.graph
  def sentence(i: Int): TextSpan = b.atlas.sentences(i).span

  test("supporting returns node support and edge claim spans, None for unknown refs"):
    assertEquals(
      g.supporting(StoryRef.Situation(b.situations(2))),
      Some(g.situations(b.situations(2)).support)
    )
    assertEquals(g.supporting(StoryRef.Segment(b.scene)), Some(g.segments(b.scene).support))
    assertEquals(g.supporting(StoryRef.Context(b.world)), Some(g.contexts(b.world).support))
    assertEquals(g.supporting(StoryRef.Situation(SituationId.unsafe("nope"))), None)
    val t = g.relations.temporal.head
    assertEquals(
      g.supporting(StoryRef.Temporal(t.from, t.relation, t.to, t.context)),
      t.meta.spans
    )

  test("covering a sentence returns exactly the references whose evidence overlaps it"):
    val refs = g.covering(sentence(2))
    assert(refs.contains(StoryRef.Situation(b.situations(2))), refs.toString)
    assert(!refs.contains(StoryRef.Situation(b.situations(0))), refs.toString)
    assert(!refs.contains(StoryRef.Situation(b.situations(3))), refs.toString)
    // Every returned reference has support overlapping the query span.
    refs.foreach { r =>
      val sup = g.supporting(r)
      assert(sup.exists(_.spans.exists(_.overlaps(sentence(2)))), s"$r: $sup")
    }

  test("covering is deterministic and ordered by address"):
    val ev = Addressable[StoryRef]
    val refs = g.covering(sentence(1))
    assertEquals(refs, refs.sortBy(r => ev.address(r).render))
    assertEquals(g.covering(sentence(1)), refs)

  test("covering an empty span or a span outside the text returns nothing"):
    assertEquals(g.covering(TextSpan.unsafe(0, 0)), Vector.empty)
    val len = b.source.canonicalText.length
    assertEquals(g.covering(TextSpan.unsafe(len + 10, len + 20)), Vector.empty)

  test("situationsCovering follows discourse order"):
    val whole = TextSpan.unsafe(0, b.source.canonicalText.length)
    assertEquals(g.situationsCovering(whole), g.discourseOrder)

  test("model-level supporting resolves containment edges through the hierarchy"):
    val m = b.draft()
    val e = m.hierarchy.containment.head
    val ref = StoryRef.Containment(e.member, e.parent, e.kind)
    assertEquals(g.supporting(ref), None)
    assertEquals(m.supporting(ref), e.meta.spans)
    assertEquals(
      m.supporting(StoryRef.Situation(b.situations(1))),
      g.supporting(StoryRef.Situation(b.situations(1)))
    )
    val whole = TextSpan.unsafe(0, b.source.canonicalText.length)
    val refs = m.covering(whole)
    assert(
      refs.forall(r => m.supporting(r).exists(_.spans.exists(_.overlaps(whole)))),
      refs.toString
    )
    assert(g.covering(whole).toSet.subsetOf(refs.toSet))

  test("violations carry the typed address of the offending object when the path names one"):
    val ev = Addressable[StoryRef]
    assertEquals(
      Violation.addressOf(s"situations/${b.situations(1).value}", g),
      Some(ev.address(StoryRef.Situation(b.situations(1))))
    )
    assertEquals(
      Violation.addressOf(s"entities/${b.entities(0).value}/label", g),
      Some(ev.address(StoryRef.Entity(b.entities(0))))
    )
    val t = g.relations.temporal.head
    assertEquals(
      Violation.addressOf("temporal/0", g),
      Some(ev.address(StoryRef.Temporal(t.from, t.relation, t.to, t.context)))
    )
    assertEquals(Violation.addressOf("temporal/999", g), None)
    assertEquals(Violation.addressOf("situations/nope", g), None)
    assertEquals(Violation.addressOf("atlas", g), None)
    // A real violation raised by the validator resolves to the edge it names.
    val self = t.copy(to = t.from)
    val broken = g.copy(relations = g.relations.copy(temporal = g.relations.temporal :+ self))
    val report = StoryValidator.validate(b.draft(graph = broken), ValidationPolicy.strict).report
    val v = report.violations.find(_.law == "temporal.no-self").getOrElse(fail(report.render))
    assertEquals(
      v.address,
      Some(ev.address(StoryRef.Temporal(self.from, self.relation, self.to, self.context)))
    )
