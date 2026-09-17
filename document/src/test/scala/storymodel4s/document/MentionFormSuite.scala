package storymodel4s.document

import cats.data.NonEmptySet
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.*
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.EntityK
import storymodel4s.proposition.*

import scala.collection.immutable.SortedSet

/** Mention forms: inference table, render/parse law, and reader-time resolution. */
class MentionFormSuite extends ScalaCheckSuite:

  private def cid(s: String) = ConceptId.unsafe(s)
  private def sentence(n: Int) = SurfaceUnitId.unsafe(s"sent:$n")
  private def mid(s: String) = MentionId.unsafe[EntityK](s)
  private val story = StoryId.unsafe("story:forms")

  /** `go(a)` where `a` has the given lemma/kind; optionally `a :name (n / name)`. */
  private def chart(
      lemma: String,
      kind: ConceptKind = ConceptKind.Entity,
      named: Boolean = false
  ): PropositionChart[Checked] =
    val p = cid("p")
    val a = cid("a")
    val n = cid("n")
    val concepts = Map(
      p -> Concept.predicate("go"),
      a -> Concept(Lemma.unsafe(lemma), None, None, kind)
    ) ++ (if named then Map(n -> Concept.name("Anna")) else Map.empty)
    val rels = Vector(PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(a))) ++
      (if named then
         Vector(PropositionRelation(a, RoleAssignment.named("name"), ConceptTarget.Node(n)))
       else Vector.empty)
    ChartValidator
      .check(PropositionChart.unchecked(Some(p), concepts, rels))
      .fold(v => fail(v.mkString(",")), identity)

  private def infer(c: PropositionChart[Checked], surface: Option[String] = None): MentionForm =
    MentionFormInference.fromChart(c, cid("a"), surface)

  test("inference: Name kind and :name relation → Name") {
    assertEquals(infer(chart("Anna", ConceptKind.Name)), MentionForm.Name)
    assertEquals(infer(chart("person", named = true)), MentionForm.Name)
  }

  test("inference: pronoun table gives person and number, possessives included") {
    assertEquals(infer(chart("he")), MentionForm.Pronominal(Person.Third, Number.Singular))
    assertEquals(infer(chart("They")), MentionForm.Pronominal(Person.Third, Number.Plural))
    assertEquals(infer(chart("i")), MentionForm.Pronominal(Person.First, Number.Singular))
    assertEquals(infer(chart("our")), MentionForm.Pronominal(Person.First, Number.Plural))
    assertEquals(infer(chart("you")), MentionForm.Pronominal(Person.Second, Number.Unknown))
    assertEquals(infer(chart("yourselves")), MentionForm.Pronominal(Person.Second, Number.Plural))
  }

  test("inference: nominal definiteness comes only from surface determiners") {
    assertEquals(infer(chart("man")), MentionForm.Nominal(Definiteness.Unknown))
    assertEquals(
      infer(chart("man"), Some("the young man")),
      MentionForm.Nominal(Definiteness.Definite)
    )
    assertEquals(
      infer(chart("man"), Some("His fellow")),
      MentionForm.Nominal(Definiteness.Definite)
    )
    assertEquals(infer(chart("man"), Some("a canoe")), MentionForm.Nominal(Definiteness.Indefinite))
    assertEquals(
      infer(chart("man"), Some("An arrow")),
      MentionForm.Nominal(Definiteness.Indefinite)
    )
    assertEquals(
      infer(chart("man"), Some("those people")),
      MentionForm.Nominal(Definiteness.Demonstrative)
    )
    assertEquals(infer(chart("man"), Some("warriors")), MentionForm.Nominal(Definiteness.Unknown))
  }

  test("inference: a missing concept is Other(missing), never a nominal") {
    assertEquals(
      MentionFormInference.fromChart(chart("man"), cid("zz"), None),
      MentionForm.Other("missing")
    )
  }

  private val genForm: Gen[MentionForm] = Gen.oneOf(
    Gen.const(MentionForm.Name),
    Gen.oneOf(Definiteness.values.toSeq).map(MentionForm.Nominal(_)),
    for
      p <- Gen.oneOf(Person.values.toSeq)
      n <- Gen.oneOf(Number.values.toSeq)
    yield MentionForm.Pronominal(p, n),
    Gen.alphaNumStr.map(s => MentionForm.Other(s + ":x"))
  )
  private given Arbitrary[MentionForm] = Arbitrary(genForm)

  property("render/parse round-trip and Order agrees with ==") {
    forAll { (a: MentionForm, b: MentionForm) =>
      MentionForm.parse(a.render) == Right(a) &&
      ((cats.Order[MentionForm].compare(a, b) == 0) == (a == b))
    }
  }

  test("parse rejects unknown renderings") {
    assert(MentionForm.parse("pronominal:third").isLeft)
    assert(MentionForm.parse("nominal:bogus").isLeft)
    assert(MentionForm.parse("").isLeft)
  }

  // ---- table-level accessors and reader-time resolution -------------------------------

  /** A document: sentence i holds one entity mention m$i with the given lemma. */
  private def doc(lemmas: Vector[String]): (MentionGraph, MentionTable[EntityK], MentionForms) =
    val graph =
      MentionGraph.of(lemmas.zipWithIndex.map((l, i) => sentence(i) -> chart(l))).toOption.get
    val table = MentionTable
      .of[EntityK](
        lemmas.indices.map(i => mid(s"m$i") -> ChartNodeRef(sentence(i), cid("a"))),
        graph
      )
      .fold(e => fail(e.message), identity)
    val forms = MentionForms
      .infer(table, graph, _ => None, MentionForms.sentenceRank(graph.sentences))
      .fold(e => fail(e.message), identity)
    (graph, table, forms)

  test("accessors partition mentions by form and keep discourse order") {
    // "Anna" is built as an Entity-kind lemma here, so it counts as nominal (introducing), not Name.
    val (_, _, forms) = doc(Vector("Anna", "she", "man", "they"))
    assertEquals(forms.pronominalMentions, Vector(mid("m1"), mid("m3")))
    assertEquals(forms.introducingMentions, Vector(mid("m0"), mid("m2")))
    assertEquals(forms.inDiscourseOrder, Vector(mid("m0"), mid("m1"), mid("m2"), mid("m3")))
    assertEquals(forms.formOf(mid("m9")), None)
  }

  test("firstNamedMention / firstIntroducingMention pick the discourse-first member") {
    val graph = MentionGraph
      .of(
        Vector(
          sentence(0) -> chart("he"),
          sentence(1) -> chart("man"),
          sentence(2) -> chart("Anna", ConceptKind.Name),
          sentence(3) -> chart("Anna", ConceptKind.Name)
        )
      )
      .toOption
      .get
    val table = MentionTable
      .of[EntityK]((0 to 3).map(i => mid(s"m$i") -> ChartNodeRef(sentence(i), cid("a"))), graph)
      .fold(e => fail(e.message), identity)
    val forms = MentionForms
      .infer(table, graph, _ => None, MentionForms.sentenceRank(graph.sentences))
      .fold(e => fail(e.message), identity)
    val all = NonEmptySet.fromSetUnsafe(SortedSet((0 to 3).map(i => mid(s"m$i"))*))
    val cluster = ExactCorefCluster.of(story, all, table).fold(e => fail(e.message), identity)
    assertEquals(forms.firstNamedMention(cluster), Some(mid("m2")))
    assertEquals(forms.firstIntroducingMention(cluster), Some(mid("m1")))
    val pronounsOnly = NonEmptySet.one(mid("m0"))
    val c0 = ExactCorefCluster.of(story, pronounsOnly, table).fold(e => fail(e.message), identity)
    assertEquals(forms.firstNamedMention(c0), None)
  }

  test("resolvableAt: a pronoun sees only clusters introduced at or before it") {
    // m0 "man", m1 "he", m2 "woman", m3 "she"; clusters {m0,m1}, {m2,m3}
    val (_, table, forms) = doc(Vector("man", "he", "woman", "she"))
    val part = CorefPartition
      .fromPairs(story, Vector(mid("m0") -> mid("m1"), mid("m2") -> mid("m3")), table)
      .fold(e => fail(e.message), identity)
    val cMan = part.canonical(mid("m0"))
    val cWoman = part.canonical(mid("m2"))
    assertEquals(forms.resolvableAt(mid("m1"), part), Vector(cMan))
    assertEquals(forms.resolvableAt(mid("m3"), part), Vector(cMan, cWoman).sorted)
    assertEquals(forms.resolvableAt(mid("m9"), part), Vector.empty)
  }

  test("MentionForms.of rejects a missing form and a form for an unknown mention") {
    val (graph, table, _) = doc(Vector("man", "he"))
    assert(
      MentionForms
        .of(
          table,
          Map(mid("m0") -> MentionForm.Name),
          MentionForms.sentenceRank(graph.sentences)
        )
        .isLeft
    )
    val bogus = Map(
      mid("m0") -> MentionForm.Name,
      mid("m1") -> MentionForm.Name,
      mid("zz") -> MentionForm.Name
    )
    assert(MentionForms.of(table, bogus, MentionForms.sentenceRank(graph.sentences)).isLeft)
  }

  /** Generated documents: a sequence of lemmas drawn from names, nouns, and pronouns. */
  private val genLemmas: Gen[Vector[String]] =
    Gen.choose(1, 8).flatMap { n =>
      Gen.listOfN(n, Gen.oneOf("man", "woman", "canoe", "he", "she", "they", "it")).map(_.toVector)
    }

  property("resolvableAt never returns a cluster whose first introducing mention is later") {
    forAll(genLemmas, Gen.choose(0, 7)) { (lemmas, seed) =>
      val (_, table, forms) = doc(lemmas)
      val ids = lemmas.indices.map(i => mid(s"m$i")).toVector
      // deterministic pairing from the seed: link i with (i + seed + 1) mod n when n > 1
      val pairs =
        if ids.size < 2 then Vector.empty
        else
          ids.indices
            .map(i => ids(i) -> ids((i + seed + 1) % ids.size))
            .toVector
            .filter(p => p._1 != p._2)
      val part = CorefPartition.fromPairs(story, pairs, table).fold(e => fail(e.message), identity)
      ids.forall { m =>
        val here = forms.positionOf(m).get
        forms.resolvableAt(m, part).forall { canonical =>
          part.clusters.find(_.canonical == canonical).forall { c =>
            forms.firstIntroducingMention(c).exists { first =>
              cats.Order[MentionPosition].lteqv(forms.positionOf(first).get, here)
            }
          }
        }
      }
    }
  }
