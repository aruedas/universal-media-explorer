package dock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dock.core.config.Site;
import dock.sftp.DemoServer;
import dock.sftp.SftpFs;
import dock.sftp.SshSessions;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.junit.jupiter.api.Test;

/**
 * The per-site transport options have to reach the live connection, not
 * merely survive the form. Each assertion reads the property or factory
 * list off the client that actually dialed, the same way the session would
 * resolve it while working.
 */
class SftpOptionsTest {

    /** Everything the owner set lands on the client that dials — and the
     *  compression the owner asked for is the compression that got
     *  negotiated, which is the only claim about it worth testing. */
    @Test
    void setOptionsReachTheDialingClient() throws Exception {
        try (DemoServer.Handle demo = DemoServer.start()) {
            Site.SftpOptions opts = new Site.SftpOptions(
                    true, 12, 7, 2, 128, false);
            SftpFs fs = SshSessions.connect(new SshSessions.ConnectionSpec(
                    "demo", "127.0.0.1", demo.port(), "demo".toCharArray(),
                    null, null, false, opts), AcceptAllServerKeyVerifier.INSTANCE, null);
            try {
                SshClient client = (SshClient) fs.session().getFactoryManager();
                var resolver = (org.apache.sshd.common.PropertyResolver) client;
                assertEquals(7_000L, resolver.getLong(
                        CoreModuleProperties.HEARTBEAT_INTERVAL.getName()),
                        "keepalive cadence follows the site");
                assertEquals(2L, resolver.getLong(
                        CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.getName()),
                        "unanswered-keepalive tolerance follows the site");
                assertEquals(128 * 1024, SftpModuleProperties.READ_BUFFER_SIZE.getOrNull(resolver),
                        "the read buffer is the site's");
                assertEquals(128 * 1024, SftpModuleProperties.WRITE_BUFFER_SIZE.getOrNull(resolver),
                        "the write buffer is the site's");
                assertEquals(StandardCharsets.ISO_8859_1,
                        SftpModuleProperties.NAME_DECODER_CHARSET.getOrNull(resolver),
                        "turning UTF-8 names off means the legacy wire encoding");
                assertEquals("zlib@openssh.com",
                        fs.session().getCompressionInformation(false).getName(),
                        "the session really is compressing");
            } finally {
                fs.close();
            }
        }
    }

    /** A site saved before these options existed dials exactly as before:
     *  the app's keepalive, nothing forced on the transport, and MINA's own
     *  proposal (which offers no compression unless asked). */
    @Test
    void sitesWithoutOptionsKeepThePreviousDial() throws Exception {
        try (DemoServer.Handle demo = DemoServer.start()) {
            SftpFs fs = SshSessions.connect(new SshSessions.ConnectionSpec(
                    "demo", "127.0.0.1", demo.port(), "demo".toCharArray(),
                    null, null, false, null), AcceptAllServerKeyVerifier.INSTANCE, null);
            try {
                SshClient client = (SshClient) fs.session().getFactoryManager();
                var resolver = (org.apache.sshd.common.PropertyResolver) client;
                assertEquals(SshSessions.KEEPALIVE_INTERVAL.toMillis(), resolver.getLong(
                        CoreModuleProperties.HEARTBEAT_INTERVAL.getName()),
                        "the app's keepalive is not an option the site owns");
                assertNull(SftpModuleProperties.READ_BUFFER_SIZE.getOrNull(resolver),
                        "nothing forces a buffer the site never chose");
                try (SshClient untouched = SshClient.setUpDefaultClient()) {
                    List<String> names = client.getCompressionFactories().stream()
                            .map(f -> f.getName()).toList();
                    assertEquals(untouched.getCompressionFactories().stream()
                                    .map(f -> f.getName()).toList(), names,
                            "an option-less site keeps the library's own list");
                }
                assertTrue(!fs.session().getCompressionInformation(false).isCompressionExecuted(),
                        "and it negotiates no compression");
            } finally {
                fs.close();
            }
        }
    }
}
