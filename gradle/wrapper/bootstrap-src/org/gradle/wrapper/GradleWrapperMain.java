
package org.gradle.wrapper;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class GradleWrapperMain {
  public static void main(String[] args) throws Exception {
    File jar = new File(GradleWrapperMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    File propsFile = new File(jar.getParentFile(), "gradle-wrapper.properties");
    Properties p = new Properties();
    try (InputStream in = new FileInputStream(propsFile)) { p.load(in); }
    String url = p.getProperty("distributionUrl").replace("\\:", ":");
    String name = url.substring(url.lastIndexOf('/') + 1, url.length() - 4);
    Path home = Paths.get(System.getProperty("user.home"), ".gradle", "wrapper", "dists", name);
    Path gradle = home.resolve(name).resolve("bin").resolve(isWindows() ? "gradle.bat" : "gradle");
    if (!Files.exists(gradle)) {
      Files.createDirectories(home);
      Path zip = home.resolve(name + ".zip");
      System.out.println("Downloading " + url);
      try (InputStream in = new URL(url).openStream()) { Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING); }
      unzip(zip, home);
      Files.deleteIfExists(zip);
    }
    List<String> cmd = new ArrayList<>();
    cmd.add(gradle.toString());
    cmd.addAll(Arrays.asList(args));
    Process proc = new ProcessBuilder(cmd).inheritIO().start();
    System.exit(proc.waitFor());
  }
  private static boolean isWindows(){ return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
  private static void unzip(Path zip, Path target) throws IOException {
    try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry e; (e = zis.getNextEntry()) != null;) {
        Path out = target.resolve(e.getName()).normalize();
        if (!out.startsWith(target)) throw new IOException("Zip traversal blocked");
        if (e.isDirectory()) Files.createDirectories(out); else {
          Files.createDirectories(out.getParent());
          Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
          if (out.toString().endsWith("/bin/gradle")) out.toFile().setExecutable(true);
        }
      }
    }
  }
}
