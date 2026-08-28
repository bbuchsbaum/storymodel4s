package storymodel4s.amr.interop

import cats.data.{NonEmptySet, NonEmptyVector, Validated}
import storymodel4s.amr.align.*
import storymodel4s.amr.graph.*
import storymodel4s.amr.graph.CheckState.Checked
import storymodel4s.amr.graph.RoleForm.CanonicalRoles
import storymodel4s.amr.schema.FrameLexicon
import storymodel4s.core.*
import storymodel4s.proposition as p

/** Token index → exact source span, the only thing the marker sidecar needs from an atlas. */
type TokenSpans = Int => Option[TextSpan]

object TokenSpans:
  /** Spans of the tokens of a surface sequence, by token index. */
  def fromSequence(seq: SurfaceSequence): TokenSpans = i => seq.tokens.lift(i).map(_.unit.span)

  /** Spans supplied directly (e.g. a provider's own tokenization). */
  def fromSpans(spans: IndexedSeq[TextSpan]): TokenSpans = spans.lift

/** What to do with `~e.N` alignment markers when they cannot be turned into evidence. */
enum MarkerPolicy:
  /** Markers present but no token spans (or a marker out of range) ⇒ [[InteropError.Lossy]]. */
  case Strict

  /** Drop markers that cannot be resolved; an explicit, recorded choice. */
  case Ignore

/** PENMAN text → checked chart candidates: parse → decode → validate → canonicalize → markers →
  * [[ToChart]].
  *
  * Why: providers (parsers, agents, imports) hand over PENMAN strings; the acquisition layer wants
  * chart candidates with typed failures. Nothing here throws — a malformed candidate is a `Left`.
  * Alignment markers are evidence (design record §43): with token spans they become an
  * [[AmrAlignment]] sidecar and hence chart alignments; without them the default policy refuses to
  * drop them silently.
  */
object AmrCandidates:
  type Candidate = Either[InteropError, p.PropositionChart[p.Checked]]

  val Stage: StageId = StageId.unsafe("amr-interop")

  def fromPenman(
      text: String,
      lexicon: FrameLexicon,
      sentence: Option[SurfaceUnitId],
      receipts: Vector[ProviderCall] = Vector.empty,
      profile: ValidationProfile = ValidationProfile.default,
      tokens: Option[TokenSpans] = None,
      markers: MarkerPolicy = MarkerPolicy.Strict,
      markerScore: Credence = Credence.unsafeRaw(1.0)
  ): Candidate =
    val decoded: Either[InteropError, Decoded] =
      scala.util.Try(Decoder.fromPenman(text)) match
        case scala.util.Success(Right(d)) => Right(d)
        case scala.util.Success(Left(m))  => Left(InteropError.Malformed(m))
        case scala.util.Failure(e)        =>
          Left(InteropError.Malformed(Option(e.getMessage).getOrElse(e.getClass.getName)))
    for
      d <- decoded
      canonical <- RoleCanonicalizer.fromUnchecked(d.graph, profile) match
        case Validated.Valid(c)   => Right(c)
        case Validated.Invalid(e) => Left(InteropError.GraphInvalid(e.toChain.toVector))
      alignment <- markerAlignment(d, canonical, sentence, tokens, markers, markerScore, receipts)
      chart <- ToChart.convert(canonical, alignment, lexicon, sentence, receipts)
    yield chart

  def toChartCandidates(
      penman: Vector[String],
      lexicon: FrameLexicon,
      sentence: Option[SurfaceUnitId],
      receipts: Vector[ProviderCall] = Vector.empty,
      profile: ValidationProfile = ValidationProfile.default,
      tokens: Option[TokenSpans] = None,
      markers: MarkerPolicy = MarkerPolicy.Strict
  ): Vector[Candidate] =
    penman.map(fromPenman(_, lexicon, sentence, receipts, profile, tokens, markers))

  /** Turn decoded `~` markers into an alignment sidecar against the canonical graph. */
  def markerAlignment(
      decoded: Decoded,
      canonical: AmrGraph[Checked, CanonicalRoles],
      sentence: Option[SurfaceUnitId],
      tokens: Option[TokenSpans],
      policy: MarkerPolicy,
      score: Credence,
      receipts: Vector[ProviderCall]
  ): Either[InteropError, Option[AmrAlignment]] =
    if decoded.markers.isEmpty then Right(None)
    else
      (tokens, sentence) match
        case (Some(ts), Some(sent)) =>
          val fingerprint = receipts.headOption
            .map(c => Fingerprint.unsafe(s"${c.provider}:${c.model}:${c.version}"))
            .getOrElse(ToChart.fingerprint)
          val provenance = Provenance(
            receipts,
            ToChart.Version,
            Checksum.ofText(s"amr-interop:markers:${ToChart.Version}")
          )
          val digest = Canonical.digest(canonical)
          val issues = Vector.newBuilder[String]
          val entries = decoded.markers.flatMap { m =>
            val spans = SpanSet.of(m.marker.indices.flatMap(ts(_)).map(SpanRef(_)))
            if spans.isEmpty then
              issues += s"marker ${m.marker.render} has no token in range"
              None
            else
              val span = spans.get
              def meta(kind: String, key: String): ClaimMeta =
                val base = Vector(sent.value, kind, key, span.toString)
                ClaimMeta.unsafe(
                  ClaimId.unsafe(ContentAddress.of("amr-align", base*)),
                  EpistemicStatus.SurfaceExplicit,
                  score,
                  NonEmptyVector.one(
                    Evidence(
                      EvidenceId.unsafe(ContentAddress.of("amr-align-ev", base*)),
                      Some(span),
                      Set.empty,
                      fingerprint,
                      Stage
                    )
                  ),
                  provenance
                )
              m.site match
                case MarkerSite.OnConcept(node) =>
                  Some(
                    AlignmentEntry.Subgraph(
                      SubgraphAlignment(
                        NonEmptySet.one(node),
                        span,
                        score,
                        meta("concept", node.value)
                      )
                    )
                  )
                case MarkerSite.OnRole(i) =>
                  canonicalEdge(decoded, i).map { e =>
                    AlignmentEntry.Relation(
                      RelationAlignment(e, span, score, meta("role", e.render))
                    )
                  }
                case MarkerSite.OnTarget(i) =>
                  canonicalEdge(decoded, i).map { e =>
                    decoded.graph.edges(i).target match
                      case AmrValue.Node(t) =>
                        AlignmentEntry.Reentrancy(
                          ReentrancyAlignment(t, e, span, score, meta("reentrancy", e.render))
                        )
                      case AmrValue.Literal(_) =>
                        AlignmentEntry.Relation(
                          RelationAlignment(e, span, score, meta("attribute", e.render))
                        )
                  }
          }
          val alignment = AmrAlignment(sent, digest, entries)
          val problems = issues.result()
          (policy, NonEmptyVector.fromVector(problems)) match
            case (MarkerPolicy.Strict, Some(ps)) => Left(InteropError.Lossy(ps))
            case _                               =>
              AmrAlignment.validate(alignment, canonical, Some(digest)) match
                case Validated.Valid(a)   => Right(Some(a))
                case Validated.Invalid(e) =>
                  Left(InteropError.Malformed(e.toChain.toVector.map(_.message).mkString("; ")))
        case _ =>
          policy match
            case MarkerPolicy.Ignore => Right(None)
            case MarkerPolicy.Strict =>
              val why =
                if tokens.isEmpty then "no token spans supplied" else "no sentence id supplied"
              Left(
                InteropError.Lossy(
                  NonEmptyVector.one(
                    s"${decoded.markers.size} alignment marker(s) would be dropped: $why"
                  )
                )
              )

  /** The canonical (direct-role) edge corresponding to decoded edge `i`, if it has one. */
  private def canonicalEdge(decoded: Decoded, i: Int): Option[Edge] =
    decoded.graph.edges
      .lift(i)
      .flatMap(_.canonical)
      .map((s, r, t) => Edge(s, SurfaceRole.direct(r), t))
