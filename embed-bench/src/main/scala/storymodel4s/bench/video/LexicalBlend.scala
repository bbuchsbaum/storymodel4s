package storymodel4s.bench.video

import storymodel4s.align.{NodeSummary, SemanticDistance, SourceNodeRef, SourceView}
import storymodel4s.recall.{Lexical, RecallUnit}

/** Re-ranks the semantic channel by blending it with an inverse-document-frequency lexical score.
  *
  * Why this exists. Three arms that changed the *text* being indexed all failed or dissolved under
  * a control, which pointed at the retrieval channel rather than its input. Measured with the
  * sequential model removed entirely, so that no prior could flatter either side, the pinned MiniLM
  * encoder and an Okapi BM25 score over the same node texts agree on the top-ranked segment for
  * only about a fifth of recall units, and a blend of the two ranks the plausible segment above
  * either channel alone. That complementarity is the thing this exploits: a mean-pooled sentence
  * embedding spreads its budget over a whole sentence, while BM25 concentrates on the rare words,
  * which for a film are the names, places and objects that distinguish one moment from another.
  *
  * Why a re-ranking and not a new distance. The blended score is mapped back onto the unit's own
  * multiset of semantic distances: the node the blend ranks first receives the smallest distance
  * the semantic channel produced for that unit, the second receives the second smallest, and so on.
  * Every unit therefore keeps the exact distance values it had before, and only their assignment to
  * nodes changes. Nothing downstream is rescaled, so the cost model's neutral substitution and any
  * threshold keep the meaning they were calibrated with, and the arm cannot win by inflating
  * confidence. It is a permutation, and it is judged as one.
  *
  * The weight is on the semantic side: `alpha = 1.0` reproduces the unblended channel exactly,
  * because the blend then ranks by the semantic distance itself and the remapping is the identity.
  *
  * Abstention is preserved rather than filled in. A pair the underlying channel declined to score
  * is absent from the table and the blended distance abstains for it too, so a missing embedding
  * never becomes a confident zero.
  */
object LexicalBlend:

  private val K1 = 1.5
  private val B = 0.75

  private final case class Bm25(
      termFreq: Vector[Map[String, Int]],
      lengths: Vector[Double],
      idf: Map[String, Double],
      averageLength: Double,
      postings: Map[String, Vector[Int]]
  ):
    /** Okapi BM25 of one query against every indexed document. */
    def score(query: Vector[String]): Vector[Double] =
      val out = Array.fill(termFreq.size)(0.0)
      query.distinct.foreach { term =>
        postings.get(term).foreach { docs =>
          val weight = idf.getOrElse(term, 0.0)
          docs.foreach { i =>
            val f = termFreq(i).getOrElse(term, 0).toDouble
            val denom = f + K1 * (1.0 - B + B * lengths(i) / averageLength)
            if denom > 0.0 then out(i) += weight * f * (K1 + 1.0) / denom
          }
        }
      }
      out.toVector

  private def index(docs: Vector[Vector[String]]): Bm25 =
    val termFreq = docs.map(_.groupBy(identity).view.mapValues(_.size).toMap)
    val lengths = docs.map(_.size.toDouble)
    val n = docs.size.toDouble
    val df = termFreq.foldLeft(Map.empty[String, Int]) { (acc, tf) =>
      tf.keys.foldLeft(acc)((m, t) => m.updated(t, m.getOrElse(t, 0) + 1))
    }
    val idf = df.map { case (t, d) =>
      t -> math.log(1.0 + (n - d.toDouble + 0.5) / (d.toDouble + 0.5))
    }
    val postings = termFreq.zipWithIndex
      .flatMap { case (tf, i) => tf.keys.map(_ -> i) }
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2))
      .toMap
    Bm25(termFreq, lengths, idf, math.max(lengths.sum / math.max(n, 1.0), 1e-9), postings)

  private def standardize(xs: Vector[Double]): Vector[Double] =
    if xs.size < 2 then xs.map(_ => 0.0)
    else
      val mean = xs.sum / xs.size
      val variance = xs.map(x => (x - mean) * (x - mean)).sum / xs.size
      val sd = math.sqrt(variance)
      if sd <= 1e-12 then xs.map(_ => 0.0) else xs.map(x => (x - mean) / sd)

  /** What the lexical index reads, which is deliberately not what the encoder reads.
    *
    * Arm 1 of the study prefixed each segment's embedded text with its location and cast and was
    * strictly worse on everything that moved: inside a mean-pooled embedding, vocabulary that
    * recurs across the episode dilutes the one discriminative field. A BM25 index has the opposite
    * response to the same input. It weights a term by rarity, so a name that appears in four
    * segments counts heavily and one that appears in four hundred counts for almost nothing, and no
    * term can crowd out another. `WithLemmas` therefore routes exactly the metadata that failed as
    * embedded text to the only channel that can price it, and the encoder never sees it.
    */
  enum LexicalFields:
    /** The node's embedded rendering alone. */
    case TextOnly

    /** That rendering, plus each of the node's content lemmas that it does not already contain.
      * Added once each, so a name contributes its rarity rather than its repetition count.
      */
    case WithLemmas

  object LexicalFields:
    /** `WithLemmas` is the default: it is what the development set chose, at +0.0723 Kendall tau
      * against the unblended channel versus +0.0433 for the node text alone.
      */
    def parse(raw: Option[String]): LexicalFields = raw.map(_.trim.toLowerCase) match
      case Some("text") | Some("text-only") => TextOnly
      case _                                => WithLemmas

  /** The blended channel. `alpha` is the weight on the semantic side, in `(0, 1]`. */
  def blended(
      base: SemanticDistance,
      units: Vector[RecallUnit],
      view: SourceView,
      nodeTexts: Vector[(SourceNodeRef, String)],
      alpha: Double,
      fields: LexicalFields = LexicalFields.WithLemmas
  ): SemanticDistance =
    val nodes: Vector[NodeSummary] = view.nodes
    val textByRef = nodeTexts.toMap
    val bm25 = index(nodes.map { n =>
      val stems = Lexical.stems(textByRef.getOrElse(n.ref, ""))
      fields match
        case LexicalFields.TextOnly   => stems
        case LexicalFields.WithLemmas => stems ++ (n.lemmas -- stems.toSet).toVector.sorted
    })
    val table = units.flatMap { unit =>
      val scored = nodes.zipWithIndex.flatMap { case (node, i) =>
        base(unit, node).toOption.map(d => (i, node.ref, d))
      }
      if scored.isEmpty then Vector.empty
      else
        val lexical = bm25.score(Lexical.stems(unit.text))
        // Both sides as "higher is better": the semantic side is a distance, so it is negated.
        val semanticZ = standardize(scored.map { case (_, _, d) => -d })
        val lexicalZ = standardize(scored.map { case (i, _, _) => lexical(i) })
        val blend = semanticZ.indices.map(k => alpha * semanticZ(k) + (1.0 - alpha) * lexicalZ(k))
        val ascending = scored.map { case (_, _, d) => d }.sorted
        blend.indices
          .sortBy(k => -blend(k))
          .zipWithIndex
          .map { case (k, rank) => (unit.id, scored(k)._2) -> ascending(rank) }
          .toVector
    }.toMap
    SemanticDistance.fromTableOrAbstain(table)
