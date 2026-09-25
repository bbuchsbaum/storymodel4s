package storymodel4s.probes

import java.lang.reflect.Modifier
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.proposition.{GraphOrder, SemanticGraph, SemanticProjection}

/** Detects new JVM read doors even when nobody thought to name them in the compile court. Scala
  * package privacy is erased on the JVM; the known package-only reductions and graph internals are
  * pinned too, without claiming they are public to Scala consumers.
  */
class ContentSurfaceSuite extends FunSuite:
  private val surfaces = Vector(
    ContentProjection.getClass -> Set("canonical", "source", "sourceUnit", "sourceNode"),
    SemanticProjection.getClass -> Set("canonical", "sourceOrder"),
    SemanticGraph.getClass -> Set("build"),
    Members.getClass -> Set("empty"),
    ParticipantContent.getClass -> Set("given_Ordering_ParticipantContent"),
    classOf[ParticipantContent] -> Set("role", "specified", "names"),
    classOf[UnitContent[GraphOrder.Canonical]] -> Set(
      "function",
      "predicate",
      "participants",
      "polarity",
      "modality",
      "outcome",
      "sensoryTerms",
      "lemmas",
      "graph",
      "byRole"
    ),
    classOf[TargetContent[GraphOrder.Canonical]] -> Set(
      "level",
      "predicate",
      "participants",
      "context",
      "polarity",
      "modality",
      "outcome",
      "lemmas",
      "hasEvidence",
      "graph",
      "members",
      "isLeaf",
      "byRole"
    ),
    classOf[Members[GraphOrder.Canonical]] -> Set(
      "structural",
      "leaves",
      "count",
      "withEvidence",
      "exists",
      "minOf"
    ),
    classOf[ContentGrain] -> Set("maxLevel"),
    classOf[SemanticGraph[GraphOrder.Canonical]] -> Set(
      "kinds",
      "lemmas",
      "glosses",
      "frames",
      "polarities",
      "embeddings",
      "relationsOf",
      "relations",
      "tiePolicy",
      "isEmpty"
    )
  )
  private val objectMethods = Set("equals", "hashCode", "toString")
  private val forbidden = Vector(
    "RecallUnit",
    "RecallUnitId",
    "NodeSummary",
    "SourceView",
    "SourceNodeRef",
    "TextSpan",
    "SpanSet",
    "PropositionChart",
    "PropositionEvidence",
    "ConceptId",
    "SentenceId",
    "Provenance",
    "EntityId",
    "Grounding",
    "ScoringPosition",
    "DiscoursePosition",
    "ImportanceWeight",
    "BindingKey"
  )

  surfaces.foreach { (cls, expected) =>
    test(s"${cls.getSimpleName}: public method names and overload counts are pinned") {
      assertEquals(cls.getDeclaredConstructors.length, 1, "constructor surface widened")
      val methods = cls.getDeclaredMethods.toVector.filter(m =>
        Modifier.isPublic(m.getModifiers) && !m.isSynthetic && !Modifier.isStatic(m.getModifiers)
      )
      assertEquals(
        methods.groupMapReduce(_.getName)(_ => 1)(_ + _),
        (expected ++ (if cls.getName.endsWith("$") then Set.empty[String] else objectMethods))
          .map(_ -> 1)
          .toMap
      )
    }

    if !cls.getName.endsWith("$") then
      test(s"${cls.getSimpleName}: no forbidden type in JVM signatures") {
        val signatures = cls.getDeclaredMethods.toVector
          .filter(m =>
            Modifier.isPublic(m.getModifiers) && !m.isSynthetic && !Modifier.isStatic(
              m.getModifiers
            )
          )
          .map(_.toGenericString) ++ cls.getFields.toVector.map(
          _.toGenericString
        ) ++ cls.getDeclaredConstructors.toVector.map(_.toGenericString)
        val leaked = signatures.filter(s =>
          forbidden.exists { name =>
            ("\\b" + name + "\\b").r.findFirstIn(s).nonEmpty
          }
        )
        assertEquals(leaked, Vector.empty)
      }
  }
