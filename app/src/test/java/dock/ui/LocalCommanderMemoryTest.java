package dock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The local view is remembered like a session: a restart lands both panes
 * where the last run left them — the folded second one stays folded, the
 * unfolded one comes back — and every navigation records itself again.
 */
class LocalCommanderMemoryTest {

    private Path config;
    private Path dirA;
    private Path dirB;
    private JFrame frame;
    private LocalCommander commander;

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource("dock.themes");
        com.formdev.flatlaf.FlatLaf.setup(new com.formdev.flatlaf.FlatDarkLaf());
    }

    @BeforeEach
    void isolate() throws Exception {
        config = Files.createTempDirectory("dock-mem-cfg");
        dirA = Files.createTempDirectory("dock-mem-a");
        dirB = Files.createTempDirectory("dock-mem-b");
        dock.core.config.AppPaths.override(config);
    }

    @AfterEach
    void close() throws Exception {
        if (frame != null) frame.dispose();
        // A memory write still in flight must not chase the reset and land
        // in the shared test config.
        Thread.sleep(300);
        dock.core.config.AppPaths.override(null);
    }

    private void show() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame();
            commander = new LocalCommander();
            frame.add(commander);
            frame.setSize(900, 600);
            frame.setLocation(-2400, 0);   // off-screen: no flash during tests
            frame.setVisible(true);
        });
    }

    private void awaitIdle() throws Exception {
        for (int i = 0; i < 250 && (commander.leftPane().busyForTest()
                || commander.rightPane().busyForTest()); i++) Thread.sleep(20);
        assertTrue(!commander.leftPane().busyForTest()
                && !commander.rightPane().busyForTest(), "both listings settle");
        // The memory write rides a virtual thread behind the landing.
        Thread.sleep(250);
    }

    @Test
    void aFreshRunStartsAtHomes() throws Exception {
        show();
        awaitIdle();
        assertEquals(dock.core.fs.LocalFs.INSTANCE.home(),
                commander.leftPane().path(), "no memory, no restore");
    }

    @Test
    void theSavedPairComesBackAndTheFoldToo() throws Exception {
        Files.createDirectory(dirA.resolve("keep.txt"));
        dock.core.config.LastLocations.putLocal(dirA.toString(), dirB.toString(), true);
        show();
        awaitIdle();
        assertEquals(dirA.toString(), commander.leftPane().path());
        assertEquals(dirB.toString(), commander.rightPane().path());
        assertTrue(!commander.rightPaneHidden(), "the unfolded second pane returns");
    }

    @Test
    void navigationRecordsItselfForTheNextRun() throws Exception {
        show();
        awaitIdle();
        javax.swing.SwingUtilities.invokeAndWait(() ->
                commander.leftPane().navigatePlace(dock.core.fs.LocalFs.INSTANCE, dirB.toString()));
        awaitIdle();
        assertEquals(dirB.toString(),
                dock.core.config.LastLocations.local().left(),
                "the next run will open here");
    }
}
