package storymodel4s.document

import cats.data.NonEmptyVector
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.core.NarrativeKind.EntityK
import storymodel4s.proposition.*
import storymodel4s.story.{EntityType, HolderGap}

/** Entity identity by referring form (ADR 0012): introducing mentions cluster by exact label, a
  * pronoun resolves only to a unique compatible antecedent, and everything else is open with its
  * candidates recorded. Every text below is one entity mention per sentence, so the rule's inputs
  * are legible in the test itself.
  */
class EntityIdentitySuite extends FunSuite:
  private def cid(s: String) = ConceptId.unsafe(s)
  private def sentence(n: Int) = SurfaceUnitId.unsafe(f"sent:$n%02d")
  private def mid(n: Int) = MentionId.unsafe[EntityK](f"m$n%02d")
  private val story = StoryId.unsafe("story:identity")
  private val custom = EntityType.Custom("chart", "entity")

  /** `go(a)` with `a` the given lemma; `quant` adds `a :quant n`. */
  private def chart(lemma: String, quant: Option[Int] = None): PropositionChart[Checked] =
    val p = cid("p")
    val a = cid("a")
    val concepts = Map(
      p -> Concept.predicate("go"),
      a -> Concept(Lemma.unsafe(lemma), None, None, ConceptKind.Entity)
    )
    val rels = Vector(PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(a))) ++
      quant.toVector.map(n =>
        PropositionRelation(
          a,
          RoleAssignment.named("quant"),
          ConceptTarget.Literal(LiteralValue.Number(BigDecimal(n)))
        )
      )
    ChartValidator
      .check(PropositionChart.unchecked(Some(p), concepts, rels))
      .fold(v => fail(v.mkString(",")), identity)

  /** One mention per sentence, in the given order; labels are the lemmas. `holders` gives, per
    * mention index, the indices of the mentions offered as holder of the frame it sits in.
    */
  private def resolve(
      lemmas: Vector[(String, Option[Int])],
      order: Vector[Int] = Vector.empty,
      holders: Map[Int, Vector[Int]] = Map.empty
  ): EntityIdentityResult =
    val charts = lemmas.zipWithIndex.map { case ((l, q), i) => sentence(i) -> chart(l, q) }
    val graph = MentionGraph.of(charts).fold(e => fail(e.message), identity)
    val entries = lemmas.indices.map(i => mid(i) -> ChartNodeRef(sentence(i), cid("a")))
    val permuted = if order.isEmpty then entries else order.map(entries)
    val table = MentionTable.of[EntityK](permuted, graph).fold(e => fail(e.message), identity)
    val rank = MentionForms.sentenceRank(lemmas.indices.map(sentence).toVector)
    val forms =
      MentionForms.infer(table, graph, _ => None, rank).fold(e => fail(e.message), identity)
    EntityIdentity
      .resolve(
        story,
        table,
        forms,
        m => (lemmas(m.value.drop(1).toInt)._1, custom),
        ref =>
          graph.chart(ref.sentence).map(ChartNumber.of(_, ref.concept)).getOrElse(Number.Unknown),
        m =>
          holders.get(m.value.drop(1).toInt).map { is =>
            NonEmptyVector
              .fromVector(is.map(i => entries(i)._2))
              .fold(HolderCandidate.Missing(HolderGap.NoCandidate))(HolderCandidate.Fillers.apply)
          }
      )
      .fold(e => fail(e.message), identity)

  private def n(l: String) = (l, None: Option[Int])

  test("introducing mentions cluster by exact folded label; a pronoun never joins by lemma") {
    val r = resolve(Vector(n("Man"), n("dog"), n("man"), n("he")))
    assertEquals(
      r.clusters.map(_.members.toVector.map(_.value)).sortBy(_.head),
      Vector(Vector("m00", "m02"), Vector("m01"))
    )
    assertEquals(r.identities(mid(0)), MentionIdentity.Introducing)
    assertEquals(r.identities(mid(2)), MentionIdentity.Introducing)
    assert(r.identities(mid(3)) != MentionIdentity.Introducing)
  }

  test("a third-person pronoun with exactly one preceding referent resolves to it") {
    val r = resolve(Vector(n("man"), n("he")))
    assertEquals(
      r.identities(mid(1)),
      MentionIdentity.Resolved(EntityIdentity.UniqueAntecedentRule)
    )
    assertEquals(r.clusters.map(_.members.toVector.map(_.value)), Vector(Vector("m00", "m01")))
    assertEquals(r.open, Vector.empty)
  }

  test("a pronoun before any referent is open with no antecedent: nothing resolves forward") {
    val r = resolve(Vector(n("he"), n("man")))
    assertEquals(
      r.identities(mid(0)),
      MentionIdentity.Open(OpenReference.NoAntecedent, Vector.empty)
    )
    assertEquals(r.clusters.map(_.members.toVector.map(_.value)), Vector(Vector("m01")))
  }

  test("several compatible referents leave the pronoun open with the candidates recorded") {
    val r = resolve(Vector(n("man"), n("dog"), n("he")))
    r.identities(mid(2)) match
      case MentionIdentity.Open(OpenReference.SeveralAntecedents, candidates) =>
        assertEquals(candidates.map(_.value), Vector("m00", "m01"))
      case other => fail(s"expected an open reference with two candidates, got $other")
    assertEquals(r.clusters.size, 2)
    assert(r.clusters.forall(_.members.length == 1), "an open pronoun joined a cluster")
  }

  test("number from :quant filters candidates: 'they' skips a singular, 'he' skips a plural") {
    val they = resolve(Vector(("man", Some(1)), ("warrior", Some(3)), n("they")))
    assertEquals(
      they.identities(mid(2)),
      MentionIdentity.Resolved(EntityIdentity.UniqueAntecedentRule)
    )
    assertEquals(they.clusters.find(_.members.length == 2).map(_.members.head.value), Some("m01"))
    val he = resolve(Vector(("man", Some(1)), ("warrior", Some(3)), n("he")))
    assertEquals(he.clusters.find(_.members.length == 2).map(_.members.head.value), Some("m00"))
    // Without a quantity the number is unknown and compatible with either pronoun.
    val open = resolve(Vector(n("man"), n("warrior"), n("they")))
    assert(open.identities(mid(2)) match
      case MentionIdentity.Open(OpenReference.SeveralAntecedents, _) => true
      case _                                                         => false)
  }

  test("first- and second-person pronouns in no held frame are open as outside speech") {
    val r = resolve(Vector(n("man"), n("I"), n("you"), n("we")))
    assertEquals(
      r.identities(mid(1)),
      MentionIdentity.Open(OpenReference.OutsideSpeech(Person.First), Vector.empty)
    )
    assertEquals(
      r.identities(mid(2)),
      MentionIdentity.Open(OpenReference.OutsideSpeech(Person.Second), Vector.empty)
    )
    assertEquals(
      r.identities(mid(3)),
      MentionIdentity.Open(OpenReference.OutsideSpeech(Person.First), Vector.empty)
    )
    assertEquals(r.clusters.map(_.members.length), Vector(1))
  }

  test("a first-person singular pronoun names the holder of its frame and joins that cluster") {
    val r = resolve(Vector(n("man"), n("I")), holders = Map(1 -> Vector(0)))
    assertEquals(r.identities(mid(1)), MentionIdentity.Resolved(EntityIdentity.SpeechHolderRule))
    assertEquals(r.clusters.map(_.members.toVector), Vector(Vector(mid(0), mid(1))))
  }

  test("a first-person plural is a group that includes the holder: open, holder as candidate") {
    val r = resolve(Vector(n("man"), n("we")), holders = Map(1 -> Vector(0)))
    assertEquals(
      r.identities(mid(1)),
      MentionIdentity.Open(OpenReference.SpeakerGroup, Vector(mid(0)))
    )
    assertEquals(r.clusters.map(_.members.length), Vector(1))
  }

  test("a second-person pronoun is the addressee the model does not represent: open") {
    val r = resolve(Vector(n("man"), n("you")), holders = Map(1 -> Vector(0)))
    assertEquals(
      r.identities(mid(1)),
      MentionIdentity.Open(OpenReference.NeedsAddressee, Vector(mid(0)))
    )
    assertEquals(r.clusters.map(_.members.length), Vector(1))
  }

  /** The holder is read after third-person pronouns resolve, so "the man ... he said: 'I ...'"
    * carries the man through "he" to "I"; a holder that is itself open carries nothing.
    */
  test("a holder that is a resolved pronoun carries its antecedent; an open holder carries none") {
    val through = resolve(Vector(n("man"), n("he"), n("I")), holders = Map(2 -> Vector(1)))
    assertEquals(
      through.identities(mid(2)),
      MentionIdentity.Resolved(EntityIdentity.SpeechHolderRule)
    )
    assertEquals(through.clusters.map(_.members.toVector), Vector(Vector(mid(0), mid(1), mid(2))))
    val open = resolve(
      Vector(n("man"), n("woman"), n("he"), n("I")),
      holders = Map(3 -> Vector(2))
    )
    assertEquals(
      open.identities(mid(3)),
      MentionIdentity.Open(OpenReference.NeedsSpeechHolder(Person.First), Vector.empty)
    )
    assertEquals(open.clusters.map(_.members.length), Vector(1, 1))
  }

  test(
    "a held frame whose holder is a gap leaves the pronoun needing a speech holder, not outside"
  ) {
    val r = resolve(Vector(n("man"), n("I")), holders = Map(1 -> Vector.empty))
    assertEquals(
      r.identities(mid(1)),
      MentionIdentity.Open(OpenReference.NeedsSpeechHolder(Person.First), Vector.empty)
    )
  }

  test("holder nodes in two clusters name no one holder: open, needing a speech holder") {
    val r = resolve(Vector(n("man"), n("woman"), n("I")), holders = Map(2 -> Vector(0, 1)))
    assertEquals(
      r.identities(mid(2)),
      MentionIdentity.Open(OpenReference.NeedsSpeechHolder(Person.First), Vector.empty)
    )
    assertEquals(r.clusters.map(_.members.length), Vector(1, 1))
  }

  /** The mention graph sorts sentences by id, and `s10` sorts before `s2`. Discourse rank must come
    * from the atlas order the caller passes; with ids that sort against discourse, a pronoun in the
    * second sentence must see only the first sentence's referent, not the tenth's.
    */
  test("discourse rank comes from the stated sentence order, not from sorted sentence ids") {
    def unpadded(n: Int) = SurfaceUnitId.unsafe(s"s$n")
    val lemmas = Vector("man") ++ Vector.fill(8)("dog").zipWithIndex.map((l, i) => s"$l$i") :+ "he"
    // sentences s1..s9 then s10 hold: man, dog0..dog7, and "he" in s2? No: build explicitly.
    val order = (1 to 11).toVector
    val texts: Vector[String] = Vector("man", "he") ++ (3 to 10).map(i => s"thing$i") :+ "cat"
    // s1 = man, s2 = he, s3..s10 = things, s11 = cat: "he" at s2 must resolve to man alone.
    val charts = order.zip(texts).map((n, l) => unpadded(n) -> chart(l))
    val graph = MentionGraph.of(charts).fold(e => fail(e.message), identity)
    val entries = order.zipWithIndex.map((n, i) => mid(i) -> ChartNodeRef(unpadded(n), cid("a")))
    val table = MentionTable.of[EntityK](entries, graph).fold(e => fail(e.message), identity)
    val rank = MentionForms.sentenceRank(order.map(unpadded))
    val forms =
      MentionForms.infer(table, graph, _ => None, rank).fold(e => fail(e.message), identity)
    val r = EntityIdentity
      .resolve(
        story,
        table,
        forms,
        m => (texts(m.value.drop(1).toInt), custom),
        _ => Number.Unknown
      )
      .fold(e => fail(e.message), identity)
    assertEquals(
      r.identities(mid(1)),
      MentionIdentity.Resolved(EntityIdentity.UniqueAntecedentRule)
    )
    assertEquals(r.open, Vector.empty)
    val _ = lemmas
  }

  test("the result does not depend on the order the table was built in") {
    val lemmas = Vector(n("man"), n("dog"), n("man"), n("he"), n("they"), n("cat"))
    val a = resolve(lemmas)
    val b = resolve(lemmas, order = Vector(5, 3, 1, 4, 0, 2))
    assertEquals(a.identities, b.identities)
    assertEquals(a.clusters, b.clusters)
  }

  test("an open mention is in no cluster and every cluster member is introducing or resolved") {
    val r = resolve(Vector(n("man"), n("he"), n("dog"), n("it"), n("I")))
    val members = r.clusters.flatMap(_.members.toVector).toSet
    r.identities.foreach { (m, id) =>
      id match
        case MentionIdentity.Open(_, _) => assert(!members.contains(m), s"open $m is a member")
        case _                          => assert(members.contains(m), s"$m is in no cluster")
    }
  }
