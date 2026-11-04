package dock.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The toolkit directory comes from configuration, not from a path written
 * into the source: an install that has no PowerShell toolkit must resolve to
 * nothing rather than to a file that does not exist.
 */
class ToolkitRootTest {

    private Path configDir;

    @BeforeEach
    void isolate() throws Exception {
        configDir = Files.createTempDirectory("ume-toolkit-config");
        AppPaths.override(configDir);
        ToolkitRoot.override(null);
    }

    @AfterEach
    void release() {
        ToolkitRoot.override(null);
    }

    @Test
    void theSettingsKeyNamesTheToolkitDirectory() throws Exception {
        Path toolkit = Files.createTempDirectory("ume-toolkit");
        AppSettings.setText(AppSettings.TOOLKIT_ROOT, toolkit.toString());
        assertEquals(toolkit, ToolkitRoot.directory().orElseThrow());

        assertTrue(ToolkitRoot.file("UE_FixFiles.ps1").isEmpty(),
                "a script that is not there resolves to nothing");
        Files.writeString(toolkit.resolve("UE_FixFiles.ps1"), "exit 0\r\n");
        assertEquals(toolkit.resolve("UE_FixFiles.ps1"),
                ToolkitRoot.file("UE_FixFiles.ps1").orElseThrow());
    }

    @Test
    void aConfiguredDirectoryIsStillNamedEvenWhenItIsGone() throws Exception {
        Path vanished = Files.createTempDirectory("ume-toolkit-gone");
        Files.delete(vanished);
        ToolkitRoot.override(vanished);
        assertEquals(vanished, ToolkitRoot.directory().orElseThrow(),
                "the setting wins over the fallback, even for a directory that no longer exists");
        assertFalse(ToolkitRoot.file("MediaCommander.ps1").isPresent(),
                "but no file is ever claimed from a directory that is not there");
        assertTrue(ToolkitRoot.describeSearch().contains(vanished.toString()),
                "the message names what was searched");
    }

    @Test
    void theHarnessOverrideBeatsTheSetting() throws Exception {
        AppSettings.setText(AppSettings.TOOLKIT_ROOT, "C:\\somewhere\\else");
        ToolkitRoot.override(Path.of("D:\\toolkit"));
        assertEquals(Path.of("D:\\toolkit"), ToolkitRoot.directory().orElseThrow());
        assertTrue(ToolkitRoot.describeSearch().contains("test override"));
    }

    @Test
    void aBlankSettingIsNoSetting() throws Exception {
        AppSettings.setText(AppSettings.TOOLKIT_ROOT, "   ");
        assertNull(AppSettings.text(AppSettings.TOOLKIT_ROOT, null),
                "whitespace never becomes a directory");
    }

    @Test
    void aRelativePathStillResolvesToAFileInsideIt() throws Exception {
        Path toolkit = Files.createTempDirectory("ume-toolkit-rel");
        Files.writeString(toolkit.resolve("UE_Upload.ps1"), "exit 0\r\n");
        ToolkitRoot.override(toolkit);
        assertEquals(toolkit.resolve("UE_Upload.ps1"),
                ToolkitRoot.file("UE_Upload.ps1").orElseThrow());
    }
}
