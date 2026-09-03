package storymodel4s.features

import storymodel4s.core.*

/** A word-level measurement procedure: a named, versioned map from a token in its sequence to a
  * scalar estimate, with the space its values are declared in.
  *
  * Why an interface and not a lexicon: the vision names imageability as *one example* of a
  * word-level measurement that forms an aligned feature track, and the mechanism must not be tuned
  * to it, or to one dataset. A measure is any procedure over the surface tokens that says what it
  * measured, in what units, under what identity, and which tokens it could not measure. Two
  * families ship: a table of published norms ([[LexiconMeasure]], any word→value table with a
  * content identity) and quantities computed from the text alone with no external data
  * ([[TokenLength]], [[TypeFrequency]]). Every token a measure cannot value is a typed `Missing`,
  * never a number (ADR 0003: no eligible unit is scored by a default).
  *
  * A measure is prepared once per sequence ([[over]]) so a text-level quantity such as a type's
  * frequency is counted once, not once per token. Non-lexical tokens are excluded by the track
  * builder before a measure sees them.
  */
trait LexicalMeasure:
  /** The space the measure's raw token track is declared in. */
  def space: FeatureSpace[Double]

  /** Provenance of a track this measure produced over `source`: deterministic library code, whose
    * configuration hash is the measure's own identity.
    */
  def provenance(source: StorySource): TrackProvenance =
    TrackProvenance(
      Provenance.deterministic(LexicalMeasure.Version, identity),
      Some(source.canonicalChecksum)
    )

  /** The content identity of the procedure: its name and version, and for a table its entries. */
  def identity: Checksum

  /** Prepare the measure over one sequence; the result values each lexical token by position. */
  def over(sequence: SurfaceSequence): TokenIndex => Estimate[Double]

object LexicalMeasure:
  val Version: String = "features-measure/v1"

  private[features] def provider(name: String): Fingerprint =
    Fingerprint.unsafe(s"storymodel4s:features:measure:$name")

/** A table of word-level norms, keyed by normalized token form, with a content identity.
  *
  * Built from parsed rows, never from a file here: `features` does no I/O, and a loader lives with
  * the caller that owns the file. Keys are folded through the same normalization the surface
  * sequence applies to words, so a lookup by `TokenView.normalized` finds them. The identity is the
  * checksum of the canonical rendering of the entries (sorted, values as IEEE-754 bits), so two
  * tables with the same content have one identity whatever file they came from, and one changed
  * value gives another.
  */
final class LexiconTable private (
    val name: String,
    val description: String,
    val units: Option[String],
    val entries: Map[String, Double],
    val identity: Checksum
):
  def size: Int = entries.size
  def lookup(normalized: String): Option[Double] = entries.get(normalized)

  override def equals(other: Any): Boolean = other match
    case that: LexiconTable => name == that.name && identity == that.identity
    case _                  => false
  override def hashCode(): Int = (name, identity).##
  override def toString: String =
    s"LexiconTable($name, ${entries.size} entries, ${identity.short()})"

object LexiconTable:
  /** Refuses an empty or space-bearing name, an empty table, a non-finite value, and two rows that
    * fold to one key with different values (a table that cannot say what it thinks of a word).
    */
  def of(
      name: String,
      description: String,
      units: Option[String],
      rows: Iterable[(String, Double)]
  ): Either[DomainError, LexiconTable] =
    val path = s"features/lexicon/$name"
    if name.isEmpty || name.exists(c => c.isWhitespace || c == ':' || c == '/') then
      Left(
        DomainError.InvalidFormat(
          "LexiconTable",
          name,
          "name must be nonempty without whitespace, ':' or '/'"
        )
      )
    else
      val folded = rows.toVector.map((k, v) => (TextNorm.lower(k.trim), v))
      folded.find((_, v) => v.isNaN || v.isInfinite) match
        case Some((k, v)) =>
          Left(DomainError.InvalidFormat("LexiconTable", s"$k=$v", "non-finite value"))
        case None =>
          val grouped = folded.groupMap(_._1)(_._2)
          grouped.collectFirst { case (k, vs) if vs.distinct.size > 1 => k } match
            case Some(k) =>
              Left(DomainError.InvariantViolation(path, s"key '$k' has conflicting values"))
            case None if grouped.isEmpty =>
              Left(DomainError.InvariantViolation(path, "empty table"))
            case None if grouped.contains("") =>
              Left(DomainError.InvariantViolation(path, "empty key"))
            case None =>
              val entries = grouped.view.mapValues(vs => fold(vs.head)).toMap
              val canonical = entries.toVector.sorted
                .map((k, v) => s"$k=${CanonicalDouble.render(v)}")
                .mkString("\n")
              Right(
                new LexiconTable(
                  name,
                  description,
                  units,
                  entries,
                  Checksum.ofText(s"lexicon-table/v1\n$name\n$canonical")
                )
              )

  private def fold(v: Double): Double = if v == 0.0 then 0.0 else v

/** A measure that reads a [[LexiconTable]]: the word's value, or `NotInLexicon`. */
final class LexiconMeasure(val table: LexiconTable) extends LexicalMeasure:
  val identity: Checksum = table.identity

  val space: FeatureSpace[Double] = FeatureSpace(
    FeatureSpaceId.unsafe(s"lexicon:${table.name}:${table.identity.short(12)}"),
    table.description,
    FeatureValueSchema.Scalar(table.units),
    table.units,
    Fingerprint.unsafe(s"storymodel4s:features:lexicon:${table.identity.hex}"),
    normalized = false
  )

  def over(sequence: SurfaceSequence): TokenIndex => Estimate[Double] =
    index =>
      sequence.at(index).flatMap(_.normalized).flatMap(table.lookup) match
        case Some(value) => Estimate.observed(value)
        case None        => Estimate.Missing(MissingReason.NotInLexicon)

/** The length of a lexical token in Unicode code points. Needs nothing outside the text. */
object TokenLength extends LexicalMeasure:
  val Name: String = "token-length/v1"
  val identity: Checksum = Checksum.ofText(s"measure:$Name")
  val space: FeatureSpace[Double] = FeatureSpace(
    FeatureSpaceId.unsafe(s"measure:$Name"),
    "length of each lexical token in Unicode code points",
    FeatureValueSchema.Scalar(Some("codepoints")),
    Some("codepoints"),
    LexicalMeasure.provider(Name),
    normalized = false
  )

  def over(sequence: SurfaceSequence): TokenIndex => Estimate[Double] =
    val text = sequence.atlas.source.canonicalText
    index =>
      sequence.at(index) match
        case Some(token) =>
          val s = text.substring(token.span.start, token.span.endExclusive)
          Estimate.observed(TextNorm.codePoints(s).size.toDouble)
        case None => Estimate.Missing(MissingReason.Excluded)

/** How many lexical tokens of the same normalized form the text contains, counting this one. A
  * text-internal measure of repetition: a first and only mention scores 1. Needs nothing outside
  * the text.
  */
object TypeFrequency extends LexicalMeasure:
  val Name: String = "type-frequency/v1"
  val identity: Checksum = Checksum.ofText(s"measure:$Name")
  val space: FeatureSpace[Double] = FeatureSpace(
    FeatureSpaceId.unsafe(s"measure:$Name"),
    "occurrences of each lexical token's normalized form in the whole text",
    FeatureValueSchema.Scalar(Some("occurrences")),
    Some("occurrences"),
    LexicalMeasure.provider(Name),
    normalized = false
  )

  def over(sequence: SurfaceSequence): TokenIndex => Estimate[Double] =
    val counts: Map[String, Int] =
      sequence.lexicalTokens.flatMap(_.normalized).groupMapReduce((s: String) => s)(_ => 1)(_ + _)
    index =>
      sequence.at(index).flatMap(_.normalized).flatMap(counts.get) match
        case Some(n) => Estimate.observed(n.toDouble)
        case None    => Estimate.Missing(MissingReason.Excluded)

/** Builds raw token tracks from a measure, and the declared reductions of one. */
object TokenTracks:
  /** One observation per surface token: excluded when the token is not lexical, else the measure's
    * estimate, with the token's span as support. Refuses a measure that emits a non-finite value.
    */
  def measure(
      sequence: SurfaceSequence,
      m: LexicalMeasure
  ): Either[DomainError, FeatureTrack[FeatureTarget.Token, Double]] =
    val valued = m.over(sequence)
    val observations = sequence.tokens.zipWithIndex.map { (token, i) =>
      val index = TokenIndex.unsafe(i)
      val estimate: Estimate[Double] =
        if !token.isLexical then Estimate.Missing(MissingReason.Excluded) else valued(index)
      FeatureObservation[FeatureTarget.Token, Double](
        FeatureTarget.Token(index),
        estimate,
        Some(SpanSet.one(token.span)),
        None
      )
    }
    FeatureTrack.validatedScores(
      FeatureTrack.raw(m.space, observations, m.provenance(sequence.atlas.source))
    )

  /** The mean of a token track over each sentence of the atlas, in atlas order, with the sentence's
    * exact span as support and its lexical-token coverage on every observation.
    */
  def perSentence(
      track: FeatureTrack[FeatureTarget.Token, Double],
      sequence: SurfaceSequence,
      reducer: ScalarReducer = ScalarReducer.Mean,
      missing: MissingValuePolicy = MissingValuePolicy.IgnoreMissing
  ): Either[DomainError, FeatureTrack[FeatureTarget.Sentence, Double]] =
    val targets: Vector[(FeatureTarget.Sentence, SpanSet)] =
      sequence.atlas.sentences.map(s => (FeatureTarget.Sentence(s.id), SpanSet.one(s.span)))
    Aggregate.overTargets(track, sequence, TargetFamily.Sentence, targets, reducer, missing)

  /** The mean of a token track over each situation, in the caller's order, with the situation's
    * exact support; every id must resolve.
    */
  def perSituation(
      track: FeatureTrack[FeatureTarget.Token, Double],
      resolver: SupportResolver,
      situations: Vector[SituationId],
      reducer: ScalarReducer = ScalarReducer.Mean,
      missing: MissingValuePolicy = MissingValuePolicy.IgnoreMissing
  ): Either[DomainError, FeatureTrack[FeatureTarget.Situation, Double]] =
    Aggregate.overSituations(track, resolver, situations, reducer, missing)
