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

/** Prevents a policy from authorizing a same-named embedder after its advertised version changes.
  */
object PolicyModelIdentity:
  opaque type PolicyModelIdentity = String

  private val Prefix = "policy-model/v1|"

  private def encodedPart(value: String): String = s"${value.length}:$value"

  private def encode(name: String, version: String): PolicyModelIdentity =
    Prefix + encodedPart(name) + encodedPart(version)

  private def readPart(value: String, offset: Int): Option[(String, Int)] =
    val colon = value.indexOf(':', offset)
    if colon <= offset then None
    else
      val lengthText = value.substring(offset, colon)
      if !lengthText.forall(_.isDigit) then None
      else
        lengthText.toIntOption.flatMap { length =>
          val start = colon + 1
          val end = start.toLong + length.toLong
          if end > value.length.toLong then None
          else Some(value.substring(start, end.toInt) -> end.toInt)
        }

  private[embed] def from(info: EmbedderInfo): PolicyModelIdentity =
    encode(info.name, info.version)

  private[embed] def parse(
      rendered: String,
      info: EmbedderInfo
  ): Option[PolicyModelIdentity] =
    Option.when(rendered.startsWith(Prefix))(rendered.drop(Prefix.length)).flatMap { body =>
      for
        (name, versionOffset) <- readPart(body, 0)
        (version, end) <- readPart(body, versionOffset)
        if end == body.length
        canonical = encode(name, version)
        if canonical == rendered
        if name == info.name && version == info.version
      yield from(info)
    }

  extension (identity: PolicyModelIdentity) def render: String = identity

  given Show[PolicyModelIdentity] = Show.show(_.render)
  given Order[PolicyModelIdentity] = Order.by(_.render)
  given Hash[PolicyModelIdentity] = Hash.fromUniversalHashCode

type PolicyModelIdentity = PolicyModelIdentity.PolicyModelIdentity

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
  *
  * Why a non-case class: `validated` was a self-returning inspector on a public case class, so
  * `fromProduct` could mint a window or stride the inspector would refuse. Construction is
  * `of(...)` only.
  */
final class LatePoolingRecipe private (
    val documentDigest: Checksum,
    val tokenizerFingerprint: Fingerprint,
    val contextLimit: Int,
    val window: Int,
    val stride: Int,
    val overlapMerge: String,
    val pooling: PoolingRule,
    val uncovered: UncoveredPolicy,
    val matryoshkaDimension: Option[Int]
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

  private[embed] def compatibilityKey: LatePoolingCompatibilityKey =
    LatePoolingCompatibilityKey(
      tokenizerFingerprint,
      contextLimit,
      window,
      stride,
      overlapMerge,
      pooling,
      uncovered,
      matryoshkaDimension
    )

  override def equals(other: Any): Boolean = other match
    case that: LatePoolingRecipe =>
      documentDigest == that.documentDigest &&
      tokenizerFingerprint == that.tokenizerFingerprint &&
      contextLimit == that.contextLimit &&
      window == that.window &&
      stride == that.stride &&
      overlapMerge == that.overlapMerge &&
      pooling == that.pooling &&
      uncovered == that.uncovered &&
      matryoshkaDimension == that.matryoshkaDimension
    case _ => false

  override def hashCode(): Int =
    (
      documentDigest,
      tokenizerFingerprint,
      contextLimit,
      window,
      stride,
      overlapMerge,
      pooling,
      uncovered,
      matryoshkaDimension
    ).hashCode()

  override def toString: String =
    s"LatePoolingRecipe(window=$window, stride=$stride, limit=$contextLimit)"

/** The late-pooling fields that must agree before two vector spaces can be compared. */
private[embed] final case class LatePoolingCompatibilityKey(
    tokenizerFingerprint: Fingerprint,
    contextLimit: Int,
    window: Int,
    stride: Int,
    overlapMerge: String,
    pooling: PoolingRule,
    uncovered: UncoveredPolicy,
    matryoshkaDimension: Option[Int]
):
  def mismatch(other: LatePoolingCompatibilityKey): Option[String] =
    if tokenizerFingerprint != other.tokenizerFingerprint then
      Some("different late-pooling tokenizer fingerprints")
    else if contextLimit != other.contextLimit then Some("different late-pooling context limits")
    else if window != other.window then Some("different late-pooling windows")
    else if stride != other.stride then Some("different late-pooling strides")
    else if overlapMerge != other.overlapMerge then Some("different late-pooling overlap merges")
    else if pooling != other.pooling then Some("different late-pooling pooling rules")
    else if uncovered != other.uncovered then Some("different late-pooling uncovered policies")
    else if matryoshkaDimension != other.matryoshkaDimension then
      Some("different late-pooling matryoshka dimensions")
    else None

object LatePoolingRecipe:
  def of(
      documentDigest: Checksum,
      tokenizerFingerprint: Fingerprint,
      contextLimit: Int,
      window: Int,
      stride: Int,
      overlapMerge: String,
      pooling: PoolingRule,
      uncovered: UncoveredPolicy,
      matryoshkaDimension: Option[Int]
  ): Either[EmbedError, LatePoolingRecipe] =
    check(contextLimit, window, stride, matryoshkaDimension).map(_ =>
      new LatePoolingRecipe(
        documentDigest,
        tokenizerFingerprint,
        contextLimit,
        window,
        stride,
        overlapMerge,
        pooling,
        uncovered,
        matryoshkaDimension
      )
    )

  def validated(r: LatePoolingRecipe): Either[EmbedError, LatePoolingRecipe] =
    check(r.contextLimit, r.window, r.stride, r.matryoshkaDimension).map(_ => r)

  private def check(
      contextLimit: Int,
      window: Int,
      stride: Int,
      matryoshkaDimension: Option[Int]
  ): Either[EmbedError, Unit] =
    if contextLimit <= 0 then Left(EmbedError.InvalidRecipe("contextLimit must be positive"))
    else if window <= 0 || window > contextLimit then
      Left(EmbedError.InvalidRecipe("window must be in (0, contextLimit]"))
    else if stride <= 0 || stride > window then
      Left(EmbedError.InvalidRecipe("stride must be in (0, window]"))
    else if matryoshkaDimension.exists(_ <= 0) then
      Left(EmbedError.InvalidRecipe("matryoshka dimension must be positive"))
    else Right(())

/** Content-addressed identity of one embedding recipe. */
object GeometryId extends OpaqueId("GeometryId")
type GeometryId = GeometryId.T

/** One embedding recipe. A `FeatureSpace` is derived from it; vectors of different recipes are
  * never compared unless a [[GeometryPair]] declares them compatible.
  */
final class EmbeddingSpace private[embed] (
    val id: GeometryId,
    val provider: ProviderFingerprint,
    val role: Role,
    val view: SemanticView,
    val instruction: Option[InstructionDigest],
    val dimension: Dimension,
    val normalization: Normalization,
    val truncation: TruncationPolicy,
    val latePooling: Option[LatePoolingRecipe],
    val parent: Option[GeometryId]
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
      latePooling match
        case None =>
          Right(
            EmbeddingSpace.build(
              provider,
              role,
              view,
              instruction,
              dim,
              Normalization.L2,
              truncation,
              None,
              Some(id)
            )
          )
        case Some(r) =>
          LatePoolingRecipe
            .of(
              r.documentDigest,
              r.tokenizerFingerprint,
              r.contextLimit,
              r.window,
              r.stride,
              r.overlapMerge,
              r.pooling,
              r.uncovered,
              Some(dim.value)
            )
            .map(v =>
              EmbeddingSpace.build(
                provider,
                role,
                view,
                instruction,
                dim,
                Normalization.L2,
                truncation,
                Some(v),
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

  override def equals(other: Any): Boolean = other match
    case that: EmbeddingSpace =>
      id == that.id &&
      provider == that.provider &&
      role == that.role &&
      view == that.view &&
      instruction == that.instruction &&
      dimension == that.dimension &&
      normalization == that.normalization &&
      truncation == that.truncation &&
      latePooling == that.latePooling &&
      parent == that.parent
    case _ => false

  override def hashCode(): Int =
    (
      id,
      provider,
      role,
      view,
      instruction,
      dimension,
      normalization,
      truncation,
      latePooling,
      parent
    ).hashCode

  override def toString: String =
    s"EmbeddingSpace(id=${id.value}, role=${role.render}, view=${view.render})"

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
    def create(id: GeometryId): EmbeddingSpace =
      new EmbeddingSpace(
        id,
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
    val provisional = create(GeometryId.unsafe("pending"))
    create(GeometryId.unsafe(ContentAddress.of("geometry", provisional.identityParts*)))

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

/** A named policy that makes intentional query/document modelling asymmetry inspectable. */
enum GeometryPairRule:
  /** Requires both the semantic view and instruction digest to match. */
  case IdenticalModelling

  /** Permits only the semantic view to differ; instruction digests must match. */
  case AllowViewDifference

  /** Permits only the instruction digest to differ; semantic views must match. */
  case AllowInstructionDifference

  /** Permits both the semantic view and instruction digest to differ. */
  case AllowViewAndInstructionDifference

  private[embed] def permitsViewDifference: Boolean = this match
    case AllowViewDifference | AllowViewAndInstructionDifference => true
    case IdenticalModelling | AllowInstructionDifference         => false

  private[embed] def permitsInstructionDifference: Boolean = this match
    case AllowInstructionDifference | AllowViewAndInstructionDifference => true
    case IdenticalModelling | AllowViewDifference                       => false

/** A validated query/document pair whose vectors may be compared under its recorded rule. */
final class GeometryPair private (
    val query: GeometryId,
    val document: GeometryId,
    val rule: GeometryPairRule
):
  override def equals(other: Any): Boolean = other match
    case that: GeometryPair =>
      query == that.query && document == that.document && rule == that.rule
    case _ => false

  override def hashCode(): Int = (query, document, rule).hashCode

  override def toString: String =
    s"GeometryPair(query=${query.value}, document=${document.value}, rule=$rule)"

object GeometryPair:
  private final case class CompatibilityKey(
      provider: ProviderFingerprint,
      dimension: Dimension,
      normalization: Normalization,
      truncation: TruncationPolicy,
      latePooling: Option[LatePoolingCompatibilityKey]
  ):
    def mismatch(other: CompatibilityKey): Option[String] =
      if provider != other.provider then Some("different providers")
      else if dimension != other.dimension then Some("different dimensions")
      else if normalization != other.normalization then Some("different normalizations")
      else if truncation != other.truncation then Some("different truncation policies")
      else
        (latePooling, other.latePooling) match
          case (None, None)                      => None
          case (Some(left), Some(right))         => left.mismatch(right)
          case (None, Some(_)) | (Some(_), None) => Some("different late-pooling presence")

  private def compatibilityKey(space: EmbeddingSpace): CompatibilityKey =
    CompatibilityKey(
      space.provider,
      space.dimension,
      space.normalization,
      space.truncation,
      space.latePooling.map(_.compatibilityKey)
    )

  /** Compatible iff both spaces have the same hard compatibility key and Query/Document roles. View
    * or instruction differences require an explicit named rule. Input document identity and
    * derivation parentage remain recipe provenance rather than coordinate compatibility.
    */
  def validated(
      query: EmbeddingSpace,
      document: EmbeddingSpace,
      rule: GeometryPairRule
  ): Either[EmbedError, GeometryPair] =
    def bad(reason: String) =
      Left(EmbedError.IncompatibleSpaces(query.id.value, document.id.value, reason))
    if query.role != Role.Query then bad("query space does not have role Query")
    else if document.role != Role.Document then bad("document space does not have role Document")
    else if query.view != document.view && !rule.permitsViewDifference then
      bad("different views require a rule that permits view asymmetry")
    else if query.instruction != document.instruction && !rule.permitsInstructionDifference then
      bad("different instructions require a rule that permits instruction asymmetry")
    else
      compatibilityKey(query).mismatch(compatibilityKey(document)) match
        case Some(reason) => bad(reason)
        case None         => Right(new GeometryPair(query.id, document.id, rule))
