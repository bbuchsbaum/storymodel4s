package storymodel4s.proposition

/** Deliberately open test control with the exact graph constructor shape. It lives here only so the
  * private parameter types can be named; the court constructs it from outside storymodel4s. No
  * instance of this control enters production scoring.
  */
final class OpenSemanticGraph[O <: GraphOrder](
    private[proposition] val kinds: Vector[ConceptKind],
    private[proposition] val lemmas: Vector[String],
    private[proposition] val glosses: Vector[Option[String]],
    private[proposition] val frames: Vector[Option[(String, String)]],
    private[proposition] val polarities: Vector[Polarity],
    private[proposition] val embeddings: Vector[Set[EmbeddingKind]],
    private[proposition] val relationsOf: Vector[Vector[Int]],
    private[proposition] val relations: Vector[SemanticRelation],
    private[proposition] val tiePolicy: TiePolicy
)

/** Deliberately open same-field constructor control for the checked content report. */
final class OpenContentReport(
    val conceptMatch: Double,
    val argumentMatch: Double,
    val partialityPenalty: Double,
    val gateReadings: Set[GateReading],
    val matched: Boolean
)
