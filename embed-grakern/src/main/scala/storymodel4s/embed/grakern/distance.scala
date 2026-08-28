package storymodel4s.embed.grakern

import java.util.concurrent.atomic.AtomicReference

import cats.kernel.Hash

import grakern.core.{Projection, SampleKey, StableCodec, StableId, StableVersion}
import grakern.engine.{WLCompiledKernel, WLCompiler, WLQueryResult}
import grakern.standard.wl.{WLCodecs, WLKernel, WLRefinement, optimalAssignment, subtree}

import storymodel4s.align.StructuralDistance
import storymodel4s.core.{Checksum, ContentAddress, ProviderCall}
import storymodel4s.embed.{
  Dimension,
  EmbeddingSpace,
  Normalization,
  ProviderFingerprint,
  Role,
  SemanticView,
  TruncationPolicy,
  ValidatedVector
}
import storymodel4s.embed.EmbedError
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.proposition.{Canonical, CheckState, PropositionChart, PropositionEvidence}

/** Errors of the structural channel. */
enum GrakernError:
  case Reification(sample: SampleKey, detail: String)
  case Refinement(detail: String)
  case Compile(detail: String)
  case Query(sample: SampleKey, detail: String)

  def message: String = this match
    case Reification(s, d) => s"reification of ${s.value} failed: $d"
    case Refinement(d)     => s"WL refinement: $d"
    case Compile(d)        => s"grakern compile: $d"
    case Query(s, d)       => s"grakern query for ${s.value} failed: $d"

/** The fixed WL program of the structural channel: `rounds` refinement rounds over reified charts,
  * `subtree + optimalAssignment`, normalized. Codecs are named and versioned so grakern's
  * `SemanticFingerprint` is durable.
  */
final case class StructuralProgram private (
    rounds: Int,
    refinement: WLRefinement[ReifiedKey, ArcKind, ReifiedKey, ArcKind],
    kernel: WLKernel[ReifiedKey, ArcKind, ReifiedKey, ArcKind]
):
  /** Static identity of this program (ADR 0001 §D2): grakern revision, codec ids/versions, rounds.
    */
  val fingerprint: ProviderFingerprint =
    ProviderFingerprint.of(
      modelArtifact = s"grakern@${GrakernPin.revision}",
      tokenizerArtifact = s"${StructuralProgram.NodeCodecId}@${StructuralProgram.CodecVersion}",
      implementation = s"storymodel4s.embed.grakern:wl-subtree+oa:normalized:rounds=$rounds",
      runtime = "jvm"
    )

  val providerName: String = "grakern"
  val modelName: String = s"wl.subtree+oa.normalized.r$rounds"

object StructuralProgram:
  val NodeCodecId = "storymodel4s.embed.grakern.reified-key"
  val EdgeCodecId = "storymodel4s.embed.grakern.arc-kind"
  val CodecVersion = 1

  private given Hash[ReifiedKey] = ReifiedKey.given_Hash_ReifiedKey
  private given Hash[ArcKind] = ArcKind.given_Hash_ArcKind

  def of(rounds: Int): Either[GrakernError, StructuralProgram] =
    for
      nodeId <- StableId.from(NodeCodecId).left.map(e => GrakernError.Refinement(e.toString))
      edgeId <- StableId.from(EdgeCodecId).left.map(e => GrakernError.Refinement(e.toString))
      version <- StableVersion.from(CodecVersion).left.map(e => GrakernError.Refinement(e.toString))
      nodeKey = Projection.named[ReifiedKey, ReifiedKey](nodeId, version)(identity)
      edgeKey = Projection.named[ArcKind, ArcKind](edgeId, version)(identity)
      codecs = WLCodecs[ReifiedKey, ArcKind](
        Some(StableCodec.named[ReifiedKey](nodeId, version)(_.render)),
        Some(StableCodec.named[ArcKind](edgeId, version)(_.render))
      )
      refinement <- WLRefinement
        .create[ReifiedKey, ArcKind, ReifiedKey, ArcKind](rounds, nodeKey, edgeKey, codecs)
        .left
        .map(e => GrakernError.Refinement(e.toString))
    yield StructuralProgram(
      rounds,
      refinement,
      (refinement.subtree + refinement.optimalAssignment).normalized
    )

/** Structural `EmbeddingSpace` identities (`structural.*` family, ADR 0001 §D4b). They are exposed
  * so receipts, feature-use ledgers and benches can name the channel; vectors exist only relative
  * to a prepared source dictionary (see [[PreparedSources.vectorOf]]).
  */
object StructuralSpaces:
  def of(program: StructuralProgram, dictionarySize: Int): Either[EmbedError, EmbeddingSpace] =
    Dimension.of(math.max(1, dictionarySize)).flatMap { dim =>
      EmbeddingSpace.of(
        provider = program.fingerprint,
        role = Role.Document,
        view = SemanticView.Custom("structural", s"wl.grakern.r${program.rounds}"),
        instruction = None,
        dimension = dim,
        normalization = Normalization.L2,
        truncation = TruncationPolicy.Reject
      )
    }

/** Immutable prepared source state: the source charts compiled once, queried many times.
  *
  * Why prepared: grakern's query overlay aligns a query chart to the source dictionary without
  * recompiling the source Gram; recall units are queries, source nodes are the prepared set.
  */
final class PreparedSources private (
    val program: StructuralProgram,
    private val compiled: WLCompiledKernel[String, ReifiedKey, ArcKind, ReifiedKey, ArcKind],
    private val indexByChecksum: Map[Checksum, Int],
    val sourceChecksums: Vector[Checksum]
):
  import PreparedSources.traverseEither

  def size: Int = sourceChecksums.size
  def contains(chart: PropositionChart[CheckState.Checked]): Boolean =
    indexByChecksum.contains(Canonical.checksum(chart))

  /** Align a batch of query charts against the prepared set with ONE query-overlay compilation
    * (`WLCompiledKernel.cross`, the API committed at the pinned grakern revision). The prepared
    * source state is never recompiled; query-only colours stay in the overlay.
    *
    * Cost: one refinement pass over the batch plus one sparse cross product against the prepared
    * feature matrix — `O(Σ query size + nnz)`; batching all recall charts of one alignment into a
    * single call is therefore preferred over one call per unit.
    */
  def crossOf(
      queries: Vector[PropositionChart[CheckState.Checked]]
  ): Either[GrakernError, WLQueryResult] =
    val keyed = queries.map(q => (SampleKey(s"query:${Canonical.checksum(q).hex}"), q))
    for
      reified <- keyed.traverseEither { case (key, q) =>
        ChartNeighbourhood.of(q, key).left.map(e => GrakernError.Reification(key, e.toString))
      }
      result <- compiled
        .cross(reified.map(_.sample))
        .left
        .map(e => GrakernError.Query(keyed.headOption.fold(SampleKey("query"))(_._1), e.toString))
    yield result

  /** Normalized kernel values of each query against every prepared source, keyed by query checksum
    * then source checksum. One `cross` call for the whole batch.
    */
  def kernelRows(
      queries: Vector[PropositionChart[CheckState.Checked]]
  ): Either[GrakernError, Map[Checksum, Map[Checksum, Double]]] =
    if queries.isEmpty then Right(Map.empty)
    else
      crossOf(queries).map { result =>
        val values = result.cross.values
        queries.zipWithIndex.map { case (q, qi) =>
          Canonical.checksum(q) ->
            sourceChecksums.zipWithIndex.map { case (c, si) => c -> values(qi, si) }.toMap
        }.toMap
      }

  /** Normalized kernel values of `query` against every prepared source, keyed by source checksum.
    */
  def kernelRow(
      query: PropositionChart[CheckState.Checked]
  ): Either[GrakernError, Map[Checksum, Double]] =
    kernelRows(Vector(query)).map(_.getOrElse(Canonical.checksum(query), Map.empty))

  /** A dense L2-normalized vector over the prepared feature dictionary (novel query colours are
    * dropped — their mass is retained only in the kernel value). Only meaningful for benches.
    */
  def vectorOf(query: PropositionChart[CheckState.Checked]): Either[GrakernError, ValidatedVector] =
    val key = SampleKey(s"query:${Canonical.checksum(query).hex}")
    for
      result <- crossOf(Vector(query))
      shared = result.features.shared
      dim = math.max(1, shared.cols)
      dense = {
        val arr = Array.fill(dim)(0.0)
        shared.foreachStoredEntry { (row, col, value) =>
          if row == 0 && col < dim then arr(col) = value
        }
        arr.toVector
      }
      vec <- Dimension
        .of(dim)
        .flatMap(d => ValidatedVector.l2(d, dense))
        .left
        .map(e => GrakernError.Query(key, e.message))
    yield vec

object PreparedSources:
  def of(
      program: StructuralProgram,
      sources: Vector[PropositionChart[CheckState.Checked]]
  ): Either[GrakernError, PreparedSources] =
    val distinct = sources.map(c => Canonical.checksum(c) -> c).distinctBy(_._1)
    val reified = distinct.traverseEither { case (c, chart) =>
      ChartNeighbourhood
        .of(chart, SampleKey(s"source:${c.hex}"))
        .left
        .map(e => GrakernError.Reification(SampleKey(c.hex), e.toString))
    }
    for
      samples <- reified
      compiled <- WLCompiler
        .plan(samples.map(_.sample), program.kernel)
        .run()
        .left
        .map(e => GrakernError.Compile(e.toString))
    yield new PreparedSources(
      program,
      compiled,
      distinct.map(_._1).zipWithIndex.toMap,
      distinct.map(_._1)
    )

  extension [A, E, B](v: Vector[A])
    private def traverseEither(f: A => Either[E, B]): Either[E, Vector[B]] =
      v.foldLeft[Either[E, Vector[B]]](Right(Vector.empty)) { (acc, a) =>
        acc.flatMap(bs => f(a).map(bs :+ _))
      }

/** `d_wl` (ADR 0001 §D4b): `1 − normalizedKernel(unit chart, source chart)` from grakern's query
  * overlay; `Missing` when the source chart was not prepared and cannot be aligned. Every query is
  * receipted as a `ProviderCall`; query rows are memoized per unit-chart checksum so each recall
  * unit is aligned once against the whole prepared set.
  *
  * This is a distance, never an adjudicator: the ModeGate decides admissibility from
  * `ChartCompatibility`/`ContradictionDetector`; `d_wl` only grades admissible pairs.
  */
final class GrakernStructuralDistance private (
    val prepared: PreparedSources,
    private val rowCache: AtomicReference[Map[Checksum, Map[Checksum, Double]]],
    private val log: AtomicReference[Vector[ProviderCall]]
) extends StructuralDistance:

  def receipts: Vector[ProviderCall] = log.get()

  def apply(unit: PropositionEvidence, node: PropositionEvidence): Estimate[Double] =
    val uc = Canonical.checksum(unit.chart)
    val nc = Canonical.checksum(node.chart)
    if !prepared.sourceChecksums.contains(nc) then Estimate.missing(MissingReason.Excluded)
    else
      rowFor(uc, unit.chart) match
        case Left(_)    => Estimate.missing(MissingReason.ProviderAbstained)
        case Right(row) =>
          row.get(nc) match
            case None    => Estimate.missing(MissingReason.Excluded)
            case Some(k) =>
              val d = 1.0 - k
              if d.isNaN || d.isInfinite then Estimate.missing(MissingReason.ProviderAbstained)
              else if math.abs(d) < GrakernStructuralDistance.Epsilon then Estimate.observed(0.0)
              else Estimate.observed(math.min(1.0, math.max(0.0, d)))

  private def rowFor(
      uc: Checksum,
      chart: PropositionChart[CheckState.Checked]
  ): Either[GrakernError, Map[Checksum, Double]] =
    rowCache.get().get(uc) match
      case Some(row) => Right(row)
      case None      =>
        prepared.kernelRow(chart).map { row =>
          rowCache.updateAndGet(_.updated(uc, row))
          log.updateAndGet(_ :+ receipt(uc, row))
          row
        }

  private def receipt(uc: Checksum, row: Map[Checksum, Double]): ProviderCall =
    val out = ContentAddress.digest(
      row.toVector.sortBy(_._1.hex).map { case (c, k) => s"${c.hex}=${k.toString}" }
    )
    ProviderCall(
      provider = prepared.program.providerName,
      model = prepared.program.modelName,
      version = GrakernPin.revision,
      promptTemplateVersion = None,
      inputChecksum = ContentAddress.digest(Vector(uc.hex) ++ prepared.sourceChecksums.map(_.hex)),
      outputChecksum = out,
      params = Map(
        "rounds" -> prepared.program.rounds.toString,
        "codecs" -> s"${StructuralProgram.NodeCodecId}@${StructuralProgram.CodecVersion}",
        "fingerprint" -> prepared.program.fingerprint.render,
        "transitive" -> s"graph4s+gale via grakern@${GrakernPin.revision}",
        "sources" -> prepared.size.toString
      ),
      seed = None,
      cached = false
    )

object GrakernStructuralDistance:
  /** Kernel values within this of 1.0 are treated as exact identity (floating-point noise of the
    * normalized kernel, e.g. 2.2e-16); it never affects the ordering of distinct structures.
    */
  val Epsilon: Double = 1e-9

  def of(prepared: PreparedSources): GrakernStructuralDistance =
    new GrakernStructuralDistance(
      prepared,
      new AtomicReference(Map.empty),
      new AtomicReference(Vector.empty)
    )

  /** Prepare the given source charts under a program with `rounds` refinement rounds. */
  def prepare(
      sources: Vector[PropositionChart[CheckState.Checked]],
      rounds: Int = 2
  ): Either[GrakernError, GrakernStructuralDistance] =
    for
      program <- StructuralProgram.of(rounds)
      prepared <- PreparedSources.of(program, sources)
    yield of(prepared)
