package dock.core.transfer;

import dock.core.fs.FileSystem;
import java.util.UUID;

/** One unit of transfer work: one file copy or one directory scan. */
public final class TransferJob {

    /**
     * QUEUED/RUNNING/PAUSED are the live states, DONE/FAILED/SKIPPED/CANCELLED
     * the settled ones. WAITING is neither: a job restored from disk whose
     * remote session is not open yet keeps its row and its endpoints, and
     * joins the queue when that session connects.
     */
    public enum State { QUEUED, RUNNING, PAUSED, DONE, FAILED, SKIPPED, CANCELLED, WAITING }

    public enum Kind { FILE, DIRECTORY }

    private final UUID id = UUID.randomUUID();
    private final UUID batchId;
    private final Kind kind;
    // Endpoints are volatile because a waiting job's placeholder is swapped
    // for the live session when it connects; the paths it carries never move.
    private volatile FileSystem src;
    private final String srcPath;
    private volatile FileSystem dst;
    private final String dstPath;
    private final boolean move;

    private volatile State state = State.QUEUED;
    private volatile long size;
    private volatile long transferred;
    private volatile long speedBytesPerSec;   // smoothed
    private volatile String error;
    /** Bytes already at the target from an interrupted attempt; retry resumes from here. */
    private volatile long resumeFrom;
    /** Epoch millis when the job reached a terminal state (0 while active). */
    private volatile long finishedAt;

    TransferJob(UUID batchId, Kind kind, FileSystem src, String srcPath,
                FileSystem dst, String dstPath, boolean move, long size) {
        this.batchId = batchId;
        this.kind = kind;
        this.src = src;
        this.srcPath = srcPath;
        this.dst = dst;
        this.dstPath = dstPath;
        this.move = move;
        this.size = size;
    }

    public UUID id() { return id; }
    public UUID batchId() { return batchId; }
    public Kind kind() { return kind; }
    public FileSystem src() { return src; }
    public String srcPath() { return srcPath; }
    public FileSystem dst() { return dst; }
    public String dstPath() { return dstPath; }
    public boolean move() { return move; }

    public State state() { return state; }
    public long size() { return size; }
    public long transferred() { return transferred; }
    public long speedBytesPerSec() { return speedBytesPerSec; }
    public String error() { return error; }

    public String name() {
        String p = srcPath;
        int cut = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return cut >= 0 ? p.substring(cut + 1) : p;
    }

    /** The saved session a waiting row is held up on, or null while both of
     *  its endpoints are live connections. */
    public String waitingOn() {
        if (src instanceof ClosedSessionFs waiting) return waiting.site();
        if (dst instanceof ClosedSessionFs waiting) return waiting.site();
        return null;
    }

    // package-private mutators for the engine
    void setState(State s) { state = s; }
    /** Swaps one endpoint for the live session it belongs to: a restored job
     *  waits on a placeholder until its site connects. Returns false when
     *  neither endpoint is that placeholder. Only ever called on a job that
     *  is not streaming. */
    boolean rebind(String site, FileSystem fresh) {
        boolean moved = false;
        if (src instanceof ClosedSessionFs waiting && waiting.site().equals(site)) {
            src = fresh;
            moved = true;
        }
        if (dst instanceof ClosedSessionFs waiting && waiting.site().equals(site)) {
            dst = fresh;
            moved = true;
        }
        return moved;
    }
    /** Swaps a dead connection's instance for the reconnected one. */
    boolean rebind(FileSystem dead, FileSystem fresh) {
        boolean moved = false;
        if (src == dead) { src = fresh; moved = true; }
        if (dst == dead) { dst = fresh; moved = true; }
        return moved;
    }
    /** True while an endpoint is still a placeholder: the job can't run yet. */
    boolean holdsPlaceholder() {
        return src instanceof ClosedSessionFs || dst instanceof ClosedSessionFs;
    }
    /** Terminal transition; records when the job settled (drives the footer's linger). */
    void settle(State s) {
        state = s;
        finishedAt = System.currentTimeMillis();
    }
    /** Fixed clock for tests. */
    void setFinishedAt(long millis) { finishedAt = millis; }
    void setSize(long s) { size = s; }
    void setTransferred(long t) { transferred = t; }
    void setSpeed(long bps) { speedBytesPerSec = bps; }
    void setError(String e) { error = e; }
    void setResumeFrom(long bytes) { resumeFrom = bytes; }

    long resumeFrom() { return resumeFrom; }
    public long finishedAtMillis() { return finishedAt; }

    public boolean isActive() {
        State s = state;
        return s == State.QUEUED || s == State.RUNNING || s == State.PAUSED;
    }

    public boolean isFinished() {
        State s = state;
        return s == State.DONE || s == State.FAILED || s == State.SKIPPED
                || s == State.CANCELLED;
    }
}
