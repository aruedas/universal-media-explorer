package dock;

import static org.junit.jupiter.api.Assertions.*;

import dock.core.config.AppPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The %APPDATA% rename: legacy Dock and Universal Explorer trees move over on first use. */
class AppPathsTest {

    @Test
    void aLegacyDockFolderMovesOverOnFirstUse(@TempDir Path tmp) throws Exception {
        Path appdata = tmp.resolve("Roaming");
        Path legacy = Files.createDirectories(appdata.resolve("Dock"));
        Files.writeString(legacy.resolve("sessions.json"), "{}");
        Path fresh = appdata.resolve("Universal Media Explorer");
        AppPaths.override(fresh);
        try {
            Path config = AppPaths.config();
            assertEquals(fresh, config);
            assertEquals("{}", Files.readString(config.resolve("sessions.json")));
            assertFalse(Files.exists(legacy), "the legacy folder must not linger");
        } finally {
            AppPaths.override(null);
        }
    }

    @Test
    void aLegacyUniversalExplorerFolderMovesOverOnFirstUse(@TempDir Path tmp) throws Exception {
        Path appdata = tmp.resolve("Roaming");
        Path legacy = Files.createDirectories(appdata.resolve("Universal Explorer"));
        Files.writeString(legacy.resolve("sessions.json"), "{\"migrated\":true}");
        Path fresh = appdata.resolve("Universal Media Explorer");
        AppPaths.override(fresh);
        try {
            Path config = AppPaths.config();
            assertEquals(fresh, config);
            assertEquals("{\"migrated\":true}",
                    Files.readString(config.resolve("sessions.json")));
            assertFalse(Files.exists(legacy), "the Universal Explorer folder must not linger");
        } finally {
            AppPaths.override(null);
        }
    }

    @Test
    void anExistingFolderWinsOverALingeringLegacy(@TempDir Path tmp) throws Exception {
        Path appdata = tmp.resolve("Roaming");
        Files.createDirectories(appdata.resolve("Dock"));
        Path fresh = Files.createDirectories(appdata.resolve("Universal Media Explorer"));
        Files.writeString(fresh.resolve("sessions.json"), "{\"kept\":true}");
        AppPaths.override(fresh);
        try {
            assertEquals("{\"kept\":true}",
                    Files.readString(AppPaths.config().resolve("sessions.json")));
            assertTrue(Files.exists(appdata.resolve("Dock")),
                    "never a destructive merge with a stale legacy tree");
        } finally {
            AppPaths.override(null);
        }
    }
}
