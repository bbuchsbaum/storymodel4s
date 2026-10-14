package storymodel4s.codec

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.view.{ArtifactId, WorkspaceRefusal}
import HsmmResultCodec.given

/** Imported workspace quantities can be queried without acquiring mapping execution authority. */
object TemporalInspectionCodecs:
  val SchemaVersion: String = "temporal-workspace/v1"
  val ViewVersion: String = "temporal-inspection-view/v1"

  enum Error:
    case Invalid, ForeignPolicy, ForeignTiming, ForeignView, MissingUnit, PermissionDenied
    case ExposureUnavailable, NonFiniteExposureQuantity
    case Workspace(reason: WorkspaceRefusal)
    case Query(reason: TemporalQuery.Refusal)
    case Grid(reason: TemporalInspection.Refusal)
    case Exposure(reason: TemporalExposure.Refusal)

  private type Result[A] = Either[Error, A]
  private def field[A: Decoder](value: Json, name: String): Result[A] =
    value.hcursor.get[A](name).left.map(_ => Error.Invalid)
  private def check(ok: Boolean): Result[Unit] = Either.cond(ok, (), Error.Invalid)
  private def parse(text: String): Result[Json] = for
    _ <- MappingJson.uniqueObjectKeys(text).left.map(_ => Error.Invalid)
    json <- Canonical.parse(text).left.map(_ => Error.Invalid)
  yield json
  private def hash(parts: Vector[String]): Checksum =
    Checksum.ofText(Canonical.print(Json.fromValues(parts.map(Json.fromString))))

  /** The optional original timing is joined to the exact inventory, never inferred from order. */
  final class Session private[TemporalInspectionCodecs] (
      val workspace: SurfaceWorkspaceCodecs.Investigation,
      val timing: Option[RecallTiming],
      val digest: Checksum
  ):
    private def address(kind: String, local: String): Address = Address(
      ModuleTag.unsafe("temporal-workspace"),
      AddressKind.unsafe(kind),
      AddressKey.of(digest.hex, local.iterator.map(c => f"${c.toInt}%04x").mkString)
    )

    /** Semantic selections bind the admitted packet and timing, independently of renderer marks. */
    def recallAddress(unit: RecallUnitId): Option[Address] =
      workspace.inventory.units.find(_.id == unit).map(_ => address("recall-unit", unit.value))
    def sourceAddress(target: SourceNodeRef): Option[Address] =
      workspace.source.target(target).map(_ => address("source-target", target.key))
    lazy val recallAddresses: Map[Address, RecallUnitId] =
      workspace.inventory.units.map(u => recallAddress(u.id).get -> u.id).toMap
    lazy val sourceAddresses: Map[Address, SourceNodeRef] =
      workspace.source.targets.map(t => sourceAddress(t.ref).get -> t.ref).toMap
    def contains(value: Address): Boolean =
      recallAddresses.contains(value) || sourceAddresses.contains(value)

    def compile(
        policy: ArtifactId,
        measure: TemporalQuery.Measure,
        request: TemporalProjection.Request
    ): Result[Projection] =
      TemporalInspectionCodecs.compile(this, policy, measure, request)
    override def equals(other: Any): Boolean = other match
      case that: Session => digest == that.digest
      case _             => false
    override def hashCode(): Int = digest.hashCode
    override def toString: String = s"TemporalInspection.Session(${digest.short()})"

  /** Failed, excluded and unmeasured rows retain distinct refusals and original claim bytes. */
  final class Row private[TemporalInspectionCodecs] (
      val unit: RecallUnitId,
      val processing: ProcessingStatus,
      val claims: Json,
      val grid: Either[Error, TemporalInspection],
      val exposure: Option[TemporalExposure.UnitReadout]
  ):
    /** Units are original assignment quantity times supplied recall seconds. */
    def durationWeighted: Result[Map[Int, Double]] = for
      source <- grid
      supplied <- exposure.toRight(Error.ExposureUnavailable)
      values = source.allocated.map((bin, quantity) => bin -> (quantity * supplied.durationSeconds))
      _ <- Either.cond(values.values.forall(_.isFinite), (), Error.NonFiniteExposureQuantity)
    yield values.filter(_._2 != 0.0)
    def durationWeightedBounds: Result[Map[Int, (Double, Double)]] = for
      source <- grid
      supplied <- exposure.toRight(Error.ExposureUnavailable)
      values = source.bounds.map((bin, b) =>
        bin ->
          (b._1 * supplied.durationSeconds, b._2 * supplied.durationSeconds)
      )
      _ <- Either.cond(
        values.values.forall((a, b) => a.isFinite && b.isFinite),
        (),
        Error.NonFiniteExposureQuantity
      )
    yield values.filter(_._2._2 != 0.0)
    private def fields = (unit, processing, claims, grid, exposure)
    override def equals(other: Any): Boolean = other match
      case that: Row => fields == that.fields
      case _         => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String = s"TemporalInspection.Row($unit,$processing)"

  /** Identity includes actual archive and policy bytes, source, inventory, timing and request. */
  final class Projection private[TemporalInspectionCodecs] (
      val session: Session,
      val policy: SurfaceWorkspaceCodecs.Policy,
      val measure: TemporalQuery.Measure,
      val request: TemporalProjection.Request,
      val coordinate: TemporalSupport.Coordinate,
      val regions: Vector[TemporalQuery.Region],
      val rows: Vector[Row],
      val exposure: Option[TemporalExposure.InspectionReadout],
      val timingState: TemporalProjection.TimingState,
      val digest: Checksum
  ):
    val clockUncertainty: TemporalProjection.ClockUncertainty =
      TemporalProjection.ClockUncertainty.NotModeled
    val jointPath: TemporalConcentration.JointStatus = TemporalConcentration.jointStatus
    def query(
        unit: RecallUnitId,
        region: TemporalQuery.Region
    ): Result[TemporalQuery.InspectionReadout] =
      for
        row <- rows.find(_.unit == unit).toRight(Error.MissingUnit)
        grid <- row.grid
        readout <- grid.prepared.query(region).left.map(Error.Query(_))
      yield readout
    override def equals(other: Any): Boolean = other match
      case that: Projection => digest == that.digest
      case _                => false
    override def hashCode(): Int = digest.hashCode
    override def toString: String = s"TemporalInspection.Projection(${digest.short()},${rows.size})"

  def checked(
      workspace: SurfaceWorkspaceCodecs.Investigation,
      timing: Option[RecallTiming]
  ): Result[Session] =
    if timing.exists(_.inventory.digest != workspace.inventory.digest) then
      Left(Error.ForeignTiming)
    else
      Right(
        new Session(
          workspace,
          timing,
          hash(
            Vector(
              SchemaVersion,
              workspace.identity.hex,
              workspace.source.digest.hex,
              workspace.inventory.digest.hex
            ) ++ workspace.policies.map(_.byteDigest.hex) ++
              timing.toVector.map(t => Checksum.ofText(RecallTimingCodecs.encode(t)).hex)
          )
        )
      )

  /** Export uses the original workspace grant; attaching timing cannot widen that permission. */
  def encode(session: Session): Result[String] =
    session.workspace.exportBytes.left.map(_ => Error.PermissionDenied).map { workspace =>
      Canonical.print(
        Json.obj(
          "schemaVersion" -> Json.fromString(SchemaVersion),
          "workspace" -> Json.fromString(workspace),
          "recallTiming" -> session.timing
            .fold(Json.Null)(t => Json.fromString(RecallTimingCodecs.encode(t))),
          "digest" -> Json.fromString(session.digest.hex)
        )
      )
    }

  def decode(text: String): Result[Session] = for
    json <- parse(text)
    schema <- field[String](json, "schemaVersion")
    _ <- check(schema == SchemaVersion)
    bytes <- field[String](json, "workspace")
    workspace <- SurfaceWorkspaceCodecs.decode(bytes).left.map(Error.Workspace(_))
    timingBytes <- field[Option[String]](json, "recallTiming")
    timing <- timingBytes.traverse(t =>
      RecallTimingCodecs.decode(t, workspace.inventory).left.map(_ => Error.ForeignTiming)
    )
    result <- checked(workspace, timing)
    digest <- field[String](json, "digest")
    _ <- Either.cond(digest == result.digest.hex, (), Error.ForeignView)
    _ <- check(
      json.asObject.exists(
        _.keys.toSet == Set("schemaVersion", "workspace", "recallTiming", "digest")
      )
    )
  yield result

  private def processing(json: Json): Result[ProcessingStatus] = for
    p <- field[Json](json, "processing_status")
    status <- field[String](p, "status")
    result <- status match
      case "complete" => Right(ProcessingStatus.Complete)
      case "excluded" => field[String](p, "reason").map(ProcessingStatus.ExcludedByInputPolicy(_))
      case "failed"   =>
        for
          f <- field[Json](p, "failure")
          kind <- field[String](f, "status")
          detail <- field[String](f, "detail")
          failure <- kind match
            case "provider-failure"  => Right(ProcessingFailure.ProviderFailure(detail))
            case "invalid-output"    => Right(ProcessingFailure.InvalidOutput(detail))
            case "inference-refused" => Right(ProcessingFailure.InferenceRefused(detail))
            case _                   => Left(Error.Invalid)
        yield ProcessingStatus.Failed(failure)
      case _ => Left(Error.Invalid)
  yield result

  private def entries(
      outcome: Json,
      measure: TemporalQuery.Measure
  ): Result[Vector[(TemporalQuery.Alternative, Double)]] =
    field[Vector[Json]](outcome, "mapping_links").flatMap { rows =>
      rows.filter(_.hcursor.get[String]("measure_kind").contains(measure.toString)).traverse {
        row =>
          for
            raw <- field[String](row, "raw_value")
            weight <- CanonicalPrimitives
              .parseHexDouble(raw)
              .filter(_.isFinite)
              .toRight(Error.Invalid)
            alternative <- measure match
              case TemporalQuery.Measure.ModelPosterior =>
                for
                  state <- field[Json](row, "state")
                  value <- field[Json](state, "value")
                  decoded <- Canonical.decodeJson[AlignState](value).left.map(_ => Error.Invalid)
                yield TemporalQuery.Alternative.Posterior(decoded)
              case other =>
                field[String](row, "destination")
                  .flatMap(MappingJson.destination(_).left.map(_ => Error.Invalid))
                  .map(d =>
                    if other == TemporalQuery.Measure.TransportMass then
                      TemporalQuery.Alternative.Transport(d)
                    else TemporalQuery.Alternative.Score(d)
                  )
          yield alternative -> weight
      }
    }

  private def validWindow(request: TemporalProjection.Request): Boolean =
    request.recallWindow.forall { w =>
      def canonical(r: ExactRational): Boolean =
        r.denominator > 0 && BigInt(r.numerator).gcd(BigInt(r.denominator)) == 1
      canonical(w.start) && canonical(w.endExclusive) &&
      BigInt(w.start.numerator) * w.endExclusive.denominator <
        BigInt(w.endExclusive.numerator) * w.start.denominator
    }

  private def compile(
      session: Session,
      id: ArtifactId,
      measure: TemporalQuery.Measure,
      request: TemporalProjection.Request
  ): Result[Projection] =
    val workspace = session.workspace
    for
      policy <- workspace.policy(id).toRight(Error.ForeignPolicy)
      _ <- check(
        validWindow(request) && request.bins.size <= TemporalInspection.MaximumBins &&
          request.bins.forall((a, b) => a < b) &&
          !request.bins.sortBy(_._1).sliding(2).exists {
            case Vector((_, end), (start, _)) => end > start
            case _                            => false
          } && request.declarations.map(_.target).distinct.size == request.declarations.size
      )
      _ <- request.declarations.traverse_(d =>
        check(
          workspace.source.target(d.target).nonEmpty &&
            (d.allocation match
              case TemporalQuery.Allocation.Mixture(s) => s.isFinite && s >= 0.0 && s <= 1.0
              case _                                   => true)
        )
      )
      coordinate <- TemporalSupport
        .select(workspace.source, request.selection)
        .left
        .map(_ => Error.Invalid)
      regions <- request.bins.traverse((a, b) =>
        TemporalQuery.Region
          .onTimeAxis(coordinate.axis, Vector(a -> b), Vector.empty)
          .left
          .map(Error.Query(_))
      )
      statuses <- policy.outcomes.traverse(o =>
        for
          id <- field[String](o, "unit").flatMap(RecallUnitId.from(_).left.map(_ => Error.Invalid))
          value <- processing(o)
        yield id -> value
      )
      checkedExposure <- session.timing.traverse(t =>
        TemporalExposure
          .inspect(workspace.inventory, t, statuses.toMap, request.overlap)
          .left
          .map(Error.Exposure(_))
      )
      exposure <- (checkedExposure, request.recallWindow) match
        case (Some(e), Some(w)) =>
          if e.timing.clock.digest != w.clock then Left(Error.ForeignTiming)
          else
            e.query(e.timing.clock, w.start, w.endExclusive)
              .left
              .map(Error.Exposure(_))
              .map(Some(_))
        case _ => Right(None)
      rows <- policy.outcomes.zip(statuses).traverse { (claims, status) =>
        entries(claims, measure).map { values =>
          val targets =
            values.map(_._1.destination).collect { case Destination.Target(ref) => ref }.toSet
          val grid =
            if status._2 != ProcessingStatus.Complete then
              Left(Error.Query(TemporalQuery.Refusal.Processing(status._2)))
            else
              for
                p <- TemporalQuery
                  .inspect(
                    workspace.source,
                    workspace.inventory,
                    status._1,
                    measure,
                    values,
                    1.0,
                    request.selection,
                    request.declarations.filter(d => targets(d.target))
                  )
                  .left
                  .map(Error.Query(_))
                g <- TemporalInspection.checked(p, regions).left.map(Error.Grid(_))
              yield g
          new Row(
            status._1,
            status._2,
            claims,
            grid,
            exposure.flatMap(_.units.find(_.unit == status._1))
          )
        }
      }
      state = request.recallWindow match
        case None => TemporalProjection.TimingState.NotRequested(session.timing.nonEmpty)
        case Some(_) if session.timing.isEmpty => TemporalProjection.TimingState.Missing
        case _                                 => TemporalProjection.TimingState.Provided
      digest = hash(
        Vector(
          ViewVersion,
          session.digest.hex,
          policy.byteDigest.hex,
          measure.toString,
          TemporalProjectionCodecs.encodeRequest(request)
        )
      )
    yield new Projection(
      session,
      policy,
      measure,
      request,
      coordinate,
      regions,
      rows,
      exposure,
      state,
      digest
    )

  /** Saved views bind original claims and full requests; summaries are recomputed when reopened. */
  def saveView(value: Projection): Result[String] =
    value.session.workspace.exportBytes.left.map(_ => Error.PermissionDenied).map { _ =>
      Canonical.print(
        Json.obj(
          "schemaVersion" -> Json.fromString(ViewVersion),
          "session" -> Json.fromString(value.session.digest.hex),
          "policy" -> Json.fromString(value.policy.id.value),
          "policyBytes" -> Json.fromString(value.policy.byteDigest.hex),
          "measure" -> Json.fromString(value.measure.toString),
          "request" -> Json.fromString(TemporalProjectionCodecs.encodeRequest(value.request)),
          "digest" -> Json.fromString(value.digest.hex)
        )
      )
    }

  def restoreView(text: String, session: Session): Result[Projection] = for
    json <- parse(text)
    schema <- field[String](json, "schemaVersion")
    _ <- check(schema == ViewVersion)
    digest <- field[String](json, "session")
    _ <- Either.cond(digest == session.digest.hex, (), Error.ForeignView)
    id <- field[String](json, "policy").flatMap(ArtifactId.from(_).left.map(_ => Error.Invalid))
    measureText <- field[String](json, "measure")
    measure <- TemporalQuery.Measure.values.find(_.toString == measureText).toRight(Error.Invalid)
    bytes <- field[String](json, "request")
    request <- TemporalProjectionCodecs
      .decodeRequest(bytes, session.workspace.source)
      .left
      .map(_ => Error.Invalid)
    projection <- session.compile(id, measure, request)
    expected <- saveView(projection).flatMap(parse)
    _ <- Either.cond(json == expected, (), Error.ForeignView)
  yield projection
