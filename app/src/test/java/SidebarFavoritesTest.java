import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dock.ui.Sidebar;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The sidebar's favorite row labels: the folder's own name, and never two
 * identical rows in one shelf — a repeated name carries its parent.
 */
class SidebarFavoritesTest {

    @Test
    void distinctFoldersKeepTheirPlainNames() {
        assertEquals(List.of("UP", "Torrent", "Downloads"),
                Sidebar.favoriteLabels(List.of("F:\\UP", "f:\\Torrent", "F:\\Downloads")));
    }

    @Test
    void twoDownloadsTellApartByWhereTheyAre() {
        assertEquals(List.of("Downloads — F:\\", "Downloads — C:\\Users\\operator"),
                Sidebar.favoriteLabels(List.of("F:\\Downloads", "C:\\Users\\operator\\Downloads")));
    }

    @Test
    void aDriveRootShowsItsPath() {
        // File.getName() is empty for a root: the path is the only label it has.
        assertEquals(List.of("D:\\"), Sidebar.favoriteLabels(List.of("D:\\")));
    }

    @Test
    void aTrailingSeparatorStillNamesTheFolder() {
        assertEquals(List.of("unbranded"),
                Sidebar.favoriteLabels(
                        List.of("C:\\Projects\\alpha\\target\\release\\flavors\\unbranded\\")));
    }

    @Test
    void aWholeShelfRendersWithoutRepeatingItself() {
        // What an import actually lands: fifteen commander favorites plus the
        // toolkit's, including the two Downloads.
        List<String> shelf = List.of(
                "F:\\Downloads", "C:\\Users\\operator\\Downloads", "D:\\", "f:\\Torrent", "F:\\UP",
                "c:\\projects\\alpha", "C:\\Projects\\beta-tv",
                "c:\\projects\\workshop\\gamma", "c:\\projects\\workshop\\delta");
        List<String> labels = Sidebar.favoriteLabels(shelf);
        assertEquals(shelf.size(), labels.size());
        assertEquals(shelf.size(), labels.stream().distinct().count(), labels.toString());
        assertTrue(labels.contains("Downloads — F:\\"), labels.toString());
        assertTrue(labels.contains("alpha"), "one of a kind stays bare: " + labels);
    }
}
