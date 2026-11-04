package dock.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the locations the operator already keeps, so the new app starts with
 * a shelf full of folders instead of an empty sidebar.
 *
 * Two owners, two formats:
 *
 * <ul><li>SimpleCommander's sidebar Favorites, which live in
 * {@code settings.toml} under {@code [session]} as an array of literal
 * strings. That app writes its settings next to the executable whenever the
 * folder is writable (portable mode), so every install location this toolkit
 * is known to use is probed and the most recently written file wins — newest
 * is the list the live app is actually showing.</li>
 *
 * <li>Media Commander's saved locations, {@code saved_locations.json} in the
 * toolkit directory
 * — a flat array of paths that SimpleCommander itself falls back to. Written
 * by PowerShell, which means a UTF-8 BOM up front and, when exactly one
 * location is saved, a bare string instead of an array.</li></ul>
 *
 * Nothing is invented here: a path the other app doesn't list is never added,
 * and a file that can't be read contributes nothing.
 */
public final class SimpleCommanderImport {

    /** One location and the app that owns it. */
    public record Favorite(String path, String origin) {}

    /** What an import did, for the message the UI and the CLI both show. */
    public record Result(int added, int alreadyHere, String source) {
        public String summary() {
            if (added == 0) {
                return alreadyHere == 0
                        ? "Nothing to import — no favorites found in SimpleCommander."
                        : "Nothing new — " + alreadyHere + " favorite"
                                + (alreadyHere == 1 ? "" : "s") + " already on the shelf.";
            }
            return "Imported " + added + " favorite" + (added == 1 ? "" : "s")
                    + (alreadyHere > 0 ? " (" + alreadyHere + " already on the shelf)" : "")
                    + " — " + source;
        }
    }

    private static final String SC = "SimpleCommander";
    private static final String TOOLKIT = "Media Commander";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path scripts() {
        return ToolkitRoot.directory().orElse(null);
    }

    private SimpleCommanderImport() {}

    /**
     * Candidate {@code settings.toml} files, in the order they should be
     * probed: the installed location first, then this toolkit's checkouts,
     * where a portable build keeps its settings beside its exe. With no
     * toolkit directory there is no checkout to probe.
     */
    public static List<Path> settingsCandidates() {
        List<Path> out = new ArrayList<>();
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            out.add(Path.of(appData, "SimpleCommander", "settings.toml"));
        }
        Path root = scripts();
        if (root == null) return out;
        Path checkout = root.resolve("SimpleCommander");
        out.add(checkout.resolve("target").resolve("release").resolve("settings.toml"));
        out.add(checkout.resolve("target").resolve("debug").resolve("settings.toml"));
        out.add(checkout.resolve("dist").resolve("SimpleCommander").resolve("settings.toml"));
        return out;
    }

    /** SimpleCommander's saved locations, or null when this machine has no
     *  toolkit directory to hold them. */
    public static Path savedLocationsFile() {
        Path root = scripts();
        return root == null ? null : root.resolve("saved_locations.json");
    }

    /** Imports into the caller's shelf; the caller decides when to save. */
    public static Result importInto(List<String> shelf) {
        Path settingsFile = newest(existing(settingsCandidates()));
        Path saved = savedLocationsFile();
        return importInto(shelf, settingsFile, saved != null && Files.exists(saved) ? saved : null);
    }

    /** Explicit files — what the discovery above hands in, and what tests use. */
    public static Result importInto(List<String> shelf, Path settingsToml, Path savedLocations) {
        List<Favorite> found = read(settingsToml, savedLocations);
        int added = Favorites.addAll(shelf, found.stream().map(Favorite::path).toList());
        return new Result(added, found.size() - added, describe(found));
    }

    /** Explicit files — the seam tests read fixtures through. */
    public static List<Favorite> read(Path settingsToml, Path savedLocations) {
        List<Favorite> out = new ArrayList<>();
        if (settingsToml != null) {
            for (String p : readTomlFavorites(settingsToml)) out.add(new Favorite(p, SC));
        }
        if (savedLocations != null) {
            for (String p : readSavedLocations(savedLocations)) out.add(new Favorite(p, TOOLKIT));
        }
        return out;
    }

    // ── settings.toml ────────────────────────────────────────────────

    /**
     * The {@code [session]} table's {@code favorites} array. Narrow on
     * purpose: one table, one key, quoted strings — enough to read what this
     * app's own serializer writes, and nothing else in the file (which holds
     * credentials) is even looked at.
     */
    static List<String> readTomlFavorites(Path settingsToml) {
        List<String> out = new ArrayList<>();
        String body = favoritesArray(settingsToml);
        if (body == null) return out;
        for (String s : stringsOf(body)) {
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    /** The text between the brackets of {@code favorites = [ … ]}. */
    private static String favoritesArray(Path settingsToml) {
        StringBuilder body = new StringBuilder();
        boolean inSession = false, collecting = false;
        try {
            for (String line : Files.readAllLines(settingsToml, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("[")) {
                    // Any table header closes the one before it; a collection
                    // left unclosed at the next header is a broken file.
                    if (collecting) break;
                    inSession = t.equals("[session]");
                    continue;
                }
                if (!inSession || t.isEmpty()) continue;
                if (!collecting) {
                    if (!t.startsWith("favorites")) continue;
                    int open = t.indexOf('[');
                    if (open < 0) continue;
                    collecting = true;
                    t = t.substring(open + 1);
                }
                int close = t.indexOf(']');
                if (close >= 0) {
                    body.append(t, 0, close);
                    break;
                }
                body.append(t);
            }
        } catch (IOException e) {
            return null;
        }
        return collecting ? body.toString() : null;
    }

    /**
     * Every quoted span in a TOML array body. Single quotes are literal — a
     * backslash means a backslash, which is the whole point for Windows
     * paths; double quotes unescape.
     */
    static List<String> stringsOf(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quote == 0) {
                if (c == '\'' || c == '"') {
                    quote = c;
                    cur.setLength(0);
                } else if (c == '#') {
                    break;   // a comment runs to the end of the line
                }
                continue;
            }
            if (c == '\\' && quote == '"' && i + 1 < body.length()) {
                char next = body.charAt(++i);
                cur.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    default -> next;      // \\ and \" arrive here; anything else stays literal
                });
                continue;
            }
            if (c == quote) {
                out.add(cur.toString());
                quote = 0;
                continue;
            }
            cur.append(c);
        }
        return out;
    }

    // ── saved_locations.json ─────────────────────────────────────────

    static List<String> readSavedLocations(Path file) {
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
        if (raw.startsWith("\uFEFF")) raw = raw.substring(1);
        raw = raw.trim();
        if (raw.isEmpty()) return List.of();
        try {
            if (raw.charAt(0) != '[') {
                // One saved location: PowerShell's ConvertTo-Json unwraps it.
                String single = JSON.readValue(raw, String.class);
                return single.isBlank() ? List.of() : List.of(single);
            }
            List<String> paths = JSON.readValue(raw,
                    JSON.getTypeFactory().constructCollectionType(List.class, String.class));
            paths.removeIf(p -> p == null || p.isBlank());
            return paths;
        } catch (IOException e) {
            return List.of();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static List<Path> existing(List<Path> candidates) {
        return candidates.stream().filter(Files::exists).toList();
    }

    /** Last written — the settings the running app is actually updating. */
    private static Path newest(List<Path> files) {
        Path best = null;
        long bestTime = -1;
        for (Path p : files) {
            try {
                long m = Files.getLastModifiedTime(p).toMillis();
                if (m > bestTime) {
                    bestTime = m;
                    best = p;
                }
            } catch (IOException e) {
                // Unreadable metadata: keep looking.
            }
        }
        return best;
    }

    /** Who contributed how many, in the order the shelf received them. */
    private static String describe(List<Favorite> found) {
        long fromSc = found.stream().filter(f -> f.origin().equals(SC)).count();
        long fromToolkit = found.size() - fromSc;
        List<String> parts = new ArrayList<>();
        if (fromSc > 0) parts.add(fromSc + " from " + SC);
        if (fromToolkit > 0) parts.add(fromToolkit + " from " + TOOLKIT);
        return String.join(", ", parts);
    }
}
