package ro.interfaz.cameratester;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStatVfs;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** SAF-only removable storage. All calls which touch storage belong on a worker thread. */
public final class RecordingStorage implements Closeable {
    public static final String[] FILES = {
            "adas_front.mp4", "adas_rear.mp4", "360front.mp4", "360left.mp4", "360right.mp4", "360rear.mp4"
    };
    public static final String DIRECTORY = "6camdvr";
    private static final String OWNER = ".camera-tester-owner";
    private static final String MAGIC = "ro.interfaz.cameratester/6camdvr/v1\n";
    private static final String MANIFEST = ".segment";
    private static final String BENCHMARK_OWNER = ".benchmark-owner";
    private static final String BENCHMARK_FILE = ".benchmark.bin";
    private static final String BENCHMARK_SEGMENT = "benchmark-videos";
    private static final String REPORT_OWNER = ".report-owner";
    private static final String REPORT_FILE = "recording-report-latest.txt";
    private final Store store;
    private Entry root;
    private Segment active;
    private boolean prepared;

    public RecordingStorage(Context context, Uri tree) {
        this(new SafStore(context.getApplicationContext(), tree));
    }

    RecordingStorage(Store store) { this.store = store; }

    /** Refuses internal storage, ungranted trees and unowned folder/file collisions. */
    public synchronized String prepare() throws IOException {
        return prepare(false);
    }

    /** Prepares only owned benchmark work; both saved last and current recordings stay intact. */
    public synchronized String prepareForBenchmark() throws IOException {
        return prepare(true);
    }

    private String prepare(boolean benchmarkOnly) throws IOException {
        if (active != null) throw new IOException("Stop recording before preparing storage.");
        prepared = false;
        Entry parent = store.validateRoot();
        root = DIRECTORY.equals(parent.name) ? parent : find(parent, DIRECTORY);
        if (root == null) {
            root = store.create(parent, DIRECTORY, true);
            Entry owner = store.create(root, OWNER, false);
            store.write(owner, MAGIC);
        } else {
            if (!root.directory) throw new IOException("6camdvr exists but is not a folder.");
            Entry owner = find(root, OWNER);
            if (owner == null && store.children(root).isEmpty()) {
                owner = store.create(root, OWNER, false);
                store.write(owner, MAGIC);
            }
            if (owner == null || !MAGIC.equals(store.read(owner)))
                throw new IOException("The existing 6camdvr folder is not owned by Camera Tester. Rename it before recording.");
        }
        List<String> recovered = new ArrayList<>();
        recoverBenchmark(recovered);
        Entry interruptedBenchmark = find(root, BENCHMARK_SEGMENT);
        if (interruptedBenchmark != null) {
            deleteSegment(interruptedBenchmark);
            recovered.add("Removed interrupted benchmark videos; saved recordings were preserved.");
        }
        Entry last = find(root, "last"), current = find(root, "current"), previous = find(root, "previous");
        if (last != null) validateSegment(last);
        if (current != null) validateSegment(current);
        if (previous != null) validateSegment(previous);
        if (previous != null) {
            if (last == null) {
                if (current != null && manifest(current).complete) {
                    try { validateCompleteSegment(current); last = store.rename(current, "last"); current = null; }
                    catch (IOException promotionFailure) {
                        store.rename(previous, "last");
                        throw new IOException("Recovered the previous complete segment; promotion must be retried.", promotionFailure);
                    }
                } else {
                    last = store.rename(previous, "last"); previous = null;
                }
            }
            if (previous != null) deleteSegment(previous);
            recovered.add("Recovered an interrupted segment rotation.");
        }
        if (current != null && !benchmarkOnly) {
            if (manifest(current).complete) {
                promote(current);
                recovered.add("Recovered a completed segment.");
            } else {
                deleteSegment(current);
                recovered.add("Removed an interrupted, unfinished segment; the last complete segment was preserved.");
            }
        }
        prepared = true;
        String ready = benchmarkOnly ? "Benchmark storage ready; existing last and current recordings are preserved."
                : "Storage ready: 6camdvr/current and 6camdvr/last.";
        return recovered.isEmpty() ? ready : String.join(" ", recovered) + " " + ready;
    }

    public synchronized Segment beginSegment(int[] slots) throws IOException {
        return beginSegment(slots, false);
    }

    /** Temporary test clips use a distinct folder so even a stopped current MP4 remains intact. */
    public synchronized Segment beginBenchmarkSegment(int[] slots) throws IOException {
        return beginSegment(slots, true);
    }

    private Segment beginSegment(int[] slots, boolean benchmarkOnly) throws IOException {
        checkPrepared();
        if (active != null) throw new IOException("A recording segment is already open.");
        validateSlots(slots);
        String folderName = benchmarkOnly ? BENCHMARK_SEGMENT : "current";
        if (find(root, folderName) != null || find(root, "previous") != null)
            throw new IOException("Storage recovery is required before starting another segment.");
        Entry folder = store.create(root, folderName, true);
        Entry metadata = null;
        Segment segment = new Segment(folder, slots.clone(), benchmarkOnly);
        try {
            metadata = store.create(folder, MANIFEST, false);
            store.write(metadata, encodeManifest(false, slots));
            for (int slot : slots) {
                Entry video = store.create(folder, FILES[slot], false);
                segment.videos[slot] = video;
                segment.descriptors[slot] = store.open(video);
            }
            active = segment;
            return segment;
        } catch (IOException | RuntimeException failure) {
            segment.closeDescriptors(false);
            try {
                if (metadata != null) deleteSegment(folder);
                else if (store.children(folder).isEmpty()) store.delete(folder);
            } catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    /** A bounded sequential write + explicit fsync test, using only owned temporary files. */
    public synchronized Benchmark benchmark(int mebibytes) throws IOException {
        checkPrepared();
        if (active != null) throw new IOException("Stop recording before running the storage benchmark.");
        if (mebibytes < 1 || mebibytes > 256) throw new IOException("Benchmark size must be between 1 and 256 MiB.");
        recoverBenchmark(new ArrayList<>());
        Entry owner = null, data = null;
        IOException failure = null;
        long bytes = mebibytes * 1024L * 1024L, writeNanos = 0, syncNanos = 0, free = -1;
        long started = System.nanoTime();
        try {
            owner = store.create(root, BENCHMARK_OWNER, false);
            store.write(owner, MAGIC);
            data = store.create(root, BENCHMARK_FILE, false);
            try (ParcelFileDescriptor descriptor = store.open(data);
                 ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                free = store.freeBytes(descriptor);
                if (free >= 0 && free < bytes + 16L * 1024 * 1024)
                    throw new IOException("Not enough free space for the selected storage benchmark.");
                byte[] buffer = new byte[1024 * 1024];
                // Nonzero, varying bytes avoid measuring zero-block optimizations.
                for (int i = 0; i < buffer.length; i++) buffer[i] = (byte) (i * 31 + (i >>> 8));
                long startWrite = System.nanoTime();
                for (int i = 0; i < mebibytes; i++) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Storage benchmark cancelled.");
                    output.write(buffer);
                }
                output.flush();
                writeNanos = System.nanoTime() - startWrite;
                long startSync = System.nanoTime();
                descriptor.getFileDescriptor().sync();
                syncNanos = System.nanoTime() - startSync;
            }
        } catch (IOException e) { failure = e; }
        finally {
            try {
                if (data != null) store.delete(data);
                if (owner != null) store.delete(owner);
            } catch (IOException cleanupFailure) {
                if (failure == null) failure = cleanupFailure; else failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure != null) throw failure;
        return new Benchmark(bytes, writeNanos, syncNanos, System.nanoTime() - started, free);
    }

    public synchronized long freeBytes() throws IOException {
        checkPrepared();
        Entry owner = find(root, OWNER);
        try (ParcelFileDescriptor descriptor = store.open(owner)) { return store.freeBytes(descriptor); }
    }

    /** Saves a small diagnostic report without overwriting an unrelated existing file. */
    public synchronized Uri writeReport(String text) throws IOException {
        checkPrepared();
        if (text == null || text.length() > 2 * 1024 * 1024) throw new IOException("The recording report is too large.");
        Entry owner = find(root, REPORT_OWNER), report = find(root, REPORT_FILE);
        if (owner == null) {
            if (report != null) throw new IOException("An unowned recording report already exists; it was not changed.");
            owner = store.create(root, REPORT_OWNER, false);
            store.write(owner, MAGIC);
        } else if (!MAGIC.equals(store.read(owner))) throw new IOException("The existing recording report ownership marker is invalid.");
        if (report == null) report = store.create(root, REPORT_FILE, false);
        if (report.directory) throw new IOException("The recording report path is a folder.");
        store.write(report, text);
        return store.uriOf(report);
    }

    @Override public synchronized void close() throws IOException { if (active != null) active.abort(); }

    public final class Segment {
        private final Entry folder;
        private final int[] slots;
        private final Entry[] videos = new Entry[FILES.length];
        private final ParcelFileDescriptor[] descriptors = new ParcelFileDescriptor[FILES.length];
        private final boolean benchmark;
        private boolean closed;

        private Segment(Entry folder, int[] slots, boolean benchmark) { this.folder = folder; this.slots = slots; this.benchmark = benchmark; }

        public FileDescriptor fd(int slot) throws IOException { return descriptor(slot).getFileDescriptor(); }

        /** Borrowed descriptor: the segment closes it after the caller releases every muxer. */
        public ParcelFileDescriptor descriptor(int slot) throws IOException {
            synchronized (RecordingStorage.this) {
                if (closed || slot < 0 || slot >= FILES.length || descriptors[slot] == null)
                    throw new IOException("The requested camera has no open recording file.");
                return descriptors[slot];
            }
        }

        public long sizeBytes(int slot) throws IOException {
            synchronized (RecordingStorage.this) {
                if (slot < 0 || slot >= FILES.length || videos[slot] == null) return 0;
                return store.size(videos[slot]);
            }
        }

        /** Call only after every selected muxer has successfully stopped and released. */
        public void finish() throws IOException {
            finalizeSegment(true);
        }

        /** Finalizes a user-stopped partial segment in current, preserving the previous full last. */
        public void finishCurrent() throws IOException {
            finalizeSegment(false);
        }

        private void finalizeSegment(boolean promoteToLast) throws IOException {
            synchronized (RecordingStorage.this) {
                if (closed) throw new IOException("This recording segment is already closed.");
                if (benchmark) throw new IOException("Benchmark video files must be discarded with abort().");
                IOException closeFailure = closeDescriptors(true);
                closed = true; active = null;
                if (closeFailure != null) throw closeFailure;
                for (int slot : slots)
                    if (store.size(videos[slot]) <= 0) throw new IOException("A recording file is empty; the last complete segment was preserved.");
                store.write(find(folder, MANIFEST), encodeManifest(true, slots));
                if (promoteToLast) promote(folder);
            }
        }

        /** Discards only this unfinished segment; the last complete segment is untouched. */
        public void abort() throws IOException {
            synchronized (RecordingStorage.this) {
                if (closed) return;
                IOException failure = closeDescriptors(false);
                closed = true; active = null;
                try { deleteSegment(folder); }
                catch (IOException cleanupFailure) {
                    if (failure == null) failure = cleanupFailure; else failure.addSuppressed(cleanupFailure);
                }
                if (failure != null) throw failure;
            }
        }

        private IOException closeDescriptors(boolean sync) {
            IOException failure = null;
            for (int slot : slots) {
                ParcelFileDescriptor descriptor = descriptors[slot];
                if (descriptor == null) continue;
                try { if (sync) descriptor.getFileDescriptor().sync(); }
                catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
                try { descriptor.close(); }
                catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
                descriptors[slot] = null;
            }
            return failure;
        }
    }

    public static final class Benchmark {
        public final long bytes, freeBytes;
        public final double writeMs, syncMs, totalMs, mebibytesPerSecond;
        Benchmark(long bytes, long writeNanos, long syncNanos, long totalNanos, long freeBytes) {
            this.bytes = bytes; this.freeBytes = freeBytes;
            writeMs = writeNanos / 1_000_000.0; syncMs = syncNanos / 1_000_000.0;
            totalMs = totalNanos / 1_000_000.0;
            mebibytesPerSecond = bytes / 1048576.0 / Math.max(0.000001, (writeNanos + syncNanos) / 1_000_000_000.0);
        }
        @Override public String toString() {
            return String.format(Locale.US, "USB sequential write: %.1f MiB/s; write %.0f ms; fsync %.0f ms; total %.0f ms; free %s.",
                    mebibytesPerSecond, writeMs, syncMs, totalMs,
                    freeBytes < 0 ? "unknown" : String.format(Locale.US, "%.1f MiB", freeBytes / 1048576.0));
        }
    }

    private void checkPrepared() throws IOException {
        if (!prepared || root == null) throw new IOException("Select and prepare removable USB storage first.");
    }

    private Entry find(Entry folder, String name) throws IOException {
        Entry found = null;
        for (Entry entry : store.children(folder)) if (name.equals(entry.name)) {
            if (found != null) throw new IOException("Duplicate storage entry: " + name);
            found = entry;
        }
        return found;
    }

    private void promote(Entry current) throws IOException {
        validateCompleteSegment(current);
        Entry last = find(root, "last"), previous = find(root, "previous");
        if (previous != null) throw new IOException("An interrupted rotation must be recovered first.");
        if (last != null) {
            validateSegment(last);
            previous = store.rename(last, "previous");
        }
        try { store.rename(current, "last"); }
        catch (IOException failure) {
            if (previous != null) {
                try { store.rename(previous, "last"); }
                catch (IOException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            }
            throw new IOException("Could not promote the new segment. Existing video files were preserved.", failure);
        }
        if (previous != null) deleteSegment(previous);
    }

    private Manifest manifest(Entry folder) throws IOException {
        Entry entry = find(folder, MANIFEST);
        if (!folder.directory || entry == null) throw new IOException("Unowned recording folder: " + folder.name);
        return decodeManifest(store.read(entry));
    }

    private Manifest validateSegment(Entry folder) throws IOException {
        Manifest value = manifest(folder);
        Set<String> expected = new HashSet<>(); expected.add(MANIFEST);
        for (int slot : value.slots) expected.add(FILES[slot]);
        for (Entry child : store.children(folder))
            if (child.directory || !expected.remove(child.name))
                throw new IOException("Unknown file in recording folder; nothing was deleted: " + folder.name + "/" + child.name);
        return value;
    }

    private void validateCompleteSegment(Entry folder) throws IOException {
        Manifest value = validateSegment(folder);
        if (!value.complete) throw new IOException("Cannot retain an unfinished segment as complete.");
        for (int slot : value.slots) {
            Entry file = find(folder, FILES[slot]);
            if (file == null || store.size(file) <= 0)
                throw new IOException("A completed segment has a missing or empty camera file; the previous recording was preserved.");
        }
    }

    private void deleteSegment(Entry folder) throws IOException {
        validateSegment(folder);
        // Delete the manifest last so interrupted cleanup remains recoverable.
        for (Entry child : store.children(folder)) if (!MANIFEST.equals(child.name)) store.delete(child);
        // Leave ownership metadata in place until the provider removes its containing folder.
        // A failed directory removal can then be recovered on the next prepare().
        store.delete(folder);
    }

    private void recoverBenchmark(List<String> messages) throws IOException {
        Entry owner = find(root, BENCHMARK_OWNER), data = find(root, BENCHMARK_FILE);
        if (owner == null) {
            if (data != null) throw new IOException("An unowned benchmark file already exists; it was not changed.");
            return;
        }
        if (!MAGIC.equals(store.read(owner)) || (data != null && data.directory))
            throw new IOException("Unrecognized benchmark files were not changed.");
        if (data != null) store.delete(data);
        store.delete(owner);
        messages.add("Removed an interrupted storage benchmark file.");
    }

    static void validateSlots(int[] slots) throws IOException {
        if (slots == null || slots.length == 0 || slots.length > FILES.length)
            throw new IOException("Select between one and six cameras.");
        boolean[] seen = new boolean[FILES.length];
        for (int slot : slots) {
            if (slot < 0 || slot >= FILES.length || seen[slot]) throw new IOException("Camera selection contains an invalid or duplicate slot.");
            seen[slot] = true;
        }
    }

    static String encodeManifest(boolean complete, int[] slots) {
        StringBuilder text = new StringBuilder(MAGIC).append(complete ? "complete\n" : "writing\n");
        for (int i = 0; i < slots.length; i++) { if (i != 0) text.append(','); text.append(slots[i]); }
        return text.append('\n').toString();
    }

    static Manifest decodeManifest(String text) throws IOException {
        if (text == null || !text.startsWith(MAGIC)) throw new IOException("Unrecognized recording manifest; files were preserved.");
        String[] lines = text.substring(MAGIC.length()).split("\n", -1);
        if (lines.length != 3 || !lines[2].isEmpty() || !("complete".equals(lines[0]) || "writing".equals(lines[0])))
            throw new IOException("Invalid recording manifest; files were preserved.");
        String[] values = lines[1].split(",", -1);
        int[] slots = new int[values.length];
        try { for (int i = 0; i < values.length; i++) slots[i] = Integer.parseInt(values[i]); }
        catch (NumberFormatException e) { throw new IOException("Invalid camera slots in recording manifest.", e); }
        validateSlots(slots);
        return new Manifest("complete".equals(lines[0]), slots);
    }

    static final class Manifest {
        final boolean complete; final int[] slots;
        Manifest(boolean complete, int[] slots) { this.complete = complete; this.slots = slots; }
    }

    static final class Entry {
        final String id, name; final boolean directory;
        Entry(String id, String name, boolean directory) { this.id = id; this.name = name; this.directory = directory; }
    }

    interface Store {
        Entry validateRoot() throws IOException;
        List<Entry> children(Entry parent) throws IOException;
        Entry create(Entry parent, String name, boolean directory) throws IOException;
        Entry rename(Entry entry, String name) throws IOException;
        void delete(Entry entry) throws IOException;
        String read(Entry entry) throws IOException;
        void write(Entry entry, String value) throws IOException;
        ParcelFileDescriptor open(Entry entry) throws IOException;
        long size(Entry entry) throws IOException;
        long freeBytes(ParcelFileDescriptor descriptor);
        Uri uriOf(Entry entry);
    }

    private static final class SafStore implements Store {
        private final Context context;
        private final ContentResolver resolver;
        private final Uri tree;
        SafStore(Context context, Uri tree) { this.context = context; resolver = context.getContentResolver(); this.tree = tree; }

        @Override public Entry validateRoot() throws IOException {
            if (tree == null || !"com.android.externalstorage.documents".equals(tree.getAuthority()) || !DocumentsContract.isTreeUri(tree))
                throw new IOException("Select a removable USB drive using the Android storage picker.");
            String documentId = DocumentsContract.getTreeDocumentId(tree);
            String[] parts = documentId.split(":", 2);
            if (parts.length != 2 || !(parts[1].isEmpty() || DIRECTORY.equals(parts[1])) || "primary".equalsIgnoreCase(parts[0]))
                throw new IOException("Select the removable USB drive root or its 6camdvr folder, not internal storage.");
            StorageManager manager = context.getSystemService(StorageManager.class);
            boolean mounted = false;
            if (manager != null) for (StorageVolume volume : manager.getStorageVolumes())
                if (volume.isRemovable() && volume.getUuid() != null && volume.getUuid().equalsIgnoreCase(parts[0])
                        && Environment.MEDIA_MOUNTED.equals(volume.getState())) mounted = true;
            if (!mounted) throw new IOException("The selected removable drive is not mounted for writing.");
            boolean granted = false;
            for (UriPermission permission : resolver.getPersistedUriPermissions())
                if (tree.equals(permission.getUri()) && permission.isReadPermission() && permission.isWritePermission()) granted = true;
            if (!granted) throw new IOException("Persistent read and write access is missing. Select the USB drive again.");
            return new Entry(documentId, parts[1].isEmpty() ? parts[0] : DIRECTORY, true);
        }

        private Uri uri(Entry entry) { return DocumentsContract.buildDocumentUriUsingTree(tree, entry.id); }
        @Override public Uri uriOf(Entry entry) { return uri(entry); }

        @Override public List<Entry> children(Entry parent) throws IOException {
            List<Entry> result = new ArrayList<>();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent.id);
            try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor == null) throw new IOException("Cannot list files on the selected USB drive.");
                while (cursor.moveToNext()) result.add(new Entry(cursor.getString(0), cursor.getString(1),
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))));
            } catch (RuntimeException e) { throw new IOException("Cannot read the selected USB drive.", e); }
            return result;
        }

        @Override public Entry create(Entry parent, String name, boolean directory) throws IOException {
            for (Entry entry : children(parent)) if (name.equals(entry.name)) throw new IOException("Storage entry already exists: " + name);
            Uri created = DocumentsContract.createDocument(resolver, uri(parent),
                    directory ? DocumentsContract.Document.MIME_TYPE_DIR : (name.endsWith(".mp4") ? "video/mp4" : "application/octet-stream"), name);
            if (created == null) throw new IOException("Cannot create recording entry: " + name);
            String id = DocumentsContract.getDocumentId(created);
            for (Entry entry : children(parent)) if (entry.id.equals(id)) {
                if (!name.equals(entry.name)) throw new IOException("The USB provider changed the requested filename: " + name);
                return entry;
            }
            throw new IOException("The created recording entry could not be found: " + name);
        }

        @Override public Entry rename(Entry entry, String name) throws IOException {
            Uri renamed = DocumentsContract.renameDocument(resolver, uri(entry), name);
            if (renamed == null) throw new IOException("The USB provider could not rename recording folder: " + entry.name);
            return new Entry(DocumentsContract.getDocumentId(renamed), name, entry.directory);
        }

        @Override public void delete(Entry entry) throws IOException {
            if (!DocumentsContract.deleteDocument(resolver, uri(entry))) throw new IOException("Could not remove owned recording entry: " + entry.name);
        }

        @Override public String read(Entry entry) throws IOException {
            if (entry.directory) throw new IOException("Expected an ownership file, found a folder.");
            try (java.io.InputStream input = resolver.openInputStream(uri(entry))) {
                if (input == null) throw new IOException("Cannot read recording metadata.");
                byte[] buffer = new byte[4097]; int used = 0, count;
                while (used < buffer.length && (count = input.read(buffer, used, buffer.length - used)) != -1) used += count;
                if (used > 4096) throw new IOException("Recording metadata exceeds its allowed size.");
                return new String(buffer, 0, used, StandardCharsets.UTF_8);
            }
        }

        @Override public void write(Entry entry, String value) throws IOException {
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri(entry), "rwt");
            if (descriptor == null) throw new IOException("Cannot write recording metadata.");
            try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                output.write(value.getBytes(StandardCharsets.UTF_8)); output.flush(); descriptor.getFileDescriptor().sync();
            }
        }

        @Override public ParcelFileDescriptor open(Entry entry) throws IOException {
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri(entry), "rw");
            if (descriptor == null) throw new IOException("Cannot open recording file for read and write.");
            try { Os.lseek(descriptor.getFileDescriptor(), 0, OsConstants.SEEK_CUR); }
            catch (android.system.ErrnoException e) {
                descriptor.close(); throw new IOException("The selected USB provider does not support seekable MP4 recording files.", e);
            }
            return descriptor;
        }

        @Override public long size(Entry entry) throws IOException {
            try (Cursor cursor = resolver.query(uri(entry), new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
                throw new IOException("Cannot verify the finalized recording size.");
            }
        }

        @Override public long freeBytes(ParcelFileDescriptor descriptor) {
            try {
                StructStatVfs stat = Os.fstatvfs(descriptor.getFileDescriptor());
                return Math.multiplyExact(stat.f_bavail, stat.f_frsize);
            } catch (Exception e) { return -1; }
        }
    }
}
