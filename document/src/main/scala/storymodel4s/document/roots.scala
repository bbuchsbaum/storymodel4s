package storymodel4s.document

import storymodel4s.proposition.{
  CheckState,
  Concept,
  ConceptId,
  ConceptKind,
  PropositionChart,
  SourceRole
}

/** The closed, structural shapes a chart concept may have that let it anchor a situation.
  *
  * Why a shared object rather than a rule inside the provider: the proposal provider decides which
  * roots it will propose, and the compiler decides which sources it will accept, and the two
  * decisions have to name the same shapes or a lawful proposal is refused downstream for a reason
  * nobody stated. Everything here is a *structural* test over a chart — a lemma in a closed set, a
  * role that reaches a concept — so both sides read one implementation and neither infers anything
  * the chart does not carry.
  *
  * The shapes are deliberately few. A frame-bearing concept is a roleset and is handled by the
  * predicate rule, so every shape below requires the concept to carry no frame.
  */
object ChartRoots:
  /** Concepts whose whole content is that they join their branches. AMR writes conjunction as
    * `(a / and :op1 ... :op2 ...)` and a multi-clause sentence as `(m / multi-sentence :snt1 ...)`;
    * none of the three is a predicate, and each holds predicates underneath it.
    *
    * Why a closed lemma set and not a kind: the AMR adapter classifies `and` and `or` as `Entity`
    * (a bare lexical concept heading no `:domain` and filling no `:mod`) and `multi-sentence` as
    * `Special`, so no concept kind separates a coordinator from an ordinary entity. The lemma does,
    * and naming the three keeps the set inspectable and falsifiable.
    */
  val CoordinationLemmas: Set[String] = Set("and", "or", "multi-sentence")

  /** The role that predicates its head of its filler: `(d / dead :domain (h / he))` asserts that
    * `he` is `dead`. AMR's `:domain` is the only role whose filler is the subject of predication.
    */
  val PredicationRole: String = "domain"

  /** The role that places its head: `(p / person :location (e / egulac))` asserts that people are
    * at Egulac. It is the locative reading and nothing more; no quantity is required or read.
    */
  val ExistenceRole: String = "location"

  /** Concept kinds that may head a predication. A frame is excluded everywhere below, so these are
    * exactly the frameless heads a `:domain` can hang from: the adapter makes a lexical concept
    * heading `:domain` a `Property` (`dead`), but a hand chart or another source may leave it an
    * `Entity` (`(p / person :domain (t / they))`, "they are people"), and the reading is the same.
    */
  val PredicativeKinds: Set[ConceptKind] = Set(ConceptKind.Property, ConceptKind.Entity)

  /** One branch of a coordinator: the role that attached it and the concept it names. */
  final case class Branch(role: SourceRole, concept: ConceptId)

  /** A coordination concept: a frameless concept whose lemma is in [[CoordinationLemmas]]. */
  def isCoordinator(concept: Concept): Boolean =
    concept.frame.isEmpty && CoordinationLemmas(concept.lemma.value)

  /** The direct branch children of `coordinator`, in discourse order.
    *
    * A branch is a relation from the coordinator under `:opN` (`and`, `or`) or `:sntN`
    * (`multi-sentence`) whose target is a concept of this chart. Order is operands before
    * sentences, then by index, then by concept id, so the order is total whatever a chart mixes;
    * within one coordinator only one family occurs in practice. A concept attached twice is
    * listed once, under its first role, because it is one branch however many ways it is named.
    */
  def branches[C <: CheckState](
      chart: PropositionChart[C],
      coordinator: ConceptId
  ): Vector[Branch] =
    chart
      .relationsFrom(coordinator)
      .flatMap(r =>
        branchOrder(r.role.source).flatMap(order =>
          r.to.nodeId
            .filter(chart.concepts.contains)
            .filter(_ != coordinator)
            .map(id => (order, id.value, Branch(r.role.source, id)))
        )
      )
      .sortBy(row => (row._1._1, row._1._2, row._2))
      .map(_._3)
      .distinctBy(_.concept)

  /** Whether `candidate` is a direct branch of `coordinator` in this chart. */
  def isBranchOf[C <: CheckState](
      chart: PropositionChart[C],
      coordinator: ConceptId,
      candidate: ConceptId
  ): Boolean = branches(chart, coordinator).exists(_.concept == candidate)

  /** A predicative root: a frameless, non-coordinating `Property` or `Entity` concept whose
    * `:domain` reaches a concept of the chart. The property is the predicate and the `:domain`
    * filler is what it is predicated of.
    *
    * A `:domain` reaching a literal, an unknown, or nothing at all is not this shape: there would
    * be no participant to predicate the property of, and a state with no subject is not something
    * the chart said.
    */
  def isPredicative[C <: CheckState](
      chart: PropositionChart[C],
      id: ConceptId,
      concept: Concept
  ): Boolean =
    concept.frame.isEmpty && !isCoordinator(concept) && PredicativeKinds(concept.kind) &&
      roleFiller(chart, id, PredicationRole).nonEmpty

  /** An existential root: a frameless, non-coordinating `Entity` concept whose `:location` reaches
    * a concept of the chart. The sentence asserts that the entity is at that place.
    */
  def isExistential[C <: CheckState](
      chart: PropositionChart[C],
      id: ConceptId,
      concept: Concept
  ): Boolean =
    concept.frame.isEmpty && !isCoordinator(concept) && concept.kind == ConceptKind.Entity &&
      roleFiller(chart, id, ExistenceRole).nonEmpty

  /** The first concept `id` reaches under the bare named role `name`, in concept order. */
  def roleFiller[C <: CheckState](
      chart: PropositionChart[C],
      id: ConceptId,
      name: String
  ): Option[ConceptId] =
    chart
      .relationsFrom(id)
      .filter(_.role.source == SourceRole.Named(name))
      .flatMap(_.to.nodeId)
      .filter(chart.concepts.contains)
      .sorted
      .headOption

  /** Sort key of a branch role: operands (`:opN`) before sentences (`:sntN`), then by index. */
  private def branchOrder(role: SourceRole): Option[(Int, Int)] = role match
    case SourceRole.Operand(index) => Some((0, index))
    case SourceRole.Named(name)    => sentenceIndex(name).map(index => (1, index))
    case _                         => None

  private val SentenceRole = "snt([1-9][0-9]*)".r

  private def sentenceIndex(name: String): Option[Int] = name match
    case SentenceRole(digits) => digits.toIntOption
    case _                    => None
