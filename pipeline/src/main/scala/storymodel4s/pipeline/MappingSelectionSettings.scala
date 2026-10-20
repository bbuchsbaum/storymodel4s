package storymodel4s.pipeline

import cats.syntax.all.*
import io.circe.Json
import storymodel4s.align.*
import storymodel4s.core.*

/** Versioned selection settings reuse checked policies and bind grouping only against actual input.
  */
private[pipeline] object MappingSelectionSettings:
  def decode(json: Json): Either[
    String,
    (Int, Option[StructuredReconstruction.Profile], Option[Vector[(SourceNodeRef, Int)]])
  ] =
    import StructuredReconstruction.*
    def ref(key: String): Either[String, SourceNodeRef] =
      if key.startsWith("sit:") then
        SituationId.from(key.drop(4)).map(SourceNodeRef.Situation(_)).left.map(_.toString)
      else if key.startsWith("seg:") then
        SegmentId.from(key.drop(4)).map(SourceNodeRef.Segment(_)).left.map(_.toString)
      else Left("unknown source reference")
    json.hcursor.downField("selection").focus match
      case None        => Right((0, None, None))
      case Some(value) =>
        val c = value.hcursor
        for
          _ <- Either.cond(
            value.asObject.exists(
              _.keys.toSet == Set("version", "level", "reconstruction", "grouping")
            ),
            (),
            "invalid selection fields"
          )
          version <- c.get[String]("version").left.map(_.message)
          _ <- Either.cond(version == "mapping-selection/v1", (), "unsupported selection version")
          level <- c.get[Int]("level").left.map(_.message)
          reconstruction <- c.get[Option[Json]]("reconstruction").left.map(_.message)
          profile <- reconstruction.traverse { r =>
            val p = r.hcursor
            for
              _ <- Either.cond(
                r.asObject.exists(
                  _.keys.toSet == Set("temperature", "order", "backwardPenalty", "forwardPenalty")
                ),
                (),
                "invalid reconstruction fields"
              )
              t <- p.get[Double]("temperature").left.map(_.message)
              name <- p.get[String]("order").left.map(_.message)
              order <- OrderPolicy.values.find(_.toString == name).toRight("unknown order")
              backward <- p.get[Double]("backwardPenalty").left.map(_.message)
              forward <- p.get[Double]("forwardPenalty").left.map(_.message)
              inference <- HsmmConfig.of(temperature = t).left.map(_.toString)
              decision <- StructuredReconstruction.DecisionPolicy
                .of(order, backward, forward)
                .left
                .map(_.toString)
              result <- Profile.of(inference, decision).left.map(_.toString)
            yield result
          }
          roots <- c.get[Option[Vector[Json]]]("grouping").left.map(_.message)
          grouping <- roots.traverse(_.traverse { row =>
            for
              _ <- Either.cond(
                row.asObject.exists(_.keys.toSet == Set("root", "rank")),
                (),
                "invalid grouping fields"
              )
              key <- row.hcursor.get[String]("root").left.map(_.message)
              root <- ref(key)
              rank <- row.hcursor.get[Int]("rank").left.map(_.message)
            yield root -> rank
          })
        yield (level, profile, grouping)
