package storymodel4s.codec

import cats.syntax.all.*
import io.circe.Json
import storymodel4s.align.*
import MappingJson.*

/** Canonical, contextual interchange for one complete checked mapping. */
object MappingCodecs:
  val Schema: String = "storymodel4s.mapping-record"
  val SchemaVersion: String = "mapping-record/v0.1"
  def encode(value: MappingResult): String = print(MappingRecordWire.encode(value))
  def toJson(value: MappingResult): Json = MappingRecordWire.encode(value)

  /** Authority-bearing values are re-derived from the supplied context, then compared exactly. */
  def decode(
      text: String,
      context: ExpectedMappingContext
  ): Either[MappingCodecError, MappingResult] =
    for
      json <- Canonical.parse(text).left.map(MappingCodecError.Wire(_))
      version <- field[String](json, "schemaVersion")
      _ <- Either.cond(
        version == SchemaVersion,
        (),
        MappingCodecError.Wire(CodecError.UnsupportedSchema(version, Vector(SchemaVersion)))
      )
      schema <- field[String](json, "schema")
      _ <- Either.cond(schema == Schema, (), MappingCodecError.ValueMismatch("schema"))
      _ <- uniqueObjectKeys(text)
      _ <- reserved(json)
      inventory <- field[Json](json, "inventory")
      inventoryDigest <- field[String](inventory, "digest")
      _ <- Either.cond(
        inventoryDigest == context.inventory.digest.hex,
        (),
        MappingCodecError.DigestMismatch("inventory")
      )
      _ <- exact(inventory, MappingSourceWire.inventory(context.inventory), "inventory")
      source <- field[Json](json, "source")
      _ <- MappingSourceWire.exactCoordinates(source)
      sourceDigest <- field[String](source, "digest")
      _ <- Either.cond(
        sourceDigest == context.source.digest.hex,
        (),
        MappingCodecError.DigestMismatch("source")
      )
      _ <- MappingSourceWire.validatePhysical(source, context.source)
      _ <- exact(source, MappingSourceWire.source(context.source), "source")
      derivation <- field[Json](json, "derivation_source").flatMap(v => derivation(v, context))
      policies <- field[Json](json, "policies").flatMap(MappingMetadataRead.policies)
      roles <- field[Json](json, "roles").flatMap(v =>
        MappingMetadataRead.roles(v, context.inventory)
      )
      ledger <- field[Json](json, "stage_assumption_receipts").flatMap(MappingMetadataRead.ledger)
      outcomes <- field[Vector[Json]](json, "outcomes").flatMap(
        _.traverse(v => MappingOutcomeRead.outcome(v, ledger, policies, context.source, derivation))
      )
      result <- checked(
        MappingResult.checked(context.inventory, context.source, policies, roles, ledger, outcomes)
      )
      digest <- field[String](json, "record_digest")
      _ <- Either.cond(digest == result.digest.hex, (), MappingCodecError.DigestMismatch("record"))
      _ <- Either.cond(
        print(json) == encode(result),
        (),
        MappingCodecError.ValueMismatch("canonical-record")
      )
    yield result

  private def derivation(
      value: Json,
      context: ExpectedMappingContext
  ): Result[Option[MappingBoundContext]] =
    status(value).flatMap {
      case "none"  => Right(None)
      case "bound" =>
        for
          inputs <- context.derivation.toRight(
            MappingCodecError.ContextRequired("derivation_source")
          )
          captured = inputs.copy(view = capture(inputs.view))
          binding <- checked(
            DerivationBinding.of(
              captured.result,
              captured.recall,
              context.inventory,
              captured.view,
              context.source
            )
          )
          wire <- field[Json](value, "binding")
          _ <- MappingMetadataRead.matchedBinding(wire, binding)
        yield Some(MappingBoundContext(binding, captured))
      case other => invalid("derivation_source", s"unknown status $other")
    }
  private def reserved(value: Json): Result[Unit] = value.asObject match
    case Some(fields) =>
      fields.toVector.traverse_ { (key, child) =>
        if key == "measurement_compatibility" then Left(MappingCodecError.Reserved(key))
        else if key == "status" && child.asString.contains("calibrated") then
          Left(MappingCodecError.Reserved("calibrated"))
        else if key == "measure_kind" && child.asString.contains("CalibratedProbability") then
          Left(MappingCodecError.Reserved("calibrated"))
        else if key == "localization_status" && child.asString.exists(s =>
            Set("partial", "ambiguous", "manual-review")(s.toLowerCase)
          )
        then Left(MappingCodecError.Reserved(child.asString.get))
        else reserved(child)
      }
    case None => value.asArray.toVector.flatten.traverse_(reserved)
