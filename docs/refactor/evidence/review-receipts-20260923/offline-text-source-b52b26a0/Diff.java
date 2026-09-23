import java.util.*; import java.nio.file.*; import java.nio.charset.StandardCharsets;
public class Diff {
  static String oldCanon(String raw) { // exact pre-b52b26a0 regex chain, executed by the JVM regex engine
    String u = raw.replace("\r\n", "\n").replace('\r', '\n');
    String[] parts = u.split("\n", -1); StringBuilder b = new StringBuilder();
    for (int i = 0; i < parts.length; i++) { if (i > 0) b.append('\n'); b.append(parts[i].replaceAll("[ \t ]+$", "")); }
    String c = b.toString().replaceAll("\n{3,}", "\n\n");
    return c.replaceAll("^\n+", "").replaceAll("\n+$", "");
  }
  public static void main(String[] a) throws Exception {
    Random r = new Random(7L);
    char[] al = {' ','\t','\n','\r','\u0085',' ',' ','a','\u000b','\u000c',' ','​','﻿','　','b'};
    int n = Integer.parseInt(a[0]), bad = 0; StringBuilder out = new StringBuilder();
    for (int k = 0; k < n; k++) {
      int len = r.nextInt(21); StringBuilder s = new StringBuilder();
      for (int i = 0; i < len; i++) s.append(al[r.nextInt(al.length)]);
      String raw = s.toString(), o = oldCanon(raw), nw = storymodel4s.core.StorySource.canonicalize(raw);
      if (!o.equals(nw)) { bad++; if (bad <= 3) System.out.println("DIFF " + hex(raw) + " old=" + hex(o) + " new=" + hex(nw)); }
      out.append(hex(raw)).append('\t').append(hex(nw)).append('\n');
    }
    Files.write(Paths.get(a[1]), out.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println("cases " + n + " new-vs-old-JVM mismatches " + bad);
  }
  static String hex(String s){ StringBuilder b=new StringBuilder(); for(char c: s.toCharArray()) b.append(String.format("%04x",(int)c)); return b.toString(); }
}
