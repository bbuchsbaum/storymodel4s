package storymodel4s.bench.sherlock

import java.nio.charset.StandardCharsets
import io.circe.parser.parse
import storymodel4s.core.Checksum

/** One packaged declaration shared with the Python scene scorer. Missing or unsupported records are
  * configuration failures; there is no arithmetic fallback.
  */
private[sherlock] object SherlockGoldRule:
  private val resource = "/storymodel4s/bench/sherlock/scene-coding-rule.json"
  private val bytes =
    val stream = Option(getClass.getResourceAsStream(resource)).getOrElse(
      throw new IllegalStateException(s"missing $resource")
    )
    try stream.readAllBytes()
    finally stream.close()
  private val json = parse(new String(bytes, StandardCharsets.UTF_8))
    .fold(e => throw new IllegalArgumentException(e.message), identity)
  private val cursor = json.hcursor
  private def required[A: io.circe.Decoder](key: String): A =
    cursor.get[A](key).fold(e => throw new IllegalArgumentException(e.message), identity)
  require(required[String]("schema") == "storymodel4s.bench.sherlock-scene-rule/v1")
  require(required[String]("intervalConvention") == "closed-first-sorted")
  val checksum: Checksum = Checksum.ofBytes(bytes)
  val trSeconds: Double = required[Double]("trSeconds")
  require(trSeconds.isFinite && trSeconds > 0.0, "invalid TR duration")
  private val pattern = required[String]("participantPattern").r
  private val rows = required[Vector[io.circe.Json]]("participants").map { row =>
    val c = row.hcursor
    val n = c.get[Int]("participant").fold(throw _, identity)
    val subject = c.get[Option[Int]]("goldSubject").fold(throw _, identity)
    val status = c.get[String]("eligibility").fold(throw _, identity)
    require(n > 0 && subject.forall(_ > 0), "invalid participant/subject")
    require(Set("eligible", "excluded-inconsistent-clock", "no-gold-source")(status))
    require(subject.isDefined == (status == "eligible"), "eligibility/subject disagreement")
    n -> subject
  }
  require(rows.nonEmpty && rows.map(_._1).distinct.size == rows.size, "duplicate/empty aliases")
  require(rows.flatMap(_._2).distinct.size == rows.flatMap(_._2).size, "duplicate gold subjects")
  private val subjects = rows.toMap
  def subjectOf(participant: Int): Option[Int] = subjects.get(participant).flatten
  def participantOf(name: String): Option[Int] =
    pattern.findFirstMatchIn(name).flatMap(_.group(1).toIntOption).filter(subjects.contains)
