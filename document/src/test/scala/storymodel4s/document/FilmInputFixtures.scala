package storymodel4s.document

import storymodel4s.core.*
import storymodel4s.proposition.*

/** Admitted mechanical A/B input shared by F1 association courts. */
private[document] object FilmInputFixtures:
  val bundle = SourceBundle
    .filmEdition(
      EditionId.unsafe("f1-ab"),
      Checksum.ofText("picture"),
      0,
      400,
      RationalTimebase.Millisecond
    )
    .toOption
    .get
  val stream = bundle.streams.head.id
  def support(a: Long, b: Long) = EvidenceSupport
    .of(
      bundle,
      Vector(
        EvidenceAnchor.MediaTime(
          bundle.id,
          stream,
          bundle.primaryAxis.id,
          PlaybackIntervalSet.one(PlaybackInterval.on(bundle.primaryAxis, a, b).toOption.get)
        )
      )
    )
    .toOption
    .get
  private def row(id: String, text: String, a: Long, b: Long) = FilmSourceRecord(
    FilmSourceRecordId.unsafe(id),
    text,
    support(a, b),
    Checksum.ofText(s"source-$id"),
    Checksum.ofText(s"output-$id"),
    SourceDerivationReceipt.of("caption/v1", id, Vector(Checksum.ofText(id))).toOption.get
  )
  val rows = Vector(row("a", "Alpha.", 0, 100), row("b", "Beta.", 100, 200))
  private def digest(fields: Vector[String]) =
    Checksum.ofText(fields.map(Checksum.ofText(_).hex).mkString("\u001f"))
  private val receipt = SourceDerivationReceipt
    .of(
      "FilmProposalSurface/v1",
      FilmProposalSurface.RecipeV1,
      Vector(bundle.identity, digest(rows.map(_.identity.hex)), digest(Vector.empty))
    )
    .toOption
    .get
  val prepared = FilmProposalSurface
    .derive(
      bundle,
      rows,
      Vector.empty,
      FilmProposalDerivation(
        receipt,
        FilmProposalSurface.RecipeV1,
        Checksum.ofText("Alpha.\n\nBeta."),
        bundle.identity,
        rows.map(_.identity),
        Vector.empty
      )
    )
    .toOption
    .get
  val a = prepared.origins(0).sentence
  val b = prepared.origins(1).sentence
  val parser = Fingerprint.unsafe("f1-parser")
  val shared = ProviderCall(
    "parser",
    "model",
    "1",
    None,
    Checksum.ofText("request-shared"),
    Checksum.ofText("shared"),
    Map.empty,
    None,
    false
  )
  val callA = ProviderCall(
    "parser",
    "model",
    "1",
    None,
    Checksum.ofText("request-a"),
    Checksum.ofText("a"),
    Map.empty,
    None,
    false
  )
  val callB = ProviderCall(
    "parser",
    "model",
    "1",
    None,
    Checksum.ofText("request-b"),
    Checksum.ofText("b"),
    Map.empty,
    None,
    false
  )
  def chart(sentence: SurfaceUnitId, id: String, calls: Vector[ProviderCall]): PropositionEvidence =
    val concept = ConceptId.unsafe(id)
    PropositionEvidence.of(
      ChartValidator
        .check(
          PropositionChart.unchecked(
            Some(concept),
            Map(concept -> Concept.entity(id)),
            Vector.empty,
            provenance = ChartProvenance(ChartOrigin.Parser(parser), calls, Vector.empty),
            sentence = Some(sentence)
          )
        )
        .toOption
        .get
    )
  val evidenceA = chart(a, "a0", Vector(callA, shared))
  val evidenceB = chart(b, "b0", Vector(callB, shared))
  def binding(sentence: SurfaceUnitId, ev: PropositionEvidence, calls: Vector[ProviderCall]) =
    FilmParseBinding(
      sentence,
      prepared.identity,
      rows(prepared.origins.indexWhere(_.sentence == sentence)).identity,
      parser,
      calls.head.inputChecksum,
      Checksum.ofText("recipe"),
      FilmCompilerInput.fullChartChecksum(ev),
      calls
    )
  val bindingA = binding(a, evidenceA, Vector(callA, shared))
  val bindingB = binding(b, evidenceB, Vector(callB, shared))
  val associations =
    Vector(FilmChartAssociation(evidenceA, bindingA), FilmChartAssociation(evidenceB, bindingB))
  val expected = Vector(bindingA, bindingB)
  def scope(
      sentences: Vector[SurfaceUnitId],
      contributors: Vector[ChartNodeRef],
      s: EvidenceSupport = support(0, 200)
  ) =
    FilmAttemptScope("scope", sentences, contributors, s)
