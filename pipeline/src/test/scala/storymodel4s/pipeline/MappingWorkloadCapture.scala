package storymodel4s.pipeline

import com.sun.management.ThreadMXBean
import io.circe.Json
import java.lang.management.{ManagementFactory, MemoryType}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import storymodel4s.align.*
import storymodel4s.codec.Canonical
import storymodel4s.core.{Checksum, SurfaceUnitKind}
import storymodel4s.embed.*

/** Bounded JVM-only workload receipt for the public paired mapping and weighted-export surfaces.
  *
  * Run with `pipeline/Test/runMain storymodel4s.pipeline.MappingWorkloadCapture`. It uses only
  * synthetic Anna/Bob text admitted through [[StudyPreparation]], limits both dimensions to 16,
  * warms each cell once, then reports three deterministic repetitions. This is a baseline capture,
  * not a throughput claim or a request to enlarge candidate budgets.
  */
object MappingWorkloadCapture:
  private val sourceSizes = Vector(2, 8, 16)
  private val recallSizes = Vector(1, 8, 16)
  private val tieBudgets = Vector(1, 16)
  private val weights = Vector(
    StructuralWeight.Reference,
    StructuralWeight.of(1, 2).fold(e => throw IllegalStateException(e.toString), identity),
    StructuralWeight.Reconstruction
  )
  private val weightSets = Vector(
    Vector(StructuralWeight.Reference, StructuralWeight.Reconstruction),
    weights
  )
  private val warmups = 1
  private val repetitions = 3

  private final case class Measurement[A](
      value: A,
      nanos: Long,
      heapBefore: Long,
      heapAfter: Long,
      allocatedBytes: Option[Long]
  )

  private def right[E, A](value: Either[E, A]): A =
    value.fold(e => throw IllegalStateException(e.toString), identity)

  private def heapUsed: Long =
    ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed

  private def heapPeak: Long =
    ManagementFactory.getMemoryPoolMXBeans.asScala
      .filter(_.getType == MemoryType.HEAP)
      .map(_.getPeakUsage.getUsed)
      .sum

  private def allocatedBytes: Option[Long] =
    try
      ManagementFactory.getThreadMXBean match
        case bean: ThreadMXBean if bean.isThreadAllocatedMemorySupported =>
          if !bean.isThreadAllocatedMemoryEnabled then bean.setThreadAllocatedMemoryEnabled(true)
          Option.when(bean.isThreadAllocatedMemoryEnabled)(
            bean.getThreadAllocatedBytes(Thread.currentThread().getId)
          )
        case _ => None
    catch case NonFatal(_) => None

  private def measure[A](body: => A): Measurement[A] =
    val beforeHeap = heapUsed
    val beforeAllocated = allocatedBytes
    val started = System.nanoTime()
    val value = body
    val elapsed = System.nanoTime() - started
    val afterAllocated = allocatedBytes
    Measurement(
      value,
      elapsed,
      beforeHeap,
      heapUsed,
      for
        before <- beforeAllocated
        after <- afterAllocated
      yield after - before
    )

  private def text(size: Int): String =
    Vector
      .tabulate(size) { index =>
        if index % 2 == 0 then "Anna arrived." else "Bob left."
      }
      .mkString(" ")

  private def write(root: Path, name: String, value: String): Unit =
    Files.writeString(root.resolve(name), value): Unit

  private def deleteTree(root: Path): Unit =
    val paths = Files.walk(root)
    try
      paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
        Files.deleteIfExists(path): Unit
      }
    finally paths.close()

  private def config(tieBudget: Int): MappingPipeline.Config =
    MappingPipeline.Config(
      MappingPipeline.LexicalProfile,
      temperature = 1.0,
      perLevel = 1,
      tieBudget = tieBudget,
      requested = Vector(MappingPipeline.Capability.Mapping)
    )

  private def disclosure(size: Int): RecallDisclosure.Input =
    val original = text(size).replace("Anna", "Jane")
    val rendered = text(size)
    val key = KeyId.unsafe("synthetic-workload-key")
    val keys = SensitiveKeyProvider.static(key, Array[Byte](3, 5, 7, 11))
    val detector = right(
      PseudonymizationDetector.wholeWordTable(
        Vector(PseudonymizationTableEntry("Jane", "Anna"))
      )
    )
    val detected = right(detector.detect(original, key, keys))
    val spans = "Jane".r
      .findAllMatchIn(original)
      .map { matched =>
        val span = storymodel4s.core.TextSpan.unsafe(matched.start, matched.end)
        span -> span
      }
      .toVector
    RecallDisclosure.certified(
      right(
        PseudonymizedText.checked(
          PrivacyPolicyId.unsafe("synthetic-workload-policy"),
          key,
          original,
          rendered,
          spans,
          detected,
          detector,
          keys
        )
      )
    )

  private def prepare(root: Path, recallSize: Int): StudyPreparation.Prepared =
    right(
      StudyPreparation.prepare(
        root,
        StudyPreparation.Config(
          StudyPreparation.SourceInput.Text("source.txt", SurfaceUnitKind.Sentence),
          Vector(
            StudyPreparation.RecallInput(
              "synthetic-participant",
              "synthetic-session",
              "recall.txt",
              admission = StudyPreparation.Admission.LocalOnly
            )
          ),
          StudyPreparation.RecallProfile
        ),
        Map(("synthetic-participant", "synthetic-session") -> disclosure(recallSize))
      )
    )

  private def byteCount(root: Path): Long =
    val paths = Files.walk(root)
    try paths.iterator().asScala.filter(Files.isRegularFile(_)).map(Files.size).sum
    finally paths.close()

  private def stateProducts(run: MappingPipeline.Run): Long =
    val states = run.execution.reconstruction.inference.posterior.rows.map(_.mass.size.toLong)
    states.zip(states.drop(1)).map((left, right) => left * right).sum

  private def measurementJson(name: String, m: Measurement[?]): Json =
    Json.obj(
      "stage" -> Json.fromString(name),
      "elapsedNanos" -> Json.fromLong(m.nanos),
      "heapUsedBeforeBytes" -> Json.fromLong(m.heapBefore),
      "heapUsedAfterBytes" -> Json.fromLong(m.heapAfter),
      "heapDeltaBytes" -> Json.fromLong(m.heapAfter - m.heapBefore),
      "threadAllocatedBytes" -> m.allocatedBytes.fold(Json.Null)(Json.fromLong)
    )

  private def once(
      root: Path,
      sourceSize: Int,
      recallSize: Int,
      tieBudget: Int,
      selectedWeights: Vector[StructuralWeight],
      index: Int
  ): Json =
    write(root, "source.txt", text(sourceSize))
    write(root, "recall.txt", text(recallSize))
    val prepared = measure(prepare(root, recallSize))
    val recall = right(prepared.value.outcomes.head.result)
    val candidatesConfig = right(
      StrictCandidateConfig.of(1, TieBudgetRequest.AtMost(tieBudget), lexicalOverlap = false, None)
    )
    val candidates = measure(
      right(
        StrictCandidateGenerator.canonicalLevel(
          StrictSemanticChannel.Lexical,
          candidatesConfig,
          recall.graph,
          prepared.value.source,
          0
        )
      )
    )
    val costModel = right(
      StrictCostModel.of(
        StrictSemanticChannel.Lexical,
        weights = CostWeights.semanticOnly,
        functionPrior = FunctionPrior.none,
        externalMismatch = 0.0,
        distortionPenalty = 0.0
      )
    )
    val localEvidence = measure(
      right(LocalEvidence.compute(recall.graph, prepared.value.source, candidates.value, costModel))
    )
    val paired = measure(
      right(
        MappingPipeline.lexical(
          recall.graph,
          prepared.value.source,
          recall.inventory,
          prepared.value.source.representation,
          config(tieBudget)
        )
      )
    )
    val batch = right(MappingPipeline.batch(prepared.value, config(tieBudget)))
    val weighted = measure(
      selectedWeights.map(weight => right(paired.value.execution.weighted(weight)).identity.hex)
    )
    val label = s"s$sourceSize-r$recallSize-t$tieBudget-w${selectedWeights.size}-$index"
    val pairedOutput = root.resolve(s"paired-$label")
    val pairedExport = measure(
      right(MappingPipeline.publishExchange(batch, pairedOutput, resume = false))
    )
    val weightedOutput = root.resolve(s"weighted-$label")
    val weightedExport = measure(
      right(WeightedMapping.publish(batch, selectedWeights, weightedOutput, resume = false))
    )
    Json.obj(
      "sourceUnits" -> Json.fromInt(sourceSize),
      "recallUnits" -> Json.fromInt(recallSize),
      "perLevel" -> Json.fromInt(1),
      "tieBudget" -> Json.fromInt(tieBudget),
      "requestedWeightedMembers" -> Json.fromInt(selectedWeights.size),
      "input" -> Json.obj(
        "sourceSha256" -> Json.fromString(Checksum.ofText(text(sourceSize)).hex),
        "recallSha256" -> Json.fromString(Checksum.ofText(text(recallSize)).hex),
        "preparation" -> Json.fromString(prepared.value.source.edition.hex)
      ),
      "measurements" -> Json.arr(
        measurementJson("preparation", prepared),
        measurementJson("candidateNomination", candidates),
        measurementJson("localEvidencePricing", localEvidence),
        measurementJson(
          "pairedLexicalLocalEvidenceAndInference",
          paired
        ),
        measurementJson("weightedSelection", weighted),
        measurementJson("pairedExchangeExport", pairedExport),
        measurementJson("weightedReexecutionAndExport", weightedExport)
      ),
      "candidateAccounting" -> Json.obj(
        "sourceLevelConsideredCandidates" -> Json.fromLong(
          candidates.value.byUnit.valuesIterator
            .flatMap(_.semanticOutcomes.iterator.map { summary =>
              (summary.observed + summary.ineligible + summary.missing.values.sum).toLong
            })
            .sum
        ),
        "sourceLevelObservedScores" -> Json.fromLong(
          candidates.value.byUnit.valuesIterator
            .flatMap(_.semanticOutcomes.iterator)
            .map(_.observed.toLong)
            .sum
        ),
        "sourceLevelIneligibleCandidates" -> Json.fromLong(
          candidates.value.byUnit.valuesIterator
            .flatMap(_.semanticOutcomes.iterator)
            .map(_.ineligible.toLong)
            .sum
        ),
        "sourceLevelMissingScores" -> Json.fromLong(
          candidates.value.byUnit.valuesIterator
            .flatMap(_.semanticOutcomes.iterator)
            .map(_.missing.values.sum.toLong)
            .sum
        ),
        "sourceLevelNominatedAnchors" -> Json.fromLong(
          localEvidence.value.nominated.map(_.size.toLong).sum
        ),
        "retainedStates" -> Json.fromLong(localEvidence.value.states.map(_.size.toLong).sum),
        "overflowedLevels" -> Json.fromLong(
          candidates.value.byUnit.valuesIterator.map(_.overflow.size.toLong).sum
        )
      ),
      "stateProductsAdjacent" -> Json.fromLong(stateProducts(paired.value)),
      "members" -> Json.obj(
        "reference" -> Json.fromString(paired.value.reference.digest.hex),
        "reconstruction" -> Json.fromString(paired.value.reconstruction.digest.hex),
        "weighted" -> Json.fromValues(weighted.value.map(Json.fromString))
      ),
      "artifacts" -> Json.obj(
        "pairedManifest" -> Json.fromString(pairedExport.value.hex),
        "pairedBytes" -> Json.fromLong(byteCount(pairedOutput)),
        "weightedManifest" -> Json.fromString(weightedExport.value.hex),
        "weightedBytes" -> Json.fromLong(byteCount(weightedOutput))
      )
    )

  private def parity(row: Json): String =
    val members = row.hcursor.downField("members").focus.get
    val artifacts = row.hcursor.downField("artifacts").focus.get
    Canonical.print(Json.obj("members" -> members, "artifacts" -> artifacts))

  def main(args: Array[String]): Unit =
    require(args.isEmpty, "MappingWorkloadCapture takes no arguments")
    val root = Files.createTempDirectory("mapping-workload-")
    try
      val cells = for
        sourceSize <- sourceSizes
        recallSize <- recallSizes
        tieBudget <- tieBudgets
        selectedWeights <- weightSets
      yield
        require(sourceSize <= 16 && recallSize <= 16, "workload dimensions must remain bounded")
        once(root, sourceSize, recallSize, tieBudget, selectedWeights, index = -1)
        // JIT/classloading warmup
        val rows =
          Vector.tabulate(repetitions)(i =>
            once(root, sourceSize, recallSize, tieBudget, selectedWeights, i)
          )
        val witnesses = rows.map(parity).distinct
        require(
          witnesses.size == 1,
          s"nondeterministic output for $sourceSize/$recallSize/$tieBudget/${selectedWeights.size}"
        )
        Json.obj(
          "sourceUnits" -> Json.fromInt(sourceSize),
          "recallUnits" -> Json.fromInt(recallSize),
          "tieBudget" -> Json.fromInt(tieBudget),
          "requestedWeightedMembers" -> Json.fromInt(selectedWeights.size),
          "parity" -> Json.fromString(witnesses.head),
          "repetitions" -> Json.fromValues(rows)
        )
      println(
        "MAPPING_WORKLOAD_CAPTURE\t" + Canonical.print(
          Json.obj(
            "schemaVersion" -> Json.fromString("mapping-workload-capture/v1"),
            "warmups" -> Json.fromInt(warmups),
            "repetitions" -> Json.fromInt(repetitions),
            "runtime" -> Json.obj(
              "osName" -> Json.fromString(System.getProperty("os.name")),
              "osVersion" -> Json.fromString(System.getProperty("os.version")),
              "osArch" -> Json.fromString(System.getProperty("os.arch")),
              "javaVersion" -> Json.fromString(System.getProperty("java.version")),
              "vmName" -> Json.fromString(System.getProperty("java.vm.name")),
              "availableProcessors" -> Json.fromInt(Runtime.getRuntime.availableProcessors()),
              "heapPeakBytes" -> Json.fromLong(heapPeak),
              "heapPeakStatus" -> Json.fromString("JVM cumulative; not attributed to a stage"),
              "processRssBytes" -> Json.Null,
              "processRssStatus" -> Json.fromString("Unavailable: no portable JVM API"),
              "threadAllocationStatus" -> Json.fromString(
                if allocatedBytes.nonEmpty then "available" else "unavailable"
              )
            ),
            "limits" -> Json.obj(
              "maximumSourceUnits" -> Json.fromInt(16),
              "maximumRecallUnits" -> Json.fromInt(16),
              "perLevel" -> Json.fromInt(1),
              "tieBudgets" -> Json.fromValues(tieBudgets.map(Json.fromInt)),
              "weightSets" -> Json.fromValues(weightSets.map { selected =>
                Json.fromValues(selected.map(w => Json.fromString(w.toString)))
              })
            ),
            "unavailableInternalSplits" -> Json.arr(
              Json.fromString("forward/backward versus Viterbi versus refinement")
            ),
            "cells" -> Json.fromValues(cells)
          )
        )
      )
    finally deleteTree(root)
