package org.wwhdrecomp.app;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** No game files: all inputs are synthetic strings or generated streams in a temporary directory. */
public final class ExtractedGameTest {
    private static int passed;

    private static final class Source implements ExtractedGame.Source {
        final Map<String, List<ExtractedGame.Entry>> dirs = new HashMap<>();
        final Map<String, byte[]> data = new HashMap<>();
        boolean unknownSize, failAsset;
        int assetOpens, maxRead;
        long generatedSize;
        AtomicBoolean cancelOnRead;

        Source() {
            dirs.put("root", new ArrayList<>());
            folder("root", "code"); folder("root", "content"); folder("root", "meta");
            file("code", "cking.rpx", "synthetic-executable");
            file("code", "app.xml", "synthetic-app"); file("code", "cos.xml", "synthetic-cos");
            folder("content", "assets"); file("content/assets", "test.bin", "synthetic-asset");
            file("meta", "meta.xml", "synthetic-meta");
            file("root", "common.key", "ignored-test-marker");
            file("code", "unneeded.bin", "ignored-test-marker");
        }

        void folder(String parent, String name) {
            String id = parent.equals("root") ? name : parent + "/" + name;
            dirs.get(parent).add(new ExtractedGame.Entry(id, name, true, 0));
            dirs.put(id, new ArrayList<>());
        }

        void file(String parent, String name, String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            String id = parent + "/" + name;
            dirs.get(parent).add(new ExtractedGame.Entry(id, name, false, bytes.length));
            data.put(id, bytes);
        }

        @Override
        public List<ExtractedGame.Entry> children(String id) throws IOException {
            if (!dirs.containsKey(id)) throw new IOException("unreadable folder");
            List<ExtractedGame.Entry> entries = new ArrayList<>();
            for (ExtractedGame.Entry e : dirs.get(id)) {
                long size = unknownSize ? -1 : e.size;
                if (e.name.equals("test.bin") && generatedSize > 0) size = generatedSize;
                entries.add(new ExtractedGame.Entry(e.id, e.name, e.directory, size));
            }
            return entries;
        }

        @Override
        public InputStream open(String id) throws IOException {
            if (id.startsWith("content/")) {
                assetOpens++;
                if (failAsset) throw new IOException("synthetic provider failure");
                if (generatedSize > 0) return new InputStream() {
                    long left = generatedSize;
                    @Override public int read() { throw new AssertionError("must use a bounded buffer"); }
                    @Override public int read(byte[] buffer, int offset, int length) {
                        maxRead = Math.max(maxRead, length);
                        if (left == 0) return -1;
                        int count = (int) Math.min(left, length);
                        java.util.Arrays.fill(buffer, offset, offset + count, (byte) 7);
                        left -= count;
                        if (cancelOnRead != null) cancelOnRead.set(true);
                        return count;
                    }
                };
            }
            if (!data.containsKey(id)) throw new IOException("unknown test file");
            return new ByteArrayInputStream(data.get(id));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Path temp() throws IOException { return Files.createTempDirectory("wwhd-import-test-"); }
    private static void cleanup(Path path) throws IOException {
        try (var files = Files.walk(path)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    private static void run(Source source, Path base, AtomicBoolean cancel, ExtractedGame.Check check) throws IOException {
        ExtractedGame.importGame(source, "root", base.resolve("game-importing").toFile(),
                base.resolve("game").toFile(), cancel::get, check);
    }

    private static ExtractedGame.Check valid() {
        return dir -> {
            require(new File(dir, "code/cking.rpx").isFile(), "executable was not prepared before validation");
            require(new File(dir, "code/app.xml").isFile(), "app.xml missing before validation");
            require(new File(dir, "code/cos.xml").isFile(), "cos.xml missing before validation");
            require(!new File(dir, "content").exists(), "validate before copying large assets");
            return null;
        };
    }

    private static void success(boolean unknown) throws IOException {
        Path base = temp();
        try {
            Source source = new Source(); source.unknownSize = unknown;
            Files.createDirectories(base.resolve("game")); Files.writeString(base.resolve("game/old"), "old");
            run(source, base, new AtomicBoolean(), valid());
            require(Files.readString(base.resolve("game/content/assets/test.bin")).equals("synthetic-asset"), "asset changed");
            require(Files.exists(base.resolve("game/meta/meta.xml")), "meta not copied");
            require(!Files.exists(base.resolve("game/common.key")), "root files copied");
            require(!Files.exists(base.resolve("game/code/unneeded.bin")), "unneeded code copied");
            require(!Files.exists(base.resolve("game/old")), "old game not replaced");
            require(!Files.exists(base.resolve("game-importing")), "staging remains");
            long[] p = ExtractedGame.progress();
            require(p[0] > 0 && (unknown ? p[1] == 0 : p[0] == p[1]), "wrong copy progress");
            passed++;
        } finally { cleanup(base); }
    }

    private static void missingStructure() throws IOException {
        for (String name : new String[] {"code", "content", "meta", "cking.rpx", "app.xml", "cos.xml"}) {
            for (boolean wrongType : new boolean[] {false, true}) {
                Path base = temp();
                try {
                    Source source = new Source();
                    String parent = name.contains(".") ? "code" : "root";
                    ExtractedGame.Entry old = source.dirs.get(parent).stream().filter(e -> e.name.equals(name)).findFirst().orElseThrow();
                    source.dirs.get(parent).remove(old);
                    if (wrongType) source.dirs.get(parent).add(new ExtractedGame.Entry(old.id, name, !old.directory, old.size));
                    try { run(source, base, new AtomicBoolean(), valid()); throw new AssertionError("accepted missing/wrong " + name); }
                    catch (ExtractedGame.InvalidFolder expected) { passed++; }
                    require(!Files.exists(base.resolve("game")), "invalid structure published");
                } finally { cleanup(base); }
            }
        }
    }

    private static void failedCopy(boolean cancel) throws IOException {
        Path base = temp();
        try {
            Source source = new Source();
            AtomicBoolean cancelled = new AtomicBoolean();
            source.failAsset = !cancel;
            if (cancel) { source.generatedSize = 2L << 20; source.cancelOnRead = cancelled; }
            Files.createDirectories(base.resolve("game")); Files.writeString(base.resolve("game/old"), "keep");
            try { run(source, base, cancelled, valid()); throw new AssertionError("failure accepted"); }
            catch (IOException expected) { require(!cancel || expected instanceof ExtractedGame.Cancelled, "wrong cancel result"); }
            require(Files.readString(base.resolve("game/old")).equals("keep"), "previous game lost on failure");
            require(!Files.exists(base.resolve("game-importing")), "partial copy remains");
            passed++;
        } finally { cleanup(base); }
    }

    private static void unsupportedRelease() throws IOException {
        Path base = temp();
        try {
            Source source = new Source();
            try { run(source, base, new AtomicBoolean(), dir -> "unsupported test executable"); throw new AssertionError("unsupported executable accepted"); }
            catch (IOException expected) { require(expected.getMessage().equals("unsupported test executable"), "validation error lost"); }
            require(source.assetOpens == 0, "copied assets for unsupported game");
            require(!Files.exists(base.resolve("game-importing")), "invalid executable remains");
            passed++;
        } finally { cleanup(base); }
    }

    private static void largeStream() throws IOException {
        Path base = temp();
        try {
            Source source = new Source(); source.generatedSize = 40L << 20;
            run(source, base, new AtomicBoolean(), valid());
            require(Files.size(base.resolve("game/content/assets/test.bin")) == source.generatedSize, "stream truncated");
            require(source.maxRead <= 65536, "unbounded stream read");
            passed++;
        } finally { cleanup(base); }
    }

    private static void unsafeNames() throws IOException {
        for (String name : new String[] {"../escape", "..", ".", "/absolute", "bad\\path", "", "test.bin"}) {
            Path base = temp();
            try {
                Source source = new Source(); source.file("content/assets", name, "synthetic");
                try { run(source, base, new AtomicBoolean(), valid()); throw new AssertionError("accepted unsafe/duplicate name " + name); }
                catch (IOException expected) { passed++; }
                require(!Files.exists(base.resolve("game")), "unsafe names published");
            } finally { cleanup(base); }
        }
    }

    private static void publicationAndRecovery() throws IOException {
        Path base = temp();
        try {
            File game = base.resolve("game").toFile();
            Files.createDirectories(game.toPath()); Files.writeString(game.toPath().resolve("old"), "keep");
            try { ExtractedGame.install(base.resolve("missing-staging").toFile(), game); throw new AssertionError("invalid publication accepted"); }
            catch (IOException expected) { require(Files.exists(game.toPath().resolve("old")), "failed rename lost old game"); }
            File previous = base.resolve("game-before-setup").toFile();
            require(game.renameTo(previous), "test recovery setup failed");
            ExtractedGame.recover(game);
            require(Files.exists(game.toPath().resolve("old")), "interrupted publication not recovered");
            passed++;
        } finally { cleanup(base); }
    }

    private static void changedFileAndLoop() throws IOException {
        for (boolean loop : new boolean[] {false, true}) {
            Path base = temp();
            try {
                Source source = new Source();
                if (loop) source.dirs.get("content/assets").add(new ExtractedGame.Entry("content", "loop", true, 0));
                else source.data.put("content/assets/test.bin", new byte[0]);
                try { run(source, base, new AtomicBoolean(), valid()); throw new AssertionError("changed file/loop accepted"); }
                catch (IOException expected) { passed++; }
                require(!Files.exists(base.resolve("game")), "broken input published");
                require(!Files.exists(base.resolve("game-importing")), "broken staging remains");
            } finally { cleanup(base); }
        }
    }

    private static void noSpace() throws IOException {
        Path base = temp();
        try {
            Source source = new Source(); source.generatedSize = Long.MAX_VALUE;
            try { run(source, base, new AtomicBoolean(), valid()); throw new AssertionError("size overflow accepted"); }
            catch (ExtractedGame.NoSpace expected) { passed++; }
            require(source.assetOpens == 0, "oversized input copied");
            require(!Files.exists(base.resolve("game")), "oversized input published");
        } finally { cleanup(base); }
    }

    public static void main(String[] args) throws Exception {
        success(false); success(true); missingStructure(); failedCopy(false); failedCopy(true);
        unsupportedRelease(); largeStream(); unsafeNames(); publicationAndRecovery();
        changedFileAndLoop(); noSpace();
        System.out.println("ExtractedGame: " + passed + " checks passed (synthetic inputs only)");
    }
}
