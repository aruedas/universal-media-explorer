package dock.ui;

import dock.commander.FilePane;
import dock.commander.PaneStandins;
import dock.core.fs.LocalFs;
import dock.kit.Glyphs;
import dock.kit.Toast;
import dock.kit.ViewSettings;
import java.awt.BorderLayout;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

/**
 * A dual-pane local file browser — the app's startup view. The second pane
 * starts folded away: one pane is the default view, and the footer's edge
 * buttons fold it back in (or swap which side shows — one pane must stay
 * visible, the same rule a session follows). When both show, the split is
 * 50/50 and focus hops between them with the left/right arrow keys.
 *
 * Media rides the same surfaces a remote session uses: Enter or F3 on a
 * file covers its pane with the image viewer, the rendered reader or the
 * streaming player, F4 opens the editor, Escape puts the table back. A
 * folded pane keeps its directory and any transfer running against it.
 */
public final class LocalCommander extends JPanel {

    private final FilePane left;
    private final FilePane right;
    private FilePane active;
    private final JSplitPane split;
    /** Viewer / editor / player for the two panes. A folded side's swaps
     *  are bookkeeping only, exactly as in a session. */
    private final PaneStandins standins;
    /** One pane must stay visible; the second starts folded — the default
     *  view is a single pane and the footer's edge buttons bring it back. */
    private boolean leftHidden;
    private boolean rightHidden = true;
    private int lastDivider;

    public LocalCommander() {
        super(new BorderLayout());
        left = new FilePane(LocalFs.INSTANCE);
        right = new FilePane(LocalFs.INSTANCE);
        active = left;

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.5);
        split.setContinuousLayout(true);

        // null site name: an in-place browser has no saved session to hang
        // playback bookmarks on.
        standins = new PaneStandins(left, right, this, split, this::sideHidden, () -> null);
        standins.install(left);
        standins.install(right);
        applyLayout();

        FocusHop hop = new FocusHop();
        left.addMouseListener(hop);
        right.addMouseListener(hop);
        left.addKeyListener(hop);
        right.addKeyListener(hop);
        left.table().addMouseListener(hop);
        right.table().addMouseListener(hop);
        left.table().addKeyListener(hop);
        right.table().addKeyListener(hop);
        left.requestFocusInWindow();

        // The local view is remembered like a session: both panes come back
        // where the last run left them, the second one unfolded only if it
        // stood that way. A directory that no longer exists falls back to
        // the pane's home — the listing commits on success only.
        var dirs = dock.core.config.LastLocations.local();
        if (dirs != null) {
            if (dirs.left() != null && !dirs.left().isBlank())
                left.navigatePlace(LocalFs.INSTANCE, dirs.left());
            if (dirs.right() != null && !dirs.right().isBlank())
                right.navigatePlace(LocalFs.INSTANCE, dirs.right());
            if (dirs.secondPane()) setRightPaneHidden(false);
        }

        // Every landed listing records the pair again — latest wins, same
        // debounced write a session's path memory uses.
        left.onDirLanded((fs, dir) -> rememberPanes());
        right.onDirLanded((fs, dir) -> rememberPanes());
    }

    /** The pair last written, so only the newest landing writes. */
    private volatile long memoryGen;

    private void rememberPanes() {
        long gen = ++memoryGen;
        String l = left.path();
        String r = right.path();
        boolean second = !rightHidden;
        Thread.ofVirtual().name("dock-remember").start(() -> {
            if (gen != memoryGen) return;
            dock.core.config.LastLocations.putLocal(l, r, second);
        });
    }

    /** The pane that currently has keyboard focus. */
    public FilePane activePane() { return active; }

    public FilePane leftPane() { return left; }
    public FilePane rightPane() { return right; }

    public boolean leftPaneHidden() { return leftHidden; }
    public boolean rightPaneHidden() { return rightHidden; }

    public void setLeftPaneHidden(boolean hidden) { setPaneHidden(left, hidden); }
    public void setRightPaneHidden(boolean hidden) { setPaneHidden(right, hidden); }

    /** Edit menu: copy or move the active pane's selection to the other
     *  one — the same engine call a session's F5/F6 makes. A folded target
     *  unfolds first: moving into a hidden pane reads as lost. */
    public void transferToOther(boolean move) {
        FilePane from = active;
        FilePane to = from == left ? right : left;
        java.util.List<dock.core.fs.FileEntry> selected = from.selectedEntries();
        if (selected.isEmpty()) {
            Toast.show(this, "Nothing selected in the active pane.", Glyphs.INFO);
            return;
        }
        if (rightHidden) setRightPaneHidden(false);
        if (to.fs().immutableListing(to.path())) {
            Toast.show(this, "This listing can't receive files — it's read-only.",
                    Glyphs.WARNING);
            return;
        }
        if (to.path().equals(from.path()) && to.fs() == from.fs()) {
            Toast.show(this, "Source and target are the same directory.", Glyphs.WARNING);
            return;
        }
        dock.core.transfer.TransferEngine.GLOBAL.enqueue(
                from.fs(), from.path(), selected, to.fs(), to.path(), move);
    }

    private boolean sideHidden(FilePane side) {
        return side == left ? leftHidden : rightHidden;
    }

    /**
     * Folds one pane away — or brings it back. Same rule as a session: the
     * pane only leaves the display — it stays alive with its directory and
     * any stand-in covering it, so transfers keep working against it — and
     * folding the last visible pane is refused.
     */
    private void setPaneHidden(FilePane side, boolean hidden) {
        if (sideHidden(side) == hidden) return;
        if (hidden && sideHidden(side == left ? right : left)) {
            Toast.show(this, "The other pane is already hidden — show it first.",
                    Glyphs.INFO);
            return;
        }
        if (side == left) leftHidden = hidden; else rightHidden = hidden;
        applyLayout();
        // The keyboard follows the pane that stays on screen.
        active = rightHidden ? left : right;
        standins.focusOccupant(active);
    }

    /** Seats each side's occupant the way the fold flags pick: the lone
     *  visible side fills the window, or the split shows both with the
     *  divider where it stood. */
    private void applyLayout() {
        if (split.getParent() == this && split.getDividerLocation() > 0)
            lastDivider = split.getDividerLocation();
        removeAll();
        if (leftHidden || rightHidden) {
            add(standins.occupant(leftHidden ? right : left), BorderLayout.CENTER);
        } else {
            split.setLeftComponent(standins.occupant(left));
            split.setRightComponent(standins.occupant(right));
            add(split, BorderLayout.CENTER);
            // Re-seating the split's children arms its reset-to-preferred
            // pass; re-asserting the remembered location lands the divider
            // back where it stood (centered until one exists).
            SwingUtilities.invokeLater(() -> {
                if (split.getWidth() > 0) {
                    split.setDividerLocation(lastDivider > 0 ? lastDivider : 0.5);
                }
            });
        }
        revalidate();
        repaint();
    }

    /** The stand-in covering {@code side} — viewer, editor or player — or
     *  null while its table shows. */
    public java.awt.Component standinForTest(FilePane side) {
        return standins.coverOf(side);
    }

    /** Pushes the View-menu hidden-files setting into both panes. */
    public void applyViewSettings() {
        left.applyViewSettings();
        right.applyViewSettings();
    }

    private void setActive(FilePane pane) {
        if (pane != null && pane != active) {
            active = pane;
            // The table is off-screen while a stand-in covers its pane; the
            // keyboard goes to whatever actually occupies the slot.
            standins.focusOccupant(pane);
        }
    }

    /** Mouse or key on a pane makes it the active one. */
    private class FocusHop implements MouseListener, KeyListener {
        @Override public void mousePressed(MouseEvent e) {
            setActive(paneFor(e.getComponent()));
        }
        @Override public void mouseClicked(MouseEvent e) {}
        @Override public void mouseReleased(MouseEvent e) {}
        @Override public void mouseEntered(MouseEvent e) {}
        @Override public void mouseExited(MouseEvent e) {}

        @Override public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_LEFT
                    || e.getKeyCode() == KeyEvent.VK_RIGHT) {
                FilePane target = (active == left) ? right : left;
                if (!sideHidden(target)) setActive(target);
            }
        }
        @Override public void keyTyped(KeyEvent e) {}
        @Override public void keyReleased(KeyEvent e) {}

        private FilePane paneFor(java.awt.Component c) {
            if (c == left || SwingUtilities.isDescendingFrom(c, left)) return left;
            if (c == right || SwingUtilities.isDescendingFrom(c, right)) return right;
            return active;
        }
    }
}
