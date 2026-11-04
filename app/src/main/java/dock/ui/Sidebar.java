package dock.ui;

import dock.commander.FilePane;
import dock.core.config.Site;
import dock.core.config.Sites;
import dock.core.fs.LocalFs;
import dock.kit.FontRegistry;
import dock.kit.Glyphs;
import dock.kit.ThemeManager;
import dock.kit.Tokens;
import dock.kit.Toast;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

/**
 * The left sidebar — Favorites, Drives, Media Servers, Respaldo, and saved
 * Sessions. Theme-aware: reads all colors from UIManager so it follows
 * whichever FlatLaf theme is active (Light / Dark / Black).
 */
public final class Sidebar extends JPanel {

    /** Host-side callbacks the sidebar needs to drive the app. */
    public interface Host {
        void navigate(FilePane pane, String path);
        void connectSite(Site site);
        List<String> favorites();
        void addFavorite(String path);
        void removeFavorite(String path);
        /** Returns the active pane's current directory, or null if none. */
        String currentPath();
    }

    private final Host host;
    private final JPanel content = new JPanel();
    private final List<String> favorites;

    public Sidebar(Host host) {
        super(new BorderLayout());
        this.host = host;
        this.favorites = host.favorites();

        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        content.setOpaque(false);

        rebuild();

        JScrollPane scroll = new JScrollPane(content,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);

        setPreferredSize(new Dimension(210, 0));
        setMinimumSize(new Dimension(160, 0));

        ThemeManager.addListener(this::applySidebarColors);
        applySidebarColors();
    }

    private void applySidebarColors() {
        setBackground(uiBg());
        content.setBackground(uiBg());
        rebuild();
    }

    /** Re-reads INI + favorites and rebuilds every section. */
    public void rebuild() {
        content.removeAll();
        favoritesSection();
        content.add(sectionSeparator());
        drivesSection();
        content.add(sectionSeparator());
        serversSection();
        content.add(sectionSeparator());
        respaldoSection();
        content.add(sectionSeparator());
        sessionsSection();
        content.add(Box.createVerticalGlue());
        content.revalidate();
        content.repaint();
    }

    // ── Theme-aware color helpers ────────────────────────────────────

    private static Color uiBg() {
        Color c = UIManager.getColor("Panel.background");
        return c != null ? c : new Color(0x0F, 0x11, 0x13);
    }

    private static Color uiFg() {
        Color c = UIManager.getColor("Label.foreground");
        return c != null ? c : new Color(0xD4, 0xD4, 0xD4);
    }

    private static Color uiWeak() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : new Color(0x70, 0x73, 0x78);
    }

    private static Color uiAccent() {
        Color c = UIManager.getColor("Dock.accent");
        if (c != null) return c;
        c = UIManager.getColor("Component.accentColor");
        return c != null ? c : new Color(0x4D, 0xA6, 0xFF);
    }

    private static Color uiHoverBg() {
        Color c = UIManager.getColor("MenuItem.selectionBackground");
        if (c != null) return c;
        c = UIManager.getColor("List.selectionBackground");
        return c != null ? c : new Color(0x1E, 0x21, 0x25);
    }

    private static Color uiSeparator() {
        Color c = UIManager.getColor("Separator.foreground");
        return c != null ? c : new Color(0x24, 0x27, 0x2C);
    }

    private static Color uiSectionBg() {
        Color c = UIManager.getColor("Dock.cardBackground");
        return c != null ? c : uiBg();
    }

    // ── Favorites ────────────────────────────────────────────────────

    private void favoritesSection() {
        sectionHeader("FAVORITES");
        List<String> paths = List.copyOf(favorites);
        List<String> labels = favoriteLabels(paths);
        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            JPanel row = navRow(Glyphs.HOME, labels.get(i), path, r -> {
                r.setToolTipText(path);
                MouseAdapter menu = new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) {
                        if (SwingUtilities.isRightMouseButton(e)) {
                            showFavoriteContextMenu(r, path);
                        }
                    }
                    @Override public void mouseClicked(MouseEvent e) {
                        if (SwingUtilities.isRightMouseButton(e)) {
                            showFavoriteContextMenu(r, path);
                        }
                    }
                };
                r.addMouseListener(menu);
                for (Component c : r.getComponents()) c.addMouseListener(menu);
            });
        }
        JPanel addBtn = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        addBtn.setBackground(uiBg());
        addBtn.setOpaque(true);
        addBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        addBtn.setBorder(BorderFactory.createEmptyBorder(2, Tokens.GAP_3, 2, Tokens.GAP_3));
        JLabel add = new JLabel("+ Add current folder");
        add.setFont(FontRegistry.ui(11));
        add.setForeground(uiAccent());
        add.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        add.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                String current = host.currentPath();
                if (current != null) {
                    host.addFavorite(current);
                    rebuild();
                }
            }
            @Override public void mouseEntered(MouseEvent e) {
                add.setForeground(uiAccent().brighter());
            }
            @Override public void mouseExited(MouseEvent e) {
                add.setForeground(uiAccent());
            }
        });
        addBtn.add(add);
        content.add(addBtn);
    }

    private void showFavoriteContextMenu(Component invoker, String path) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem remove = new JMenuItem("Remove favorite");
        remove.addActionListener(e -> {
            host.removeFavorite(path);
            rebuild();
        });
        menu.add(remove);
        menu.show(invoker, 0, invoker.getHeight());
    }

    /**
     * One row label per favorite. Two folders called "Downloads" — say
     * {@code F:\Downloads} and {@code C:\Users\operator\Downloads} — would
     * otherwise paint the same row twice with nothing to tell them apart, so
     * a repeated name carries its parent. Public because it is the naming
     * rule, and the shelf it reads is the imported one.
     */
    public static List<String> favoriteLabels(List<String> paths) {
        Map<String, Integer> repeats = new HashMap<>();
        for (String p : paths) repeats.merge(baseName(p), 1, Integer::sum);
        List<String> labels = new ArrayList<>(paths.size());
        for (String p : paths) {
            String name = baseName(p);
            labels.add(repeats.get(name) > 1 ? name + " — " + parentHint(p) : name);
        }
        return labels;
    }

    /** The folder's own name; a drive root has none, so it shows as its path. */
    private static String baseName(String path) {
        String name = new File(path).getName();
        return name.isEmpty() ? path : name;
    }

    /** Where it lives: the drive for a top-level folder, the parent otherwise. */
    private static String parentHint(String path) {
        String parent = new File(path).getParent();
        return parent == null ? path : parent;
    }

    // ── Drives ───────────────────────────────────────────────────────

    private void drivesSection() {
        sectionHeader("DRIVES");
        for (String root : LocalFs.INSTANCE.roots()) {
            String label = root.replace("\\", "");
            if (label.isEmpty()) label = "/";
            navRow(Glyphs.HDD, label, root, null);
            driveBar(root);
        }
    }

    private void driveBar(String root) {
        double usedFrac = -1;
        String tooltip = "";
        try {
            FileStore store = Files.getFileStore(Path.of(root));
            long total = store.getTotalSpace();
            long free = store.getUsableSpace();
            if (total > 0) {
                usedFrac = (double) (total - free) / total;
                long usedGb = (total - free) / 1_073_741_824L;
                long totalGb = total / 1_073_741_824L;
                int pct = (int) (usedFrac * 100);
                tooltip = usedGb + "/" + totalGb + " GB (" + pct + "%)";
            }
        } catch (Exception ignored) {}

        JPanel bar = new DriveBar(usedFrac >= 0 ? usedFrac : 0);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        bar.setBorder(BorderFactory.createEmptyBorder(0, Tokens.GAP_3, 2, Tokens.GAP_3));
        if (!tooltip.isEmpty()) bar.setToolTipText(tooltip);
        content.add(bar);
    }

    // ── Media Servers ────────────────────────────────────────────────

    private void serversSection() {
        sectionHeader("MEDIA SERVERS");
        IniConfig ini = IniConfig.load();
        if (ini.servers().isEmpty()) {
            mutedRow("(no servers in INI)");
            return;
        }
        for (IniConfig.MediaServer srv : ini.servers()) {
            String tag = srv.indexOnly() ? "  [Index]" : "";
            String glyph = srv.indexOnly() ? Glyphs.EYE : Glyphs.SERVER;
            JPanel row = navRow(glyph, srv.name() + tag, null, null);
            JPanel toggles = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            toggles.setBackground(uiBg());
            toggles.setOpaque(true);
            toggles.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
            toggles.setBorder(BorderFactory.createEmptyBorder(0, Tokens.GAP_4, 2, Tokens.GAP_3));
            JCheckBox mov = new JCheckBox("M", srv.moviesEnabled());
            JCheckBox ser = new JCheckBox("S", srv.seriesEnabled());
            mov.setFont(FontRegistry.ui(9));
            ser.setFont(FontRegistry.ui(9));
            mov.setMargin(new Insets(0, 2, 0, 2));
            ser.setMargin(new Insets(0, 2, 0, 2));
            mov.setForeground(uiWeak());
            ser.setForeground(uiWeak());
            mov.setOpaque(false);
            ser.setOpaque(false);
            mov.setToolTipText("Movies " + (srv.moviesEnabled() ? "ON" : "OFF"));
            ser.setToolTipText("Series " + (srv.seriesEnabled() ? "ON" : "OFF"));
            // A toggle is the operator's standing choice: it writes itself
            // into the fleet INI, where SimpleCommander and the PowerShell
            // toolkit read the same switch. A failed write restores the
            // checkbox — the picture never lies about the file.
            mov.addItemListener(e -> {
                if (e.getStateChange() != java.awt.event.ItemEvent.SELECTED
                        && e.getStateChange() != java.awt.event.ItemEvent.DESELECTED) return;
                boolean on = e.getStateChange() == java.awt.event.ItemEvent.SELECTED;
                try {
                    IniConfig.setServerMoviesEnabled(srv.index(), on);
                    mov.setToolTipText("Movies " + (on ? "ON" : "OFF"));
                } catch (Exception ex) {
                    mov.setSelected(!on);
                    Toast.show(this, "Could not save the switch: "
                            + ex.getMessage(), Glyphs.WARNING);
                }
            });
            ser.addItemListener(e -> {
                if (e.getStateChange() != java.awt.event.ItemEvent.SELECTED
                        && e.getStateChange() != java.awt.event.ItemEvent.DESELECTED) return;
                boolean on = e.getStateChange() == java.awt.event.ItemEvent.SELECTED;
                try {
                    IniConfig.setServerSeriesEnabled(srv.index(), on);
                    ser.setToolTipText("Series " + (on ? "ON" : "OFF"));
                } catch (Exception ex) {
                    ser.setSelected(!on);
                    Toast.show(this, "Could not save the switch: "
                            + ex.getMessage(), Glyphs.WARNING);
                }
            });
            toggles.add(mov);
            toggles.add(ser);
            content.add(toggles);
            row.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() >= 2) {
                        Site site = new Site(srv.name(), srv.host(), 22,
                                "srv-user", null);
                        host.connectSite(site);
                    }
                }
            });
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            row.setToolTipText(srv.host() + ":" + srv.port() + srv.home());
        }
    }

    // ── Respaldo ─────────────────────────────────────────────────────

    private void respaldoSection() {
        sectionHeader("RESPALDO");
        IniConfig ini = IniConfig.load();
        if (ini.respaldoDrives().isEmpty()) {
            mutedRow("(no backup drives)");
            return;
        }
        for (IniConfig.RespaldoDrive d : ini.respaldoDrives()) {
            if (!d.external()) continue;
            String display = d.root() + (d.root().endsWith("\\") ? "" : "\\");
            navRow(Glyphs.HDD, display, d.root(), null);
            driveBar(d.root());
        }
    }

    // ── Sessions ─────────────────────────────────────────────────────

    private void sessionsSection() {
        sectionHeader("SESSIONS");
        List<Site> sites = Sites.load();
        if (sites.isEmpty()) {
            mutedRow("(no saved sessions)");
            return;
        }
        for (Site s : sites) {
            navRow(Glyphs.SERVER, s.name(), null, row -> {
                row.addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) {
                        host.connectSite(s);
                    }
                });
                row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                row.setToolTipText(s.user() + "@" + s.host()
                        + (s.port() == 22 ? "" : ":" + s.port()));
            });
        }
    }

    // ── Row builders ─────────────────────────────────────────────────

    private void sectionHeader(String text) {
        JLabel lbl = new JLabel(text, SwingConstants.CENTER);
        lbl.setFont(FontRegistry.uiSemiBold(10));
        lbl.setForeground(uiWeak());
        lbl.setOpaque(true);
        lbl.setBackground(uiSectionBg());
        lbl.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        lbl.setBorder(BorderFactory.createEmptyBorder(6, Tokens.GAP_3, 4, Tokens.GAP_3));
        content.add(lbl);
    }

    private void mutedRow(String text) {
        JLabel lbl = new JLabel("  " + text);
        lbl.setFont(FontRegistry.ui(11));
        lbl.setForeground(uiWeak());
        lbl.setOpaque(false);
        lbl.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        lbl.setBorder(BorderFactory.createEmptyBorder(0, Tokens.GAP_3, 0, Tokens.GAP_3));
        content.add(lbl);
    }

    private JPanel navRow(String glyph, String text, String navigatePath,
                          Consumer<JPanel> extra) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(uiBg());
        row.setOpaque(true);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        row.setBorder(BorderFactory.createEmptyBorder(2, Tokens.GAP_3, 2, Tokens.GAP_3));

        JLabel icon = new JLabel(Glyphs.icon(glyph, 14, Sidebar::uiWeak));
        icon.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
        row.add(icon, BorderLayout.WEST);

        JLabel label = new JLabel(text);
        label.setFont(FontRegistry.ui(12));
        label.setForeground(uiFg());
        row.add(label, BorderLayout.CENTER);

        MouseAdapter hover = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                row.setBackground(uiHoverBg());
                label.setForeground(uiAccent());
            }
            @Override public void mouseExited(MouseEvent e) {
                row.setBackground(uiBg());
                label.setForeground(uiFg());
            }
        };
        attachDeep(row, hover);

        if (navigatePath != null) {
            Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
            row.setCursor(hand);
            icon.setCursor(hand);
            label.setCursor(hand);
            // The click must answer for the whole row: the label and icon paint
            // over most of it, and Swing mouse events land on the deepest
            // component under the cursor without bubbling to the parent.
            attachDeep(row, new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        host.navigate(null, navigatePath);
                    }
                }
            });
        }

        if (extra != null) extra.accept(row);
        content.add(row);
        return row;
    }

    /** Registers a mouse adapter on a nav row and on every child that paints over it. */
    private static void attachDeep(JPanel row, MouseAdapter adapter) {
        row.addMouseListener(adapter);
        for (Component c : row.getComponents()) c.addMouseListener(adapter);
    }

    private Component sectionSeparator() {
        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setForeground(uiSeparator());
        sep.setBackground(uiSeparator());
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setBorder(BorderFactory.createEmptyBorder(4, Tokens.GAP_3, 4, Tokens.GAP_3));
        return sep;
    }

    // ── Drive capacity bar ───────────────────────────────────────────

    /** Thin bar that fills left-to-right with a color threshold: accent under
     *  80 %, warn 80-92 %, error above 92 %. */
    private static final class DriveBar extends JPanel {
        private final double fraction;

        DriveBar(double fraction) {
            this.fraction = Math.max(0, Math.min(1, fraction));
            setOpaque(false);
            setPreferredSize(new Dimension(0, 5));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int trackH = 3;
            int y = (h - trackH) / 2;
            int trackW = w - Tokens.GAP_3;
            if (trackW < 4) trackW = 4;

            g2.setColor(uiSeparator());
            g2.fillRoundRect(0, y, trackW, trackH, 3, 3);

            if (fraction > 0) {
                Color fill = fraction >= 0.92 ? new Color(0xFF, 0x5C, 0x5C)
                           : fraction >= 0.80 ? new Color(0xFF, 0xB8, 0x4D)
                           : uiAccent();
                g2.setColor(fill);
                int filled = Math.max(2, (int) (trackW * fraction));
                g2.fillRoundRect(0, y, filled, trackH, 3, 3);
            }
            g2.dispose();
        }
    }
}
