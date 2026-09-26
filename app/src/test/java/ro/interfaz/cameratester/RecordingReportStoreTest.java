package ro.interfaz.cameratester;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RecordingReportStoreTest {
    @Test public void readsReportsWrittenByPreviousAppVersions() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String old="Saved report\nADAS Front: 25 FPS\n";
        Files.write(new File(context.getFilesDir(),"last-recording-report.txt").toPath(),old.getBytes(StandardCharsets.UTF_8));
        assertEquals(old,RecordingReportStore.read(context));
    }
    @Test public void partialWriteFailurePreservesLastCompleteReportAndNextWriteRecovers() throws Exception {
        Context context=RuntimeEnvironment.getApplication();String old="Last complete six-camera report.";
        RecordingReportStore.write(context,old);
        try {
            RecordingReportStore.write(context,"Incomplete replacement must never be exported.",(output,bytes)->{
                output.write(bytes,0,8);throw new IOException("Simulated full storage during report write");
            });
            fail("Write failure must be reported");
        } catch(IOException expected){assertTrue(expected.getMessage().contains("full storage"));}
        assertEquals(old,RecordingReportStore.read(context));
        String latest="New completed segment: all six files finalized.";RecordingReportStore.write(context,latest);
        assertEquals(latest,RecordingReportStore.read(context));
    }
    @Test public void exportDuringPublicationNeverReadsAnEmptyOrPartialReport() throws Exception {
        Context context=RuntimeEnvironment.getApplication();RecordingReportStore.write(context,"Previous complete report.");
        StringBuilder text=new StringBuilder("Camera Tester\n");for(int i=0;i<1000;i++)text.append("All six cameras finished.\n");String replacement=text.toString();
        CountDownLatch halfWritten=new CountDownLatch(1),finishWrite=new CountDownLatch(1),readerStarted=new CountDownLatch(1);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Future<?> writer=executor.submit(()->{
                RecordingReportStore.write(context,replacement,(output,bytes)->{
                    int half=bytes.length/2;output.write(bytes,0,half);halfWritten.countDown();
                    try{if(!finishWrite.await(3,TimeUnit.SECONDS))throw new IOException("Test writer timed out");}
                    catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException(error);}
                    output.write(bytes,half,bytes.length-half);
                });return null;
            });
            assertTrue(halfWritten.await(3,TimeUnit.SECONDS));
            Future<String> reader=executor.submit(()->{readerStarted.countDown();return RecordingReportStore.read(context);});
            assertTrue(readerStarted.await(3,TimeUnit.SECONDS));
            try{reader.get(100,TimeUnit.MILLISECONDS);fail("Export must wait for publication to finish");}
            catch(TimeoutException expected){}
            finishWrite.countDown();writer.get(3,TimeUnit.SECONDS);assertEquals(replacement,reader.get(3,TimeUnit.SECONDS));
        } finally {finishWrite.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));}
    }
}
