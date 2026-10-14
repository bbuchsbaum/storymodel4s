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

/** Executes an explicit annotation interval plan without manufacturing detector evidence. */
private[media] object PlannedVideoAcquisition:
  val Schema = "video-source-planned-job/v1"

  private final case class Runtime(
      ffmpeg: Path,
      ffprobe: Path,
      captionPython: Path,
      captionScript: Path,
      modelPath: Path
  )
  private final case class Extent(id: String, start: Long, endExclusive: Long, count: Int)
  private final case class Part(row: Json, count: Int, extents: Vector[CaptionExtent])

  private def error(code: String, detail: String): DomainError =
    DomainError.InvariantViolation(s"video-source-planned/$code", detail)
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
    attempt(Files.readAllBytes(path)).flatMap { bytes =>
      val value = new String(bytes, UTF_8)
      requireThat(value.getBytes(UTF_8).sameElements(bytes), "utf8", "invalid UTF-8").map(_ =>
        value
      )
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
          "maxDecodedBytes",
          "timeoutSeconds"
        )
        schema <- field[String](json, "schemaVersion")
        _ <- requireThat(schema == Schema, "config", "unsupported planned video job")
        edition <- field[String](json, "edition").flatMap(EditionId.from)
        runtimeJson <- field[Json](json, "runtime")
        runtime <- runtime(root, runtimeJson)
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
        recipe <- CaptionRecipe.parse(json.hcursor.downField("captionRecipe"))
        budget <- field[Long](json, "maxDecodedBytes")
        timeout <- field[Long](json, "timeoutSeconds")
        _ <- requireThat(
          budget > 0 && timeout > 0 && timeout <= 86400,
          "config",
          "positive storage budget and timeout (at most one day) required"
        )
        rows <- field[Vector[Json]](json, "parts")
        occurrences <- rows.traverse(field[String](_, "occurrence"))
        _ <- requireThat(
          rows.nonEmpty && occurrences.forall(
            _.trim.nonEmpty
          ) && occurrences.distinct.size == occurrences.size,
          "parts",
          "nonempty unique occurrences required"
        )
        allExtents <- rows.traverse(r =>
          field[Vector[Json]](r, "extents").flatMap(_.traverse(parseExtent))
        )
        ids = allExtents.flatten.map(_.id)
        _ <- requireThat(
          ids.distinct.size == ids.size,
          "extents",
          "extent ids must be unique across the job"
        )
        _ <- requireThat(
          !Files.exists(output, LinkOption.NOFOLLOW_LINKS),
          "output",
          "output already exists"
        )
        result <- publish(
          root,
          output,
          config,
          edition,
          rows,
          runtime,
          captioner,
          model,
          recipe,
          budget,
          timeout.seconds
        )
      yield result
    }.flatten

  private def runtime(root: Path, json: Json): Either[DomainError, Runtime] =
    val names = Vector("ffmpeg", "ffprobe", "captionPython", "captionScript", "modelPath")
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
    yield Runtime(paths(0), paths(1), paths(2), paths(3), paths(4))

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
      config: String,
      edition: EditionId,
      rows: Vector[Json],
      runtime: Runtime,
      captioner: ToolRealization,
      model: ModelPin,
      recipe: CaptionRecipe,
      budget: Long,
      timeout: FiniteDuration
  ): Either[DomainError, CaptionPackage] =
    val temporary =
      Files.createTempDirectory(output.toAbsolutePath.getParent, ".video-source-planned-")
    try
      for
        _ <- attempt(write(temporary, "plan.json", config))
        _ <- attempt(
          write(
            temporary,
            "selection-rule.json",
            Json
              .obj(
                "schema" -> str("storymodel4s.media.planned-selection-rule"),
                "schemaVersion" -> Json.fromInt(1),
                "rule" -> str("first-last-and-evenly-spaced-interior/v1")
              )
              .noSpaces
          )
        )
        parts <- rows.zipWithIndex.traverse { (row, index) =>
          acquirePart(
            root,
            temporary,
            row,
            index,
            runtime,
            captioner,
            model,
            recipe,
            budget,
            timeout
          )
        }
        total = parts.map(_.count.toLong).sum
        _ <- requireThat(total <= Int.MaxValue, "samples", "too many samples")
        offsets = parts.map(_.count).scanLeft(0)(_ + _).init
        extents = parts.zip(offsets).flatMap { (part, offset) =>
          part.extents.map(e =>
            Json.obj(
              "id" -> str(e.id),
              "ordinals" -> Json.fromValues(e.ordinals.map(i => Json.fromInt(i + offset)))
            )
          )
        }
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

  private def parseExtent(json: Json): Either[DomainError, Extent] =
    def tick(key: String): Either[DomainError, Long] =
      field[String](json, key).flatMap { raw =>
        try Right(java.lang.Long.parseLong(raw))
        catch
          case _: NumberFormatException => Left(error("extent", s"$key must be an integer string"))
      }
    for
      _ <- keys(json, "id", "start", "endExclusive", "samplesPerExtent")
      id <- field[String](json, "id")
      start <- tick("start")
      end <- tick("endExclusive")
      count <- field[Int](json, "samplesPerExtent")
      _ <- requireThat(
        id.trim.nonEmpty && end > start && count > 0,
        "extent",
        "nonempty id, positive interval and positive samplesPerExtent required"
      )
    yield Extent(id, start, end, count)

  private def selection(index: PacketIndex, plan: Extent): Either[DomainError, Vector[Int]] =
    val first = index.entries.indexWhere(_.pts == plan.start)
    val last = index.entries.indexWhere(e =>
      e.durationTicks.exists(d => Math.addExact(e.pts, d) == plan.endExclusive)
    )
    for
      _ <- requireThat(
        first >= 0 && last >= first,
        "boundary",
        s"extent ${plan.id} is off a frame boundary"
      )
      width = last - first + 1
      _ <- requireThat(
        (width == 1 && plan.count == 1) || (width > 1 && plan.count >= 2),
        "samples",
        s"extent ${plan.id} needs one sample for one frame or at least two for multiple frames"
      )
      selectedCount = math.min(width, plan.count)
    yield
      if selectedCount == 1 then Vector(first)
      else
        Vector.tabulate(selectedCount)(i =>
          first + (i.toLong * (width - 1) / (selectedCount - 1)).toInt
        )

  private def acquirePart(
      root: Path,
      out: Path,
      row: Json,
      number: Int,
      runtime: Runtime,
      captioner: ToolRealization,
      model: ModelPin,
      recipe: CaptionRecipe,
      budget: Long,
      timeout: FiniteDuration
  ): Either[DomainError, Part] =
    val prefix = s"part-$number"
    def name(value: String): String = s"$prefix/$value"
    for
      _ <- keys(
        row,
        "occurrence",
        "manifest",
        "input",
        "inputSha256",
        "streamIndex",
        "timebase",
        "extents"
      )
      occurrence <- field[String](row, "occurrence")
      manifestName <- field[String](row, "manifest")
      original <- input(root, manifestName).flatMap(read)
      manifestJson <- MediaJson.parseDocument("fixture-manifest", original)
      updated = manifestJson.mapObject(_.add("file", str(name("media"))))
      manifest <- FixtureManifest.parse(updated.noSpaces)
      source <- field[String](row, "input").flatMap(input(root, _))
      pinnedInput <- field[String](row, "inputSha256").flatMap(Checksum.from)
      observed <- hash(source)
      _ <- requireThat(
        observed == pinnedInput && observed == manifest.sha256 && Files.size(
          source
        ) == manifest.byteLength,
        "input",
        "inputSha256, manifest and actual bytes must agree"
      )
      stream <- field[Int](row, "streamIndex")
      plannedTimebase <- field[String](row, "timebase").flatMap(FixtureManifest.parseTimebase)
      rawExtents <- field[Vector[Json]](row, "extents")
      plans <- rawExtents.traverse(parseExtent)
      _ <- requireThat(
        plans.nonEmpty && plans.map(_.id).distinct.size == plans.size,
        "extents",
        "nonempty globally unique extent ids required"
      )
      _ <- attempt {
        val _ = Files.createDirectory(out.resolve(prefix));
        val _ = Files.copy(source, out.resolve(name("media")))
      }
      probeTool <- ToolRealization.observe("ffprobe", runtime.ffprobe)
      probeArgs = Ffprobe.CanonicalArgs ++ Vector(
        "-format_whitelist",
        "mov,matroska,avi,mpegts,mpeg,ogg"
      )
      probeRun <- Subprocess.run(
        (runtime.ffprobe.toString +: probeArgs) :+ out.resolve(name("media")).toString,
        timeout
      )
      _ <- requireThat(
        probeRun.exitCode == 0,
        "probe",
        "configured probe refused input; no package published"
      )
      parsed <- FfprobeJson.parse(probeRun.stdout)
      probe <- MediaProbe.join(manifest, observed, probeTool, probeArgs, parsed)
      geometry <- manifest
        .stream(stream)
        .flatMap(_.picture)
        .map(_.geometry)
        .toRight(error("stream", "selected stream is not a declared picture stream"))
      index <- FrameSet.admittedStream(probe, stream, geometry)
      _ <- requireThat(
        index.timebase == plannedTimebase,
        "timebase",
        "planned timebase differs from admitted stream"
      )
      selections <- plans.traverse(selection(index, _))
      shared = selections.flatten.distinct.sorted
      _ <- requireThat(
        BigInt(geometry.frameBytes) * shared.size <= BigInt(budget),
        "storage-budget",
        "planned decode exceeds maxDecodedBytes"
      )
      sampled <- SampledExtraction.run(
        runtime.ffmpeg,
        out.resolve(name("media")),
        out.resolve(name("frames.bgr")),
        probe,
        stream,
        geometry,
        shared,
        timeout
      )
      positions = shared.zipWithIndex.toMap
      extents <- plans.zip(selections).traverse { (plan, selected) =>
        CaptionExtent.of(plan.id, selected.map(positions))
      }
      request <- CaptionRequest.issue(
        sampled.frames,
        extents,
        model,
        recipe,
        s"caption-planned-$number-${sampled.frames.identity.hex}"
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
      captions <- CaptionSearch.join(sampled.frames, request, outcome, captioner, localEdition)
      _ <- plans.zip(captions.proposals).traverse_ { (plan, proposal) =>
        requireThat(
          proposal.support.start == plan.start && proposal.support.endExclusive == plan.endExclusive,
          "support",
          s"caption ${plan.id} support differs from planned native interval"
        )
      }
      _ <- attempt {
        write(out, name("fixture.json"), updated.noSpaces)
        write(out, name("probe.json"), probeRun.stdout)
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
              "args" -> Json.fromValues(probeArgs.map(str)),
              "stdoutFile" -> str(name("probe.json")),
              "stdoutSha256" -> str(Checksum.ofText(probeRun.stdout).hex)
            )
            .noSpaces
        )
        write(out, name("frames-envelope.json"), sampled.framesEnvelopeJson)
        write(out, name("sampled-execution.json"), sampled.executionJson)
        write(
          out,
          name("realized-selections.json"),
          Json
            .obj(
              "schema" -> str("storymodel4s.media.planned-realized-selections"),
              "schemaVersion" -> Json.fromInt(1),
              "sharedPresentationOrdinals" -> Json.fromValues(shared.map(Json.fromInt)),
              "extents" -> Json.fromValues(plans.zip(selections).map { (p, s) =>
                Json.obj(
                  "id" -> str(p.id),
                  "presentationOrdinals" -> Json.fromValues(s.map(Json.fromInt))
                )
              })
            )
            .noSpaces
        )
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
      }
    yield Part(
      Json.obj(
        "occurrence" -> str(occurrence),
        "manifest" -> str(name("fixture.json")),
        "probeEnvelope" -> str(name("probe-envelope.json")),
        "framesEnvelope" -> str(name("frames-envelope.json")),
        "framesFile" -> str(name("frames.bgr")),
        "captionEnvelope" -> str(name("caption-envelope.json")),
        "selectedPresentationOrdinals" -> Json.fromValues(shared.map(Json.fromInt)),
        "planFile" -> str("plan.json"),
        "selectionRuleFile" -> str("selection-rule.json"),
        "realizedSelectionsFile" -> str(name("realized-selections.json"))
      ),
      shared.size,
      extents
    )
