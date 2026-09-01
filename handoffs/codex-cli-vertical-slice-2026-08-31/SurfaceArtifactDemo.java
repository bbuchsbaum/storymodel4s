import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import scala.util.Either;
import scala.util.Right;
import storymodel4s.codec.SurfaceAtlasArtifactCodec$;
import storymodel4s.core.StorySource;
import storymodel4s.core.StorySource$;
import storymodel4s.core.SurfaceAnalyzer$;
import storymodel4s.core.SurfaceAtlas;

public final class SurfaceArtifactDemo {
  public static void main(String[] args) throws Exception {
    byte[] bytes = System.in.readAllBytes();
    String raw = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();

    Either<?, StorySource> admitted = StorySource$.MODULE$.fromText(
        raw,
        StorySource$.MODULE$.fromText$default$2(),
        StorySource$.MODULE$.fromText$default$3(),
        StorySource$.MODULE$.fromText$default$4(),
        StorySource$.MODULE$.fromText$default$5());

    if (!(admitted instanceof Right<?, ?>)) {
      System.err.println("source admission failed");
      System.exit(2);
    }

    StorySource source = ((Right<?, StorySource>) admitted).value();
    SurfaceAtlas atlas = SurfaceAnalyzer$.MODULE$.analyze(source);
    String artifact = SurfaceAtlasArtifactCodec$.MODULE$.encode(atlas);
    if (args.length == 1) {
      Files.writeString(Path.of(args[0]), artifact, StandardCharsets.UTF_8);
    } else {
      System.out.print(artifact);
    }
  }
}
