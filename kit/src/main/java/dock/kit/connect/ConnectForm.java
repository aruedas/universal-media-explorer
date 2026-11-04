package dock.kit.connect;

/**
 * The connect dialog seen from a fragment's side: the common chrome's
 * readable state plus the two hooks (port and prefill) a fragment's own
 * widgets need. The dialog implements this; fragments never see the
 * dialog class itself.
 */
public interface ConnectForm {

    String host();

    int port();

    /** Replaces the port — for the port-follow rules (443↔80, 21↔990). */
    void setPort(int port);

    /** True when the port field still shows the candidate default (i.e.
     *  the user has not typed their own — only then may a rule move it). */
    boolean portIs(int candidate);

    String user();

    /** The password field's contents (a fresh array; may be empty). */
    char[] password();

    /** The key-passphrase field's contents (a fresh array; may be empty). */
    char[] passphrase();

    String keyPath();

    String path();

    // ---- prefill hooks (edit mode) ----

    void setPath(String path);

    void setKeyPath(String keyPath);

    /** The transport options as the form shows them; null while they are
     *  unopened, which means "dial with the defaults". */
    dock.core.config.Site.SftpOptions sftpOptions();

    /** Edit mode: puts a saved site's transport options back in the form. */
    void setSftpOptions(dock.core.config.Site.SftpOptions options);

    /** The auth mode currently selected in the picker. */
    AuthMode authMode();
}
