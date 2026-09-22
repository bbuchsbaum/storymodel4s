package storymodel4s.laws

/** Experimental, project-authored diagnostic data. This transcription is checked against the
  * admitted JSON fixture; its alternatives are authored controls, not model estimates.
  */
object MappingMiniature:
  val fixtureSha256 = "be3f8c3d8b4595cd4e58ba0ef8135f26b702cc9c470f4d0a0b2ed8f17d742fc3"
  val schema = "storymodel4s.bench.baseline-miniatures/v1"
  val meaning =
    "Independently authored contract fixtures and expected alternatives, not mapper predictions or calibration data."
  val textId = "synthetic-text-v1"
  val textJoin = "newline"
  val videoId = "synthetic-two-part-v1"
  val mediaBytes = "none: invented annotations and coordinates, not an acquired film"

  final case class Event(id: String, order: Int, text: String)
  final case class Part(id: String, axis: String, ticksPerSecond: String, durationTicks: String)
  final case class Locus(
      event: String,
      part: Option[String],
      startTick: Option[String],
      endTick: Option[String]
  )
  final case class Packet(
      id: String,
      text: String,
      admissible: Vector[String],
      onsetSeconds: Option[String]
  )

  val events: Vector[Event] = Vector(
    Event("e1", 1, "A courier folds a silver map."),
    Event("e2", 2, "A mechanic lifts a violet lantern."),
    Event("e3", 3, "A baker locks a round tin."),
    Event("e4", 4, "A pilot counts five copper pins."),
    Event("e5", 5, "A gardener rinses a blue cup."),
    Event("e6", 6, "A diver unties a green ribbon."),
    Event("e7", 7, "A painter rolls a striped rug."),
    Event("e8", 8, "A nurse closes an empty crate.")
  )
  val parts: Vector[Part] = Vector(
    Part("part-a", "axis-a", "10", "40"),
    Part("part-b", "axis-b", "10", "40")
  )
  val loci: Vector[Locus] = Vector(
    Locus("e1", Some("part-a"), Some("0"), Some("10")),
    Locus("e2", Some("part-a"), Some("10"), Some("20")),
    Locus("e3", Some("part-a"), Some("20"), Some("30")),
    Locus("e4", None, None, None),
    Locus("e5", Some("part-b"), Some("0"), Some("10")),
    Locus("e6", Some("part-b"), Some("10"), Some("20")),
    Locus("e7", Some("part-b"), Some("20"), Some("30")),
    Locus("e8", Some("part-b"), Some("30"), Some("30"))
  )
  val groupId = "g1"
  val groupMembers = Vector("e2", "e4")
  val groupCompletePlaybackSupport = false
  val packets: Vector[Packet] = Vector(
    Packet("p1", "The mechanic lifted the violet lantern.", Vector("e2"), Some("1.0")),
    Packet("p2", "The painter rolled the striped rug.", Vector("e7"), Some("2.0")),
    Packet("p3", "The baker locked the round tin.", Vector("e3"), None),
    Packet("p4", "The nurse closed the empty crate.", Vector("e8"), Some("4.0")),
    Packet("p5", "I replaced the batteries in my desk clock.", Vector("external"), Some("5.0")),
    Packet("p6", "The diver untied the green ribbon.", Vector("e6"), Some("6.0")),
    Packet("p7", "Someone closed a container.", Vector("e3", "e8"), Some("7.0")),
    Packet("p8", "The mechanic lifted that violet lantern again.", Vector("e2"), Some("8.0"))
  )
  val clearPath = Vector("e2", "e7", "e3", "e8")
  val clearBackward = 1
  val clearForward = 2
  val revisitPacket = "p8"
  val externalPacket = "p5"
  val missingTimingPacket = "p3"
  val failurePacket = "p6"
  val failureStatus = "failed"
  val failureSelected: Option[String] = None
  val disagreementPacket = "p3"
  val argmax = "e3"
  val decoded = "e7"
  val disagreementMeaning = "deliberately different reconstruction choice; not a second observation"
  val partialSupportTarget = "g1"
  val ambiguousPacket = "p7"
