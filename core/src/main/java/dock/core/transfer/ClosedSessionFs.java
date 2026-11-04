package dock.core.transfer;

import dock.core.fs.FileEntry;
import dock.core.fs.FileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Stands in for a saved session's filesystem inside a job restored from
 * disk while that session is not open. It exists so the waiting row keeps
 * both endpoints, its paths and a readable label; it is deliberately useless
 * for work — every operation refuses, so a job can only ever stream after
 * the engine swaps this for the live connection.
 */
final class ClosedSessionFs implements FileSystem {

    private final String site;

    ClosedSessionFs(String site) {
        this.site = site;
    }

    String site() { return site; }

    @Override public String label() { return site + " (not connected)"; }
    @Override public boolean remote() { return true; }
    @Override public String separator() { return "/"; }
    @Override public String home() { return "/"; }
    @Override public List<String> roots() { return List.of("/"); }

    // Paths are carried verbatim from the queue file: a placeholder must not
    // reshape them on the way back out.
    @Override public String normalize(String path) { return path; }
    @Override public String parent(String path) { return path; }
    @Override public String child(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    /** Nothing on the far side is reachable or writable until it connects. */
    @Override public boolean immutableListing(String path) { return true; }
    @Override public boolean extractable(String path) { return false; }

    private IOException closed() {
        return new IOException("Session " + site + " is not connected.");
    }

    @Override public boolean exists(String path) throws IOException { throw closed(); }
    @Override public List<FileEntry> list(String path) throws IOException { throw closed(); }
    @Override public FileEntry stat(String path) throws IOException { throw closed(); }
    @Override public void mkdir(String path) throws IOException { throw closed(); }
    @Override public void delete(String path) throws IOException { throw closed(); }
    @Override public void rename(String from, String to) throws IOException { throw closed(); }
    @Override public InputStream read(String path) throws IOException { throw closed(); }
    @Override public OutputStream write(String path, boolean append) throws IOException {
        throw closed();
    }
    @Override public void setTimes(String path, long mtimeMillis) throws IOException {
        throw closed();
    }
    @Override public void setPerms(String path, int posix) throws IOException { throw closed(); }
    @Override public void close() { }
}
