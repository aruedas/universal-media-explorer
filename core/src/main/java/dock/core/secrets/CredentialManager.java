package dock.core.secrets;

import dock.core.platform.Platform;

/**
 * Password/passphrase storage in the OS credential store (see
 * {@link Platform#secrets()}): the Windows Credential Manager or the macOS
 * login Keychain. Secrets never touch a file of ours.
 */
public final class CredentialManager {

    /** The app was Project Dock once, then Universal Explorer. Loads that
     *  miss a current target retry legacy twins ("Universal Explorer/…"
     *  then "Dock/…") and copy the secret forward; deletes take the twins
     *  down too, so no stale credential outlives the site. */
    private static final String TARGET_PREFIX = "Universal Media Explorer/";
    private static final String[] LEGACY_PREFIXES = {
        "Universal Explorer/",
        "Dock/"
    };

    private CredentialManager() {}

    public static boolean available() {
        return store().available();
    }

    /** The OS store's name for UI text ("Keychain"). */
    public static String storeName() {
        return store().displayName();
    }

    public static void save(String target, String secret) {
        if (!available() || secret == null) return;
        delete(target);
        store().write(target, secret);
    }

    /** Returns null when nothing is stored (or the OS has no store). A miss
     *  on a current target retries the legacy twins and copies the secret
     *  forward — the fallback never destroys the old entry. */
    public static String load(String target) {
        if (!available()) return null;
        String secret = store().read(target);
        if (secret == null && target.startsWith(TARGET_PREFIX)) {
            String suffix = target.substring(TARGET_PREFIX.length());
            for (String legacyPrefix : LEGACY_PREFIXES) {
                secret = store().read(legacyPrefix + suffix);
                if (secret != null) {
                    store().write(target, secret);
                    break;
                }
            }
        }
        return secret;
    }

    public static void delete(String target) {
        if (!available()) return;
        store().remove(target);
        if (target.startsWith(TARGET_PREFIX)) {
            String suffix = target.substring(TARGET_PREFIX.length());
            for (String legacyPrefix : LEGACY_PREFIXES) {
                store().remove(legacyPrefix + suffix);
            }
        }
    }

    private static SecretStore store() {
        return Platform.secrets();
    }
}
