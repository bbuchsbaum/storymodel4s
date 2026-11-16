package storymodel4s.pipeline.agentmapping

import storymodel4s.align.*
import storymodel4s.core.ExactRational

/** A weight-averaged extent of one reference, read part by part (case study 2, milestone 0).
  *
  * Why a declared reducer: ADR 0031 §1 reports extent per constituent on one declared axis, and any
  * scalar across entries or members is a reducer that names its weights. This one is:
  *   - each constituent is measured on the one part, among `selections`, on which its support is
  *     wholly available (a film in two parts has no single axis covering both; a constituent whose
  *     support is available on no part is unavailable);
  *   - a member's extent is the sum of its constituents' measures, in seconds;
  *   - the reference's extent is Σ w·E / Σ w over its members, with the supplied weights as written
  *     (a definite reference has one member, so its weight cancels).
  *
  * Nothing is renormalized in the reference itself; the division is the reducer's own.
  */
object ReferenceExtent:
  val Version: String = "weighted-member-extent-by-part/v1"

  /** Why a reference has no extent. Why: an unmeasurable value gets no number (rule 7). */
  enum Unavailable:
    /** No selection gave every constituent of member `member` a measurable extent. */
    case Constituent(member: Int, entry: Int, reasons: Vector[ReferenceMeasures.ExtentUnavailable])
    case Weightless(member: Int)
    case Outcome
    case NonFinite

    def code: String = this match
      case Constituent(_, _, reasons) =>
        reasons
          .map {
            case ReferenceMeasures.ExtentUnavailable.Support(units) =>
              units.toVector.map(_._2.productPrefix).distinct.sorted.mkString("+")
            case other => other.productPrefix
          }
          .distinct
          .mkString("|")
      case other => other.productPrefix

  /** The measured extent in seconds and the measures it came from. */
  final case class Result(seconds: Double, log10: Double)

  private def seconds(r: ExactRational): BigDecimal =
    BigDecimal(r.numerator) / BigDecimal(r.denominator)

  /** The reducer over one checked declaration, read on every selection. */
  def of(
      source: SourceRepresentation,
      selections: Vector[TemporalSupport.Selection],
      declaration: ProvisionalReference.Declaration
  ): Either[Unavailable, Result] =
    val measured = selections.flatMap(s =>
      ProvisionalReference
        .of(source, s, declaration, ProvisionalReference.WeightCoverage.Exhaustive)
        .toOption
        .map(ReferenceMeasures.of)
    )
    val perSelection = measured.map(_.members)
    if perSelection.isEmpty || perSelection.exists(_.isLeft) then Left(Unavailable.Outcome)
    else
      val members = perSelection.map(_.toOption.get)
      val count = members.head.size
      val extents = (0 until count).toVector.map { k =>
        val entries = members.map(_(k).entries)
        val weight = members.head(k).weight
        val size = entries.collectFirst { case Right(es) => es.length }
        size match
          case None =>
            Left(Unavailable.Constituent(k, 0, entries.collect { case Left(e) => e }))
          case Some(n) =>
            (0 until n).toVector
              .foldLeft[Either[Unavailable, BigDecimal]](Right(BigDecimal(0))) { (acc, c) =>
                acc.flatMap { total =>
                  val reads = entries.collect { case Right(es) => es.toVector(c).extent }
                  reads.collectFirst { case Right(e) => e } match
                    case Some(e) => Right(total + seconds(e.measure.seconds))
                    case None    =>
                      Left(Unavailable.Constituent(k, c, reads.collect { case Left(e) => e }))
                }
              }
              .flatMap(e => weight.toRight(Unavailable.Weightless(k)).map(w => (BigDecimal(w), e)))
      }
      extents
        .foldLeft[Either[Unavailable, Vector[(BigDecimal, BigDecimal)]]](Right(Vector.empty)) {
          (acc, e) => acc.flatMap(done => e.map(done :+ _))
        }
        .flatMap { pairs =>
          val total = pairs.map(_._1).sum
          val value = (pairs.map((w, e) => w * e).sum / total).toDouble
          val log = math.log10(value)
          if value > 0.0 && value.isFinite && log.isFinite then Right(Result(value, log))
          else Left(Unavailable.NonFinite)
        }
