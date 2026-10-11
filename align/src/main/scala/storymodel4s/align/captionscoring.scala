package storymodel4s.align

import cats.syntax.all.*
import storymodel4s.core.*
import storymodel4s.features.{CanonicalDouble, Estimate, MissingReason}
import storymodel4s.proposition.GraphOrder
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Explicit lexical augmentation keeps caption content apart from its coordinate-bearing join. */
final class CaptionScoring private (
    val blend: Double,
    val evidence: CaptionEvidence,
    val additionalEvidence: Vector[CaptionEvidence],
    val correspondence: Option[Checksum]
):
  val allEvidence: Vector[CaptionEvidence] = evidence +: additionalEvidence
  val identity: Checksum = MappingRender.digest(
    Vector("caption-scoring/v1", evidence.identity.hex, CanonicalDouble.render(blend)) ++
      additionalEvidence.map(_.identity.hex) ++ correspondence.toVector.map(_.hex)
  )
  override def equals(other: Any): Boolean = other match
    case s: CaptionScoring => identity == s.identity
    case _                 => false
  override def hashCode: Int = identity.hashCode
  override def toString: String = s"CaptionScoring(${identity.short()})"
  def session(
      recall: RecallGraph[Checked],
      view: SourceView,
      source: SourceRepresentation
  ): Either[DomainError, CaptionScoring.Session] =
    CaptionScoring.Session.of(this, recall, view, source)

object CaptionScoring:
  def of(blend: Double, evidence: CaptionEvidence): Either[DomainError, CaptionScoring] =
    if !blend.isFinite || blend < 0.0 || blend > 1.0 then
      Left(refused("caption blend must be finite in [0,1]"))
    else Right(new CaptionScoring(if blend == 0.0 then 0.0 else blend, evidence, Vector.empty, None))
  /** One complete annotation universe is scored once, with every correspondence in provenance. */
  def multipart(blend: Double, evidence: Vector[CaptionEvidence], correspondence: Checksum): Either[DomainError, CaptionScoring] =
    if evidence.isEmpty then Left(refused("caption evidence is empty"))
    else of(blend, evidence.head).map(valid =>
      new CaptionScoring(valid.blend, evidence.head, evidence.tail, Some(correspondence)))

  private def refused(message: String): DomainError =
    DomainError.InvariantViolation("caption-scoring", message)

  /** Coordinates select the checked content; only content enters the closed distance function. */
  private[align] def score(
      u: UnitContent[GraphOrder.Canonical],
      t: TargetContent[GraphOrder.Canonical],
      caption: String,
      blend: Double
  ): Estimate[Double] =
    if caption.trim.isEmpty then Estimate.missing(MissingReason.ChannelUnavailable)
    else
      val words = Lexical.stems(caption).toSet
      val union = u.lemmas.union(words)
      val distance =
        if union.isEmpty then 1.0 else 1.0 - u.lemmas.intersect(words).size.toDouble / union.size
      ContentLexical.score(u, t).map(a => (1.0 - blend) * a + blend * distance)

  /** A bound session prevents same-text occurrences from borrowing each other's captions. */
  final class Session private[CaptionScoring] (
      val scoring: CaptionScoring,
      val recall: RecallGraph[Checked],
      val view: SourceView,
      val source: SourceRepresentation,
      private val captions: Map[SourceNodeRef, CaptionEvidence.Caption]
  ):
    private val binding = StrictBinding.of(recall, view)
    val channel: StrictSemanticChannel.CaptionLexical =
      StrictSemanticChannel.CaptionLexical.of(scoring.blend)
    val identity: Checksum = MappingRender.digest(
      Vector(
        "caption-session/v1",
        scoring.identity.hex,
        source.digest.hex,
        binding.recallChecksum.hex,
        binding.recallSupplement.hex
      )
    )
    def binds(r: RecallGraph[Checked], v: SourceView): Boolean = StrictBinding.of(r, v) == binding
    private[align] def evaluate(
        unit: RecallUnit,
        node: NodeSummary,
        u: UnitContent[GraphOrder.Canonical],
        t: TargetContent[GraphOrder.Canonical]
    ): Either[StrictScoringRefusal, Estimate[Double]] =
      if !recall.byId.get(unit.id).contains(unit) || !view.node(node.ref).contains(node) then
        Left(StrictScoringRefusal.CaptionContextRequired)
      else
        Right(
          captions
            .get(node.ref)
            .fold[Estimate[Double]](Estimate.missing(MissingReason.ChannelUnavailable))(c =>
              score(u, t, c.text, scoring.blend)
            )
        )
    override def equals(other: Any): Boolean = other match
      case s: Session => identity == s.identity
      case _          => false
    override def hashCode: Int = identity.hashCode
    override def toString: String = s"CaptionSession(${identity.short()})"

  object Session:
    def of(
        scoring: CaptionScoring,
        recall: RecallGraph[Checked],
        sourceView: SourceView,
        source: SourceRepresentation
    ): Either[DomainError, Session] =
      val view = MappingBindingRender.snapshot(sourceView)
      val bundles = source.bundles.toVector.collect { case m: BundleEntry.Media => m.bundle }
      val supplied = scoring.allEvidence
      val allCaptions = supplied.flatMap(_.captions)
      if !supplied.forall(e => bundles.contains(e.bundle)) ||
        source.viewFingerprint != ViewFingerprint.of(view) ||
        source.scopeDigest != MappingSourceRender.scope(view.nodes)
      then Left(refused("source binding differs"))
      else
        source.targets.filter(_.level == 0).traverse { target =>
          target.sourceSupport match
            case located: SourceSupportStatus.Located =>
              val local = located.support match
                case TypedSupport.Anchored(support) => supplied.filter(_.bundle.identity == support.bundleIdentity)
                case _ => Vector.empty
              if local.isEmpty then Right(Vector.empty)
              else PrimaryProjection.on(local.head.bundle, located.support).flatMap {
                case PrimaryProjection.Playback(_, support)
                    if support.points.isEmpty && support.intervals.size == 1 =>
                  val hits = local.flatMap(_.captions).filter(_.support == support.intervals.head)
                  if hits.size <= 1 then Right(hits.map(target.ref -> _))
                  else Left(refused("ambiguous caption support"))
                case _ => Right(Vector.empty)
              }
            case _ => Right(Vector.empty)
        }.map(_.flatten).flatMap { rows =>
          if rows.size != allCaptions.size || rows.map(_._2).toSet != allCaptions.toSet then
            Left(refused("caption has no unique exact target"))
          else Right(new Session(scoring, recall, view, source, rows.toMap))
        }

  /** The profile binds provenance even when two frame sets happen to yield equal numeric costs. */
  final class Produced private[CaptionScoring] (
      val session: Session,
      val config: StrictCandidateConfig,
      val inventory: RecallInventory,
      val evidence: LocalEvidence
  ):
    val identity: Checksum = MappingRender.digest(
      Vector(
        "caption-evidence-profile/v1",
        session.identity.hex,
        inventory.digest.hex,
        config.perLevel.toString,
        MappingRender.sequence(CandidateTiePolicy.render(config.policy)),
        config.lexicalOverlap.toString,
        MappingRender.optional(config.space),
        evidence.identity.checksum.hex
      )
    )
    override def equals(other: Any): Boolean = other match
      case p: Produced => identity == p.identity
      case _           => false
    override def hashCode: Int = identity.hashCode
    override def toString: String = s"CaptionEvidenceProfile(${identity.short()})"

  def produce(
      session: Session,
      inventory: RecallInventory,
      config: StrictCandidateConfig
  ): Either[AlignError, Produced] =
    for
      _ <- Either.cond(
        inventory.describes(session.recall),
        (),
        AlignError.InvalidConfig("caption", "foreign inventory")
      )
      candidates <- StrictCandidates
        .canonical(
          session.channel,
          config,
          session.recall,
          session.view,
          captionSession = Some(session),
          selectedLevel = Some(0)
        )
        .left
        .map(e => AlignError.InvalidConfig("caption", e.message))
      model <- StrictCostModel.of(
        session.channel,
        weights = CostWeights.semanticOnly,
        functionPrior = FunctionPrior.none,
        externalMismatch = 0.0,
        distortionPenalty = 0.0,
        captionSession = Some(session)
      )
      evidence <- LocalEvidence.compute(session.recall, session.view, candidates, model)
    yield new Produced(session, config, inventory, evidence)
