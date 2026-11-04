import dock.ui.LocalCommander;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Toolbar toolkit actions run against the active pane's real selection —
 * selected files, or the pane directory when nothing is selected — never a
 * stale or hand-picked path.
 */
class ToolkitPaneSelectionTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource("dock.themes");
        com.formdev.flatlaf.FlatLaf.setup(new com.formdev.flatlaf.FlatDarkLaf());
    }

    private JFrame frame;

    private void show() throws Exception {
        EventQueue.invokeAndWait(() -> {
            var win = new dock.ui.MainWindow();
            win.setSize(1100, 700);
            win.setLocation(-2000, 0); // off-screen: no flash during tests
            win.setVisible(true);
            frame = win;
        });
        assertNotNull(frame, "the window is showing");
    }

    @AfterEach
    void close() {
        if (frame != null) frame.dispose();
    }

    private static LocalCommander findCommander(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof LocalCommander lc) return lc;
            if (c instanceof Container nested) {
                LocalCommander found = findCommander(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private dock.commander.FilePane activePane() throws Exception {
        show();
        var pane = new AtomicReference<dock.commander.FilePane>();
        EventQueue.invokeAndWait(() ->
                pane.set(findCommander(frame.getContentPane()).activePane()));
        assertNotNull(pane.get(), "the local commander has an active pane");
        return pane.get();
    }

    /** The listing lands on a virtual thread; poll until the file shows. */
    private static void awaitListing(dock.commander.FilePane pane, String fileName)
            throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var paths = new AtomicReference<List<String>>();
            EventQueue.invokeAndWait(() -> {
                pane.selectEntry(fileName);
                paths.set(pane.selectedPaths());
            });
            if (paths.get() != null && !paths.get().isEmpty()
                    && paths.get().get(0).endsWith(fileName)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("the listing never showed " + fileName);
    }

    @Test
    void selectedFilesFeedTheActionNotThePaneDirectory(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("aaa.txt"), "x");
        Files.writeString(dir.resolve("bbb.mkv"), "x");
        dock.commander.FilePane pane = activePane();

        EventQueue.invokeAndWait(() -> pane.navigate(dir.toString()));
        awaitListing(pane, "bbb.mkv");

        var paths = new AtomicReference<List<String>>();
        EventQueue.invokeAndWait(() -> paths.set(pane.selectedPaths()));
        assertEquals(List.of(dir.resolve("bbb.mkv").toString()), paths.get(),
                "the selection, not the pane directory, feeds the action");
    }

    @Test
    void withNoSelectionThePaneDirectoryIsTheTarget(@TempDir Path dir) throws Exception {
        dock.commander.FilePane pane = activePane();

        EventQueue.invokeAndWait(() -> pane.navigate(dir.toString()));
        var paths = new AtomicReference<List<String>>();
        EventQueue.invokeAndWait(() -> paths.set(pane.selectedPaths()));
        assertEquals(List.of(dir.toString()), paths.get(),
                "with nothing selected the action runs on the directory itself");
    }
}
