package dock.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * last_locations.json: the window's box, the local commander's panes and
 * each session's last directories come back between runs, and a broken
 * file reads as nothing remembered rather than crashing the startup.
 */
class LastLocationsTest {

    private Path dir;

    @BeforeEach
    void isolate() throws Exception {
        dir = Files.createTempDirectory("dock-lastloc");
        AppPaths.override(dir);
    }

    @Test
    void noFileMeansNothingRemembered() {
        assertNull(LastLocations.window());
        assertNull(LastLocations.local());
        assertNull(LastLocations.session("srv-upload"));
    }

    @Test
    void theWindowBoxComesBackWhole() {
        LastLocations.putWindow(new LastLocations.WindowBox(11, 22, 1280, 800, true));
        LastLocations.WindowBox box = LastLocations.window();
        assertEquals(11, box.x());
        assertEquals(22, box.y());
        assertEquals(1280, box.w());
        assertEquals(800, box.h());
        assertTrue(box.maximized());
    }

    @Test
    void theLocalPanesComeBackBoth() {
        LastLocations.putLocal("F:\\UP", "D:\\- TV", true);
        LastLocations.LocalDirs dirs = LastLocations.local();
        assertEquals("F:\\UP", dirs.left());
        assertEquals("D:\\- TV", dirs.right());
        assertTrue(dirs.secondPane());
    }

    @Test
    void eachSessionKeepsItsOwnPair() {
        LastLocations.putSession("srv-upload", "D:\\Media", "/srv/www");
        LastLocations.putSession("srv-index", "E:\\media-repair", "/srv/storage");
        assertEquals("/srv/www", LastLocations.session("srv-upload").remote());
        assertEquals("E:\\media-repair", LastLocations.session("srv-index").local());
        assertNull(LastLocations.session("srv-regular"), "a session never seen remembers nothing");
    }

    @Test
    void aLaterWriteSupersedesTheOldOne() {
        LastLocations.putSession("srv-upload", "C:\\old", "/old");
        LastLocations.putSession("srv-upload", "C:\\new", "/new");
        assertEquals("C:\\new", LastLocations.session("srv-upload").local());
        assertEquals("/new", LastLocations.session("srv-upload").remote());
    }

    @Test
    void unrelatedEntriesSurviveARewrite() {
        LastLocations.putWindow(new LastLocations.WindowBox(1, 2, 3, 4, false));
        LastLocations.putLocal("C:\\a", "C:\\b", false);
        LastLocations.putSession("srv-upload", "C:\\s", "/s");
        LastLocations.putLocal("C:\\c", "C:\\d", true);
        assertEquals(1, LastLocations.window().x(), "the window box is untouched");
        assertEquals("/s", LastLocations.session("srv-upload").remote(), "the session is untouched");
        assertEquals("C:\\c", LastLocations.local().left(), "the local panes are the newest");
        assertTrue(LastLocations.local().secondPane());
    }

    @Test
    void aBrokenFileReadsAsNothingRemembered() throws IOException {
        LastLocations.putLocal("C:\\a", "C:\\b", false);
        Files.writeString(dir.resolve("last_locations.json"), "{ not json");
        assertNull(LastLocations.local());
        assertNull(LastLocations.window());
    }

    @Test
    void aGarbledWindowEntryReadsAsNothing() throws IOException {
        Files.writeString(dir.resolve("last_locations.json"),
                "{\"window\": {\"x\": \"left of nowhere\"}}");
        assertNull(LastLocations.window());
    }
}
