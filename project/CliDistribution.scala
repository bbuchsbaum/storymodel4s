import sbt._
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.sys.process._

/** Packages the existing runtime classpath without resolving dependencies on the user's machine. */
object CliDistribution {
  val stageCliDistribution = taskKey[File]("Stage an offline JVM command distribution")
  val cliDistribution = taskKey[File]("Zip the staged JVM command distribution")

  private def sha(file: File): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(file.toPath)
    try {
      val buffer = new Array[Byte](65536)
      var n = in.read(buffer)
      while (n >= 0) {
        digest.update(buffer, 0, n)
        n = in.read(buffer)
      }
      digest.digest().map(b => f"${b & 0xff}%02x").mkString
    } finally in.close()
  }

  private def members(root: File): Vector[(File, String)] = {
    val stream = Files.walk(root.toPath)
    try {
      import scala.collection.JavaConverters._
      stream
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path))
        .map { path =>
          path.toFile -> root.toPath
            .relativize(path)
            .toString
            .replace(java.io.File.separatorChar, '/')
        }
        .toVector
        .sortBy(_._2)
    } finally stream.close()
  }

  private def zip(files: Vector[(File, String)], output: File): Unit = {
    val stream = new ZipOutputStream(Files.newOutputStream(output.toPath), UTF_8)
    try
      files.foreach { case (file, name) =>
        val entry = new ZipEntry(name)
        entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
        stream.putNextEntry(entry)
        Files.copy(file.toPath, stream)
        stream.closeEntry()
      }
    finally stream.close()
  }

  private def quoted(value: String): String =
    "\"" + value.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    } + "\""

  def stage(
      repository: File,
      target: File,
      version: String,
      scalaVersion: String,
      classpath: Seq[File]
  ): File = {
    require(version.matches("[A-Za-z0-9._+\\-]+"), "unsafe distribution version")
    val status = Process(Seq("git", "status", "--porcelain"), repository).!!
    require(status.trim.isEmpty, "CLI distribution requires a clean source checkout")
    val revision = Process(Seq("git", "rev-parse", "HEAD"), repository).!!.trim
    require(revision.matches("[0-9a-f]{40}"), "invalid source revision")
    require(classpath.nonEmpty && classpath.forall(_.exists()), "incomplete runtime classpath")
    val output = target / "cli-distribution" / s"storymodel4s-$version"
    IO.delete(output)
    IO.createDirectory(output / "lib")
    IO.createDirectory(output / "bin")
    val jars = classpath.zipWithIndex.map { case (source, ordinal) =>
      val staged = output / "lib" / f"$ordinal%04d.jar"
      if (source.isDirectory) zip(members(source), staged) else IO.copyFile(source, staged)
      staged.getName
    }
    IO.write(output / "classpath.txt", jars.mkString("", "\n", "\n"), UTF_8)
    val launcher = output / "bin" / "storymodel4s"
    IO.copyFile(repository / "pipeline" / "src" / "universal" / "bin" / "storymodel4s", launcher)
    require(launcher.setExecutable(true, false), "cannot make launcher executable")
    IO.copyFile(repository / "LICENSE", output / "LICENSE")
    IO.write(
      output / "README.txt",
      "Requires Java 17 or newer on macOS/Linux. Add bin to PATH, or run sh bin/storymodel4s.\n" +
        "Run storymodel4s --help for commands. Model and media resources are supplied separately.\n" +
        "After ZIP extraction run chmod +x bin/storymodel4s before using it directly.\n" +
        "This local artifact is not a published release or a scientific qualification.\n",
      UTF_8
    )
    val entries = members(output).map { case (file, name) =>
      s"""{"path":${quoted(name)},"bytes":${file.length()},"sha256":${quoted(sha(file))}}"""
    }
    IO.write(
      output / "manifest.json",
      s"""{"schemaVersion":"storymodel4s-cli-distribution/v1","source":${quoted(revision)},""" +
        s""""version":${quoted(version)},"scalaVersion":${quoted(scalaVersion)},""" +
        s""""classpath":[${jars.map(quoted).mkString(",")}],"files":[${entries.mkString(
            ","
          )}]}""" + "\n",
      UTF_8
    )
    output
  }

  def archive(staged: File): File = {
    val output = staged.getParentFile / (staged.getName + ".zip")
    zip(members(staged).map { case (file, name) => file -> (staged.getName + "/" + name) }, output)
    output
  }
}
