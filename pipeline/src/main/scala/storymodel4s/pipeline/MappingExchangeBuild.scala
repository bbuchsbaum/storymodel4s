package storymodel4s.pipeline

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{FileAlreadyExistsException, Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import storymodel4s.align.MappingResult
import storymodel4s.codec.{ExpectedMappingContext, MappingExchange}
import storymodel4s.core.Checksum

/** Local-file boundary for the checked single-record exchange component. */
object MappingExchangeBuild:
  enum Error:
    case Exchange(error: MappingExchange.Error)
    case OutputExists, OutputWrite, InputRead

  def write(record: MappingResult, output: Path): Either[Error, Checksum] =
    MappingExchange.encode(record).left.map(Error.Exchange(_)).flatMap(publish(_, output))

  /** A completed manifest is the publication marker; consumers reject a partial directory. */
  private[pipeline] def publish(
      bundle: MappingExchange.Bundle,
      output: Path,
      writeFile: (Path, Array[Byte]) => Unit = (path, bytes) => {
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE): Unit
      },
      writeManifest: (Path, Array[Byte]) => Unit = (path, bytes) => {
        Files.write(path, bytes, StandardOpenOption.WRITE): Unit
      }
  ): Either[Error, Checksum] =
    var pending = Option.empty[Path]
    try
      val destination = output.toAbsolutePath
      Files.createDirectory(destination): Unit
      bundle.files.filterNot(_._1 == "manifest.json").foreach { (name, text) =>
        writeFile(destination.resolve(name), text.getBytes(StandardCharsets.UTF_8))
      }
      val staging = Files.createTempFile(destination.getParent, ".mapping-exchange-", ".pending")
      pending = Some(staging)
      writeManifest(staging, bundle.manifest.getBytes(StandardCharsets.UTF_8))
      Files.createLink(destination.resolve("manifest.json"), staging): Unit
      Right(bundle.digest)
    catch
      case _: FileAlreadyExistsException => Left(Error.OutputExists)
      case NonFatal(_)                   => Left(Error.OutputWrite)
    finally
      pending.foreach { path =>
        try { Files.deleteIfExists(path): Unit }
        catch case NonFatal(_) => ()
      }

  def read(directory: Path, expected: ExpectedMappingContext): Either[Error, MappingResult] =
    val files = try
      val stream = Files.list(directory)
      try
        Right(stream.iterator().asScala.toVector.map { path =>
          val bytes = Files.readAllBytes(path)
          val text = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString
          path.getFileName.toString -> text
        })
      finally stream.close()
    catch case NonFatal(_) => Left(Error.InputRead)
    files.flatMap(MappingExchange.decode(_, expected).left.map(Error.Exchange(_)))
