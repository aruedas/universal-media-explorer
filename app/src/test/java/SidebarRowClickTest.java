import dock.ui.LocalCommander;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JLabel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sidebar rows navigate when the operator clicks the text, not just the empty
 * tail of the row: the click listener must answer for the JLabel and icon that
 * paint over most of the row, because Swing delivers mouse events to the
 * deepest component under the cursor and they do not bubble to the parent.
 */
class SidebarRowClickTest {

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

    private static JLabel findLabel(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel l && text.equals(l.getText()) && l.isVisible()) return l;
            if (c instanceof Container nested) {
                JLabel found = findLabel(nested, text);
                if (found != null) return found;
            }
        }
        return null;
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

    @Test
    void clickingRowTextNavigatesTheActivePane() throws Exception {
        show();
        var cmd = new AtomicReference<LocalCommander>();
        var before = new AtomicReference<String>();
        EventQueue.invokeAndWait(() -> {
            cmd.set(findCommander(frame.getContentPane()));
            before.set(cmd.get().activePane().path());
        });
        assertNotNull(cmd.get(), "the local commander is present");

        JLabel drive = findLabel(frame.getContentPane(), "C:");
        assertNotNull(drive, "the C: drive row is in the sidebar");
        assertNotEquals("C:\\", before.get(), "the pane starts somewhere else");

        // A click that lands on the label text — the part of the row the
        // operator actually aims at.
        EventQueue.invokeAndWait(() -> drive.dispatchEvent(new MouseEvent(
                drive, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, drive.getWidth() / 2, drive.getHeight() / 2, 1, false,
                MouseEvent.BUTTON1)));
        EventQueue.invokeAndWait(() -> assertEquals("C:\\",
                cmd.get().activePane().path(),
                "clicking the row text must navigate the active pane"));
    }
}
