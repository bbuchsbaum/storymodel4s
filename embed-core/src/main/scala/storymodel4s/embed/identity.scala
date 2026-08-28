package storymodel4s.embed

import cats.{Hash, Order, Show}

import storymodel4s.core.{Checksum, ContentAddress, FeatureSpaceId, Fingerprint, OpaqueId}
import storymodel4s.features.{FeatureSpace, FeatureValueSchema}

/** Static identity of a provider: model artifact, tokenizer artifact, implementation, runtime.
  *
  * Why separate from [[EmbeddingSpace]]: one provider serves many recipes (role, view, instruction,
  * truncation). Conflating the two lets a query vector silently compare against a document vector
  * from a different recipe (Codex review P0-2).
  */
final case class ProviderFingerprint(value: Checksum):
  def render: String = value.hex

object ProviderFingerprint:
  /** Content-address the static provider description. Parts must be stable strings (artifact
    * checksums, version tags), never wall-clock or paths.
    */
  def of(
      modelArtifact: String,
      tokenizerArtifact: String,
      implementation: String,
      runtime: String
  ): ProviderFingerprint =
    ProviderFingerprint(
      ContentAddress.digest(
        Vector("provider", modelArtifact, tokenizerArtifact, implementation, runtime)
      )
    )

  given Show[ProviderFingerprint] = Show.show(_.render)
  given Order[ProviderFingerprint] = Order.by(_.render)
  given Hash[ProviderFingerprint] = Hash.fromUniversalHashCode

/** Vector length; strictly positive. */
object Dimension:
  opaque type Dimension = Int
  def of(n: Int): Either[EmbedError, Dimension] =
    if n <= 0 then Left(EmbedError.InvalidDimension(n)) else Right(n)
  def unsafe(n: Int): Dimension =
    of(n).fold(e => throw new IllegalArgumentException(e.message), identity)
  extension (d: Dimension) def value: Int = d
  given Show[Dimension] = Show.show(_.toString)
  given Order[Dimension] = Order[Int]
type Dimension = Dimension.Dimension

/** Declared normalization of stored vectors. `L2` vectors have unit norm within tolerance. */
enum Normalization:
  case Unnormalized
  case L2

  def render: String = this match
    case Unnormalized => "unnormalized"
    case L2           => "l2"

/** What the provider does with inputs longer than its context. Part of recipe identity because it
  * changes which text the vector represents.
  */
enum TruncationPolicy:
  case Reject
  case KeepHead(maxTokens: Int)
  case KeepTail(maxTokens: Int)

  def render: String = this match
    case Reject      => "reject"
    case KeepHead(n) => s"head:$n"
    case KeepTail(n) => s"tail:$n"

/** Whether the vector was produced for the query side or the document side of retrieval. Part of
  * identity, never call metadata.
  */
enum Role:
  case Query
  case Document

  def render: String = this match
    case Query    => "query"
    case Document => "document"

/** Which rendering of a node was embedded (ADR 0001 §D4). Late-pooled contextual vectors are a
  * distinct view, never a silent fallback to the template view.
  */
enum SemanticView:
  case Surface
  case Gloss
  case ContextualTemplate
  case ContextualLatePooled
  case Segment
  case Custom(namespace: String, name: String)

  def render: String = this match
    case Surface              => "surface"
    case Gloss                => "gloss"
    case ContextualTemplate   => "contextual.template"
    case ContextualLatePooled => "contextual.latepooled"
    case Segment              => "segment"
    case Custom(ns, n)        => s"custom:$ns:$n"

object SemanticView:
  given Order[SemanticView] = Order.by(_.render)

/** Digest of the exact instruction string sent with a request; the string itself never enters
  * identity or receipts.
  */
final case class InstructionDigest(value: Checksum):
  def render: String = value.hex

object InstructionDigest:
  def of(instruction: String): InstructionDigest = InstructionDigest(Checksum.ofText(instruction))

/** Pooling rule for late-pooled views. */
enum PoolingRule:
  case Mean
  case Max
  case FirstToken
  def render: String = this match
    case Mean       => "mean"
    case Max        => "max"
    case FirstToken => "first"

/** What happens when the node's support is not fully covered by the pooled token ranges. */
enum UncoveredPolicy:
  case PartialCoverage
  case Missing
  def render: String = this match
    case PartialCoverage => "partial"
    case Missing         => "missing"

/** Identity of a late-pooled contextual view (ADR 0001 §D4/§D4d). Every field that changes which
  * tokens are pooled is part of identity.
  */
final case class LatePoolingRecipe(
    documentDigest: Checksum,
    tokenizerFingerprint: Fingerprint,
    contextLimit: Int,
    window: Int,
    stride: Int,
    overlapMerge: String,
    pooling: PoolingRule,
    uncovered: UncoveredPolicy,
    matryoshkaDimension: Option[Int]
):
  def parts: Vector[String] =
    Vector(
      documentDigest.hex,
      tokenizerFingerprint.value,
      contextLimit.toString,
      window.toString,
      stride.toString,
      overlapMerge,
      pooling.render,
      uncovered.render,
      matryoshkaDimension.fold("full")(_.toString)
    )

object LatePoolingRecipe:
  def validated(r: LatePoolingRecipe): Either[EmbedError, LatePoolingRecipe] =
    if r.contextLimit <= 0 then Left(EmbedError.InvalidRecipe("contextLimit must be positive"))
    else if r.window <= 0 || r.window > r.contextLimit then
      Left(EmbedError.InvalidRecipe("window must be in (0, contextLimit]"))
    else if r.stride <= 0 || r.stride > r.window then
      Left(EmbedError.InvalidRecipe("stride must be in (0, window]"))
    else if r.matryoshkaDimension.exists(_ <= 0) then
      Left(EmbedError.InvalidRecipe("matryoshka dimension must be positive"))
    else Right(r)

/** Content-addressed identity of one embedding recipe. */
object GeometryId extends OpaqueId("GeometryId")
type GeometryId = GeometryId.T

/** One embedding recipe. A `FeatureSpace` is derived from it; vectors of different recipes are
  * never compared unless a [[GeometryPair]] declares them compatible.
  */
final case class EmbeddingSpace private[embed] (
    id: GeometryId,
    provider: ProviderFingerprint,
    role: Role,
    view: SemanticView,
    instruction: Option[InstructionDigest],
    dimension: Dimension,
    normalization: Normalization,
    truncation: TruncationPolicy,
    latePooling: Option[LatePoolingRecipe],
    parent: Option[GeometryId]
):
  /** Matryoshka truncation: a derived, re-normalized space with its own identity and a recorded
    * parent. Fails if the target dimension is not smaller than the current one.
    */
  def truncated(dim: Dimension): Either[EmbedError, EmbeddingSpace] =
    if dim.value >= dimension.value then
      Left(
        EmbedError.InvalidRecipe(s"truncation ${dim.value} must be smaller than ${dimension.value}")
      )
    else
      Right(
        EmbeddingSpace.build(
          provider,
          role,
          view,
          instruction,
          dim,
          Normalization.L2,
          truncation,
          latePooling.map(_.copy(matryoshkaDimension = Some(dim.value))),
          Some(id)
        )
      )

  /** The `features` space this recipe populates. Vectors live in sidecars; the graph never holds
    * them.
    */
  def toFeatureSpace: FeatureSpace[Vector[Double]] =
    FeatureSpace(
      id = FeatureSpaceId.unsafe(s"embed:${view.render}:${role.render}:${id.value}"),
      description = s"${view.render} ${role.render} embedding (${provider.render.take(12)})",
      valueSchema = FeatureValueSchema.Vector(dimension.value),
      units = None,
      provider = Fingerprint.unsafe(provider.render),
      normalized = normalization == Normalization.L2,
      normalizationPopulation = None
    )

  def identityParts: Vector[String] =
    Vector(
      provider.render,
      role.render,
      view.render,
      instruction.fold("no-instruction")(_.render),
      dimension.value.toString,
      normalization.render,
      truncation.render
    ) ++ latePooling.fold(Vector("no-latepool"))(r => "latepool" +: r.parts) ++
      parent.fold(Vector("no-parent"))(p => Vector("parent", p.value))

object EmbeddingSpace:
  private[embed] def build(
      provider: ProviderFingerprint,
      role: Role,
      view: SemanticView,
      instruction: Option[InstructionDigest],
      dimension: Dimension,
      normalization: Normalization,
      truncation: TruncationPolicy,
      latePooling: Option[LatePoolingRecipe],
      parent: Option[GeometryId]
  ): EmbeddingSpace =
    val provisional = EmbeddingSpace(
      GeometryId.unsafe("pending"),
      provider,
      role,
      view,
      instruction,
      dimension,
      normalization,
      truncation,
      latePooling,
      parent
    )
    provisional.copy(id =
      GeometryId.unsafe(ContentAddress.of("geometry", provisional.identityParts*))
    )

  /** Construct a root recipe (no parent). Late-pooled views must carry a validated recipe and the
    * `ContextualLatePooled` view; other views must not carry one.
    */
  def of(
      provider: ProviderFingerprint,
      role: Role,
      view: SemanticView,
      instruction: Option[InstructionDigest],
      dimension: Dimension,
      normalization: Normalization,
      truncation: TruncationPolicy,
      latePooling: Option[LatePoolingRecipe] = None
  ): Either[EmbedError, EmbeddingSpace] =
    (view, latePooling) match
      case (SemanticView.ContextualLatePooled, None) =>
        Left(EmbedError.InvalidRecipe("late-pooled view requires a LatePoolingRecipe"))
      case (SemanticView.ContextualLatePooled, Some(r)) =>
        LatePoolingRecipe
          .validated(r)
          .map(v =>
            build(
              provider,
              role,
              view,
              instruction,
              dimension,
              normalization,
              truncation,
              Some(v),
              None
            )
          )
      case (_, Some(_)) =>
        Left(EmbedError.InvalidRecipe("only the late-pooled view may carry a LatePoolingRecipe"))
      case (_, None) =>
        Right(
          build(provider, role, view, instruction, dimension, normalization, truncation, None, None)
        )

  given Show[EmbeddingSpace] = Show.show(s => s"EmbeddingSpace(${s.id.value})")

/** A validated query/document pair whose vectors may be compared. */
final case class GeometryPair private (query: GeometryId, document: GeometryId)

object GeometryPair:
  /** Compatible iff both come from the same provider, dimension, normalization, truncation and
    * late-pooling recipe, with roles Query and Document respectively. Views may differ (a recall
    * unit's surface may be compared to a node's gloss) — that is a modelling choice recorded in the
    * pair, not an error.
    */
  def validated(query: EmbeddingSpace, document: EmbeddingSpace): Either[EmbedError, GeometryPair] =
    def bad(reason: String) =
      Left(EmbedError.IncompatibleSpaces(query.id.value, document.id.value, reason))
    if query.role != Role.Query then bad("query space does not have role Query")
    else if document.role != Role.Document then bad("document space does not have role Document")
    else if query.provider != document.provider then bad("different providers")
    else if query.dimension != document.dimension then bad("different dimensions")
    else if query.normalization != document.normalization then bad("different normalizations")
    else if query.truncation != document.truncation then bad("different truncation policies")
    else if query.latePooling.map(_.matryoshkaDimension) != document.latePooling.map(
        _.matryoshkaDimension
      )
    then bad("different late-pooling dimensions")
    else Right(GeometryPair(query.id, document.id))
