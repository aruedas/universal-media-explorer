import dock.commander.FilePane;
import dock.core.config.ToolkitRoot;
import dock.terminal.ToolkitActions;
import java.awt.Component;
import java.awt.EventQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Toolkit Actions submenu states what is runnable right now. Availability
 * is read while the menu is built, so adding the toolkit folder to the
 * environment lights the items up without a restart - and leaving it out
 * greys every one of them with the same sentence the click would produce.
 */
class ToolkitMenuAvailabilityTest {

    private Path root;

    @BeforeAll
    static void theme() {
        dock.kit.FontRegistry.install();
        com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource("dock.themes");
        com.formdev.flatlaf.FlatLaf.setup(new com.formdev.flatlaf.FlatDarkLaf());
    }

    @AfterEach
    void tearDown() {
        ToolkitRoot.override(null);
        if (root != null) deleteTree(root);
    }

    @Test
    void aScriptThatExistsIsEnabledAndSaysWhatItDoes() throws Exception {
        ToolkitActions.Action action = ToolkitActions.all().get(0);
        root = Files.createTempDirectory("dock-toolkit-menu-ready");
        Files.createFile(root.resolve(action.script()));
        ToolkitRoot.override(root);

        JMenuItem item = toolkitItem(action.name());
        assertTrue(item.isEnabled(), action.name() + " has its script on disk");
        assertEquals(action.description(), item.getToolTipText());
    }

    @Test
    void everyItemIsGreyAndExplainsItselfWhenNoToolkitIsConfigured() throws Exception {
        root = Files.createTempDirectory("dock-toolkit-menu-empty");
        ToolkitRoot.override(root);

        List<JMenuItem> items = toolkitItems();
        assertEquals(ToolkitActions.all().size(), items.size(), "one item per action");
        for (JMenuItem item : items) {
            assertTrue(!item.isEnabled(), item.getText() + " cannot run without a script");
            String tip = item.getToolTipText();
            assertNotNull(tip, "a greyed item has to say why");
            assertTrue(tip.contains(ToolkitRoot.ENV_VAR),
                    "the tooltip names the environment variable: " + tip);
            assertTrue(tip.contains("toolkitRoot"),
                    "the tooltip names the settings key: " + tip);
        }
    }

    // ---- helpers ----

    private static JMenuItem toolkitItem(String text) {
        FilePane pane = pane();
        return onEdt(() -> {
            for (JMenuItem item : toolkitItemsUnchecked(pane)) {
                if (text.equals(item.getText())) return item;
            }
            throw new AssertionError("no Toolkit menu item called " + text);
        });
    }

    private static List<JMenuItem> toolkitItems() {
        FilePane pane = pane();
        return onEdt(() -> toolkitItemsUnchecked(pane));
    }

    /** A pane over the toolkit folder; the listing is irrelevant to the menu. */
    private static FilePane pane() {
        FilePane pane = new FilePane(dock.core.fs.LocalFs.INSTANCE);
        await(() -> onEdt(pane::rowCount) > 0);
        return pane;
    }

    private static List<JMenuItem> toolkitItemsUnchecked(FilePane pane) {
        JPopupMenu menu = pane.buildContextMenu();
        List<JMenuItem> out = new ArrayList<>();
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenu jm && "Toolkit Actions".equals(jm.getText())) {
                for (Component inner : jm.getMenuComponents()) {
                    if (inner instanceof JMenuItem mi) out.add(mi);
                }
            }
        }
        assertTrue(!out.isEmpty(), "the pane menu carries a Toolkit Actions submenu");
        return out;
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T onEdt(java.util.function.Supplier<T> read) {
        AtomicReference<T> out = new AtomicReference<>();
        try {
            EventQueue.invokeAndWait(() -> out.set(read.get()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out.get();
    }

    private static void await(java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
        }
    }
}
