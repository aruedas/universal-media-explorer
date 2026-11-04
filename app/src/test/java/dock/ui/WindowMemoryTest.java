package dock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The window comes back the size and place the last run left it: a saved
 * box restores on the next construction, and an out-of-range or off-screen
 * one falls back to the centered default.
 */
class WindowMemoryTest {

    private Path config;
    private MainWindow window;

    @BeforeEach
    void isolate() throws Exception {
        config = Files.createTempDirectory("dock-win-cfg");
        dock.core.config.AppPaths.override(config);
    }

    @AfterEach
    void close() throws Exception {
        if (window != null) window.dispose();
        // A memory write still in flight must not chase the reset and land
        // in the shared test config.
        Thread.sleep(300);
        dock.core.config.AppPaths.override(null);
    }

    @Test
    void theSavedBoxRestoresOnTheNextLaunch() {
        window = new MainWindow();
        window.setBounds(33, 44, 1000, 700);
        window.rememberWindow();
        window.dispose();
        window = null;

        MainWindow next = new MainWindow();
        try {
            assertEquals(33, next.getX());
            assertEquals(44, next.getY());
            assertEquals(1000, next.getWidth());
            assertEquals(700, next.getHeight());
        } finally {
            next.dispose();
        }
    }

    @Test
    void aBoxNoScreenShowsFallsBackToCentered() throws Exception {
        Files.writeString(config.resolve("last_locations.json"),
                "{\"window\": {\"x\": -9000, \"y\": -9000, \"w\": 1000, \"h\": 700,"
                        + " \"maximized\": false}}");
        window = new MainWindow();
        // Centered: somewhere on a screen, at the default size.
        boolean onScreen = false;
        for (var gd : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getScreenDevices()) {
            if (gd.getDefaultConfiguration().getBounds().intersects(window.getBounds())) {
                onScreen = true;
                break;
            }
        }
        assertTrue(onScreen, "the window is visible on some screen");
        assertEquals(1280, window.getWidth(), "the default size applies");
    }
}
