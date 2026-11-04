package dock.kit;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Applies the FlatLaf theme. Follows the Windows dark-mode setting by
 * default; an explicit in-app switch disables following until re-enabled.
 *
 * Three modes: Light, Dark, and Black (SimpleCommander-style AMOLED).
 *
 * System theme changes are picked up by a low-frequency registry poll (2s).
 * The alternative — intercepting WM_SETTINGCHANGE via a native window
 * subclass — costs fragile JNA code for a two-second latency win.
 */
public final class ThemeManager {

    public enum Mode { LIGHT, DARK, BLACK }

    private static volatile Mode mode = Mode.BLACK;
    private static volatile boolean followSystem = true;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ThemeManager() {}

    public static void init() {
        FlatLaf.registerCustomDefaultsSource("dock.themes");
        mode = systemPrefersDark() ? Mode.BLACK : Mode.LIGHT;
        apply();
        Thread.ofVirtual().name("theme-watcher").start(() -> {
            boolean lastDark = mode != Mode.LIGHT;
            while (true) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                if (!followSystem) continue;
                boolean dark = systemPrefersDark();
                if (dark != lastDark) {
                    lastDark = dark;
                    Mode next = dark ? Mode.BLACK : Mode.LIGHT;
                    SwingUtilities.invokeLater(() -> {
                        mode = next;
                        apply();
                    });
                }
            }
        });
    }

    /** Explicit user choice: switches theme and stops following the system. */
    public static void setMode(Mode m) {
        followSystem = false;
        mode = m;
        apply();
    }

    /** Cycles Light → Dark → Black → Light. */
    public static void cycleNext() {
        setMode(switch (mode) {
            case LIGHT -> Mode.DARK;
            case DARK  -> Mode.BLACK;
            case BLACK -> Mode.LIGHT;
        });
    }

    public static void setFollowSystem(boolean follow) {
        followSystem = follow;
        if (follow) {
            mode = systemPrefersDark() ? Mode.BLACK : Mode.LIGHT;
            apply();
        }
    }

    public static boolean isFollowSystem() { return followSystem; }
    public static Mode mode() { return mode; }

    /** Human-readable label for menus. */
    public static String modeLabel() {
        return switch (mode) {
            case LIGHT -> "Light";
            case DARK  -> "Dark";
            case BLACK -> "Black";
        };
    }

    /** Notified (on the EDT) after every theme application. */
    public static void addListener(Runnable l) { listeners.add(l); }

    private static void apply() {
        FlatLaf laf = switch (mode) {
            case LIGHT -> new FlatLightLaf();
            case DARK  -> new FlatDarkLaf();
            case BLACK -> new FlatBlackLaf();
        };
        FlatLaf.setup(laf);
        // Icons can't be expressed in theme properties; re-register after every
        // LAF setup (which resets UIDefaults). Small IntelliJ-style close mark.
        // Color: the hidden tab-close button's foreground (hover-aware), with
        // closeForeground as fallback — plain "TabbedPane.foreground" does not
        // exist in FlatLaf and resolves to null (NPEs inside the tab renderer).
        UIManager.put("TabbedPane.closeIcon",
                Glyphs.iconInheritForeground(Glyphs.CLOSE, 10,
                        () -> UIManager.getColor("TabbedPane.closeForeground")));
        FontRegistry.applyUiDefaults();
        FlatLaf.updateUI();
        listeners.forEach(Runnable::run);
    }

    /** {@code AppsUseLightTheme} = 0 means the system is in dark mode. */
    static boolean systemPrefersDark() {
        try {
            return Advapi32Util.registryGetIntValue(
                    WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "AppsUseLightTheme") == 0;
        } catch (Throwable t) {
            return true; // key missing (older Windows or hardened registry): default to dark
        }
    }
}
