package storymodel4s.pipeline.agentmapping

import io.circe.Json

/** The view rule of ADR 0031 section 4: results from different producer conditions are different
  * estimands, and no view merges them into one series.
  *
  * Why a checked combinator: nothing in a bundle stops a reader from pooling a content-local run
  * with a whole-recall run and reporting one number. Every cross-bundle view goes through
  * `combine`, which accepts only bundles of one estimand. Replicates of one estimand (another seed
  * or salt) may be combined; a legacy condition, which says nothing about what the producer saw,
  * combines only with the same legacy name and never with a declared condition. Combining does not
  * pool anything itself; it is the gate a view passes before it pools.
  */
object ConditionView:

  /** Why bundles were not combined. */
  enum Refusal:
    case Empty

    /** Two bundles answer different questions. */
    case DifferentEstimands(first: RecordedCondition, other: RecordedCondition)

    def message: String = this match
      case Empty                        => "no bundles to combine"
      case DifferentEstimands(a, other) =>
        s"bundles of different producer conditions are not one estimand: $a and $other"

  /** Items of one estimand, with the conditions they were recorded under, in input order. */
  final class Combined[A] private[ConditionView] (
      val estimand: Either[String, ProducerCondition.Estimand],
      val conditions: Vector[RecordedCondition],
      val items: Vector[A]
  )

  /** Whether two recorded conditions measure the same thing: the same study condition name, and the
    * same producer estimand (or both legacy). The name is kept in the comparison because it can
    * name what else differs, such as the film record shown; refusing there fails closed.
    */
  def sameEstimand(a: RecordedCondition, b: RecordedCondition): Boolean =
    a.name == b.name && a.producer.map(_.estimand) == b.producer.map(_.estimand)

  /** Combine items whose conditions are one estimand; refuse at the first that is not. The estimand
    * is the declared one, or `Left(name)` for a legacy condition.
    */
  def combine[A](
      items: Vector[A]
  )(condition: A => RecordedCondition): Either[Refusal, Combined[A]] =
    items.headOption.toRight(Refusal.Empty).flatMap { head =>
      val first = condition(head)
      items
        .map(condition)
        .find(c => !sameEstimand(first, c))
        .map(c => Refusal.DifferentEstimands(first, c))
        .toLeft(
          new Combined(
            first.producer.map(_.estimand).toRight(first.name),
            items.map(condition).distinct,
            items
          )
        )
    }

  /** A study-level readout over saved bundles: per judgement, how many units fell in each kind, and
    * the bundles' estimand. Only bundles of one estimand are read out together.
    */
  def readout(bundles: Vector[StudyBundle.Loaded]): Either[Refusal, Json] =
    combine(bundles)(_.condition).map { combined =>
      def kinds(field: String): Json =
        val all = combined.items.flatMap(_.units.flatMap { u =>
          u.hcursor.downField(field).downField("judgement").get[String]("kind").toOption
        })
        Json.fromFields(
          all.groupBy(identity).toVector.sortBy(_._1).map((k, v) => k -> Json.fromInt(v.size))
        )
      Json.obj(
        "schema" -> Json.fromString("storymodel4s.agentmapping.study-readout/v1"),
        "estimand" -> combined.estimand.fold(
          name => Json.obj("legacy" -> Json.fromString(name)),
          e =>
            Json.obj(
              "window" -> Json.fromString(e.window.token),
              "order" -> Json.fromString(e.order),
              "ids" -> Json.fromString(e.ids)
            )
        ),
        "conditions" -> Json.fromValues(combined.conditions.map { c =>
          Json.fromFields(
            Vector("name" -> Json.fromString(c.name)) ++
              c.producer.map(p => "rendering" -> Json.fromString(p.rendering))
          )
        }),
        "bundles" -> Json.fromInt(combined.items.size),
        "units" -> Json.fromInt(combined.items.map(_.units.size).sum),
        "judgements" -> Json.fromFields(StudyBundle.Fields.map(f => f -> kinds(f)))
      )
    }
