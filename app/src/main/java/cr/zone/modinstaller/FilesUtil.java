package cr.zone.modinstaller;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

public final class FilesUtil {
    private FilesUtil() {}
    public static String path(String input) throws IOException {
        String value = input.replace('\\', '/');
        if (value.startsWith("/") || value.contains(":" ) || value.indexOf('\0') >= 0)
            throw new IOException(Text.get("message_000") + input);
        List<String> parts = new ArrayList<>();
        for (String p : value.split("/")) {
            if (p.equals("..")) throw new IOException(Text.get("message_001") + input);
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.endsWith(" ") || p.endsWith(".")) throw new IOException(Text.get("message_002") + input);
            parts.add(p);
        }
        return String.join("/", parts);
    }
    public static String asset(String value) throws IOException {
        String p = path(value).toLowerCase(Locale.ROOT);
        if (p.startsWith("gamedata/")) p = p.substring(9);
        Set<String> roots = Set.of("configs", "scripts", "meshes", "textures", "sounds", "shaders", "anims", "particles", "ai", "spawns", "levels", "versions");
        if (!roots.contains(p.split("/")[0]) || !p.contains("/")) throw new IOException(Text.get("message_003") + value);
        if (p.matches(".*\\.(exe|dll|bat|cmd|ps1|apk|so)$")) throw new IOException(Text.get("message_004") + value);
        return p;
    }
    public static void mkdir(File f) throws IOException {
        if (!f.isDirectory() && !f.mkdirs()) throw new IOException(Text.get("message_005") + f.getName());
    }
    public static void erase(File f) throws IOException {
        if (f.isDirectory()) { File[] children = f.listFiles(); if (children != null) for (File c : children) erase(c); }
        if (f.exists() && !f.delete()) throw new IOException(Text.get("message_006") + f.getName());
    }
    public static String sha(InputStream in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] b = new byte[128 * 1024]; int n;
        while ((n = in.read(b)) != -1) md.update(b, 0, n);
        StringBuilder s = new StringBuilder(); for (byte v : md.digest()) s.append(String.format(Locale.ROOT, "%02x", v & 255)); return s.toString();
    }
    public static String sha(File f) throws Exception { try (InputStream in = new FileInputStream(f)) { return sha(in); } }
    public static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[128 * 1024]; long total = 0; int n;
        while ((n = in.read(buf)) != -1) { total += n; if (total > limit) throw new IOException(Text.get("message_007")); out.write(buf, 0, n); }
        return total;
    }
    public static void atomicText(File f, String text) throws IOException {
        mkdir(f.getParentFile()); File temp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.getFD().sync(); }
        Files.move(temp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
    public static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8); }
}
