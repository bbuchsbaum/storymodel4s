package storymodel4s.corpus.intake

import java.nio.file.Path

import storymodel4s.corpus.{IntakeRefusal, RelativeArtifactPath}

/** Resolves a checked relative path under a root, WITHOUT disclosing it.
  *
  * This helper exists so that a caller outside the `storymodel4s.corpus.*` tree can use
  * `RelativeArtifactPath` at all. The type's whole point is that its value never escapes -- its
  * `toString` is `<external-artifact>` and its `value` is `private[corpus]`, because a corpus
  * source set is git-ignored precisely because its filenames can identify participants.
  *
  * Without this, reuse would mean exposing the value and defeating the primitive. With it, a caller
  * hands over a root and gets a resolved `Path`, and the relative path stays opaque.
  */
object IntakePaths:
  /** Resolves under `root`, refusing anything that escapes it after normalization. */
  def resolve(root: Path, path: RelativeArtifactPath): Either[IntakeRefusal, Path] =
    val realRoot = root.normalize()
    val candidate = realRoot.resolve(path.value).normalize()
    if candidate.startsWith(realRoot) then Right(candidate)
    else Left(IntakeRefusal.UnsafeRelativePath)
