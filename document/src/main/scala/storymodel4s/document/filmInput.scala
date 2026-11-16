package storymodel4s.document

import storymodel4s.core.*
import storymodel4s.proposition.*

/** Stable caller identity of one caption or annotation record.
  *
  * Why: record order and identity are part of the proposal-surface binding; a description alone is
  * not enough to say which physical envelope licensed it.
  */
object FilmSourceRecordId extends OpaqueId("FilmSourceRecordId")
type FilmSourceRecordId = FilmSourceRecordId.T

/** One portable source record used to derive proposal text. */
final case class FilmSourceRecord(
    id: FilmSourceRecordId,
    description: String,
    support: EvidenceSupport,
    sourceIdentity: Checksum,
    proposalIdentity: Checksum,
    derivation: SourceDerivationReceipt
):
  def identity: Checksum = FilmProposalSurface.recordIdentity(this)

/** Declared deterministic derivation of the proposal text from ordered source records. */
final case class FilmProposalDerivation(
    receipt: SourceDerivationReceipt,
    recipe: String,
    outputChecksum: Checksum,
    bundleIdentity: Checksum,
    orderedRecordIdentities: Vector[Checksum],
    originalUnitIdentities: Vector[Checksum]
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

  def recordIdentity(record: FilmSourceRecord): Checksum =
    digest(
      Vector(
        "film-source-record/v1",
        record.id.value,
        record.description,
        TypedSupport.Anchored(record.support).identity.hex,
        record.sourceIdentity.hex,
        record.proposalIdentity.hex,
        record.derivation.bindingIdentity.hex
      )
    )

  def originalUnitIdentity(unit: NarrativeProposalUnit): Checksum =
    digest(
      Vector(
        "film-original-unit/v1",
        unit.id.value,
        TypedSupport.Anchored(unit.support).identity.hex,
        if unit.surface.isEmpty then "surface:none" else "surface:present"
      )
    )

  /** Builds a declared expectation from the independently selected source inventory. Why: consumers
    * need the same deterministic receipt recipe without duplicating hashing. Supplying this
    * expectation declares correspondence; it does not attest acquisition.
    */
  def expectedDerivation(
      bundle: SourceBundle,
      records: Vector[FilmSourceRecord],
      originalUnits: Vector[NarrativeProposalUnit],
      outputChecksum: Checksum
  ): Either[DomainError, FilmProposalDerivation] =
    SourceDerivationReceipt
      .of(
        "FilmProposalSurface/v1",
        RecipeV1,
        Vector(
          bundle.identity,
          digest(records.map(_.identity.hex)),
          digest(originalUnits.map(originalUnitIdentity).map(_.hex))
        )
      )
      .map(
        FilmProposalDerivation(
          _,
          RecipeV1,
          outputChecksum,
          bundle.identity,
          records.map(_.identity),
          originalUnits.map(originalUnitIdentity)
        )
      )

  private def unitId(
      bound: BoundProposalSurface,
      record: FilmSourceRecord,
      sentence: SurfaceUnitId
  ): NarrativeProposalUnitId =
    NarrativeProposalUnitId.unsafe(
      ContentAddress.of(
        "film-proposal-unit",
        bound.identity.hex,
        record.identity.hex,
        sentence.value
      )
    )

  /** Derives the canonical proposal surface. It refuses any input whose exact descriptions would be
    * altered by StorySource canonicalization; this boundary has no raw-to-canonical inverse.
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
    else if records.exists(_.description.trim.isEmpty) then
      invalid("description", "record description is blank")
    else if expected.bundleIdentity != bundle.identity then
      invalid("bundle", "expected full bundle identity differs")
    else if expected.orderedRecordIdentities != records.map(_.identity) then
      invalid("records", "expected ordered source record identities differ")
    else if expected.originalUnitIdentities != originalUnits.map(originalUnitIdentity) then
      invalid("original-units", "expected original unit identities differ")
    else
      val raw = records.map(_.description).mkString(Separator)
      StorySource.fromText(raw).flatMap { source =>
        if source.canonicalText != raw then
          invalid("description", "StorySource canonicalization would change a source description")
        else if source.canonicalChecksum != expected.outputChecksum then
          invalid("output", "supplied proposal output checksum does not match derived source")
        else
          val expectedReceiptInputs = Vector(
            bundle.identity,
            digest(records.map(_.identity.hex)),
            digest(originalUnits.map(originalUnitIdentity).map(_.hex))
          )
          if expected.receipt.algorithm != "FilmProposalSurface/v1" ||
            expected.receipt.parameters != RecipeV1 ||
            expected.receipt.inputChecksums != expectedReceiptInputs
          then
            invalid("receipt", "surface receipt does not bind recipe and complete input inventory")
          else
            val surface = SurfaceAnalyzer.analyze(source)
            val sentenceByRecord = records.zipWithIndex.flatMap { case (record, index) =>
              val start = records.take(index).map(_.description.length + Separator.length).sum
              val end = start + record.description.length
              surface.units.collect {
                case sentence @ SurfaceUnit(_, SurfaceUnitKind.Sentence, span, _, _)
                    if span.start >= start && span.endExclusive <= end =>
                  record -> sentence
              }
            }
            val sentences = surface.units.collect {
              case sentence @ SurfaceUnit(_, SurfaceUnitKind.Sentence, _, _, _) => sentence
            }
            if sentences.exists { sentence =>
                val owning = records.zipWithIndex.count { case (record, index) =>
                  val start = records.take(index).map(_.description.length + Separator.length).sum
                  val end = start + record.description.length
                  sentence.span.start >= start && sentence.span.endExclusive <= end
                }
                owning != 1
              } || records.exists(record => !sentenceByRecord.exists(_._1 == record))
            then invalid("sentences", "every generated sentence and source record needs one origin")
            else
              val bound = BoundProposalSurface.of(surface, expected.receipt)
              val generated = sentenceByRecord.map { case (record, sentence) =>
                NarrativeProposalUnit.of(
                  unitId(bound, record, sentence.id),
                  record.support,
                  Some(sentence.id)
                )
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
                units
                  .foldLeft[Either[DomainError, Unit]](Right(())) { (result, unit) =>
                    result.flatMap(_ => FilmInputSupport.check(bundle, unit.support).map(_ => ()))
                  }
                  .flatMap(_ => AnchoredNarrativeAtlas.of(bundle, units, Some(bound)))
                  .map { atlas =>
                    val identity = digest(
                      Vector(
                        "film-proposal-surface/v1",
                        bundle.identity.hex,
                        bound.identity.hex,
                        expected.receipt.bindingIdentity.hex,
                        expected.recipe,
                        expected.outputChecksum.hex,
                        records.size.toString
                      ) ++ records.flatMap(r => Vector(r.identity.hex)) ++
                        origins
                          .flatMap(o => Vector(o.sentence.value, o.unit.value, o.record.value)) ++
                        originalUnits.map(_.id.value)
                    )
                    new FilmProposalSurface(
                      atlas,
                      records,
                      originalUnits,
                      origins,
                      expected,
                      identity
                    )
                  }
      }

/** Expected portable parse association for one generated sentence. */
final case class FilmParseBinding(
    sentence: SurfaceUnitId,
    preparedIdentity: Checksum,
    originRecordIdentity: Checksum,
    parser: Fingerprint,
    requestChecksum: Checksum,
    recipeIdentity: Checksum,
    outputChartChecksum: Checksum,
    calls: Vector[ProviderCall]
)

final case class FilmChartAssociation(evidence: PropositionEvidence, binding: FilmParseBinding)

/** The explicitly declared proposal sentences consumed by one compiler attempt. */
final case class FilmAttemptScope(
    id: String,
    sentences: Vector[SurfaceUnitId],
    contributors: Vector[ChartNodeRef],
    support: EvidenceSupport
)

/** Selected portable derivations for checked chart references. */
final case class FilmSelectedProvenance(
    calls: Vector[ProviderCall],
    records: Vector[FilmSourceRecord],
    surfaceReceipt: SourceDerivationReceipt
)

/** Checked F1 compiler input. It binds joins only; it neither calls a provider nor compiles a film.
  */
final class FilmCompilerInput private (
    val prepared: FilmProposalSurface,
    val charts: Vector[FilmChartAssociation],
    val attemptScopes: Vector[FilmAttemptScope],
    val identity: Checksum
):
  private lazy val chartsBySentence = charts.map(c => c.binding.sentence -> c).toMap

  /** Selects exact parse calls for actual chart nodes. Shared upstream calls occur once. */
  def provenanceFor(refs: Iterable[ChartNodeRef]): Either[DomainError, FilmSelectedProvenance] =
    val selected = refs.iterator.map(_.sentence).toVector.distinct
    if selected.exists(sentence => !chartsBySentence.contains(sentence)) then
      Left(
        DomainError
          .InvariantViolation("film-input/provenance", "chart node has no checked association")
      )
    else if refs.exists(ref =>
        !chartsBySentence(ref.sentence).evidence.chart.concepts.contains(ref.concept)
      )
    then
      Left(DomainError.InvariantViolation("film-input/provenance", "unknown chart node reference"))
    else
      val records = selected.flatMap { sentence =>
        prepared.origins
          .find(_.sentence == sentence)
          .flatMap(origin => prepared.records.find(_.id == origin.record))
      }.distinct
      Right(
        FilmSelectedProvenance(
          selected.flatMap(chartsBySentence(_).binding.calls).distinct,
          records,
          prepared.derivation.receipt
        )
      )

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
  private def tag(value: String, fields: Vector[String]): String =
    value + "(" + fields.map(Checksum.ofText(_).hex).mkString(",") + ")"
  private def opt(value: Option[String]): String = value.fold("none")(v => tag("some", Vector(v)))
  private def credence(value: Credence): String =
    tag("credence", Vector(value.score.render, value.basis.render))
  private def call(value: ProviderCall): String =
    tag(
      "call",
      Vector(
        value.provider,
        value.model,
        value.version,
        opt(value.promptTemplateVersion.map(_.value)),
        value.inputChecksum.hex,
        value.outputChecksum.hex,
        value.params.toVector
          .sortBy(_._1)
          .map((k, v) => tag("parameter", Vector(k, v)))
          .mkString("\u001e"),
        opt(value.seed.map(_.toString)),
        value.cached.toString
      )
    )
  private def provenance(value: Provenance): String =
    tag(
      "provenance",
      Vector(value.softwareVersion, value.configHash.hex, value.calls.map(call).mkString("\u001e"))
    )
  private def spans(value: SpanSet): String = tag(
    "spans",
    value.refs.toVector.sortBy(r => (r.span.start, r.span.endExclusive, r.unit.map(_.value))).map {
      r =>
        tag(
          "span",
          Vector(r.span.start.toString, r.span.endExclusive.toString, opt(r.unit.map(_.value)))
        )
    }
  )
  private def evidence(value: Evidence): String = tag(
    "evidence",
    Vector(
      value.id.value,
      opt(value.spans.map(spans)),
      value.upstream.toVector.map(_.value).sorted.mkString("\u001e"),
      value.extractor.value,
      value.stage.value,
      opt(value.anchors.map(a => TypedSupport.Anchored(a).identity.hex))
    )
  )
  private def meta(value: ClaimMeta): String = tag(
    "meta",
    Vector(
      value.id.value,
      value.status.ordinal.toString,
      credence(value.credence),
      value.evidence.toVector.map(evidence).mkString("\u001e"),
      provenance(value.provenance)
    )
  )
  private def sourceRole(value: SourceRole): String = value match
    case SourceRole.Numbered(i)       => tag("numbered", Vector(i.toString))
    case SourceRole.Named(n)          => tag("named", Vector(n))
    case SourceRole.Operand(i)        => tag("operand", Vector(i.toString))
    case SourceRole.Extension(ns, nm) => tag("extension", Vector(ns, nm))
  private def participant(value: ParticipantRole): String = value match
    case ParticipantRole.Custom(ns, label) => tag("custom", Vector(ns, label))
    case other                             => tag("standard", Vector(other.ordinal.toString))
  private def role(value: RoleAssignment): String = tag(
    "role",
    Vector(
      sourceRole(value.source),
      opt(value.normalized.map((r, c) => tag("normalized", Vector(participant(r), credence(c)))))
    )
  )
  private def literal(value: LiteralValue): String = value match
    case LiteralValue.Text(v)   => tag("text", Vector(v))
    case LiteralValue.Number(v) => tag("number", Vector(LiteralValue.canonicalNumber(v)))
    case LiteralValue.Symbol(v) => tag("symbol", Vector(v))
  private def target(value: ConceptTarget): String = value match
    case ConceptTarget.Node(id)   => tag("node", Vector(id.value))
    case ConceptTarget.Literal(v) => tag("literal", Vector(literal(v)))
    case ConceptTarget.Unknown    => "unknown"
  private def relation(value: PropositionRelation): String =
    tag("relation", Vector(value.from.value, role(value.role), target(value.to)))
  private def alignmentTarget(value: AlignmentTarget): String = value match
    case AlignmentTarget.Concepts(ids) => tag("concepts", ids.toSortedSet.toVector.map(_.value))
    case AlignmentTarget.Relation(r)   => tag("relation", Vector(relation(r)))
  private def chartOrigin(value: ChartOrigin): String = value match
    case ChartOrigin.Hand                => "hand"
    case ChartOrigin.Parser(fingerprint) => tag("parser", Vector(fingerprint.value))
    case ChartOrigin.Agent(fingerprint)  => tag("agent", Vector(fingerprint.value))
    case ChartOrigin.Converted(from, fp) => tag("converted", Vector(from, fp.value))
    case ChartOrigin.Resolved            => "resolved"
  private def chartProvenance(value: ChartProvenance): String = tag(
    "chart-provenance",
    Vector(
      chartOrigin(value.origin),
      value.receipts.map(call).mkString("\u001e"),
      value.alternatives
        .map((checksum, c) => tag("alternative", Vector(checksum.hex, credence(c))))
        .mkString("\u001e")
    )
  )

  /** Full payload binding. This deliberately retains local IDs, alignments and provenance; it is
    * not the lossy structural canonical checksum used by chart comparison.
    */
  def fullChartChecksum(evidence: PropositionEvidence): Checksum =
    val chart = evidence.chart
    digest(
      Vector(
        "film-chart-payload/v1",
        opt(chart.focus.map(_.value)),
        chart.concepts.toVector
          .sortBy(_._1.value)
          .map { case (id, c) =>
            tag(
              "concept",
              Vector(
                id.value,
                c.lemma.value,
                opt(c.gloss),
                opt(
                  c.frame.map(f =>
                    tag("frame", Vector(f.namespace, f.id, opt(f.senseCredence.map(credence))))
                  )
                ),
                c.kind.ordinal.toString
              )
            )
          }
          .mkString("\u001e"),
        chart.relations.map(relation).mkString("\u001e"),
        chart.polarity.toVector
          .sortBy(_._1.value)
          .map((id, p) => tag("polarity", Vector(id.value, p.ordinal.toString)))
          .mkString("\u001e"),
        chart.embedded
          .map(e =>
            tag("embedded", Vector(e.container.value, e.kind.ordinal.toString, e.content.value))
          )
          .mkString("\u001e"),
        chart.alignments
          .map(a =>
            tag(
              "alignment",
              Vector(alignmentTarget(a.target), spans(a.spans), credence(a.credence), meta(a.meta))
            )
          )
          .mkString("\u001e"),
        chartProvenance(chart.provenance),
        opt(chart.sentence.map(_.value)),
        chartProvenance(evidence.provenance)
      )
    )

  /** Validates chart/attempt joins against one prepared surface. */
  def of(
      prepared: FilmProposalSurface,
      chartAssociations: Vector[FilmChartAssociation],
      expectedParseBindings: Vector[FilmParseBinding],
      attemptScopes: Vector[FilmAttemptScope]
  ): Either[DomainError, FilmCompilerInput] =
    val generatedSentences = prepared.origins.map(_.sentence)
    val chartBySentence = chartAssociations.map(a => a.binding.sentence -> a).toMap
    if chartAssociations.map(_.binding.sentence).distinct.size != chartAssociations.size then
      Left(DomainError.DuplicateId("FilmChartAssociation", "sentence"))
    else if chartAssociations.map(_.binding.sentence).toSet != generatedSentences.toSet ||
      expectedParseBindings.map(_.sentence).toSet != generatedSentences.toSet ||
      expectedParseBindings.groupBy(_.sentence).exists(_._2.size != 1)
    then invalid("charts", "each generated sentence needs exactly one chart association")
    else if chartAssociations.map(_.binding) != expectedParseBindings then
      invalid(
        "chart/binding",
        "ordered parse bindings differ from the independent expected inventory"
      )
    else if attemptScopes.map(_.id).distinct.size != attemptScopes.size then
      Left(DomainError.DuplicateId("FilmAttemptScope", "attempt identity"))
    else
      chartAssociations
        .foldLeft[Either[DomainError, Unit]](Right(())) { (acc, association) =>
          acc.flatMap { _ =>
            val chart = association.evidence.chart
            val binding = association.binding
            val origin = prepared.origins.find(_.sentence == binding.sentence).get
            val record = prepared.records.find(_.id == origin.record).get
            if chart.sentence != Some(binding.sentence) then
              invalid("chart/sentence", "chart is associated with a different sentence")
            else if association.evidence.provenance != chart.provenance then
              invalid("chart/provenance", "evidence provenance differs from chart provenance")
            else if binding.preparedIdentity != prepared.identity || binding.originRecordIdentity != record.identity
            then
              invalid(
                "chart/binding",
                "binding does not name the prepared surface and origin record"
              )
            else if binding.calls != chart.provenance.receipts then
              invalid("chart/calls", "expected parser calls differ from chart receipts")
            else if fullChartChecksum(association.evidence) != binding.outputChartChecksum then
              invalid("chart/output", "expected full chart payload checksum differs")
            else if !prepared.atlas.surface.flatMap(_.surface.byId.get(binding.sentence)).exists {
                sentence =>
                  chart.alignments.forall { alignment =>
                    alignment.target.conceptIds.forall(chart.concepts.contains) &&
                    (alignment.target match
                      case AlignmentTarget.Relation(r) => chart.relations.contains(r)
                      case _                           => true) &&
                    alignment.spans.refs.toVector.forall { ref =>
                      sentence.span.contains(ref.span) && ref.unit.forall(_ == binding.sentence)
                    }
                  }
              }
            then invalid("chart/alignment", "alignment target or span escapes its bound sentence")
            else Right(())
          }
        }
        .flatMap { _ =>
          attemptScopes
            .foldLeft[Either[DomainError, Unit]](Right(())) { (acc, scope) =>
              acc.flatMap { _ =>
                if scope.sentences.isEmpty || scope.sentences.distinct.size != scope.sentences.size
                then
                  invalid("attempt/sentences", "attempt must declare unique contributing sentences")
                else if scope.sentences.exists(sentence => !generatedSentences.contains(sentence))
                then
                  invalid(
                    "attempt/sentences",
                    "attempt declares a sentence outside the prepared surface"
                  )
                else if scope.contributors.map(_.sentence).toSet != scope.sentences.toSet ||
                  scope.contributors.exists(ref =>
                    !chartBySentence
                      .get(ref.sentence)
                      .exists(_.evidence.chart.concepts.contains(ref.concept))
                  )
                then
                  invalid(
                    "attempt/contributors",
                    "contributors must exactly name declared chart sentences"
                  )
                else
                  FilmInputSupport.check(prepared.atlas.bundle, scope.support).flatMap { checked =>
                    val anchors = checked.anchors.toVector
                    if anchors.exists {
                        case _: EvidenceAnchor.MediaTime | _: EvidenceAnchor.MediaPoint => false
                        case _                                                          => true
                      }
                    then
                      invalid(
                        "attempt/support",
                        "only MediaTime and MediaPoint anchors are admitted"
                      )
                    else
                      val permitted = scope.sentences
                        .flatMap(prepared.unitBySentence.get)
                        .flatMap(_.support.anchors.toVector)
                      val grouped = permitted.groupBy(a => (a.anchorStream, a.axisId))
                      val contained = anchors.forall {
                        case EvidenceAnchor.MediaTime(_, stream, axis, intervals) =>
                          grouped
                            .get(stream -> Some(axis))
                            .flatMap { allowed =>
                              (for
                                envelope <- PlaybackSupport.of(
                                  axis,
                                  allowed.flatMap {
                                    case EvidenceAnchor.MediaTime(_, _, _, set) =>
                                      set.intervals.toVector
                                    case _ => Vector.empty
                                  },
                                  allowed.collect { case EvidenceAnchor.MediaPoint(_, _, point) =>
                                    point
                                  }
                                )
                                attempted <- PlaybackSupport.of(
                                  axis,
                                  intervals.intervals.toVector,
                                  Vector.empty
                                )
                              yield envelope.contains(attempted)).toOption
                            }
                            .contains(true)
                        case EvidenceAnchor.MediaPoint(_, stream, point) =>
                          grouped
                            .get(stream -> Some(point.axis))
                            .flatMap { allowed =>
                              (for
                                envelope <- PlaybackSupport.of(
                                  point.axis,
                                  allowed.flatMap {
                                    case EvidenceAnchor.MediaTime(_, _, _, set) =>
                                      set.intervals.toVector
                                    case _ => Vector.empty
                                  },
                                  allowed.collect { case EvidenceAnchor.MediaPoint(_, _, p) => p }
                                )
                                attempted <- PlaybackSupport.of(
                                  point.axis,
                                  Vector.empty,
                                  Vector(point)
                                )
                              yield envelope.contains(attempted)).toOption
                            }
                            .contains(true)
                        case _ => false
                      }
                      if !contained then
                        invalid(
                          "attempt/containment",
                          "support escapes declared sentence envelopes"
                        )
                      else if !anchors.exists(
                          _.axisId.contains(prepared.atlas.bundle.primaryAxis.id)
                        )
                      then invalid("attempt/primary", "attempt lacks direct primary-axis support")
                      else Right(())
                  }
              }
            }
            .map { _ =>
              val identity = digest(
                Vector("film-compiler-input/v1", prepared.identity.hex) ++
                  chartAssociations.flatMap { a =>
                    val b = a.binding
                    Vector(
                      b.sentence.value,
                      b.preparedIdentity.hex,
                      b.originRecordIdentity.hex,
                      b.parser.value,
                      b.requestChecksum.hex,
                      b.recipeIdentity.hex,
                      b.outputChartChecksum.hex
                    ) ++
                      b.calls.map(call)
                  } ++
                  attemptScopes.flatMap(s =>
                    Vector(s.id) ++ s.sentences.map(_.value) ++
                      s.contributors.flatMap(r => Vector(r.sentence.value, r.concept.value)) ++
                      Vector(TypedSupport.Anchored(s.support).identity.hex)
                  )
              )
              new FilmCompilerInput(prepared, chartAssociations, attemptScopes, identity)
            }
        }

/** Shared film-input admissibility; checked bundle membership precedes kind/projection rules. */
private[document] object FilmInputSupport:
  def check(bundle: SourceBundle, support: EvidenceSupport): Either[DomainError, EvidenceSupport] =
    support.checkedOn(bundle).flatMap { checked =>
      val anchors = checked.anchors.toVector
      if anchors.exists {
          case _: EvidenceAnchor.MediaTime | _: EvidenceAnchor.MediaPoint => false
          case _                                                          => true
        }
      then
        Left(
          DomainError.InvariantViolation(
            "film-input/support",
            "only MediaTime and MediaPoint anchors are admitted"
          )
        )
      else if !anchors.exists(_.axisId.contains(bundle.primaryAxis.id)) then
        Left(
          DomainError
            .InvariantViolation("film-input/primary", "support lacks a direct primary-axis member")
        )
      else Right(checked)
    }
