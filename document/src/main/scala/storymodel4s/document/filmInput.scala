package storymodel4s.document

import storymodel4s.core.*
import storymodel4s.proposition.{Canonical, PropositionEvidence}

/** Stable caller identity of one caption or annotation record.
  *
  * Why: record order and identity are part of the proposal-surface binding; a description alone
  * is not enough to say which physical envelope licensed it.
  */
object FilmSourceRecordId extends OpaqueId("FilmSourceRecordId")
type FilmSourceRecordId = FilmSourceRecordId.T

/** One portable source record used to derive proposal text. */
final case class FilmSourceRecord(
    id: FilmSourceRecordId,
    description: String,
    support: EvidenceSupport
)

/** Declared deterministic derivation of the proposal text from ordered source records. */
final case class FilmProposalDerivation(
    receipt: SourceDerivationReceipt,
    recipe: String,
    outputChecksum: Checksum
)

/** A generated sentence's physical origin. Original units deliberately have no surface sentence. */
final case class FilmSentenceOrigin(
    sentence: SurfaceUnitId,
    unit: NarrativeProposalUnitId,
    record: FilmSourceRecordId
)

/** A prepared, portable film proposal surface and its complete checked origin inventory.
  *
  * Why a non-case class: the surface, record order, original units and sentence table are one
  * identity-bearing assertion; callers must use [[FilmProposalSurface.derive]].
  */
final class FilmProposalSurface private (
    val atlas: AnchoredNarrativeAtlas,
    val records: Vector[FilmSourceRecord],
    val originalUnits: Vector[NarrativeProposalUnit],
    val origins: Vector[FilmSentenceOrigin],
    val derivation: FilmProposalDerivation,
    val identity: Checksum
):
  lazy val sentenceUnits: Vector[NarrativeProposalUnit] =
    atlas.units.filter(_.surface.isDefined)
  lazy val unitBySentence: Map[SurfaceUnitId, NarrativeProposalUnit] =
    sentenceUnits.flatMap(unit => unit.surface.map(_ -> unit)).toMap

  override def equals(other: Any): Boolean = other match
    case that: FilmProposalSurface =>
      atlas == that.atlas && records == that.records && originalUnits == that.originalUnits &&
      origins == that.origins && derivation == that.derivation
    case _ => false
  override def hashCode(): Int = (atlas, records, originalUnits, origins, derivation).hashCode()

object FilmProposalSurface:
  /** The fixed recipe joins non-empty records with exactly one blank line before record-local
    * sentence analysis. Its literal form is intentionally part of the input identity.
    */
  val RecipeV1: String = "film-proposal-surface/v1;separator=LF-LF;analyzer=SurfaceAnalyzer"
  private val Separator = "\n\n"

  private def invalid(path: String, detail: String): Left[DomainError, Nothing] =
    Left(DomainError.InvariantViolation(s"film-input/$path", detail))

  private def digest(fields: Vector[String]): Checksum =
    Checksum.ofText(fields.map(value => Checksum.ofText(value).hex).mkString("\u001f"))

  private def unitId(record: FilmSourceRecordId, sentence: SurfaceUnitId): NarrativeProposalUnitId =
    NarrativeProposalUnitId.unsafe(ContentAddress.of("film-proposal-unit", record.value, sentence.value))

  /** Derives the canonical proposal surface. It refuses any input whose exact descriptions would
    * be altered by StorySource canonicalization; this boundary has no raw-to-canonical inverse.
    */
  def derive(
      bundle: SourceBundle,
      records: Vector[FilmSourceRecord],
      originalUnits: Vector[NarrativeProposalUnit],
      expected: FilmProposalDerivation
  ): Either[DomainError, FilmProposalSurface] =
    if records.isEmpty then invalid("records", "proposal derivation needs at least one record")
    else if records.map(_.id).distinct.size != records.size then
      Left(DomainError.DuplicateId("FilmSourceRecord", "record identity"))
    else if expected.recipe != RecipeV1 then invalid("recipe", "unknown proposal-surface recipe")
    else if records.exists(_.description.isEmpty) then invalid("description", "record description is empty")
    else
      val raw = records.map(_.description).mkString(Separator)
      StorySource.fromText(raw).flatMap { source =>
        if source.canonicalText != raw then
          invalid("description", "StorySource canonicalization would change a source description")
        else if source.canonicalChecksum != expected.outputChecksum then
          invalid("output", "supplied proposal output checksum does not match derived source")
        else
          val surface = SurfaceAnalyzer.analyze(source)
          val sentenceByRecord = records.zipWithIndex.flatMap { case (record, index) =>
            val start = records.take(index).map(_.description.length + Separator.length).sum
            val end = start + record.description.length
            surface.units.collect {
              case sentence @ SurfaceUnit(_, SurfaceUnitKind.Sentence, span, _, _)
                  if span.start >= start && span.endExclusive <= end => record -> sentence
            }
          }
          if sentenceByRecord.exists { case (_, sentence) =>
              val owning = records.zipWithIndex.count { case (record, index) =>
                val start = records.take(index).map(_.description.length + Separator.length).sum
                val end = start + record.description.length
                sentence.span.start >= start && sentence.span.endExclusive <= end
              }
              owning != 1
            }
          then invalid("sentences", "a generated sentence has no unique source record")
          else
            val bound = BoundProposalSurface.of(surface, expected.receipt)
            val generated = sentenceByRecord.map { case (record, sentence) =>
              NarrativeProposalUnit.of(unitId(record.id, sentence.id), record.support, Some(sentence.id))
            }
            val units = originalUnits ++ generated
            val origins = sentenceByRecord.zip(generated).map { case ((record, sentence), unit) =>
              FilmSentenceOrigin(sentence.id, unit.id, record.id)
            }
            val allIds = units.map(_.id)
            if allIds.distinct.size != allIds.size then
              Left(DomainError.DuplicateId("NarrativeProposalUnit", "film proposal inventory"))
            else if originalUnits.exists(_.surface.nonEmpty) then
              invalid("original-units", "original row/scene units must have surface=None")
            else
              AnchoredNarrativeAtlas.of(bundle, units, Some(bound)).map { atlas =>
                val identity = digest(
                  Vector("film-proposal-surface/v1", bundle.identity.hex, bound.identity.hex,
                    expected.receipt.bindingIdentity.hex, expected.recipe, expected.outputChecksum.hex,
                    records.size.toString) ++ records.flatMap(r => Vector(r.id.value, r.description, r.support.bundleIdentity.hex)) ++
                    origins.flatMap(o => Vector(o.sentence.value, o.unit.value, o.record.value)) ++
                    originalUnits.map(_.id.value)
                )
                new FilmProposalSurface(atlas, records, originalUnits, origins, expected, identity)
              }
      }

/** Expected portable parse association for one generated sentence. */
final case class FilmChartAssociation(
    sentence: SurfaceUnitId,
    evidence: PropositionEvidence,
    expectedCalls: Vector[ProviderCall],
    expectedOutputChecksum: Checksum
)

/** The explicitly declared proposal sentences consumed by one compiler attempt. */
final case class FilmAttemptScope(id: String, sentences: Vector[SurfaceUnitId], support: EvidenceSupport)

/** Checked F1 compiler input. It binds joins only; it neither calls a provider nor compiles a film. */
final class FilmCompilerInput private (
    val prepared: FilmProposalSurface,
    val charts: Vector[FilmChartAssociation],
    val attemptScopes: Vector[FilmAttemptScope],
    val identity: Checksum
):
  private lazy val chartsBySentence = charts.map(c => c.sentence -> c).toMap

  /** Selects exact parse calls for actual chart nodes. Shared upstream calls occur once. */
  def provenanceFor(refs: Iterable[ChartNodeRef]): Either[DomainError, Vector[ProviderCall]] =
    val selected = refs.iterator.map(_.sentence).toVector.distinct
    if selected.exists(sentence => !chartsBySentence.contains(sentence)) then
      Left(DomainError.InvariantViolation("film-input/provenance", "chart node has no checked association"))
    else if refs.exists(ref => !chartsBySentence(ref.sentence).evidence.chart.concepts.contains(ref.concept)) then
      Left(DomainError.InvariantViolation("film-input/provenance", "unknown chart node reference"))
    else Right(selected.flatMap(chartsBySentence(_).expectedCalls).distinct)

  override def equals(other: Any): Boolean = other match
    case that: FilmCompilerInput =>
      prepared == that.prepared && charts == that.charts && attemptScopes == that.attemptScopes
    case _ => false
  override def hashCode(): Int = (prepared, charts, attemptScopes).hashCode()

object FilmCompilerInput:
  private def invalid(path: String, detail: String): Left[DomainError, Nothing] =
    Left(DomainError.InvariantViolation(s"film-input/$path", detail))
  private def digest(fields: Vector[String]): Checksum =
    Checksum.ofText(fields.map(value => Checksum.ofText(value).hex).mkString("\u001f"))
  private def fullChartChecksum(evidence: PropositionEvidence): Checksum =
    val chart = evidence.chart
    Checksum.ofText(
      Vector(
        Canonical.serialization(chart), chart.alignments.toString, chart.provenance.toString,
        chart.sentence.map(_.value).getOrElse("none"), evidence.provenance.toString
      ).mkString("\n")
    )

  /** Validates chart/attempt joins against one prepared surface. */
  def of(
      prepared: FilmProposalSurface,
      chartAssociations: Vector[FilmChartAssociation],
      attemptScopes: Vector[FilmAttemptScope]
  ): Either[DomainError, FilmCompilerInput] =
    val generatedSentences = prepared.origins.map(_.sentence)
    if chartAssociations.map(_.sentence).distinct.size != chartAssociations.size then
      Left(DomainError.DuplicateId("FilmChartAssociation", "sentence"))
    else if chartAssociations.map(_.sentence).toSet != generatedSentences.toSet then
      invalid("charts", "each generated sentence needs exactly one chart association")
    else if attemptScopes.map(_.id).distinct.size != attemptScopes.size then
      Left(DomainError.DuplicateId("FilmAttemptScope", "attempt identity"))
    else
      chartAssociations.foldLeft[Either[DomainError, Unit]](Right(())) { (acc, association) =>
        acc.flatMap { _ =>
          val chart = association.evidence.chart
          if chart.sentence != Some(association.sentence) then
            invalid("chart/sentence", "chart is associated with a different sentence")
          else if association.evidence.provenance != chart.provenance then
            invalid("chart/provenance", "evidence provenance differs from chart provenance")
          else if association.expectedCalls != chart.provenance.receipts then
            invalid("chart/calls", "expected parser calls differ from chart receipts")
          else if fullChartChecksum(association.evidence) != association.expectedOutputChecksum then
            invalid("chart/output", "expected full chart payload checksum differs")
          else Right(())
        }
      }.flatMap { _ =>
        attemptScopes.foldLeft[Either[DomainError, Unit]](Right(())) { (acc, scope) =>
          acc.flatMap { _ =>
            if scope.sentences.isEmpty || scope.sentences.distinct.size != scope.sentences.size then
              invalid("attempt/sentences", "attempt must declare unique contributing sentences")
            else if scope.sentences.exists(sentence => !generatedSentences.contains(sentence)) then
              invalid("attempt/sentences", "attempt declares a sentence outside the prepared surface")
            else
              scope.support.checkedOn(prepared.atlas.bundle).flatMap { checked =>
                val anchors = checked.anchors.toVector
                if anchors.exists {
                    case _: EvidenceAnchor.MediaTime | _: EvidenceAnchor.MediaPoint => false
                    case _                                                           => true
                  }
                then invalid("attempt/support", "only MediaTime and MediaPoint anchors are admitted")
                else
                  val permitted = scope.sentences.flatMap(prepared.unitBySentence.get).flatMap(_.support.anchors.toVector)
                  val grouped = permitted.groupBy(a => (a.anchorStream, a.axisId))
                  val contained = anchors.forall {
                    case EvidenceAnchor.MediaTime(_, stream, axis, intervals) =>
                      grouped.get(stream -> Some(axis)).flatMap { allowed =>
                        (for
                          envelope <- PlaybackSupport.of(
                            axis,
                            allowed.flatMap {
                              case EvidenceAnchor.MediaTime(_, _, _, set) => set.intervals.toVector
                              case _                                       => Vector.empty
                            },
                            allowed.collect { case EvidenceAnchor.MediaPoint(_, _, point) => point }
                          )
                          attempted <- PlaybackSupport.of(axis, intervals.intervals.toVector, Vector.empty)
                        yield envelope.contains(attempted)).toOption
                      }.contains(true)
                    case EvidenceAnchor.MediaPoint(_, stream, point) =>
                      grouped.get(stream -> Some(point.axis)).flatMap { allowed =>
                        (for
                          envelope <- PlaybackSupport.of(
                            point.axis,
                            allowed.flatMap {
                              case EvidenceAnchor.MediaTime(_, _, _, set) => set.intervals.toVector
                              case _                                       => Vector.empty
                            },
                            allowed.collect { case EvidenceAnchor.MediaPoint(_, _, p) => p }
                          )
                          attempted <- PlaybackSupport.of(point.axis, Vector.empty, Vector(point))
                        yield envelope.contains(attempted)).toOption
                      }.contains(true)
                    case _ => false
                  }
                  if !contained then invalid("attempt/containment", "support escapes declared sentence envelopes")
                  else if !anchors.exists(_.axisId.contains(prepared.atlas.bundle.primaryAxis.id)) then
                    invalid("attempt/primary", "attempt lacks direct primary-axis support")
                  else Right(())
              }
          }
        }.map { _ =>
          val identity = digest(Vector("film-compiler-input/v1", prepared.identity.hex) ++
            chartAssociations.flatMap(a => Vector(a.sentence.value, a.expectedOutputChecksum.hex)) ++
            attemptScopes.flatMap(s => Vector(s.id, s.sentences.map(_.value).mkString(","), s.support.bundleIdentity.hex)))
          new FilmCompilerInput(prepared, chartAssociations, attemptScopes, identity)
        }
      }
