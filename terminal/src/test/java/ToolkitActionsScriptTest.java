import dock.core.config.ToolkitRoot;
import dock.terminal.LocalShell;
import dock.terminal.ToolkitActions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A toolbar button names a script file; where that file lives is decided at
 * click time by the toolkit root. The failure mode this pins is the silent
 * one: launching a path that does not exist, so PowerShell prints an error in
 * a tab the operator reads as "the button is broken".
 */
class ToolkitActionsScriptTest {

    private Path toolkit;

    @BeforeEach
    void pointAtAToolkit() throws Exception {
        toolkit = Files.createTempDirectory("ume-toolkit-actions");
        ToolkitRoot.override(toolkit);
    }

    @AfterEach
    void release() {
        ToolkitRoot.override(null);
        ToolkitActions.setTabOpener(null);
    }

    @Test
    void aScriptInTheRootResolvesAndIsQuotedIntoTheCommand() throws Exception {
        Path script = toolkit.resolve("UE_FixFiles.ps1");
        Files.writeString(script, "exit 0\r\n");
        String command = ToolkitActions.NOMBRAR.commandFor(List.of("C:\\media\\a file.mkv"));
        assertEquals("& '" + script + "' 'C:\\media\\a file.mkv'", command,
                "the action runs the script the root actually has, with every argument quoted");
    }

    @Test
    void aMissingScriptIsUnavailableAndSaysWhereItLooked() throws Exception {
        assertFalse(ToolkitActions.isAvailable(ToolkitActions.NOMBRAR),
                "no file, no availability");
        IOException failure = assertThrows(IOException.class,
                () -> ToolkitActions.NOMBRAR.commandFor(List.of("C:\\media\\a.mkv")));
        assertTrue(failure.getMessage().contains("UE_FixFiles.ps1"),
                "the message names the script the button wanted");
        assertTrue(failure.getMessage().contains(toolkit.toString()),
                "and the place it searched: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(ToolkitRoot.ENV_VAR),
                "and how to point somewhere else");
    }

    @Test
    void launchingAMissingScriptNeverStartsAPowerShell() {
        ToolkitActions.setTabOpener((title, shell) -> {
            throw new AssertionError("a script that does not exist must not open a tab");
        });
        assertThrows(IOException.class, () -> ToolkitActions.launch(
                ToolkitActions.CERTIFICAR, List.of("C:\\media\\a.mkv"),
                System.getProperty("java.io.tmpdir")));
    }

    @Test
    void anAbsolutePathStaysVerbatimForTestsAndAdHocActions() throws Exception {
        Path elsewhere = Files.createTempFile("ume-adhoc", ".ps1");
        Files.writeString(elsewhere, "exit 0\r\n");
        ToolkitActions.Action adhoc = new ToolkitActions.Action(
                "ADHOC", "", elsewhere.toString(), "explicit path", false);
        assertTrue(ToolkitActions.isAvailable(adhoc),
                "an action that names an absolute file does not need the root");
        LocalShell shell = ToolkitActions.launch(adhoc, List.of(),
                System.getProperty("java.io.tmpdir"));
        try {
            assertEquals(0, shell.waitFor());
        } finally {
            shell.close();
            Files.deleteIfExists(elsewhere);
        }
    }

    @Test
    void everyRegistryActionNamesOneFileInTheRoot() {
        for (ToolkitActions.Action action : ToolkitActions.all()) {
            Path declared = Path.of(action.script());
            assertFalse(declared.isAbsolute(),
                    action.name() + " must name a file, not a machine's path");
            assertFalse(action.script().contains(".."),
                    action.name() + " stays inside the toolkit directory");
        }
    }
}
