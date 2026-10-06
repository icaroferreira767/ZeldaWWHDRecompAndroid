package org.wwhdrecomp.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Streaming import of a user-owned extracted game. No Android paths or game data are bundled. */
final class ExtractedGame {
    private ExtractedGame() {}
    private static final long COMPILE_SPACE = 300L << 20;
    private static volatile long copied, total;

    static final class Entry {
        final String id, name;
        final boolean directory;
        final long size; // -1 if the provider cannot report it

        Entry(String id, String name, boolean directory, long size) {
            this.id = id;
            this.name = name;
            this.directory = directory;
            this.size = size;
        }
    }

    interface Source {
        List<Entry> children(String id) throws IOException;
        InputStream open(String id) throws IOException;
    }

    interface Check { String game(File directory); }

    static final class InvalidFolder extends IOException {}
    static final class Cancelled extends IOException {}
    static final class NoSpace extends IOException {
        final long needed, available;
        NoSpace(long needed, long available) { this.needed = needed; this.available = available; }
    }

    static long[] progress() { return new long[] {copied, total}; }
    static void resetProgress() { copied = total = 0; }

    /** Copies to staging, validates with the existing executable check, then publishes atomically. */
    static void importGame(Source source, String root, File staging, File game,
                           BooleanSupplier cancelled, Check check) throws IOException {
        resetProgress();
        List<Entry> dirs = source.children(root);
        Entry code = required(dirs, "code", true);
        Entry content = required(dirs, "content", true);
        Entry meta = required(dirs, "meta", true);
        List<Entry> codeFiles = source.children(code.id);
        Entry[] executable = {required(codeFiles, "cking.rpx", false),
                required(codeFiles, "app.xml", false), required(codeFiles, "cos.xml", false)};
        interrupted(cancelled);
        delete(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot create the game folder");
        try {
            // Only code needed by the existing pipeline; copy the asset trees without buffering them.
            long size = 0;
            for (Entry e : executable) size = addSize(size, e.size);
            size = addSize(size, treeSize(source, content, cancelled, new HashSet<>()));
            size = addSize(size, treeSize(source, meta, cancelled, new HashSet<>()));
            total = size < 0 ? 0 : size;
            long free = staging.getUsableSpace();
            long needed = size < 0 ? COMPILE_SPACE : addSize(size, COMPILE_SPACE);
            if (needed < 0 || free < needed) throw new NoSpace(needed < 0 ? Long.MAX_VALUE : needed, free);
            File localCode = new File(staging, "code");
            if (!localCode.mkdir()) throw new IOException("Cannot create code folder");
            byte[] buffer = new byte[1 << 16];
            for (Entry e : executable) copyFile(source, e, child(localCode, e.name), buffer, cancelled);
            String problem = check.game(staging);
            if (problem != null) throw new IOException(problem);
            copyTree(source, content, new File(staging, "content"), buffer, cancelled, new HashSet<>());
            copyTree(source, meta, new File(staging, "meta"), buffer, cancelled, new HashSet<>());
            interrupted(cancelled);
            install(staging, game);
        } finally {
            delete(staging); // failures never leave a partial game at the final path
        }
    }

    private static Entry required(List<Entry> entries, String name, boolean directory) throws InvalidFolder {
        Entry found = null;
        for (Entry e : entries) {
            if (!name.equals(e.name)) continue;
            if (found != null || e.directory != directory) throw new InvalidFolder();
            found = e;
        }
        if (found == null) throw new InvalidFolder();
        return found;
    }

    private static void interrupted(BooleanSupplier cancelled) throws Cancelled {
        if (cancelled.getAsBoolean()) throw new Cancelled();
    }

    private static long addSize(long a, long b) {
        return a < 0 || b < 0 || Long.MAX_VALUE - a < b ? -1 : a + b;
    }

    private static void enter(Entry directory, Set<String> ancestors) throws IOException {
        if (ancestors.size() >= 64 || !ancestors.add(directory.id))
            throw new IOException("The selected folder contains a directory loop or is too deep");
    }

    private static long treeSize(Source source, Entry dir, BooleanSupplier cancelled,
                                 Set<String> ancestors) throws IOException {
        interrupted(cancelled);
        enter(dir, ancestors);
        long size = 0;
        for (Entry e : source.children(dir.id)) {
            interrupted(cancelled);
            size = addSize(size, e.directory ? treeSize(source, e, cancelled, ancestors) : e.size);
        }
        ancestors.remove(dir.id);
        return size;
    }

    private static File child(File parent, String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0)
            throw new IOException("The selected folder contains an invalid file name");
        return new File(parent, name);
    }

    private static void copyTree(Source source, Entry dir, File target, byte[] buffer,
                                 BooleanSupplier cancelled, Set<String> ancestors) throws IOException {
        interrupted(cancelled);
        enter(dir, ancestors);
        if (!target.mkdir()) throw new IOException("Cannot create " + target.getName());
        Set<String> names = new HashSet<>();
        for (Entry e : source.children(dir.id)) {
            interrupted(cancelled);
            File file = child(target, e.name);
            if (!names.add(e.name)) throw new IOException("Duplicate file name: " + e.name);
            if (e.directory) copyTree(source, e, file, buffer, cancelled, ancestors);
            else copyFile(source, e, file, buffer, cancelled);
        }
        ancestors.remove(dir.id);
    }

    private static void copyFile(Source source, Entry entry, File file, byte[] buffer,
                                 BooleanSupplier cancelled) throws IOException {
        interrupted(cancelled);
        long free = file.getParentFile().getUsableSpace();
        if (entry.size >= 0 && free < entry.size) throw new NoSpace(entry.size, free);
        long written = 0;
        try (InputStream in = source.open(entry.id); FileOutputStream out = new FileOutputStream(file)) {
            if (in == null) throw new IOException("Cannot read " + entry.name);
            for (int n; (n = in.read(buffer)) != -1; ) {
                interrupted(cancelled);
                out.write(buffer, 0, n);
                written += n;
                copied += n;
            }
        }
        if (entry.size >= 0 && written != entry.size)
            throw new IOException("The file changed or could not be fully read: " + entry.name);
    }

    private static File previous(File game) { return new File(game.getParentFile(), game.getName() + "-before-setup"); }

    /** Recover if the process stopped between the two directory renames. */
    static void recover(File game) throws IOException {
        File previous = previous(game);
        if (!game.exists() && previous.exists() && !previous.renameTo(game))
            throw new IOException("Cannot restore the previous game folder");
    }

    /** Shared by disc extraction and folder import. Keep the old game if publication fails. */
    static void install(File staging, File game) throws IOException {
        recover(game);
        File previous = previous(game);
        delete(previous);
        boolean hadGame = game.exists();
        if (hadGame && !game.renameTo(previous)) throw new IOException("Cannot replace the previous game folder");
        if (!staging.renameTo(game)) {
            if (hadGame && !previous.renameTo(game)) throw new IOException("Cannot restore the previous game folder");
            throw new IOException("Cannot move the prepared game files into place");
        }
        delete(previous);
    }

    private static void delete(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot read the temporary game folder");
            for (File child : children) delete(child);
        }
        if (!file.delete()) throw new IOException("Cannot remove the temporary game folder");
    }
}
