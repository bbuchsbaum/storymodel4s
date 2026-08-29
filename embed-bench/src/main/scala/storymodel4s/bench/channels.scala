package storymodel4s.bench

import cats.Id

import storymodel4s.align.{NodeSummary, SemanticDistance, SourceNodeRef, StructuralDistance}
import storymodel4s.core.{Checksum, ContentAddress}
import storymodel4s.embed.*
import storymodel4s.embed.grakern.GrakernStructuralDistance
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.recall.RecallUnit

/** Identity of the semantic side of a channel: which provider produced the vectors, in which
  * geometries, and how many provider calls it took. Fingerprints, never names, so two runs of the
  * "same" model with different artifacts never share a row.
  */
final case class SemanticIdentity(
    provider: ProviderFingerprint,
    querySpace: GeometryId,
    documentSpace: GeometryId,
    pairRule: GeometryPairRule,
    providerCalls: Int
):
  def render: String =
    s"semantic=${provider.render.take(12)} q=${querySpace.value.take(12)} d=${documentSpace.value.take(12)} pair=$pairRule calls=$providerCalls"

/** Identity of the structural side of a channel. */
enum StructuralIdentity:
  /** grakern `d_wl` over the given prepared source charts. */
  case Grakern(provider: ProviderFingerprint, preparedSources: Int)

  /** No structural channel: the structural cost term is `Missing` for every pair, and the report
    * says so through the term's coverage.
    */
  case Absent(reason: String)

  def render: String = this match
    case Grakern(p, n)  => s"structural=grakern:${p.render.take(12)} sources=$n"
    case Absent(reason) => s"structural=absent ($reason)"

/** One system under test: a semantic distance, a structural distance, and their identities.
  *
  * Why identities travel with the functions: every metric row in a report must name the exact
  * provider fingerprints and geometries it was computed under (ADR 0001 §D2), and a channel whose
  * structural side is absent must say so rather than silently scoring zero.
  */
final case class Channel(
    name: String,
    semantic: SemanticDistance,
    semanticIdentity: SemanticIdentity,
    structural: StructuralDistance,
    structuralIdentity: StructuralIdentity,
    /** Law I5 clause 4: declared, never inferred. See [[ChannelExposure]]. */
    exposure: ChannelExposure
):
  def identityChecksum: Checksum =
    ContentAddress.digest(
      Vector(
        "channel/v1",
        name,
        semanticIdentity.render,
        structuralIdentity.render,
        exposure.render
      )
    )

  def render: String =
    s"$name [${semanticIdentity.render}; ${structuralIdentity.render}; ${exposure.render}]"

enum ChannelError:
  case Embed(error: EmbedError)
  case Grakern(detail: String)
  case NoSpace(role: Role, view: SemanticView)

  def message: String = this match
    case Embed(e)      => e.toString
    case Grakern(m)    => m
    case NoSpace(r, v) => s"embedder exposes no ($r, $v) space"

/** Builds a [[SemanticDistance]] from any embed-core [[Embedder]] by embedding the recall units
  * (query role) and the source nodes (document role) once, in one batch per side, and taking the
  * cosine distance between validated vectors. Abstentions stay `Missing`.
  *
  * Why batch-once: provider calls are receipts; embedding per pair would multiply calls and make
  * the receipt count meaningless. The table is keyed by unit id and node ref, so the distance is a
  * pure lookup during inference.
  */
object EmbedderSemantic:
  def of(
      embedder: Embedder[Id],
      units: Vector[RecallUnit],
      nodes: Vector[(SourceNodeRef, String)],
      view: SemanticView = SemanticView.Surface
  ): Either[ChannelError, (SemanticDistance, SemanticIdentity)] =
    def space(role: Role): Either[ChannelError, EmbeddingSpace] =
      embedder.spaces
        .find(s => s.role == role && s.view == view)
        .toRight(ChannelError.NoSpace(role, view))
    def embed(
        texts: Vector[(String, String)],
        space: EmbeddingSpace
    ): Either[ChannelError, (Map[String, Estimate[ValidatedVector]], Int)] =
      if texts.isEmpty then Right((Map.empty, 0))
      else
        val requests = texts.map { case (key, text) =>
          EmbedRequest(RequestId.unsafe(key), EmbedPayload.Raw(text, Sensitivity.Public), space.id)
        }
        EmbedBatch.validated(requests, embedder.spaceIds).left.map(ChannelError.Embed(_)).map {
          batch =>
            val result = embedder.embed(batch)
            val byKey = result.outcomes.iterator.map { o =>
              val e: Estimate[ValidatedVector] = o.value.fold(
                _ => Estimate.missing(MissingReason.ProviderAbstained),
                identity
              )
              o.id.value -> e
            }.toMap
            (byKey, result.receipt.providerCalls.size)
        }
    for
      q <- space(Role.Query)
      d <- space(Role.Document)
      pair <- GeometryPair
        .validated(q, d, GeometryPairRule.IdenticalModelling)
        .left
        .map(ChannelError.Embed(_))
      unitVectors <- embed(units.map(u => s"unit:${u.id.value}" -> u.text), q)
      nodeVectors <- embed(nodes.map { case (ref, text) => s"node:${ref.key}" -> text }, d)
    yield
      val _ = pair
      val distance = SemanticDistance { (unit, node) =>
        val a = unitVectors._1.getOrElse(
          s"unit:${unit.id.value}",
          Estimate.missing(MissingReason.NotInLexicon)
        )
        val b = nodeVectors._1.getOrElse(
          s"node:${node.ref.key}",
          Estimate.missing(MissingReason.NotInLexicon)
        )
        Distances.cosineEstimate(a, b).map(_.value)
      }
      val identity = SemanticIdentity(
        embedder.info.provider,
        q.id,
        d.id,
        GeometryPairRule.IdenticalModelling,
        unitVectors._2 + nodeVectors._2
      )
      (distance, identity)

/** The free channels of the spike spec: hashed n-gram, TF-IDF, and grakern `d_wl`. */
object BenchChannels:
  /** Portable hashed character-n-gram embedder as the semantic side. */
  def hashedNgram(
      units: Vector[RecallUnit],
      nodes: Vector[(SourceNodeRef, String)],
      dimension: Int = 512,
      seed: Long = 0L,
      structural: (StructuralDistance, StructuralIdentity) = noStructure
  ): Either[ChannelError, Channel] =
    EmbedderSemantic.of(HashedNgramEmbedder[Id](dimension, seed), units, nodes).map {
      case (d, id) =>
        Channel(
          s"hashed-ngram:d$dimension:s$seed",
          d,
          id,
          structural._1,
          structural._2,
          ChannelExposure.NonMemorizing
        )
    }

  /** TF-IDF fitted on the given corpus (its fingerprint is part of the provider identity). Fitting
    * on the case's own texts is benchmark-tuned by construction — the channel name says so.
    */
  def tfIdf(
      units: Vector[RecallUnit],
      nodes: Vector[(SourceNodeRef, String)],
      corpus: Vector[String],
      structural: (StructuralDistance, StructuralIdentity) = noStructure
  ): Either[ChannelError, Channel] =
    for
      e <- TfIdfEmbedder.fit[Id](corpus).left.map(ChannelError.Embed(_))
      s <- EmbedderSemantic.of(e, units, nodes)
    yield Channel(
      s"tfidf:corpus-fit",
      s._1,
      s._2,
      structural._1,
      structural._2,
      ChannelExposure.NonMemorizing
    )

  /** No structural side: the structural term is `Missing` everywhere and reported as such. */
  val noStructure: (StructuralDistance, StructuralIdentity) =
    (StructuralDistance.missing, StructuralIdentity.Absent("no structural channel configured"))

  /** grakern `d_wl` prepared over the charts the view carries. With no charts on the view the
    * channel is honestly [[StructuralIdentity.Absent]] rather than a zero-scoring stub.
    */
  def grakern(
      nodes: Vector[NodeSummary],
      rounds: Int = 2
  ): Either[ChannelError, (StructuralDistance, StructuralIdentity)] =
    val charts = nodes.flatMap(_.evidence).map(_.chart)
    if charts.isEmpty then
      Right(
        (
          StructuralDistance.missing,
          StructuralIdentity.Absent("the source view carries no proposition charts")
        )
      )
    else
      GrakernStructuralDistance
        .prepare(charts, rounds)
        .left
        .map(e => ChannelError.Grakern(e.message))
        .map(d => (d, StructuralIdentity.Grakern(d.prepared.program.fingerprint, charts.size)))
