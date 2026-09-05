package storymodel4s.bench.video

import io.circe.Json
import storymodel4s.align.*
import storymodel4s.core.Checksum
import storymodel4s.recall.RecallUnit

/** Opt-in engineering evidence for comparing selection stages on the same admitted local costs.
  * Source/recall prose is omitted. This is a bench receipt, not a calibrated prediction contract.
  */
private[bench] object StageTrace:

  /** Independent emissions with a uniform state prior. Shifting costs cannot change the result. */
  def localMass(costs: Vector[Double], temperature: Double): Vector[Double] =
    require(costs.nonEmpty && costs.forall(_.isFinite), "finite nonempty local costs required")
    require(temperature.isFinite && temperature > 0.0, "positive finite temperature required")
    val least = costs.min
    val weights = costs.map(c => math.exp(-(c - least) / temperature))
    val total = weights.sum
    weights.map(_ / total)

  private def number(d: Double): Json =
    require(d.isFinite, "trace cannot publish nonfinite numbers")
    Json.fromDoubleOrNull(d)

  private def anchor(ref: Option[SourceNodeRef]): Json =
    ref.fold(Json.Null)(r => Json.fromString(r.key))

  def render(
      built: TimedSourceView.Built,
      units: Vector[RecallUnit],
      candidates: Candidates,
      result: HsmmResult,
      config: HsmmConfig,
      decisions: Vector[MonotoneScene.Decision],
      chosen: Vector[Option[SourceNodeRef]],
      reportChecksum: Checksum,
      sourceInputChecksum: Option[Checksum]
  ): String =
    require(result.refinementPasses == 0, "retained base costs cannot stand for refined emissions")
    require(units.size == result.posterior.rows.size && units.size == chosen.size)
    val noFill =
      if decisions.isEmpty then result.posterior.rows.map(_.mapSource)
      else MonotoneScene.decide(built, result.posterior.rows).map(_.anchor)
    Json
      .obj(
        "schema" -> Json.fromString("storymodel4s.bench.stage-trace/v1"),
        "reportSha256" -> Json.fromString(reportChecksum.hex),
        "sourceInputSha256" -> sourceInputChecksum.fold(Json.Null)(c => Json.fromString(c.hex)),
        "recallChecksum" -> Json.fromString(result.recallChecksum.toString),
        "sourceFingerprint" -> Json.fromString(result.viewFingerprint.checksum.toString),
        "temperature" -> number(config.temperature),
        "refinementPasses" -> Json.fromInt(result.refinementPasses),
        "units" -> Json.fromValues(units.zipWithIndex.map { case (u, i) =>
          val row = result.posterior.rows(i)
          require(row.unit == u.id, "trace and posterior unit order differ")
          val costs = result.costs(u.id).toVector.filterNot(_._2.excluded).sortBy(_._1.key)
          val masses = localMass(costs.map(_._2.total), config.temperature)
          Json.obj(
            "unit" -> Json.fromInt(u.ordinal),
            "unitId" -> Json.fromString(u.id.toString),
            "abstained" -> Json.fromBoolean(candidates.abstained(u.id)),
            "nominations" -> Json.fromValues(candidates.set(u.id).nominations.map { n =>
              Json.obj(
                "ref" -> Json.fromString(n.ref.key),
                "level" -> built.view.node(n.ref).fold(Json.Null)(v => Json.fromInt(v.level)),
                "channel" -> Json.fromString(n.channel),
                "rankWithinLevel" -> Json.fromInt(n.rank),
                "rawScore" -> n.rawScore.fold(Json.Null)(number)
              )
            }),
            "states" -> Json.fromValues(costs.zip(masses).map { case ((state, cost), mass) =>
              Json.obj(
                "state" -> Json.fromString(state.key),
                "anchor" -> anchor(state.anchor),
                "cost" -> number(cost.total),
                "localMass" -> number(mass),
                "posteriorMass" -> number(row(state)),
                "terms" -> Json.obj(cost.terms.toVector.sortBy(_._1.toString).map { (t, v) =>
                  t.toString -> number(v)
                }*),
                "missingTerms" -> Json.fromValues(
                  cost.missingTerms.toVector.map(_.toString).sorted.map(Json.fromString)
                ),
                "imputedTerms" -> Json.fromValues(
                  cost.imputedTerms.keys.toVector.map(_.toString).sorted.map(Json.fromString)
                )
              )
            }),
            "hsmmViterbi" -> Json.fromString(result.viterbi(i).key),
            "posteriorAnchor" -> anchor(row.mapSource),
            "noFillAnchor" -> anchor(noFill(i)),
            "finalAnchor" -> anchor(chosen(i)),
            "assignedScene" -> decisions.lift(i).fold(Json.Null)(d => Json.fromInt(d.scene))
          )
        })
      )
      .noSpaces
