package dock.ui;

import dock.kit.ViewSettings;
import dock.kit.Toast;
import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.ThemeManager;
import dock.kit.Tokens;
import dock.kit.WrapLayout;
import dock.kit.tabs.SessionTab;
import dock.kit.tabs.TabContent;
import dock.kit.tabs.TabContentProvider;
import dock.kit.tabs.TabContents;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.JToolBar;
import javax.swing.UIManager;

/** The application main window: menu bar, session tabs, status bar. */
public final class MainWindow extends JFrame {

    /**
     * One open tab: its content, the provider that made it (for close),
     * and — for session tabs — the session it browses (for the terminal
     * overlay).
     */
    private record TabEntry(TabContentProvider provider, dock.core.session.Session session,
                            Component content) { }

    private final JTabbedPane tabs = new JTabbedPane();
    private final List<TabEntry> tabEntries = new ArrayList<>();
    private final JPanel center = new JPanel(new java.awt.BorderLayout());
    private final java.awt.CardLayout centerCards = new java.awt.CardLayout();
    private final JPanel centerPanel = new JPanel(centerCards);
    private final JToolBar toolbar = new JToolBar();
    private final StatusBar statusBar;
    private final TransferPopup transfers;
    private SessionsHome homeView;
    private LocalCommander localCommander;
    private Sidebar sidebar;
    /** "local" card = dual-pane local file browser; "tabs" card = session tabs. */
    private static final String CARD_LOCAL = "local";
    private static final String CARD_TABS = "tabs";
    /** Which center card is showing — the pane toggles' source of truth.
     *  CardLayout doesn't maintain its cards' visibility, so isShowing()
     *  can't answer this; every switch records itself via {@link #showCard}. */
    private String currentCard = CARD_LOCAL;
    /** The View-menu Explorer checkbox — synced with the footer's pane
     *  buttons by {@link #syncPaneToggles()}. */
    private JCheckBoxMenuItem explorerItem;
    /** The View-menu second-pane, sidebar and preview checkboxes — same
     *  sync flow as the Explorer toggle. */
    private JCheckBoxMenuItem secondPaneItem;
    private JCheckBoxMenuItem sidebarItem;
    private JCheckBoxMenuItem previewItem;
    /** The window's one split: sidebar | commander (local or sessions). */
    private javax.swing.JSplitPane mainSplit;
    /** Favorites: directories bookmarked for quick access. The shelf is on
     *  disk (favorites.json) — what the last run left is what this one shows. */
    private final List<String> favorites = new ArrayList<>(dock.core.config.Favorites.load());

    public MainWindow() {
        super("Universal Media Explorer");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setIconImages(dock.kit.AppIcon.windowImages());
        setJMenuBar(menuBar());

        dock.core.transfer.TransferEngine.GLOBAL.setResolver(ConflictDialog.resolver(this));
        dock.core.transfer.TransferEngine.GLOBAL.addListener(this::onTransfersChanged);
        // Before the restore, so a queue file that fails on the way in is
        // heard rather than only printed where nobody is looking.
        dock.core.transfer.TransferEngine.GLOBAL.addProblemListener(this::onQueueProblem);

        // Rows the last run left unfinished come back now; the ones whose
        // session isn't open yet wait for it rather than being dropped.
        int restored = dock.core.transfer.TransferEngine.GLOBAL.restoreQueue(
                dock.core.fs.LocalFs.INSTANCE);
        if (restored > 0) {
            dock.kit.Toast.show(this, "Restored " + restored + " transfer"
                    + (restored > 1 ? "s" : "") + " from the previous session",
                    dock.kit.Glyphs.INFO);
        }

        // Toolbar: toolkit action buttons with icons and GODMODE gating.
        // The row wraps instead of clipping — a narrow window must not hide
        // the remote group behind the window edge.
        toolbar.setFloatable(false);
        toolbar.setLayout(new WrapLayout());
        rebuildToolbar(null);
        add(toolbar, BorderLayout.NORTH);

        tabs.putClientProperty("JTabbedPane.tabsClosable", true);
        tabs.putClientProperty("JTabbedPane.tabCloseCallback",
                (java.util.function.BiConsumer<JTabbedPane, Integer>) this::closeSession);
        tabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close Tab");
        tabs.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isMiddleMouseButton(e)) closeTabAt(e);
                else if (e.isPopupTrigger()) showTabMenu(e);
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) showTabMenu(e);
            }
        });
        tabs.setVisible(false);

        // Center area: CardLayout switches between local commander and session tabs.
        localCommander = new LocalCommander();
        centerPanel.add(localCommander, CARD_LOCAL);
        centerPanel.add(tabs, CARD_TABS);
        showCard(CARD_LOCAL);

        // Every toolkit launch (toolbar or pane context menu) surfaces its
        // shell in a terminal tab the user can watch.
        dock.terminal.ToolkitActions.setTabOpener(this::openToolkitTerminalTab);

        // Sidebar: favorites, drives, media servers, respaldo, sessions.
        sidebar = new Sidebar(new Sidebar.Host() {
            @Override public void navigate(dock.commander.FilePane pane, String path) {
                // Switch to local commander view if not already showing
                showCard(CARD_LOCAL);
                tabs.setVisible(false);
                dock.commander.FilePane target = pane != null ? pane
                        : localCommander.activePane();
                target.navigate(path);
                target.requestFocusInWindow();
                syncPaneToggles();
            }
            @Override public void connectSite(dock.core.config.Site site) {
                MainWindow.this.connectSite(site, (err, fs) -> {
                    if (err != null) {
                        Toast.show(MainWindow.this, "Could not connect: " + err.getMessage(),
                                Glyphs.WARNING);
                    }
                });
            }
            @Override public java.util.List<String> favorites() { return favorites; }
            @Override public void addFavorite(String path) {
                if (dock.core.config.Favorites.addAll(favorites, List.of(path)) > 0) {
                    persistFavorites();
                    Toast.show(MainWindow.this, "Added to favorites: " + path, Glyphs.INFO);
                }
            }
            @Override public void removeFavorite(String path) {
                if (dock.core.config.Favorites.remove(favorites, path)) {
                    persistFavorites();
                }
                Toast.show(MainWindow.this, "Removed from favorites", Glyphs.INFO);
            }
            @Override public String currentPath() { return activeLocalPath(); }
        });

        mainSplit = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.HORIZONTAL_SPLIT, sidebar, centerPanel);
        mainSplit.setDividerLocation(210);
        mainSplit.setResizeWeight(0.0);
        mainSplit.setContinuousLayout(true);
        mainSplit.setBorder(BorderFactory.createEmptyBorder());
        add(mainSplit, BorderLayout.CENTER);
        // The saved sidebar choice takes effect before the window shows.
        applySidebarHidden(dock.core.config.AppSettings.flag("sidebarHidden", false));

        var closeKey = KeyStroke.getKeyStroke("ctrl W");
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(closeKey, "dock.closeTab");
        getRootPane().getActionMap().put("dock.closeTab", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (tabs.getSelectedIndex() >= 0) closeSession(tabs, tabs.getSelectedIndex());
            }
        });
        // Ctrl+K only: Ctrl+F is the panes' Filter (the Tools menu) — two
        // WHEN_IN_FOCUSED_WINDOW owners on one chord would race.
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ctrl K"), "dock.searchSessions");
        getRootPane().getActionMap().put("dock.searchSessions", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (homeView != null && homeView.isShowing()) homeView.focusSearch();
            }
        });
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ctrl SLASH"), "dock.showShortcuts");
        getRootPane().getActionMap().put("dock.showShortcuts", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                showKeyboardShortcuts();
            }
        });

        statusBar = new StatusBar(dock.core.transfer.TransferEngine.GLOBAL, this::toggleTransfers);
        statusBar.setPaneActions(this::toggleLocalPane, this::toggleRemotePane);
        add(statusBar, BorderLayout.SOUTH);
        transfers = new TransferPopup(this, dock.core.transfer.TransferEngine.GLOBAL, statusBar);
        syncPaneToggles();
        setMinimumSize(new Dimension(960, 600));
        // The window comes back the size and place the last run left it;
        // a saved box that no screen shows anymore falls back to centered.
        var box = dock.core.config.LastLocations.window();
        if (box != null && box.w() >= 960 && box.h() >= 600 && landsOnAScreen(box)) {
            setBounds(box.x(), box.y(), box.w(), box.h());
            if (box.maximized()) setExtendedState(MAXIMIZED_BOTH);
        } else {
            setSize(1280, 800);
            // Centered on a first run: setLocationByPlatform let the OS
            // place the window, and Windows cascades new windows, so the
            // app opened at a slightly different spot each run. After
            // setSize, so the centering accounts for the real size.
            setLocationRelativeTo(null);
        }
        // The X button saves the box through windowClosing; the Quit menu
        // item does it explicitly — dispose() alone fires neither.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                rememberWindow();
            }
        });
    }

    /** Whether the saved box overlaps at least one attached screen. */
    private static boolean landsOnAScreen(dock.core.config.LastLocations.WindowBox box) {
        for (var gd : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getScreenDevices()) {
            if (gd.getDefaultConfiguration().getBounds().intersection(
                    new java.awt.Rectangle(box.x(), box.y(), box.w(), box.h())).isEmpty()) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** The geometry the next run restores. Package-private: the tests pin
     *  the round trip without driving a real close. */
    void rememberWindow() {
        boolean max = (getExtendedState() & MAXIMIZED_BOTH) != 0;
        var b = getBounds();
        dock.core.config.LastLocations.putWindow(
                new dock.core.config.LastLocations.WindowBox(b.x, b.y, b.width, b.height, max));
    }

    /** The saved-sessions landing screen, rebuilt fresh each time it returns. */
    private SessionsHome home() {
        homeView = new SessionsHome(new SessionsHome.Host() {
            @Override public void connect(dock.core.config.Site site,
                    java.util.function.BiConsumer<Exception, dock.core.fs.FileSystem> outcome) {
                MainWindow.this.connectSite(site, outcome);
            }
            @Override public void newSession() { MainWindow.this.newSession(); }
            @Override public void localShell() { MainWindow.this.openLocalShell(); }
            @Override public void quickConnect(String typed) { MainWindow.this.quickConnect(typed); }
            @Override public void editSite(dock.core.config.Site site) { MainWindow.this.editSite(site); }
            @Override public boolean hasSourceEntries() {
                for (dock.core.config.SiteSource src : dock.core.config.SiteSource.all()) {
                    if (!src.sites().isEmpty()) return true;
                }
                return false;
            }
            @Override public boolean sourcesShown() {
                for (dock.core.config.SiteSource src : dock.core.config.SiteSource.all()) {
                    if (src.shown()) return true;
                }
                return false;
            }
            @Override public void setSourcesShown(boolean shown) {
                try {
                    for (dock.core.config.SiteSource src : dock.core.config.SiteSource.all()) {
                        dock.core.config.AppSettings.setFlag(src.settingKey(), shown);
                    }
                } catch (java.io.IOException ex) {
                    Toast.show(MainWindow.this, "Could not save the setting: " + ex.getMessage(),
                            Glyphs.WARNING);
                }
            }
            @Override public java.util.List<SessionsHome.Section> sourceSections() {
                java.util.List<SessionsHome.Section> out = new java.util.ArrayList<>();
                for (dock.core.config.SiteSource src : dock.core.config.SiteSource.all()) {
                    if (src.shown()) {
                        out.add(new SessionsHome.Section(src.title(), src.tag(), src.sites()));
                    }
                }
                return out;
            }
        });
        return homeView;
    }

    private void onTransfersChanged() {
        // Called from worker threads; coalesce onto the EDT.
        javax.swing.SwingUtilities.invokeLater(this::handleTransfersChanged);
    }

    /** Trouble the queue had persisting itself, not transferring: a file
     *  that would not write or a stored row that would not come back. */
    private void onQueueProblem(String problem) {
        Toast.show(this, problem, Glyphs.WARNING);
    }

    private void handleTransfersChanged() {
        // Bursts surface in the footer bar; the transfers popup opens only
        // on demand (footer click / Ctrl+Alt+Q) — IntelliJ-style, never on
        // its own.
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof SessionTab view) view.refreshAfterTransfers();
        }
    }

    /** Toggles the transfers popup anchored above the footer bar. */
    public void toggleTransfers() {
        transfers.toggle();
    }

    /** The footer's edge buttons: fold one pane of the selected session —
     *  or of the local commander, when the commander card is showing —
     *  away, or bring it back. */
    private void toggleLocalPane() {
        if (onSessionCard() && tabs.getSelectedComponent() instanceof SessionTab view) {
            view.setLocalPaneHidden(!view.localPaneHidden());
        } else if (CARD_LOCAL.equals(currentCard) && localCommander != null) {
            localCommander.setLeftPaneHidden(!localCommander.leftPaneHidden());
        }
        syncPaneToggles();
    }

    private void toggleRemotePane() {
        if (onSessionCard() && tabs.getSelectedComponent() instanceof SessionTab view) {
            view.setRemotePaneHidden(!view.remotePaneHidden());
        } else if (CARD_LOCAL.equals(currentCard) && localCommander != null) {
            localCommander.setRightPaneHidden(!localCommander.rightPaneHidden());
        }
        syncPaneToggles();
    }

    /** True while the session-tab card is the one under the window — never
     *  inferred from tab selection alone, which survives the return to the
     *  commander view. */
    private boolean onSessionCard() {
        return CARD_TABS.equals(currentCard);
    }

    /** Rebuilds the toolbar buttons based on the current path for GODMODE gating. */
    private void rebuildToolbar(String path) {
        toolbar.removeAll();
        int group = 0;
        for (var entry : dock.terminal.ToolkitActions.layoutFor(path)) {
            if (entry.isSection()) {
                // One run per group: the wrap never lands between a caption
                // and the buttons it names.
                group++;
                JLabel caption = sectionCaption(entry.caption());
                caption.putClientProperty(WrapLayout.GROUP, group);
                toolbar.add(caption);
                continue;
            }
            dock.terminal.ToolkitActions.Action action = entry.action();
            JButton btn = new JButton(action.name(), Glyphs.icon(action.glyph(), 18,
                    () -> UIManager.getColor("Label.disabledForeground")));
            btn.setToolTipText(action.description());
            btn.addActionListener(e -> launchToolkitActionFromToolbar(action));
            btn.setFocusable(false);
            btn.putClientProperty(WrapLayout.GROUP, group);
            toolbar.add(btn);
        }
        toolbar.revalidate();
        toolbar.repaint();
    }

    /**
     * A group caption in the toolbar row: the same small semi-bold ink the
     * sidebar uses for its sections, and the button's own height so the row
     * never jumps when the path changes which groups it shows.
     */
    private JLabel sectionCaption(String text) {
        JLabel caption = new JLabel(text);
        caption.setFont(FontRegistry.uiSemiBold(11));
        caption.setForeground(UIManager.getColor("Label.disabledForeground"));
        caption.setBorder(BorderFactory.createEmptyBorder(0, Tokens.GAP_2, 0, Tokens.GAP_1));
        caption.setFocusable(false);
        return caption;
    }

    /** The transfers popup (screenshot/selftest hook). */
    public TransferPopup transfersPopup() {
        return transfers;
    }

    private void newSession() {
        new ConnectDialog(this, this::connectedFromDialog).setVisible(true);
    }

    /** Quick connect: opens the dialog prefilled when the text parses as [user@]host[:port]. */
    private void quickConnect(String typed) {
        ConnectDialog d = new ConnectDialog(this, this::connectedFromDialog);
        String[] q = ConnectDialog.parseQuickSpec(typed);
        if (q != null) d.prefillQuick(q[0], q[1], q[2]);
        d.setVisible(true);
    }

    private void editSite(dock.core.config.Site site) {
        // The row menu modifies the saved entry; connecting stays on the
        // row's double-click.
        new ConnectDialog(this, site, saved -> {
            if (homeView != null) homeView.refresh();
        }).setVisible(true);
    }

    /** The missing-secret prompt: the prefilled form collects the secret
     *  and connects — a double-click that paused, not an edit. */
    private void editSiteToConnect(dock.core.config.Site site) {
        new ConnectDialog(this, site, this::connectedFromDialog).setVisible(true);
    }

    /** Adapter for the connect dialog: the site carries both the tab's
     *  preferred title and its remembered directories. */
    private void connectedFromDialog(dock.core.session.Session session,
                                     dock.core.config.Site site) {
        openSession(session, site.name(), site);
    }

    /**
     * Connects with a saved site's settings and stored secret. The outcome
     * runs on the EDT exactly once: non-null fs = connected (the tab is open),
     * non-null error = failed, both null = aborted (missing password opens
     * the edit dialog instead).
     */
    public void connectSite(dock.core.config.Site site,
                            java.util.function.BiConsumer<Exception, dock.core.fs.FileSystem> outcome) {
        Thread.ofVirtual().name("dock-connect").start(() -> {
            try {
                var backend = dock.core.spi.Backends.of(site.protocol());
                if (backend == null) {
                    throw new java.io.IOException(site.protocol().label()
                            + " support is not present in this build.");
                }
                dock.core.session.Session session;
                try {
                    session = backend.dial(site, MainWindow::loadSecret);
                } catch (dock.core.spi.MissingSecretException e) {
                    // Aborted (missing secret): the edit dialog is up.
                    SwingUtilities.invokeLater(() -> {
                        editSiteToConnect(site);
                        outcome.accept(null, null);
                    });
                    return;
                }
                try {
                    dock.core.config.Sites.touch(site.name());
                } catch (Exception ignored) {
                    // Recency bookkeeping must never kill the connection.
                }
                dock.core.fs.FileSystem fs = session.fs();
                SwingUtilities.invokeLater(() -> {
                    outcome.accept(null, fs);
                    openSession(session, site.name(), site);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> outcome.accept(ex, null));
            }
        });
    }

    /** The credential-store leg of the dial: secrets enter as char[]
     *  arrays so the backends can wipe them; the String is gone by then. */
    private static char[] loadSecret(String credentialTarget) {
        String stored = dock.core.secrets.CredentialManager.load(credentialTarget);
        return stored == null ? null : stored.toCharArray();
    }

    /** Opens a connected session as a new closable tab. */
    /**
     * Opens a connected session titled by its preferred name (the saved
     * session's name); blank names fall back to the endpoint, which then
     * also hides the tooltip carrying the endpoint.
     */
    public void openSession(dock.core.session.Session session, String preferredName) {
        openSession(session, preferredName, null);
    }

    /**
     * Opens a connected session as a new closable tab; a saved site also
     * hands the commander view its remembered directories, so both panes
     * reopen where this session was last left.
     */
    public void openSession(dock.core.session.Session session, String preferredName,
                            dock.core.config.Site site) {
        TabContentProvider provider = TabContents.defaultFor(session);
        if (provider == null) {
            Toast.show(this, "No tab content supports this session.", Glyphs.WARNING);
            return;
        }
        TabContent content;
        try {
            content = provider.create(session, preferredName);
        } catch (java.io.IOException e) {
            Toast.show(this, "Could not open the session: " + e.getMessage(), Glyphs.WARNING);
            return;
        }
        // Right after construction, before the first listings land: the
        // restores supersede the default opening directories cleanly.
        if (site != null && content.component() instanceof dock.commander.SessionView view)
            view.applySite(site);
        tabs.addTab(content.title(), content.component());
        // After the add: with no tab yet the card flip was a no-op and the
        // first session never surfaced.
        ensureTabsVisible();
        tabEntries.add(new TabEntry(provider, session, content.component()));
        int index = tabs.getTabCount() - 1;
        if (content.tooltip() != null && !content.title().equals(content.tooltip())) {
            tabs.setToolTipTextAt(index, content.tooltip());
        }
        tabs.setSelectedIndex(index);
        tabs.requestFocusInWindow();
    }

    /** Opens an embedded terminal for the selected session tab. */
    private void openTerminal() {
        TabEntry entry = selectedEntry();
        if (entry == null || !(entry.content() instanceof SessionTab)) {
            Toast.show(this, "Open a session first.", Glyphs.INFO);
            return;
        }
        openTerminalFor(entry);
    }

    /** Spawns this machine's shell as a terminal tab — no session needed. */
    private void openLocalShell() {
        for (TabContentProvider provider : TabContents.all()) {
            try {
                Component shell = provider.localShell();
                if (shell != null) {
                    addContentTab(provider, "Local shell", shell);
                    return;
                }
            } catch (java.io.IOException e) {
                Toast.show(this, "Could not start the local shell: " + e.getMessage(),
                        Glyphs.WARNING);
                return;
            }
        }
    }

    /**
     * Launches a toolkit action from the toolbar button. Targets the active
     * pane's selection (or its directory when nothing is selected) and opens
     * a terminal tab showing the script's live output — via the tab opener
     * ToolkitActions.launch invokes.
     */
    private void launchToolkitActionFromToolbar(dock.terminal.ToolkitActions.Action action) {
        dock.commander.FilePane pane = activeToolkitPane();
        if (pane == null) {
            Toast.show(this, "No active pane — open a folder first.", Glyphs.WARNING);
            return;
        }
        try {
            dock.terminal.ToolkitActions.launch(action, pane.selectedPaths(), pane.path());
        } catch (java.io.IOException e) {
            Toast.show(this, "Failed to launch " + action.name() + ": " + e.getMessage(),
                    Glyphs.WARNING);
        }
    }

    /**
     * The pane a toolbar action should run against: the session tab's local
     * pane while the session card shows, else the local commander's active
     * pane. The card — not the tab selection — decides, so a stale tab
     * selection can't hijack the commander's files.
     */
    private dock.commander.FilePane activeToolkitPane() {
        if (onSessionCard()
                && tabs.getSelectedComponent() instanceof dock.commander.SessionView view) {
            return view.localPane();
        }
        if (CARD_LOCAL.equals(currentCard) && localCommander != null) {
            return localCommander.activePane();
        }
        return null;
    }

    /** Hook target for ToolkitActions.launch: shows the shell in a terminal
     *  tab and focuses it. */
    private void openToolkitTerminalTab(String title, dock.terminal.LocalShell shell) {
        dock.terminal.TerminalView termView = dock.terminal.TerminalView.forLocalShell(shell);
        tabs.addTab(title, termView);
        ensureTabsVisible();
        tabEntries.add(new TabEntry(null, null, termView));
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        termView.requestFocusInWindow();
    }

    private void showCard(String card) {
        currentCard = card;
        centerCards.show(centerPanel, card);
    }

    /** Terminal overlay for a session tab (menu, Ctrl+Shift+T). */
    private void openTerminalFor(TabEntry entry) {
        TabContentProvider overlay = TabContents.overlayFor(entry.session());
        if (overlay == null) {
            Toast.show(this, "The embedded terminal is available for SFTP sessions.", Glyphs.INFO);
            return;
        }
        String title = entry.content() instanceof SessionTab tab
                ? tab.title() + " — terminal" : "terminal";
        try {
            TabContent content = overlay.create(entry.session(), title);
            addContentTab(overlay, content.title(), content.component());
        } catch (java.io.IOException e) {
            Toast.show(this, "Could not open shell: " + e.getMessage(), Glyphs.WARNING);
        }
    }

    /** Adds a tab of provider-made content and focuses it. */
    private void addContentTab(TabContentProvider provider, String title, Component content) {
        tabs.addTab(title, content);
        ensureTabsVisible();
        tabEntries.add(new TabEntry(provider, null, content));
        tabs.setSelectedIndex(tabs.getTabCount() - 1);
        if (content instanceof java.awt.Container c) c.requestFocusInWindow();
    }

    private void ensureTabsVisible() {
        if (tabs.getTabCount() == 0) return;
        showCard(CARD_TABS);
        centerPanel.revalidate();
        centerPanel.repaint();
    }

    private TabEntry selectedEntry() {
        Component selected = tabs.getSelectedComponent();
        for (TabEntry e : tabEntries) {
            if (e.content() == selected) return e;
        }
        return null;
    }

    /** Screenshot-harness hook: terminal tab for the first session. */
    public void openTerminalForScreenshots() {
        for (TabEntry entry : tabEntries) {
            if (entry.session() != null && entry.content() instanceof SessionTab) {
                openTerminalFor(entry);
                return;
            }
        }
    }

    private void closeSession(JTabbedPane pane, Integer index) {
        java.awt.Component view = pane.getComponentAt(index);
        pane.remove(index);
        TabEntry owner = null;
        for (TabEntry e : tabEntries) {
            if (e.content() == view) {
                owner = e;
                break;
            }
        }
        if (owner != null) {
            tabEntries.remove(owner);
            owner.provider().close(view);
        }
        if (pane.getTabCount() == 0) {
            showCard(CARD_LOCAL);
            tabs.setVisible(false);
            centerPanel.revalidate();
            centerPanel.repaint();
            syncPaneToggles();
        }
    }

    /** Middle-click close, resolved from the event position. */
    private void closeTabAt(java.awt.event.MouseEvent e) {
        int index = tabs.indexAtLocation(e.getX(), e.getY());
        if (index >= 0) closeSession(tabs, index);
    }

    /** IntelliJ-style tab context menu: Close / Close Others / Close All. */
    private void showTabMenu(java.awt.event.MouseEvent e) {
        int index = tabs.indexAtLocation(e.getX(), e.getY());
        if (index < 0) return;
        tabs.setSelectedIndex(index);

        JPopupMenu menu = new JPopupMenu();
        JMenuItem close = new JMenuItem("Close Tab");
        close.setAccelerator(KeyStroke.getKeyStroke("ctrl W"));
        close.addActionListener(ev -> closeSession(tabs, index));
        JMenuItem others = new JMenuItem("Close Other Tabs");
        others.setEnabled(tabs.getTabCount() > 1);
        others.addActionListener(ev -> {
            for (int i = tabs.getTabCount() - 1; i >= 0; i--) {
                if (i != index) closeSession(tabs, i);
            }
        });
        JMenuItem all = new JMenuItem("Close All Tabs");
        all.addActionListener(ev -> {
            for (int i = tabs.getTabCount() - 1; i >= 0; i--) closeSession(tabs, i);
        });
        menu.add(close);
        menu.add(others);
        menu.add(all);
        menu.show(tabs, e.getX(), e.getY());
    }

    public int sessionCount() {
        return tabs.getTabCount();
    }

    private JMenuBar menuBar() {
        JMenuBar bar = new JMenuBar();
        bar.add(fileMenu());
        bar.add(editMenu());
        bar.add(viewMenu());
        bar.add(goMenu());
        bar.add(toolsMenu());
        bar.add(favoritesMenu());
        bar.add(helpMenu());
        return bar;
    }

    /** SimpleCommander's File menu: in-place creation, then this app's
     *  session management, then the shells and the exit. */
    private JMenu fileMenu() {
        JMenu menu = new JMenu("File");
        menu.setMnemonic(KeyEvent.VK_F);
        menu.add(item("New Session…", Glyphs.PLUS, "ctrl N", this::newSession));
        menu.add(savedSessionsMenu());
        menu.addSeparator();
        java.util.Map<JCheckBoxMenuItem, dock.core.config.SiteSource> shownItems =
                new java.util.LinkedHashMap<>();
        for (dock.core.config.SiteSource src : dock.core.config.SiteSource.all()) {
            menu.add(item("Import " + src.menuName() + "…", Glyphs.SERVER, null,
                    () -> importFrom(src)));
            JCheckBoxMenuItem shown = sourceShownItem(src);
            shownItems.put(shown, src);
            menu.add(shown);
        }
        // The launcher's Configs toggle flips the same flags: re-read on open.
        menu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) {
                shownItems.forEach((mi, src) -> mi.setState(src.shown()));
            }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) {}
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        menu.addSeparator();
        menu.add(item("New Folder", Glyphs.FOLDER, "F7",
                () -> forActivePane(dock.commander.FilePane::newFolder)));
        menu.add(item("New File", Glyphs.EDIT, null,
                () -> forActivePane(dock.commander.FilePane::newFile)));
        menu.addSeparator();
        menu.add(item("Open Terminal…", Glyphs.TERMINAL, "ctrl shift T", this::openTerminal));
        menu.add(item("Local Shell", Glyphs.TERMINAL, "ctrl shift L", this::openLocalShell));
        menu.addSeparator();
        menu.add(item("Quit", Glyphs.CLOSE, "ctrl Q", () -> {
            rememberWindow();
            dispose();
        }));
        return menu;
    }

    /** SimpleCommander's Edit menu, on UME's own clipboard and transfer
     *  engine: Copy/Cut/Paste with the dock payload, paths as text, and
     *  cross-pane copy/move. */
    private JMenu editMenu() {
        JMenu menu = new JMenu("Edit");
        menu.setMnemonic(KeyEvent.VK_E);
        menu.add(item("Copy", Glyphs.EXCHANGE, "ctrl C",
                () -> forActivePane(p -> p.copySelectionToClipboard(false))));
        menu.add(item("Cut", Glyphs.EXCHANGE, "ctrl X",
                () -> forActivePane(p -> p.copySelectionToClipboard(true))));
        menu.add(item("Paste", Glyphs.EXCHANGE, "ctrl V",
                () -> forActivePane(dock.commander.FilePane::pasteFromClipboard)));
        menu.add(item("Copy Paths", Glyphs.EXCHANGE, "ctrl shift C",
                () -> forActivePane(dock.commander.FilePane::copyPathsToClipboard)));
        menu.add(item("Copy to Other Pane", Glyphs.EXCHANGE, "ctrl alt C",
                () -> transferToOther(false)));
        menu.add(item("Move to Other Pane", Glyphs.EXCHANGE, "ctrl shift M",
                () -> transferToOther(true)));
        menu.add(item("Select All", Glyphs.EXCHANGE, "ctrl A",
                () -> forActivePane(dock.commander.FilePane::selectAllRows)));
        menu.addSeparator();
        menu.add(item("Batch Rename…", Glyphs.EDIT, null,
                () -> launchToolkitActionFromToolbar(dock.terminal.ToolkitActions.RENAME)));
        return menu;
    }

    private JMenu viewMenu() {
        JMenu menu = new JMenu("View");
        menu.setMnemonic(KeyEvent.VK_V);
        menu.add(item("Transfers", Glyphs.EXCHANGE, "ctrl alt Q", this::toggleTransfers));
        menu.addSeparator();
        explorerItem = explorerModeItem();
        menu.add(explorerItem);
        secondPaneItem = checkItem("Show Second Pane", Glyphs.MONITOR, null);
        secondPaneItem.addActionListener(e -> toggleRemotePane());
        menu.add(secondPaneItem);
        previewItem = checkItem("Preview Panel", Glyphs.EYE, null);
        previewItem.setState(dock.commander.FilePane.previewEnabled());
        previewItem.addActionListener(e -> {
            try {
                dock.commander.FilePane.setPreviewEnabled(previewItem.getState());
            } catch (java.io.IOException ex) {
                Toast.show(this, "Could not save the setting: " + ex.getMessage(),
                        Glyphs.WARNING);
            }
            applyPreviewEnabled();
        });
        menu.add(previewItem);
        menu.addSeparator();
        sidebarItem = checkItem("Show Sidebar", Glyphs.FOLDER, null);
        sidebarItem.setState(!dock.core.config.AppSettings.flag("sidebarHidden", false));
        sidebarItem.addActionListener(e -> {
            boolean hidden = !sidebarItem.getState();
            try {
                dock.core.config.AppSettings.setFlag("sidebarHidden", hidden);
            } catch (java.io.IOException ex) {
                Toast.show(this, "Could not save the setting: " + ex.getMessage(),
                        Glyphs.WARNING);
            }
            applySidebarHidden(hidden);
        });
        menu.add(sidebarItem);
        menu.addSeparator();
        menu.add(hiddenFilesItem());
        menu.add(dotFilesItem());
        menu.addSeparator();
        menu.add(themeMenu());
        JCheckBoxMenuItem follow = new JCheckBoxMenuItem("Follow System Theme",
                ThemeManager.isFollowSystem());
        follow.addActionListener(e2 -> ThemeManager.setFollowSystem(follow.getState()));
        menu.add(follow);
        return menu;
    }

    private JMenu goMenu() {
        JMenu menu = new JMenu("Go");
        menu.setMnemonic(KeyEvent.VK_G);
        menu.add(item("Back", Glyphs.ARROW_LEFT, "alt LEFT",
                () -> forActivePane(dock.commander.FilePane::goBack)));
        menu.add(item("Forward", Glyphs.ARROW_RIGHT, "alt RIGHT",
                () -> forActivePane(dock.commander.FilePane::goForward)));
        menu.add(item("Up", Glyphs.ARROW_UP, "BACK_SPACE",
                () -> forActivePane(dock.commander.FilePane::goUp)));
        menu.addSeparator();
        menu.add(item("Recycle Bin", Glyphs.TRASH, null, this::goToRecycleBin));
        return menu;
    }

    private JMenu toolsMenu() {
        JMenu menu = new JMenu("Tools");
        menu.setMnemonic(KeyEvent.VK_T);
        menu.add(item("Filter", Glyphs.EYE, "ctrl F",
                () -> forActivePane(dock.commander.FilePane::openFilter)));
        menu.addSeparator();
        menu.add(item("Open in Explorer", Glyphs.FOLDER, "ctrl shift E", this::openInExplorer));
        menu.addSeparator();
        JMenuItem scriptSettings = item("Script Settings", Glyphs.GEAR, "ctrl COMMA",
                () -> launchToolkitActionFromToolbar(dock.terminal.ToolkitActions.CFG));
        scriptSettings.setToolTipText("Open the toolkit's script settings dialog "
                + "(WinSCP_Script_Settings.ps1).");
        menu.add(scriptSettings);
        return menu;
    }

    private JMenu favoritesMenu() {
        JMenu menu = new JMenu("Favorites");
        menu.setMnemonic(KeyEvent.VK_F);
        // Ctrl+Shift+D: the pane's table owns Ctrl+D for new-folder under
        // Swing's focus-chain resolution — a menu accelerator on the same
        // chord would never fire while a pane is focused.
        menu.add(item("Add Current Directory", Glyphs.HOME, "ctrl shift D",
                this::addCurrentToFavorites));
        JMenuItem importFavs = item("Import from SimpleCommander", Glyphs.DOWNLOAD, null,
                this::importFavoritesFromSimpleCommander);
        importFavs.setToolTipText("Bring in the folders already bookmarked in SimpleCommander "
                + "and the Media Commander toolkit.");
        menu.add(importFavs);
        menu.addSeparator();
        menu.add(favoritesListMenu());
        return menu;
    }

    private JMenu helpMenu() {
        JMenu menu = new JMenu("Help");
        menu.setMnemonic(KeyEvent.VK_H);
        menu.add(item("Check for Updates…", Glyphs.SYNC, null, this::checkForUpdates));
        menu.addSeparator();
        menu.add(item("Keyboard Shortcuts", Glyphs.TERMINAL, "ctrl /", this::showKeyboardShortcuts));
        menu.addSeparator();
        menu.add(item("About", Glyphs.INFO, null, this::showAbout));
        return menu;
    }

    /** The pane the menus act on — the focused pane of the selected session,
     *  or the local commander's active pane; null when neither shows. */
    private dock.commander.FilePane commandPane() {
        if (onSessionCard()
                && tabs.getSelectedComponent() instanceof dock.commander.SessionView view) {
            return view.activeFilePane();
        }
        if (CARD_LOCAL.equals(currentCard) && localCommander != null) {
            return localCommander.activePane();
        }
        return null;
    }

    /** Runs a pane action, explaining itself when no pane is showing. */
    private void forActivePane(java.util.function.Consumer<dock.commander.FilePane> action) {
        dock.commander.FilePane pane = commandPane();
        if (pane == null) {
            Toast.show(this, "No active pane — open a folder first.", Glyphs.WARNING);
            return;
        }
        action.accept(pane);
    }

    /** Edit menu: copy or move the active pane's selection to its sibling —
     *  the session's transfer, or the local commander's own. */
    private void transferToOther(boolean move) {
        if (onSessionCard()
                && tabs.getSelectedComponent() instanceof dock.commander.SessionView view) {
            view.transfer(move);
        } else if (CARD_LOCAL.equals(currentCard) && localCommander != null) {
            localCommander.transferToOther(move);
        } else {
            Toast.show(this, "No active pane — open a folder first.", Glyphs.WARNING);
        }
    }

    /** Go menu: the local pane walks into the shell's recycle bin. */
    private void goToRecycleBin() {
        dock.commander.FilePane pane = commandPane();
        if (pane == null) {
            Toast.show(this, "No active pane — open a folder first.", Glyphs.WARNING);
            return;
        }
        if (pane.fs().remote()) {
            Toast.show(this, "The recycle bin is a local place.", Glyphs.INFO);
            return;
        }
        pane.navigate("C:\\$Recycle.Bin");
    }

    /** Tools menu: the active local pane's directory in the Windows shell. */
    private void openInExplorer() {
        dock.commander.FilePane pane = commandPane();
        if (pane == null) {
            Toast.show(this, "No active pane — open a folder first.", Glyphs.WARNING);
            return;
        }
        if (pane.fs().remote()) {
            Toast.show(this, "Open in Explorer works on local folders.", Glyphs.INFO);
            return;
        }
        try {
            java.awt.Desktop.getDesktop().open(java.nio.file.Path.of(pane.path()).toFile());
        } catch (Exception ex) {
            Toast.show(this, "Could not open Explorer: " + ex.getMessage(), Glyphs.WARNING);
        }
    }

    /** Pushes the View-menu preview switch into every open pane. */
    private void applyPreviewEnabled() {
        if (localCommander != null) {
            localCommander.leftPane().applyPreviewEnabled();
            localCommander.rightPane().applyPreviewEnabled();
        }
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof dock.commander.SessionView view) {
                view.applyPreviewEnabled();
            }
        }
    }

    /** The View-menu sidebar switch: the left column leaves the split
     *  entirely — no sliver to grab, and the commander fills the window. */
    private void applySidebarHidden(boolean hidden) {
        if (mainSplit == null || sidebar == null) return;
        if (hidden) {
            if (mainSplit.getLeftComponent() != null) mainSplit.setLeftComponent(null);
        } else if (mainSplit.getLeftComponent() == null) {
            mainSplit.setLeftComponent(sidebar);
            mainSplit.setDividerLocation(210);
        }
        mainSplit.revalidate();
        mainSplit.repaint();
    }

    private JMenuItem item(String text, String glyph, String accelerator, Runnable action) {
        JMenuItem mi = new JMenuItem(text,
                Glyphs.icon(glyph, 15, () -> UIManager.getColor("Label.disabledForeground")));
        if (accelerator != null) {
            mi.setAccelerator(KeyStroke.getKeyStroke(accelerator));
        }
        mi.addActionListener(e -> action.run());
        return mi;
    }

    /** Theme submenu: Light / Dark / Black radio items. */
    private JMenu themeMenu() {
        JMenu menu = new JMenu("Theme");
        menu.setMnemonic(KeyEvent.VK_T);
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        for (ThemeManager.Mode m : ThemeManager.Mode.values()) {
            JCheckBoxMenuItem rb = new JCheckBoxMenuItem(
                    switch (m) {
                        case LIGHT -> "Light";
                        case DARK  -> "Dark";
                        case BLACK -> "Black";
                    },
                    m == ThemeManager.mode());
            rb.addActionListener(e -> ThemeManager.setMode(m));
            group.add(rb);
            menu.add(rb);
        }
        ThemeManager.addListener(() -> {
            for (int i = 0; i < menu.getItemCount(); i++) {
                JCheckBoxMenuItem rb = (JCheckBoxMenuItem) menu.getItem(i);
                rb.setSelected(ThemeManager.Mode.values()[i] == ThemeManager.mode());
            }
        });
        return menu;
    }

    /** Adds the active pane's directory to favorites. */
    private void addCurrentToFavorites() {
        String currentPath = activeLocalPath();
        if (currentPath == null) {
            Toast.show(this, "No active pane — open a folder first.", Glyphs.WARNING);
            return;
        }
        if (dock.core.config.Favorites.addAll(favorites, List.of(currentPath)) > 0) {
            persistFavorites();
            Toast.show(this, "Added to favorites: " + currentPath, Glyphs.INFO);
        } else {
            Toast.show(this, "Already in favorites: " + currentPath, Glyphs.INFO);
        }
    }

    /** Builds the submenu listing all favorited directories. */
    private JMenu favoritesListMenu() {
        JMenu menu = new JMenu("Go to Favorite");
        menu.setIcon(Glyphs.icon(Glyphs.HOME, 15,
                () -> UIManager.getColor("Label.disabledForeground")));
        if (favorites.isEmpty()) {
            JMenuItem empty = new JMenuItem("(no favorites yet)");
            empty.setEnabled(false);
            menu.add(empty);
        } else {
            for (String path : favorites) {
                JMenuItem item = new JMenuItem(path);
                item.addActionListener(e -> navigateToFavorite(path));
                menu.add(item);
            }
            menu.addSeparator();
            menu.add(item("Remove All Favorites", Glyphs.TRASH, null, () -> {
                favorites.clear();
                persistFavorites();
                Toast.show(this, "All favorites removed.", Glyphs.INFO);
            }));
        }
        return menu;
    }

    /** Writes the shelf, then repaints the sidebar — every favorite change ends here. */
    private void persistFavorites() {
        try {
            dock.core.config.Favorites.save(favorites);
        } catch (java.io.IOException e) {
            Toast.show(this, "Favorites could not be saved: " + e.getMessage(), Glyphs.WARNING);
        }
        if (sidebar != null) sidebar.rebuild();
    }

    /**
     * Brings in the folders already bookmarked in SimpleCommander and in the
     * toolkit. A folder the shelf holds under a different spelling counts as
     * already here instead of becoming a second row.
     */
    private void importFavoritesFromSimpleCommander() {
        var result = dock.core.config.SimpleCommanderImport.importInto(favorites);
        if (result.added() > 0) persistFavorites();
        Toast.show(this, result.summary(),
                result.added() > 0 || result.alreadyHere() > 0 ? Glyphs.INFO : Glyphs.WARNING);
    }

    /** Navigates the active local pane to a favorited directory. */
    private void navigateToFavorite(String path) {
        if (localCommander != null && localCommander.isShowing()) {
            localCommander.activePane().navigate(path);
            Toast.show(this, "Navigated to: " + path, Glyphs.INFO);
        } else if (tabs.getSelectedComponent() instanceof dock.commander.SessionView view) {
            view.localPane().navigate(path);
            Toast.show(this, "Navigated to: " + path, Glyphs.INFO);
        } else {
            Toast.show(this, "No active pane — open a folder first.", Glyphs.WARNING);
        }
    }

    /** Returns the active local pane's path from either the local commander
     *  or the selected session tab — null when nothing is showing. */
    private String activeLocalPath() {
        if (localCommander != null && localCommander.isShowing()) {
            return localCommander.activePane().path();
        }
        if (tabs.getSelectedComponent() instanceof dock.commander.SessionView view) {
            return view.localPane().path();
        }
        return null;
    }

    /**
     * Commander/Explorer toggle for the selected session tab — the local
     * pane's visibility. The checkbox mirrors the tab's layout and
     * follows tab selection; terminal tabs and the home screen disable
     * it, and so does a folded remote pane (one pane must stay visible).
     */
    private JCheckBoxMenuItem explorerModeItem() {
        JCheckBoxMenuItem explorerMode = new JCheckBoxMenuItem("Explorer Mode",
                Glyphs.icon(Glyphs.MONITOR, 15,
                        () -> UIManager.getColor("Label.disabledForeground")));
        explorerMode.setAccelerator(KeyStroke.getKeyStroke("ctrl alt E"));
        explorerMode.addActionListener(e -> {
            if (onSessionCard()
                    && tabs.getSelectedComponent() instanceof SessionTab view) {
                view.setLocalPaneHidden(explorerMode.getState());
                syncPaneToggles();
            } else {
                // No session open yet: acknowledge the toggle so the user knows
                // it'll take effect once they connect.
                Toast.show(this, explorerMode.getState()
                        ? "Explorer mode will apply when you connect."
                        : "Commander mode will apply when you connect.", Glyphs.INFO);
            }
        });
        // Selection drives everything: connect/close/select all move it.
        tabs.addChangeListener(e -> syncPaneToggles());
        return explorerMode;
    }

    /** One truth for pane visibility: the View-menu checkbox and the
     *  footer's two edge buttons mirror the view under the window — the
     *  selected session, or the local commander when it shows. */
    private void syncPaneToggles() {
        SessionTab tab = onSessionCard()
                && tabs.getSelectedComponent() instanceof SessionTab t ? t : null;
        boolean commanderShowing = tab == null && localCommander != null
                && CARD_LOCAL.equals(currentCard);
        boolean localHidden = tab != null ? tab.localPaneHidden()
                : commanderShowing && localCommander.leftPaneHidden();
        boolean remoteHidden = tab != null ? tab.remotePaneHidden()
                : commanderShowing && localCommander.rightPaneHidden();
        if (explorerItem != null) {
            // Enabled with no session too — the user can set the mode before
            // connecting and the state is preserved for when one opens. Only
            // a session with its remote side folded refuses it: that would be
            // the last pane, and one pane always stays on screen.
            explorerItem.setEnabled(tab == null || !remoteHidden);
            explorerItem.setState(tab != null && tab.localPaneHidden());
        }
        if (secondPaneItem != null) {
            secondPaneItem.setState(tab != null ? !tab.remotePaneHidden()
                    : commanderShowing && !localCommander.rightPaneHidden());
            secondPaneItem.setEnabled(tab != null || commanderShowing);
        }
        if (statusBar != null) {
            statusBar.syncPaneToggles(tab != null || commanderShowing,
                    localHidden, remoteHidden);
        }
    }

    /** Auto mode: list a source's entries on the launcher, live. */
    private JCheckBoxMenuItem sourceShownItem(dock.core.config.SiteSource src) {
        JCheckBoxMenuItem mi = checkItem("Show " + src.menuName(), Glyphs.EYE, null);
        mi.setState(src.shown());
        mi.addActionListener(e -> {
            try {
                dock.core.config.AppSettings.setFlag(src.settingKey(), mi.getState());
            } catch (java.io.IOException ex) {
                Toast.show(this, "Could not save the setting: " + ex.getMessage(),
                        Glyphs.WARNING);
            }
            if (homeView != null) homeView.refresh();
        });
        return mi;
    }

    /** Manual mode: pick a source's entries to save as sessions. */
    private void importFrom(dock.core.config.SiteSource src) {
        java.util.List<dock.core.config.Site> hosts = src.sites();
        if (hosts.isEmpty()) {
            Toast.show(this, "Nothing to import: " + src.title().replace("From ", "")
                    + " lists no usable entries.", Glyphs.INFO);
            return;
        }
        int imported = new SourceImportDialog(this, "Import " + src.menuName(), hosts)
                .showAndImport();
        if (imported > 0) {
            if (homeView != null) homeView.refresh();
            Toast.show(this, "Imported " + imported + (imported == 1 ? " session." : " sessions."),
                    Glyphs.INFO);
        }
    }

    /** Show-hidden toggle for every open pane (View menu, Ctrl+H). */
    private JCheckBoxMenuItem hiddenFilesItem() {
        JCheckBoxMenuItem showHidden = checkItem("Show Hidden Files", Glyphs.EYE, "ctrl H");
        showHidden.addActionListener(e -> {
            ViewSettings.setShowHidden(showHidden.getState());
            applyViewSettings();
        });
        return showHidden;
    }

    /** Windows extra: also hide dot-prefixed names in local panes (off by default). */
    private JCheckBoxMenuItem dotFilesItem() {
        JCheckBoxMenuItem hideDot =
                checkItem("Hide Dot-Prefixed Files on Windows", Glyphs.FOLDER, null);
        hideDot.addActionListener(e -> {
            ViewSettings.setHideDotPrefixedOnWindows(hideDot.getState());
            applyViewSettings();
        });
        return hideDot;
    }

    private JCheckBoxMenuItem checkItem(String text, String glyph, String accelerator) {
        JCheckBoxMenuItem mi = new JCheckBoxMenuItem(text,
                Glyphs.icon(glyph, 15, () -> UIManager.getColor("Label.disabledForeground")));
        if (accelerator != null) mi.setAccelerator(KeyStroke.getKeyStroke(accelerator));
        return mi;
    }

    /** Pushes View-menu settings into every open pane. */
    private void applyViewSettings() {
        if (localCommander != null) localCommander.applyViewSettings();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof SessionTab view) view.applyViewSettings();
        }
    }

    /**
     * Saved-sites submenu, repopulated on every open so it never goes stale
     * after a connect (recency order) or a delete on the landing screen.
     */
    private JMenu savedSessionsMenu() {
        JMenu menu = new JMenu("Saved Sessions");
        populateSavedSessions(menu);
        menu.getPopupMenu().addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                populateSavedSessions(menu);
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });
        return menu;
    }

    private void populateSavedSessions(JMenu menu) {
        java.util.List<dock.core.config.Site> sites =
                dock.core.config.Sites.byRecency(dock.core.config.Sites.load());
        menu.removeAll();
        if (sites.isEmpty()) {
            JMenuItem none = new JMenuItem("No saved sessions yet");
            none.setEnabled(false);
            menu.add(none);
            return;
        }
        for (dock.core.config.Site s : sites) {
            JMenuItem mi = new JMenuItem(s.name(), Glyphs.icon(Glyphs.SERVER, 15,
                    () -> UIManager.getColor("Label.disabledForeground")));
            mi.setToolTipText(s.user() + "@" + s.host()
                    + (s.port() == 22 ? "" : ":" + s.port()));
            mi.addActionListener(ev -> connectSite(s, (err, fs) -> {
                if (err != null) {
                    Toast.show(MainWindow.this, "Could not connect to " + s.name() + ": "
                            + err.getMessage(), Glyphs.WARNING);
                }
            }));
            menu.add(mi);
        }
    }

    private void checkForUpdates() {
        launchToolkitActionFromToolbar(dock.terminal.ToolkitActions.PUSH_LATEST);
    }

    private void showKeyboardShortcuts() {
        JLabel title = new JLabel("Keyboard Shortcuts");
        title.setFont(FontRegistry.uiSemiBold(16));

        String[][] shortcuts = {
            {"File", ""},
            {"  New Session", "Ctrl+N"},
            {"  New Folder", "F7 (or Ctrl+D)"},
            {"  New File", "—"},
            {"  Open Terminal", "Ctrl+Shift+T"},
            {"  Local Shell", "Ctrl+Shift+L"},
            {"  Quit", "Ctrl+Q"},
            {"", ""},
            {"Edit", ""},
            {"  Copy", "Ctrl+C"},
            {"  Cut", "Ctrl+X"},
            {"  Paste", "Ctrl+V"},
            {"  Copy Paths", "Ctrl+Shift+C"},
            {"  Copy to Other Pane", "Ctrl+Alt+C"},
            {"  Move to Other Pane", "Ctrl+Shift+M"},
            {"  Select All", "Ctrl+A"},
            {"", ""},
            {"View", ""},
            {"  Transfers", "Ctrl+Alt+Q"},
            {"  Explorer Mode", "Ctrl+Alt+E"},
            {"  Show Hidden Files", "Ctrl+H"},
            {"  Preview Panel", "Space (toggles)"},
            {"  Show Sidebar", "—"},
            {"", ""},
            {"Go", ""},
            {"  Back", "Alt+Left"},
            {"  Forward", "Alt+Right"},
            {"  Up", "Backspace"},
            {"", ""},
            {"Tools", ""},
            {"  Filter", "Ctrl+F"},
            {"  Open in Explorer", "Ctrl+Shift+E"},
            {"  Script Settings", "Ctrl+,"},
            {"", ""},
            {"Favorites", ""},
            {"  Add Current Directory", "Ctrl+Shift+D"},
            {"", ""},
            {"Navigation", ""},
            {"  Search Sessions", "Ctrl+K"},
            {"  Keyboard Shortcuts", "Ctrl+/"},
            {"", ""},
            {"In a pane", ""},
            {"  Rename", "F2"},
            {"  Delete", "Delete"},
            {"  Properties", "Alt+Enter"},
            {"  Refresh", "Ctrl+R"},
            {"  Open — plays, views or edits by kind", "Enter"},
            {"  View an image, render a note, play media", "F3"},
            {"  Edit the text", "F4"},
            {"  Hop to the other pane", "Left / Right"},
            {"  Back to the list", "Escape"},
            {"  Pause playback, or step to the next image", "Space"},
        };

        JPanel grid = new JPanel(new java.awt.GridLayout(shortcuts.length, 2, Tokens.GAP_3, 2));
        grid.setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_3, Tokens.GAP_4,
                Tokens.GAP_2, Tokens.GAP_4));
        for (String[] row : shortcuts) {
            JLabel label = new JLabel(row[0]);
            JLabel key = new JLabel(row[1]);
            if (row[1].isEmpty()) {
                label.setFont(FontRegistry.uiSemiBold(13));
                label.setForeground(UIManager.getColor("Dock.accent"));
            } else {
                label.setFont(FontRegistry.ui());
                key.setFont(FontRegistry.mono(12));
            }
            key.setForeground(UIManager.getColor("Label.disabledForeground"));
            key.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
            grid.add(label);
            grid.add(key);
        }

        JOptionPane.showMessageDialog(this, grid, "Keyboard Shortcuts",
                JOptionPane.PLAIN_MESSAGE);
    }

    private void showAbout() {
        // Null when running straight from classes (IDE); the packaged jar
        // carries the Gradle version in its manifest.
        String version = getClass().getPackage().getImplementationVersion();
        JLabel title = new JLabel(version == null ? "Universal Media Explorer"
                : "Universal Media Explorer " + version);
        title.setFont(FontRegistry.uiSemiBold(16));
        JLabel body = new JLabel("A fast multi-protocol media file client.");
        body.setFont(FontRegistry.ui());
        body.setForeground(UIManager.getColor("Label.disabledForeground"));
        JLabel stack = new JLabel("Java " + Runtime.version().feature()
                + "  ·  FlatLaf  ·  Nerd Fonts");
        stack.setFont(FontRegistry.ui(12));
        stack.setForeground(UIManager.getColor("Label.disabledForeground"));
        JLabel legal = new JLabel("Copyright (c) 2026 Universal Media Explorer contributors"
                + "  ·  GPL-3.0");
        legal.setFont(FontRegistry.ui(12));
        legal.setForeground(UIManager.getColor("Label.disabledForeground"));

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(Tokens.GAP_3, Tokens.GAP_4,
                Tokens.GAP_2, Tokens.GAP_4));
        title.setAlignmentX(0f);
        body.setAlignmentX(0f);
        stack.setAlignmentX(0f);
        legal.setAlignmentX(0f);
        p.add(title);
        p.add(Box.createVerticalStrut(Tokens.GAP_1));
        p.add(body);
        p.add(Box.createVerticalStrut(Tokens.GAP_1));
        p.add(stack);
        p.add(Box.createVerticalStrut(Tokens.GAP_1));
        p.add(legal);

        JOptionPane.showMessageDialog(this, p, "About", JOptionPane.PLAIN_MESSAGE,
                Glyphs.iconRotated(Glyphs.PLANET, 32,
                        () -> UIManager.getColor("Dock.accent"), Glyphs.PLANET_TILT_DEG));
    }
}
