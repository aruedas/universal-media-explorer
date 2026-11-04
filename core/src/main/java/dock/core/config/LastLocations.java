package dock.core.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this machine remembers between runs: the window's size and place,
 * the local commander's two directories, and where each session's panes
 * last stood — saved sites keep theirs in sessions.json; this shelf is the
 * fallback that also covers unsaved quick-connects. Everything is
 * best-effort: a missing or broken file reads as nothing remembered, and a
 * failed write never reaches the caller.
 */
public final class LastLocations {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The window's geometry: position, size, maximized flag. */
    public record WindowBox(int x, int y, int w, int h, boolean maximized) {}

    /** The local commander's panes and whether the second was unfolded. */
    public record LocalDirs(String left, String right, boolean secondPane) {}

    /** Where one session's panes last stood. */
    public record SessionDirs(String local, String remote) {}

    private LastLocations() {}

    public static synchronized WindowBox window() {
        return box("window") instanceof Map<?, ?> m ? windowOf(m) : null;
    }

    public static synchronized void putWindow(WindowBox box) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", box.x());
        m.put("y", box.y());
        m.put("w", box.w());
        m.put("h", box.h());
        m.put("maximized", box.maximized());
        store("window", m);
    }

    public static synchronized LocalDirs local() {
        if (box("local") instanceof Map<?, ?> m) {
            return new LocalDirs(str(m.get("left")), str(m.get("right")),
                    m.get("secondPane") instanceof Boolean b && b);
        }
        return null;
    }

    public static synchronized void putLocal(String left, String right, boolean secondPane) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("left", left);
        m.put("right", right);
        m.put("secondPane", secondPane);
        store("local", m);
    }

    public static synchronized SessionDirs session(String name) {
        Object sessions = box("sessions");
        if (sessions instanceof Map<?, ?> byName
                && byName.get(name) instanceof Map<?, ?> m) {
            return new SessionDirs(str(m.get("local")), str(m.get("remote")));
        }
        return null;
    }

    public static synchronized void putSession(String name, String localDir, String remoteDir) {
        Map<String, Object> all = load();
        Object sessions = all.get("sessions");
        Map<String, Object> byName = sessions instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("local", localDir);
        entry.put("remote", remoteDir);
        byName.put(name, entry);
        all.put("sessions", byName);
        write(all);
    }

    private static WindowBox windowOf(Map<?, ?> m) {
        try {
            return new WindowBox(((Number) m.get("x")).intValue(),
                    ((Number) m.get("y")).intValue(),
                    ((Number) m.get("w")).intValue(),
                    ((Number) m.get("h")).intValue(),
                    m.get("maximized") instanceof Boolean b && b);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Object box(String key) {
        return load().get(key);
    }

    /** Replaces one top-level entry and writes the file back. */
    private static void store(String key, Object value) {
        Map<String, Object> all = load();
        all.put(key, value);
        write(all);
    }

    private static Map<String, Object> load() {
        Path f = file();
        if (!Files.exists(f)) return new LinkedHashMap<>();
        try {
            return JSON.readValue(f.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private static void write(Map<String, Object> all) {
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(file().toFile(), all);
        } catch (IOException ignored) {
            // Best-effort memory; a full disk must not break the app.
        }
    }

    private static Path file() {
        return AppPaths.config().resolve("last_locations.json");
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }
}
