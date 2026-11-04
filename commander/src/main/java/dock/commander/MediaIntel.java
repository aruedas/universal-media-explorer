package dock.commander;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dock.core.config.ToolkitRoot;
import dock.core.fs.FileEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Media intelligence for the file panes — the Java side of SimpleCommander's
 * mc_intel: the PS engine's probe cache (probe_quality_cache.json, written
 * beside the toolkit scripts), the certification marker files, and poster
 * discovery. Read-only consumers; the scripts own the data.
 */
public final class MediaIntel {

    private MediaIntel() {}

    // ------------------------------------------------------------------
    // Probe entry (probe_quality_cache.json)
    // ------------------------------------------------------------------

    /** One probe/quality entry from the engine's cache. Field names follow
     *  the engine's JSON exactly. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ProbeEntry {
        @JsonProperty("Width") public int width;
        @JsonProperty("Height") public int height;
        @JsonProperty("Is4K") public boolean is4k;
        @JsonProperty("IsPremium") public boolean isPremium;
        @JsonProperty("IsHDR") public boolean isHdr;
        @JsonProperty("IsDolbyVision") public boolean isDolbyVision;
        @JsonProperty("IsAtmos") public boolean isAtmos;
        @JsonProperty("AudioCount") public int audioCount;
        @JsonProperty("AudioLangs") public List<String> audioLangs = List.of();
        @JsonProperty("SubCount") public int subCount;
        @JsonProperty("SubLangs") public List<String> subLangs = List.of();
        @JsonProperty("CertificationBadge") public String certificationBadge;
        @JsonProperty("VideoBitrateKbps") public long videoBitrateKbps;
        /** Null when never evaluated (bitrate module off or no data). */
        @JsonProperty("BitrateOk") public Boolean bitrateOk;
        @JsonProperty("BitrateReason") public String bitrateReason = "";
        @JsonProperty("CertReason") public String certReason = "";

        /** "3840x1608", or "" when unprobed. */
        public String resolution() {
            return width > 0 && height > 0 ? width + "x" + height : "";
        }

        /** Compact quality badge: "4K HDR DV Atmos Premium" / "1080p" / "". */
        public String qualityBadge() {
            List<String> parts = new ArrayList<>();
            if (is4k) parts.add("4K");
            else if (height >= 1440) parts.add("1440p");
            else if (height >= 1080) parts.add("1080p");
            else if (height >= 720) parts.add("720p");
            else if (height > 0) parts.add(height + "p");
            if (isDolbyVision) parts.add("DV");
            else if (isHdr) parts.add("HDR");
            if (isAtmos) parts.add("Atmos");
            if (isPremium) parts.add("Premium");
            return String.join(" ", parts);
        }

        /** "2x [eng,spa]", or "" when no audio track was probed. */
        public String audioLabel() {
            if (audioCount == 0) return "";
            return audioCount + "x [" + String.join(",", audioLangs) + "]";
        }

        /** "48.0M ✓" / "3.0M ✗" / "48.0M"; "" when no bitrate was probed. */
        public String bitrateLabel() {
            if (videoBitrateKbps == 0) return "";
            double mbps = videoBitrateKbps / 1000.0;
            if (Boolean.TRUE.equals(bitrateOk)) return String.format(Locale.ROOT, "%.1fM ✓", mbps);
            if (Boolean.FALSE.equals(bitrateOk)) return String.format(Locale.ROOT, "%.1fM ✗", mbps);
            return String.format(Locale.ROOT, "%.1fM", mbps);
        }

        /** "4x [eng,spa,fra,ita]", or "" when no subtitles were probed. */
        public String subsLabel() {
            if (subCount == 0) return "";
            return subCount + "x [" + String.join(",", subLangs) + "]";
        }
    }

    /** The loaded probe cache. The engine writes keys already lowercased,
     *  but lookups normalize anyway so case and separator never matter. */
    public static final class ProbeCache {
        private final Map<String, ProbeEntry> entries;

        ProbeCache(Map<String, ProbeEntry> entries) { this.entries = entries; }

        public ProbeEntry lookup(String path) {
            return entries.get(normalizeKey(path));
        }

        public int size() { return entries.size(); }
    }

    private static String normalizeKey(String path) {
        return path.toLowerCase(Locale.ROOT).replace('\\', '/');
    }

    /** Parses the engine's cache file (UTF-8 with a BOM, keys backslashed). */
    public static ProbeCache loadCache(Path scriptDir) {
        Path file = scriptDir.resolve(CACHE_FILE);
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.startsWith("\uFEFF")) raw = raw.substring(1);
            Map<String, ProbeEntry> in = new ObjectMapper().readValue(raw,
                    new TypeReference<LinkedHashMap<String, ProbeEntry>>() { });
            Map<String, ProbeEntry> out = new HashMap<>(in.size());
            for (Map.Entry<String, ProbeEntry> e : in.entrySet()) {
                if (e.getValue() != null) out.put(normalizeKey(e.getKey()), e.getValue());
            }
            return new ProbeCache(Map.copyOf(out));
        } catch (Exception e) {
            return new ProbeCache(Map.of());
        }
    }

    private static final String CACHE_FILE = "probe_quality_cache.json";

    private static final Object LOCK = new Object();
    private static Path cacheDir = defaultCacheDir();
    private static ProbeCache parsed;
    private static long parsedMtime;

    /** The engine writes its cache beside the toolkit scripts, so the probe
     *  columns read the directory the launcher resolves - and on a machine
     *  with no toolkit there is no cache to read. */
    private static Path defaultCacheDir() {
        return ToolkitRoot.directory().orElse(null);
    }

    /** The engine's probe cache, reloaded when the file changes on disk —
     *  cheap (one stat per call) so columns follow the scripts' runs live. */
    public static ProbeCache cache() {
        synchronized (LOCK) {
            Path dir = cacheDir;
            if (dir == null) return new ProbeCache(Map.of());
            try {
                long m = Files.getLastModifiedTime(dir.resolve(CACHE_FILE)).toMillis();
                if (parsed == null || m != parsedMtime) {
                    parsed = loadCache(dir);
                    parsedMtime = m;
                }
            } catch (IOException e) {
                if (parsed == null) parsed = new ProbeCache(Map.of());
            }
            return parsed;
        }
    }

    /** Tests point the cache at a fixture directory; null restores default. */
    static void setCacheDirForTest(Path dir) {
        synchronized (LOCK) {
            cacheDir = dir != null ? dir : defaultCacheDir();
            parsed = null;
            parsedMtime = 0;
        }
    }

    // ------------------------------------------------------------------
    // Certification markers
    // ------------------------------------------------------------------

    public enum CertStatus {
        NONE, ROKU, PREMIUM, ROKU_HEVC;

        public String badge() {
            return switch (this) {
                case ROKU -> "ROKU";
                case PREMIUM -> "PREMIUM";
                case ROKU_HEVC -> "ROKU HEVC";
                default -> "";
            };
        }
    }

    /** Check a folder for cert marker files. */
    public static CertStatus checkCertStatus(Path folder) {
        if (Files.isRegularFile(folder.resolve(".roku-hevc-certified"))) return CertStatus.ROKU_HEVC;
        if (Files.isRegularFile(folder.resolve(".premium-certified"))) return CertStatus.PREMIUM;
        if (Files.isRegularFile(folder.resolve(".roku-certified"))) return CertStatus.ROKU;
        return CertStatus.NONE;
    }

    /**
     * Display-oriented cert status. A folder's own marker wins; otherwise,
     * for a series whose season subfolders are ALL certified to the same
     * tier, that tier propagates up so the show folder shows the badge too.
     * A partially or mixedly certified show shows nothing rather than a
     * misleading badge.
     */
    public static CertStatus checkCertStatusDisplay(Path folder) {
        CertStatus own = checkCertStatus(folder);
        if (own != CertStatus.NONE) return own;
        List<Path> seasons;
        try (var kids = Files.list(folder)) {
            seasons = kids.filter(Files::isDirectory)
                    .filter(p -> isSeasonName(p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return CertStatus.NONE;
        }
        if (seasons.isEmpty()) return CertStatus.NONE;
        CertStatus shared = null;
        for (Path s : seasons) {
            CertStatus st = checkCertStatus(s);
            if (st == CertStatus.NONE) return CertStatus.NONE;
            if (shared == null) shared = st;
            else if (shared != st) return CertStatus.NONE;
        }
        return shared;
    }

    /** "S1", "S02", "S1234" — the season folder shape the engine certifies. */
    static boolean isSeasonName(String name) {
        name = name.trim();
        if (name.length() < 2 || name.length() > 5) return false;
        if (name.charAt(0) != 'S' && name.charAt(0) != 's') return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Poster discovery
    // ------------------------------------------------------------------

    /** Well-known poster file names, in priority order. */
    private static final String[] POSTER_NAMES = {
            "poster.jpg", "poster.jpeg", "poster.png", "poster.orig.jpg",
            "cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.png"};

    /** Find a poster image in the given directory, or null. */
    public static Path findPoster(Path dir) {
        for (String name : POSTER_NAMES) {
            Path p = dir.resolve(name);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Per-listing rows
    // ------------------------------------------------------------------

    /**
     * Everything one row shows in the media columns: the prebuilt cell
     * labels plus the numeric keys the sort uses. Rows without data — the
     * ".." row, unprobed files, remote listings — are {@link #EMPTY}.
     */
    public record RowIntel(String type, String resolution, String quality, String audio,
                           String bitrate, String estado, String estadoReason, String subs,
                           boolean poster, long pixels, int qualityRank,
                           int audioCount, long bitrateKbps, int subCount) {
        public static final RowIntel EMPTY =
                new RowIntel("", "", "", "", "", "", "", "", false, 0, 0, 0, 0, 0);
    }

    /**
     * Intel for one directory listing, keyed by entry name. Files read the
     * probe cache (their estado is the probe's certification badge); folders
     * get live cert-marker checks (with season propagation) and their own
     * poster hunt. A file row's poster lives in the listed directory, so it
     * is checked once for the whole listing. Any failure yields blanks —
     * intelligence must never break a listing.
     */
    public static Map<String, RowIntel> forListing(Path dir, List<FileEntry> entries,
                                                   ProbeCache cache) {
        try {
            boolean dirPoster = findPoster(dir) != null;
            Map<String, RowIntel> out = new HashMap<>(entries.size());
            for (FileEntry e : entries) {
                if (e == FileEntry.PARENT || "..".equals(e.name())) continue;
                try {
                    out.put(e.name(), row(dir, dirPoster, e, cache));
                } catch (Exception inner) {
                    out.put(e.name(), RowIntel.EMPTY);
                }
            }
            return Map.copyOf(out);
        } catch (Exception outer) {
            return Map.of();
        }
    }

    private static RowIntel row(Path dir, boolean dirPoster, FileEntry e, ProbeCache cache) {
        String full = dir.resolve(e.name()).toString();
        if (e.directory()) {
            CertStatus cert = checkCertStatusDisplay(dir.resolve(e.name()));
            boolean poster = findPoster(dir.resolve(e.name())) != null;
            return new RowIntel("dir", "", "", "", "", cert.badge(), "", "",
                    poster, 0, 0, 0, 0, 0);
        }
        ProbeEntry p = cache != null ? cache.lookup(full) : null;
        if (p == null) {
            return new RowIntel(ext(e.name()), "", "", "", "", "", "", "",
                    dirPoster, 0, 0, 0, 0, 0);
        }
        int rank = qualityRank(p);
        return new RowIntel(ext(e.name()), p.resolution(), p.qualityBadge(), p.audioLabel(),
                p.bitrateLabel(), p.certificationBadge == null ? "" : p.certificationBadge,
                p.certReason, p.subsLabel(), dirPoster,
                (long) p.width * p.height, rank, p.audioCount, p.videoBitrateKbps, p.subCount);
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    /** Tier dominates, then DV/HDR, then Atmos, then Premium — the badge
     *  order. Only a sort key. */
    private static int qualityRank(ProbeEntry p) {
        int tier;
        if (p.is4k) tier = 4;
        else if (p.height >= 1440) tier = 3;
        else if (p.height >= 1080) tier = 2;
        else if (p.height >= 720) tier = 1;
        else tier = 0;
        return tier * 100
                + (p.isDolbyVision ? 30 : p.isHdr ? 20 : 0)
                + (p.isAtmos ? 10 : 0)
                + (p.isPremium ? 5 : 0);
    }
}
