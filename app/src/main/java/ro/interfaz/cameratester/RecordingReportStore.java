package ro.interfaz.cameratester;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Serializes live report publication and export; failed writes retain the complete old report. */
final class RecordingReportStore {
    private static final Object LOCK=new Object();
    interface WriteOperation {void write(FileOutputStream output,byte[] bytes) throws IOException;}
    private static AtomicFile file(Context context){return new AtomicFile(new File(context.getFilesDir(),"last-recording-report.txt"));}
    static String read(Context context) throws IOException {
        synchronized(LOCK){return new String(file(context).readFully(),StandardCharsets.UTF_8);}
    }
    static void write(Context context,String text) throws IOException {write(context,text,FileOutputStream::write);}
    // Fault-injection seam exercises rollback after a real partial write on the real AtomicFile.
    static void write(Context context,String text,WriteOperation operation) throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        synchronized(LOCK) {
            AtomicFile target=file(context);FileOutputStream output=target.startWrite();
            try {operation.write(output,bytes);target.finishWrite(output);}
            catch(IOException|RuntimeException|Error failure){target.failWrite(output);throw failure;}
        }
    }
    private RecordingReportStore(){}
}
