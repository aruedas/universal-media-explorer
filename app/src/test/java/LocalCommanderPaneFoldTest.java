import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dock.ui.LocalCommander;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.EventQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local commander opens as ONE pane: the second starts folded away and
 * the same pane toggles a session uses bring it back — or swap which side
 * shows. One pane must stay visible, the same rule a session follows.
 */
class LocalCommanderPaneFoldTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        FlatLaf.setup(new FlatDarkLaf());
    }

    private JFrame frame;
    private LocalCommander commander;

    @org.junit.jupiter.api.BeforeEach
    void isolate() throws Exception {
        // The commander now restores remembered panes; these tests pin the
        // fresh-start contract, so the config shelf points at scratch.
        dock.core.config.AppPaths.override(
                java.nio.file.Files.createTempDirectory("dock-fold"));
    }

    @AfterEach
    void close() throws Exception {
        if (frame != null) frame.dispose();
        // Pending memory writes must not chase the override reset.
        Thread.sleep(300);
        dock.core.config.AppPaths.override(null);
    }

    private void show() throws Exception {
        EventQueue.invokeAndWait(() -> {
            commander = new LocalCommander();
            frame = new JFrame();
            frame.add(commander);
            frame.setSize(1000, 600);
            frame.setLocation(-2000, 0); // off-screen: no flash during tests
            frame.setVisible(true);
        });
    }

    @Test
    void onePaneIsTheDefaultAndTheToggleBringsTheSecondBack() throws Exception {
        show();
        assertTrue(commander.rightPaneHidden(), "the second pane starts folded");
        assertTrue(SwingUtilities.isDescendingFrom(commander.leftPane(), frame));
        assertFalse(SwingUtilities.isDescendingFrom(commander.rightPane(), frame),
                "the folded pane is out of the window");

        EventQueue.invokeAndWait(() -> commander.setRightPaneHidden(false));
        assertFalse(commander.rightPaneHidden());
        assertTrue(SwingUtilities.isDescendingFrom(commander.leftPane(), frame));
        assertTrue(SwingUtilities.isDescendingFrom(commander.rightPane(), frame),
                "expanding seats both panes in the split");

        EventQueue.invokeAndWait(() -> commander.setRightPaneHidden(true));
        assertFalse(SwingUtilities.isDescendingFrom(commander.rightPane(), frame));
        assertTrue(SwingUtilities.isDescendingFrom(commander.leftPane(), frame));
    }

    @Test
    void theLastVisiblePaneCannotFold() throws Exception {
        show();
        EventQueue.invokeAndWait(() -> commander.setLeftPaneHidden(true));
        assertFalse(commander.leftPaneHidden(),
                "folding the only visible pane is refused");
        assertTrue(SwingUtilities.isDescendingFrom(commander.leftPane(), frame));
    }

    @Test
    void foldingTheLeftSideSwapsWhichPaneShows() throws Exception {
        show();
        EventQueue.invokeAndWait(() -> commander.setRightPaneHidden(false));
        EventQueue.invokeAndWait(() -> commander.setLeftPaneHidden(true));
        assertTrue(commander.leftPaneHidden());
        assertFalse(SwingUtilities.isDescendingFrom(commander.leftPane(), frame));
        assertTrue(SwingUtilities.isDescendingFrom(commander.rightPane(), frame));
        EventQueue.invokeAndWait(() ->
                assertTrue(commander.activePane() == commander.rightPane(),
                        "the keyboard follows the pane that stays on screen"));
    }
}
