package dock.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reading the favorites the operator keeps in SimpleCommander's
 * {@code settings.toml} and the toolkit's {@code saved_locations.json} —
 * the shapes those two writers actually produce, backslashes, BOM and
 * PowerShell's one-element array included.
 */
class SimpleCommanderImportTest {

    private Path dir;

    @BeforeEach
    void isolate() throws Exception {
        dir = Files.createTempDirectory("dock-scimport");
        AppPaths.override(dir);
    }

    private Path settings(String body) throws IOException {
        return Files.writeString(dir.resolve("settings.toml"), body);
    }

    private Path saved(String body) throws IOException {
        return Files.writeString(dir.resolve("saved_locations.json"), body);
    }

    // ── settings.toml ────────────────────────────────────────────────

    @Test
    void theCommandersOwnFavoritesComeInOrder() throws IOException {
        Path f = settings("""
                [session]
                theme = "amoled"
                favorites = [
                    'F:\\Downloads',
                    'C:\\Projects\\alpha',
                ]

                [m3u]
                url = "https://example"
                """);
        assertEquals(List.of("F:\\Downloads", "C:\\Projects\\alpha"),
                SimpleCommanderImport.read(f, null).stream().map(SimpleCommanderImport.Favorite::path).toList(),
                "literal strings keep their backslashes verbatim");
    }

    @Test
    void aFavoriteListUnderAnotherTableIsNotTheSessionShelf() throws IOException {
        Path f = settings("""
                [other]
                favorites = ['Z:\\wrong']

                [session]
                favorites = ['Z:\\right']
                """);
        assertEquals(List.of("Z:\\right"),
                SimpleCommanderImport.read(f, null).stream().map(SimpleCommanderImport.Favorite::path).toList(),
                "only the [session] table holds the sidebar's favorites");
    }

    @Test
    void aKeyThatIsNotFavoritesIsIgnored() throws IOException {
        Path f = settings("""
                [session]
                tree_expanded = ['Z:\\expanded']
                favorites = ['Z:\\real']
                """);
        assertEquals(List.of("Z:\\real"),
                SimpleCommanderImport.read(f, null).stream().map(SimpleCommanderImport.Favorite::path).toList());
    }

    @Test
    void aSingleLineArrayIsReadTheSameWay() throws IOException {
        Path f = settings("[session]\nfavorites = ['A:\\one', 'B:\\two']\n");
        assertEquals(List.of("A:\\one", "B:\\two"),
                SimpleCommanderImport.read(f, null).stream().map(SimpleCommanderImport.Favorite::path).toList());
    }

    @Test
    void doubleQuotedPathsAreUnescaped() throws IOException {
        Path f = settings("[session]\nfavorites = [\"F:\\\\UP\"]\n");
        assertEquals(List.of("F:\\UP"),
                SimpleCommanderImport.read(f, null).stream().map(SimpleCommanderImport.Favorite::path).toList(),
                "a basic string writes one backslash as two");
    }

    @Test
    void anEmptyOrMissingShelfContributesNothing() throws IOException {
        Path f = settings("[session]\nfavorites = []\n");
        assertTrue(SimpleCommanderImport.read(f, null).isEmpty());
        assertTrue(SimpleCommanderImport.read(dir.resolve("absent.toml"), null).isEmpty());
    }

    @Test
    void eachFavoriteCarriesTheAppItCameFrom() throws IOException {
        Path f = settings("[session]\nfavorites = ['F:\\UP']\n");
        Path s = saved("[\"D:\\\\torrents\"]");
        List<SimpleCommanderImport.Favorite> all = SimpleCommanderImport.read(f, s);
        assertEquals(2, all.size());
        assertEquals("SimpleCommander", all.get(0).origin());
        assertEquals("Media Commander", all.get(1).origin());
    }

    // ── saved_locations.json ─────────────────────────────────────────

    @Test
    void aPowerShellBomDoesNotVoidTheList() throws IOException {
        Path s = saved("\uFEFF[\"f:\\\\Torrent\", \"F:\\\\UP\"]");
        assertEquals(List.of("f:\\Torrent", "F:\\UP"),
                SimpleCommanderImport.read(null, s).stream().map(SimpleCommanderImport.Favorite::path).toList());
    }

    @Test
    void oneSavedLocationArrivesWithoutItsArray() throws IOException {
        // ConvertTo-Json unwraps a single element — the toolkit writes a bare string.
        Path s = saved("\"F:\\\\UP\"");
        assertEquals(List.of("F:\\UP"),
                SimpleCommanderImport.read(null, s).stream().map(SimpleCommanderImport.Favorite::path).toList());
    }

    @Test
    void aMangledLocationsFileIsSilentRatherThanFatal() throws IOException {
        assertTrue(SimpleCommanderImport.read(null, saved("{ this is not an array")).isEmpty());
        assertTrue(SimpleCommanderImport.read(null, saved("[]")).isEmpty());
        assertTrue(SimpleCommanderImport.read(null, dir.resolve("absent.json")).isEmpty());
    }

    // ── the import itself ────────────────────────────────────────────

    @Test
    void aFolderListedByBothAppsLandsOnTheShelfOnce() throws IOException {
        Path f = settings("[session]\nfavorites = ['F:\\Downloads']\n");
        Path s = saved("[\"F:\\\\Downloads\"]");
        List<String> shelf = new ArrayList<>();
        var result = SimpleCommanderImport.importInto(shelf, f, s);
        assertEquals(List.of("F:\\Downloads"), shelf);
        assertEquals(1, result.added());
        assertEquals(1, result.alreadyHere(), "the second copy is counted, not duplicated");
    }

    @Test
    void anEmptyShelfTakesEverythingInOrder() throws IOException {
        Path f = settings("[session]\nfavorites = ['F:\\UP', 'D:\\']\n");
        Path s = saved("[\"F:\\\\Torrent\"]");
        List<String> shelf = new ArrayList<>();
        var result = SimpleCommanderImport.importInto(shelf, f, s);
        assertEquals(List.of("F:\\UP", "D:\\", "F:\\Torrent"), shelf);
        assertEquals(3, result.added());
        assertEquals("2 from SimpleCommander, 1 from Media Commander", result.source());
        assertEquals("Imported 3 favorites — 2 from SimpleCommander, 1 from Media Commander",
                result.summary());
    }

    @Test
    void aShelfThatAlreadyHasThemSaysSo() throws IOException {
        Path f = settings("[session]\nfavorites = ['F:\\UP']\n");
        List<String> shelf = new ArrayList<>(List.of("f:\\up\\"));
        var result = SimpleCommanderImport.importInto(shelf, f, null);
        assertEquals(0, result.added());
        assertEquals(1, result.alreadyHere());
        assertEquals("Nothing new — 1 favorite already on the shelf.", result.summary());
    }

    @Test
    void nothingToImportIsReportedAsThat() throws IOException {
        List<String> shelf = new ArrayList<>();
        var result = SimpleCommanderImport.importInto(shelf, null, null);
        assertEquals(0, result.added());
        assertEquals("Nothing to import — no favorites found in SimpleCommander.",
                result.summary());
    }

    @Test
    void anImportedPathNavigatesAsWritten() throws IOException {
        // The shelf stores paths verbatim: what Java opens must be what
        // SimpleCommander wrote, separators and all. A literal (single-quoted)
        // TOML string is where that path comes from.
        Path folder = Files.createDirectories(dir.resolve("Deep").resolve("Name"));
        List<String> shelf = new ArrayList<>();
        Path f = settings("[session]\nfavorites = ['" + folder + "']\n");
        SimpleCommanderImport.importInto(shelf, f, null);
        assertEquals(folder.toString(), shelf.get(0));
        assertTrue(Files.isDirectory(Path.of(shelf.get(0))), shelf.toString());
    }
}
