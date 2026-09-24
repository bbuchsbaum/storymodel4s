package storymodel4s.align

import munit.FunSuite
import storymodel4s.recall.*

/** Pins `ContradictionDetector` output over a grid that reaches every sketch rule, captured on the
  * pre-port base (solo/s2a1b 16d1c97c) before S2a-2 moved the rules onto content types.
  *
  * Why a grid: the historical suites never exercised the sketch-level modality rule. Deleting it
  * left all 535 align tests green (mutant P1, S2a-2), so their passing could not show that the port
  * preserved it. This grid crosses predicate match, agent and patient names, polarity, modality,
  * context and outcome, and digests `detect` and `engages` through both entry points. JVM-only: the
  * digest is bit-level.
  */
class ContradictionParityPin extends FunSuite:
  private val base = AnnaFixture.nodes.head.copy(evidence = None)

  private def people(agent: String, patient: String) =
    Vector(
      ParticipantSummary(SketchRole.Agent, agent, Set.empty),
      ParticipantSummary(SketchRole.Patient, patient, Set.empty)
    )
  private def sketchPeople(agent: String, patient: String) =
    Vector(
      SketchParticipant(SketchRole.Agent, None, agent),
      SketchParticipant(SketchRole.Theme, None, patient)
    )

  private val predicates = Vector(Some("run"), Some("walk"), None)
  private val casts = Vector(("anna", "bob"), ("bob", "anna"), ("anna", "carl"))
  private val outcomes = Vector(Some("dies"), Some("Lives"), None)
  private val contexts = Vector(ContextTag.NarratedWorld, ContextTag.Speech)

  private def rows: Vector[String] =
    for
      sp <- predicates
      (sa, sb) <- casts
      spol <- PolarityTag.values.toVector
      smod <- ModalityTag.values.toVector
      so <- outcomes
      np <- predicates.take(2)
      (na, nb) <- casts.take(2)
      npol <- PolarityTag.values.toVector
      nmod <- ModalityTag.values.toVector
      ctx <- contexts
      no <- outcomes
      if (sp, spol, so, npol, no).hashCode % 3 == 0 // a fixed, content-derived thinning
    yield
      val sketch =
        PropositionSketch(
          sp,
          sketchPeople(sa, sb),
          spol,
          smod,
          Vector.empty,
          Vector.empty,
          Vector.empty,
          Set.empty,
          so
        )
      val node = base.copy(
        predicate = np,
        participants = people(na, nb),
        context = ctx,
        polarity = npol,
        modality = nmod,
        outcome = no
      )
      val unit = AnnaFixture.u0.copy(proposition = sketch, evidence = None)
      Vector(
        ContradictionDetector.detect(unit, node).mkString(","),
        ContradictionDetector.detect(sketch, node).mkString(","),
        ContradictionDetector.engages(unit, node).toString,
        ContradictionDetector.engages(sketch, node).toString
      ).mkString("|")

  test("precondition: the grid reaches every sketch contradiction") {
    val all = rows.mkString(" ")
    for c <- Vector(
        "RoleReversal",
        "PolarityConflict",
        "ContextConflict",
        "ModalityConflict",
        "OutcomeConflict"
      )
    do assert(all.contains(c), s"grid never produces $c")
  }

  test("contradiction detection over the grid is byte-identical to the pre-port base") {
    assertEquals(
      (rows.size, MappingRender.digest(rows).hex),
      (87024, "a5c19770b7d07d8e4d9a90fa27370d7c6272242274e1255a10b908c067f82f9f")
    )
  }
