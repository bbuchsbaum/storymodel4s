package docsprobe

import storymodel4s.embed.{SensitiveKeyProvider, Sensitivity}
import storymodel4s.embed.grakern.*
import storymodel4s.features.Estimate
import storymodel4s.proposition.*

/** Compare checked proposition graphs with the JVM-only grakern structural channel. */
@main def structuralDistance(): Unit =
  def checked(
      predicate: String,
      agent: String,
      patient: String,
      ids: (String, String, String) = ("p", "a", "b"),
      reverseRelations: Boolean = false
  ): PropositionChart[Checked] =
    val (p0, a0, b0) = ids
    val p = ConceptId.unsafe(p0)
    val a = ConceptId.unsafe(a0)
    val b = ConceptId.unsafe(b0)
    val relations = Vector(
      PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(a)),
      PropositionRelation(p, RoleAssignment.arg(1), ConceptTarget.Node(b))
    )
    ChartValidator
      .check(
        PropositionChart.unchecked(
          Some(p),
          Map(
            p -> Concept.predicate(predicate),
            a -> Concept.entity(agent),
            b -> Concept.entity(patient)
          ),
          if reverseRelations then relations.reverse else relations
        )
      )
      .fold(errors => throw new IllegalArgumentException(errors.mkString("; ")), identity)

  val find = checked("find", "Anna", "brother")
  val renamed = checked(
    "find",
    "Anna",
    "brother",
    ids = ("predicate-9", "entity-7", "entity-3"),
    reverseRelations = true
  )
  val swapped = checked("find", "brother", "Anna")
  val unrelated = checked("hear", "Anna", "scream")
  val notPrepared = checked("repair", "Anna", "boat")
  val receiptContext = StructuralReceiptContext
    .of(Sensitivity.Public, Sensitivity.Public, SensitiveKeyProvider.none)
    .fold(error => throw new IllegalArgumentException(error.message), identity)
  val distance = GrakernStructuralDistance
    .prepare(Vector(find, swapped, unrelated), receiptContext)
    .fold(error => throw new IllegalArgumentException(error.message), identity)

  def render(query: PropositionChart[Checked], source: PropositionChart[Checked]): String =
    distance(PropositionEvidence.hand(query), PropositionEvidence.hand(source)) match
      case Estimate.Observed(value, _) => f"Observed($value%.6f)"
      case Estimate.Missing(reason)    => s"Missing($reason)"

  println(s"prepared source charts: ${distance.prepared.size}")
  println(s"identical structure: ${render(find, find)}")
  println(s"alpha-renamed and reordered: ${render(renamed, find)}")
  println(s"ARG0/ARG1 fillers swapped: ${render(find, swapped)}")
  println(s"unrelated predicate and patient: ${render(find, unrelated)}")
  println(s"source not in prepared dictionary: ${render(find, notPrepared)}")
  println(s"memoized provider calls: ${distance.receipts.size}")
  val receipt = distance.receipts.head
  println(s"receipt provider: ${receipt.provider}")
  println(s"receipt rounds: ${receipt.params("rounds")}")
  println(s"pinned grakern revision: ${receipt.version}")
