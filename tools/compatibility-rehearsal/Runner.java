import java.net.*;
import java.nio.file.*;
import java.util.*;
import tastymima.intf.*;

/** Calls the installed analyzer with explicit dependency inputs, separate from its own loader. */
public final class Runner {
  private static List<Path> classpath(String artifact) {
    List<Path> paths = new ArrayList<>();
    paths.add(Paths.get(artifact));
    for (String p : System.getProperty("analysis.dependencies").split(java.io.File.pathSeparator))
      paths.add(Paths.get(p));
    paths.add(Paths.get(URI.create("jrt:/java.base")));
    return paths;
  }
  public static void main(String[] args) throws Exception {
    URL[] urls = Arrays.stream(System.getProperty("mima.jars").split(java.io.File.pathSeparator))
      .map(s -> {
        try { return Paths.get(s).toUri().toURL(); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
      }).toArray(URL[]::new);
    TastyMiMa mima = TastyMiMa.newInstance(urls, Runner.class.getClassLoader(), new Config());
    List<Problem> problems = mima.analyze(classpath(args[0]), Paths.get(args[0]),
                                         classpath(args[1]), Paths.get(args[1]));
    problems.forEach(p -> System.out.println(p.getKind() + " " + p.getPathString()));
    System.exit(problems.isEmpty() ? 0 : 1);
  }
}
