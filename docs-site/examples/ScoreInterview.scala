package docsprobe

import storymodel4s.fixtures.interview.BirthdayInterview
import storymodel4s.interview.scoring.AiCategory

/** Public-API-only summary of the synthetic Birthday Interview fixture. */
@main def scoreInterview(): Unit =
  val model = BirthdayInterview.model
  val scores = BirthdayInterview.scores
  val profile = BirthdayInterview.profile

  println(
    s"recall_units=${model.recall.size} details=${model.details.size} assessments=${model.assessments.size}"
  )
  AiCategory.values.foreach { category =>
    val expected = scores.expected(category)
    println(
      f"${category.toString}%-26s point=${expected.point}%.4f " +
        f"interval=[${expected.interval.low}%.4f, ${expected.interval.high}%.4f] " +
        s"hard=${scores.hard(category)}"
    )
  }
  println(
    f"observed_coverage=${scores.coverage.observed}/${scores.coverage.eligible} " +
      f"(${scores.coverage.fraction}%.4f)"
  )
  println(s"placement_resolution=${scores.resolution.render}")
  println(s"internal_ratio_when_resolved=${scores.internalRatio.whenResolved}")
  println(
    f"target_mass=${profile.targetMass}%.4f " +
      f"event_purity=${profile.eventPurity.toOption.getOrElse(Double.NaN)}%.4f " +
      f"probe_gain=${profile.probeGain.toOption.getOrElse(Double.NaN)}%.4f"
  )
  println(
    f"episodic_density_per_word=${profile.episodicDensityPerWord.toOption.getOrElse(Double.NaN)}%.4f " +
      f"episodic_density_per_second=${profile.episodicDensityPerSecond.toOption.getOrElse(Double.NaN)}%.4f"
  )
  val perceptual = profile.perceptualProfile.toVector
    .sortBy(_._1.ordinal)
    .map((modality, score) => s"$modality=${score.toOption}")
    .mkString(", ")
  val mental = profile.mentalStateProfile.toVector
    .sortBy(_._1.ordinal)
    .map((kind, score) => s"$kind=${score.toOption}")
    .mkString(", ")
  println(s"perceptual=$perceptual")
  println(s"mental=$mental")
