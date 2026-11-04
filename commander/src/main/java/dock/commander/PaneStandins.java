package dock.commander;

import dock.editor.EditorPanel;
import dock.media.PlayerPanel;
import dock.viewer.ImageViewerPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JPanel;
import javax.swing.JSplitPane;

/**
 * The surfaces a file pane can be covered by: the image viewer, the text
 * editor (markdown's rendered page included), and the streaming player.
 * A remote session and the local commander browse files the same way, so
 * they present media the same way: one stand-in per side, the pane alive
 * underneath it — its directory, stacks and transfers keep working while
 * hidden — and Escape puts the table back with the keyboard on it.
 */
public final class PaneStandins {

    private final FilePane left;
    private final FilePane right;
    private final JPanel content;
    private final JSplitPane split;
    /** True when a side is folded away: its swaps are bookkeeping only. */
    private final Predicate<FilePane> sideHidden;
    /** The saved session the playback bookmarks hang on; null for an
     *  in-place browser, which has no site to remember against. */
    private final Supplier<String> siteName;

    private ImageViewerPanel viewerLeft;
    private ImageViewerPanel viewerRight;
    private EditorPanel editorLeft;
    private EditorPanel editorRight;
    private PlayerPanel playerLeft;
    private PlayerPanel playerRight;

    public PaneStandins(FilePane left, FilePane right, JPanel content, JSplitPane split,
                        Predicate<FilePane> sideHidden, Supplier<String> siteName) {
        this.left = left;
        this.right = right;
        this.content = content;
        this.split = split;
        this.sideHidden = sideHidden;
        this.siteName = siteName;
    }

    /** Points a pane's View / Edit / Play requests at these surfaces. */
    public void install(FilePane pane) {
        pane.onViewRequest(name -> openViewer(pane, name));
        pane.onMarkdownRequest(name -> openEditor(pane, name, true));
        pane.onEditRequest(name -> openEditor(pane, name));
        pane.onPlayRequest(name -> openPlayer(pane, name));
    }

    /** Swaps {@code side} out for the in-pane image viewer on one file. */
    public void openViewer(FilePane side, String firstName) {
        if (sideHidden.test(side)) return;
        closeViewer(side);       // one stand-in per side, whichever kind
        closeEditor(side);
        ImageViewerPanel viewer = new ImageViewerPanel(side.fs(), side.path(), firstName,
                side::imageSequence, side::selectEntry, () -> closeViewer(side));
        if (side == left) viewerLeft = viewer; else viewerRight = viewer;
        mount(side, viewer);
        viewer.focusCanvas();
    }

    /** Puts the file table back and hands it the keyboard. */
    public void closeViewer(FilePane side) {
        ImageViewerPanel viewer = side == left ? viewerLeft : viewerRight;
        if (viewer == null) return;
        if (side == left) viewerLeft = null; else viewerRight = null;
        mount(side, side);
        side.focusTable();
    }

    /** Swaps {@code side} out for the text editor on one file. Saves land
     *  back through the same pane's filesystem and refresh its listing —
     *  a remote edit uploads on every save. Markdown files open on the
     *  rendered page ({@code render} true, the View path) or straight on
     *  the source (false, F4) and flip between the two in place. */
    public void openEditor(FilePane side, String name) {
        openEditor(side, name, false);
    }

    public void openEditor(FilePane side, String name, boolean render) {
        if (sideHidden.test(side)) return;
        closeEditor(side);
        closeViewer(side);
        EditorPanel editor = new EditorPanel(side.fs(), side.path(), name, render,
                linked -> openEditor(side, linked, true),
                side::reload, () -> closeEditor(side));
        // The reader's convention rides the render open: the pane's cursor
        // follows the file shown. The edit path (F4) keeps the editor's
        // own rule — the cursor stands where F4 found it.
        if (render) editor.onNavigate(side::selectEntry);
        if (side == left) editorLeft = editor; else editorRight = editor;
        mount(side, editor);
        editor.focusCanvas();
    }

    /** Puts the file table back and hands it the keyboard. */
    public void closeEditor(FilePane side) {
        EditorPanel editor = side == left ? editorLeft : editorRight;
        if (editor == null) return;
        if (side == left) editorLeft = null; else editorRight = null;
        mount(side, side);
        side.focusTable();
    }

    /** Swaps {@code side} out for the in-pane streaming player on one file. */
    public void openPlayer(FilePane side, String name) {
        if (sideHidden.test(side)) return;
        closePlayer(side);        // one stand-in per side, whichever kind
        closeViewer(side);
        closeEditor(side);
        PlayerPanel player = new PlayerPanel(side.fs(), side.path(), name,
                side::mediaSequence, side::selectEntry, () -> closePlayer(side),
                new SessionPlayback(siteName.get()));
        if (side == left) playerLeft = player; else playerRight = player;
        mount(side, player);
        player.focusSurface();
    }

    /** Puts the file table back and hands it the keyboard. The player's
     *  engine and bridge token are retired before the unmount. */
    public void closePlayer(FilePane side) {
        PlayerPanel player = side == left ? playerLeft : playerRight;
        if (player == null) return;
        if (side == left) playerLeft = null; else playerRight = null;
        player.release();
        mount(side, side);
        side.focusTable();
    }

    /** Retires the stand-in on one side, whichever kind it is. */
    public void closeAll(FilePane side) {
        closeViewer(side);
        closeEditor(side);
        closePlayer(side);
    }

    /** Retires every stand-in — a closing view must not leave an engine or
     *  an unsaved buffer behind. */
    public void closeAll() {
        closeAll(left);
        closeAll(right);
    }

    /** True while the viewer stands in for this pane. */
    public boolean showingViewer(FilePane side) {
        return (side == left ? viewerLeft : viewerRight) != null;
    }

    /** True while the editor stands in for this pane. */
    public boolean showingEditor(FilePane side) {
        return (side == left ? editorLeft : editorRight) != null;
    }

    /** True while the player stands in for this pane. */
    public boolean showingPlayer(FilePane side) {
        return (side == left ? playerLeft : playerRight) != null;
    }

    /** The viewer covering this pane, or null. */
    public ImageViewerPanel viewerOf(FilePane side) {
        return side == left ? viewerLeft : viewerRight;
    }

    /** The editor covering this pane, or null. */
    public EditorPanel editorOf(FilePane side) {
        return side == left ? editorLeft : editorRight;
    }

    /** The player covering this pane, or null. */
    public PlayerPanel playerOf(FilePane side) {
        return side == left ? playerLeft : playerRight;
    }

    /** The component standing in {@code side}'s slot: its stand-in, or the
     *  pane itself while nothing covers it. */
    public Component occupant(FilePane side) {
        Component cover = coverOf(side);
        return cover != null ? cover : side;
    }

    /** The stand-in covering {@code side}, or null when its table shows. */
    public Component coverOf(FilePane side) {
        ImageViewerPanel viewer = viewerOf(side);
        if (viewer != null) return viewer;
        EditorPanel editor = editorOf(side);
        if (editor != null) return editor;
        return playerOf(side);
    }

    /** Gives the keyboard to whatever legitimately occupies the slot: the
     *  stand-in when one covers the pane, the table otherwise. */
    public void focusOccupant(FilePane side) {
        ImageViewerPanel viewer = viewerOf(side);
        if (viewer != null) {
            viewer.focusCanvas();
            return;
        }
        EditorPanel editor = editorOf(side);
        if (editor != null) {
            editor.focusCanvas();
            return;
        }
        PlayerPanel player = playerOf(side);
        if (player != null) player.focusSurface();
        else side.focusTable();
    }

    /** True when any editor holds changes that a teardown would lose. */
    public boolean hasUnsavedEdits() {
        return (editorLeft != null && editorLeft.isDirty())
                || (editorRight != null && editorRight.isDirty());
    }

    /** Seats a stand-in (or the pane itself back again) in {@code side}'s
     *  slot: the commander split for one side, or the whole window while
     *  the other side is folded away. A folded side's slot is off-screen,
     *  so its swaps are bookkeeping — the display stands untouched. */
    private void mount(FilePane side, Component standin) {
        if (sideHidden.test(side)) return;
        if (sideHidden.test(left) || sideHidden.test(right)) {
            content.removeAll();
            content.add(standin, BorderLayout.CENTER);
        } else {
            swapSplitSide(side, standin);
        }
        content.revalidate();
        content.repaint();
    }

    /**
     * Replaces one side of the commander split, holding the separator at
     * its exact position. A child swap arms the split layout's
     * reset-to-preferred-sizes pass (BasicSplitPaneUI sets doReset on
     * every add and remove), which would park the divider wherever the
     * newcomer's preferred width ends. Re-asserting the location after
     * the swap re-arms the "location is set" flag instead, so the pending
     * layout puts the divider back where it was — the pane and its
     * stand-in keep the same width, with no jump in either direction. A
     * divider dragged while a viewer is open survives the close the same
     * way.
     */
    private void swapSplitSide(FilePane side, Component standing) {
        int divider = split.getDividerLocation();
        if (side == left) split.setLeftComponent(standing);
        else split.setRightComponent(standing);
        split.setDividerLocation(divider);
    }
}
