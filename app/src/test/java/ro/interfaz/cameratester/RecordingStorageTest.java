package ro.interfaz.cameratester;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RecordingStorageTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private FileStore store;
    private RecordingStorage storage;

    @Before public void setup() throws IOException {
        store = new FileStore(temporary.newFolder("usb"));
        storage = new RecordingStorage(store);
    }

    @Test public void rolloverRetainsExactlyLatestCompleteAndCurrentWithRequestedNames() throws Exception {
        storage.prepare();
        complete("first", 0, 1, 2, 3, 4, 5);
        RecordingStorage.Segment current = storage.beginSegment(new int[]{0, 1, 2, 3, 4, 5});
        write(current, "second", 0, 1, 2, 3, 4, 5);
        for (String name : RecordingStorage.FILES) {
            assertEquals("first", read("last/" + name));
            assertEquals("second", read("current/" + name));
        }
        current.finish();
        for (String name : RecordingStorage.FILES) assertEquals("second", read("last/" + name));
        assertFalse(file("current").exists());
        assertFalse(file("previous").exists());
    }

    @Test public void selectingFewerCamerasDoesNotRetainStaleSlotsFromPreviousSegment() throws Exception {
        storage.prepare(); complete("six", 0, 1, 2, 3, 4, 5); complete("one", 2);
        assertEquals("one", read("last/360front.mp4"));
        assertFalse(file("last/adas_front.mp4").exists());
        assertEquals(2, file("last").listFiles().length);
    }

    @Test public void benchmarkRecordingAbortPreservesLastRealRecording() throws Exception {
        storage.prepare(); complete("real", 0);
        storage.prepareForBenchmark();
        RecordingStorage.Segment benchmark = storage.beginBenchmarkSegment(new int[]{0, 5});
        write(benchmark, "test", 0, 5); benchmark.abort();
        assertEquals("real", read("last/adas_front.mp4"));
        assertFalse(file("current").exists());
    }

    @Test public void completeBenchmarkPreservesBothLastAndStoppedCurrentByteForByte() throws Exception {
        storage.prepare(); complete("full retained recording", 0, 1, 2, 3, 4, 5);
        RecordingStorage.Segment stopped = storage.beginSegment(new int[]{0, 1, 2, 3, 4, 5});
        write(stopped, "stopped partial recording", 0, 1, 2, 3, 4, 5); stopped.finishCurrent();
        String lastManifest = read("last/.segment"), currentManifest = read("current/.segment");
        RecordingStorage benchmarkStorage = new RecordingStorage(store);
        benchmarkStorage.prepareForBenchmark(); benchmarkStorage.benchmark(1);
        RecordingStorage.Segment benchmark = benchmarkStorage.beginBenchmarkSegment(new int[]{0, 1, 2, 3, 4, 5});
        write(benchmark, "temporary test output", 0, 1, 2, 3, 4, 5); benchmark.abort(); benchmarkStorage.close();
        for (String name : RecordingStorage.FILES) {
            assertEquals("full retained recording", read("last/" + name));
            assertEquals("stopped partial recording", read("current/" + name));
        }
        assertEquals(lastManifest, read("last/.segment"));
        assertEquals(currentManifest, read("current/.segment"));
        assertFalse(file("benchmark-videos").exists());
        assertFalse(file(".benchmark.bin").exists());
    }

    @Test public void benchmarkRecoveryCleansOnlyItsOwnInterruptedClips() throws Exception {
        storage.prepare(); complete("last", 0);
        RecordingStorage.Segment incomplete = storage.beginSegment(new int[]{0}); write(incomplete, "unfinished current", 0);
        incomplete.descriptor(0).close();
        RecordingStorage benchmarkStorage = new RecordingStorage(store); benchmarkStorage.prepareForBenchmark();
        RecordingStorage.Segment benchmark = benchmarkStorage.beginBenchmarkSegment(new int[]{0}); write(benchmark, "test clip", 0);
        benchmark.descriptor(0).close();
        RecordingStorage recovered = new RecordingStorage(store); recovered.prepareForBenchmark();
        assertEquals("last", read("last/adas_front.mp4"));
        assertEquals("unfinished current", read("current/adas_front.mp4"));
        assertFalse(RecordingStorage.decodeManifest(read("current/.segment")).complete);
        assertFalse(file("benchmark-videos").exists());
    }

    @Test public void benchmarkCannotAccidentallyPromoteItsOutput() throws Exception {
        storage.prepare(); complete("real", 0); storage.prepareForBenchmark();
        RecordingStorage.Segment benchmark = storage.beginBenchmarkSegment(new int[]{0}); write(benchmark, "test", 0);
        expectIo(benchmark::finish); expectIo(benchmark::finishCurrent);
        benchmark.abort(); assertEquals("real", read("last/adas_front.mp4"));
    }

    @Test public void userStopFinalizesCurrentAndPreservesLastUntilNextSession() throws Exception {
        storage.prepare(); complete("full three minute segment", 0, 1);
        RecordingStorage.Segment partial = storage.beginSegment(new int[]{0, 1});
        write(partial, "short final segment", 0, 1); partial.finishCurrent(); storage.close();
        assertEquals("full three minute segment", read("last/adas_front.mp4"));
        assertEquals("short final segment", read("current/adas_front.mp4"));
        assertTrue(RecordingStorage.decodeManifest(read("current/.segment")).complete);
        RecordingStorage reopened = new RecordingStorage(store); reopened.prepare();
        assertEquals("short final segment", read("last/adas_front.mp4"));
        assertFalse(file("current").exists());
        reopened.beginSegment(new int[]{0}).abort();
    }

    @Test public void failedPromotionRollsBackAndNextPrepareRecoversCompletedCurrent() throws Exception {
        storage.prepare(); complete("old", 0);
        RecordingStorage.Segment current = storage.beginSegment(new int[]{0}); write(current, "new", 0);
        store.failCurrentRename = true;
        expectIo(current::finish);
        assertEquals("old", read("last/adas_front.mp4"));
        assertEquals("new", read("current/adas_front.mp4"));
        store.failCurrentRename = false;
        assertTrue(new RecordingStorage(store).prepare().contains("Recovered a completed segment"));
        assertEquals("new", read("last/adas_front.mp4"));
        assertFalse(file("current").exists()); assertFalse(file("previous").exists());
    }

    @Test public void interruptedRollbackRetainsBothGenerationsAndRecovers() throws Exception {
        storage.prepare(); complete("old", 1);
        RecordingStorage.Segment current = storage.beginSegment(new int[]{1}); write(current, "new", 1);
        store.failCurrentRename = true; store.failRollback = true;
        expectIo(current::finish);
        assertEquals("old", read("previous/adas_rear.mp4"));
        assertEquals("new", read("current/adas_rear.mp4"));
        store.failCurrentRename = false; store.failRollback = false;
        new RecordingStorage(store).prepare();
        assertEquals("new", read("last/adas_rear.mp4"));
        assertFalse(file("previous").exists());
    }

    @Test public void cleanupFailureAfterPromotionKeepsNewLastAndRetriesOwnedPrevious() throws Exception {
        storage.prepare(); complete("old", 0, 1);
        RecordingStorage.Segment current = storage.beginSegment(new int[]{0}); write(current, "new", 0);
        store.failPreviousDelete = true;
        expectIo(current::finish);
        assertEquals("new", read("last/adas_front.mp4"));
        assertTrue(file("previous/.segment").isFile());
        store.failPreviousDelete = false;
        new RecordingStorage(store).prepare();
        assertEquals("new", read("last/adas_front.mp4"));
        assertFalse(file("previous").exists());
    }

    @Test public void recoveryRemovesOnlyOwnedInterruptedCurrent() throws Exception {
        storage.prepare(); complete("last", 0);
        RecordingStorage.Segment interrupted = storage.beginSegment(new int[]{0}); write(interrupted, "unfinished", 0);
        interrupted.descriptor(0).close(); // Process death closes descriptors without finishing the MP4.
        assertTrue(new RecordingStorage(store).prepare().contains("unfinished"));
        assertEquals("last", read("last/adas_front.mp4"));
        assertFalse(file("current").exists());
    }

    @Test public void emptySelectedVideoNeverReplacesLastCompleteSegment() throws Exception {
        storage.prepare(); complete("last", 0);
        RecordingStorage.Segment current = storage.beginSegment(new int[]{0, 1}); write(current, "only front", 0);
        expectIo(current::finish);
        assertEquals("last", read("last/adas_front.mp4"));
        new RecordingStorage(store).prepare();
        assertFalse(file("current").exists());
    }

    @Test public void damagedFinalizedCurrentCannotReplaceLastOnRecovery() throws Exception {
        storage.prepare(); complete("last", 0);
        RecordingStorage.Segment current = storage.beginSegment(new int[]{0, 1}); write(current, "new", 0, 1);
        current.finishCurrent(); assertTrue(file("current/adas_rear.mp4").delete());
        expectIo(() -> new RecordingStorage(store).prepare());
        assertEquals("last", read("last/adas_front.mp4"));
        assertEquals("new", read("current/adas_front.mp4"));
    }

    @Test public void partialOpenFailureClosesAndRemovesNewCurrentOnly() throws Exception {
        storage.prepare(); complete("last", 0);
        store.failOpenName = "360left.mp4";
        expectIo(() -> storage.beginSegment(new int[]{0, 3}));
        assertFalse(file("current").exists());
        assertEquals("last", read("last/adas_front.mp4"));
        store.failOpenName = null;
        storage.beginSegment(new int[]{0}).abort();
    }

    @Test public void unknownFilesInCurrentPreventRecursiveDeletion() throws Exception {
        storage.prepare(); complete("last", 0);
        RecordingStorage.Segment current = storage.beginSegment(new int[]{0}); write(current, "current", 0);
        writeUtf8(file("current/my-important-file.txt"), "keep");
        expectIo(current::abort);
        assertEquals("keep", read("current/my-important-file.txt"));
        assertEquals("current", read("current/adas_front.mp4"));
        assertEquals("last", read("last/adas_front.mp4"));
        expectIo(() -> new RecordingStorage(store).prepare());
    }

    @Test public void existingUnownedRootFolderIsNeverClaimed() throws Exception {
        assertTrue(file("").mkdir());
        writeUtf8(file("holiday.txt"), "keep");
        expectIo(storage::prepare);
        assertEquals("keep", read("holiday.txt"));
        assertFalse(file(".camera-tester-owner").exists());
        expectIo(() -> storage.beginSegment(new int[]{0}));
        expectIo(() -> storage.writeReport("Must not mutate an unowned folder."));
    }

    @Test public void existingUnownedCurrentFolderIsNeverDeleted() throws Exception {
        storage.prepare(); assertTrue(file("current").mkdir());
        writeUtf8(file("current/adas_front.mp4"), "unrelated");
        expectIo(storage::prepare);
        assertEquals("unrelated", read("current/adas_front.mp4"));
    }

    @Test public void unexpectedFilesBesideOwnedFoldersRemainUntouched() throws Exception {
        storage.prepare(); writeUtf8(file("my-notes.txt"), "keep");
        complete("video", 3); storage.prepare();
        assertEquals("keep", read("my-notes.txt"));
    }

    @Test public void previouslyOwnedRootCanBeSelectedDirectlyWithoutNested6camdvr() throws Exception {
        storage.prepare();
        FileStore selectedFolder = new FileStore(file(""));
        RecordingStorage reopened = new RecordingStorage(selectedFolder);
        reopened.prepare(); reopened.beginSegment(new int[]{1}).abort();
        assertFalse(file("6camdvr").exists());
    }

    @Test public void empty6camdvrCreatedByThePickerCanBeSelectedDirectly() throws Exception {
        assertTrue(file("").mkdir());
        RecordingStorage selected = new RecordingStorage(new FileStore(file("")));
        selected.prepare();
        assertTrue(file(".camera-tester-owner").isFile());
        selected.beginSegment(new int[]{0}).abort();
        assertFalse(file("6camdvr").exists());
    }

    @Test public void reportsOverwriteOnlyOwnedReport() throws Exception {
        storage.prepare();
        Uri first = storage.writeReport("first"); Uri second = storage.writeReport("second");
        assertEquals(first, second); assertEquals("second", read("recording-report-latest.txt"));
        assertTrue(file(".report-owner").delete());
        expectIo(() -> storage.writeReport("must not replace"));
        assertEquals("second", read("recording-report-latest.txt"));
    }

    @Test public void boundedBenchmarkWritesSyncsAndDeletesTemporaryData() throws Exception {
        storage.prepare();
        RecordingStorage.Benchmark result = storage.benchmark(1);
        assertEquals(1024L * 1024, result.bytes);
        assertTrue(result.writeMs >= 0); assertTrue(result.syncMs >= 0);
        assertTrue(result.mebibytesPerSecond > 0);
        assertFalse(file(".benchmark.bin").exists()); assertFalse(file(".benchmark-owner").exists());
    }

    @Test public void failedBenchmarkCleansUpAndPreservesLast() throws Exception {
        storage.prepare(); complete("last", 0); store.availableBytes = 1024;
        expectIo(() -> storage.benchmark(1));
        assertFalse(file(".benchmark.bin").exists()); assertFalse(file(".benchmark-owner").exists());
        assertEquals("last", read("last/adas_front.mp4"));
    }

    @Test public void benchmarkNeverOverwritesAnUnownedFile() throws Exception {
        storage.prepare(); writeUtf8(file(".benchmark.bin"), "keep");
        expectIo(() -> storage.benchmark(1));
        assertEquals("keep", read(".benchmark.bin"));
    }

    @Test public void activeRecordingRejectsPrepareBenchmarkAndSecondSegment() throws Exception {
        storage.prepare(); RecordingStorage.Segment current = storage.beginSegment(new int[]{0});
        expectIo(storage::prepare); expectIo(() -> storage.benchmark(1));
        expectIo(() -> storage.beginSegment(new int[]{1}));
        current.abort();
    }

    @Test public void invalidSlotSelectionCreatesNoFiles() throws Exception {
        storage.prepare();
        for (int[] slots : new int[][]{new int[]{}, new int[]{0, 0}, new int[]{-1}, new int[]{6}})
            expectIo(() -> storage.beginSegment(slots));
        assertFalse(file("current").exists());
    }

    @Test public void internalOrUnrelatedProviderTreesAreRejectedBeforeAnyWriting() throws Exception {
        for (String tree : new String[]{"content://com.android.externalstorage.documents/tree/primary%3A",
                "content://other.provider/tree/1111-2222%3A", "content://com.android.externalstorage.documents/tree/1111-2222%3ADCIM"}) {
            RecordingStorage other = new RecordingStorage(RuntimeEnvironment.getApplication(), Uri.parse(tree));
            expectIo(other::prepare);
        }
    }

    private void complete(String text, int... slots) throws Exception {
        RecordingStorage.Segment segment = storage.beginSegment(slots); write(segment, text, slots); segment.finish();
    }
    private void write(RecordingStorage.Segment segment, String text, int... slots) throws Exception {
        for (int slot : slots) {
            FileOutputStream output = new FileOutputStream(segment.fd(slot));
            output.write(text.getBytes(StandardCharsets.UTF_8)); output.flush();
        }
    }
    private File file(String path) { return new File(new File(store.base, RecordingStorage.DIRECTORY), path); }
    private String read(String path) throws IOException { return readUtf8(file(path)); }
    private static String readUtf8(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static void writeUtf8(File file, String text) throws IOException { Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    interface IoAction { void run() throws Exception; }
    private static void expectIo(IoAction action) throws Exception {
        try { action.run(); fail("Expected an IOException"); } catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }

    private static final class FileStore implements RecordingStorage.Store {
        final File base;
        boolean failCurrentRename, failRollback, failPreviousDelete;
        String failOpenName;
        long availableBytes = 1024L * 1024 * 1024;
        FileStore(File base) { this.base = base; }
        private RecordingStorage.Entry entry(File file) { return new RecordingStorage.Entry(file.getAbsolutePath(), file.getName(), file.isDirectory()); }
        @Override public RecordingStorage.Entry validateRoot() { return entry(base); }
        @Override public List<RecordingStorage.Entry> children(RecordingStorage.Entry parent) throws IOException {
            File[] files = new File(parent.id).listFiles();
            if (files == null) throw new IOException("Cannot read folder.");
            List<RecordingStorage.Entry> result = new ArrayList<>(); for (File file : files) result.add(entry(file)); return result;
        }
        @Override public RecordingStorage.Entry create(RecordingStorage.Entry parent, String name, boolean directory) throws IOException {
            File file = new File(parent.id, name);
            if (file.exists() || !(directory ? file.mkdir() : file.createNewFile())) throw new IOException("Create failed.");
            return entry(file);
        }
        @Override public RecordingStorage.Entry rename(RecordingStorage.Entry old, String name) throws IOException {
            if (failCurrentRename && old.name.equals("current")) throw new IOException("Simulated promotion failure.");
            if (failRollback && old.name.equals("previous")) throw new IOException("Simulated rollback failure.");
            File source = new File(old.id), target = new File(source.getParentFile(), name);
            if (target.exists() || !source.renameTo(target)) throw new IOException("Rename failed.");
            return entry(target);
        }
        @Override public void delete(RecordingStorage.Entry entry) throws IOException {
            if (failPreviousDelete && new File(entry.id).toPath().toString().contains(File.separator + "previous"))
                throw new IOException("Simulated cleanup failure.");
            File file = new File(entry.id);
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children == null) throw new IOException("Cannot list folder for deletion.");
                for (File child : children) delete(entry(child));
            }
            if (!file.delete()) throw new IOException("Delete failed.");
        }
        @Override public String read(RecordingStorage.Entry entry) throws IOException { return readUtf8(new File(entry.id)); }
        @Override public void write(RecordingStorage.Entry entry, String value) throws IOException { writeUtf8(new File(entry.id), value); }
        @Override public ParcelFileDescriptor open(RecordingStorage.Entry entry) throws IOException {
            if (entry.name.equals(failOpenName)) throw new IOException("Simulated open failure.");
            return ParcelFileDescriptor.open(new File(entry.id), ParcelFileDescriptor.MODE_READ_WRITE);
        }
        @Override public long size(RecordingStorage.Entry entry) throws IOException { return Files.size(new File(entry.id).toPath()); }
        @Override public long freeBytes(ParcelFileDescriptor descriptor) { return availableBytes; }
        @Override public Uri uriOf(RecordingStorage.Entry entry) { return Uri.fromFile(new File(entry.id)); }
    }
}

