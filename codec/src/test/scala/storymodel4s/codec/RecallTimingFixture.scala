package storymodel4s.codec

import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallTiming.*

object RecallTimingFixture:
  val inventory = MappingCodecFixture.inventory
  val clock = Clock.declared(
    Checksum.ofText("synthetic clock source"),
    ClockKey.unsafe("openneuro-onset"),
    Checksum.ofText("synthetic descriptor"),
    RecordingIdentity.Unestablished,
    Origin.Unestablished
  )
  val basis = Basis.SourceReported(Checksum.ofText("synthetic row evidence"))
  val observations = Vector(
    Observation.Missing(MissingReason.BlankSourceCell),
    Observation.OnsetOnly(ExactRational.of(35, 4).toOption.get, basis),
    Observation.OnsetOnly(ExactRational.integer(10), basis),
    Observation.OnsetOnly(ExactRational.integer(9), basis)
  )
  def build(os: Vector[Observation] = observations, c: Clock = clock): RecallTiming =
    RecallTiming
      .checked(
        inventory,
        c,
        inventory.words.zip(os).map((w, o) => Entry(w.id, c, o)),
        RecallTiming
          .Provenance(Checksum.ofText("synthetic correspondence"), RecordingLink.NotEstablished)
      )
      .toOption
      .get
  val exact: RecallTiming = build(
    Vector(
      Observation.OnsetOnly(ExactRational.integer(9007199254740993L), basis),
      Observation.Interval(ExactRational.integer(Long.MinValue), ExactRational.integer(-1), basis),
      Observation.Interval(
        ExactRational.of(1, 3).toOption.get,
        ExactRational.of(2, 3).toOption.get,
        Basis.Estimated(Checksum.ofText("synthetic estimator"))
      ),
      Observation.Missing(MissingReason.NoCorrespondence)
    )
  )
