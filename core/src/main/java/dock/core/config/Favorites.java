package dock.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * The favorites shelf: directories bookmarked for quick access, saved as a
 * flat JSON array in favorites.json beside sessions.json.
 *
 * Paths are kept exactly as the user wrote them — the app navigates with
 * them verbatim — but a repeated entry folds: on Windows {@code F:\UP},
 * {@code f:\UP} and {@code F:\UP\} name one folder, so the shelf keeps the
 * first spelling and drops the rest. A missing or unreadable file reads as
 * empty rather than wiping what was there before it broke.
 */
public final class Favorites {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One favorite per line — the default printer keeps arrays inline, and a
     * shelf of 17 paths on one line is not something a person can read or
     * edit in place.
     */
    private static final com.fasterxml.jackson.databind.ObjectWriter WRITER = JSON.writer(
            new com.fasterxml.jackson.core.util.DefaultPrettyPrinter()
                    .withArrayIndenter(
                            com.fasterxml.jackson.core.util.DefaultIndenter.SYSTEM_LINEFEED_INSTANCE));

    private Favorites() {}

    private static Path file() {
        return AppPaths.config().resolve("favorites.json");
    }

    /** The shelf as saved; empty when there is no file yet or it can't be read. */
    public static synchronized List<String> load() {
        Path f = file();
        if (!Files.exists(f)) return new ArrayList<>();
        try {
            List<String> read = JSON.readValue(f.toFile(), new TypeReference<List<String>>() {});
            List<String> clean = new ArrayList<>();
            for (String p : read) {
                if (p != null && !p.isBlank()) clean.add(p);
            }
            return clean;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public static synchronized void save(List<String> paths) throws IOException {
        WRITER.writeValue(file().toFile(), paths.toArray(new String[0]));
    }

    /** Whether the shelf already holds this directory, spelling aside. */
    public static boolean contains(List<String> shelf, String path) {
        String k = key(path);
        for (String p : shelf) {
            if (key(p).equals(k)) return true;
        }
        return false;
    }

    /** Drops this directory however it is spelled; false when it wasn't there. */
    public static boolean remove(List<String> shelf, String path) {
        String k = key(path);
        for (int i = 0; i < shelf.size(); i++) {
            if (key(shelf.get(i)).equals(k)) {
                shelf.remove(i);
                return true;
            }
        }
        return false;
    }

    /**
     * Appends every path the shelf doesn't hold yet, in the order given, and
     * reports how many landed. The caller's list is the shelf — nothing is
     * written to disk here, so a batch (an import, say) saves once.
     */
    public static int addAll(List<String> shelf, Collection<String> paths) {
        int added = 0;
        HashSet<String> seen = new HashSet<>();
        for (String p : shelf) seen.add(key(p));
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            if (!seen.add(key(p))) continue;
            shelf.add(p);
            added++;
        }
        return added;
    }

    /** The identity of a favorite: case- and separator-insensitive. */
    static String key(String path) {
        String k = path.toLowerCase(Locale.ROOT).replace('/', '\\');
        int end = k.length();
        while (end > 0 && k.charAt(end - 1) == '\\') end--;
        k = k.substring(0, end);
        // A bare drive keeps its separator so "D:" and "D:\" are one folder.
        return k.length() == 2 && k.charAt(1) == ':' ? k + "\\" : k;
    }
}
