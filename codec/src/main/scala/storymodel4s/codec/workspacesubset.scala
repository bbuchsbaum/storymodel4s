package storymodel4s.codec

import java.nio.charset.StandardCharsets

import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.features.CanonicalDouble
import storymodel4s.view.*

import CanonicalPrimitives.given
import CoreCodecs.given

/** Provider-owned selection export. The scientific rows are copied from the checked record; no
  * measure, normalization, candidate set or decision is recomputed for the selection.
  */
object WorkspaceSubsetCodec:
  val SchemaVersion = "workspace-selection/v0.1"
  val ReceiptVersion = "workspace-selection-receipt/v0.1"
  val Query = "selected-recall-and-inverse-source/v1"

  final class Payload private[WorkspaceSubsetCodec] (
      val dataJson: String,
      val tableCsv: String,
      val accessibleText: String,
      val receiptJson: String
  )

  private def str(value: String): Json = Json.fromString(value)
  private def obj(fields: (String, Json)*): Json = Json.obj(fields*)
  private def pieces(value: Vector[(SpanRef, String)]): Json =
    Json.fromValues(value.map((ref, text) => obj("span" -> ref.asJson, "text" -> str(text))))
  private def csv(fields: Vector[String]): String =
    fields.map(s => "\"" + s.replace("\"", "\"\"") + "\"").mkString(",")
  private def utf8(value: String): Boolean =
    new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8) == value

  /** A selected source expands to every referring recall row. An empty selection means an empty
    * subset, never an implicit export-all. All dictionary/context metadata remains available so the
    * subset cannot be mistaken for a different normalization universe.
    */
  def selected(
      workspace: SourceRecallWorkspace,
      policyId: ArtifactId,
      selection: Set[Address]
  ): Either[WorkspaceRefusal, Payload] =
    if !workspace.archive.capabilities
        .permitsExport(workspace.modelArtifact, workspace.recallArtifact)
    then Left(WorkspaceRefusal.PermissionDenied)
    else if !selection.forall(workspace.contains) then Left(WorkspaceRefusal.InvalidSelection)
    else
      for
        policy <- workspace.policy(policyId).toRight(WorkspaceRefusal.IncompatiblePolicy)
        sourceRefs = selection.toVector.flatMap(workspace.sourceAddresses.get).sortBy(_.key)
        inverses <- sourceRefs.traverse(workspace.inverse(policyId, _))
        unitIds = selection.flatMap(workspace.recallAddresses.get) ++ inverses.flatten
        rows = policy.matrix.rows.filter(row => unitIds.contains(row.unit.id))
        referenced = sourceRefs ++ rows.flatMap(_.cells.flatMap { cell =>
          cell.destination match
            case Destination.Target(ref)
                if cell.raw.nonEmpty || cell.normalized.nonEmpty || cell.transport.nonEmpty || cell.posterior.nonEmpty || cell.chosen =>
              Vector(ref)
            case _ => Vector.empty
        })
        sourceEvidence <- referenced.distinct
          .sortBy(_.key)
          .traverse(ref =>
            workspace
              .sourceEvidence(ref)
              .map(evidence =>
                obj(
                  "target" -> str(ref.key),
                  "address" -> str(workspace.sourceAddress(ref).get.render),
                  "support" -> (evidence match
                    case Some(value) => obj("status" -> str("located"), "pieces" -> pieces(value))
                    case None        => obj("status" -> str("unlocated")))
                )
              )
          )
        recallEvidence <- rows.traverse(row =>
          workspace
            .recallEvidence(row.unit.id)
            .map(evidence =>
              obj(
                "unit" -> str(row.unit.id.value),
                "address" -> str(workspace.recallAddress(row.unit.id).get.render),
                "pieces" -> pieces(evidence)
              )
            )
        )
        recordJson = MappingCodecs.toJson(policy.record)
        allOutcomes = recordJson.hcursor.get[Vector[Json]]("outcomes").toOption.get
        selectedOutcomes = policy.record.outcomes.zip(allOutcomes).collect {
          case (outcome, json) if unitIds.contains(outcome.unit) => json
        }
        context = Json.fromJsonObject(
          recordJson.asObject.get
            .remove("schema")
            .remove("schemaVersion")
            .remove("record_digest")
            .remove("outcomes")
        )
        archiveText <- WorkspaceArchiveCodec.encode(workspace.archive.manifest)
        archiveDigest = Checksum.ofText(archiveText)
        selectedAddresses = selection.toVector.sortBy(_.render).map(_.render)
        data = MappingJson.print(
          obj(
            "schemaVersion" -> str(SchemaVersion),
            "query" -> str(Query),
            "workspace_archive" -> archiveDigest.asJson,
            "policy" -> str(policyId.value),
            "original_record_digest" -> policy.record.digest.asJson,
            "mapping_schema" -> str(MappingCodecs.Schema),
            "mapping_version" -> str(MappingCodecs.SchemaVersion),
            "selection" -> Json.fromValues(selectedAddresses.map(str)),
            "mapping_context" -> context,
            "outcomes" -> Json.fromValues(selectedOutcomes),
            "recall_evidence" -> Json.fromValues(recallEvidence),
            "source_evidence" -> Json.fromValues(sourceEvidence)
          )
        )
        table = tableFor(workspace, policy, rows)
        twin = textFor(workspace, policy, rows, referenced.distinct.sortBy(_.key), table)
        _ <- Either.cond(utf8(table) && utf8(twin), (), WorkspaceRefusal.UnsupportedContent)
        receipt = MappingJson.print(
          obj(
            "schemaVersion" -> str(ReceiptVersion),
            "query" -> str(Query),
            "workspace_archive" -> archiveDigest.asJson,
            "policy" -> str(policyId.value),
            "original_record_digest" -> policy.record.digest.asJson,
            "selection" -> Json.fromValues(selectedAddresses.map(str)),
            "units" -> Json.fromValues(rows.map(row => str(row.unit.id.value))),
            "data_sha256" -> Checksum.ofText(data).asJson,
            "table_sha256" -> Checksum.ofText(table).asJson,
            "text_sha256" -> Checksum.ofText(twin).asJson
          )
        )
      yield new Payload(data, table, twin, receipt)

  private def tableFor(
      workspace: SourceRecallWorkspace,
      policy: WorkspaceCodecs.Policy,
      rows: Vector[MappingMatrix.Row]
  ): String =
    val header = csv(
      Vector(
        "unit",
        "address",
        "destination",
        "measure",
        "channel",
        "state",
        "value_ieee754",
        "scale",
        "direction",
        "normalization",
        "processing",
        "localization",
        "decision_origin",
        "chosen",
        "candidate_coverage"
      )
    )
    val lines = rows.flatMap { row =>
      def line(destination: String, values: Vector[String], chosen: Boolean): String = csv(
        Vector(
          row.unit.id.value,
          workspace.recallAddress(row.unit.id).get.render,
          destination
        ) ++ values ++
          Vector(
            row.outcome.processing.toString,
            row.outcome.localization.toString,
            row.outcome.decision.fold("none")(_.origin.toString),
            chosen.toString,
            policy.record.policies.candidate.toString
          )
      )
      val missing = Vector("NotSupplied", "", "", "", "", "", "")
      if row.cells.isEmpty then Vector(line("", missing, false))
      else
        row.cells.flatMap { cell =>
          val values = cell.raw.map((raw, value) =>
            Vector(
              "RawScore",
              raw.channel,
              "",
              CanonicalDouble.render(value),
              raw.scale,
              raw.direction.toString,
              "not-normalized"
            )
          ) ++
            cell.normalized.toVector.map(value =>
              Vector(
                "NormalizedScoreMass",
                "",
                "",
                CanonicalDouble.render(value),
                "normalized-score-mass",
                "",
                MappingJson.print(
                  obj(
                    "universe" -> row.outcome.measures.normalized.get.universe.digest.asJson,
                    "prior" -> str(row.outcome.measures.normalized.get.prior.value),
                    "temperature" -> row.outcome.measures.normalized.get.temperature.asJson
                  )
                )
              )
            ) ++
            cell.transport.toVector.map(value =>
              Vector(
                "TransportMass",
                "",
                "",
                CanonicalDouble.render(value),
                "transport-mass",
                "",
                "row-budget:" + CanonicalDouble.render(row.outcome.measures.transport.get.rowBudget)
              )
            ) ++
            cell.posterior.map((state, value) =>
              Vector(
                "ModelPosterior",
                "",
                MappingJson.print(Json.fromValues(AlignState.keyParts(state).map(str))),
                CanonicalDouble.render(value),
                "model-posterior",
                "",
                "original-state-row"
              )
            )
          val supplied =
            if values.isEmpty then Vector(missing) else values
          supplied.map(value => line(cell.destination.key, value, cell.chosen))
        }
    }
    (header +: lines).mkString("\n") + "\n"

  private def textFor(
      workspace: SourceRecallWorkspace,
      policy: WorkspaceCodecs.Policy,
      rows: Vector[MappingMatrix.Row],
      sources: Vector[SourceNodeRef],
      table: String
  ): String =
    val header = Vector(
      "Source and recall investigation",
      "Policy: " + policy.id.value,
      "Origin: " + workspace.origin.toString,
      "Source: " + workspace.draft.promotion.label,
      "Inference: " + policy.record.policies.inference.toString,
      "Candidate coverage: " + policy.record.policies.candidate.toString,
      "Selection query: " + Query,
      "Values retain their original measure, scale and normalization. Missing values are not zero."
    )
    val evidence = rows.flatMap { row =>
      Vector(
        "",
        "Recall " + row.unit.id.value + " (ordinal " + row.unit.ordinal.toString + ")",
        "Processing: " + row.outcome.processing.toString,
        "Localization: " + row.outcome.localization.toString,
        "Timing: " + workspace.timing(row.unit.id).toString
      ) ++
        workspace
          .recallEvidence(row.unit.id)
          .toOption
          .get
          .map((ref, text) => s"Exact recall [${ref.span.start},${ref.span.endExclusive}): $text")
    }
    val sourceEvidence = sources.flatMap(ref =>
      workspace.sourceEvidence(ref).toOption.flatten match
        case Some(pieces) =>
          pieces.map((span, text) =>
            s"Exact source ${ref.key} [${span.span.start},${span.span.endExclusive}): $text"
          )
        case None => Vector(s"Source ${ref.key}: no supplied physical support")
    )
    (header ++ evidence ++ sourceEvidence ++ Vector(
      "",
      "Complete fixed-cut values (CSV; IEEE-754 value encoding):",
      table
    )).mkString("\n")
