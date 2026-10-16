import sbt._
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDateTime
import java.net.URL
import java.util.zip.{ZipEntry, ZipFile, ZipOutputStream}
import scala.sys.process._

/** Packages the existing runtime classpath without resolving dependencies on the user's machine. */
object CliDistribution {
  val stageCliDistribution = taskKey[File]("Stage an offline JVM command distribution")
  val cliDistribution = taskKey[File]("Zip the staged JVM command distribution")

  /** Actual sbt project products and resolution records, before classpath attribution is lost. */
  final case class ProjectInput(
      project: String,
      base: File,
      module: ModuleID,
      products: Seq[File],
      report: UpdateReport,
      licenses: Seq[(String, URL)],
      scm: Option[ScmInfo],
      scalaVersion: String
  )

  private final case class Notice(name: String, bytes: Array[Byte], origin: String)
  private final case class Origin(
      json: String,
      licenses: Seq[(String, Option[String])],
      notices: Vector[Notice]
  )
  private final case class Supplement(
      coordinate: String,
      artifactSha256: String,
      license: String,
      file: File,
      url: String
  )

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
        // January 1 is the DOS lower-bound sentinel and can add timezone-dependent extra bytes.
        entry.setTimeLocal(LocalDateTime.of(1980, 1, 2, 0, 0))
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

  private def obj(fields: (String, String)*): String =
    fields.map { case (key, value) => quoted(key) + ":" + value }.mkString("{", ",", "}")

  private def array(values: Seq[String]): String = values.mkString("[", ",", "]")

  private def optional(value: Option[String]): String = value.map(quoted).getOrElse("null")

  private def safeRelative(name: String): Boolean =
    name.nonEmpty && !name.startsWith("/") && !name.contains("\\") &&
      !name.split("/", -1).exists(part => part.isEmpty || part == "." || part == "..")

  private def legalName(name: String): Boolean = {
    val leaf = name.split('/').last.toLowerCase(java.util.Locale.ROOT)
    leaf.matches(
      "(.*[._-])?(license|licence|notice|thirdpartynotices|copying|copyright)([._-].*)?"
    ) &&
    !leaf.matches(".*\\.(class|jar|so|dylib|dll|exe)")
  }

  private def embeddedNotices(file: File): Vector[Notice] = {
    val jar = new ZipFile(file)
    try {
      import scala.collection.JavaConverters._
      val entries = jar
        .entries()
        .asScala
        .filter(entry => !entry.isDirectory && legalName(entry.getName))
        .toVector
      require(entries.map(_.getName).distinct.size == entries.size, "duplicate legal JAR entry")
      entries.sortBy(_.getName).map { entry =>
        val name = entry.getName
        require(safeRelative(name), "unsafe legal JAR entry")
        require(
          entry.getSize > 0 && entry.getSize <= 4 * 1024 * 1024,
          "invalid legal JAR entry size"
        )
        val stream = jar.getInputStream(entry)
        val bytes =
          try IO.readBytes(stream)
          finally stream.close()
        Notice(
          "embedded/" + name,
          bytes,
          obj("kind" -> quoted("jar-entry"), "entry" -> quoted(name))
        )
      }
    } finally jar.close()
  }

  private def supplements(repository: File): Vector[Supplement] = {
    val root = repository / "tools" / "release" / "licenses"
    val rows = IO
      .readLines(root / "catalog.tsv", UTF_8)
      .filter(line => line.nonEmpty && !line.startsWith("#"))
    rows.map { line =>
      val fields = line.split("\t", -1)
      require(fields.length == 6, "invalid license catalog row")
      val Array(coordinate, artifactChecksum, license, name, checksum, url) = fields
      require(
        coordinate.split(":", -1).length == 3 && license.nonEmpty && safeRelative(name),
        "invalid license catalog identity"
      )
      require(
        checksum.matches("[0-9a-f]{64}") && artifactChecksum.matches("[0-9a-f]{64}") && url
          .startsWith("https://"),
        "invalid license evidence provenance"
      )
      val file = root / name
      require(file.isFile && sha(file) == checksum, "license evidence missing or changed: " + name)
      Supplement(coordinate, artifactChecksum, license, file, url)
    }.toVector
  }

  private def sourceOrigin(input: ProjectInput, source: File): Origin = {
    val root = file(Process(Seq("git", "rev-parse", "--show-toplevel"), input.base).!!.trim)
    require(
      Process(Seq("git", "status", "--porcelain"), root).!!.trim.isEmpty,
      "dirty source dependency"
    )
    val revision = Process(Seq("git", "rev-parse", "HEAD"), root).!!.trim
    require(revision.matches("[0-9a-f]{40}"), "invalid source dependency revision")
    val repository = input.scm
      .map(_.browseUrl.toString)
      .getOrElse(sys.error("source dependency has no SCM identity"))
    require(input.licenses.nonEmpty, "source dependency has no declared license")
    val legal = Seq("LICENSE", "LICENSE.txt", "LICENSE.md", "NOTICE", "NOTICE.txt", "COPYING")
      .map(root / _)
      .filter(_.isFile)
      .toVector
    require(legal.exists(_.getName.startsWith("LICENSE")), "source dependency has no license text")
    Origin(
      obj(
        "kind" -> quoted("source-project"),
        "project" -> quoted(input.project),
        "group" -> quoted(input.module.organization),
        "artifact" -> quoted(input.module.name),
        "version" -> quoted(input.module.revision),
        "repository" -> quoted(repository),
        "revision" -> quoted(revision),
        "scalaVersion" -> quoted(input.scalaVersion)
      ),
      input.licenses.map { case (name, url) => name -> Some(url.toString) },
      embeddedNotices(source) ++ legal.map { file =>
        Notice(
          "source/" + file.getName,
          IO.readBytes(file),
          obj(
            "kind" -> quoted("source-file"),
            "path" -> quoted(file.getName),
            "revision" -> quoted(revision)
          )
        )
      }
    )
  }

  private def resolveOrigin(
      source: File,
      inputs: Seq[ProjectInput],
      catalog: Vector[Supplement]
  ): Origin = {
    val canonical = source.getCanonicalFile
    val projects = inputs.filter(_.products.exists(_.getCanonicalFile == canonical))
    val external = inputs
      .flatMap(_.report.configurations)
      .flatMap(_.modules)
      .filterNot(_.evicted)
      .flatMap(report =>
        report.artifacts.collect {
          case (artifact, path) if path.getCanonicalFile == canonical =>
            (report, artifact)
        }
      )
    require(
      projects.size + (if (external.nonEmpty) 1 else 0) == 1,
      "unknown or ambiguous classpath origin: " + source.getName
    )
    if (projects.nonEmpty) sourceOrigin(projects.head, source)
    else {
      require(source.isFile, "external dependency is not a JAR")
      val identities = external.map { case (report, artifact) =>
        (
          report.module.organization,
          report.module.name,
          report.module.revision,
          artifact.name,
          artifact.`type`,
          artifact.extension,
          artifact.classifier
        )
      }.distinct
      require(identities.size == 1, "ambiguous external artifact identity")
      val (group, module, version, artifact, kind, extension, classifier) = identities.head
      val coordinate = s"$group:$module:$version"
      val declared = external.flatMap(_._1.licenses).distinct.sortBy { case (name, url) =>
        (name, url.getOrElse(""))
      }
      require(declared.nonEmpty, "missing declared license: " + coordinate)
      val artifactChecksum = sha(source)
      val evidence = catalog.filter(row =>
        row.coordinate == coordinate && row.artifactSha256 == artifactChecksum
      )
      require(
        declared.forall { case (name, _) => evidence.exists(_.license == name) },
        "unresolved license evidence: " + coordinate + " [" + declared
          .map(_._1)
          .mkString(", ") + "]"
      )
      val notices = evidence.map { row =>
        Notice(
          "supplement/" + row.file.getName,
          IO.readBytes(row.file),
          obj(
            "kind" -> quoted("supplement"),
            "license" -> quoted(row.license),
            "artifactSha256" -> quoted(artifactChecksum),
            "url" -> quoted(row.url)
          )
        )
      }
      Origin(
        obj(
          "kind" -> quoted("maven-artifact"),
          "group" -> quoted(group),
          "artifact" -> quoted(module),
          "version" -> quoted(version),
          "artifactName" -> quoted(artifact),
          "type" -> quoted(kind),
          "extension" -> quoted(extension),
          "classifier" -> optional(classifier),
          "url" -> optional(external.flatMap(_._2.url.map(_.toString)).distinct.headOption)
        ),
        declared,
        embeddedNotices(source) ++ notices
      )
    }
  }

  def stage(
      repository: File,
      target: File,
      version: String,
      scalaVersion: String,
      classpath: Seq[File],
      inputs: Seq[ProjectInput]
  ): File = {
    require(version.matches("[A-Za-z0-9._+\\-]+"), "unsafe distribution version")
    val status = Process(Seq("git", "status", "--porcelain"), repository).!!
    require(status.trim.isEmpty, "CLI distribution requires a clean source checkout")
    val revision = Process(Seq("git", "rev-parse", "HEAD"), repository).!!.trim
    require(revision.matches("[0-9a-f]{40}"), "invalid source revision")
    require(classpath.nonEmpty && classpath.forall(_.exists()), "incomplete runtime classpath")
    val catalog = supplements(repository)
    // Resolve and validate all evidence before replacing any previously staged artifact.
    val origins = classpath.map(resolveOrigin(_, inputs, catalog))
    val output = target / "cli-distribution" / s"storymodel4s-$version"
    IO.delete(output)
    IO.createDirectory(output / "lib")
    IO.createDirectory(output / "bin")
    val provenance = classpath.zip(origins).zipWithIndex.map { case ((source, origin), ordinal) =>
      val staged = output / "lib" / f"$ordinal%04d.jar"
      if (source.isDirectory) zip(members(source), staged) else IO.copyFile(source, staged)
      val notices = origin.notices.zipWithIndex.map { case (notice, index) =>
        val name = f"notices/$ordinal%04d/$index%04d/" + notice.name
        val target = output / name
        IO.createDirectory(target.getParentFile)
        IO.write(target, notice.bytes)
        obj(
          "path" -> quoted(name),
          "bytes" -> target.length().toString,
          "sha256" -> quoted(sha(target)),
          "origin" -> notice.origin
        )
      }
      val licenses = origin.licenses.map { case (name, url) =>
        obj("name" -> quoted(name), "url" -> optional(url))
      }
      obj(
        "path" -> quoted("lib/" + staged.getName),
        "sha256" -> quoted(sha(staged)),
        "origin" -> origin.json,
        "licenses" -> array(licenses),
        "notices" -> array(notices)
      )
    }
    val jars = classpath.indices.map(ordinal => f"$ordinal%04d.jar")
    IO.write(output / "classpath.txt", jars.mkString("", "\n", "\n"), UTF_8)
    IO.write(
      output / "dependency-provenance.json",
      obj(
        "schemaVersion" -> quoted("storymodel4s-cli-dependencies/v1"),
        "source" -> quoted(revision),
        "scalaVersion" -> quoted(scalaVersion),
        "classpath" -> array(provenance)
      ) + "\n",
      UTF_8
    )
    val launcher = output / "bin" / "storymodel4s"
    IO.copyFile(repository / "pipeline" / "src" / "universal" / "bin" / "storymodel4s", launcher)
    require(launcher.setExecutable(true, false), "cannot make launcher executable")
    IO.copyFile(repository / "LICENSE", output / "LICENSE")
    IO.write(
      output / "README.txt",
      "Requires Java 17 or newer on macOS/Linux. Add bin to PATH, or run sh bin/storymodel4s.\n" +
        "Run storymodel4s --help for commands. Model and media resources are supplied separately.\n" +
        "After ZIP extraction run chmod +x bin/storymodel4s before using it directly.\n" +
        "dependency-provenance.json identifies every JAR; notices/ retains dependency license/notice text.\n" +
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
