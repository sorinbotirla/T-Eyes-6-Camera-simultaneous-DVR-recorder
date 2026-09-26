package ro.interfaz.cameratester;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Streams installed code and readable service files, never private application data. */
final class ExtractionBundle {
    interface Progress { void update(String message); }
    static final class Result {
        final long packages,files,bytes,partialFiles,unavailableFiles,unavailableDirectories,specialFiles,diagnosticFailures;
        Result(Bundle b,long packages) { this.packages=packages;files=b.files;bytes=b.bytes;partialFiles=b.partialFiles;unavailableFiles=b.unavailableFiles;unavailableDirectories=b.unavailableDirectories;specialFiles=b.specialFiles;diagnosticFailures=b.diagnosticFailures; }
        String summary() { return String.format(Locale.US,"Export complete: %d installed packages inventoried; %d files copied (%.1f MiB).\nPartial files: %d. Unavailable files: %d. Unavailable directories: %d. Non-regular files skipped: %d. Failed or timed-out diagnostics: %d.\nSee manifest.tsv and collection-log.txt inside the ZIP. Android-protected files and private app data are not included.",packages,files,bytes/1048576.0,partialFiles,unavailableFiles,unavailableDirectories,specialFiles,diagnosticFailures); }
    }
    static Result write(Context context,OutputStream destination,Progress progress)throws Exception {
        checkCancelled();
        try(Bundle zip=new Bundle(destination,progress)) {
            PackageManager manager=context.getPackageManager();
            int flags=PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_PROVIDERS|PackageManager.GET_RECEIVERS|PackageManager.GET_PERMISSIONS|PackageManager.GET_SHARED_LIBRARY_FILES;
            List<PackageInfo> packages=installedPackages(manager.getInstalledPackages(flags));
            StringBuilder inventory=new StringBuilder("All installed packages visible to Camera Tester in the current Android user/profile.\nNo package keyword filter or package/file/byte limit is applied.\nAndroid ").append(Build.VERSION.RELEASE).append('\n').append(Build.FINGERPRINT).append('\n');
            Set<String> visitedDirectories=new HashSet<>();
            for(PackageInfo info:packages) {
                checkCancelled();String name=info.packageName;
                progress.update("Exporting package "+name+"\n"+zip.progressText());
                inventory.append("\nPACKAGE ").append(name).append(" version=").append(info.versionName).append(" versionCode=").append(Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode).append("\nPermissions: ").append(Arrays.toString(info.requestedPermissions)).append('\n');
                components(inventory,info.services);components(inventory,info.activities);components(inventory,info.providers);components(inventory,info.receivers);
                ApplicationInfo app=info.applicationInfo;
                if(app==null){zip.note("PACKAGE METADATA UNAVAILABLE "+name+": no application code paths were reported.");continue;}
                inventory.append("sourceDir=").append(app.sourceDir).append("\nsplitSourceDirs=").append(Arrays.toString(app.splitSourceDirs)).append("\nnativeLibraryDir=").append(app.nativeLibraryDir).append("\nsharedLibraryFiles=").append(Arrays.toString(app.sharedLibraryFiles)).append('\n');
                copyPackageCode(zip,info);
                if(app.nativeLibraryDir!=null)tree(zip,new File(app.nativeLibraryDir),"packages/"+name+"/native",visitedDirectories);
                if(app.sharedLibraryFiles!=null)for(String path:app.sharedLibraryFiles)if(path!=null)zip.publicFile(new File(path));
            }
            // Service implementations and dependencies need not contain vendor or camera keywords.
            for(String partition:new String[]{"/system","/system_ext","/vendor","/product","/odm","/oem","/spd","/spdproject"})
                for(String suffix:new String[]{"/framework","/lib","/lib64","/bin","/etc/init"}) {
                    File folder=new File(partition+suffix);tree(zip,folder,"system"+folder.getAbsolutePath(),visitedDirectories);
                }
            tree(zip,new File("/apex"),"system/apex",visitedDirectories);
            progress.update("Exporting complete service and process inventories…\n"+zip.progressText());
            zip.text("diagnostics/packages-and-components.txt",inventory.toString());
            zip.text("diagnostics/camera-config.json",ExportData.config(context));
            zip.text("diagnostics/camera-reports.txt",ExportData.report(context,ExportData.ALL_REPORTS));
            for(String path:new String[]{"/proc/net/unix","/proc/net/tcp","/proc/net/tcp6"})zip.copy(new File(path),"diagnostics/"+new File(path).getName()+".txt");
            command(context,zip,"diagnostics/services.txt","/system/bin/service","list");
            command(context,zip,"diagnostics/hal.txt","/system/bin/lshal");
            command(context,zip,"diagnostics/dumpsys-service-list.txt","/system/bin/dumpsys","-l");
            command(context,zip,"diagnostics/processes.txt","/system/bin/ps","-A");
            command(context,zip,"diagnostics/camera-service.txt","/system/bin/dumpsys","media.camera");
            command(context,zip,"diagnostics/spd-service.txt","/system/bin/dumpsys","spd");
            command(context,zip,"diagnostics/spd-hal.txt","/system/bin/dumpsys","spd.Hal");
            StringBuilder nodes=new StringBuilder();File[] videoNodes=new File("/sys/class/video4linux").listFiles();
            if(videoNodes==null)nodes.append("Video node directory is unavailable to this Android app.\n");
            else for(File node:videoNodes){nodes.append(node).append('\n');zip.copy(new File(node,"name"),"diagnostics/video-nodes/"+node.getName()+".txt");}
            zip.text("diagnostics/video-nodes.txt",nodes.toString());
            zip.text("README.txt","All installed application/system APKs and split APKs exposed by PackageManager in the current Android user/profile are attempted.\nDeclared native/shared libraries, readable framework/native/binary/init files in known system/vendor partitions, APEX contents, and service/process inventories are also attempted.\nNo package keyword, file-count, individual-file-size, total-size or diagnostic-output-size cap is applied. ZIP data is streamed directly to the selected destination.\nDiagnostic commands have an explicit 10-second execution deadline; their full captured output and exit/timeout status are retained.\nAndroid permissions and SELinux still apply. No root, running-process memory, private app data, hidden user profiles or unavailable protected binaries are included.\nServices are represented by declared components, runtime inventories and readable code; this is not a complete image of private service state.\nmanifest.tsv contains entry status, SHA-256, byte count and source. PARTIAL means a source read failed after an entry began. collection-log.txt records unavailable paths and diagnostic failures.\nAn unavailable directory count does not imply a known number of missing files inside it. Destination write failures or cancellation leave an incomplete ZIP, not a successful export.\n");
            Result result=new Result(zip,packages.size());zip.text("export-summary.txt",result.summary());zip.finishIndex();
            progress.update("Finishing ZIP directory…\n"+zip.progressText());return result;
        }
    }
    static List<PackageInfo> installedPackages(List<PackageInfo> installed) {
        Map<String,PackageInfo> sorted=new TreeMap<>();for(PackageInfo info:installed)if(info!=null&&info.packageName!=null)sorted.put(info.packageName,info);return new ArrayList<>(sorted.values());
    }
    static void copyPackageCode(Bundle zip,PackageInfo info)throws Exception {
        if(info.applicationInfo==null)return;String prefix="packages/"+info.packageName+"/",base=info.applicationInfo.sourceDir;
        if(base==null)zip.unavailableFile("Base APK path not declared for "+info.packageName);else zip.copy(new File(base),prefix+"base.apk");
        String[] splits=info.applicationInfo.splitSourceDirs;
        if(splits!=null)for(int i=0;i<splits.length;i++)if(splits[i]==null)zip.unavailableFile("Split APK path not declared for "+info.packageName+" index "+i);else zip.copy(new File(splits[i]),prefix+"split-"+i+".apk");
    }
    private static void tree(Bundle zip,File directory,String target,Set<String> visited)throws Exception {
        checkCancelled();String canonical;
        try{canonical=directory.getCanonicalPath();}catch(IOException|SecurityException e){zip.unavailableDirectory(directory+": "+e);return;}
        if(!visited.add(canonical)){zip.note("DIRECTORY ALIAS ALREADY VISITED "+directory+" -> "+canonical);return;}
        File[] files;try{files=directory.listFiles();}catch(SecurityException e){zip.unavailableDirectory(directory+": "+e);return;}
        if(files==null){zip.unavailableDirectory(directory.toString());return;}
        Arrays.sort(files,Comparator.comparing(File::getName));
        for(File file:files){checkCancelled();if(file.isDirectory())tree(zip,file,target+"/"+file.getName(),visited);else zip.copy(file,target+"/"+file.getName());}
    }
    private static void components(StringBuilder text,ComponentInfo[] list) {
        if(list==null)return;for(ComponentInfo c:list){text.append(c.getClass().getSimpleName()).append(' ').append(c.name).append(" process=").append(c.processName).append(" exported=").append(c.exported).append(" enabled=").append(c.enabled);if(c instanceof ServiceInfo)text.append(" permission=").append(((ServiceInfo)c).permission);if(c instanceof ProviderInfo)text.append(" authority=").append(((ProviderInfo)c).authority);text.append('\n');}
    }
    private static void command(Context context,Bundle zip,String entry,String...args)throws Exception {
        File temporary=null;Process process=null;
        try {
            checkCancelled();temporary=File.createTempFile("export-command-",".txt",context.getCacheDir());
            try{process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(temporary).start();}
            catch(IOException|SecurityException e){zip.diagnosticFailures++;zip.text(entry,"Command unavailable: "+String.join(" ",args)+"\n"+e+"\n");return;}
            boolean done=waitForCommand(process);
            if(!done){process.destroyForcibly();waitForTermination(process);}
            String status=done?"exit="+process.exitValue():"TIMEOUT after 10 seconds; partial command output retained";
            if(!done||process.exitValue()!=0)zip.diagnosticFailures++;
            zip.text(entry+".status.txt",String.join(" ",args)+"\n"+status+"\n");zip.copy(temporary,entry);
        }finally{if(process!=null)process.destroy();if(temporary!=null&&!temporary.delete())zip.note("Temporary diagnostic cleanup failed: "+temporary);}
    }
    static boolean waitForCommand(Process process)throws InterruptedIOException {
        try{return process.waitFor(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Export cancelled during diagnostics.");}
    }
    private static void waitForTermination(Process process)throws InterruptedIOException {
        try{process.waitFor(1,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Export cancelled during diagnostics.");}
    }
    static void checkCancelled()throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Export cancelled.");}

    static final class Bundle implements AutoCloseable {
        final ZipOutputStream zip;final Progress progress;
        long bytes,files,partialFiles,unavailableFiles,unavailableDirectories,specialFiles,diagnosticFailures;
        private long lastProgressNs;
        final Set<String> names=new HashSet<>(),publicPaths=new HashSet<>();
        final StringBuilder manifest=new StringBuilder("entry\tbytes\tsha256\tstatus\tsource\n"),log=new StringBuilder();
        Bundle(OutputStream destination,Progress progress){zip=new ZipOutputStream(new BufferedOutputStream(destination,128*1024));zip.setLevel(1);this.progress=progress;}
        String progressText(){return files+" completed files · "+bytes/1024/1024+" MiB · "+unavailableFiles+" unavailable files";}
        void note(String message){log.append(message).append('\n');}
        void unavailableFile(String reason){unavailableFiles++;note("UNAVAILABLE FILE "+reason);}
        void unavailableDirectory(String reason){unavailableDirectories++;note("UNAVAILABLE DIRECTORY "+reason);}
        void publicFile(File file)throws Exception {if(publicPaths.add(file.getAbsolutePath()))copy(file,"system"+file.getAbsolutePath());}
        static void validate(String name)throws IOException {
            if(name==null||name.isEmpty()||name.startsWith("/")||name.contains("\\")||name.contains("\0")||Arrays.asList(name.split("/",-1)).contains("..")||Arrays.asList(name.split("/",-1)).contains(""))throw new IOException("Unsafe archive entry name.");
        }
        void copy(File file,String name)throws Exception {
            checkCancelled();validate(name);if(names.contains(name))return;boolean regular;
            try{regular=file.isFile();}catch(SecurityException e){unavailableFile(file+": "+e);return;}
            if(!regular){if(!file.exists())unavailableFile(file.toString());else{specialFiles++;note("NON-REGULAR FILE SKIPPED "+file);}return;}
            FileInputStream input;try{input=new FileInputStream(file);}catch(IOException|SecurityException e){unavailableFile(file+": "+e);return;}
            long expected=file.length();progress.update("Exporting "+file+"\n"+progressText());
            try(InputStream source=input){copyOpened(source,name,file.toString(),expected);}
        }
        /** Source read failures produce explicit PARTIAL entries; destination failures abort. */
        void copyOpened(InputStream source,String name,String origin,long expected)throws Exception {
            checkCancelled();validate(name);if(!names.add(name))return;
            MessageDigest digest=MessageDigest.getInstance("SHA-256");zip.putNextEntry(new ZipEntry(name));
            byte[] buffer=new byte[128*1024];long copied=0;String readFailure=null;
            while(true){checkCancelled();int count;try{count=source.read(buffer);}catch(InterruptedIOException e){throw e;}catch(IOException e){readFailure=e.toString();break;}if(count<0)break;if(count==0)continue;
                zip.write(buffer,0,count);digest.update(buffer,0,count);copied=Math.addExact(copied,count);bytes=Math.addExact(bytes,count);
                long now=System.nanoTime();if(now-lastProgressNs>1_000_000_000L){lastProgressNs=now;progress.update("Exporting "+origin+"\n"+progressText());}}
            zip.closeEntry();
            if(readFailure==null){files++;if(expected>=0&&expected!=copied)note("SOURCE SIZE CHANGED "+origin+": expected "+expected+", copied "+copied);}
            else{partialFiles++;note("PARTIAL FILE "+origin+": "+readFailure+"; copied "+copied+" bytes");}
            manifest.append(tsv(name)).append('\t').append(copied).append('\t').append(hex(digest.digest())).append('\t').append(readFailure==null?"COMPLETE":"PARTIAL").append('\t').append(tsv(origin)).append('\n');
        }
        void text(String name,String value)throws Exception {if(names.contains(name))return;byte[] bytes=value.getBytes(StandardCharsets.UTF_8);copyOpened(new ByteArrayInputStream(bytes),name,"generated",bytes.length);}
        void finishIndex()throws IOException {
            writeIndex("manifest.tsv",manifest.toString());writeIndex("collection-log.txt","Completed data files: "+files+"\nCopied bytes: "+bytes+"\nPartial files: "+partialFiles+"\nUnavailable files: "+unavailableFiles+"\nUnavailable directories: "+unavailableDirectories+"\nNon-regular files skipped: "+specialFiles+"\nDiagnostic failures: "+diagnosticFailures+"\n\n"+log);
        }
        private void writeIndex(String name,String value)throws IOException {checkCancelled();validate(name);if(!names.add(name))throw new IOException("Duplicate archive index.");zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        private static String tsv(String value){return value.replace('\t',' ').replace('\r',' ').replace('\n',' ');}
        private static String hex(byte[] data){StringBuilder out=new StringBuilder();for(byte b:data)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
        @Override public void close()throws IOException {zip.close();}
    }
}
