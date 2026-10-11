package storymodel4s.codec

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import storymodel4s.align.*
import storymodel4s.align.bridge.{TextSourceView, TimedSourceView}
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.story.{ModelStatus, StoryValidator, TextModel}
import storymodel4s.view.*
import RecallCodecs.given
import HsmmResultCodec.given

/** Ordinary-source inspection preserves imported claims without reconstructing execution authority.
  */
object SurfaceWorkspaceCodecs:
  enum SourceKind:
    case Text(kind: SurfaceUnitKind)
    case TimedAnnotations, NarrativeModel, VideoCaptions

  private type Result[A] = Either[WorkspaceRefusal, A]
  private def admitted[E, A](value: Either[E, A]): Result[A] =
    value.left.map(_ => WorkspaceRefusal.SemanticJoinMismatch)
  private def field[A: Decoder](json: Json, name: String): Result[A] =
    json.hcursor.get[A](name).left.map(_ => WorkspaceRefusal.UnsupportedContent)
  private def number(json: Json, name: String): Result[Double] =
    field[String](json, name).flatMap(value =>
      CanonicalPrimitives
        .parseHexDouble(value)
        .filter(_.isFinite)
        .toRight(WorkspaceRefusal.UnsupportedContent)
    )
  private def check(ok: Boolean): Result[Unit] =
    Either.cond(ok, (), WorkspaceRefusal.SemanticJoinMismatch)
  private def parse(text: String): Result[Json] = for
    _ <- admitted(MappingJson.uniqueObjectKeys(text))
    value <- admitted(Canonical.parse(text))
  yield value
  private def text(archive: WorkspaceArchive, role: WorkspaceRole): Result[String] =
    archive.manifest
      .bytes(role)
      .toRight(WorkspaceRefusal.MissingRequiredRole)
      .flatMap(WorkspaceArchiveCodec.utf8)

  /** These are immutable imported claims, not a MappingResult or a live derived-stage witness. */
  final class Policy private[SurfaceWorkspaceCodecs] (
      val id: ArtifactId,
      val bytes: String,
      val document: Json,
      val claimedRecordDigest: Checksum
  ):
    val byteDigest: Checksum = Checksum.ofText(bytes)
    val outcomes: Vector[Json] = document.hcursor.get[Vector[Json]]("outcomes").toOption.get
    val declarations: Json = document.hcursor.get[Json]("policies").toOption.get
    override def equals(other: Any): Boolean = other match
      case p: Policy => id == p.id && bytes == p.bytes
      case _         => false
    override def hashCode: Int = (id, bytes).hashCode
    override def toString: String = s"ImportedMapping(${id.value},${byteDigest.short()})"

  /** The exact archive joins a real source, checked recall inventory and both imported members. */
  final class Investigation private[SurfaceWorkspaceCodecs] (
      val archive: WorkspaceArchive,
      val sourceArtifact: String,
      val sourceKind: SourceKind,
      val view: SourceView,
      val source: SourceRepresentation,
      val narrativeModel: Option[TextModel[ModelStatus.Validated]],
      val recall: RecallGraph[Checked],
      val inventory: RecallInventory,
      val policies: Vector[Policy],
      val disclosureDescription: Json,
      val producerRevision: String,
      private val canonicalArchive: String
  ):
    /** Portable imported Draft evidence; this never mints a JVM execution witness. */
    val mediaCaptions: Option[MediaCaptionSourceCodec.Imported] = sourceKind match
      case SourceKind.VideoCaptions => MediaCaptionSourceCodec.decode(sourceArtifact).toOption
      case _                        => None
    val sourceEdition: Checksum = Checksum.ofText(sourceArtifact)
    val identity: Checksum = Checksum.ofText(canonicalArchive)

    /** Export authorization is separate from permission to inspect the admitted packet. */
    def exportBytes: Either[WorkspaceRefusal, String] =
      WorkspaceArchiveCodec.encode(archive.manifest)
    def policy(id: ArtifactId): Option[Policy] = policies.find(_.id == id)
    def recallText(id: RecallUnitId): Option[String] = recall
      .unit(id)
      .map(u =>
        u.span.refs.toVector
          .map(_.span.slice(recall.transcript.canonicalText).toOption.get)
          .mkString(" … ")
      )
    val sourceDocument: String = view match
      case v: TextSourceView  => v.sourcePackage.source.canonicalText
      case v: TimedSourceView => v.document
      case _                  => narrativeModel.map(_.source.canonicalText).getOrElse("")
    def sourceText(id: SourceNodeRef): Option[String] = view
      .node(id)
      .flatMap(_.scoringPosition)
      .map(p =>
        p.spans.refs.toVector.map(_.span.slice(sourceDocument).toOption.get).mkString(" … ")
      )
    override def equals(other: Any): Boolean = other match
      case w: Investigation => identity == w.identity
      case _                => false
    override def hashCode: Int = identity.hashCode
    override def toString: String = s"ImportedInvestigation(${identity.short()})"

  private def sourceJson(artifact: String, kind: SourceKind): Json = Json.obj(
    "schemaVersion" -> Json.fromString("workspace-source-input/v1"),
    "kind" -> Json.fromString(kind match
      case SourceKind.Text(_)          => "text"
      case SourceKind.TimedAnnotations => "timed-annotations"
      case SourceKind.NarrativeModel   => "narrative-model"
      case SourceKind.VideoCaptions    => "video-captions"),
    "segmentKind" -> (kind match
      case SourceKind.Text(k) => Json.fromString(k.toString)
      case _                  => Json.Null),
    "artifact" -> Json.fromString(artifact)
  )

  private def sourceInput(json: Json): Result[
    (String, SourceKind, SourceView, SourceRepresentation, Option[TextModel[ModelStatus.Validated]])
  ] =
    for
      schema <- field[String](json, "schemaVersion")
      _ <- check(schema == "workspace-source-input/v1")
      label <- field[String](json, "kind")
      artifact <- field[String](json, "artifact")
      result <- label match
        case "text" =>
          for
            k <- field[String](json, "segmentKind")
            kind <- SurfaceUnitKind.values
              .find(_.toString == k)
              .toRight(WorkspaceRefusal.UnsupportedContent)
            source <- admitted(
              TextSourceArtifactCodec.decode(
                artifact.getBytes(java.nio.charset.StandardCharsets.UTF_8)
              )
            )
            view <- admitted(TextSourceView.of(source, kind))
          yield (
            SourceKind.Text(kind),
            view: SourceView,
            view.representation,
            Option.empty[TextModel[ModelStatus.Validated]]
          )
        case "timed-annotations" =>
          admitted(TimedSourceArtifactCodec.decode(artifact)).map(v =>
            (
              SourceKind.TimedAnnotations,
              v: SourceView,
              v.representation,
              Option.empty[TextModel[ModelStatus.Validated]]
            )
          )
        case "video-captions" =>
          admitted(MediaCaptionSourceCodec.decode(artifact)).map(v =>
            (
              SourceKind.VideoCaptions,
              v.view: SourceView,
              v.view.representation,
              Option.empty[TextModel[ModelStatus.Validated]]
            )
          )
        case "narrative-model" =>
          for
            draft <- admitted(StoryModelCodec.decode(artifact))
            model <- StoryValidator
              .validate(draft)
              .validated
              .toRight(WorkspaceRefusal.UnsupportedContent)
            pair <- WorkspaceCodecs.sourceFor(model)
          yield (SourceKind.NarrativeModel, pair._1: SourceView, pair._2, Some(model))
        case _ => Left(WorkspaceRefusal.UnsupportedContent)
      _ <- check(json == sourceJson(artifact, result._1))
    yield (artifact, result._1, result._2, result._3, result._4)

  private def optional(value: Json): Result[(String, Option[Json])] =
    field[String](value, "status").flatMap {
      case "present" => field[Json](value, "value").map("present" -> Some(_))
      case "absent"  => field[String](value, "reason").map(_ => "absent" -> None)
      case _         => Left(WorkspaceRefusal.UnsupportedContent)
    }

  private def destination(value: String, source: SourceRepresentation): Result[Destination] =
    admitted(MappingJson.destination(value)).flatMap { result =>
      check(result match
        case Destination.Target(ref) => source.target(ref).nonEmpty
        case Destination.External(_) => true).map(_ => result)
    }

  /** Validate the ledger's wire relationships without reifying an imported derived stage. */
  private def stages(value: Json): Result[Map[String, Stage]] =
    field[Vector[Json]](value, "stage_assumption_receipts").flatMap { entries =>
      entries
        .traverse { entry =>
          for
            id <- field[String](entry, "id").flatMap(v => admitted(Checksum.from(v)))
            stageName <- field[String](entry, "stage")
            stage <- Stage.values
              .find(_.toString == stageName)
              .toRight(WorkspaceRefusal.UnsupportedContent)
            provenance <- field[Json](entry, "provenance")
            status <- field[String](provenance, "status")
            _ <- status match
              case "derived" =>
                field[Json](provenance, "receipt").flatMap { receipt =>
                  admitted(MappingMetadataRead.receipt(receipt))
                    .flatMap(r => check(r.stage == stage))
                }
              case "unknown" =>
                for
                  reason <- field[String](provenance, "reason")
                  _ <- check(UnknownProvenanceReason.values.exists(_.toString == reason))
                  asserted <- field[Json](provenance, "asserted")
                  _ <- optional(asserted).flatMap(
                    _._2.traverse(r =>
                      admitted(MappingMetadataRead.receipt(r))
                        .flatMap(receipt => check(receipt.stage == stage))
                    )
                  )
                yield ()
              case _ => Left(WorkspaceRefusal.UnsupportedContent)
          yield id.hex -> stage
        }
        .flatMap { rows =>
          check(
            rows.map(_._1).distinct.size == rows.size && rows.map(_._2).distinct.size == rows.size
          )
            .map(_ => rows.toMap)
        }
    }

  private def stage(
      value: Json,
      name: String,
      expected: Stage,
      ledger: Map[String, Stage]
  ): Result[Unit] =
    field[String](value, name).flatMap(id => check(ledger.get(id).contains(expected)))

  private def emptyOutcome(outcome: Json): Result[Unit] =
    for
      links <- field[Vector[Json]](outcome, "mapping_links")
      _ <- check(links.isEmpty)
      rendered <- field[Vector[Json]](outcome, "links")
      _ <- check(rendered.isEmpty)
      measures <- field[Json](outcome, "measures")
      raw <- field[Vector[Json]](measures, "raw")
      _ <- check(raw.isEmpty)
      _ <- Vector("normalized", "posterior", "transport").traverse_(name =>
        field[Json](measures, name).flatMap(optional).flatMap(v => check(v._1 == "absent"))
      )
      decision <- field[Json](outcome, "decision").flatMap(optional)
      _ <- check(decision._1 == "absent")
      refs <- field[Json](outcome, "stages").flatMap(optional)
      _ <- check(refs._1 == "absent")
    yield ()

  private def outcomeClaims(
      outcome: Json,
      expectedKind: String,
      source: SourceRepresentation,
      ledger: Map[String, Stage]
  ): Result[Unit] =
    for
      processing <- field[Json](outcome, "processing_status")
      processingStatus <- field[String](processing, "status")
      localization <- field[String](outcome, "localization_status")
      _ <- check(LocalizationStatus.values.exists(_.toString == localization))
      _ <- processingStatus match
        case "failed" =>
          for
            failure <- field[Json](processing, "failure")
            kind <- field[String](failure, "status")
            detail <- field[String](failure, "detail")
            _ <- check(
              Set("provider-failure", "invalid-output", "inference-refused")(
                kind
              ) && detail.trim.nonEmpty &&
                localization == LocalizationStatus.NotComputed.toString
            )
            _ <- emptyOutcome(outcome)
          yield ()
        case "excluded" =>
          for
            reason <- field[String](processing, "reason")
            _ <- check(
              reason.trim.nonEmpty && localization == LocalizationStatus.NotComputed.toString
            )
            _ <- emptyOutcome(outcome)
          yield ()
        case "complete" => completeOutcome(outcome, expectedKind, source, ledger, localization)
        case _          => Left(WorkspaceRefusal.UnsupportedContent)
    yield ()

  private def completeOutcome(
      outcome: Json,
      expectedKind: String,
      source: SourceRepresentation,
      ledger: Map[String, Stage],
      localization: String
  ): Result[Unit] =
    for
      refs <- field[Json](outcome, "stages").flatMap(optional)
      stageRefs <- refs._2.toRight(WorkspaceRefusal.SemanticJoinMismatch)
      _ <- stage(stageRefs, "inference", Stage.Inference, ledger)
      _ <- stage(stageRefs, "candidates", Stage.Candidates, ledger)
      _ <- stage(stageRefs, "decision", Stage.Decision, ledger)
      rows <- field[Vector[Json]](outcome, "mapping_links")
      values <- rows.traverse { row =>
        for
          kind <- field[String](row, "measure_kind")
          _ <- check(
            kind == expectedKind || (expectedKind == "ModelPosterior" && kind == "RawScore")
          )
          channel <- field[Json](row, "channel").flatMap(optional)
          _ <- check((kind == "RawScore") == channel._2.exists(_.asString.exists(_.trim.nonEmpty)))
          destination <- field[String](row, "destination").flatMap(destination(_, source))
          value <- number(row, "raw_value")
          _ <- check(value.isFinite && (kind == "RawScore" || value >= 0.0))
          state <- field[Json](row, "state").flatMap(optional)
          decodedState <- state._2.traverse(v => admitted(Canonical.decodeJson[AlignState](v)))
          _ <- check(
            if kind == "ModelPosterior" then
              decodedState.exists(s => Destination.of(s) == destination)
            else decodedState.isEmpty
          )
        yield (kind, destination, value)
      }
      _ <- check(
        values.map((kind, destination, _) => kind -> destination).distinct.size == values.size
      )
      measures <- field[Json](outcome, "measures")
      raw <- field[Vector[Json]](measures, "raw")
      _ <- raw.traverse_(row => stage(row, "stage", Stage.Scoring, ledger))
      family = if expectedKind == "ModelPosterior" then "posterior" else "normalized"
      main <- field[Json](measures, family).flatMap(optional)
      mass = values.collect { case (kind, _, value) if kind == expectedKind => value }
      _ <- check(
        (main._1 == "present") == mass.nonEmpty && (mass.isEmpty || math.abs(
          mass.sum - 1.0
        ) <= 1e-12)
      )
      _ <- main._2.traverse(row => stage(row, "stage", Stage.Inference, ledger))
      _ <- (Set("normalized", "posterior", "transport") - family).toVector.traverse_(name =>
        field[Json](measures, name).flatMap(optional).flatMap(v => check(v._1 == "absent"))
      )
      links <- field[Vector[Json]](outcome, "links")
      _ <- links.traverse_ { link =>
        for
          _ <- field[String](link, "destination").flatMap(destination(_, source))
          _ <- stage(link, "inference_stage_id", Stage.Inference, ledger)
          _ <- field[String](link, "candidate_set_id").flatMap(v => admitted(Checksum.from(v))).void
          _ <- field[Json](link, "term_support")
            .flatMap(field[String](_, "status"))
            .flatMap(status => check(Set("not-computed", "evaluated")(status)))
        yield ()
      }
      _ <- check(
        links.flatMap(_.hcursor.get[String]("destination").toOption).toSet == values
          .map(_._2.key)
          .toSet
      )
      decision <- field[Json](outcome, "decision").flatMap(optional)
      value <- decision._2.toRight(WorkspaceRefusal.SemanticJoinMismatch)
      basis <- field[Json](value, "basis")
      basisKind <- field[String](basis, "measure_kind")
      _ <- check(basisKind == expectedKind)
      basisChannel <- field[Json](basis, "channel").flatMap(optional)
      _ <- check(
        (basisKind == "RawScore") == basisChannel._2.exists(_.asString.exists(_.trim.nonEmpty))
      )
      request <- field[Json](value, "request")
      requestStatus <- field[String](request, "status")
      _ <- requestStatus match
        case "raw-argmax"      => Right(())
        case "external-decode" =>
          for
            _ <- field[String](request, "chosen").flatMap(destination(_, source))
            _ <- field[String](request, "policy").flatMap(v => admitted(DecisionPolicyId.from(v)))
          yield ()
        case "abstain" =>
          for
            _ <- field[String](request, "policy").flatMap(v => admitted(DecisionPolicyId.from(v)))
            reason <- field[String](request, "reason")
            _ <- check(reason.trim.nonEmpty)
          yield ()
        case _ => Left(WorkspaceRefusal.UnsupportedContent)
      chosen <- field[Json](value, "decoded_target_id").flatMap(optional)
      chosenDestination <- chosen._2.traverse(v =>
        v.asString.toRight(WorkspaceRefusal.UnsupportedContent).flatMap(destination(_, source))
      )
      basisValues = values.collect {
        case (kind, destination, score) if kind == expectedKind => destination -> score
      }.toMap
      expectedArgmax = basisValues.toVector.sortWith { (a, b) =>
        if a._2 == b._2 then a._1.key < b._1.key else a._2 > b._2
      }.headOption
      raw <- field[Json](value, "raw_argmax").flatMap(optional)
      actualArgmax <- raw._2.traverse { row =>
        for
          target <- field[String](row, "destination").flatMap(destination(_, source))
          score <- number(row, "value")
        yield target -> score
      }
      _ <- check(actualArgmax == expectedArgmax)
      _ <- check(
        if requestStatus == "abstain" || basisValues.isEmpty then chosenDestination.isEmpty
        else if requestStatus == "raw-argmax" then chosenDestination == expectedArgmax.map(_._1)
        else chosenDestination.nonEmpty
      )
      decodedMass <- field[Json](value, "decoded_target_mass")
      decodedStatus <- field[String](decodedMass, "status")
      _ <- decodedStatus match
        case "value" =>
          for
            mass <- number(decodedMass, "value")
            target <- chosenDestination.toRight(WorkspaceRefusal.SemanticJoinMismatch)
            supported <- field[Boolean](decodedMass, "decision_in_candidate_support")
            _ <- check(
              supported == basisValues
                .contains(target) && mass == basisValues.getOrElse(target, 0.0)
            )
          yield ()
        case "no-decision" => check(chosenDestination.isEmpty)
        case _             => Left(WorkspaceRefusal.UnsupportedContent)
      origin <- field[Json](value, "decision_origin")
      originStatus <- field[String](origin, "status")
      _ <- originStatus match
        case "raw-argmax"                     => Right(())
        case "structured-decode" | "gap-fill" =>
          field[String](origin, "decision_policy_id")
            .flatMap(v => admitted(DecisionPolicyId.from(v)))
            .void
        case "abstention" =>
          field[String](origin, "reason").flatMap(reason => check(reason.trim.nonEmpty))
        case _ => Left(WorkspaceRefusal.UnsupportedContent)
      calibration <- field[Json](value, "calibration")
      _ <- field[String](calibration, "status").flatMap(status => check(status == "unavailable"))
      _ <- field[String](calibration, "reason").flatMap(reason =>
        check(reason == DecisionCalibrationUnavailableReason.NoCalibrationArtifact.toString)
      )
      policy <- field[Json](value, "decision_policy_id").flatMap(optional)
      _ <- policy._2.traverse(v =>
        v.asString
          .toRight(WorkspaceRefusal.UnsupportedContent)
          .flatMap(id => admitted(DecisionPolicyId.from(id)))
      )
      _ <- check(
        requestStatus != "external-decode" ||
          (chosen._2.map(_.asString).flatten == request.hcursor.get[String]("chosen").toOption)
      )
      _ <- check(
        localization != LocalizationStatus.NotComputed.toString || requestStatus == "abstain"
      )
    yield ()

  private def readPolicy(
      id: ArtifactId,
      bytes: String,
      inventory: RecallInventory,
      source: SourceRepresentation
  ): Result[Policy] =
    for
      json <- parse(bytes)
      schema <- field[String](json, "schemaVersion")
      _ <- check(schema == MappingCodecs.SchemaVersion)
      recordSchema <- field[String](json, "schema")
      _ <- check(recordSchema == MappingCodecs.Schema)
      inv <- field[Json](json, "inventory")
      src <- field[Json](json, "source")
      _ <- check(
        inv == MappingSourceWire.inventory(inventory) && src == MappingSourceWire.source(source)
      )
      _ <- admitted(MappingSourceWire.exactCoordinates(src))
      _ <- admitted(MappingSourceWire.validatePhysical(src, source))
      policies <- field[Json](json, "policies")
      declared <- admitted(MappingMetadataRead.policies(policies))
      _ <- check(declared.universe.targets.forall(source.target(_).nonEmpty))
      _ <- field[Json](json, "roles").flatMap(v =>
        admitted(MappingMetadataRead.roles(v, inventory))
      )
      derivation <- field[Json](json, "derivation_source")
      derivationStatus <- field[String](derivation, "status")
      _ <- derivationStatus match
        case "none"  => Right(())
        case "bound" =>
          field[Json](derivation, "binding").flatMap { binding =>
            Vector(
              "recall_checksum",
              "recall_supplement",
              "inventory_digest",
              "view_fingerprint",
              "scope_digest",
              "result_digest"
            )
              .traverse_(name =>
                field[String](binding, name).flatMap(v => admitted(Checksum.from(v))).void
              )
          }
        case _ => Left(WorkspaceRefusal.UnsupportedContent)
      ledger <- stages(json)
      outcomes <- field[Vector[Json]](json, "outcomes")
      units <- outcomes.traverse(o => field[String](o, "unit"))
      _ <- check(units == inventory.units.map(_.id.value))
      _ <- outcomes.traverse_(
        outcomeClaims(
          _,
          if id.value == "reference" then "NormalizedScoreMass" else "ModelPosterior",
          source,
          ledger
        )
      )
      digest <- field[String](json, "record_digest").flatMap(v => admitted(Checksum.from(v)))
    yield new Policy(id, bytes, json, digest)

  private def pairJoin(
      policies: Vector[Policy],
      inventory: RecallInventory,
      source: SourceRepresentation
  ): Result[Unit] =
    policies
      .traverse { p =>
        for
          entries <- field[Vector[Json]](p.document, "stage_assumption_receipts")
          derived = entries.filter(
            _.hcursor.downField("provenance").get[String]("status").contains("derived")
          )
          stages <- derived.traverse(e => field[String](e, "stage"))
          _ <- check(stages.sorted == Vector("Decision", "Inference"))
          bindings <- derived.traverse { entry =>
            for
              stage <- field[String](entry, "stage")
              provenance <- field[Json](entry, "provenance")
              receipt <- field[Json](provenance, "receipt")
              policy <- field[String](receipt, "policy")
              member = if p.id.value == "reference" then "Reference" else "Reconstruction"
              _ <- check(policy == s"mapping-run/v1/$member/$stage")
              execution <- field[String](receipt, "config")
              _ <- admitted(Checksum.from(execution))
              inputs <- field[Vector[String]](receipt, "inputs")
              _ <- check(
                inputs.size == 3 && inputs
                  .drop(1) == Vector(inventory.digest.hex, source.digest.hex)
              )
              _ <- admitted(Checksum.from(inputs.head))
            yield execution -> inputs.head
          }
          _ <- check(bindings.distinct.size == 1)
        yield bindings.head
      }
      .flatMap(bindings => check(bindings.distinct.size == 1))

  def decode(input: String): Result[Investigation] = for
    archive <- WorkspaceArchiveCodec.decode(input)
    archiveJson <- parse(input)
    _ <- check(archive.manifest.schemaVersion == WorkspaceManifest.SurfaceSchemaVersion)
    sourceText <- text(archive, WorkspaceRole.SourceInput)
    sourceJson <- parse(sourceText)
    source <- sourceInput(sourceJson)
    recallText <- text(archive, WorkspaceRole.Recall)
    recall <- admitted(Canonical.decode[RecallGraph[Checked]](recallText))
    inventoryText <- text(archive, WorkspaceRole.Inventory)
    invJson <- parse(inventoryText)
    inv <- WorkspaceCodecs.readInventory(invJson, recall)
    _ <- check(inv._2.values.forall(_ == WorkspaceTiming.Untimed))
    receiptText <- text(archive, WorkspaceRole.Receipt)
    receipt <- parse(receiptText)
    revision <- field[String](receipt, "producerRevision")
    _ <- check(revision.matches("[0-9a-f]{40}"))
    disclosure <- field[Json](receipt, "disclosure")
    disclosureSchema <- field[String](disclosure, "schemaVersion")
    scope <- field[String](disclosure, "scope")
    textDigest <- field[String](disclosure, "textDigest")
    basis <- field[Json](disclosure, "basis").flatMap(field[String](_, "kind"))
    _ <- check(
      disclosureSchema == "recall-disclosure-receipt/v1" && scope == "local-workspace" &&
        textDigest == Checksum.ofText(recall.transcript.rawText).hex &&
        Set("public-corpus-admitted", "certified-pseudonymized")(basis)
    )
    derivationText <- text(archive, WorkspaceRole.Derivation)
    derivation <- parse(derivationText)
    _ <- check(
      derivation == Json.obj(
        "schemaVersion" -> Json.fromString("imported-mapping-claims/v1"),
        "authority" -> Json.fromString("inspection-only")
      )
    )
    ids = archive.manifest.mappings.map(_.id)
    _ <- check(ids.map(_.value).toSet == Set("reference", "reconstruction"))
    policies <- ids.traverse(id =>
      text(archive, WorkspaceRole.Mapping(id)).flatMap(readPolicy(id, _, inv._1, source._4))
    )
    _ <- pairJoin(policies, inv._1, source._4)
    members <- field[Vector[Json]](receipt, "members")
    _ <- check(members == memberDescriptions(policies))
    edition <- field[String](receipt, "sourceEdition")
    _ <- check(edition == Checksum.ofText(source._1).hex)
  yield new Investigation(
    archive,
    source._1,
    source._2,
    source._3,
    source._4,
    source._5,
    recall,
    inv._1,
    policies,
    disclosure,
    revision,
    MappingJson.print(archiveJson)
  )

  private def memberDescriptions(policies: Vector[Policy]): Vector[Json] = policies.map(p =>
    Json.obj(
      "id" -> Json.fromString(p.id.value),
      "bytes" -> Json.fromString(p.byteDigest.hex),
      "recordClaim" -> Json.fromString(p.claimedRecordDigest.hex)
    )
  )

  /** Packaging checks grants and joins; disclosure authority must be retained by the JVM caller. */
  def create(
      sourceArtifact: String,
      kind: SourceKind,
      recall: RecallGraph[Checked],
      inventory: RecallInventory,
      reference: MappingResult,
      reconstruction: MappingResult,
      disclosureDescription: String,
      revision: String,
      inspection: WorkspaceContentGrant,
      exportPermission: WorkspaceContentGrant
  ): Result[Investigation] =
    if inspection != WorkspaceContentGrant.Granted || exportPermission != WorkspaceContentGrant.Granted
    then Left(WorkspaceRefusal.PermissionDenied)
    else
      for
        source <- sourceInput(sourceJson(sourceArtifact, kind))
        _ <- check(inventory.describes(recall))
        policies <- Vector("reference" -> reference, "reconstruction" -> reconstruction)
          .sortBy(_._1)
          .traverse { (name, record) =>
            readPolicy(ArtifactId.unsafe(name), MappingCodecs.encode(record), inventory, source._4)
          }
        disclosure <- parse(disclosureDescription)
        receipt = Json.obj(
          "producerRevision" -> Json.fromString(revision),
          "disclosure" -> disclosure,
          "sourceEdition" -> Json.fromString(Checksum.ofText(sourceArtifact).hex),
          "members" -> Json.fromValues(memberDescriptions(policies))
        )
        contents = Vector(
          WorkspaceRole.SourceInput -> MappingJson.print(sourceJson(sourceArtifact, kind)),
          WorkspaceRole.Recall -> Canonical.encode(recall),
          WorkspaceRole.Inventory -> Canonical.print(
            WorkspaceCodecs
              .inventoryJson(inventory, inventory.units.map(_.id -> WorkspaceTiming.Untimed).toMap)
          ),
          WorkspaceRole.Derivation -> Canonical.print(
            Json.obj(
              "schemaVersion" -> Json.fromString("imported-mapping-claims/v1"),
              "authority" -> Json.fromString("inspection-only")
            )
          ),
          WorkspaceRole.Receipt -> Canonical.print(receipt)
        ) ++ policies.map(p => WorkspaceRole.Mapping(p.id) -> p.bytes)
        base = contents.sortBy(_._1.key).zipWithIndex.map { case ((role, value), index) =>
          WorkspaceCodecs.member(role, s"member-$index.json", value)
        }
        capabilities = WorkspaceCapabilities(
          base
            .find(_._1.role == WorkspaceRole.SourceInput)
            .get
            ._1
            .disposition
            .asInstanceOf[WorkspaceDisposition.Supplied]
            .artifact
            .checksum,
          base
            .find(_._1.role == WorkspaceRole.Recall)
            .get
            ._1
            .disposition
            .asInstanceOf[WorkspaceDisposition.Supplied]
            .artifact
            .checksum,
          inspection,
          exportPermission,
          Checksum.ofText(Canonical.print(receipt))
        )
        capability = WorkspaceCodecs.member(
          WorkspaceRole.Capabilities,
          "capabilities.json",
          WorkspaceArchiveCodec.encodeCapabilities(capabilities)
        )
        supplied = base :+ capability
        manifest <- WorkspaceManifest.of(
          WorkspaceManifest.SurfaceSchemaVersion,
          supplied.map(_._1),
          supplied.map((e, b) => e.path -> b).toMap
        )
        encoded <- WorkspaceArchiveCodec.encode(manifest)
        result <- decode(encoded)
      yield result
