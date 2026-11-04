import dock.kit.WrapLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JToolBar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toolkit row as the operator reads it: two labelled groups, the upload
 * closing the local one, nothing clipped, and script settings demoted to the
 * Help menu. Pinned against a real window because the row is rebuilt from a
 * registry and wrapped by a custom layout — both go wrong silently on screen.
 */
class ToolkitToolbarRowTest {

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource("dock.themes");
        com.formdev.flatlaf.FlatLaf.setup(new com.formdev.flatlaf.FlatDarkLaf());
    }

    private JFrame frame;
    private JToolBar bar;

    /** The window shown off-screen at a width the row cannot fit in one line. */
    private void show() throws Exception {
        EventQueue.invokeAndWait(() -> {
            var win = new dock.ui.MainWindow();
            win.setSize(1100, 700);
            win.setLocation(-2000, 0); // off-screen: no flash during tests
            win.setVisible(true);
            frame = win;
            bar = findToolBar(win.getContentPane());
        });
        assertNotNull(bar, "the window carries the toolkit row");
    }

    @AfterEach
    void close() {
        if (frame != null) frame.dispose();
    }

    private static JToolBar findToolBar(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JToolBar row) return row;
            if (c instanceof Container nested) {
                JToolBar found = findToolBar(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String label(Component c) {
        return c instanceof JButton button ? button.getText() : ((JLabel) c).getText();
    }

    /** The row's slots as text, in the order they were added. */
    private static List<String> slots(JToolBar row) {
        List<String> out = new ArrayList<>();
        for (Component c : row.getComponents()) out.add(label(c));
        return out;
    }

    private static int groupOf(JToolBar row, int index) {
        Object group = ((javax.swing.JComponent) row.getComponent(index))
                .getClientProperty(WrapLayout.GROUP);
        assertNotNull(group, "slot " + index + " (" + label(row.getComponent(index))
                + ") declares no group");
        return (int) group;
    }

    @Test
    void theRowReadsLocalThenUploadThenRemote() throws Exception {
        show();
        assertEquals(List.of(
                "LOCAL:", "NOMBRAR", "NOMBRAR S", "EXTRAER", "CERTIFICAR",
                "GEN M3U", "SUBIR M3U", "RESPALDO", "COMMANDER", "UPLOAD",
                "REMOTE:", "NOMBRAR REP", "RENAME REP", "CERTIFICAR REP",
                "RESPALDO REP", "PUSH LATEST"), slots(bar));
    }

    @Test
    void scriptSettingsLeavesTheRowForTheToolsMenu() throws Exception {
        show();
        assertFalse(slots(bar).contains("CFG"), "CFG is not a toolbar button");
        JMenuItem settings = menuItem("Tools", "Script Settings");
        assertNotNull(settings, "Tools carries the script settings entry");
        assertTrue(settings.getToolTipText().contains("WinSCP_Script_Settings.ps1"),
                "and says what it opens: " + settings.getToolTipText());
    }

    private JMenuItem menuItem(String menuName, String text) {
        var menus = frame.getJMenuBar();
        for (int i = 0; i < menus.getMenuCount(); i++) {
            JMenu menu = menus.getMenu(i);
            if (menu == null || !menuName.equals(menu.getText())) continue;
            for (int j = 0; j < menu.getItemCount(); j++) {
                JMenuItem item = menu.getItem(j);
                if (item != null && text.equals(item.getText())) return item;
            }
        }
        return null;
    }

    /**
     * The point of the wrap: at this width the sixteen slots do not fit on
     * one line, so the row grows instead of hiding its tail.
     */
    @Test
    void nothingHidesBehindTheWindowEdge() throws Exception {
        show();
        for (Component c : bar.getComponents()) {
            assertTrue(c.getX() + c.getWidth() <= bar.getWidth(),
                    label(c) + " ends at " + (c.getX() + c.getWidth())
                            + " past the row's " + bar.getWidth());
        }
    }

    /** A caption left alone at the end of a line labels nothing. */
    @Test
    void eachGroupTravelsAsOnePiece() throws Exception {
        show();
        int remote = slots(bar).indexOf("REMOTE:");
        assertTrue(remote > 0, "the remote caption is in the row");
        int local = groupOf(bar, 0);
        assertNotEquals(local, groupOf(bar, remote), "the groups are separate runs");
        for (int i = remote; i < bar.getComponentCount(); i++) {
            assertEquals(groupOf(bar, remote), groupOf(bar, i),
                    label(bar.getComponent(i)) + " belongs with its caption");
        }
        for (int i = 0; i < remote; i++) {
            assertEquals(local, groupOf(bar, i), label(bar.getComponent(i)) + " stays local");
        }
    }
}
