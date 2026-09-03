package storymodel4s.proposition

import munit.FunSuite
import storymodel4s.core.*

/** Role-shape and embedding laws added after review. */
class ValidateRolesSuite extends FunSuite:
  private def c(i: Int) = ConceptId.unsafe(s"c$i")
  private val agent = Some(
    (ParticipantRole.Agent, Credence.unsafeRaw(1.0, ScorerId.unsafe("test-scorer")))
  )

  private def one(role: RoleAssignment, frame: Option[FrameRef] = None) =
    PropositionChart.unchecked(
      None,
      Map(c(0) -> Concept.predicate("strike", frame), c(1) -> Concept.entity("warrior")),
      Vector(PropositionRelation(c(0), role, ConceptTarget.Node(c(1))))
    )

  private def names(chart: PropositionChart[Unchecked]) =
    ChartValidator.check(chart).left.getOrElse(Vector.empty).map(_.productPrefix)

  test("Named roles must be bare: ':location' is rejected, 'location' accepted") {
    assert(names(one(RoleAssignment.named(":location"))).contains("InvalidRoleName"))
    assertEquals(names(one(RoleAssignment.named("location"))), Vector.empty)
    assert(names(one(RoleAssignment.named(""))).contains("InvalidRoleName"))
    assert(names(one(RoleAssignment.named("a b"))).contains("InvalidRoleName"))
  }

  test("numbered arguments smuggled in as names are rejected, normalized or not") {
    assert(names(one(RoleAssignment.named("ARG0"))).contains("NumberedRoleAsNamed"))
    assert(
      names(one(RoleAssignment(SourceRole.Named("arg1"), agent))).contains("NumberedRoleAsNamed")
    )
    assert(
      names(one(RoleAssignment(SourceRole.Extension("vn", "ARG2"), agent)))
        .contains("NumberedRoleAsNamed")
    )
  }

  test("SourceRole.named smart constructor mirrors the validator") {
    assert(SourceRole.named(":x").isLeft)
    assert(SourceRole.named("ARG3").isLeft)
    assertEquals(SourceRole.named("purpose"), Right(SourceRole.Named("purpose")))
  }

  test("duplicate embeddings are rejected; chains and legitimate cycles are fine") {
    val s = c(0); val t = c(1); val x = c(2)
    val base = Map(
      s -> Concept.predicate("say"),
      t -> Concept.predicate("think"),
      x -> Concept.predicate("go")
    )
    def chart(emb: Vector[EmbeddedProposition]) =
      PropositionChart.unchecked(Some(s), base, Vector.empty, embedded = emb)
    val chain = Vector(
      EmbeddedProposition(s, EmbeddingKind.Speech, t),
      EmbeddedProposition(t, EmbeddingKind.Belief, x)
    )
    assertEquals(names(chart(chain)), Vector.empty)
    assert(names(chart(chain :+ chain.head)).contains("DuplicateEmbedding"))
    // "The boy wants the girl to believe that he wants it": a legitimate embedding cycle.
    val cyclic = chain :+ EmbeddedProposition(x, EmbeddingKind.Desire, s)
    assertEquals(names(chart(cyclic)), Vector.empty)
  }
