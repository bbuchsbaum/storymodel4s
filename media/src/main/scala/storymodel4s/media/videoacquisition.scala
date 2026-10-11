package storymodel4s.media

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.jawn.JawnParser
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path}
import java.security.MessageDigest
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import storymodel4s.core.{Checksum, DomainError, EditionId}

/** Configured offline video acquisition uses admitted input manifests and publishes checked replay.
  */
object VideoAcquisition:
  val Schema: String = "video-source-job/v1"
  private final case class Runtime(
      ffmpeg: Path,
      ffprobe: Path,
      detectorPython: Path,
      detectorScript: Path,
      captionPython: Path,
      captionScript: Path,
      modelPath: Path
  )
  private final case class Part(row: Json, count: Int, extents: Vector[CaptionExtent])
  private def error(code: String, detail: String): DomainError =
    DomainError.InvariantViolation(s"video-source/$code", detail)
  private def requireThat(ok: Boolean, code: String, detail: String): Either[DomainError, Unit] =
    Either.cond(ok, (), error(code, detail))
  private def attempt[A](body: => A): Either[DomainError, A] =
    try Right(body)
    catch case NonFatal(e) => Left(error("io", e.getClass.getSimpleName))
  private def field[A: Decoder](json: Json, key: String): Either[DomainError, A] =
    json.hcursor.get[A](key).left.map(_ => error("config", s"missing or invalid $key"))
  private def keys(j: Json, names: String*): Either[DomainError, Unit] =
    requireThat(
      j.asObject.exists(_.keys.toSet == names.toSet),
      "config",
      "unknown or missing fields"
    )
  private def str(value: String): Json = Json.fromString(value)
  private def tool(value: ToolRealization): Json = Json.obj(
    "name" -> str(value.name),
    "versionLine" -> str(value.versionLine),
    "binarySha256" -> str(value.binarySha256.hex)
  )
  private def worker(value: ToolRealization): Json = Json.obj(
    "name" -> str(value.name),
    "versionLine" -> str(value.versionLine),
    "scriptSha256" -> str(value.binarySha256.hex)
  )
  private def read(path: Path): Either[DomainError, String] =
    attempt(Files.readAllBytes(path)).flatMap { b =>
      val s = new String(b, UTF_8)
      requireThat(s.getBytes(UTF_8).sameElements(b), "utf8", "invalid UTF-8").map(_ => s)
    }
  private def input(root: Path, name: String): Either[DomainError, Path] = attempt {
    root.toRealPath() -> root.resolve(name).toRealPath()
  }.flatMap { (base, path) =>
    requireThat(
      !Path.of(name).isAbsolute && path.startsWith(base) && Files.isRegularFile(path),
      "path",
      "input must be a regular file inside the job directory"
    ).map(_ => path)
  }
  private def hash(path: Path): Either[DomainError, Checksum] = attempt {
    val in = Files.newInputStream(path)
    val digest = MessageDigest.getInstance("SHA-256")
    try
      val buffer = new Array[Byte](65536)
      var n = in.read(buffer)
      while n >= 0 do
        digest.update(buffer, 0, n)
        n = in.read(buffer)
      java.util.HexFormat.of().formatHex(digest.digest())
    finally in.close()
  }.flatMap(Checksum.from)
  private def write(root: Path, name: String, text: String): Unit =
    val _ = Files.writeString(root.resolve(name), text, UTF_8)

  /** Missing tools, runtime modules or pinned local models refuse before creating output. */
  def run(root: Path, config: String, output: Path): Either[DomainError, CaptionPackage] =
    attempt {
      for
        json <- new JawnParser(maxValueSize = None, allowDuplicateKeys = false)
          .parse(config)
          .left
          .map(_ => error("config", "invalid JSON"))
        _ <- keys(
          json,
          "schemaVersion",
          "edition",
          "runtime",
          "parts",
          "model",
          "captionRecipe",
          "detectorRecipe",
          "samplesPerShot",
          "maxDecodedBytes",
          "timeoutSeconds"
        )
        schema <- field[String](json, "schemaVersion")
        _ <- requireThat(schema == Schema, "config", "unsupported video job")
        edition <- field[String](json, "edition").flatMap(EditionId.from)
        runtimeJson <- field[Json](json, "runtime")
        runtime <- runtime(root, runtimeJson)
        detector <- WorkerRealization
          .observe(runtime.detectorPython, runtime.detectorScript)
          .left
          .map(_ =>
            error("missing-capability", "install the configured local PySceneDetect runtime")
          )
        captioner <- WorkerRealization
          .observeCaption(runtime.captionPython, runtime.captionScript)
          .left
          .map(_ => error("missing-capability", "install the configured local MLX caption runtime"))
        model <- ModelPin.parse(json.hcursor.downField("model"))
        _ <- model.files.toVector.traverse_ { (name, expected) =>
          for
            path <- input(runtime.modelPath, name).left.map(_ =>
              error("missing-capability", s"missing pinned local model file $name")
            )
            observed <- hash(path)
            _ <- requireThat(observed == expected, "model", s"model digest mismatch: $name")
          yield ()
        }
        captionRecipe <- CaptionRecipe.parse(json.hcursor.downField("captionRecipe"))
        detectorRecipe <- ContentDetectorRecipe.parse(json.hcursor.downField("detectorRecipe"))
        per <- field[Int](json, "samplesPerShot")
        budget <- field[Long](json, "maxDecodedBytes")
        timeout <- field[Long](json, "timeoutSeconds")
        _ <- requireThat(
          per > 0 && budget > 0 && timeout > 0 && timeout <= 86400,
          "config",
          "positive sampling, storage budget and timeout (at most one day) required"
        )
        parts <- field[Vector[Json]](json, "parts")
        occurrences <- parts.traverse(field[String](_, "occurrence"))
        _ <- requireThat(
          parts.nonEmpty && occurrences.forall(_.trim.nonEmpty) &&
            occurrences.distinct.size == occurrences.size,
          "parts",
          "nonempty unique occurrences required"
        )
        _ <- requireThat(
          !Files.exists(output, LinkOption.NOFOLLOW_LINKS),
          "output",
          "output already exists"
        )
        result <- publish(
          root,
          output,
          edition,
          parts,
          runtime,
          detector,
          captioner,
          model,
          captionRecipe,
          detectorRecipe,
          per,
          budget,
          timeout.seconds
        )
      yield result
    }.flatten

  private def runtime(root: Path, json: Json): Either[DomainError, Runtime] =
    val names = Vector(
      "ffmpeg",
      "ffprobe",
      "detectorPython",
      "detectorScript",
      "captionPython",
      "captionScript",
      "modelPath"
    )
    for
      _ <- keys(json, names*)
      paths <- names.traverse { name =>
        for
          text <- field[String](json, name)
          path = root.resolve(text).toAbsolutePath.normalize()
          _ <- requireThat(
            if name == "modelPath" then Files.isDirectory(path) else Files.isRegularFile(path),
            "missing-capability",
            s"configured $name is unavailable; provide an installed local path"
          )
        yield path
      }
    yield Runtime(paths(0), paths(1), paths(2), paths(3), paths(4), paths(5), paths(6))

  private def invoke(
      python: Path,
      script: Path,
      root: Path,
      request: String,
      outcome: String,
      timeout: FiniteDuration
  ): Either[DomainError, Unit] =
    val launcher =
      "import os,runpy,sys; root,script,request,outcome=sys.argv[1:]; os.chdir(root); sys.argv=[script,request,outcome]; runpy.run_path(script,run_name='__main__')"
    Subprocess
      .run(
        Vector(python.toString, "-c", launcher, root.toString, script.toString, request, outcome),
        timeout
      )
      .flatMap(o =>
        requireThat(
          o.exitCode == 0,
          "worker",
          s"${script.getFileName} exited ${o.exitCode}; no package published"
        )
      )

  private def publish(
      root: Path,
      output: Path,
      edition: EditionId,
      rows: Vector[Json],
      runtime: Runtime,
      detector: ToolRealization,
      captioner: ToolRealization,
      model: ModelPin,
      captionRecipe: CaptionRecipe,
      detectorRecipe: ContentDetectorRecipe,
      per: Int,
      budget: Long,
      timeout: FiniteDuration
  ): Either[DomainError, CaptionPackage] =
    val temporary = Files.createTempDirectory(output.toAbsolutePath.getParent, ".video-source-")
    try
      for
        parts <- rows.zipWithIndex.traverse { (row, index) =>
          acquirePart(
            root,
            temporary,
            row,
            index,
            runtime,
            detector,
            captioner,
            model,
            captionRecipe,
            detectorRecipe,
            per,
            budget,
            timeout
          )
        }
        total = parts.map(_.count.toLong).sum
        _ <- requireThat(total <= Int.MaxValue, "samples", "too many samples")
        offsets = parts.map(_.count).scanLeft(0)(_ + _).init
        extents = parts.zip(offsets).flatMap { (p, offset) =>
          p.extents.map(e =>
            Json.obj(
              "id" -> str(e.id),
              "ordinals" -> Json.fromValues(e.ordinals.map(i => Json.fromInt(i + offset)))
            )
          )
        }
        detectorPythonHash <- hash(runtime.detectorPython)
        captionPythonHash <- hash(runtime.captionPython)
        _ <- attempt(
          write(
            temporary,
            "runtime.json",
            Json
              .obj(
                "detector" -> worker(detector),
                "captioner" -> worker(captioner),
                "detectorInterpreterSha256" -> str(detectorPythonHash.hex),
                "captionInterpreterSha256" -> str(captionPythonHash.hex),
                "scope" -> str(
                  "interpreter and worker bytes plus observed module versions; not a wheel audit"
                )
              )
              .noSpaces
          )
        )
        paths <- attempt {
          val stream = Files.walk(temporary)
          try stream.iterator().asScala.filter(Files.isRegularFile(_)).toVector.sortBy(_.toString)
          finally stream.close()
        }
        pins <- paths.traverse(p =>
          hash(p).map(h => temporary.relativize(p).toString -> str(h.hex))
        )
        manifest = Json
          .obj(
            "schema" -> str(CaptionPackage.Schema),
            "schemaVersion" -> Json.fromInt(2),
            "edition" -> str(edition.value),
            "files" -> Json.obj(pins*),
            "parts" -> Json.fromValues(parts.map(_.row)),
            "extents" -> Json.fromValues(extents)
          )
          .noSpaces
        bytes = manifest.getBytes(UTF_8)
        checked <- CaptionPackage.load(temporary, bytes, Checksum.ofBytes(bytes), edition)
        _ <- attempt {
          write(temporary, "manifest.json", manifest)
          val _ = Files.move(temporary, output)
        }
      yield checked
    finally
      if Files.exists(temporary) then
        val paths = Files.walk(temporary)
        try
          paths
            .iterator()
            .asScala
            .toVector
            .reverse
            .foreach(p => { val _ = Files.deleteIfExists(p) })
        finally paths.close()

  private def acquirePart(
      root: Path,
      out: Path,
      row: Json,
      number: Int,
      runtime: Runtime,
      detector: ToolRealization,
      captioner: ToolRealization,
      model: ModelPin,
      captionRecipe: CaptionRecipe,
      detectorRecipe: ContentDetectorRecipe,
      per: Int,
      budget: Long,
      timeout: FiniteDuration
  ): Either[DomainError, Part] =
    val prefix = s"part-$number"
    def name(s: String): String = s"$prefix/$s"
    for
      _ <- keys(row, "occurrence", "manifest", "input", "streamIndex")
      occurrence <- field[String](row, "occurrence")
      manifestName <- field[String](row, "manifest")
      manifestPath <- input(root, manifestName)
      original <- read(manifestPath)
      manifestJson <- MediaJson.parseDocument("fixture-manifest", original)
      updated = manifestJson.mapObject(_.add("file", str(name("media"))))
      manifest <- FixtureManifest.parse(updated.noSpaces)
      inputName <- field[String](row, "input")
      source <- input(root, inputName)
      observed <- hash(source)
      _ <- requireThat(
        observed == manifest.sha256 && Files.size(source) == manifest.byteLength,
        "input",
        "raw input differs from admitted fixture manifest"
      )
      stream <- field[Int](row, "streamIndex")
      _ <- attempt {
        val _ = Files.createDirectory(out.resolve(prefix));
        val _ = Files.copy(source, out.resolve(name("media")))
      }
      probeTool <- ToolRealization.observe("ffprobe", runtime.ffprobe)
      stdout <- Ffprobe.invoke(runtime.ffprobe, out.resolve(name("media")))
      parsed <- FfprobeJson.parse(stdout)
      probe <- MediaProbe.join(manifest, observed, probeTool, Ffprobe.CanonicalArgs, parsed)
      geometry <- manifest
        .stream(stream)
        .flatMap(_.picture)
        .map(_.geometry)
        .toRight(error("stream", "selected stream is not a declared picture stream"))
      index <- FrameSet.admittedStream(probe, stream, geometry)
      _ <- requireThat(
        BigInt(geometry.frameBytes) * index.entries.size <= BigInt(budget),
        "storage-budget",
        "full detector decode exceeds maxDecodedBytes"
      )
      full <- SampledExtraction.run(
        runtime.ffmpeg,
        out.resolve(name("media")),
        out.resolve(name("full.bgr")),
        probe,
        stream,
        geometry,
        index.entries.indices.toVector,
        timeout
      )
      fullFrames <- FrameSet.join(
        probe,
        stream,
        geometry,
        full.frames.tool,
        full.frames.args,
        geometry.frameBytes * full.frames.count.toLong,
        full.frames.framesSha256
      )
      detectorRequest = DetectorRequest.issue(
        fullFrames,
        detectorRecipe,
        s"detector-$number-${probe.identity.hex}"
      )
      _ <- attempt(
        write(out, name("detector-request.json"), detectorRequest.render(name("full.bgr")))
      )
      _ <- invoke(
        runtime.detectorPython,
        runtime.detectorScript,
        out,
        name("detector-request.json"),
        name("detector-outcome.json"),
        timeout
      )
      detectedText <- read(out.resolve(name("detector-outcome.json")))
      detected <- DetectorOutcome.parse(detectedText)
      _ <- BoundarySearch.join(fullFrames, detectorRequest, detected, detector)
      limits = Vector(0) ++ detected.cuts ++ Vector(full.frames.count)
      selections = limits
        .sliding(2)
        .map { bounds =>
          val count = math.min(per, bounds(1) - bounds(0))
          Vector.tabulate(count)(i =>
            bounds(0) + (i.toLong * (bounds(1) - bounds(0) - 1) / math.max(1, count - 1)).toInt
          )
        }
        .toVector
      selected = selections.flatten
      sampled <- SampledExtraction.run(
        runtime.ffmpeg,
        out.resolve(name("media")),
        out.resolve(name("frames.bgr")),
        probe,
        stream,
        geometry,
        selected,
        timeout
      )
      starts = selections.map(_.size).scanLeft(0)(_ + _).init
      extents <- selections.zip(starts).zipWithIndex.traverse { case ((selection, start), i) =>
        CaptionExtent.of(s"part-$number-shot-$i", (start until start + selection.size).toVector)
      }
      request <- CaptionRequest.issue(
        sampled.frames,
        extents,
        model,
        captionRecipe,
        s"caption-$number-${sampled.frames.identity.hex}"
      )
      _ <- attempt(
        write(
          out,
          name("caption-request.json"),
          request.render(name("frames.bgr"), runtime.modelPath.toString)
        )
      )
      _ <- invoke(
        runtime.captionPython,
        runtime.captionScript,
        out,
        name("caption-request.json"),
        name("caption-outcome.json"),
        timeout
      )
      outcomeText <- read(out.resolve(name("caption-outcome.json")))
      outcome <- CaptionOutcome.parse(outcomeText)
      localEdition <- EditionId.from(manifest.fixtureId)
      _ <- CaptionSearch.join(sampled.frames, request, outcome, captioner, localEdition)
      _ <- attempt {
        write(out, name("fixture.json"), updated.noSpaces)
        write(out, name("probe.json"), stdout)
        write(
          out,
          name("probe-envelope.json"),
          Json
            .obj(
              "schema" -> str(ProbeEnvelope.Schema),
              "schemaVersion" -> Json.fromInt(1),
              "fixtureId" -> str(manifest.fixtureId),
              "inputSha256" -> str(observed.hex),
              "tool" -> tool(probeTool),
              "args" -> Json.fromValues(Ffprobe.CanonicalArgs.map(str)),
              "stdoutFile" -> str(name("probe.json")),
              "stdoutSha256" -> str(Checksum.ofText(stdout).hex)
            )
            .noSpaces
        )
        write(out, name("frames-envelope.json"), sampled.framesEnvelopeJson)
        write(out, name("sampled-execution.json"), sampled.executionJson)
        write(out, name("full-execution.json"), full.executionJson)
        write(
          out,
          name("caption-envelope.json"),
          Json
            .obj(
              "schema" -> str(CaptionEnvelope.Schema),
              "schemaVersion" -> Json.fromInt(1),
              "fixtureId" -> str(manifest.fixtureId),
              "worker" -> worker(captioner),
              "requestFile" -> str(name("caption-request.json")),
              "outcomeFile" -> str(name("caption-outcome.json")),
              "outcomeSha256" -> str(Checksum.ofText(outcomeText).hex)
            )
            .noSpaces
        )
        write(
          out,
          name("detector-envelope.json"),
          Json
            .obj(
              "schema" -> str(DetectorEnvelope.Schema),
              "schemaVersion" -> Json.fromInt(1),
              "fixtureId" -> str(manifest.fixtureId),
              "worker" -> worker(detector),
              "requestFile" -> str(name("detector-request.json")),
              "outcomeFile" -> str(name("detector-outcome.json")),
              "outcomeSha256" -> str(Checksum.ofText(detectedText).hex)
            )
            .noSpaces
        )
        val _ = Files.delete(out.resolve(name("full.bgr")))
      }
    yield Part(
      Json.obj(
        "occurrence" -> str(occurrence),
        "manifest" -> str(name("fixture.json")),
        "probeEnvelope" -> str(name("probe-envelope.json")),
        "framesEnvelope" -> str(name("frames-envelope.json")),
        "framesFile" -> str(name("frames.bgr")),
        "captionEnvelope" -> str(name("caption-envelope.json")),
        "selectedPresentationOrdinals" -> Json.fromValues(selected.map(Json.fromInt))
      ),
      selected.size,
      extents
    )
