import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.commander.FilePane;
import dock.media.MediaEngine;
import dock.ui.LocalCommander;
import java.awt.Component;
import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JLabel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup browser browses and displays: the local commander's panes
 * carry the same media surfaces a remote session does — Enter/F3 covers a
 * pane with the image viewer or the player, F4 with the editor, Escape
 * puts its table back with the cursor where it stood. The other pane keeps
 * its listing on screen throughout (a stand-in swaps one side of the split,
 * never the window). The engine is the fake, so no natives ride into the
 * test run.
 */
class LocalCommanderMediaTest {

    private static final java.util.List<String> OPENED = new java.util.ArrayList<>();

    private LocalCommander commander;
    private JFrame frame;
    private Path dir;

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
        dock.media.PlayerPanel.useEngineFactoryForTest(() -> {
            OPENED.clear();
            return new RecordingEngine();
        });
    }

    @AfterAll
    static void restoreEngineFactory() {
        dock.media.PlayerPanel.useEngineFactoryForTest(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        EventQueue.invokeAndWait(() -> frame.dispose());
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    @Test
    void f3OnAnImageCoversItsPaneAndEscapeReturnsTheTable() throws Exception {
        build(root -> image(root.resolve("a.png")));
        var pane = commander.leftPane();
        awaitListed(pane);
        EventQueue.invokeAndWait(() -> pane.selectEntry("a.png"));
        EventQueue.invokeAndWait(() -> pane.fireTableActionForTest("F3"));

        Component cover = onEdt(() -> commander.standinForTest(pane));
        assertTrue(cover instanceof dock.viewer.ImageViewerPanel, "the viewer took the slot");
        assertFalse(pane.isDisplayable(), "the table waits underneath it");
        assertTrue(commander.rightPane().isDisplayable(), "the other pane keeps browsing");

        EventQueue.invokeAndWait(() ->
                ((dock.viewer.ImageViewerPanel) cover).fireKeyForTest("ESCAPE"));
        assertNull(onEdt(() -> commander.standinForTest(pane)), "escape puts the table back");
        assertTrue(pane.isDisplayable());
        assertEquals("a.png", selectedName(pane), "the cursor never moved");
    }

    @Test
    void enterOnAudioPlaysItInsideThePane() throws Exception {
        build(root -> Files.write(root.resolve("song.mp3"), new byte[] {1, 2, 3}));
        var pane = commander.leftPane();
        awaitListed(pane);
        EventQueue.invokeAndWait(() -> pane.selectEntry("song.mp3"));
        EventQueue.invokeAndWait(() -> pane.fireTableActionForTest("ENTER"));

        Component cover = onEdt(() -> commander.standinForTest(pane));
        assertTrue(cover instanceof dock.media.PlayerPanel, "the player took the slot");
        await(() -> !OPENED.isEmpty(), "the engine opened the file");
        var player = (dock.media.PlayerPanel) cover;
        String url = onEdt(player::bridgeUrlForTest);
        assertEquals(OPENED.get(0), url, "the engine plays the pane's own bridge URL");

        EventQueue.invokeAndWait(() -> player.fireKeyForTest("ESCAPE"));
        assertNull(onEdt(() -> commander.standinForTest(pane)));
        assertTrue(pane.isDisplayable());
    }

    @Test
    void f4OnTextOpensTheEditorOverThePane() throws Exception {
        build(root -> Files.writeString(root.resolve("notes.txt"), "plain words\n"));
        var pane = commander.rightPane();
        awaitListed(pane);
        EventQueue.invokeAndWait(() -> pane.selectEntry("notes.txt"));
        EventQueue.invokeAndWait(() -> pane.fireTableActionForTest("F4"));

        Component cover = onEdt(() -> commander.standinForTest(pane));
        assertTrue(cover instanceof dock.editor.EditorPanel, "F4 edits in place");
        assertTrue(commander.leftPane().isDisplayable(), "the other side is untouched");

        EventQueue.invokeAndWait(() ->
                ((dock.editor.EditorPanel) cover).fireKeyForTest("ESCAPE"));
        assertNull(onEdt(() -> commander.standinForTest(pane)));
    }

    // ---- helpers ----

    /** A minimal engine: records the URL, reports playing, no natives. */
    private static final class RecordingEngine implements MediaEngine {
        private Listener listener = Listener.NOTHING;
        private final JLabel surface = new JLabel();
        private boolean playing;

        @Override public javax.swing.JComponent surface() { return surface; }
        @Override public void setListener(Listener l) { listener = l; }
        @Override public void setOverlay(Overlay o) { }
        @Override public void open(String url) {
            OPENED.add(url);
            playing = true;
            listener.playing();
        }
        @Override public void play() { playing = true; listener.playing(); }
        @Override public void pause() { playing = false; listener.paused(); }
        @Override public void stop() { playing = false; }
        @Override public void seekMs(long ms) { }
        @Override public long timeMs() { return 0; }
        @Override public long durationMs() { return 0; }
        @Override public boolean isPlaying() { return playing; }
        @Override public void setVolume(int percent) { }
        @Override public void setMute(boolean mute) { }
        @Override public void loadSubtitle(java.io.File file) { }
        @Override public void close() { }
    }

    private void build(IOThrowing<Path> fill) throws Exception {
        dir = Files.createTempDirectory("dock-local-media");
        fill.accept(dir);
        EventQueue.invokeAndWait(() -> {
            commander = new LocalCommander();
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(commander);
            frame.setSize(1000, 600);
            frame.setLocation(-2000, 0);    // off-screen: no flash during tests
            frame.setVisible(true);
            // The media surfaces under test ride both panes of the split,
            // so these tests expand the default single-pane layout first.
            commander.setRightPaneHidden(false);
            commander.leftPane().navigate(dir.toString());
            commander.rightPane().navigate(dir.toString());
        });
    }

    private static void image(Path file) throws java.io.IOException {
        var img = new java.awt.image.BufferedImage(20, 14,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 14; y++)
            for (int x = 0; x < 20; x++) img.setRGB(x, y, 0xFF3366AA);
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        Files.write(file, out.toByteArray());
    }

    private static void awaitListed(FilePane pane) throws InterruptedException {
        for (int i = 0; i < 250 && pane.busyForTest(); i++) Thread.sleep(20);
        assertTrue(!pane.busyForTest(), "listing settles");
    }

    private static void await(java.util.function.BooleanSupplier cond, String what)
            throws InterruptedException {
        for (int i = 0; i < 250 && !cond.getAsBoolean(); i++) Thread.sleep(20);
        assertTrue(cond.getAsBoolean(), "timed out waiting for: " + what);
    }

    private interface EdtSupplier<T> { T get(); }

    private static <T> T onEdt(EdtSupplier<T> read) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        EventQueue.invokeAndWait(() -> out.set(read.get()));
        return out.get();
    }

    private static String selectedName(FilePane pane) throws Exception {
        return onEdt(() -> {
            int row = pane.table().getSelectedRow();
            if (row < 0) return null;
            var m = (dock.commander.FileTableModel) pane.table().getModel();
            return m.row(row).name();
        });
    }

    @FunctionalInterface
    private interface IOThrowing<T> {
        void accept(T value) throws java.io.IOException;
    }
}
