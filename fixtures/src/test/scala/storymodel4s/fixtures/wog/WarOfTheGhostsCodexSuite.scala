package storymodel4s.fixtures.wog

import munit.FunSuite
import cats.syntax.all.*
import storymodel4s.core.*
import storymodel4s.view.*

class WarOfTheGhostsCodexSuite extends FunSuite:
  test("the WOG Codex textual twin labels the acceptance fixture and preserves exact text"):
    val model = WarOfTheGhostsModel.model
    val battle = model.graph.situations(WarOfTheGhostsModel.S.battle)
    val target = Addressable[CoreRef].address(CoreRef.Claim(battle.meta.id))
    val upstream = battle.meta.evidence.toVector.map { evidence =>
      Addressable[CoreRef].address(CoreRef.Evidence(evidence.id))
    }
    val annotation = TextAnnotation
      .of(
        target,
        battle.support,
        AnnotationKind.Claim,
        AnnotationPriority.unsafe(100),
        AuditRecord.of(upstream, battle.meta.provenance)
      )
      .fold(error => fail(error.message), identity)
    val config = Checksum.ofText("wog-codex-textual-twin")
    val provenance = ViewProvenance
      .fixture(model.source.canonicalChecksum, "storymodel4s-test", config)
      .fold(error => fail(error.message), identity)
    val flow = CodexFlow
      .exact(model.source, Vector(annotation), provenance)
      .fold(error => fail(error.message), identity)

    assertEquals(
      flow.runs.traverse(flow.text).map(_.mkString),
      Right(model.source.canonicalText)
    )
    assertEquals(model.source.canonicalText, StorySource.canonicalize(WarOfTheGhostsText.text))
    assert(flow.textualTwin.contains("researcher-reviewed narrative acceptance fixture"))
    assert(flow.textualTwin.contains(WarOfTheGhostsText.title))
    assert(flow.textualTwin.contains(model.source.canonicalText))
    assert(flow.textualTwin.contains(target.render))
    assertEquals(flow.navigation.targetOf(annotation.id), Some(target))
