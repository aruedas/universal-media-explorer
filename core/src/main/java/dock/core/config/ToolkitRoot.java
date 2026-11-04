package dock.core.config;

import dock.core.platform.Os;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The directory that holds the PowerShell media toolkit and the fleet INI it
 * shares with SimpleCommander.
 *
 * Nothing here is required: an install without a toolkit runs the whole
 * commander, it simply has no media scripts to launch. The root is resolved
 * per call, never cached, so pointing the environment variable or the
 * settings key at a checkout takes effect on the next click rather than
 * requiring a restart.
 *
 * Resolution order, first hit wins:
 * <ol>
 *   <li>the {@value #ENV_VAR} environment variable;</li>
 *   <li>the {@value #SETTINGS_KEY} key in settings.json;</li>
 *   <li>the legacy toolkit directory, but only on Windows and only when it
 *       actually exists - it is where the fleet has kept its scripts for as
 *       long as this app has run there, so an operator upgrading changes
 *       nothing.</li>
 * </ol>
 *
 * The order is not accidental: the INI used to be reachable only through a
 * hardcoded absolute path, and a path cannot be configured inside the file it
 * points at. Reading the setting from the app's own config tree breaks that
 * circle.
 */
public final class ToolkitRoot {

    public static final String ENV_VAR = "UME_TOOLKIT_PATH";

    /** Settings key holding the same value; see {@link AppSettings#TOOLKIT_ROOT}. */
    public static final String SETTINGS_KEY = "toolkitRoot";

    /** The fleet's toolkit directory on Windows, kept as the last fallback. */
    static final String LEGACY_DIR = "C:\\Scripts";

    /** Test/harness isolation; takes precedence over env and settings. */
    private static volatile Path override;

    private ToolkitRoot() {}

    /** The configured root, whether or not it exists on disk - a missing
     *  directory still names the place the user asked for, which is what the
     *  "not found" messages need. Empty when nothing is configured and the
     *  legacy directory is absent. */
    public static Optional<Path> directory() {
        Path o = override;
        if (o != null) return Optional.of(o);
        Path fromEnv = parse(System.getenv(ENV_VAR));
        if (fromEnv != null) return Optional.of(fromEnv);
        Path fromSettings = parse(AppSettings.text(SETTINGS_KEY, null));
        if (fromSettings != null) return Optional.of(fromSettings);
        if (Os.current() == Os.WINDOWS) {
            Path legacy = Path.of(LEGACY_DIR);
            if (Files.isDirectory(legacy)) return Optional.of(legacy);
        }
        return Optional.empty();
    }

    /** A toolkit file by name: {@code m3u_server_methods.ini}, a script.
     *  Resolves to nothing when there is no root or the file is not there, so
     *  callers never launch against a path they have not seen. */
    public static Optional<Path> file(String name) {
        return directory().map(dir -> dir.resolve(name))
                .filter(Files::isRegularFile);
    }

    /** Where it looked, for the message a user reads when nothing resolved. */
    public static String describeSearch() {
        Optional<Path> dir = directory();
        if (dir.isPresent()) {
            return dir.get() + " (from " + source() + ")";
        }
        return ENV_VAR + ", then the " + SETTINGS_KEY + " key in "
                + AppPaths.config().resolve("settings.json")
                + (Os.current() == Os.WINDOWS ? ", then " + LEGACY_DIR : "");
    }

    private static String source() {
        if (override != null) return "test override";
        if (parse(System.getenv(ENV_VAR)) != null) return ENV_VAR;
        return "the " + SETTINGS_KEY + " setting";
    }

    private static Path parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value.trim());
        } catch (RuntimeException badPath) {
            return null;
        }
    }

    /** Redirects the root (tests); null restores the real resolution. */
    public static void override(Path dir) {
        override = dir;
    }
}
