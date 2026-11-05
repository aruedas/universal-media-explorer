package dock.ui;

import dock.core.config.ToolkitRoot;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads {@code m3u_server_methods.ini} — the media-server fleet and
 * respaldo-drive definitions shared with SimpleCommander and the PowerShell
 * toolkit. Only the keys the sidebar needs are extracted; the rest of the
 * INI (naming policy, codec policy, etc.) is ignored.
 *
 * The file lives in the toolkit directory, so its location comes from
 * {@link ToolkitRoot} rather than a path baked in here: an install with no
 * toolkit simply has no fleet, and the sidebar falls back to the drives it
 * does know about.
 */
public final class IniConfig {

    public record MediaServer(int index, String name, String host, String home,
                              int port, boolean indexOnly, boolean moviesEnabled,
                              boolean seriesEnabled, String user) {}

    public record RespaldoDrive(int index, String root, boolean external,
                                boolean moviesEnabled, boolean seriesEnabled) {}

    /** File name looked for in the toolkit directory. */
    public static final String INI_FILE = "m3u_server_methods.ini";

    private final List<MediaServer> servers;
    private final List<RespaldoDrive> respaldoDrives;

    private IniConfig(List<MediaServer> servers, List<RespaldoDrive> respaldoDrives) {
        this.servers = servers;
        this.respaldoDrives = respaldoDrives;
    }

    public List<MediaServer> servers() { return servers; }
    public List<RespaldoDrive> respaldoDrives() { return respaldoDrives; }

    /** The fleet file, when this machine has one. */
    public static Optional<Path> iniFile() {
        return ToolkitRoot.file(INI_FILE);
    }

    /** Reads the INI; returns an empty config on any I/O failure, including
     *  no toolkit directory at all. */
    public static IniConfig load() {
        return iniFile().map(IniConfig::load)
                .orElseGet(() -> new IniConfig(List.of(), List.of()));
    }

    static IniConfig load(Path path) {
        if (!Files.exists(path)) return new IniConfig(List.of(), List.of());
        Map<String, String> kv = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(path)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            return new IniConfig(List.of(), List.of());
        }

        List<MediaServer> servers = new ArrayList<>();
        List<Integer> order = parseOrder(kv.get("serverOrder"));
        if (order.isEmpty()) {
            for (int i = 1; i <= 20; i++) order.add(i);
        }
        for (int idx : order) {
            String host = kv.getOrDefault("site" + idx, "").trim();
            if (host.isEmpty()) continue;
            String name = kv.getOrDefault("name" + idx, "Server " + idx).trim();
            String home = kv.getOrDefault("home" + idx, "/").trim();
            int port = parseInt(kv.get("port" + idx), 1000);
            boolean indexOnly = parseBool(kv.get("serverIndexOnly" + idx), false);
            // First-load defaults follow the server's role: an index-only
            // server is never a copy target, so its M/S start unchecked; a
            // regular serving host starts with both. An explicit key in the
            // INI is the operator's choice and always wins.
            boolean movies = parseBool(kv.get("serverMoviesEnabled" + idx), !indexOnly);
            boolean series = parseBool(kv.get("serverSeriesEnabled" + idx), !indexOnly);
            // The SSH user lives here, in the operator's own file, and never
            // in the source: a published repo must not name a fleet account.
            // An absent key is not defaulted to a guess - see Sidebar.
            String user = kv.getOrDefault("user" + idx, "").trim();
            servers.add(new MediaServer(idx, name, host, home, port,
                    indexOnly, movies, series, user));
        }

        List<RespaldoDrive> drives = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String root = kv.get("respaldoDrive" + i);
            if (root == null || root.isBlank()) continue;
            root = root.trim();
            boolean external = parseBool(kv.get("respaldoExternal" + i), false);
            boolean movies = parseBool(kv.get("respaldoMoviesEnabled" + i), true);
            boolean series = parseBool(kv.get("respaldoSeriesEnabled" + i), true);
            drives.add(new RespaldoDrive(i, root, external, movies, series));
        }

        return new IniConfig(servers, drives);
    }

    /** Flips one media-server copy switch in the INI, in place: an existing
     *  key is rewritten, an absent one is inserted right after the server's
     *  site line. Comments, spellings, the file's line endings and its
     *  encoding are preserved — the file is shared with SimpleCommander and
     *  the PowerShell toolkit. */
    public static void setServerMoviesEnabled(int index, boolean value) throws IOException {
        setServerToggle(requireIni(), index, "serverMoviesEnabled", value);
    }

    public static void setServerSeriesEnabled(int index, boolean value) throws IOException {
        setServerToggle(requireIni(), index, "serverSeriesEnabled", value);
    }

    /** A checkbox that cannot be honoured says so rather than writing a copy
     *  switch into a file that is not there. */
    private static Path requireIni() throws IOException {
        return iniFile().orElseThrow(() -> new IOException(
                INI_FILE + " is not in the toolkit directory (searched "
                        + ToolkitRoot.describeSearch() + ")"));
    }

    static void setServerToggle(Path ini, int index, String keyPrefix, boolean value)
            throws IOException {
        byte[] raw = Files.readAllBytes(ini);
        boolean bom = raw.length >= 3 && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF;
        String text = new String(raw, bom ? 3 : 0, raw.length - (bom ? 3 : 0),
                java.nio.charset.StandardCharsets.UTF_8);
        String eol = text.contains("\r\n") ? "\r\n" : "\n";

        // Split keeping every line's own terminator: the last line may carry
        // none, and a stray \r must not leak into the key match below.
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            int nl = text.indexOf('\n', i);
            if (nl < 0) { lines.add(text.substring(i)); break; }
            lines.add(text.substring(i, nl + 1));
            i = nl + 1;
        }

        String key = keyPrefix + index;
        boolean replaced = false;
        for (int j = 0; j < lines.size() && !replaced; j++) {
            String bare = lines.get(j).strip();
            if (bare.startsWith(key) && bare.indexOf('=') == key.length()) {
                String term = lines.get(j).endsWith("\n") ? eol : "";
                lines.set(j, key + "=" + value + term);
                replaced = true;
            }
        }
        if (!replaced) {
            int after = -1;
            String siteKey = "site" + index;
            for (int j = 0; j < lines.size(); j++) {
                String bare = lines.get(j).strip();
                if (bare.startsWith(siteKey) && bare.indexOf('=') == siteKey.length()) after = j;
            }
            if (after < 0) throw new IOException("no " + siteKey + " in " + ini);
            lines.add(after + 1, key + "=" + value + eol);
        }

        Path tmp = Files.createTempFile(ini.getParent(), ini.getFileName().toString(), ".tmp");
        Files.writeString(tmp, (bom ? "\uFEFF" : "") + String.join("", lines),
                java.nio.charset.StandardCharsets.UTF_8);
        try {
            Files.move(tmp, ini, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, ini, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Integer> parseOrder(String value) {
        List<Integer> out = new ArrayList<>();
        if (value == null || value.isBlank()) return out;
        for (String part : value.split(",")) {
            try { out.add(Integer.parseInt(part.trim())); }
            catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static boolean parseBool(String value, boolean fallback) {
        if (value == null) return fallback;
        String v = value.trim().toLowerCase();
        if (v.equals("true") || v.equals("1") || v.equals("yes")) return true;
        if (v.equals("false") || v.equals("0") || v.equals("no")) return false;
        return fallback;
    }
}
