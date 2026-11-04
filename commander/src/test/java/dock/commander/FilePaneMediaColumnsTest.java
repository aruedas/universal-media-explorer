package dock.commander;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The intelligence columns ride local panes: headers in SimpleCommander's
 * vocabulary, cells fed by the engine's probe cache and the cert markers,
 * a real header click sorting on the hidden numeric facts — while remote
 * panes keep the classic four columns.
 */
class FilePaneMediaColumnsTest {

    /** LocalFs rooted at the temp dir, with a settable remote flag. */
    private static final class TempFs implements dock.core.fs.FileSystem {
        private final dock.core.fs.FileSystem inner = dock.core.fs.LocalFs.INSTANCE;
        private final String home;
        private final boolean rem;
        TempFs(String home, boolean rem) { this.home = home; this.rem = rem; }
        @Override public String home() { return home; }
        @Override public boolean remote() { return rem; }
        @Override public String label() { return inner.label(); }
        @Override public String separator() { return inner.separator(); }
        @Override public List<String> roots() { return inner.roots(); }
        @Override public String normalize(String p) { return inner.normalize(p); }
        @Override public String parent(String p) { return inner.parent(p); }
        @Override public String child(String d, String n) { return inner.child(d, n); }
        @Override public boolean exists(String p) throws java.io.IOException { return inner.exists(p); }
        @Override public List<dock.core.fs.FileEntry> list(String p) throws java.io.IOException { return inner.list(p); }
        @Override public dock.core.fs.FileEntry stat(String p) throws java.io.IOException { return inner.stat(p); }
        @Override public void mkdir(String p) throws java.io.IOException { inner.mkdir(p); }
        @Override public void delete(String p) throws java.io.IOException { inner.delete(p); }
        @Override public void rename(String f, String t) throws java.io.IOException { inner.rename(f, t); }
        @Override public java.io.InputStream read(String p) throws java.io.IOException { return inner.read(p); }
        @Override public java.io.OutputStream write(String p, boolean a) throws java.io.IOException { return inner.write(p, a); }
        @Override public void setTimes(String p, long m) throws java.io.IOException { inner.setTimes(p, m); }
        @Override public void setPerms(String p, int x) throws java.io.IOException { inner.setPerms(p, x); }
        @Override public void close() {}
    }

    private static final class FakeSession implements dock.core.session.Session {
        private final dock.core.fs.FileSystem fs;
        FakeSession(dock.core.fs.FileSystem fs) { this.fs = fs; }
        @Override public dock.core.fs.FileSystem fs() { return fs; }
        @Override public dock.core.fs.FileSystem reconnect() { return fs; }
        @Override public void onConnectionLost(Runnable callback) { }
        @Override public boolean reconnectable() { return false; }
        @Override public void close() { }
    }

    private dock.commander.SessionView view;
    private JFrame frame;
    private Path dir;

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @AfterEach
    void tearDown() throws Exception {
        MediaIntel.setCacheDirForTest(null);
        if (frame != null) EventQueue.invokeAndWait(() -> frame.dispose());
        if (dir != null) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (java.io.IOException e) { throw new RuntimeException(e); }
                });
            }
        }
    }

    /** A directory the engine has already probed: one certified season
     *  folder, a poster in the listing, and a probed movie file. */
    private void seed() throws Exception {
        dir = Files.createTempDirectory("dock-media-columns");
        Files.writeString(dir.resolve("Movie (2020).mkv"), "x");
        Files.writeString(dir.resolve("Other show.mkv"), "x");
        Files.writeString(dir.resolve("cover.jpg"), "x");
        Files.createDirectories(dir.resolve("S01"));
        Files.writeString(dir.resolve("S01").resolve(".roku-certified"), "");
        Files.writeString(dir.resolve("S01").resolve("poster.jpg"), "x");

        String movieKey = dir.resolve("Movie (2020).mkv").toString().replace("\\", "\\\\");
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
    }

    @Test
    void localPaneShowsTheIntelligenceColumns() throws Exception {
        seed();
        EventQueue.invokeAndWait(() -> {
            view = new SessionView(new FakeSession(new TempFs(dir.toString(), false)), "unit");
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(view);
            frame.setSize(1400, 600);
            frame.setLocation(-2400, 0);    // off-screen: no flash during tests
            frame.setVisible(true);
        });
        FilePane pane = view.remotePane();
        awaitListed(pane);
        AtomicReference<String> failure = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            try {
                var cm = pane.table().getColumnModel();
                List<String> headers = List.of("Name", "Size", "Modified", "Type",
                        "Resolution", "Calidad", "Calidad Audio", "Bitrate",
                        "Estado", "Subtítulos", "Poster");
                assertEquals(headers.size(), cm.getColumnCount(), "local drops Attrs for intel");
                for (int i = 0; i < headers.size(); i++) {
                    assertEquals(headers.get(i), cm.getColumn(i).getHeaderValue(),
                            "header " + i);
                }
                var m = pane.table().getModel();
                int movie = rowOf(m, "Movie (2020).mkv");
                assertEquals("mkv", m.getValueAt(movie, 4));
                assertEquals("3840x1608", m.getValueAt(movie, 5));
                assertEquals("4K HDR Atmos", m.getValueAt(movie, 6));
                assertEquals("2x [eng,spa]", m.getValueAt(movie, 7));
                assertEquals("48.0M ✓", m.getValueAt(movie, 8));
                assertEquals("ROKU CERTIFIED", m.getValueAt(movie, 9));
                assertEquals("4x [eng,spa,fra,ita]", m.getValueAt(movie, 10));
                assertEquals("✓", m.getValueAt(movie, 11));

                int season = rowOf(m, "S01");
                assertEquals("dir", m.getValueAt(season, 4));
                assertEquals("ROKU", m.getValueAt(season, 9));
                assertEquals("✓", m.getValueAt(season, 11));
                assertEquals("", m.getValueAt(season, 5));

                int other = rowOf(m, "Other show.mkv");
                assertEquals("mkv", m.getValueAt(other, 4));
                assertEquals("", m.getValueAt(other, 5));
                assertEquals("✓", m.getValueAt(other, 11));
            } catch (AssertionError e) {
                failure.set(e.getMessage());
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test
    void bitrateHeaderClickSortsOnTheHiddenFact() throws Exception {
        seed();
        // Every file needs a probed bitrate: unprobed rows (kbps 0) would
        // otherwise slide ahead of the fixtures and muddy the order.
        Files.deleteIfExists(dir.resolve("cover.jpg"));
        Files.writeString(dir.resolve("aaa.mkv"), "x");
        Files.writeString(dir.resolve("zzz.mkv"), "x");
        String movie = dir.resolve("Movie (2020).mkv").toString().replace("\\", "\\\\");
        String other = dir.resolve("Other show.mkv").toString().replace("\\", "\\\\");
        String aaa = dir.resolve("aaa.mkv").toString().replace("\\", "\\\\");
        String zzz = dir.resolve("zzz.mkv").toString().replace("\\", "\\\\");
        String json = """
                {
                  "%s": {"Width": 3840, "Height": 1608, "VideoBitrateKbps": 20000},
                  "%s": {"Width": 1920, "Height": 1080, "VideoBitrateKbps": 10000},
                  "%s": {"Width": 1920, "Height": 1080, "VideoBitrateKbps": 3000},
                  "%s": {"Width": 1920, "Height": 1080, "VideoBitrateKbps": 48000}
                }
                """.formatted(movie, other, aaa, zzz);
        Files.write(dir.resolve("probe_quality_cache.json"),
                ("\uFEFF" + json).getBytes(StandardCharsets.UTF_8));
        MediaIntel.setCacheDirForTest(dir);

        EventQueue.invokeAndWait(() -> {
            view = new SessionView(new FakeSession(new TempFs(dir.toString(), false)), "unit");
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(view);
            frame.setSize(1400, 600);
            frame.setLocation(-2400, 0);
            frame.setVisible(true);
        });
        FilePane pane = view.remotePane();
        awaitListed(pane);
        // View column 7 is Bitrate (Name, Size, Modified, Type, Resolution,
        // Calidad, Calidad Audio, Bitrate) — the click must map the view
        // position to the model's enum position, not read it raw.
        clickHeader(pane, 7);
        // probe_quality_cache.json sits in the listing too — unprobed, kbps 0.
        awaitOrder(pane, List.of("..", "S01", "probe_quality_cache.json",
                "aaa.mkv", "Other show.mkv", "Movie (2020).mkv", "zzz.mkv"));
        clickHeader(pane, 7);
        // Descending: the unprobed cache file (kbps 0) sinks to the end.
        awaitOrder(pane, List.of("..", "S01", "zzz.mkv", "Movie (2020).mkv",
                "Other show.mkv", "aaa.mkv", "probe_quality_cache.json"));
    }

    @Test
    void remotePaneKeepsTheClassicColumns() throws Exception {
        seed();
        EventQueue.invokeAndWait(() -> {
            view = new SessionView(new FakeSession(new TempFs(dir.toString(), true)), "unit");
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(view);
            frame.setSize(1000, 600);
            frame.setLocation(-2400, 0);
            frame.setVisible(true);
        });
        FilePane pane = view.remotePane();
        awaitListed(pane);
        AtomicReference<String> failure = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> {
            try {
                var cm = pane.table().getColumnModel();
                assertEquals(4, cm.getColumnCount(), "remote keeps Name..Attrs");
                assertEquals("Attrs", cm.getColumn(3).getHeaderValue());
            } catch (AssertionError e) {
                failure.set(e.getMessage());
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    // ---- helpers ----

    private static int rowOf(javax.swing.table.TableModel m, String name) {
        for (int r = 0; r < m.getRowCount(); r++) {
            if (name.equals(m.getValueAt(r, 0))) return r;
        }
        throw new AssertionError("no row " + name);
    }

    private static void clickHeader(FilePane pane, int viewColumn) {
        var header = pane.table().getTableHeader();
        Rectangle r = header.getHeaderRect(viewColumn);
        header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0,
                r.x + r.width / 2, r.y + r.height / 2, 1, false));
    }

    private static void awaitListed(FilePane pane) throws InterruptedException {
        for (int i = 0; i < 250 && pane.busyForTest(); i++) Thread.sleep(20);
        if (pane.busyForTest()) throw new AssertionError("listing never settled");
    }

    private static void awaitOrder(FilePane pane, List<String> wanted) throws Exception {
        List<String> last = null;
        for (int i = 0; i < 250; i++) {
            var seen = new AtomicReference<List<String>>();
            EventQueue.invokeAndWait(() -> {
                var m = (FileTableModel) pane.table().getModel();
                seen.set(java.util.stream.IntStream.range(0, m.getRowCount())
                        .mapToObj(m::row).map(dock.core.fs.FileEntry::name).toList());
            });
            last = seen.get();
            if (wanted.equals(seen.get())) return;
            Thread.sleep(20);
        }
        throw new AssertionError("sort never settled on " + wanted + " — last saw " + last);
    }
}
