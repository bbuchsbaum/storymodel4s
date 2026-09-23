import java.util.*; import java.nio.file.*; import java.nio.charset.StandardCharsets;
public class Canon2 {
  static String canonicalize(String raw) { // verbatim from core/.../atlas.scala StorySource.canonicalize
    String unixLines = raw.replace("\r\n", "\n").replace('\r', '\n');
    String[] parts = unixLines.split("\n", -1);
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < parts.length; i++) { if (i > 0) b.append('\n'); b.append(parts[i].replaceAll("[ \t ]+$", "")); }
    String collapsed = b.toString().replaceAll("\n{3,}", "\n\n");
    return collapsed.replaceAll("^\n+", "").replaceAll("\n+$", "");
  }
  public static void main(String[] a) throws Exception {
    Random r = new Random(20260923L); char[] al = {' ','\t','\n','\r','\u0085',' ',' ','a'};
    StringBuilder out = new StringBuilder();
    for (int n = 0; n < 20000; n++) {
      int len = r.nextInt(9); StringBuilder s = new StringBuilder();
      for (int i = 0; i < len; i++) s.append(al[r.nextInt(al.length)]);
      out.append(esc(s.toString())).append('\t').append(esc(canonicalize(s.toString()))).append('\n');
    }
    Files.write(Paths.get(a[0]), out.toString().getBytes(StandardCharsets.UTF_8));
  }
  static String esc(String s){ StringBuilder b=new StringBuilder(); for(char c: s.toCharArray()) b.append(String.format("%04x",(int)c)); return b.toString(); }
}
