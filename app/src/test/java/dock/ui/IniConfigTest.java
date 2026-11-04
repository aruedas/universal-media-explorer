package dock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fleet INI as the sidebar reads it: a server without explicit M/S keys
 * defaults to its role — index-only starts unchecked, a regular serving
 * host starts with both — and an operator's toggle writes itself back into
 * the file without disturbing anything around it.
 */
class IniConfigTest {

    private static final String FLEET = """
            ; ----SERVERS
            serverOrder=3,2,7
            ; site3 is index-only: no M/S keys, they default to its role.
            serverIndexOnly3=true
            site3=203.0.113.10
            local3=true
            home3=/srv/storage
            name3=srv-index
            ; site2 has explicit keys written by an earlier toggle.
            serverMoviesEnabled2=true
            serverSeriesEnabled2=false
            site2=198.51.100.20
            name2=srv-upload
            ; site7 is a plain regular host: no M/S keys at all.
            site7=192.0.2.30
            name7=srv-regular
            """;

    @Test
    void anIndexOnlyServerStartsUnchecked() throws IOException {
        Path ini = write(FLEET);
        IniConfig config = IniConfig.load(ini);
        IniConfig.MediaServer index = config.servers().get(0);
        assertEquals("srv-index", index.name());
        assertTrue(index.indexOnly());
        assertFalse(index.moviesEnabled(), "index-only defaults to no movies");
        assertFalse(index.seriesEnabled(), "index-only defaults to no series");
    }

    @Test
    void aRegularServerStartsWithBoth() throws IOException {
        Path ini = write(FLEET);
        IniConfig config = IniConfig.load(ini);
        IniConfig.MediaServer regular = config.servers().get(2);
        assertEquals("srv-regular", regular.name());
        assertFalse(regular.indexOnly());
        assertTrue(regular.moviesEnabled());
        assertTrue(regular.seriesEnabled(), "no explicit key means the regular default");
    }

    @Test
    void anExplicitKeyAlwaysWins() throws IOException {
        Path ini = write(FLEET);
        IniConfig config = IniConfig.load(ini);
        IniConfig.MediaServer upload = config.servers().get(1);
        assertFalse(upload.seriesEnabled(), "the operator's written-off switch stays off");
    }

    @Test
    void aToggleUpdatesAnExistingKeyInPlace() throws IOException {
        Path ini = write(FLEET);
        IniConfig.setServerToggle(ini, 2, "serverSeriesEnabled", true);
        assertEquals("serverSeriesEnabled2=true", readLine(ini, "serverSeriesEnabled2"),
                "the existing line is rewritten where it stood");
        assertTrue(IniConfig.load(ini).servers().get(1).seriesEnabled());
    }

    @Test
    void aToggleLandsInItsServerBlockWhenAbsent() throws IOException {
        Path ini = write(FLEET);
        IniConfig.setServerToggle(ini, 3, "serverMoviesEnabled", true);
        IniConfig config = IniConfig.load(ini);
        assertTrue(config.servers().get(0).moviesEnabled());
        assertEquals("serverMoviesEnabled3=true", readLine(ini, "serverMoviesEnabled3"),
                "the key exists now, next read takes it");
        int site = indexOf(ini, "site3=");
        int added = indexOf(ini, "serverMoviesEnabled3=");
        assertEquals(site + 1, added, "inserted right after the server's site line");
    }

    @Test
    void theFileAroundTheToggleSurvives() throws IOException {
        Path ini = write(FLEET);
        IniConfig.setServerToggle(ini, 3, "serverSeriesEnabled", false);
        String text = Files.readString(ini, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("; ----SERVERS"), "the leading comment stands");
        assertTrue(text.contains("name3=srv-index"), "the server block stands");
        assertTrue(text.contains("name2=srv-upload"), "the other server stands");
        assertTrue(text.endsWith("\n"), "the file still ends with a newline");
        assertFalse(text.contains("\r\n\r\n"), "no doubled terminators were added");
    }

    @Test
    void togglingAnUnknownServerIsRefused() throws IOException {
        Path ini = write(FLEET);
        assertThrows(IOException.class,
                () -> IniConfig.setServerToggle(ini, 9, "serverMoviesEnabled", true));
    }

    @Test
    void windowsEndingsSurviveTheRewrite() throws IOException {
        Path ini = Files.createTempFile("dock-ini", ".ini");
        Files.write(ini, FLEET.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        IniConfig.setServerToggle(ini, 3, "serverMoviesEnabled", false);
        String text = Files.readString(ini, StandardCharsets.UTF_8);
        assertTrue(text.contains("\r\n"), "the CRLF endings stay");
        assertFalse(text.replace("\r\n", "").contains("\n"), "no bare LF leaked in");
    }

    private static Path write(String content) throws IOException {
        Path ini = Files.createTempFile("dock-ini", ".ini");
        Files.writeString(ini, content, StandardCharsets.UTF_8);
        return ini;
    }

    private static String readLine(Path ini, String key) throws IOException {
        for (String line : Files.readAllLines(ini, StandardCharsets.UTF_8)) {
            if (line.strip().startsWith(key + "=")) return line.strip();
        }
        return null;
    }

    private static int indexOf(Path ini, String prefix) throws IOException {
        List<String> lines = Files.readAllLines(ini, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith(prefix)) return i;
        }
        return -1;
    }
}
