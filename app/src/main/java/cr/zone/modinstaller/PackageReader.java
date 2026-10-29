package cr.zone.modinstaller;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.compress.archivers.sevenz.*;

/** Extraction never writes into the game. Android library storage is the only staging target. */
public class PackageReader {
    public interface Progress { void report(String message); }
    public static final int MAX_ENTRIES = 150000;
    private long left;
    private int count;
    private final Set<String> seen = new HashSet<>();
    private final File stage;
    private final Progress progress;
    public PackageReader(File stage, long limit, Progress progress) throws IOException { this.stage = stage; left = limit; this.progress = progress; FilesUtil.mkdir(stage); }
    private File target(String raw) throws IOException {
        if (++count > MAX_ENTRIES) throw new IOException(Text.get("message_157"));
        String name = FilesUtil.path(raw);
        if (name.isEmpty()) throw new IOException(Text.get("message_158"));
        if (!seen.add(name.toLowerCase(Locale.ROOT))) throw new IOException(Text.get("message_159") + name);
        File out = new File(stage, name);
        if (!out.getCanonicalPath().startsWith(stage.getCanonicalPath() + File.separator)) throw new IOException(Text.get("message_160"));
        FilesUtil.mkdir(out.getParentFile()); return out;
    }
    private void write(String name, InputStream in) throws IOException {
        File out = target(name);
        try (OutputStream os = new FileOutputStream(out)) { left -= FilesUtil.copy(in, os, left); }
        if (count % 80 == 0) progress.report(Text.get("message_161") + count + Text.get("message_162"));
    }
    public void zip(File source) throws IOException {
        try (ZipFile zip = new ZipFile(source)) {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) { ZipEntry e = it.nextElement(); FilesUtil.path(e.getName()); if (!e.isDirectory()) try (InputStream in = zip.getInputStream(e)) { write(e.getName(), in); } }
        }
    }
    public void sevenZip(File source) throws IOException {
        try (SevenZFile zip = SevenZFile.builder().setFile(source).setMaxMemoryLimitKiB(192 * 1024).get()) {
            SevenZArchiveEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName(); if (name == null) throw new IOException(Text.get("message_163")); FilesUtil.path(name);
                if (e.isDirectory()) continue;
                if (e.isAntiItem()) throw new IOException(Text.get("message_164"));
                InputStream in = new InputStream() { @Override public int read() throws IOException { return zip.read(); } @Override public int read(byte[] b, int off, int len) throws IOException { return zip.read(b, off, len); } };
                write(name, in);
            }
        }
    }
    public static List<File> find(File root, String name) {
        List<File> found = new ArrayList<>(); findInto(root, name, found); return found;
    }
    private static void findInto(File root, String name, List<File> out) {
        if (root.getName().equalsIgnoreCase(name)) { out.add(root); if (root.isDirectory()) return; }
        File[] list = root.listFiles(); if (list != null) for (File child : list) findInto(child, name, out);
    }
    public static Map<String, File> index(File root) throws IOException {
        Map<String, File> files = new TreeMap<>(); indexInto(root, "", files); return files;
    }
    private static void indexInto(File dir, String prefix, Map<String, File> out) throws IOException {
        File[] list = dir.listFiles(); if (list == null) return;
        for (File f : list) { String p = prefix + f.getName(); if (f.isDirectory()) indexInto(f, p + "/", out); else { String key = FilesUtil.path(p).toLowerCase(Locale.ROOT); if (out.put(key, f) != null) throw new IOException(Text.get("message_165") + p); } }
    }
    public static Map<String, File> manual(List<File> roots) throws IOException {
        Map<String, File> plan = new TreeMap<>();
        for (File root : roots) for (Map.Entry<String, File> e : index(root).entrySet()) {
            if(!e.getKey().contains("/"))continue; // Loose-package readmes are not game assets.
            plan.put(FilesUtil.asset(e.getKey()), e.getValue());
        }
        if (plan.isEmpty()) throw new IOException(Text.get("message_166")); return plan;
    }
}
