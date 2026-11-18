package storymodel4s.align

/** Emits a compact, synthetic JVM oracle for the two LocalReference populations.
  *
  * Copy this beside ReferenceMeasurementSuite in a clean ea146bbb7 clone before running it.
  */
object LocalReferenceJvmEvidence:
  import ReferenceFixture.*

  private def measuredEvidence(channel: StrictSemanticChannel, external: Double): LocalEvidence =
    val candidates = right(
      StrictCandidateGenerator.canonicalLevel(channel, config(), recall, view, 0)
    )
    val model = right(
      StrictCostModel.of(
        channel,
        weights = CostWeights.semanticOnly,
        functionPrior = FunctionPrior.none,
        externalFloor = external,
        externalMismatch = 0.0,
        distortionPenalty = 0.0,
        contentPolicy = ContentCostPolicy.MeasuredSemanticV1
      )
    )
    right(LocalEvidence.compute(recall, view, candidates, model))

  private def quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def costs(row: LocalReference.Outcome): String =
    row.costs.toVector.sortBy(_._1.key).map { (state, cost) =>
      s"{\"state\":${quote(state.key)},\"cost\":${cost.total},\"excluded\":${cost.excluded}}"
    }.mkString("[", ",", "]")

  private def outcome(row: LocalReference.Outcome): String = row match
    case computed: LocalReference.Computed =>
      val mass = computed.mass.toVector.sortBy(_._1.key).map { (state, value) =>
        s"{\"state\":${quote(state.key)},\"mass\":$value}"
      }.mkString("[", ",", "]")
      s"{\"status\":\"computed\",\"information\":${quote(computed.information.toString)},\"costs\":${costs(computed)},\"mass\":$mass}"
    case missing: LocalReference.NotComputed =>
      s"{\"status\":\"not-computed\",\"reason\":${quote(missing.reason.toString)},\"costs\":${costs(missing)}}"

  private def run(name: String, evidence: LocalEvidence, p: LocalReference.Profile): String =
    val result = right(LocalReference.compute(recall, view, evidence, p))
    s"{\"name\":${quote(name)},\"population\":${quote(p.population.toString)},\"prior\":${quote(p.prior.toString)},\"temperature\":${p.temperature},\"outcome\":${outcome(result.outcomes.head)}}"

  def main(args: Array[String]): Unit =
    val base = evidence(table())
    val supplied = profile()
    val sourceTargets = right(LocalReference.Profile.sourceTargets(supplied.universe, supplied.temperature))
    val measuredTargets = right(
      LocalReference.Profile.sourceTargets(supplied.universe, 1.0 / math.log(2.0))
    )
    val overflow = evidence(table(distance = (_, _) => 0.0), cfg = config(1, TieBudgetRequest.AtMost(1)))
    val incompatibleWeights = right(CostWeights.of(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0))
    val incompatible = evidence(
      table(distance = (_, _) => 0.0),
      weights = incompatibleWeights,
      external = 0.0,
      distortion = 0.3
    )
    val foreign = right(
      LocalReference.Profile.of(
        right(DeclaredUniverse.of(Vector(a, AnnaFixture.sit("foreign")), TargetGrain.SingleLevel(0))),
        1.0
      )
    )
    val items = Vector(
      run("supplied-destinations", base, supplied),
      run("source-targets-placeholder-refusal", base, sourceTargets),
      run("source-targets-measured", measuredEvidence(table(), external = 100.0), measuredTargets),
      run("tie-overflow", overflow, supplied),
      run("incomparable-source-targets", incompatible, sourceTargets),
      s"{\"name\":\"invalid-temperature\",\"refusal\":${quote(LocalReference.Profile.of(supplied.universe, 0.0).left.toOption.get.toString)}}",
      s"{\"name\":\"foreign-universe\",\"refusal\":${quote(LocalReference.compute(recall, view, base, foreign).left.toOption.get.toString)}}"
    )
    println(items.mkString("{\"schema\":\"local-reference-jvm-oracle/v1\",\"cases\":[", ",", "]}"))
