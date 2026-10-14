package storymodel4s.probe

import io.circe.Json
import java.nio.file.Files
import munit.FunSuite
import storymodel4s.media.VideoAcquisition

class VideoAcquisitionSuite extends FunSuite:
  test("missing local runtime is actionable and publishes no partial package"):
    val root = Files.createTempDirectory("video-capability-")
    try
      val runtime = Json.obj(
        Vector(
          "ffmpeg",
          "ffprobe",
          "detectorPython",
          "detectorScript",
          "captionPython",
          "captionScript",
          "modelPath"
        ).map(n => n -> Json.fromString(s"missing-$n"))*
      )
      val job = Json.obj(
        "schemaVersion" -> Json.fromString(VideoAcquisition.Schema),
        "edition" -> Json.fromString("f0-job"),
        "runtime" -> runtime,
        "parts" -> Json.arr(),
        "model" -> Json.obj(),
        "captionRecipe" -> Json.obj(),
        "detectorRecipe" -> Json.obj(),
        "samplesPerShot" -> Json.fromInt(3),
        "maxDecodedBytes" -> Json.fromLong(1000000L),
        "timeoutSeconds" -> Json.fromInt(30)
      )
      val output = root.resolve("output")
      val result = VideoAcquisition.run(root, job.noSpaces, output)
      assert(result.left.toOption.exists(_.message.contains("configured ffmpeg is unavailable")))
      assert(!Files.exists(output))
      assert(
        VideoAcquisition
          .run(root, job.noSpaces.replaceFirst("\\{", "{\"parts\":[],"), output)
          .isLeft
      )
    finally
      val _ = Files.delete(root)

  private def resource(name: String): Array[Byte] =
    val in = getClass.getResourceAsStream(s"/f0/$name")
    assert(in != null)
    try in.readAllBytes()
    finally in.close()
  private def jsonResource(name: String): Json =
    io.circe.parser
      .parse(new String(resource(name), java.nio.charset.StandardCharsets.UTF_8))
      .toOption
      .get
  private def withSyntheticWorkers(f: (java.nio.file.Path, Json) => Unit): Unit =
    import scala.jdk.CollectionConverters.*
    import storymodel4s.core.Checksum
    import storymodel4s.media.{Ffmpeg, Ffprobe}
    assume(
      Ffmpeg.locate().nonEmpty && Ffprobe.locate().nonEmpty &&
        Files.isExecutable(java.nio.file.Path.of("/usr/bin/python3")),
      "process court requires ffmpeg, ffprobe and Python"
    )
    val root = Files.createTempDirectory("synthetic-video-workers-")
    def write(name: String, value: String): Unit =
      val _ = Files.writeString(root.resolve(name), value)
    try
      val _ = Files.write(root.resolve("input.mov"), resource("f0-v1.mov"))
      val _ = Files.write(root.resolve("fixture.json"), resource("f0-v1.manifest.json"))
      // These are deliberately synthetic worker responses, not inference or detector evidence.
      // Real ffmpeg/ffprobe and the production subprocess/request/package path still execute.
      write(
        "python",
        """#!/usr/bin/python3
        |import os,sys
        |code=sys.argv[2]
        |if 'import platform, scenedetect' in code:
        | print('scenedetect 0.7.1 python 3.13.11 numpy 2.5.2 opencv 5.0.0')
        |elif 'import platform, mlx_vlm' in code:
        | print('mlx_vlm 0.6.17 mlx 0.32.2 python 3.13.11')
        |else:
        | os.execv(sys.executable,[sys.executable]+sys.argv[1:])
        |""".stripMargin
      )
      assert(root.resolve("python").toFile.setExecutable(true))
      val script = """import json,sys,hashlib,pathlib
        |r=json.loads(pathlib.Path(sys.argv[1]).read_text())
        |o=json.loads(pathlib.Path(__file__).with_suffix('.json').read_text())
        |assert hashlib.sha256(pathlib.Path(r['frames']['file']).read_bytes()).hexdigest()==r['frames']['sha256']
        |o['requestId']=r['requestId']
        |o['frames']={k:v for k,v in r['frames'].items() if k!='file'}
        |if 'extents' in r:
        | o['recipe']=r['recipe']
        | o['model'].update(repo=r['model']['repo'],revision=r['model']['revision'],configSha256=r['model']['filesSha256']['config.json'],filesVerified=sorted(r['model']['filesSha256']))
        | o['coverage']={'requested':len(r['extents']),'observed':len(r['extents'])}
        | for e,q in zip(o['extents'],r['extents']):
        |  e['id']=q['id']; e['ordinals']=q['ordinals']; e['imageGridThw']=[[1,6,12] for _ in q['ordinals']]
        |else:
        | o['detector']=r['detector']
        |pathlib.Path(sys.argv[2]).write_text(json.dumps(o))
        |""".stripMargin
      write("detector.py", script)
      write("caption.py", script)
      val _ = Files.write(root.resolve("detector.json"), resource("f0-v1.detector-outcome.json"))
      val _ = Files.write(root.resolve("caption.json"), resource("f0-v1.caption-outcome.json"))
      val _ = Files.createDirectory(root.resolve("model"))
      val files = Vector("config.json", "synthetic.safetensors").map { name =>
        val content = s"synthetic process fixture: $name"
        write(s"model/$name", content)
        name -> Json.fromString(Checksum.ofText(content).hex)
      }
      val job = Json.obj(
        "schemaVersion" -> Json.fromString(VideoAcquisition.Schema),
        "edition" -> Json.fromString("synthetic-worker-f0"),
        "runtime" -> Json.obj(
          "ffmpeg" -> Json.fromString(Ffmpeg.locate().get.toString),
          "ffprobe" -> Json.fromString(Ffprobe.locate().get.toString),
          "detectorPython" -> Json.fromString("python"),
          "captionPython" -> Json.fromString("python"),
          "detectorScript" -> Json.fromString("detector.py"),
          "captionScript" -> Json.fromString("caption.py"),
          "modelPath" -> Json.fromString("model")
        ),
        "parts" -> Json.arr(
          Json.obj(
            "occurrence" -> Json.fromString("part-a"),
            "manifest" -> Json.fromString("fixture.json"),
            "input" -> Json.fromString("input.mov"),
            "streamIndex" -> Json.fromInt(0)
          )
        ),
        "model" -> Json.obj(
          "repo" -> Json.fromString("synthetic/process-fixture"),
          "revision" -> Json.fromString("v1"),
          "filesSha256" -> Json.obj(files*)
        ),
        "captionRecipe" -> jsonResource("f0-v1.caption-request.json").hcursor
          .downField("recipe")
          .focus
          .get,
        "detectorRecipe" -> jsonResource("f0-v1.detector-request.json").hcursor
          .downField("detector")
          .focus
          .get,
        "samplesPerShot" -> Json.fromInt(3),
        "maxDecodedBytes" -> Json.fromLong(1000000L),
        "timeoutSeconds" -> Json.fromInt(30)
      )
      f(root, job)
    finally
      val stream = Files.walk(root)
      try
        stream.iterator().asScala.toVector.reverse.foreach(p => { val _ = Files.deleteIfExists(p) })
      finally stream.close()

  private def plannedJob(job: Json): Json =
    val c = job.hcursor
    val originalRuntime = c.downField("runtime")
    val runtime = Json.obj(
      Vector("ffmpeg", "ffprobe", "captionPython", "captionScript", "modelPath").map { name =>
        name -> originalRuntime.downField(name).focus.get
      }*
    )
    val inputSha = storymodel4s.core.Checksum.ofBytes(resource("f0-v1.mov")).hex
    Json.obj(
      "schemaVersion" -> Json.fromString("video-source-planned-job/v1"),
      "edition" -> c.downField("edition").focus.get,
      "runtime" -> runtime,
      "parts" -> Json.arr(
        Json.obj(
          "occurrence" -> Json.fromString("part-a"),
          "manifest" -> Json.fromString("fixture.json"),
          "input" -> Json.fromString("input.mov"),
          "inputSha256" -> Json.fromString(inputSha),
          "streamIndex" -> Json.fromInt(0),
          "timebase" -> Json.fromString("1/24000"),
          "extents" -> Json.arr(
            Json.obj(
              "id" -> Json.fromString("a"),
              "start" -> Json.fromString("0"),
              "endExclusive" -> Json.fromString("7000"),
              "samplesPerExtent" -> Json.fromInt(3)
            ),
            Json.obj(
              "id" -> Json.fromString("b"),
              "start" -> Json.fromString("4000"),
              "endExclusive" -> Json.fromString("10000"),
              "samplesPerExtent" -> Json.fromInt(3)
            ),
            Json.obj(
              "id" -> Json.fromString("c"),
              "start" -> Json.fromString("19000"),
              "endExclusive" -> Json.fromString("21000"),
              "samplesPerExtent" -> Json.fromInt(1)
            )
          )
        )
      ),
      "model" -> c.downField("model").focus.get,
      "captionRecipe" -> c.downField("captionRecipe").focus.get,
      "maxDecodedBytes" -> c.downField("maxDecodedBytes").focus.get,
      "timeoutSeconds" -> c.downField("timeoutSeconds").focus.get
    )

  test("real extraction through synthetic worker processes publishes a replayable package"):
    withSyntheticWorkers { (root, job) =>
      val out = root.resolve("output")
      val result =
        VideoAcquisition.run(root, job.noSpaces, out).fold(e => fail(e.message), identity)
      assertEquals(result.parts.size, 1)
      val part = result.parts.head
      assertEquals(part.frames.selected, Vector(0, 6, 13, 14, 21, 28, 29, 36, 44))
      assertEquals(part.examinedSampleIndices, (0 until 9).toVector)
      assertEquals(part.captions.proposals.size, 3)
      assert(Files.exists(out.resolve("part-0/sampled-execution.json")))
      assert(!Files.exists(out.resolve("part-0/full.bgr")))
      val manifest = Files.readAllBytes(out.resolve("manifest.json"))
      val replay = storymodel4s.media.CaptionPackage
        .load(out, manifest, result.manifestChecksum, result.edition)
        .fold(e => fail(e.message), identity)
      assertEquals(replay.identity, result.identity)
      assert(VideoAcquisition.run(root, job.noSpaces, out).isLeft)
      assertEquals(Files.readAllBytes(out.resolve("manifest.json")).toVector, manifest.toVector)
    }

  test("planned intervals preserve exact support and share selected frames"):
    withSyntheticWorkers { (root, job) =>
      val out = root.resolve("planned-output")
      val result = VideoAcquisition
        .run(root, plannedJob(job).noSpaces, out)
        .fold(e => fail(e.message), identity)
      assertEquals(result.parts.size, 1)
      assertEquals(result.parts.head.frames.selected, Vector(0, 2, 4, 5, 7, 17))
      assertEquals(
        result.parts.head.captions.proposals.map(p => (p.support.start, p.support.endExclusive)),
        Vector((0L, 7000L), (4000L, 10000L), (19000L, 21000L))
      )
      assert(Files.exists(out.resolve("plan.json")))
      assert(Files.exists(out.resolve("selection-rule.json")))
      assert(Files.exists(out.resolve("part-0/realized-selections.json")))
      assert(!Files.exists(out.resolve("part-0/detector-envelope.json")))
      val manifest = Files.readAllBytes(out.resolve("manifest.json"))
      val replay = storymodel4s.media.CaptionPackage.load(
        out,
        manifest,
        result.manifestChecksum,
        result.edition
      )
      assertEquals(replay.map(_.identity), Right(result.identity))
    }

  test("planned intervals refuse off-boundary, wrong pins and insufficient multi-frame samples"):
    withSyntheticWorkers { (root, job) =>
      val base = plannedJob(job)
      def altered(f: Json => Json): Json =
        base.mapObject(o => o.add("parts", f(o("parts").get)))
      val offBoundary = altered(
        _.mapArray(
          _.map(
            _.mapObject(
              _.add(
                "extents",
                Json.arr(
                  Json.obj(
                    "id" -> Json.fromString("bad"),
                    "start" -> Json.fromString("1"),
                    "endExclusive" -> Json.fromString("7000"),
                    "samplesPerExtent" -> Json.fromInt(2)
                  )
                )
              )
            )
          )
        )
      )
      assert(
        VideoAcquisition
          .run(root, offBoundary.noSpaces, root.resolve("off"))
          .left
          .toOption
          .exists(_.message.contains("off a frame boundary"))
      )
      val wrongPin =
        altered(_.mapArray(_.map(_.mapObject(_.add("inputSha256", Json.fromString("0" * 64))))))
      assert(VideoAcquisition.run(root, wrongPin.noSpaces, root.resolve("pin")).isLeft)
      val wrongTimebase =
        altered(_.mapArray(_.map(_.mapObject(_.add("timebase", Json.fromString("1/2500"))))))
      assert(
        VideoAcquisition
          .run(root, wrongTimebase.noSpaces, root.resolve("timebase"))
          .left
          .toOption
          .exists(_.message.contains("timebase"))
      )
      val few = altered(
        _.mapArray(
          _.map(
            _.mapObject(
              _.add(
                "extents",
                Json.arr(
                  Json.obj(
                    "id" -> Json.fromString("few"),
                    "start" -> Json.fromString("0"),
                    "endExclusive" -> Json.fromString("7000"),
                    "samplesPerExtent" -> Json.fromInt(1)
                  )
                )
              )
            )
          )
        )
      )
      assert(
        VideoAcquisition
          .run(root, few.noSpaces, root.resolve("few"))
          .left
          .toOption
          .exists(_.message.contains("at least two"))
      )
    }

  test("wrong worker answer refuses the full process pipeline without publishing"):
    withSyntheticWorkers { (root, job) =>
      val script = root.resolve("caption.py")
      val _ = Files.writeString(
        script,
        Files
          .readString(script)
          .replace("o['requestId']=r['requestId']", "o['requestId']='foreign-request'")
      )
      val out = root.resolve("output")
      val result = VideoAcquisition.run(root, job.noSpaces, out)
      assert(result.left.toOption.exists(_.message.contains("request")), result.toString)
      assert(!Files.exists(out))
      val paths = Files.list(root)
      try assert(!paths.anyMatch(_.getFileName.toString.startsWith(".video-source-")))
      finally paths.close()
    }

  test("planned input refusals leave no published or temporary package"):
    withSyntheticWorkers { (root, job) =>
      val base = plannedJob(job)
      val part = base.hcursor.downField("parts").focus.get.asArray.get.head
      val duplicate = base.mapObject(
        _.add(
          "parts",
          Json.arr(
            part,
            part.mapObject(_.add("occurrence", Json.fromString("part-b")))
          )
        )
      )
      val point = base.mapObject(
        _.add(
          "parts",
          Json.arr(
            part.mapObject(
              _.add(
                "extents",
                Json.arr(
                  Json.obj(
                    "id" -> Json.fromString("point"),
                    "start" -> Json.fromString("0"),
                    "endExclusive" -> Json.fromString("0"),
                    "samplesPerExtent" -> Json.fromInt(1)
                  )
                )
              )
            )
          )
        )
      )
      val cases = Vector(
        duplicate -> "extent ids must be unique",
        point -> "positive interval",
        base.mapObject(_.add("inventedAuthority", Json.True)) -> "unknown or missing fields",
        base.mapObject(_.add("maxDecodedBytes", Json.fromLong(1))) -> "maxDecodedBytes"
      )
      cases.zipWithIndex.foreach { case ((config, reason), index) =>
        val out = root.resolve(s"refused-$index")
        val result = VideoAcquisition.run(root, config.noSpaces, out)
        assert(result.left.toOption.exists(_.message.contains(reason)), result.toString)
        assert(!Files.exists(out))
        val files = Files.list(root)
        try assert(!files.anyMatch(_.getFileName.toString.startsWith(".video-source-planned-")))
        finally files.close()
      }
    }

  test("pinned model bytes and decode budget are enforced before publishing"):
    withSyntheticWorkers { (root, job) =>
      val out = root.resolve("output")
      val lowBudget = job.mapObject(_.add("maxDecodedBytes", Json.fromLong(1)))
      assert(
        VideoAcquisition
          .run(root, lowBudget.noSpaces, out)
          .left
          .toOption
          .exists(_.message.contains("maxDecodedBytes"))
      )
      val _ = Files.writeString(root.resolve("model/config.json"), "changed bytes")
      assert(
        VideoAcquisition
          .run(root, job.noSpaces, out)
          .left
          .toOption
          .exists(_.message.contains("model digest mismatch"))
      )
      assert(!Files.exists(out))
    }

  test("reference-bearing media refuses at the first probe subprocess"):
    withSyntheticWorkers { (root, job) =>
      val original = root.resolve("original.mov")
      val _ = Files.move(root.resolve("input.mov"), original)
      val bytes = s"ffconcat version 1.0\nfile '$original'\n".getBytes(
        java.nio.charset.StandardCharsets.UTF_8
      )
      val _ = Files.write(root.resolve("input.mov"), bytes)
      val manifest = jsonResource("f0-v1.manifest.json").mapObject(
        _.add("sha256", Json.fromString(storymodel4s.core.Checksum.ofBytes(bytes).hex))
          .add("byteLength", Json.fromInt(bytes.length))
      )
      val _ = Files.writeString(root.resolve("fixture.json"), manifest.noSpaces)
      val result = VideoAcquisition.run(root, job.noSpaces, root.resolve("output"))
      assert(
        result.left.toOption.exists(_.message.contains("configured probe refused")),
        result.toString
      )
      assert(!Files.exists(root.resolve("output")))
    }
