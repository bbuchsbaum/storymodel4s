package storymodel4s.codec

import java.nio.charset.StandardCharsets

import cats.data.NonEmptyVector
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import storymodel4s.acquire.MediaTypeId
import storymodel4s.align.*
import storymodel4s.align.bridge.StorySourceView
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.story.*
import storymodel4s.view.*

import CanonicalPrimitives.given
import CoreCodecs.given
import OutputCodecs.given
import RecallCodecs.given

enum WorkspaceOrigin:
  case AuthoredFixture, ImportedArtifact

/** Unit-clock annotations are explicitly supplied, never inferred from ordinal or text length. */
enum WorkspaceTiming:
  case Untimed
  case Onset(at: Seconds)
  case Interval(span: ClockSpan)

/** Unchecked packaging input. The canonical decoder rechecks every member against actual inputs. */
final case class WorkspaceMappingInput(
    id: ArtifactId,
    record: MappingResult,
    result: Option[HsmmResult]
)

type SourceRecallWorkspace = WorkspaceCodecs.Investigation

/** Checked text-source workspace interchange. Byte/permission admission precedes semantic decoding.
  * A structural validation success permits the existing source bridge; it does not certify a
  * complete interpretation, erase derivation gaps or upgrade scientific authority.
  */
object WorkspaceCodecs:
  val InventoryVersion = "workspace-inventory/v0.1"
  val DerivationVersion = "workspace-derivation/v0.1"
  val ReceiptVersion = "workspace-receipt/v0.1"
  private type Result[A] = Either[WorkspaceRefusal, A]
  private def admitted[E, A](value: Either[E, A]): Result[A] =
    value.left.map(_ => WorkspaceRefusal.SemanticJoinMismatch)
  private def obj(fields: (String, Json)*): Json = Json.obj(fields*)
  private def str(value: String): Json = Json.fromString(value)
  private def field[A: Decoder](json: Json, name: String): Result[A] =
    json.hcursor.get[A](name).left.map(_ => WorkspaceRefusal.UnsupportedContent)
  private def parse(text: String): Result[Json] =
    for
      json <- admitted(Canonical.parse(text))
      _ <- admitted(MappingJson.uniqueObjectKeys(text))
    yield json
  private def exact(actual: Json, expected: Json): Result[Unit] =
    Either.cond(
      MappingJson.print(actual) == MappingJson.print(expected),
      (),
      WorkspaceRefusal.SemanticJoinMismatch
    )
  private def version(json: Json, expected: String): Result[Unit] =
    field[String](json, "schemaVersion").flatMap(v =>
      Either.cond(v == expected, (), WorkspaceRefusal.UnsupportedVersion)
    )
  private def text(archive: WorkspaceArchive, role: WorkspaceRole): Result[String] =
    archive.manifest
      .bytes(role)
      .toRight(WorkspaceRefusal.MissingRequiredRole)
      .flatMap(WorkspaceArchiveCodec.utf8)
  private def canonical[A](
      input: String,
      decode: String => Result[A],
      encode: A => String
  ): Result[A] =
    for
      json <- parse(input)
      value <- decode(input)
      expected <- parse(encode(value))
      _ <- exact(json, expected)
    yield value

  final class Policy private[WorkspaceCodecs] (val id: ArtifactId, val record: MappingResult):
    lazy val matrix: MappingMatrix = MappingMatrix.from(record)

  /** Construction is owned by this decoder. No package-private alternate constructor or copy. */
  final class Investigation private[WorkspaceCodecs] (
      val archive: WorkspaceArchive,
      val draft: DraftModel,
      val model: TextModel[ModelStatus.Validated],
      val recall: RecallGraph[Checked],
      val inventory: RecallInventory,
      val source: SourceRepresentation,
      val policies: Vector[Policy],
      val timing: Map[RecallUnitId, WorkspaceTiming],
      val origin: WorkspaceOrigin,
      val producerRevision: String
  ):
    val modelArtifact: Checksum = archive.manifest.artifact(WorkspaceRole.SourceModel).get.checksum
    val recallArtifact: Checksum = archive.manifest.artifact(WorkspaceRole.Recall).get.checksum
    def policy(id: ArtifactId): Option[Policy] = policies.find(_.id == id)
    private def address(kind: String, local: String): Address = Address(
      ModuleTag.unsafe("workspace"),
      AddressKind.unsafe(kind),
      AddressKey.of(
        modelArtifact.hex,
        recallArtifact.hex,
        local.iterator.map(c => f"${c.toInt}%04x").mkString
      )
    )
    def recallAddress(id: RecallUnitId): Option[Address] =
      recall.unit(id).map(_ => address("recall-unit", id.value))
    def sourceAddress(ref: SourceNodeRef): Option[Address] = source
      .target(ref)
      .map(_ =>
        ref match
          case SourceNodeRef.Situation(id) => address("source-situation", id.value)
          case SourceNodeRef.Segment(id)   => address("source-segment", id.value)
      )
    lazy val recallAddresses: Map[Address, RecallUnitId] =
      inventory.units.map(u => recallAddress(u.id).get -> u.id).toMap
    lazy val sourceAddresses: Map[Address, SourceNodeRef] =
      source.targets.map(t => sourceAddress(t.ref).get -> t.ref).toMap
    def contains(address: Address): Boolean =
      recallAddresses.contains(address) || sourceAddresses.contains(address)
    private def slices(spans: SpanSet, content: String): Vector[(SpanRef, String)] =
      spans.refs.toVector.map(ref => ref -> ref.span.slice(content).toOption.get)
    def recallEvidence(id: RecallUnitId): Result[Vector[(SpanRef, String)]] =
      recall
        .unit(id)
        .toRight(WorkspaceRefusal.InvalidSelection)
        .map(u => slices(u.span, recall.transcript.canonicalText))
    def sourceEvidence(ref: SourceNodeRef): Result[Option[Vector[(SpanRef, String)]]] =
      source
        .target(ref)
        .toRight(WorkspaceRefusal.InvalidSelection)
        .map(_.sourceSupport match
          case located: SourceSupportStatus.Located =>
            located.support match
              case TypedSupport.Text(spans) => Some(slices(spans, model.source.canonicalText))
              case _                        => None
          case _: SourceSupportStatus.Unlocated => None)

    /** Every supplied reference and decoded choice, in recall order; repeated references survive.
      */
    def inverse(policyId: ArtifactId, ref: SourceNodeRef): Result[Vector[RecallUnitId]] =
      for
        selected <- policy(policyId).toRight(WorkspaceRefusal.IncompatiblePolicy)
        _ <- source.target(ref).toRight(WorkspaceRefusal.InvalidSelection)
        destination = Destination.Target(ref)
      yield selected.record.outcomes
        .filter(o =>
          o.measures.destinations.contains(destination) || o.links
            .exists(_.destination == destination) ||
            o.decision.flatMap(_.chosen).contains(destination)
        )
        .map(_.unit)

  /** The same text bridge and physical dictionary are used by producer and consumer. */
  def sourceFor(
      model: TextModel[ModelStatus.Validated]
  ): Result[(StorySourceView, SourceRepresentation)] =
    val view = StorySourceView.validated(model)
    admitted(
      SourceRepresentation.of(
        view,
        NonEmptyVector.one(BundleEntry.text(model.source.canonicalChecksum)),
        None,
        view.nodes.map(n => n.ref -> SourceSupportStatus.located(n.support)).toMap
      )
    ).map(view -> _)

  private def timingJson(value: WorkspaceTiming): Json = value match
    case WorkspaceTiming.Untimed        => obj("status" -> str("untimed"))
    case WorkspaceTiming.Onset(at)      => obj("status" -> str("onset"), "at" -> at.value.asJson)
    case WorkspaceTiming.Interval(span) =>
      obj(
        "status" -> str("interval"),
        "start" -> span.start.value.asJson,
        "end" -> span.end.value.asJson
      )
  private def readTiming(json: Json): Result[WorkspaceTiming] =
    field[String](json, "status").flatMap {
      case "untimed" => Right(WorkspaceTiming.Untimed)
      case "onset"   =>
        field[Double](json, "at")
          .flatMap(v => admitted(Seconds.of(v)))
          .map(WorkspaceTiming.Onset(_))
      case "interval" =>
        for
          start <- field[Double](json, "start")
          end <- field[Double](json, "end")
          span <- admitted(ClockSpan.of(start, end))
        yield WorkspaceTiming.Interval(span)
      case _ => Left(WorkspaceRefusal.UnsupportedContent)
    }
  private def inventoryJson(
      inventory: RecallInventory,
      timing: Map[RecallUnitId, WorkspaceTiming]
  ): Json = obj(
    "schemaVersion" -> str(InventoryVersion),
    "digest" -> inventory.digest.asJson,
    "inputArtifact" -> inventory.idPolicy.artifact.asJson,
    "words" -> Json.fromValues(inventory.words.map(w => w.span.asJson)),
    "timing" -> Json.fromValues(
      inventory.units.map(u => obj("unit" -> u.id.asJson, "clock" -> timingJson(timing(u.id))))
    )
  )
  private def readInventory(
      json: Json,
      recall: RecallGraph[Checked]
  ): Result[(RecallInventory, Map[RecallUnitId, WorkspaceTiming])] =
    for
      _ <- version(json, InventoryVersion)
      input <- field[Checksum](json, "inputArtifact")
      words <- field[Vector[TextSpan]](json, "words")
      inventory <- admitted(RecallInventory.of(recall, words, WordIdPolicy.inputArtifact(input)))
      clocks <- field[Vector[Json]](json, "timing").flatMap(
        _.traverse(v =>
          for
            unit <- field[RecallUnitId](v, "unit")
            clock <- field[Json](v, "clock").flatMap(readTiming)
          yield unit -> clock
        )
      )
      _ <- Either.cond(
        clocks.map(_._1) == inventory.units.map(_.id),
        (),
        WorkspaceRefusal.SemanticJoinMismatch
      )
      timing = clocks.toMap
      _ <- exact(json, inventoryJson(inventory, timing))
    yield inventory -> timing

  private def optionalText(value: Option[String]): Json = MappingJson.optional(value)(str)
  private def readOptionalText(json: Json): Result[Option[String]] = admitted(
    MappingJson.readOptional(json)(MappingJson.read[String])
  )
  private def derivationJson(
      compilation: Option[String],
      results: Vector[(ArtifactId, Option[String])]
  ): Json = obj(
    "schemaVersion" -> str(DerivationVersion),
    "compilation" -> optionalText(compilation),
    "mappings" -> Json.fromValues(
      results
        .sortBy(_._1.value)
        .map((id, result) => obj("id" -> id.asJson, "hsmm" -> optionalText(result)))
    )
  )
  private def readDerivation(
      json: Json,
      model: TextModel[ModelStatus.Draft],
      recall: RecallGraph[Checked],
      view: SourceView,
      mappingIds: Vector[ArtifactId]
  ): Result[(DerivationRecord, Map[ArtifactId, Option[HsmmResult]])] =
    for
      _ <- version(json, DerivationVersion)
      compilationText <- field[Json](json, "compilation").flatMap(readOptionalText)
      compilation <- compilationText.traverse(t =>
        canonical(
          t,
          v => admitted(DerivationRecordCodec.decode(model, v)),
          DerivationRecordCodec.encode
        )
      )
      resultTexts <- field[Vector[Json]](json, "mappings").flatMap(
        _.traverse(v =>
          for
            id <- field[ArtifactId](v, "id")
            result <- field[Json](v, "hsmm").flatMap(readOptionalText)
          yield id -> result
        )
      )
      _ <- Either.cond(
        resultTexts.map(_._1) == mappingIds.sortBy(_.value),
        (),
        WorkspaceRefusal.SemanticJoinMismatch
      )
      results <- resultTexts.traverse((id, input) =>
        input
          .traverse(t =>
            for
              parsed <- parse(t)
              result <- admitted(HsmmResultCodec.decode(t, recall, view))
              encoded <- admitted(HsmmResultCodec.encode(result))
              expected <- parse(encoded)
              _ <- exact(parsed, expected)
            yield result
          )
          .map(id -> _)
      )
      _ <- exact(json, derivationJson(compilation.map(DerivationRecordCodec.encode), resultTexts))
    yield compilation.fold[DerivationRecord](DerivationRecord.NotSupplied)(
      _.record
    ) -> results.toMap

  private def receiptJson(
      origin: WorkspaceOrigin,
      revision: String,
      entries: Vector[WorkspaceEntry]
  ): Json = obj(
    "schemaVersion" -> str(ReceiptVersion),
    "origin" -> str(origin.toString),
    "producerRevision" -> str(revision),
    "members" -> Json.fromValues(
      entries
        .filterNot(e => e.role == WorkspaceRole.Capabilities || e.role == WorkspaceRole.Receipt)
        .sortBy(_.role.key)
        .map(e =>
          obj(
            "role" -> str(e.role.key),
            "path" -> e.path.asJson,
            "content" -> (e.disposition match
              case WorkspaceDisposition.Supplied(ref) =>
                obj("status" -> str("Supplied"), "artifact" -> ref.asJson)
              case other => obj("status" -> str(other.toString)))
          )
        )
    )
  )
  private def readReceipt(
      json: Json,
      entries: Vector[WorkspaceEntry]
  ): Result[(WorkspaceOrigin, String)] =
    for
      _ <- version(json, ReceiptVersion)
      label <- field[String](json, "origin")
      origin <- WorkspaceOrigin.values
        .find(_.toString == label)
        .toRight(WorkspaceRefusal.UnsupportedContent)
      revision <- field[String](json, "producerRevision")
      _ <- Either.cond(revision.matches("[0-9a-f]{40}"), (), WorkspaceRefusal.UnsupportedContent)
      _ <- exact(json, receiptJson(origin, revision, entries))
    yield origin -> revision

  private def checkFeatures(
      archive: WorkspaceArchive,
      model: TextModel[ModelStatus.Draft]
  ): Result[Unit] =
    val entries = archive.manifest.entries.collect {
      case e @ WorkspaceEntry(WorkspaceRole.Features(_), _, _) => e
    }
    val expected = model.sidecars.keys.map(_.value).toSet
    val actual = entries.collect { case WorkspaceEntry(WorkspaceRole.Features(id), _, _) =>
      id.value
    }.toSet
    if actual != expected then Left(WorkspaceRefusal.SemanticJoinMismatch)
    else
      entries.traverse_ { e =>
        e match
          case WorkspaceEntry(WorkspaceRole.Features(id), _, WorkspaceDisposition.Supplied(ref)) =>
            val sidecar = model.sidecars.find(_._1.value == id.value).get._2
            Either.cond(
              sidecar.checksum == ref.checksum && sidecar.expectedByteLength == Right(
                ref.byteLength
              ),
              (),
              WorkspaceRefusal.SemanticJoinMismatch
            )
          case _ => Right(())
      }

  def decode(input: String): Result[SourceRecallWorkspace] =
    for
      archive <- WorkspaceArchiveCodec.decode(input)
      modelText <- text(archive, WorkspaceRole.SourceModel)
      model <- canonical(
        modelText,
        t => admitted(StoryModelCodec.decode(t)),
        m => StoryModelCodec.encode(m)
      )
      validation = StoryValidator.validate(model)
      validated <- validation.validated.toRight(WorkspaceRefusal.UnsupportedContent)
      sourcePair <- sourceFor(validated)
      (view, source) = sourcePair
      recallText <- text(archive, WorkspaceRole.Recall)
      recall <- canonical[RecallGraph[Checked]](
        recallText,
        t => admitted(Canonical.decode[RecallGraph[Checked]](t)),
        Canonical.encode(_)
      )
      inventoryText <- text(archive, WorkspaceRole.Inventory)
      inventoryJson <- parse(inventoryText)
      inventoryPair <- readInventory(inventoryJson, recall)
      (inventory, timing) = inventoryPair
      derivationText <- text(archive, WorkspaceRole.Derivation)
      derivationJson <- parse(derivationText)
      ids = archive.manifest.mappings.map(_.id)
      derivationPair <- readDerivation(derivationJson, model, recall, view, ids)
      (derivation, results) = derivationPair
      policies <- ids.traverse { id =>
        for
          mappingText <- text(archive, WorkspaceRole.Mapping(id))
          record <- admitted(
            MappingCodecs.decode(
              mappingText,
              ExpectedMappingContext(
                inventory,
                source,
                results(id).map(DerivationContext(_, recall, view))
              )
            )
          )
          _ <- Either.cond(
            (record.derivation != DerivationSource.NoDerivedValues) == results(id).isDefined,
            (),
            WorkspaceRefusal.SemanticJoinMismatch
          )
        yield new Policy(id, record)
      }
      _ <- Either.cond(
        policies.map(_.record.policies.universe.id).distinct.size == 1,
        (),
        WorkspaceRefusal.IncompatiblePolicy
      )
      receiptText <- text(archive, WorkspaceRole.Receipt)
      receiptJson <- parse(receiptText)
      receipt <- readReceipt(receiptJson, archive.manifest.entries)
      _ <- checkFeatures(archive, model)
    yield new Investigation(
      archive,
      DraftModel.of(model, validation, derivation),
      validated,
      recall,
      inventory,
      source,
      policies,
      timing,
      receipt._1,
      receipt._2
    )

  /** Produce a transferable fixture/import packet, then pass it through the same admission path
    * used by consumers. Both grants are mandatory arguments. No estimator is invoked here.
    */
  def create(
      model: TextModel[ModelStatus.Draft],
      recall: RecallGraph[Checked],
      inventory: RecallInventory,
      compilation: Option[DerivationArtifact],
      mappings: Vector[WorkspaceMappingInput],
      timing: Map[RecallUnitId, WorkspaceTiming],
      origin: WorkspaceOrigin,
      producerRevision: String,
      inspection: WorkspaceContentGrant,
      exportPermission: WorkspaceContentGrant
  ): Result[SourceRecallWorkspace] =
    if inspection != WorkspaceContentGrant.Granted || exportPermission != WorkspaceContentGrant.Granted
    then Left(WorkspaceRefusal.PermissionDenied)
    else if mappings.isEmpty || mappings
        .map(_.id)
        .distinct
        .size != mappings.size || timing.keySet != inventory.units.map(_.id).toSet
    then Left(WorkspaceRefusal.SemanticJoinMismatch)
    else
      for
        results <- mappings.traverse(m =>
          m.result.traverse(r => admitted(HsmmResultCodec.encode(r))).map(m.id -> _)
        )
        modelJson <- parse(StoryModelCodec.encode(model))
        recallJson <- parse(Canonical.encode(recall))
        contents = Vector(
          WorkspaceRole.SourceModel -> MappingJson.print(modelJson),
          WorkspaceRole.Recall -> MappingJson.print(recallJson),
          WorkspaceRole.Inventory -> MappingJson.print(inventoryJson(inventory, timing)),
          WorkspaceRole.Derivation -> MappingJson.print(
            derivationJson(compilation.map(DerivationRecordCodec.encode), results)
          )
        ) ++ mappings.map(m => WorkspaceRole.Mapping(m.id) -> MappingCodecs.encode(m.record))
        base = contents.sortBy(_._1.key).zipWithIndex.map { case ((role, content), i) =>
          member(role, s"member-$i.json", content)
        }
        features = model.sidecars.keys.toVector.sortBy(_.value).zipWithIndex.map { (space, i) =>
          WorkspaceEntry(
            WorkspaceRole.Features(ArtifactId.unsafe(space.value)),
            BundlePath.unsafe(s"feature-$i.json"),
            WorkspaceDisposition.Absent
          )
        }
        receiptText = MappingJson.print(
          receiptJson(origin, producerRevision, base.map(_._1) ++ features)
        )
        receipt = member(WorkspaceRole.Receipt, "receipt.json", receiptText)
        capabilities = WorkspaceCapabilities(
          Checksum.ofText(contents.find(_._1 == WorkspaceRole.SourceModel).get._2),
          Checksum.ofText(contents.find(_._1 == WorkspaceRole.Recall).get._2),
          inspection,
          exportPermission,
          Checksum.ofText(receiptText)
        )
        capability = member(
          WorkspaceRole.Capabilities,
          "capabilities.json",
          WorkspaceArchiveCodec.encodeCapabilities(capabilities)
        )
        supplied = base ++ Vector(receipt, capability)
        manifest <- WorkspaceManifest.of(
          WorkspaceManifest.SchemaVersion,
          supplied.map(_._1) ++ features,
          supplied.map((e, b) => e.path -> b).toMap
        )
        encoded <- WorkspaceArchiveCodec.encode(manifest)
        workspace <- decode(encoded)
      yield workspace

  private def member(
      role: WorkspaceRole,
      path: String,
      content: String
  ): (WorkspaceEntry, Vector[Byte]) =
    val bytes = content.getBytes(StandardCharsets.UTF_8).toVector
    val ref = ArtifactRef.fromBytes(
      ArtifactId.unsafe(path),
      role.artifactRole,
      MediaTypeId.unsafe("application/json"),
      None,
      bytes.toArray
    )
    WorkspaceEntry(role, BundlePath.unsafe(path), WorkspaceDisposition.Supplied(ref)) -> bytes
