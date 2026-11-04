package dock.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * favorites.json: the shelf keeps what the last run put on it, folds a folder
 * listed twice under a different spelling, and never takes a broken file as
 * permission to forget.
 */
class FavoritesTest {

    private Path dir;

    @BeforeEach
    void isolate() throws Exception {
        dir = Files.createTempDirectory("dock-favorites");
        AppPaths.override(dir);
    }

    @Test
    void noFileMeansAnEmptyShelf() {
        assertEquals(List.of(), Favorites.load());
    }

    @Test
    void whatWasSavedComesBackInOrder() throws IOException {
        Favorites.save(new ArrayList<>(List.of("F:\\UP", "D:\\")));
        assertEquals(List.of("F:\\UP", "D:\\"), Favorites.load());
    }

    @Test
    void aBrokenFileReadsAsEmptyInsteadOfCrashing() throws IOException {
        Files.writeString(dir.resolve("favorites.json"), "{ not json at all");
        assertEquals(List.of(), Favorites.load());
    }

    @Test
    void aFolderAlreadyOnTheShelfIsNotAddedTwice() {
        List<String> shelf = new ArrayList<>();
        assertEquals(1, Favorites.addAll(shelf, List.of("F:\\UP")));
        assertEquals(0, Favorites.addAll(shelf, List.of("f:\\up")));
        assertEquals(0, Favorites.addAll(shelf, List.of("F:\\UP\\")));
        assertEquals(List.of("F:\\UP"), shelf, "the first spelling is the one kept");
    }

    @Test
    void aDriveAndItsRootAreTheSameFolder() {
        List<String> shelf = new ArrayList<>();
        assertEquals(1, Favorites.addAll(shelf, List.of("D:\\")));
        assertEquals(0, Favorites.addAll(shelf, List.of("D:")));
        assertTrue(Favorites.contains(shelf, "d:\\"));
    }

    @Test
    void removeFindsAFavoriteWhateverItsSpelling() {
        List<String> shelf = new ArrayList<>();
        Favorites.addAll(shelf, List.of("D:\\Media"));
        assertTrue(Favorites.remove(shelf, "d:\\media\\"));
        assertTrue(shelf.isEmpty());
        assertFalse(Favorites.remove(shelf, "D:\\Media"));
    }

    @Test
    void blanksAreNotFolders() {
        List<String> shelf = new ArrayList<>();
        assertEquals(0, Favorites.addAll(shelf, Arrays.asList("", "   ", null)));
        assertTrue(shelf.isEmpty());
    }

    @Test
    void blanksAlreadyOnDiskAreDroppedOnTheNextRead() throws IOException {
        Files.writeString(dir.resolve("favorites.json"), "[\"C:\\\\ok\", \"\", null]");
        assertEquals(List.of("C:\\ok"), Favorites.load());
    }
}
