package dock.core.transfer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dock.core.config.AppPaths;
import dock.core.fs.FileSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The transfer queue written to disk as JSON, so work the user queued
 * survives a close: %APPDATA%/Universal Media Explorer/queue.json.
 *
 * Only unfinished rows are kept — a finished transfer has nothing to say to
 * the next launch — and a row is only writable when both of its endpoints
 * can be named: the local disk, or a saved session. A quick connect to an
 * address with no saved site can't be re-dialled, so its jobs live in this
 * run's queue only.
 *
 * Saves are debounced: rapid queue changes coalesce into one write.
 *
 * A file this store cannot write or read is never swallowed here: the reason
 * goes to the problem sink the caller hands in, which is how a queue that
 * failed to persist reaches the operator rather than a console no one opens.
 */
public final class QueuePersistence {

    private static final String QUEUE_FILE = "queue.json";
    private static final long DEBOUNCE_MS = 500;
    /** The endpoint of a job that runs against this machine's disks. */
    static final String LOCAL = "local";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            // The queue is a cache, not an API: a field a newer build drops
            // must not throw the rest of it away.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * One transfer as stored. The {@code FileSystem} references of the live
     * job become session names: {@value #LOCAL}, or the saved site the shell
     * registered — and on load a name with no live connection behind it
     * restores as a waiting row.
     */
    public record JobRecord(
            @JsonProperty("id") String id,
            @JsonProperty("batchId") String batchId,
            @JsonProperty("kind") String kind,
            @JsonProperty("srcSession") String srcSession,
            @JsonProperty("srcPath") String srcPath,
            @JsonProperty("dstSession") String dstSession,
            @JsonProperty("dstPath") String dstPath,
            @JsonProperty("move") boolean move,
            @JsonProperty("state") String state,
            @JsonProperty("size") long size,
            @JsonProperty("transferred") long transferred,
            @JsonProperty("error") String error,
            @JsonProperty("resumeFrom") long resumeFrom
    ) {
        @JsonCreator
        public JobRecord {}
    }

    public record QueueRecord(
            @JsonProperty("version") int version,
            @JsonProperty("savedAt") long savedAt,
            @JsonProperty("jobs") List<JobRecord> jobs
    ) {
        @JsonCreator
        public QueueRecord {}
    }

    private final Path queueFile;
    private volatile long lastSaveRequest = 0;
    private volatile Thread saveThread = null;

    public QueuePersistence() {
        this(AppPaths.config().resolve(QUEUE_FILE));
    }

    public QueuePersistence(Path queueFile) {
        this.queueFile = queueFile;
    }

    /**
     * Saves the unfinished part of the queue. Debounced: rapid calls
     * coalesce into one write. {@code siteOf} names an endpoint; a job with
     * an endpoint it cannot name is left out of the file.
     *
     * <p>A write that fails is not fatal — the queue keeps working in memory
     * — so the reason travels to {@code problems} for whoever can show it.
     */
    public void saveAsync(List<TransferJob> jobs, Function<FileSystem, String> siteOf,
                          Consumer<String> problems) {
        lastSaveRequest = System.currentTimeMillis();
        synchronized (this) {
            if (saveThread == null || !saveThread.isAlive()) {
                saveThread = Thread.ofVirtual().name("queue-save").start(() -> {
                    try {
                        long lastRequest;
                        do {
                            Thread.sleep(DEBOUNCE_MS);
                            lastRequest = lastSaveRequest;
                        } while (System.currentTimeMillis() - lastRequest < DEBOUNCE_MS);
                        saveNow(jobs, siteOf, problems);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }
    }

    /** Writes the file now — what the debounce eventually does. */
    void saveNow(List<TransferJob> jobs, Function<FileSystem, String> siteOf,
                 Consumer<String> problems) {
        try {
            List<JobRecord> records = jobs.stream()
                    // A failure the user never answered is still unfinished
                    // work: it keeps its row, its error and its resume point.
                    .filter(job -> !settled(job.state()))
                    .map(job -> toRecord(job, siteOf))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (records.isEmpty()) {
                Files.deleteIfExists(queueFile);
                return;
            }
            QueueRecord queue = new QueueRecord(1, System.currentTimeMillis(), records);
            Files.createDirectories(queueFile.getParent());
            MAPPER.writeValue(queueFile.toFile(), queue);
        } catch (IOException e) {
            // Persistence failure is non-fatal; the queue keeps working in memory.
            problems.accept("Could not save the transfer queue: " + why(e));
        }
    }

    /**
     * Loads the persisted queue and restores its rows into the engine.
     *
     * An endpoint with a live connection behind its name comes straight
     * back; one without waits — the row shows in the queue and starts when
     * that session connects. A job interrupted mid-transfer comes back
     * failed-with-resume-point rather than as if it were never touched.
     *
     * @param engine     the engine to restore rows into
     * @param localFs    this machine's filesystem, standing in for "local"
     * @param liveOf     the open connection for a saved session, or null
     * @param problems   where a row that could not come back is reported
     * @return the number of rows restored
     */
    public int restoreInto(TransferEngine engine, FileSystem localFs,
                           Function<String, FileSystem> liveOf, Consumer<String> problems) {
        QueueRecord queue = load(problems);
        if (queue == null || queue.jobs() == null || queue.jobs().isEmpty()) return 0;
        int restored = 0;
        for (JobRecord record : queue.jobs()) {
            try {
                TransferJob job = toJob(record, localFs, liveOf);
                if (job != null && engine.addRestoredJob(job)) restored++;
            } catch (RuntimeException e) {
                problems.accept("Could not restore a queued transfer: " + why(e));
            }
        }
        return restored;
    }

    /** Loads the persisted queue; null when the file is missing or unreadable. */
    public QueueRecord load(Consumer<String> problems) {
        if (!Files.exists(queueFile)) return null;
        try {
            return MAPPER.readValue(queueFile.toFile(), QueueRecord.class);
        } catch (IOException e) {
            problems.accept("Could not read the transfer queue: " + why(e));
            return null;
        }
    }

    /** The shortest true sentence about a failure; some carry no message. */
    private static String why(Throwable problem) {
        String text = problem.getMessage();
        return text == null ? problem.toString() : text;
    }

    /** The queue file's path — diagnostics and tests read it back. */
    public Path file() {
        return queueFile;
    }

    /**
     * Rebuilds one row. A name with a live connection is used directly; a
     * session that is not open gets a placeholder and the row waits. The
     * local disk is always there, so a purely local burst resumes at once.
     */
    private static TransferJob toJob(JobRecord record, FileSystem localFs,
                                     Function<String, FileSystem> liveOf) {
        TransferJob.State stored = state(record.state());
        if (stored == null || settled(stored)) return null;
        FileSystem src = endpoint(record.srcSession(), localFs, liveOf);
        FileSystem dst = endpoint(record.dstSession(), localFs, liveOf);
        TransferJob job = new TransferJob(UUID.fromString(record.batchId()),
                TransferJob.Kind.valueOf(record.kind()),
                src, record.srcPath(), dst, record.dstPath(),
                record.move(), record.size());
        job.setTransferred(record.transferred());
        job.setResumeFrom(record.transferred());
        if (src instanceof ClosedSessionFs || dst instanceof ClosedSessionFs) {
            // The session it was moving through is not open: the row waits
            // for it rather than guessing at another route.
            job.setState(TransferJob.State.WAITING);
        } else if (stored == TransferJob.State.FAILED) {
            // A failure the user never answered stays a failure: retrying it
            // is their call, and the resume point is kept for when they do.
            job.setState(TransferJob.State.FAILED);
            job.setError(record.error());
            job.setResumeFrom(record.resumeFrom());
        } else {
            // Queued, running or paused when the app closed: it goes back to
            // the head of the queue and picks up where the bytes stopped.
            job.setState(TransferJob.State.QUEUED);
        }
        return job;
    }

    private static TransferJob.State state(String name) {
        try {
            return TransferJob.State.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;   // a state this build doesn't know is not work to start
        }
    }

    /** The settled states a stored row must never come back from. */
    private static boolean settled(TransferJob.State state) {
        return state == TransferJob.State.DONE || state == TransferJob.State.SKIPPED
                || state == TransferJob.State.CANCELLED;
    }

    private static FileSystem endpoint(String session, FileSystem localFs,
                                       Function<String, FileSystem> liveOf) {
        if (session == null || LOCAL.equals(session)) return localFs;
        FileSystem live = liveOf.apply(session);
        return live != null ? live : new ClosedSessionFs(session);
    }

    private static JobRecord toRecord(TransferJob job, Function<FileSystem, String> siteOf) {
        String src = nameOf(job.src(), siteOf);
        String dst = nameOf(job.dst(), siteOf);
        if (src == null || dst == null) return null;   // nothing to re-dial this from
        return new JobRecord(job.id().toString(), job.batchId().toString(),
                job.kind().name(), src, job.srcPath(), dst, job.dstPath(), job.move(),
                job.state().name(), job.size(), job.transferred(), job.error(),
                job.resumeFrom());
    }

    /** The name an endpoint persists under: the local disk, the saved site
     *  a live connection belongs to, the session a placeholder already
     *  waits on — or null when the endpoint is an ad-hoc connect. */
    private static String nameOf(FileSystem fs, Function<FileSystem, String> siteOf) {
        if (fs instanceof ClosedSessionFs waiting) return waiting.site();
        if (!fs.remote()) return LOCAL;
        return siteOf.apply(fs);
    }

    /** The saved session a live connection belongs to, read off the engine's registry. */
    static Function<FileSystem, String> siteOf(Map<FileSystem, String> sessionNames) {
        return name -> name == null ? null : sessionNames.get(name);
    }

    /** The open connection behind a saved session name, or null. */
    static Function<String, FileSystem> liveOf(Map<FileSystem, String> sessionNames) {
        return site -> {
            for (Map.Entry<FileSystem, String> e : sessionNames.entrySet()) {
                if (site.equals(e.getValue())) return e.getKey();
            }
            return null;
        };
    }
}
