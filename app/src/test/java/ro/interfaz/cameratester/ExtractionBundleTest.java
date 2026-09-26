package ro.interfaz.cameratester;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class ExtractionBundleTest {
    @Test public void interruptedCommandPreservesCancellation()throws Exception {
        Process interrupted=new Process(){
            public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
            public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
            public InputStream getErrorStream(){return getInputStream();}
            public int waitFor()throws InterruptedException{throw new InterruptedException();}
            public boolean waitFor(long timeout,java.util.concurrent.TimeUnit unit)throws InterruptedException{throw new InterruptedException();}
            public int exitValue(){return 0;}public void destroy(){}
        };
        try{ExtractionBundle.waitForCommand(interrupted);fail("Cancellation must propagate");}
        catch(InterruptedIOException expected){assertTrue(Thread.currentThread().isInterrupted());try{ExtractionBundle.checkCancelled();fail();}catch(InterruptedIOException alsoExpected){}}
        finally{Thread.interrupted();}
    }
    @Test public void exportedBytesAndDigestMatchAndUnavailableFileIsListed()throws Exception {
        File file=file("abc");ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(ExtractionBundle.Bundle bundle=new ExtractionBundle.Bundle(output,s->{})) {
            bundle.copy(file,"packages/example/base.apk");bundle.copy(file,"packages/example/base.apk");
            bundle.copy(new File(file.getParentFile(),"absent-extractor-test-"+System.nanoTime()),"missing.apk");
            assertEquals(1,bundle.files);assertEquals(1,bundle.unavailableFiles);bundle.finishIndex();
        }finally{file.delete();}
        Map<String,String> contents=unzip(output);
        assertEquals("abc",contents.get("packages/example/base.apk"));
        assertTrue(contents.get("manifest.tsv").contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\tCOMPLETE"));
        assertTrue(contents.get("collection-log.txt").contains("UNAVAILABLE FILE"));assertFalse(contents.containsKey("missing.apk"));assertEquals(3,contents.size());
    }
    @Test public void allInstalledPackagesIncludeUserSystemAndServiceOnlyPackages() {
        PackageInfo teyes=pkg("com.spd.dvr"),user=pkg("com.whatsapp"),self=pkg("ro.interfaz.cameratester"),system=pkg("android.service.only");
        system.services=new ServiceInfo[]{new ServiceInfo()};system.applicationInfo.flags=ApplicationInfo.FLAG_SYSTEM;
        List<PackageInfo> selected=ExtractionBundle.installedPackages(Arrays.asList(teyes,user,self,system,teyes));
        assertEquals(4,selected.size());assertTrue(selected.contains(user));assertTrue(selected.contains(self));assertTrue(selected.contains(system));assertTrue(selected.contains(teyes));
    }
    @Test public void packageCopiesBaseAndEverySplitIncludingLargeAggregateCounters()throws Exception {
        File base=file("base"),one=file("split-one"),two=file("split-two");PackageInfo info=pkg("com.example.userapp");
        info.applicationInfo.sourceDir=base.getAbsolutePath();info.applicationInfo.splitSourceDirs=new String[]{one.getAbsolutePath(),two.getAbsolutePath()};
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(ExtractionBundle.Bundle bundle=new ExtractionBundle.Bundle(output,s->{})) {
            bundle.bytes=4L*1024*1024*1024;ExtractionBundle.copyPackageCode(bundle,info);
            assertEquals(3,bundle.files);assertEquals(4L*1024*1024*1024+22,bundle.bytes);bundle.finishIndex();
        }finally{base.delete();one.delete();two.delete();}
        Map<String,String> contents=unzip(output);assertEquals("base",contents.get("packages/com.example.userapp/base.apk"));assertEquals("split-one",contents.get("packages/com.example.userapp/split-0.apk"));assertEquals("split-two",contents.get("packages/com.example.userapp/split-1.apk"));
    }
    @Test public void aLargeReportedSourceSizeDoesNotCauseAnArbitrarySkip()throws Exception {
        File real=file("small-after-source-shrink");File reported=new File(real.getAbsolutePath()){@Override public long length(){return 2L*1024*1024*1024;}};
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(ExtractionBundle.Bundle bundle=new ExtractionBundle.Bundle(output,s->{})){bundle.copy(reported,"large.apk");assertEquals(1,bundle.files);assertEquals(0,bundle.unavailableFiles);bundle.finishIndex();}finally{real.delete();}
        Map<String,String> contents=unzip(output);assertEquals("small-after-source-shrink",contents.get("large.apk"));assertTrue(contents.get("collection-log.txt").contains("SOURCE SIZE CHANGED"));
    }
    @Test public void sourceReadFailureIsExplicitPartialAndDoesNotHideFollowingFiles()throws Exception {
        InputStream broken=new InputStream(){boolean sent;public int read()throws IOException{throw new IOException("source failure");}public int read(byte[] b,int off,int len)throws IOException{if(sent)throw new IOException("source failure");sent=true;b[off]='x';return 1;}};
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(ExtractionBundle.Bundle bundle=new ExtractionBundle.Bundle(output,s->{})) {
            bundle.copyOpened(broken,"broken.apk","simulated source",100);bundle.text("following.txt","complete");
            assertEquals(1,bundle.partialFiles);assertEquals(1,bundle.files);bundle.finishIndex();
        }
        Map<String,String> contents=unzip(output);assertEquals("x",contents.get("broken.apk"));assertEquals("complete",contents.get("following.txt"));assertTrue(contents.get("manifest.tsv").contains("\tPARTIAL\t"));assertTrue(contents.get("collection-log.txt").contains("PARTIAL FILE"));
    }
    @Test public void destinationFailureAbortsInsteadOfClaimingPartialSourceSuccess()throws Exception {
        OutputStream failed=new OutputStream(){public void write(int value)throws IOException{throw new IOException("destination full");}};
        try(ExtractionBundle.Bundle bundle=new ExtractionBundle.Bundle(failed,s->{})){bundle.text("one.txt","data");bundle.finishIndex();}
        catch(IOException expected){assertEquals("destination full",expected.getMessage());return;}
        fail("A destination write failure must propagate.");
    }
    @Test public void unsafeNamesAreRejected()throws Exception {
        for(String name:new String[]{"/absolute","packages/../../private","a\\b","a//b","","a/"}) {
            try{ExtractionBundle.Bundle.validate(name);fail(name);}catch(IOException expected){}
        }
        ExtractionBundle.Bundle.validate("packages/com.example/split-0.apk");
    }
    private static PackageInfo pkg(String name){PackageInfo p=new PackageInfo();p.packageName=name;p.applicationInfo=new ApplicationInfo();return p;}
    private static File file(String value)throws IOException{File f=File.createTempFile("extract-test-",".apk");Files.write(f.toPath(),value.getBytes(StandardCharsets.UTF_8));return f;}
    private static Map<String,String> unzip(ByteArrayOutputStream output)throws IOException {
        Map<String,String> result=new HashMap<>();try(ZipInputStream input=new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))){ZipEntry e;byte[] buffer=new byte[8192];while((e=input.getNextEntry())!=null){ByteArrayOutputStream text=new ByteArrayOutputStream();int n;while((n=input.read(buffer))!=-1)text.write(buffer,0,n);result.put(e.getName(),text.toString("UTF-8"));}}return result;
    }
}
