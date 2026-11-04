package dock.commander;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.JTextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SimpleCommander's flow, ported: plain Space on the table previews the
 * selection in a side well (Space again closes it) without stealing the
 * keyboard from an active speed search; File → New File names an empty
 * file in place like a folder draft; and an Edit-menu Cut pastes as a
 * real move through the transfer engine.
 */
class SpacePreviewTest {

    /** LocalFs rooted at the temp dir, so the pane lists only our files. */
    private static final class TempFs implements dock.core.fs.FileSystem {
        private final dock.core.fs.FileSystem inner = dock.core.fs.LocalFs.INSTANCE;
        private final String home;
        TempFs(String home) { this.home = home; }

        @Override public String home() { return home; }
        @Override public String label() { return inner.label(); }
        @Override public boolean remote() { return inner.remote(); }
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

    private JFrame frame;
    private Path dirA;
    private Path dirB;
    private dock.commander.FilePane paneA;
    private dock.commander.FilePane paneB;

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (frame != null) EventQueue.invokeAndWait(() -> frame.dispose());
        dock.commander.FilePane.useClipboardForTest(null);
        dock.commander.FilePane.previewEnabledForTest(true);
        for (Path dir : new Path[]{dirA, dirB}) {
            if (dir == null) continue;
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (java.io.IOException e) { throw new RuntimeException(e); }
                });
            }
        }
    }

    @Test
    void spaceTogglesThePreviewWellAndBack() throws Exception {
        build(root -> {
            Files.writeString(root.resolve("a.txt"), "hello");
            Files.writeString(root.resolve("b.txt"), "there");
        });
        awaitIdle(paneA);
        selectByName(paneA, "a.txt");

        EventQueue.invokeAndWait(() -> paneA.pressForTest(java.awt.event.KeyEvent.VK_SPACE));
        assertTrue(paneA.previewOpenForTest(), "the first Space opens the preview");

        EventQueue.invokeAndWait(() -> paneA.pressForTest(java.awt.event.KeyEvent.VK_SPACE));
        assertFalse(paneA.previewOpenForTest(), "the second Space closes it");
    }

    @Test
    void spaceLeavesAnActiveSearchAlone() throws Exception {
        build(root -> {
            Files.writeString(root.resolve("alpha.txt"), "1");
            Files.writeString(root.resolve("also.txt"), "2");
        });
        awaitIdle(paneA);

        EventQueue.invokeAndWait(() -> paneA.typeForTest("al"));
        assertTrue(paneA.searchActiveForTest(), "typing arms the speed search");
        EventQueue.invokeAndWait(() -> paneA.pressForTest(java.awt.event.KeyEvent.VK_SPACE));
        assertFalse(paneA.previewOpenForTest(),
                "Space inside a search never opens the preview");
        EventQueue.invokeAndWait(() -> paneA.typeForTest(" "));
        assertTrue(paneA.searchQueryForTest().endsWith(" "),
                "the typed space lands in the query");
    }

    @Test
    void newFileNamesAnEmptyFileInPlace() throws Exception {
        build(root -> Files.writeString(root.resolve("z.txt"), "x"));
        awaitIdle(paneA);

        EventQueue.invokeAndWait(() -> paneA.newFile());
        assertTrue(paneA.table().isEditing(), "the draft row's name cell is editing");
        EventQueue.invokeAndWait(() -> {
            JTextField field = (JTextField) paneA.table().getEditorComponent();
            assertEquals("New file.txt", field.getText());
            field.setText("notes");
        });
        EventQueue.invokeAndWait(() -> paneA.table().getCellEditor().stopCellEditing());
        awaitIdle(paneA);

        Path made = dirA.resolve("notes");
        assertTrue(Files.exists(made), "Enter commits the empty file");
        assertEquals(0, Files.size(made), "the file is empty");
    }

    @Test
    void cutThenPasteMovesTheFileForReal() throws Exception {
        build(root -> Files.writeString(root.resolve("a.txt"), "payload"));
        awaitIdle(paneA);

        java.awt.datatransfer.Clipboard cb = new java.awt.datatransfer.Clipboard("test");
        EventQueue.invokeAndWait(() -> {
            selectByName(paneA, "a.txt");
            paneA.copySelectionToClipboard(true, cb);
        });
        dock.commander.FilePane.useClipboardForTest(() -> cb.getContents(null));
        EventQueue.invokeAndWait(paneB::pasteFromClipboard);

        Path moved = dirB.resolve("a.txt");
        boolean landed = await(() -> Files.exists(moved));
        assertTrue(landed, "the paste queues a real move and it lands");
        // The move is copy-then-delete: poll for the deletion rather than
        // racing the beat between the two phases.
        boolean gone = await(() -> !Files.exists(dirA.resolve("a.txt")));
        assertTrue(gone, "the source is gone — cut, not copy");
    }

    @Test
    void copyThenPasteKeepsBothSides() throws Exception {
        build(root -> Files.writeString(root.resolve("a.txt"), "payload"));
        awaitIdle(paneA);

        java.awt.datatransfer.Clipboard cb = new java.awt.datatransfer.Clipboard("test");
        EventQueue.invokeAndWait(() -> {
            selectByName(paneA, "a.txt");
            paneA.copySelectionToClipboard(false, cb);
        });
        dock.commander.FilePane.useClipboardForTest(() -> cb.getContents(null));
        EventQueue.invokeAndWait(paneB::pasteFromClipboard);

        Path copied = dirB.resolve("a.txt");
        assertTrue(await(() -> Files.exists(copied)), "the copy lands");
        assertTrue(Files.exists(dirA.resolve("a.txt")), "the source stays");
    }

    // ---- helpers ----

    private interface EdtCheck { boolean check(); }

    private boolean await(EdtCheck until) throws InterruptedException {
        for (int i = 0; i < 250; i++) {
            var ok = new AtomicReference<Boolean>();
            try {
                EventQueue.invokeAndWait(() -> ok.set(until.check()));
            } catch (Exception e) {
                // the worker may still be mid-flight; keep waiting
            }
            if (Boolean.TRUE.equals(ok.get())) return true;
            Thread.sleep(20);
        }
        return false;
    }

    private void build(IOThrowing<java.nio.file.Path> fill) throws Exception {
        dirA = Files.createTempDirectory("dock-preview-a");
        dirB = Files.createTempDirectory("dock-preview-b");
        fill.accept(dirA);
        EventQueue.invokeAndWait(() -> {
            paneA = new dock.commander.FilePane(new TempFs(dirA.toString()));
            paneB = new dock.commander.FilePane(new TempFs(dirB.toString()));
            javax.swing.JSplitPane split = new javax.swing.JSplitPane(
                    javax.swing.JSplitPane.HORIZONTAL_SPLIT, paneA, paneB);
            split.setResizeWeight(0.5);
            frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.add(split);
            frame.setSize(1200, 600);
            frame.setLocation(-2400, 0);    // off-screen: no flash during tests
            frame.setVisible(true);
        });
        awaitIdle(paneB);
    }

    private void awaitIdle(dock.commander.FilePane pane) throws InterruptedException {
        for (int i = 0; i < 250 && pane.busyForTest(); i++) Thread.sleep(20);
        assertTrue(!pane.busyForTest(), "listing settles");
    }

    /** Selects the row showing {@code name} (the model's row order). */
    private void selectByName(dock.commander.FilePane pane, String name) {
        JTable t = pane.table();
        var m = (dock.commander.FileTableModel) t.getModel();
        for (int r = 0; r < t.getRowCount(); r++) {
            if (name.equals(m.row(r).name())) {
                t.setRowSelectionInterval(r, r);
                return;
            }
        }
        throw new AssertionError("no row named " + name);
    }

    @FunctionalInterface
    private interface IOThrowing<T> {
        void accept(T value) throws java.io.IOException;
    }
}
