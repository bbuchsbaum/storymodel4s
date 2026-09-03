package storymodel4s.bench.sherlock

import java.nio.file.{Files, Path}
import storymodel4s.core.{Checksum, DomainError}
import storymodel4s.view.{ClockSpan, CodedInterval, IndependentCoding}

/** The released 50-scene coding of each Sherlock recall, as an [[IndependentCoding]] for the voyage
  * (`Sherlock_Recall_Scene_n50_Onsets.csv`, gin.g-node.org/ljchang/Sherlock).
  *
  * The clock and the participant mapping are the ones the scene pre-registration fixed
  * (`docs/plans/2026-09-02-gold-scene-preregistration.md` §2–3) and `gold_scene.py` implements:
  * onsets and offsets are TR indices at 1.5 s; gold subject *n* is participant `NN0n` up to 4 and
  * `NN0(n+1)` from 5; `NN05` has no coding; `NN01` is excluded because its coding runs to 1417 s
  * against a 782 s transcript. Two implementations of one rule is one too many, so this one cites
  * the other and the study log carries the numbers that check them against each other.
  */
object SherlockSceneCoding:
  val trSeconds: Double = 1.5

  /** Gold subject for a participant number, or none for the two the pre-registration excludes. */
  def subjectOf(participant: Int): Option[Int] =
    if participant == 1 || participant == 5 then None
    else if participant <= 4 then Some(participant)
    else Some(participant - 1)

  /** The participant number from a recall file name such as `NN03_ANT_202231_final.csv`. */
  def participantOf(fileName: String): Option[Int] =
    val m = "^NN(\\d{2})_".r.findFirstMatchIn(fileName)
    m.map(_.group(1).toInt)

  /** Read the coding for one participant; `None` when the participant has no gold. */
  def load(csv: Path, participant: Int): Either[DomainError, Option[IndependentCoding]] =
    subjectOf(participant) match
      case None          => Right(None)
      case Some(subject) =>
        val bytes = Files.readAllBytes(csv)
        val text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).stripPrefix("﻿")
        val lines = text.split("\r?\n").toVector.filter(_.nonEmpty)
        lines.headOption.map(_.split(",").map(_.trim).toVector) match
          case Some(Vector("Subject", "Scene", "Onset", "Offset")) =>
            val rows = lines.tail.map(_.split(",").map(_.trim))
            val mine = rows.filter(r => r.length == 4 && r(0).toIntOption.contains(subject))
            val intervals = mine.foldLeft[Either[DomainError, Vector[CodedInterval]]](
              Right(Vector.empty)
            ) { (acc, r) =>
              for
                got <- acc
                scene <- r(1).toIntOption.toRight(
                  DomainError.InvalidFormat("SceneCoding.scene", r(1), "not an integer")
                )
                onset <- r(2).toDoubleOption.toRight(
                  DomainError.InvalidFormat("SceneCoding.onset", r(2), "not a number")
                )
                offset <- r(3).toDoubleOption.toRight(
                  DomainError.InvalidFormat("SceneCoding.offset", r(3), "not a number")
                )
                span <- ClockSpan.of(onset * trSeconds, offset * trSeconds)
              yield got :+ CodedInterval(span, scene)
            }
            intervals.map(iv =>
              Some(IndependentCoding(csv.getFileName.toString, Checksum.ofBytes(bytes), iv))
            )
          case other =>
            Left(
              DomainError.InvalidFormat(
                "SceneCoding.header",
                other.fold("(empty)")(_.mkString(",")),
                "expected Subject,Scene,Onset,Offset"
              )
            )
