package dock.sftp;

import dock.sftp.SftpFs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.PropertyResolverUtils;
import org.apache.sshd.common.compression.BuiltinCompressions;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.SftpModuleProperties;

/**
 * Connects SFTP sessions. One connect = one virtual thread's blocking
 * workload; never call from the EDT.
 */
public final class SshSessions {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration AUTH_TIMEOUT = Duration.ofSeconds(30);
    /** Keepalive cadence; also keeps NAT/firewall mappings from idling out. */
    public static final Duration KEEPALIVE_INTERVAL = Duration.ofSeconds(30);
    /** Unanswered keepalives tolerated before the session is declared dead. */
    public static final int KEEPALIVE_NO_REPLY_MAX = 3;

    private SshSessions() {}

    /**
     * Everything needed to (re)establish a session. Passwords live only in
     * memory. {@code useAgent} offers the keys of the Windows OpenSSH agent
     * (YubiKey); no secret is handled by the app in that mode.
     */
    public record ConnectionSpec(String user, String host, int port,
                                 char[] password, String keyPath, char[] passphrase,
                                 boolean useAgent,
                                 dock.core.config.Site.SftpOptions sftpOptions) {
        public ConnectionSpec {
            // The spec outlives the dial (reconnects re-authenticate from
            // it), while callers wipe their secret arrays as soon as
            // connect returns — it must own private copies or reconnects
            // see blanked-out secrets.
            password = password == null ? null : password.clone();
            passphrase = passphrase == null ? null : passphrase.clone();
        }

        public ConnectionSpec(String user, String host, int port,
                              char[] password, String keyPath, char[] passphrase) {
            this(user, host, port, password, keyPath, passphrase, false, null);
        }

        public ConnectionSpec(String user, String host, int port,
                              char[] password, String keyPath, char[] passphrase,
                              boolean useAgent) {
            this(user, host, port, password, keyPath, passphrase, useAgent, null);
        }
    }

    /** Notified (on an SSH thread) when the session drops for reasons other than close(). */
    public interface ConnectionListener {
        void connectionLost();
    }

    public static SftpFs connect(String user, String host, int port,
                                 char[] password, String keyPath, char[] passphrase,
                                 ServerKeyVerifier verifier) throws IOException {
        return connect(new ConnectionSpec(user, host, port, password, keyPath, passphrase),
                verifier, null);
    }

    public static SftpFs connect(ConnectionSpec spec, ServerKeyVerifier verifier,
                                 ConnectionListener onLost) throws IOException {
        return connect(spec, verifier, onLost, null);
    }

    /**
     * @param agentFactory agent bridge for {@code spec.useAgent()}; null uses
     *                     the Windows OpenSSH agent pipe (tests inject a fake).
     */
    public static SftpFs connect(ConnectionSpec spec, ServerKeyVerifier verifier,
                                 ConnectionListener onLost,
                                 dock.sftp.WindowsAgent.Factory agentFactory)
            throws IOException {
        dock.sftp.WindowsAgent.Factory agent = null;
        if (spec.useAgent()) {
            // Before dialing: an unreachable agent would otherwise surface as
            // a generic "authentication failed" with no hint what to start.
            agent = agentFactory != null ? agentFactory
                    : new dock.sftp.WindowsAgent.Factory();
            agent.probe();
        }
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(verifier);

        // Reply-expecting keepalive (OpenSSH ServerAliveInterval semantics).
        // A connection dropped for inactivity — NAT/firewall idle timeout,
        // sleep/wake, server policy — usually delivers no close notification:
        // nothing ever notices, sessionClosed never fires, the UI never
        // reconnects. Each keepalive@ request expects a reply; three
        // unanswered ones close the session, which triggers the reconnect.
        // (MINA's default here is disabled; the old IGNORE heartbeat was
        // send-only and could not detect a dead peer.)
        dock.core.config.Site.SftpOptions opts = spec.sftpOptions();
        long keepaliveMs = secondsOrDefault(opts == null ? null : opts.keepaliveIntervalSec(),
                KEEPALIVE_INTERVAL).toMillis();
        int keepaliveMax = opts != null && opts.keepaliveMaxCount() != null && opts.keepaliveMaxCount() > 0
                ? opts.keepaliveMaxCount()
                : KEEPALIVE_NO_REPLY_MAX;
        PropertyResolverUtils.updateProperty(client,
                CoreModuleProperties.HEARTBEAT_INTERVAL.getName(), keepaliveMs);
        PropertyResolverUtils.updateProperty(client,
                CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.getName(), keepaliveMax);

        // Everything else is opt-in per site: a saved site with no options
        // block dials exactly as it did before these toggles existed.
        if (opts != null) {
            applyDialOptions(client, opts);
        }
        Duration connectTimeout = secondsOrDefault(opts == null ? null : opts.timeoutSec(),
                CONNECT_TIMEOUT);
        Duration authTimeout = secondsOrDefault(opts == null ? null : opts.timeoutSec(),
                AUTH_TIMEOUT);

        client.start();
        try {
            ClientSession session = client.connect(spec.user(), spec.host(), spec.port())
                    .verify(connectTimeout).getSession();

            if (spec.useAgent()) {
                // Reachability was probed before the dial; a mid-auth
                // disappearance surfaces as an auth failure.
                client.setAgentFactory(agent);
            }

            boolean haveIdentity = spec.useAgent();
            if (spec.password() != null && spec.password().length > 0) {
                session.addPasswordIdentity(new String(spec.password()));
                haveIdentity = true;
            }
            if (spec.keyPath() != null && !spec.keyPath().isBlank()) {
                for (KeyPair kp : loadKey(spec.keyPath(), spec.passphrase())) {
                    session.addPublicKeyIdentity(kp);
                    haveIdentity = true;
                }
            }
            if (!haveIdentity) {
                throw new IOException("No authentication method: provide a password or a key file.");
            }

            try {
                session.auth().verify(authTimeout);
            } catch (IOException e) {
                throw new IOException("Authentication failed for " + spec.user() + "@"
                        + spec.host() + " - check the user, password, or key file.", e);
            }
            if (!session.isAuthenticated()) {
                throw new IOException("Authentication failed for " + spec.user() + "@"
                        + spec.host() + " - the server rejected every offered method.");
            }
            if (onLost != null) {
                // Fires for every close, user-initiated included; the UI side
                // filters by its own "closing" flag.
                session.addSessionListener(new org.apache.sshd.common.session.SessionListener() {
                    @Override
                    public void sessionClosed(org.apache.sshd.common.session.Session s) {
                        onLost.connectionLost();
                    }
                });
            }
            return new SftpFs(client, session, SftpFs.openChannel(session),
                    spec.user() + "@" + spec.host());
        } catch (IOException | RuntimeException e) {
            client.close(true);
            throw e;
        }
    }

    private static Duration secondsOrDefault(Integer seconds, Duration fallback) {
        return seconds != null && seconds > 0 ? Duration.ofSeconds(seconds) : fallback;
    }

    /**
     * The per-site toggles that belong to the transport rather than to the
     * file model. Compression is a negotiation: restricting the proposal to
     * "none" is what "off" means here, and it is only ever restricted for a
     * site whose owner opened the options.
     */
    private static void applyDialOptions(SshClient client,
                                         dock.core.config.Site.SftpOptions opts) {
        client.setCompressionFactories(Boolean.TRUE.equals(opts.compression())
                ? List.of(BuiltinCompressions.delayedZlib, BuiltinCompressions.zlib,
                        BuiltinCompressions.none)
                : List.of(BuiltinCompressions.none));
        if (opts.transferBufferKb() != null && opts.transferBufferKb() > 0) {
            int bytes = opts.transferBufferKb() * 1024;
            PropertyResolverUtils.updateProperty(client,
                    SftpModuleProperties.READ_BUFFER_SIZE.getName(), bytes);
            PropertyResolverUtils.updateProperty(client,
                    SftpModuleProperties.WRITE_BUFFER_SIZE.getName(), bytes);
        }
        if (opts.utf8Filenames() != null) {
            PropertyResolverUtils.updateProperty(client,
                    SftpModuleProperties.NAME_DECODER_CHARSET.getName(),
                    opts.utf8Filenames() ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
        }
    }

    private static List<KeyPair> loadKey(String path, char[] passphrase) throws IOException {
        FilePasswordProvider provider = passphrase == null || passphrase.length == 0
                ? null : FilePasswordProvider.of(new String(passphrase));
        List<KeyPair> out = new ArrayList<>();
        try {
            for (KeyPair kp : SecurityUtils.getKeyPairResourceParser()
                    .loadKeyPairs(null, Path.of(path), provider)) {
                out.add(kp);
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot load key " + path
                    + " (wrong passphrase, or unsupported format).", e);
        }
        if (out.isEmpty()) {
            throw new IOException("No usable key found in " + path + ".");
        }
        return out;
    }
}
