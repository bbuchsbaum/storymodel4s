package storymodel4s.embed.grakern

import storymodel4s.core.Credence
import storymodel4s.core.ScorerId
import storymodel4s.proposition.*
import storymodel4s.proposition.CheckState.Checked

/** Small hand charts for the structural channel's laws. */
object Charts:

  def checked(u: PropositionChart[Unchecked]): PropositionChart[Checked] =
    ChartValidator.check(u).fold(v => throw new AssertionError(s"invalid chart: $v"), identity)

  /** `pred(agent = ARG0, patient = ARG1)` with optional negation and an optional embedding. */
  def transitive(
      pred: String,
      agent: String,
      patient: String,
      negated: Boolean = false,
      ids: (String, String, String) = ("p", "a", "b"),
      relationOrder: Boolean = true
  ): PropositionChart[Checked] =
    val (pi, ai, bi) = ids
    val p = ConceptId.unsafe(pi)
    val a = ConceptId.unsafe(ai)
    val b = ConceptId.unsafe(bi)
    val rels = Vector(
      PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(a)),
      PropositionRelation(p, RoleAssignment.arg(1), ConceptTarget.Node(b))
    )
    checked(
      PropositionChart.unchecked(
        Some(p),
        Map(p -> Concept.predicate(pred), a -> Concept.entity(agent), b -> Concept.entity(patient)),
        if relationOrder then rels else rels.reverse,
        polarity = Map(p -> (if negated then Polarity.Negative else Polarity.Positive))
      )
    )

  /** `say(speaker) :content pred(agent, patient)` with the content embedded under `kind`. */
  def embedded(
      kind: EmbeddingKind,
      agent: String = "warriors",
      patient: String = "man"
  ): PropositionChart[Checked] =
    val s = ConceptId.unsafe("s")
    val w = ConceptId.unsafe("w")
    val p = ConceptId.unsafe("p")
    val a = ConceptId.unsafe("a")
    val b = ConceptId.unsafe("b")
    checked(
      PropositionChart.unchecked(
        Some(s),
        Map(
          s -> Concept.predicate("say"),
          w -> Concept.entity("warriors"),
          p -> Concept.predicate("hit"),
          a -> Concept.entity(agent),
          b -> Concept.entity(patient)
        ),
        Vector(
          PropositionRelation(s, RoleAssignment.arg(0), ConceptTarget.Node(w)),
          PropositionRelation(s, RoleAssignment.arg(1), ConceptTarget.Node(p)),
          PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(a)),
          PropositionRelation(p, RoleAssignment.arg(1), ConceptTarget.Node(b))
        ),
        polarity = Map(s -> Polarity.Positive, p -> Polarity.Positive),
        embedded = Vector(EmbeddedProposition(s, kind, p))
      )
    )

  /** A chart with a literal and an unknown filler. */
  def withLeaves: PropositionChart[Checked] =
    val p = ConceptId.unsafe("p")
    val a = ConceptId.unsafe("a")
    checked(
      PropositionChart.unchecked(
        Some(p),
        Map(p -> Concept.predicate("count"), a -> Concept.entity("canoe")),
        Vector(
          PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(a)),
          PropositionRelation(
            p,
            RoleAssignment.named("quant"),
            ConceptTarget.Literal(LiteralValue.Number(BigDecimal(5)))
          ),
          PropositionRelation(p, RoleAssignment.named("location"), ConceptTarget.Unknown)
        ),
        polarity = Map(p -> Polarity.Positive)
      )
    )

  val rawCredence: Credence = Credence.unsafeRaw(0.5, ScorerId.unsafe("test-scorer"))
