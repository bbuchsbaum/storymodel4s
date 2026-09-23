package storymodel4s.pipeline

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import storymodel4s.codec.{MappingCodecs, WorkspaceArchiveCodec}
import storymodel4s.core.Checksum
import storymodel4s.fixtures.WorkspaceFixtures

class MappingExchangeCliSuite extends FunSuite:
  private lazy val workspace = WorkspaceFixtures.all("a" * 40).head._2

  private def temporary[A](run: Path => A): A =
    val root = Files.createTempDirectory("mapping-exchange-cli-")
    try run(root)
    finally
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(p => { Files.delete(p): Unit })
      finally paths.close()

  private def invoke(args: String*): (Int, Vector[String], Vector[String]) =
    val out = Vector.newBuilder[String]
    val err = Vector.newBuilder[String]
    val status = MappingExchangeCli.run(
      args.toList,
      s => { out.addOne(s): Unit },
      s => { err.addOne(s): Unit }
    )
    (status, out.result(), err.result())

  test("explicit second policy produces exact output and a receipt only on stdout") {
    temporary { root =>
      val input = root.resolve("ordinary name.json")
      val text = WorkspaceArchiveCodec.encode(workspace.archive.manifest).toOption.get + "\n"
      Files.writeString(input, text): Unit
      val output = root.resolve("analysis output")
      val selected = workspace.policies(1)
      assertNotEquals(selected.record.digest, workspace.policies.head.record.digest)
      val (status, stdout, stderr) = invoke(input.toString, selected.id.value, output.toString)
      assertEquals(status, 0)
      assertEquals(stderr, Vector.empty)
      assertEquals(stdout.size, 1)
      val receipt = parse(stdout.head).toOption.get.hcursor
      assertEquals(
        receipt.get[String]("schemaVersion").toOption.get,
        MappingExchangeCli.ReceiptSchema
      )
      assertEquals(receipt.get[String]("status").toOption.get, "complete")
      assertEquals(receipt.get[String]("policy_id").toOption.get, selected.id.value)
      assertEquals(receipt.get[String]("input_sha256").toOption.get, Checksum.ofText(text).hex)
      assertEquals(receipt.get[String]("mapping_digest").toOption.get, selected.record.digest.hex)
      assertEquals(
        receipt.get[String]("manifest_sha256").toOption.get,
        Checksum.ofBytes(Files.readAllBytes(output.resolve("manifest.json"))).hex
      )
      assertEquals(
        Files.readString(output.resolve("mapping.json")),
        MappingCodecs.encode(selected.record)
      )
      val (again, nextOut, nextErr) = invoke(input.toString, selected.id.value, output.toString)
      assertEquals(again, 2)
      assertEquals(nextOut, Vector.empty)
      assertEquals(
        parse(nextErr.head).toOption.get.hcursor.get[String]("error").toOption.get,
        "OutputExists"
      )
      assertEquals(
        Files.readString(output.resolve("mapping.json")),
        MappingCodecs.encode(selected.record)
      )
    }
  }

  test("invalid argument shapes and identifiers cannot reach the file boundary") {
    val cases = Vector(
      List.empty[String] -> "Arguments",
      List("secret input", "policy") -> "Arguments",
      List("secret input", "policy", "secret output", "extra") -> "Arguments",
      List("secret input", "bad policy", "secret output") -> "InvalidPolicy",
      List("\u0000secret input", "policy", "secret output") -> "InvalidPath",
      List("", "policy", "secret output") -> "InvalidPath"
    )
    cases.foreach { (args, expected) =>
      val (status, stdout, stderr) = invoke(args*)
      assertEquals(status, 2)
      assertEquals(stdout, Vector.empty)
      assertEquals(stderr.size, 1)
      assert(!stderr.head.contains("secret"))
      assertEquals(
        parse(stderr.head).toOption.get.hcursor.get[String]("error").toOption.get,
        expected
      )
    }
  }

  test("unknown policy refuses without output or leaked input text") {
    temporary { root =>
      val input = root.resolve("input.json")
      Files.writeString(
        input,
        WorkspaceArchiveCodec.encode(workspace.archive.manifest).toOption.get
      ): Unit
      val output = root.resolve("must-not-exist")
      val (status, stdout, stderr) = invoke(input.toString, "unknown-policy", output.toString)
      assertEquals(status, 2)
      assertEquals(stdout, Vector.empty)
      assert(!Files.exists(output))
      val error = parse(stderr.head).toOption.get.hcursor
      assertEquals(error.get[String]("error").toOption.get, "WorkspaceRefused")
      assertEquals(error.get[String]("reason").toOption.get, "IncompatiblePolicy")
      assert(!stderr.head.contains(input.toString))
    }
  }

  test("help succeeds without opening input or printing an error") {
    assertEquals(invoke("--help"), (0, Vector(MappingExchangeCli.Usage), Vector.empty))
  }
