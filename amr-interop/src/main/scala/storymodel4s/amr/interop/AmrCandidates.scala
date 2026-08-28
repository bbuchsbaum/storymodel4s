package storymodel4s.amr.interop

import cats.data.Validated
import storymodel4s.amr.graph.*
import storymodel4s.amr.schema.FrameLexicon
import storymodel4s.core.{ProviderCall, SurfaceUnitId}
import storymodel4s.proposition as p

/** PENMAN text → checked chart candidates: parse → decode → validate → canonicalize → [[ToChart]].
  *
  * Why: providers (parsers, agents, imports) hand over PENMAN strings; the acquisition layer wants
  * chart candidates with typed failures. Nothing here throws — a malformed candidate is a `Left`.
  */
object AmrCandidates:
  type Candidate = Either[InteropError, p.PropositionChart[p.Checked]]

  def fromPenman(
      text: String,
      lexicon: FrameLexicon,
      sentence: Option[SurfaceUnitId],
      receipts: Vector[ProviderCall] = Vector.empty,
      profile: ValidationProfile = ValidationProfile.default
  ): Candidate =
    val decoded: Either[InteropError, AmrGraph[CheckState.Unchecked, RoleForm.SurfaceRoles]] =
      scala.util.Try(Decoder.graphFromPenman(text)) match
        case scala.util.Success(Right(g)) => Right(g)
        case scala.util.Success(Left(m))  => Left(InteropError.Malformed(m))
        case scala.util.Failure(e)        =>
          Left(InteropError.Malformed(Option(e.getMessage).getOrElse(e.getClass.getName)))
    for
      g <- decoded
      canonical <- RoleCanonicalizer.fromUnchecked(g, profile) match
        case Validated.Valid(c)   => Right(c)
        case Validated.Invalid(e) => Left(InteropError.GraphInvalid(e.toChain.toVector))
      chart <- ToChart.convert(canonical, None, lexicon, sentence, receipts)
    yield chart

  def toChartCandidates(
      penman: Vector[String],
      lexicon: FrameLexicon,
      sentence: Option[SurfaceUnitId],
      receipts: Vector[ProviderCall] = Vector.empty,
      profile: ValidationProfile = ValidationProfile.default
  ): Vector[Candidate] =
    penman.map(fromPenman(_, lexicon, sentence, receipts, profile))
