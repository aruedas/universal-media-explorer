package dock.commander;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The media-intelligence port: the engine's probe cache parses with its BOM
 * and backslashed keys, cert markers follow SimpleCommander's priority and
 * season propagation, posters keep their precedence, and the intelligence
 * columns sort by numeric facts.
 */
class MediaIntelTest {

    private Path dir;

    @AfterEach
    void tearDown() throws Exception {
        MediaIntel.setCacheDirForTest(null);
        if (dir != null) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (java.io.IOException e) { throw new RuntimeException(e); }
                });
            }
        }
    }

    // ---- probe entry labels ----

    @Test
    void probeEntryLabelsMatchTheEngine() {
        MediaIntel.ProbeEntry e = new MediaIntel.ProbeEntry();
        e.width = 3840; e.height = 1608; e.is4k = true; e.isHdr = true; e.isAtmos = true;
        e.audioCount = 2; e.audioLangs = List.of("eng", "spa");
        e.subCount = 4; e.subLangs = List.of("eng", "spa", "fra", "ita");
        e.certificationBadge = "ROKU CERTIFIED";
        e.videoBitrateKbps = 48000; e.bitrateOk = Boolean.TRUE;
        assertEquals("3840x1608", e.resolution());
        assertEquals("4K HDR Atmos", e.qualityBadge());
        assertEquals("2x [eng,spa]", e.audioLabel());
        assertEquals("4x [eng,spa,fra,ita]", e.subsLabel());
        assertEquals("48.0M ✓", e.bitrateLabel());
    }

    @Test
    void badgeTiersAndBitrateMarks() {
        MediaIntel.ProbeEntry dv = new MediaIntel.ProbeEntry();
        dv.width = 3840; dv.height = 1608; dv.is4k = true;
        dv.isDolbyVision = true; dv.isHdr = true; dv.isPremium = true;
        assertEquals("4K DV Premium", dv.qualityBadge());

        MediaIntel.ProbeEntry tall = new MediaIntel.ProbeEntry();
        tall.height = 1440;
        assertEquals("1440p", tall.qualityBadge());

        MediaIntel.ProbeEntry plain = new MediaIntel.ProbeEntry();
        plain.height = 1080;
        assertEquals("1080p", plain.qualityBadge());

        MediaIntel.ProbeEntry odd = new MediaIntel.ProbeEntry();
        odd.height = 804;
        assertEquals("720p", odd.qualityBadge());

        MediaIntel.ProbeEntry low = new MediaIntel.ProbeEntry();
        low.height = 480;
        assertEquals("480p", low.qualityBadge());

        MediaIntel.ProbeEntry blank = new MediaIntel.ProbeEntry();
        assertEquals("", blank.qualityBadge());
        assertEquals("", blank.resolution());
        assertEquals("", blank.audioLabel());
        assertEquals("", blank.subsLabel());
        assertEquals("", blank.bitrateLabel());

        MediaIntel.ProbeEntry rejected = new MediaIntel.ProbeEntry();
        rejected.videoBitrateKbps = 3000; rejected.bitrateOk = Boolean.FALSE;
        assertEquals("3.0M ✗", rejected.bitrateLabel());

        MediaIntel.ProbeEntry unevaluated = new MediaIntel.ProbeEntry();
        unevaluated.videoBitrateKbps = 3000;
        assertEquals("3.0M", unevaluated.bitrateLabel());
    }

    // ---- probe cache parsing ----

    @Test
    void cacheParsesBomBackslashesAndCase() throws Exception {
        dir = Files.createTempDirectory("dock-media-intel");
        String json = """
                {
                  "C:\\\\Media\\\\Movie (2020)\\\\movie.mkv": {"Width": 1920, "Height": 804, "IsHDR": true},
                  "C:\\\\Media\\\\broken.mkv": null
                }
                """;
        Files.write(dir.resolve("probe_quality_cache.json"),
                ("\uFEFF" + json).getBytes(StandardCharsets.UTF_8));
        MediaIntel.setCacheDirForTest(dir);
        MediaIntel.ProbeCache cache = MediaIntel.cache();
        assertEquals(1, cache.size(), "null entries are skipped");
        MediaIntel.ProbeEntry hit = cache.lookup("c:/MEDIA/MOVIE (2020)/movie.mkv");
        assertTrue(hit != null, "lookup normalizes case and separators");
        assertEquals("1920x804", hit.resolution());
        assertNull(cache.lookup("c:/media/other.mkv"));
    }

    @Test
    void cacheSurvivesAMissingFile() throws Exception {
        dir = Files.createTempDirectory("dock-media-intel-empty");
        MediaIntel.setCacheDirForTest(dir);
        assertEquals(0, MediaIntel.cache().size());
    }

    // ---- cert markers ----

    @Test
    void certMarkerPriority() throws Exception {
        dir = Files.createTempDirectory("dock-media-cert");
        assertEquals(MediaIntel.CertStatus.NONE, MediaIntel.checkCertStatus(dir));
        Files.writeString(dir.resolve(".roku-certified"), "");
        assertEquals(MediaIntel.CertStatus.ROKU, MediaIntel.checkCertStatus(dir));
        Files.writeString(dir.resolve(".premium-certified"), "");
        assertEquals(MediaIntel.CertStatus.PREMIUM, MediaIntel.checkCertStatus(dir));
        Files.writeString(dir.resolve(".roku-hevc-certified"), "");
        assertEquals(MediaIntel.CertStatus.ROKU_HEVC, MediaIntel.checkCertStatus(dir));
    }

    @Test
    void certDisplayPropagatesOnlyFullyCertifiedSeasons() throws Exception {
        dir = Files.createTempDirectory("dock-media-cert-display");
        Files.createDirectories(dir.resolve("S01"));
        Files.createDirectories(dir.resolve("S02"));
        assertEquals(MediaIntel.CertStatus.NONE, MediaIntel.checkCertStatusDisplay(dir));

        Files.writeString(dir.resolve("S01").resolve(".roku-certified"), "");
        assertEquals(MediaIntel.CertStatus.NONE, MediaIntel.checkCertStatusDisplay(dir));

        Files.writeString(dir.resolve("S02").resolve(".roku-certified"), "");
        assertEquals(MediaIntel.CertStatus.ROKU, MediaIntel.checkCertStatusDisplay(dir));

        Files.deleteIfExists(dir.resolve("S02").resolve(".roku-certified"));
        Files.writeString(dir.resolve("S02").resolve(".premium-certified"), "");
        assertEquals(MediaIntel.CertStatus.NONE, MediaIntel.checkCertStatusDisplay(dir));

        Files.writeString(dir.resolve(".premium-certified"), "");
        assertEquals(MediaIntel.CertStatus.PREMIUM, MediaIntel.checkCertStatusDisplay(dir));
    }

    @Test
    void seasonFolderShape() {
        assertTrue(MediaIntel.isSeasonName("S1"));
        assertTrue(MediaIntel.isSeasonName("S02"));
        assertTrue(MediaIntel.isSeasonName("S1234"));
        assertTrue(MediaIntel.isSeasonName("s01"));
        assertFalse(MediaIntel.isSeasonName("Season1"));
        assertFalse(MediaIntel.isSeasonName("S"));
        assertFalse(MediaIntel.isSeasonName("S01x"));
        assertFalse(MediaIntel.isSeasonName(""));
    }

    // ---- posters ----

    @Test
    void posterPriority() throws Exception {
        dir = Files.createTempDirectory("dock-media-poster");
        assertNull(MediaIntel.findPoster(dir));
        Files.writeString(dir.resolve("cover.jpg"), "x");
        assertEquals(dir.resolve("cover.jpg"), MediaIntel.findPoster(dir));
        Files.writeString(dir.resolve("poster.jpg"), "x");
        assertEquals(dir.resolve("poster.jpg"), MediaIntel.findPoster(dir));
    }

    // ---- per-listing rows ----

    @Test
    void forListingBuildsRowIntel() throws Exception {
        dir = Files.createTempDirectory("dock-media-listing");
        Files.writeString(dir.resolve("movie.mkv"), "x");
        Files.writeString(dir.resolve("other.txt"), "x");
        Files.writeString(dir.resolve("cover.jpg"), "x");
        Files.createDirectories(dir.resolve("S01"));
        Files.writeString(dir.resolve("S01").resolve(".roku-certified"), "");
        Files.writeString(dir.resolve("S01").resolve("poster.jpg"), "x");
        Files.createDirectories(dir.resolve("plain"));

        String movieKey = dir.resolve("movie.mkv").toString().replace("\\", "\\\\");
        String json = """
                {
                  "%s": {"Width": 3840, "Height": 1608, "Is4K": true, "IsHDR": true,
                          "IsAtmos": true, "AudioCount": 2, "AudioLangs": ["eng", "spa"],
                          "SubCount": 4, "SubLangs": ["eng", "spa", "fra", "ita"],
                          "CertificationBadge": "ROKU CERTIFIED", "VideoBitrateKbps": 48000,
                          "BitrateOk": true}
                }
                """.formatted(movieKey);
        Files.write(dir.resolve("probe_quality_cache.json"),
                ("\uFEFF" + json).getBytes(StandardCharsets.UTF_8));
        MediaIntel.setCacheDirForTest(dir);

        List<dock.core.fs.FileEntry> entries = List.of(
                dock.core.fs.FileEntry.PARENT,
                new dock.core.fs.FileEntry("movie.mkv", false, 10, 0, null, false),
                new dock.core.fs.FileEntry("other.txt", false, 10, 0, null, false),
                new dock.core.fs.FileEntry("S01", true, 0, 0, null, false),
                new dock.core.fs.FileEntry("plain", true, 0, 0, null, false));
        Map<String, MediaIntel.RowIntel> m =
                MediaIntel.forListing(dir, entries, MediaIntel.cache());

        assertFalse(m.containsKey(".."), "the way out needs no intelligence");

        MediaIntel.RowIntel movie = m.get("movie.mkv");
        assertEquals("mkv", movie.type());
        assertEquals("3840x1608", movie.resolution());
        assertEquals("4K HDR Atmos", movie.quality());
        assertEquals("2x [eng,spa]", movie.audio());
        assertEquals("48.0M ✓", movie.bitrate());
        assertEquals("ROKU CERTIFIED", movie.estado());
        assertEquals("4x [eng,spa,fra,ita]", movie.subs());
        assertTrue(movie.poster(), "the file row's poster lives in the listed dir");

        MediaIntel.RowIntel s01 = m.get("S01");
        assertEquals("dir", s01.type());
        assertEquals("ROKU", s01.estado());
        assertTrue(s01.poster(), "the folder row's poster is its own");
        assertEquals("", s01.resolution());

        MediaIntel.RowIntel plain = m.get("plain");
        assertEquals("dir", plain.type());
        assertEquals("", plain.estado());
        assertFalse(plain.poster());

        MediaIntel.RowIntel other = m.get("other.txt");
        assertEquals("txt", other.type());
        assertEquals("", other.resolution());
        assertTrue(other.poster(), "unprobed files still show the listing's poster");
    }

    // ---- sorting on the intelligence columns ----

    @Test
    void displayListSortsIntelligenceByNumericFacts() {
        dock.core.fs.FileEntry a = new dock.core.fs.FileEntry("a.mkv", false, 0, 0, null, false);
        dock.core.fs.FileEntry z = new dock.core.fs.FileEntry("z.mkv", false, 0, 0, null, false);
        dock.core.fs.FileEntry mid = new dock.core.fs.FileEntry("mid.mkv", false, 0, 0, null, false);
        Map<String, MediaIntel.RowIntel> intel = Map.of(
                "a.mkv", row(3000, 0, false),
                "z.mkv", row(48000, 0, false),
                "mid.mkv", row(10000, 0, false));
        List<dock.core.fs.FileEntry> out = FileTableModel.displayList(
                List.of(z, mid, a), FileTableModel.Column.BITRATE, true, false, false, intel);
        assertEquals(List.of("a.mkv", "mid.mkv", "z.mkv"), names(out), "bitrate asc");
        out = FileTableModel.displayList(
                List.of(z, mid, a), FileTableModel.Column.BITRATE, false, false, false, intel);
        assertEquals(List.of("z.mkv", "mid.mkv", "a.mkv"), names(out), "bitrate desc");
    }

    @Test
    void displayListSortsQualityAndPoster() {
        dock.core.fs.FileEntry hd = new dock.core.fs.FileEntry("hd.mkv", false, 0, 0, null, false);
        dock.core.fs.FileEntry uhd = new dock.core.fs.FileEntry("uhd.mkv", false, 0, 0, null, false);
        dock.core.fs.FileEntry sd = new dock.core.fs.FileEntry("sd.mkv", false, 0, 0, null, false);
        Map<String, MediaIntel.RowIntel> intel = Map.of(
                "hd.mkv", row(0, 2200, true),
                "uhd.mkv", row(0, 4400, false),
                "sd.mkv", MediaIntel.RowIntel.EMPTY);
        assertEquals(List.of("sd.mkv", "hd.mkv", "uhd.mkv"), names(FileTableModel.displayList(
                List.of(uhd, sd, hd), FileTableModel.Column.QUALITY, true, false, false, intel)),
                "quality rank asc: no data, then 1080p, then 4K");

        dock.core.fs.FileEntry bare = new dock.core.fs.FileEntry("bare", false, 0, 0, null, false);
        dock.core.fs.FileEntry dressed = new dock.core.fs.FileEntry("dressed", false, 0, 0, null, false);
        Map<String, MediaIntel.RowIntel> posters = Map.of(
                "bare", MediaIntel.RowIntel.EMPTY,
                "dressed", new MediaIntel.RowIntel("", "", "", "", "", "", "", "",
                        true, 0, 0, 0, 0, 0));
        assertEquals(List.of("bare", "dressed"), names(FileTableModel.displayList(
                List.of(dressed, bare), FileTableModel.Column.POSTER, true, false, false, posters)),
                "posterless first, poster found last");
    }

    private static MediaIntel.RowIntel row(long kbps, int height, boolean hdr) {
        return new MediaIntel.RowIntel("mkv", "", hdr && height >= 3000 ? "4K" : height >= 1000 ? "1080p" : "",
                "", kbps > 0 ? "x" : "", "", "", "", false,
                (long) (height >= 3000 ? 3840 : 1920) * Math.max(height, 1),
                height >= 3000 ? 420 : height >= 1000 ? 220 : 0,
                0, kbps, 0);
    }

    private static List<String> names(List<dock.core.fs.FileEntry> rows) {
        return rows.stream().map(dock.core.fs.FileEntry::name).toList();
    }
}
