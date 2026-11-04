import dock.terminal.LocalShell;
import dock.terminal.ToolkitActions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every toolkit launch must surface its shell through the registered tab
 * opener, so the operator watches the script run instead of wondering
 * whether anything happened.
 */
class ToolkitActionsLaunchTest {

    @Test
    void launchSurfacesTheShellThroughTheRegisteredTabOpener() throws Exception {
        Path script = Files.createTempFile("ume-noop", ".ps1");
        Files.writeString(script, "exit 0\r\n");
        ToolkitActions.Action noop = new ToolkitActions.Action(
                "NOOP", "", script.toString(), "test action", false);

        var openedTitle = new AtomicReference<String>();
        var openedShell = new AtomicReference<LocalShell>();
        ToolkitActions.setTabOpener((title, shell) -> {
            openedTitle.set(title);
            openedShell.set(shell);
        });
        try {
            LocalShell shell = ToolkitActions.launch(noop,
                    List.of("C:\\some dir\\file.mkv"), System.getProperty("java.io.tmpdir"));
            assertEquals("NOOP", openedTitle.get(),
                    "launch must call the opener with the action name");
            assertSame(shell, openedShell.get(),
                    "launch must hand the opener the shell it spawned");
            assertTrue(shell.isConnected());
            assertEquals(0, shell.waitFor(), "the noop script exits cleanly");
        } finally {
            ToolkitActions.setTabOpener(null);
            shellRefCleanup(openedShell.get());
            Files.deleteIfExists(script);
        }
    }

    private static void shellRefCleanup(LocalShell shell) {
        if (shell != null) shell.close();
    }
}
