import dock.terminal.ToolkitActions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toolbar's shape: two labelled groups, the upload as the bridge between
 * them, and script settings off the row entirely. These pins exist because the
 * row is rebuilt from a registry — an edit there silently reorders real buttons.
 */
class ToolkitToolbarLayoutTest {

    private static final String PLAIN = "F:\\Media";
    private static final String GODMODE = "F:\\godmode\\godmode\\godmode\\godmode\\godmode";

    /** Every slot's text: captions and button labels, in the toolbar's order. */
    private static List<String> slots(String path) {
        List<String> out = new ArrayList<>();
        for (ToolkitActions.Entry entry : ToolkitActions.layoutFor(path)) {
            out.add(entry.isSection() ? entry.caption() : entry.action().name());
        }
        return out;
    }

    @Test
    void localGroupLeadsAndRemoteGroupFollowsTheUpload() {
        assertEquals(List.of(
                "LOCAL:", "NOMBRAR", "NOMBRAR S", "EXTRAER", "CERTIFICAR",
                "RESPALDO", "COMMANDER", "UPLOAD",
                "REMOTE:", "NOMBRAR REP", "RENAME REP", "CERTIFICAR REP",
                "RESPALDO REP", "PUSH LATEST"), slots(PLAIN));
    }

    @Test
    void selectiveRenameIsLabelledNOMBRARs() {
        assertEquals("NOMBRAR S", ToolkitActions.RENAME.name());
        assertEquals("Selective rename (local)", ToolkitActions.RENAME.description());
    }

    @Test
    void scriptSettingsLeavesTheToolbarForTheHelpMenu() {
        assertFalse(slots(PLAIN).contains("CFG"));
        assertFalse(slots(GODMODE).contains("CFG"));
        assertTrue(ToolkitActions.all().contains(ToolkitActions.CFG),
                "CFG stays in the registry — the pane's Toolkit Actions menu lists it");
    }

    @Test
    void godmodeInsertsThePlaylistActionsIntoTheLocalGroup() {
        List<String> godmode = slots(GODMODE);
        assertTrue(godmode.indexOf("GEN M3U") > godmode.indexOf("CERTIFICAR"),
                "playlist actions come after certifying: " + godmode);
        assertTrue(godmode.indexOf("SUBIR M3U") < godmode.indexOf("RESPALDO"),
                "and before the backup: " + godmode);
    }

    @Test
    void everyCaptionIntroducesAtLeastOneButton() {
        for (String path : new String[] {PLAIN, GODMODE, null}) {
            List<ToolkitActions.Entry> layout = ToolkitActions.layoutFor(path);
            for (int i = 0; i < layout.size(); i++) {
                if (!layout.get(i).isSection()) continue;
                assertTrue(i + 1 < layout.size() && !layout.get(i + 1).isSection(),
                        "caption with nothing under it: " + layout.get(i).caption());
            }
        }
    }

    @Test
    void theRowNeverStartsWithAButton() {
        for (String path : new String[] {PLAIN, GODMODE, null}) {
            assertTrue(ToolkitActions.layoutFor(path).get(0).isSection(),
                    "the first slot names the group the operator is working in");
        }
    }
}
