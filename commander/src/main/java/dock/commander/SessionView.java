package dock.commander;

import dock.kit.ViewSettings;
import dock.kit.Toast;
import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.Tokens;
import dock.core.config.Site;
import dock.core.config.Sites;
import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import dock.core.fs.LocalFs;
import dock.core.transfer.TransferEngine;
import dock.core.transfer.TransferJob;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** One open connection: local pane | remote pane (Commander layout).
 * A fresh session puts the keyboard in the remote listing (nothing
 * preselected); LEFT/RIGHT hop it between the panes. */
public final class SessionView extends javax.swing.JPanel
        implements dock.kit.tabs.SessionTab {

    // A sleeping NAS or a flaky link can outlast any fixed retry budget;
    // after the initial ramp retries continue indefinitely at a capped pace.
    private static final long[] RETRY_DELAYS_MS = {2_000, 5_000, 10_000, 30_000};

    private FileSystem remoteFs;
    private final dock.core.session.Session session;
    /** The session's preferred name (tab title); blank falls back to the endpoint. */
    private final String title;
    private final FilePane local;
    private final FilePane remote;
    private FilePane lastActive;
    private final JSplitPane split;
    /** Swaps between the commander split and the lone visible pane. */
    private final JPanel content = new JPanel(new BorderLayout());
    /** Pane visibility: a folded side leaves the display — the other
     *  fills the window — but keeps its directory and any stand-in
     *  covering it alive underneath. One side always stays visible. */
    private boolean localHidden;
    private boolean remoteHidden;
    /** The divider's last standing position, restored when both sides
     *  return after an excursion. */
    private int lastDivider = -1;
    /** The surfaces that cover a pane — image viewer, text editor, media
     *  player — with the slot bookkeeping they share. One stand-in per
     *  side; the pane it covers stays alive underneath, so its directory,
     *  stacks and transfers (F5/F6 act on the last active pane) keep
     *  working while hidden. The player holds live resources (an engine, a
     *  bridge token), so retiring it always runs through its release. */
    private final PaneStandins standins;
    /** The saved session this view belongs to, once applied — the panes
     *  reopen at the directories it remembers and navigation records back
     *  under it. Ad-hoc sessions stay null: nothing to key memory on. */
    private String siteName;
    /** The last plain directory each side showed (archive interiors don't
     *  count — restoring into a mount is not worth it). */
    private String lastLocalDir;
    private String lastRemoteDir;
    /** The pair last written, to skip no-op writes; bumped per pending
     *  write so only the newest one lands. */
    private volatile long memoryGen;
    private String flushedLocal, flushedRemote;

    private final JPanel banner = new JPanel(new BorderLayout(Tokens.GAP_2, 0));
    private final JLabel bannerText = new JLabel();
    private final JButton reconnectNow = new JButton("Reconnect now");
    private volatile boolean closing = false;
    private volatile boolean reconnecting = false;
    private volatile int reconnectAttempt = 0;

    public SessionView(dock.core.session.Session session, String title) {
        super(new BorderLayout());
        this.session = session;
        this.remoteFs = session.fs();
        this.title = title == null || title.isBlank() ? remoteFs.label() : title;
        this.local = new FilePane(LocalFs.INSTANCE);
        this.remote = new FilePane(remoteFs);
        this.lastActive = remote;

        buildBanner();

        FocusAdapter tracker = new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                lastActive = paneOf(e.getSource());
            }
        };
        local.table().addFocusListener(tracker);
        remote.table().addFocusListener(tracker);

        // A fresh session lands keyboard-first: when the remote listing
        // arrives, the table takes focus — no entry is preselected, the
        // first arrow-down starts the scan. A background tab's focus
        // request quietly fails.
        remote.onFirstListing(remote::focusTable);
        // Path memory: every committed listing tells the session where its
        // panes stand; applySite supplies the key and the restores.
        local.onDirLanded(this::paneLanded);
        remote.onDirLanded(this::paneLanded);

        bind("F5", "copy", () -> transfer(false));
        bind("F6", "move", () -> transfer(true));
        bind("ctrl L", "edit-path", () -> activePane().beginPathEdit());
        // Pane hopping: left is always the local pane, right always the
        // remote one — no need to remember which side holds the keyboard.
        bind("LEFT", "hop-local", () -> hop(local));
        bind("RIGHT", "hop-remote", () -> hop(remote));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, local, remote);
        this.split = split;
        split.setResizeWeight(0.5);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(Tokens.GAP_1 + 4);
        // resizeWeight alone does not place the divider; preferred sizes
        // would park it wherever the path bar ends. Place it once, centered.
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            boolean placed = false;
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (!placed && split.getWidth() > 0) {
                    placed = true;
                    split.setDividerLocation(0.5);
                }
            }
        });
        content.setOpaque(false);
        content.add(split, BorderLayout.CENTER);
        add(banner, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);

        // The stand-ins need the finished split and content panel: a side
        // either swaps within the split or covers the whole window, which
        // the hidden flags decide.
        this.standins = new PaneStandins(local, remote, content, split,
                this::sideHidden, () -> siteName);
        standins.install(local);
        standins.install(remote);

        // The session keeps its loss hook alive across reconnects; it may
        // fire for our own close too — the closing flag filters that.
        session.onConnectionLost(this::onConnectionLost);
    }

    private void buildBanner() {
        banner.setVisible(false);
        banner.setOpaque(true);
        banner.setBackground(tint(new Color(0xE5484D)));
        banner.setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_2, Tokens.GAP_3,
                Tokens.GAP_2, Tokens.GAP_3));
        bannerText.setFont(FontRegistry.uiMedium());
        bannerText.setIcon(Glyphs.icon(Glyphs.WARNING, Tokens.ICON_SMALL,
                () -> new Color(0xE5484D)));
        bannerText.setIconTextGap(Tokens.GAP_2);
        banner.add(bannerText, BorderLayout.CENTER);
        reconnectNow.putClientProperty("FlatLaf.style", "arc: " + Tokens.ARC + ";");
        reconnectNow.addActionListener(e -> {
            reconnectAttempt = 0;
            reconnect();
        });
        var right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        right.add(reconnectNow, BorderLayout.CENTER);
        banner.add(right, BorderLayout.EAST);
    }

    private static Color tint(Color c) {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) return c;
        return new Color(
                (c.getRed() + bg.getRed()) / 2,
                (c.getGreen() + bg.getGreen()) / 2,
                (c.getBlue() + bg.getBlue()) / 2);
    }

    // ---- pane stand-ins (image viewer, editor, player) ----

    /** True when the image viewer is currently standing in for this pane. */
    public boolean showingViewer(FilePane side) {
        return standins.showingViewer(side);
    }

    /** True when the text editor is currently standing in for this pane. */
    public boolean showingEditor(FilePane side) {
        return standins.showingEditor(side);
    }

    /** True when the media player is currently standing in for this pane. */
    public boolean showingPlayer(FilePane side) {
        return standins.showingPlayer(side);
    }

    /** Fires a key on the viewer covering {@code side} — the integration
     *  tests drive ESC/arrows through the real binding. */
    public void fireViewerKeyForTest(FilePane side, String spec) {
        var viewer = standins.viewerOf(side);
        if (viewer != null) viewer.fireKeyForTest(spec);
    }

    /** Fires a key on the editor covering {@code side} — the integration
     *  tests drive Ctrl+S/ESC through the real binding. */
    public void fireEditorKeyForTest(FilePane side, String spec) {
        var editor = standins.editorOf(side);
        if (editor != null) editor.fireKeyForTest(spec);
    }

    /** Fires a key on the player covering {@code side} — the integration
     *  tests drive Space/ESC through the real binding. */
    public void firePlayerKeyForTest(FilePane side, String spec) {
        var player = standins.playerOf(side);
        if (player != null) player.fireKeyForTest(spec);
    }

    /** The commander separator's current position — tests pin it across
     *  the viewer swap. */
    public int dividerLocationForTest() {
        return split.getDividerLocation();
    }

    /** Moves the separator exactly the way a drag does — through the
     *  location-setting call the divider itself makes. */
    public void moveDividerForTest(int location) {
        split.setDividerLocation(location);
    }

    /** Whatever currently stands in {@code side}'s slot of the commander
     *  split: the pane, or the viewer replacing it. */
    public java.awt.Component splitSideForTest(FilePane side) {
        return side == local ? split.getLeftComponent() : split.getRightComponent();
    }

    // ---- reconnection ----

    /** Registered as the connection listener; called from protocol threads. */
    public void onConnectionLost() {
        SwingUtilities.invokeLater(() -> {
            if (closing || reconnecting) return;
            showBanner("Connection lost. Reconnecting…");
            scheduleAutoReconnect();
        });
    }

    private void scheduleAutoReconnect() {
        if (!session.reconnectable()) {
            showBanner("Connection lost.");
            return;
        }
        int attempt = reconnectAttempt++;
        long delay = RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)];
        showBanner("Connection lost. Reconnecting in " + (delay / 1000) + "s…");
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(delay); } catch (InterruptedException e) { return; }
            SwingUtilities.invokeLater(this::reconnect);
        });
    }

    private void reconnect() {
        if (!session.reconnectable() || closing || reconnecting) return;
        reconnecting = true;
        showBanner("Reconnecting…");
        final FileSystem dead = remoteFs;
        Thread.ofVirtual().name("dock-reconnect").start(() -> {
            try {
                FileSystem fresh = session.reconnect();
                remoteFs = fresh;
                // Backends that heal in place return the same instance —
                // closing it would kill the just-healed line.
                if (dead != fresh) {
                    try { dead.close(); } catch (Exception ignored) {}
                }
                SwingUtilities.invokeLater(() -> {
                    hideBanner();
                    reconnecting = false;
                    reconnectAttempt = 0;
                    // The stand-ins hold the dead connection's filesystem —
                    // they go before the pane switches to the fresh one.
                    standins.closeAll(remote);
                    remotePane().setFileSystem(fresh);
                    // Queue rows that named the dead instance now run through
                    // the healed one; failures keep their resume point.
                    TransferEngine.GLOBAL.sessionRebound(dead, fresh);
                    Toast.show(SessionView.this, "Reconnected.", Glyphs.CHECK);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    reconnecting = false;
                    showBanner("Reconnect failed: " + firstLine(e.getMessage()));
                    scheduleAutoReconnect();
                });
            }
        });
    }

    private void showBanner(String text) {
        bannerText.setText(text);
        banner.setVisible(true);
        revalidate();
        repaint();
    }

    private void hideBanner() {
        banner.setVisible(false);
        revalidate();
        repaint();
    }

    private static String firstLine(String s) {
        if (s == null) return "unknown error";
        return s.split("\n")[0];
    }

    // ---- transfers ----

    private FilePane paneOf(Object focusComponent) {
        return SwingUtilities.isDescendingFrom((java.awt.Component) focusComponent, local)
                ? local : remote;
    }

    private void bind(String key, String name, Runnable action) {
        var im = getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        var am = getActionMap();
        im.put(KeyStroke.getKeyStroke(key), name);
        am.put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { action.run(); }
        });
        // The tables' focused maps win over ancestor maps for unbound keys,
        // so register there too.
        var lim = local.table().getInputMap(WHEN_FOCUSED);
        var lam = local.table().getActionMap();
        lim.put(KeyStroke.getKeyStroke(key), name);
        lam.put(name, am.get(name));
        var rim = remote.table().getInputMap(WHEN_FOCUSED);
        var ram = remote.table().getActionMap();
        rim.put(KeyStroke.getKeyStroke(key), name);
        ram.put(name, am.get(name));
    }

    private FilePane activePane() {
        var focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus != null) {
            if (SwingUtilities.isDescendingFrom(focus, local)) return local;
            if (SwingUtilities.isDescendingFrom(focus, remote)) return remote;
        }
        return lastActive;
    }

    /** Menu dispatch: the pane the Edit/Go menus act on. */
    public FilePane activeFilePane() { return activePane(); }

    /** A LEFT/RIGHT hop moves the keyboard without selecting anything.
     *  A folded side is off-screen, so hops onto it do nothing. A side
     *  showing a stand-in takes the hop itself — the table underneath
     *  it is hidden. */
    private void hop(FilePane target) {
        if (sideHidden(target)) return;
        standins.focusOccupant(target);
    }

    /** F5/F6 and the Edit menu's copy/move-to-other-pane. */
    public void transfer(boolean move) {
        FilePane from = activePane();
        FilePane to = from == local ? remote : local;
        List<FileEntry> selected = from.selectedEntries();
        if (selected.isEmpty()) {
            Toast.show(this, "Nothing selected in the " + (from == local ? "local" : "remote")
                    + " pane.", Glyphs.INFO);
            return;
        }
        // A virtual listing can't receive anything; it can only be copied
        // out of — archive entries extract, share rows can't even do that.
        if (to.fs().immutableListing(to.path())) {
            Toast.show(this, "This listing can't receive files — it's read-only.",
                    Glyphs.INFO);
            return;
        }
        if (from.fs().immutableListing(from.path())) {
            if (move) {
                Toast.show(this, "The source is read-only — copy (F5), don't move.",
                        Glyphs.INFO);
                return;
            }
            if (!from.fs().extractable(from.path())) {
                Toast.show(this, "The share list is not a folder — open a share first.",
                        Glyphs.INFO);
                return;
            }
        }
        if (to.path().equals(from.path()) && to.fs() == from.fs()) {
            Toast.show(this, "Source and target are the same directory.", Glyphs.WARNING);
            return;
        }
        TransferEngine.GLOBAL.enqueue(from.fs(), from.path(), selected, to.fs(), to.path(), move);
    }

    /** Called (EDT) after engine state changes; refreshes panes after transfers. */
    public void refreshAfterTransfers() {
        var engine = TransferEngine.GLOBAL;
        boolean touchedRemote = false;
        boolean touchedLocal = false;
        String remoteDir = null;
        String localDir = null;
        for (TransferJob j : engine.snapshot()) {
            if (j.state() != TransferJob.State.DONE && j.state() != TransferJob.State.SKIPPED) continue;
            if (j.src() == remoteFs || j.dst() == remoteFs) {
                touchedRemote = true;
                remoteDir = j.dst() == remoteFs ? parentOf(j.dstPath())
                        : parentOf(j.srcPath());
            } else if (j.src() == LocalFs.INSTANCE || j.dst() == LocalFs.INSTANCE) {
                touchedLocal = true;
                localDir = j.dst() == LocalFs.INSTANCE ? parentOf(j.dstPath())
                        : parentOf(j.srcPath());
            }
        }
        if (touchedRemote && remote.path().equals(remoteDir)) remote.reload();
        if (touchedLocal && local.path().equals(localDir)) local.reload();
    }

    private static String parentOf(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut > 0 ? path.substring(0, cut) : path;
    }

    public FileSystem remoteFs() { return remoteFs; }
    /** The session's preferred name — tab and terminal-tab title. */
    public String title() { return title; }
    public FilePane localPane() { return local; }
    public FilePane remotePane() { return remote; }

    /** Pushes View-menu settings into both panes. */
    public void applyViewSettings() {
        local.applyViewSettings();
        remote.applyViewSettings();
    }

    /** Pushes the View-menu preview switch into both panes. */
    public void applyPreviewEnabled() {
        local.applyPreviewEnabled();
        remote.applyPreviewEnabled();
    }

    // ---- pane visibility ----

    /** Whether {@code side} is folded away — off-screen, but alive with
     *  its directory and any stand-in covering it. */
    private boolean sideHidden(FilePane side) {
        return side == local ? localHidden : remoteHidden;
    }

    /** The side's current occupant: the stand-in covering it, or the pane. */
    private java.awt.Component slot(FilePane side) {
        return standins.occupant(side);
    }

    /**
     * Folds one pane away — or brings it back. The pane only leaves the
     * display: it stays alive with its directory and any stand-in
     * covering it, so transfers (F5/F6, drops) keep working against it
     * and the commander layout restores exactly where it stood. One pane
     * must stay visible; folding the last one is refused.
     */
    private void setPaneHidden(FilePane side, boolean hidden) {
        if (sideHidden(side) == hidden) return;
        if (hidden && sideHidden(side == local ? remote : local)) {
            Toast.show(this, "The other pane is already hidden — show it first.",
                    Glyphs.INFO);
            return;
        }
        if (side == local) localHidden = hidden; else remoteHidden = hidden;
        applyLayout();
        // The keyboard follows the pane that stays on screen.
        lastActive = remoteHidden ? local : remote;
        hop(lastActive);
    }

    /** Seats each side's occupant in the shape the hidden flags pick:
     *  both sides back in the commander split — the divider where it
     *  stood — or the lone visible side filling the window. */
    private void applyLayout() {
        if (content.isAncestorOf(split) && split.getDividerLocation() > 0)
            lastDivider = split.getDividerLocation();
        content.removeAll();
        if (localHidden || remoteHidden) {
            content.add(slot(localHidden ? remote : local), BorderLayout.CENTER);
        } else {
            split.setLeftComponent(slot(local));
            split.setRightComponent(slot(remote));
            content.add(split, BorderLayout.CENTER);
            // Re-seating the split's children arms its reset-to-preferred
            // pass; re-asserting the remembered location lands the divider
            // back where it stood (centered until one exists).
            SwingUtilities.invokeLater(() -> {
                if (split.getWidth() > 0) {
                    if (lastDivider > 0) split.setDividerLocation(lastDivider);
                    else split.setDividerLocation(0.5);
                }
            });
        }
        content.revalidate();
        content.repaint();
    }

    @Override public void setLocalPaneHidden(boolean hidden) { setPaneHidden(local, hidden); }
    @Override public void setRemotePaneHidden(boolean hidden) { setPaneHidden(remote, hidden); }
    @Override public boolean localPaneHidden() { return localHidden; }
    @Override public boolean remotePaneHidden() { return remoteHidden; }

    /**
     * Attaches the saved session this view belongs to: both panes reopen
     * at the directories they last showed here — one that no longer exists
     * falls back to its filesystem's home — and every further navigation
     * records back under the session's name. Call once, right after
     * construction, before the first listings land.
     */
    public void applySite(Site site) {
        siteName = site.name();
        // The queue file writes remote endpoints as saved-site names, so this
        // is where a connection becomes addressable: rows restored waiting
        // on this site get the live channel and start moving.
        TransferEngine.GLOBAL.sessionOpened(siteName, remoteFs);
        // A saved site remembers in sessions.json; an unsaved quick connect
        // falls back to the last_locations shelf, keyed by name either way.
        var shelf = dock.core.config.LastLocations.session(siteName);
        String l = site.lastLocalPath();
        String r = site.lastRemotePath();
        if ((l == null || l.isBlank()) && shelf != null) l = shelf.local();
        if ((r == null || r.isBlank()) && shelf != null) r = shelf.remote();
        if (l != null && !l.isBlank() && local.fs() == LocalFs.INSTANCE
                && !l.equals(local.path()))
            local.navigatePlace(LocalFs.INSTANCE, l);
        if (r != null && !r.isBlank() && remote.fs() == remoteFs && !r.equals(remote.path()))
            remote.navigatePlace(remoteFs, r);
    }

    /** A pane committed a listing: under a saved session, remember where
     *  both sides stand once each has landed somewhere. */
    private void paneLanded(FileSystem fs, String dir) {
        if (siteName == null) return;
        if (fs == LocalFs.INSTANCE) lastLocalDir = dir;
        else if (fs == remoteFs) lastRemoteDir = dir;
        else return;   // an archive interior — the last plain directory stays remembered
        if (lastLocalDir == null || lastRemoteDir == null) return;
        if (lastLocalDir.equals(flushedLocal) && lastRemoteDir.equals(flushedRemote)) return;
        flushedLocal = lastLocalDir;
        flushedRemote = lastRemoteDir;
        String site = siteName, l = lastLocalDir, r = lastRemoteDir;
        long gen = ++memoryGen;
        Thread.ofVirtual().name("dock-remember").start(() -> {
            if (gen != memoryGen) return;   // a newer navigation supersedes this write
            try {
                Sites.recordPaths(site, l, r);
            } catch (Exception ignored) {
                // Best-effort memory; navigation must never fail over it.
            }
            // Saved or not, the shelf keeps the pair so even an unsaved
            // quick connect reopens where it left.
            dock.core.config.LastLocations.putSession(site, l, r);
        });
    }

    /** True when no editor holds unsaved edits, or the user chose to
     *  discard them. The structural paths (layout switch, session close)
     *  retire stand-ins synchronously — there is no room for a
     *  save-then-close round trip there, so the choice is explicit. */
    private boolean confirmLosingEdits(String what) {
        if (!standins.hasUnsavedEdits()) return true;
        return JOptionPane.showConfirmDialog(this,
                "There are unsaved edits. " + what + " Discard them?",
                "Unsaved edits", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /** Closes the session; cancels its transfers after confirmation. */
    public void close() {
        var engine = TransferEngine.GLOBAL;
        if (engine.hasActive(remoteFs)) {
            int ans = JOptionPane.showConfirmDialog(this,
                    "Transfers involving this session are still running.\nCancel them and close?",
                    "Close session", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ans != JOptionPane.YES_OPTION) return;
            engine.cancelAll(remoteFs);
        }
        if (!confirmLosingEdits("Closing the session closes the editor.")) return;
        closing = true;
        standins.closeAll();
        local.releaseMounts();
        remote.releaseMounts();
        Thread.ofVirtual().start(session::close);
    }
}
