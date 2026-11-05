package dock.core.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import dock.core.fs.LocalFs;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The queue's life across a close: what lands in queue.json, what a restore
 * makes of it, and how a waiting row finds the session it was moving through.
 * The remote endpoints are folders in the temp dir standing in for a saved
 * site, so a restored row can be followed all the way to its bytes.
 */
class QueuePersistenceTest {

    @TempDir Path dir;

    private Named namedStore() {
        Path file = dir.resolve("queue-" + System.nanoTime() + ".json");
        return new Named(new QueuePersistence(file), file, new ArrayList<>());
    }

    /** A store, its file, and every trouble it reported along the way. */
    private record Named(QueuePersistence store, Path file, List<String> problems) {}

    /** A queue file plus the address book that wrote it. */
    private Named write(TransferJob job, Map<FileSystem, String> names) {
        Named n = namedStore();
        n.store.saveNow(List.of(job), QueuePersistence.siteOf(names), n.problems::add);
        return n;
    }

    /** The next launch: an engine reading the same file with nothing connected. */
    private TransferEngine relaunch(Named n) {
        return new TransferEngine(n.store);
    }

    // ---- what gets written ----

    @Test
    void aSettledBurstLeavesNothingOnDisk() {
        Named n = namedStore();
        TransferJob done = file(LocalFs.INSTANCE, "C:\\a.mp4", LocalFs.INSTANCE, "C:\\b.mp4");
        done.settle(TransferJob.State.DONE);

        n.store.saveNow(List.of(done), QueuePersistence.siteOf(Map.of()), n.problems::add);

        assertFalse(Files.exists(n.file), "a finished transfer has nothing to say to the next launch");
    }

    @Test
    void anUnsavedSessionIsNotWritten() {
        // A quick connect: the shell never learned a site name for it, so no
        // later launch could re-dial the route this row took.
        TransferJob job = file(LocalFs.INSTANCE, "C:\\a.mp4", new DirFs("192.0.2.7", dir), "/b.mp4");
        Named n = write(job, Map.of());

        assertFalse(Files.exists(n.file), "a row with no addressable endpoint is dropped");
        assertEquals(0, relaunch(n).restoreQueue(LocalFs.INSTANCE));
    }

    @Test
    void aWaitingRowKeepsItsSiteAndItsPaths() {
        TransferJob job = file(LocalFs.INSTANCE, "C:\\a.mp4", new ClosedSessionFs("srv-upload"), "/b.mp4");
        job.setState(TransferJob.State.WAITING);
        Named n = write(job, Map.of());

        QueuePersistence.QueueRecord loaded = n.store.load(n.problems::add);
        assertNotNull(loaded);
        assertEquals(1, loaded.jobs().size());
        assertEquals("srv-upload", loaded.jobs().get(0).dstSession());
        assertEquals("/b.mp4", loaded.jobs().get(0).dstPath());
    }

    @Test
    void aSavedSiteIsWrittenUnderItsNameAndTheLocalSideAsLocal() {
        DirFs remote = new DirFs("srv-upload", dir.resolve("srv"));
        TransferJob job = file(LocalFs.INSTANCE, "C:\\a.mp4", remote, "/b.mp4");
        Named n = write(job, Map.of(remote, "srv-upload"));

        QueuePersistence.JobRecord rec = n.store.load(n.problems::add).jobs().get(0);
        assertEquals("local", rec.srcSession());
        assertEquals("srv-upload", rec.dstSession());
    }

    // ---- what a restore makes of it ----

    @Test
    void aRowWhoseSessionIsClosedComesBackWaiting() {
        DirFs remote = new DirFs("srv-upload", dir.resolve("srv"));
        TransferJob job = file(LocalFs.INSTANCE, "C:\\a.mp4", remote, "/b.mp4");
        Named n = write(job, Map.of(remote, "srv-upload"));

        // Fresh process: nothing is connected, and nothing here dials out.
        TransferEngine e = relaunch(n);
        assertEquals(1, n.store.restoreInto(e, LocalFs.INSTANCE,
                QueuePersistence.liveOf(Map.of()), n.problems::add));

        TransferJob waiting = e.snapshot().get(0);
        assertEquals(TransferJob.State.WAITING, waiting.state());
        assertEquals("srv-upload", waiting.waitingOn());
        assertFalse(e.hasActive(), "a waiting row is not work in flight");
    }

    @Test
    void openingTheSessionRunsTheRowItWasWaitingOn() throws IOException {
        Path source = Files.writeString(dir.resolve("movie.mkv"), "container-bytes");
        Path server = Files.createDirectories(dir.resolve("srv"));
        DirFs remote = new DirFs("srv-upload", server);
        TransferJob job = file(LocalFs.INSTANCE, source.toString(), remote, "/movie.mkv");
        Named n = write(job, Map.of(remote, "srv-upload"));

        TransferEngine e = relaunch(n);
        e.restoreQueue(LocalFs.INSTANCE);
        TransferJob waiting = e.snapshot().get(0);
        assertEquals(TransferJob.State.WAITING, waiting.state());

        assertEquals(1, e.sessionOpened("srv-upload", remote));
        await("row settles", () -> !waiting.isActive());

        assertEquals(TransferJob.State.DONE, waiting.state());
        assertEquals("container-bytes", Files.readString(server.resolve("movie.mkv")));
    }

    @Test
    void aRowBetweenTwoClosedSessionsWaitsForTheLastOne() throws IOException {
        Path source = Files.createDirectories(dir.resolve("srv-index")).resolve("in.mp4");
        Files.writeString(source, "bytes");
        Path target = Files.createDirectories(dir.resolve("srv-upload"));
        DirFs index = new DirFs("srv-index", dir.resolve("srv-index"));
        DirFs upload = new DirFs("srv-upload", target);

        TransferJob job = file(index, "/in.mp4", upload, "/out.mp4");
        job.setState(TransferJob.State.WAITING);
        Named n = write(job, Map.of(index, "srv-index", upload, "srv-upload"));

        TransferEngine e = relaunch(n);
        n.store.restoreInto(e, LocalFs.INSTANCE, QueuePersistence.liveOf(Map.of()), n.problems::add);
        TransferJob waited = e.snapshot().get(0);

        assertEquals(0, e.sessionOpened("srv-upload", upload),
                "the far side is reachable but there is still nothing to read from");
        assertEquals(TransferJob.State.WAITING, waited.state());

        assertEquals(1, e.sessionOpened("srv-index", index));
        await("row settles", () -> !waited.isActive());
        assertEquals(TransferJob.State.DONE, waited.state());
        assertEquals("bytes", Files.readString(target.resolve("out.mp4")));
    }

    @Test
    void anInterruptedTransferPicksUpWhereTheBytesStopped() throws IOException {
        Path source = Files.writeString(dir.resolve("big.bin"), "payload");
        Path partial = Files.writeString(dir.resolve("half.bin"), "paylo");
        TransferJob job = file(LocalFs.INSTANCE, source.toString(),
                LocalFs.INSTANCE, partial.toString());
        job.setState(TransferJob.State.RUNNING);
        job.setTransferred(5);
        job.setResumeFrom(5);
        Named n = write(job, Map.of());

        TransferEngine e = relaunch(n);
        assertEquals(1, e.restoreQueue(LocalFs.INSTANCE));
        TransferJob back = e.snapshot().get(0);
        assertEquals(TransferJob.State.QUEUED, back.state());
        assertEquals(5, back.transferred(), "the bytes already at the target are kept");
        await("row settles", () -> !back.isActive());

        assertEquals(TransferJob.State.DONE, back.state());
        assertEquals("payload", Files.readString(partial));
    }

    @Test
    void aFailureStaysAFailureUntilTheUserRetriesIt() {
        TransferJob job = file(LocalFs.INSTANCE, "C:\\a.mp4", LocalFs.INSTANCE, "C:\\b.mp4");
        job.setState(TransferJob.State.FAILED);
        job.setError("connection reset");
        job.setResumeFrom(2048);
        Named n = write(job, Map.of());

        TransferEngine e = relaunch(n);
        n.store.restoreInto(e, LocalFs.INSTANCE, QueuePersistence.liveOf(Map.of()), n.problems::add);

        TransferJob back = e.snapshot().get(0);
        assertEquals(TransferJob.State.FAILED, back.state());
        assertEquals("connection reset", back.error());
        assertEquals(2048, back.resumeFrom());
        assertFalse(e.hasActive(), "retrying a failure is the user's call, not the queue's");
    }

    @Test
    void reconnectingHandsTheDeadConnectionsRowsToTheFreshOne() {
        TransferEngine e = new TransferEngine(namedStore().store);
        DirFs dead = new DirFs("srv-upload", dir);
        DirFs fresh = new DirFs("srv-upload", dir);
        e.nameSession(dead, "srv-upload");
        TransferJob job = file(LocalFs.INSTANCE, "C:\\a.mp4", dead, "/b.mp4");
        job.setState(TransferJob.State.FAILED);
        job.setError("was aborted mid-copy");
        assertTrue(e.addRestoredJob(job));

        e.sessionRebound(dead, fresh);

        assertEquals(fresh, job.dst(), "a retry runs through the healed connection");
        assertEquals("srv-upload", e.sessionName(fresh));
        assertNull(e.sessionName(dead), "the dead instance stops naming a site");
    }

    // ---- the controls over a waiting row ----

    @Test
    void aWaitingRowCanBeDroppedWithoutConnecting() {
        TransferEngine e = new TransferEngine(namedStore().store);
        TransferJob job = file(LocalFs.INSTANCE, "C:\\a.mp4", new ClosedSessionFs("srv-upload"), "/b.mp4");
        job.setState(TransferJob.State.WAITING);
        assertTrue(e.addRestoredJob(job));

        e.cancel(job.id());

        assertEquals(TransferJob.State.CANCELLED, job.state());
        assertFalse(e.hasActive());
    }

    @Test
    void retryRefusesToRunARowWithNoConnectionBehindIt() {
        TransferEngine e = new TransferEngine(namedStore().store);
        TransferJob job = file(LocalFs.INSTANCE, "C:\\a.mp4", new ClosedSessionFs("srv-upload"), "/b.mp4");
        job.setState(TransferJob.State.WAITING);
        assertTrue(e.addRestoredJob(job));

        e.retry(job.id());

        assertEquals(TransferJob.State.WAITING, job.state(),
                "retry restarts a transfer, not a session");
    }

    @Test
    void waitingRowsDoNotLightTheFooterBar() {
        TransferJob waiting = file(LocalFs.INSTANCE, "C:\\a.mp4",
                new ClosedSessionFs("srv-upload"), "/b.mp4");
        waiting.setState(TransferJob.State.WAITING);
        waiting.setSize(100);
        Progress p = TransferEngine.overall(List.of(waiting), System.currentTimeMillis(), 10_000);
        assertFalse(p.active());
        assertFalse(p.recent());
    }

    // ---- a file from a different build ----

    @Test
    void anUnknownStateIsSkippedAndNotFatal() throws IOException {
        Named n = namedStore();
        Files.writeString(n.file, """
                {"version":1,"savedAt":1,"jobs":[
                  {"id":"not-a-uuid","batchId":"nor-this","kind":"FILE",
                   "srcSession":"local","srcPath":"C:\\\\a.mp4",
                   "dstSession":"local","dstPath":"C:\\\\b.mp4","move":false,
                   "state":"TELEPORTED","size":1,"transferred":0,"error":null,"resumeFrom":0}]}
                """);

        TransferEngine e = relaunch(n);

        assertEquals(0, e.restoreQueue(LocalFs.INSTANCE));
        assertTrue(e.snapshot().isEmpty());
    }

    @Test
    void fieldsThisBuildDoesNotKnowDoNotVoidTheQueue() throws IOException {
        Named n = namedStore();
        String id = "6f1c2c0e-0a4b-4a5c-8b0f-1d2e3f4a5b6c";
        String batch = "6f1c2c0e-0a4b-4a5c-8b0f-1d2e3f4a5b6d";
        Files.writeString(n.file, """
                {"version":2,"savedAt":1,"checksum":"abc",
                 "jobs":[{"id":"%s","batchId":"%s","kind":"FILE",
                   "srcSession":"local","srcPath":"C:\\\\a.mp4",
                   "dstSession":"local","dstPath":"C:\\\\b.mp4","move":false,
                   "state":"QUEUED","size":1,"transferred":0,"error":null,
                   "resumeFrom":0,"finishedAt":0,"brandNewThing":{"deep":true}}]}
                """.formatted(id, batch));

        TransferEngine e = relaunch(n);

        assertEquals(1, e.restoreQueue(LocalFs.INSTANCE),
                "a queue written by a newer build still restores its rows");
    }

    // ---- when the file itself refuses ----

    /**
     * A queue that cannot persist is not allowed to go quiet: the write dies
     * on an endpoint no test can accidentally make writable — a folder where
     * the JSON belongs.
     */
    @Test
    void aQueueFileThatWillNotWriteReportsWhy() throws IOException {
        Path blocked = dir.resolve("queue-blocked.json");
        Files.createDirectory(blocked);
        QueuePersistence store = new QueuePersistence(blocked);
        List<String> problems = new ArrayList<>();

        store.saveNow(List.of(file(LocalFs.INSTANCE, "C:\\a.mp4",
                        LocalFs.INSTANCE, "C:\\b.mp4")),
                QueuePersistence.siteOf(Map.of()), problems::add);

        assertEquals(1, problems.size(), "the queue keeps working in memory, and says so");
        assertTrue(problems.get(0).startsWith("Could not save the transfer queue"),
                problems.toString());
    }

    @Test
    void aCorruptQueueFileSaysWhyItRestoredNothing() throws IOException {
        Named n = namedStore();
        Files.writeString(n.file, "{ not the queue we expect }");
        TransferEngine e = relaunch(n);

        assertEquals(0, n.store.restoreInto(e, LocalFs.INSTANCE,
                QueuePersistence.liveOf(Map.of()), n.problems::add));
        assertEquals(1, n.problems.size(), "an unreadable queue is reported, not skipped");
        assertTrue(n.problems.get(0).startsWith("Could not read the transfer queue"),
                n.problems.toString());
    }

    /** The contract the shell subscribes to: persistence trouble reaches its
     *  problem listeners, so it can become something the operator sees. */
    @Test
    void theEngineForwardsPersistenceTroubleToItsListeners() throws IOException {
        Named n = namedStore();
        Files.writeString(n.file, "not json at all");
        TransferEngine e = relaunch(n);
        List<String> heard = new ArrayList<>();
        e.addProblemListener(heard::add);

        assertEquals(0, e.restoreQueue(LocalFs.INSTANCE));
        assertEquals(1, heard.size(), "core reports; the shell decides how to show it");
        assertTrue(heard.get(0).startsWith("Could not read the transfer queue"),
                heard.toString());
    }

    // ---- helpers ----

    private static TransferJob file(FileSystem src, String srcPath,
                                    FileSystem dst, String dstPath) {
        return new TransferJob(UUID.randomUUID(), TransferJob.Kind.FILE,
                src, srcPath, dst, dstPath, false, 0);
    }

    private static void await(String what, java.util.function.BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (done.getAsBoolean()) return;
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                return;
            }
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    /**
     * A saved site's connection, standing in for SFTP: paths are server-side
     * ("/movie.mkv") and land inside a folder of the temp dir, so a restored
     * row can stream all the way through without a server in the loop.
     */
    private static final class DirFs implements FileSystem {
        private final String site;
        private final Path root;

        DirFs(String site, Path root) {
            this.site = site;
            this.root = root;
        }

        private Path p(String path) {
            if (!path.startsWith("/")) throw new IllegalArgumentException(path);
            return path.equals("/") ? root : root.resolve(path.substring(1));
        }

        @Override public String label() { return site; }
        @Override public boolean remote() { return true; }
        @Override public String separator() { return "/"; }
        @Override public String home() { return "/"; }
        @Override public List<String> roots() { return List.of("/"); }
        @Override public String normalize(String path) { return path; }
        @Override public String parent(String path) {
            int cut = path.lastIndexOf('/');
            return cut <= 0 ? "/" : path.substring(0, cut);
        }
        @Override public String child(String dirPath, String name) {
            return dirPath.endsWith("/") ? dirPath + name : dirPath + "/" + name;
        }
        @Override public boolean exists(String path) { return Files.exists(p(path)); }
        @Override public List<FileEntry> list(String path) throws IOException {
            try (var stream = Files.list(p(path))) {
                return stream.map(entry -> {
                    try {
                        return new FileEntry(root.relativize(entry).toString().replace('\\', '/'),
                                Files.isDirectory(entry), Files.size(entry),
                                Files.getLastModifiedTime(entry).toMillis(), null, false);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).toList();
            }
        }
        @Override public FileEntry stat(String path) {
            boolean directory = Files.isDirectory(p(path));
            try {
                return new FileEntry(childName(path), directory,
                        directory ? 0 : Files.size(p(path)),
                        Files.getLastModifiedTime(p(path)).toMillis(), null, false);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        private static String childName(String path) {
            int cut = path.lastIndexOf('/');
            return cut >= 0 ? path.substring(cut + 1) : path;
        }
        @Override public void mkdir(String path) throws IOException { Files.createDirectories(p(path)); }
        @Override public void delete(String path) throws IOException { Files.delete(p(path)); }
        @Override public void rename(String from, String to) throws IOException {
            Files.move(p(from), p(to));
        }
        @Override public InputStream read(String path) throws IOException {
            return Files.newInputStream(p(path));
        }
        @Override public OutputStream write(String path, boolean append) throws IOException {
            return append ? Files.newOutputStream(p(path), StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND)
                    : Files.newOutputStream(p(path), StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
        }
        @Override public void setTimes(String path, long mtimeMillis) throws IOException {
            Files.setLastModifiedTime(p(path), java.nio.file.attribute.FileTime.fromMillis(mtimeMillis));
        }
        @Override public void setPerms(String path, int posix) {
            throw new UnsupportedOperationException();
        }
        @Override public void close() { }
    }
}
