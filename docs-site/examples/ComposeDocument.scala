package docsprobe

import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.EntityK
import storymodel4s.document.*
import storymodel4s.proposition.*

/** Compose sentence-local proposition charts into a document graph, then quotient exact entity
  * coreference without destructively merging the charts.
  */
@main def composeDocument(): Unit =
  def conceptId(value: String): ConceptId = ConceptId.unsafe(value)
  def sentence(value: String): SurfaceUnitId = SurfaceUnitId.unsafe(value)
  def mention(value: String): MentionId[EntityK] = MentionId.unsafe[EntityK](value)

  def checkedChart(
      predicate: String,
      agent: String,
      patient: Option[String]
  ): PropositionChart[Checked] =
    val p = conceptId("p")
    val a = conceptId("a")
    val concepts =
      Map(p -> Concept.predicate(predicate), a -> Concept.entity(agent)) ++
        patient.map(value => conceptId("b") -> Concept.entity(value))
    val relations =
      Vector(PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(a))) ++
        patient.toVector.map(_ =>
          PropositionRelation(p, RoleAssignment.arg(1), ConceptTarget.Node(conceptId("b")))
        )
    ChartValidator
      .check(PropositionChart.unchecked(Some(p), concepts, relations))
      .fold(errors => throw new IllegalArgumentException(errors.mkString("; ")), identity)

  val s1 = sentence("sentence-1")
  val s2 = sentence("sentence-2")
  val s3 = sentence("sentence-3")
  val graph = MentionGraph
    .of(
      Vector(
        s1 -> checkedChart("find", "Maya", Some("boat")),
        s2 -> checkedChart("repair", "she", Some("it")),
        s3 -> checkedChart("sail", "Maya", None)
      )
    )
    .fold(error => throw new IllegalArgumentException(error.message), identity)

  val maya1 = mention("maya-1")
  val maya2 = mention("maya-2")
  val maya3 = mention("maya-3")
  val boat1 = mention("boat-1")
  val boat2 = mention("boat-2")
  val table = MentionTable
    .of[EntityK](
      Vector(
        maya1 -> ChartNodeRef(s1, conceptId("a")),
        boat1 -> ChartNodeRef(s1, conceptId("b")),
        maya2 -> ChartNodeRef(s2, conceptId("a")),
        boat2 -> ChartNodeRef(s2, conceptId("b")),
        maya3 -> ChartNodeRef(s3, conceptId("a"))
      ),
      graph
    )
    .fold(error => throw new IllegalArgumentException(error.message), identity)

  val identityPairs = Vector(maya1 -> maya2, maya2 -> maya3, boat1 -> boat2)
  val partition = CorefPartition
    .fromPairs(StoryId.unsafe("story:document-demo"), identityPairs, table)
    .fold(error => throw new IllegalArgumentException(error.message), identity)
  val reversed = CorefPartition
    .fromPairs(StoryId.unsafe("story:document-demo"), identityPairs.reverse.map(_.swap), table)
    .fold(error => throw new IllegalArgumentException(error.message), identity)

  println(s"sentence charts: ${graph.size}")
  println(s"document nodes: ${graph.nodes.size}")
  println(s"typed entity mentions: ${table.size}")
  println(s"sentence-1 agent preserved: ${graph.concept(ChartNodeRef(s1, conceptId("a"))).map(_.lemma.value).getOrElse("missing")}")
  println(s"maya-1 same as maya-3: ${partition.same(maya1, maya3)}")
  println(s"boat-1 same as boat-2: ${partition.same(boat1, boat2)}")
  println(s"maya-1 same as boat-1: ${partition.same(maya1, boat1)}")
  partition.clusters.sortBy(_.canonical.value).foreach { cluster =>
    val members = cluster.mentions.toSortedSet.toVector.map(_.value).mkString(",")
    println(s"cluster $members -> ${cluster.canonical.value}")
  }
  println(s"pair order independent: ${partition == reversed}")
  val unknown = mention("unknown")
  val refused = CorefPartition.fromPairs(
    StoryId.unsafe("story:document-demo"),
    Vector(maya1 -> unknown),
    table
  )
  println(s"unknown mention refused: ${refused.isLeft}")
