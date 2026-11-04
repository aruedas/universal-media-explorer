package dock.core.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * App preferences that outlive a run, as a flat JSON object in
 * settings.json beside sessions.json. Unknown keys survive a rewrite, and
 * a missing or unreadable file reads as all defaults.
 */
public final class AppSettings {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Show the hosts of ~/.ssh/config on the launcher, live (off by default). */
    public static final String SSH_CONFIG_HOSTS = "sshConfigHosts";

    /** Show the profiles of ~/.aws on the launcher, live (off by default). */
    public static final String AWS_PROFILES = "awsProfiles";

    /** Where the PowerShell media toolkit lives; see
     *  {@link ToolkitRoot#directory()}. Unset means fall back to the legacy
     *  directory, then to no toolkit at all. */
    public static final String TOOLKIT_ROOT = "toolkitRoot";

    private AppSettings() {}

    public static synchronized boolean flag(String key, boolean fallback) {
        return load().get(key) instanceof Boolean b ? b : fallback;
    }

    public static synchronized void setFlag(String key, boolean value) throws IOException {
        put(key, value);
    }

    public static synchronized String text(String key, String fallback) {
        return load().get(key) instanceof String s && !s.isBlank() ? s : fallback;
    }

    public static synchronized void setText(String key, String value) throws IOException {
        put(key, value);
    }

    private static void put(String key, Object value) throws IOException {
        Map<String, Object> all = load();
        all.put(key, value);
        JSON.writerWithDefaultPrettyPrinter().writeValue(file().toFile(), all);
    }

    private static Map<String, Object> load() {
        Path f = file();
        if (!Files.exists(f)) return new LinkedHashMap<>();
        try {
            return JSON.readValue(f.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private static Path file() {
        return AppPaths.config().resolve("settings.json");
    }
}
