package dock.terminal;

import dock.core.config.ToolkitRoot;
import dock.kit.Glyphs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Registry of toolkit actions that can be launched from the file commander.
 * Each action names a PowerShell script in the toolkit directory — see
 * {@link dock.core.config.ToolkitRoot} for where that directory comes from
 * and {@link #launch} for what happens when the script is not there.
 *
 * Actions store the file name, never a location: a hardcoded absolute path
 * resolves only on the machine that wrote it, and a toolbar that launches
 * nothing is indistinguishable from one that launched successfully.
 *
 * The toolbar's own order lives in LAYOUT: the local group, the upload, then
 * the remote (REP) group. Some actions are GODMODE-gated: they only appear
 * when the active pane's path contains "GODMODE" repeated 5 times.
 */
public final class ToolkitActions {

    public record Action(String name, String glyph, String script, String description,
                         boolean godmodeGated) {

        /** The script this action would run, or nothing when the toolkit has
         *  no such file. An absolute {@code script} is taken verbatim — that
         *  is how tests and ad-hoc actions point at a file directly. */
        public Optional<Path> scriptFile() {
            Path declared = Path.of(script);
            Optional<Path> candidate = declared.isAbsolute()
                    ? Optional.of(declared)
                    : ToolkitRoot.directory().map(dir -> dir.resolve(script));
            return candidate.filter(Files::isRegularFile);
        }

        public String commandFor(List<String> paths) throws IOException {
            Path script = scriptFile().orElseThrow(() -> new IOException(missingMessage(this)));
            return commandFor(script, paths);
        }

        String commandFor(Path script, List<String> paths) {
            StringBuilder cmd = new StringBuilder();
            cmd.append("& '").append(script).append("'");
            for (String path : paths) {
                cmd.append(" '").append(path.replace("'", "''")).append("'");
            }
            return cmd.toString();
        }
    }

    /** True when this action has a script on disk to run right now. */
    public static boolean isAvailable(Action action) {
        return action.scriptFile().isPresent();
    }

    /** The sentence a user reads instead of a stack trace: what was wanted,
     *  where it looked, and the two ways to point it somewhere else. */
    public static String missingMessage(Action action) {
        return "Toolkit script not found: " + action.script()
                + " (searched " + ToolkitRoot.describeSearch() + "). "
                + "Set the " + ToolkitRoot.ENV_VAR + " environment variable, or write the "
                + "toolkit folder into the \"" + ToolkitRoot.SETTINGS_KEY + "\" key of settings.json.";
    }

    // ── Media Commander actions (SimpleCommander order) ──────────────

    public static final Action NOMBRAR = new Action(
            "NOMBRAR", Glyphs.CLIPBOARD,
            "UE_FixFiles.ps1",
            "Auto TMDB rename (local)", false);

    public static final Action RENAME = new Action(
            "NOMBRAR S", Glyphs.CLIPBOARD,
            "UE_Rename.ps1",
            "Selective rename (local)", false);

    public static final Action EXTRAER = new Action(
            "EXTRAER", Glyphs.ARCHIVE,
            "ExtraerMedia.ps1",
            "Extract audio/subtitle tracks, then recertify", false);

    public static final Action CERTIFICAR = new Action(
            "CERTIFICAR", Glyphs.TINT,
            "UE_Certificar.ps1",
            "Certify media files (container check, audio repair, subtitles)", false);

    public static final Action GEN_M3U = new Action(
            "GEN M3U", Glyphs.ASTERISK,
            "WinSCP_Generate_M3U.ps1",
            "Generate M3U playlist from selection", true);

    public static final Action SUBIR_M3U = new Action(
            "SUBIR M3U", Glyphs.ARROW_UP,
            "XUI-ImportM3U.ps1",
            "Import M3U playlist (GUI)", true);

    public static final Action RESPALDO = new Action(
            "RESPALDO", Glyphs.WARNING,
            "WinSCP_Respaldo.ps1",
            "Backup selection to respaldo drives", false);

    public static final Action CFG = new Action(
            "CFG", Glyphs.GEAR,
            "WinSCP_Script_Settings.ps1",
            "WinSCP script settings (GUI)", false);

    public static final Action COMMANDER = new Action(
            "COMMANDER", Glyphs.TERMINAL,
            "MediaCommander.ps1",
            "Full MediaCommander tool (renamer + FTP/WinSCP)", false);

    // ── Remote (REP) variants ────────────────────────────────────────

    public static final Action CERTIFICAR_REP = new Action(
            "CERTIFICAR REP", Glyphs.BOLT,
            "Probe-RemoteMedia.ps1",
            "Remote certification probe", false);

    public static final Action NOMBRAR_REP = new Action(
            "NOMBRAR REP", Glyphs.CLIPBOARD,
            "WinSCP_Fixfiles_Remote.ps1",
            "Remote TMDB rename", false);

    public static final Action RENAME_REP = new Action(
            "RENAME REP", Glyphs.EDIT,
            "WinSCP_Fixfiles_Remote.ps1",
            "Remote selective rename", false);

    public static final Action RESPALDO_REP = new Action(
            "RESPALDO REP", Glyphs.MOON,
            "WinSCP_Respaldo_Remote.ps1",
            "Remote backup", false);

    // ── General utilities ────────────────────────────────────────────

    public static final Action UPLOAD = new Action(
            "UPLOAD", Glyphs.UPLOAD,
            "UE_Upload.ps1",
            "Upload media to SFTP server", false);

    public static final Action PUSH_LATEST = new Action(
            "PUSH LATEST", Glyphs.ARROW_UP,
            "Push-LatestVersion.ps1",
            "Push latest version to update manifest", false);

    private static final List<Action> ALL = List.of(
            NOMBRAR, RENAME, EXTRAER, CERTIFICAR,
            GEN_M3U, SUBIR_M3U, RESPALDO, COMMANDER, CFG,
            NOMBRAR_REP, RENAME_REP, CERTIFICAR_REP, RESPALDO_REP,
            UPLOAD, PUSH_LATEST
    );

    /**
     * One toolbar slot: a group caption ("LOCAL:") when {@code action} is
     * null, otherwise the button that action gets.
     */
    public record Entry(String caption, Action action) {
        static Entry section(String caption) {
            return new Entry(caption, null);
        }

        static Entry button(Action action) {
            return new Entry(null, action);
        }

        public boolean isSection() {
            return action == null;
        }
    }

    /**
     * The toolbar's order. CFG is deliberately absent: script settings are a
     * once-in-a-while dialog, not something that belongs beside a rename
     * button, so it lives in the Help menu instead.
     */
    private static final List<Entry> LAYOUT = List.of(
            Entry.section("LOCAL:"),
            Entry.button(NOMBRAR), Entry.button(RENAME), Entry.button(EXTRAER),
            Entry.button(CERTIFICAR), Entry.button(GEN_M3U), Entry.button(SUBIR_M3U),
            Entry.button(RESPALDO), Entry.button(COMMANDER), Entry.button(UPLOAD),
            Entry.section("REMOTE:"),
            Entry.button(NOMBRAR_REP), Entry.button(RENAME_REP),
            Entry.button(CERTIFICAR_REP), Entry.button(RESPALDO_REP),
            Entry.button(PUSH_LATEST)
    );

    public static List<Action> all() {
        return ALL;
    }

    /**
     * The toolbar's slots for the given directory path, in LAYOUT order and
     * without the actions the path doesn't unlock. A caption only ships when
     * at least one button follows it — a group empty of buttons never shows
     * its name.
     */
    public static List<Entry> layoutFor(String path) {
        boolean godmode = godmodeActive(path);
        List<Entry> out = new ArrayList<>();
        Entry pending = null;
        for (Entry entry : LAYOUT) {
            if (entry.isSection()) {
                pending = entry;
                continue;
            }
            if (entry.action().godmodeGated() && !godmode) continue;
            if (pending != null) {
                out.add(pending);
                pending = null;
            }
            out.add(entry);
        }
        return out;
    }

    /**
     * Returns actions visible for the given directory path.
     * GODMODE-gated actions only appear when the path contains "godmode" 5+ times.
     */
    public static List<Action> visibleFor(String path) {
        if (godmodeActive(path)) return ALL;
        List<Action> filtered = new ArrayList<>();
        for (Action a : ALL) {
            if (!a.godmodeGated()) filtered.add(a);
        }
        return filtered;
    }

    /** GODMODE unlocks the playlist actions; no pane at all shows everything. */
    public static boolean godmodeActive(String path) {
        if (path == null) return true;
        String lower = path.toLowerCase();
        int count = 0;
        int idx = 0;
        while ((idx = lower.indexOf("godmode", idx)) >= 0) {
            count++;
            idx += 7;
        }
        return count >= 5;
    }

    /** Set once by the UI layer: every launched shell surfaces in a terminal
     *  tab so the user sees what the script is doing. Null (tests, headless)
     *  means the shell runs without a visible tab. */
    private static volatile BiConsumer<String, LocalShell> tabOpener;

    public static void setTabOpener(BiConsumer<String, LocalShell> opener) {
        tabOpener = opener;
    }

    public static LocalShell launch(Action action, List<String> paths, String workingDirectory)
            throws IOException {
        String command = action.commandFor(paths);
        LocalShell shell = LocalShell.spawnPowerShell(command, workingDirectory);
        BiConsumer<String, LocalShell> opener = tabOpener;
        if (opener != null) opener.accept(action.name(), shell);
        return shell;
    }
}
