import sbt._

/** Keep the first stable coordinate in every later 1.x binary-compatibility comparison. */
object ReleasePolicy {
  val baseline = "1.0.0"
  private val Version = """(\d+)\.(\d+)(?:\.(\d+))?(?:[-+].*)?""".r

  def requiresBaseline(version: String): Boolean = version match {
    case Version(major, minor, patch) =>
      major == "1" && (BigInt(minor) > 0 || Option(patch).exists(BigInt(_) > 0))
    case _ => sys.error("Unrecognized version for release baseline policy: " + version)
  }

  def requiredVersions(version: String): Set[String] =
    if (requiresBaseline(version)) Set(baseline) else Set.empty

  def check(version: String, project: ModuleID, artifacts: Set[ModuleID]): Unit =
    require(
      !requiresBaseline(version) || artifacts.contains(
        project.withRevision(baseline).withExplicitArtifacts(Vector.empty)
      ),
      "A post-1.0.0 release must compare with this module's exact 1.0.0 binary baseline"
    )
}
