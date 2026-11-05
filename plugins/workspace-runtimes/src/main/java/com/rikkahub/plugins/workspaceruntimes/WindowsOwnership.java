package com.rikkahub.plugins.workspaceruntimes;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.*;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Native ownership is established before any untrusted child instruction runs. */
final class WindowsOwnership implements AutoCloseable {
    interface Api extends StdCallLibrary {
        Api INSTANCE = Native.load("kernel32", Api.class, W32APIOptions.UNICODE_OPTIONS);
        HANDLE CreateJobObjectW(Pointer security, String name);
        boolean SetInformationJobObject(HANDLE job, int type, Pointer data, int length);
        boolean QueryInformationJobObject(HANDLE job, int type, Pointer data, int length, Pointer returned);
        boolean AssignProcessToJobObject(HANDLE job, HANDLE process);
        boolean TerminateJobObject(HANDLE job, int code);
        int ResumeThread(HANDLE thread);
        boolean InitializeProcThreadAttributeList(Pointer list, int count, int flags, BaseTSD.ULONG_PTRByReference size);
        boolean UpdateProcThreadAttribute(Pointer list, int flags, BaseTSD.ULONG_PTR attribute,
                Pointer value, BaseTSD.SIZE_T length, Pointer previous, Pointer returned);
        void DeleteProcThreadAttributeList(Pointer list);
        boolean CreateProcessW(String application, char[] command, Pointer processSecurity, Pointer threadSecurity,
                boolean inherit, int flags, Pointer environment, String directory, Pointer startup, WinBase.PROCESS_INFORMATION info);
    }
    /** Independent emergency termination preserves the job handle for drain observation. */
    private interface Nt extends StdCallLibrary {
        Nt INSTANCE = Native.load("ntdll", Nt.class);
        int NtTerminateJobObject(HANDLE job, int status);
    }
    @Structure.FieldOrder({"startup", "attributes"})
    public static class Startup extends Structure {
        public WinBase.STARTUPINFO startup = new WinBase.STARTUPINFO();
        public Pointer attributes;
    }
    // Do not initialize JNA/native bindings merely to ask whether the platform is supported.
    private static final class NativeBindings {
        static final Kernel32 K = Kernel32.INSTANCE;
    }
    private final Api api;
    private HANDLE job;
    private HANDLE process;
    private HANDLE completionPort;
    final InputStream stdout;
    final InputStream stderr;
    final OutputStream stdin;

    private WindowsOwnership(Api api, HANDLE job, HANDLE process, HANDLE completionPort, HANDLE in, HANDLE out, HANDLE err) {
        this.api=api; this.job=job; this.process=process; this.completionPort=completionPort;
        stdin=new PipeOutput(in); stdout=new PipeInput(out); stderr=new PipeInput(err);
    }
    static boolean supported() { return Platform.isWindows(); }
    static void check(boolean success) {
        if (!success) throw new IllegalStateException("Windows execution unavailable (native error " + NativeBindings.K.GetLastError() + ")");
    }
    static boolean valid(HANDLE handle) { return handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle); }
    static void closeHandle(HANDLE handle) { if(valid(handle)) check(NativeBindings.K.CloseHandle(handle)); }

    static WindowsOwnership start(List<String> argv, Path cwd, Map<String,String> env) {
        if (!supported()) throw new UnsupportedOperationException("Owned process execution currently requires Windows");
        return start(argv, cwd, env, Api.INSTANCE);
    }
    // Native adapter boundary; callers retain real suspended creation/assignment and handle ownership.
    static WindowsOwnership start(List<String> argv, Path cwd, Map<String,String> env, Api api) {
        if (!supported()) throw new UnsupportedOperationException("Owned process execution currently requires Windows");
        Kernel32 k=NativeBindings.K;
        HANDLE job=null, port=null; WinBase.PROCESS_INFORMATION pi=new WinBase.PROCESS_INFORMATION();
        List<HANDLE> handles=new ArrayList<>();
        Memory attributes=null;
        boolean initialized=false, success=false, created=false, assigned=false;
        try {
            job=api.CreateJobObjectW(null,null); check(valid(job));
            try (Memory limits=new Memory(Native.POINTER_SIZE==8?144:112)) {
                limits.clear(); limits.setInt(16,0x2000);
                check(api.SetInformationJobObject(job,9,limits,(int)limits.size()));
            }
            // A second, independent drain observation survives job-query failure. It is associated
            // before assignment, so ACTIVE_PROCESS_ZERO cannot be missed due to a fast root exit.
            port=k.CreateIoCompletionPort(WinBase.INVALID_HANDLE_VALUE,null,null,1); check(valid(port));
            try (Memory association=new Memory(2L*Native.POINTER_SIZE)) {
                association.setPointer(0,Pointer.createConstant(1)); association.setPointer(Native.POINTER_SIZE,port.getPointer());
                check(api.SetInformationJobObject(job,7,association,(int)association.size()));
            }
            WinBase.SECURITY_ATTRIBUTES sa=new WinBase.SECURITY_ATTRIBUTES(); sa.bInheritHandle=true; sa.write();
            HANDLE[] in=pipe(sa,handles), out=pipe(sa,handles), err=pipe(sa,handles);
            check(k.SetHandleInformation(in[1],1,0)); check(k.SetHandleInformation(out[0],1,0)); check(k.SetHandleInformation(err[0],1,0));
            BaseTSD.ULONG_PTRByReference size=new BaseTSD.ULONG_PTRByReference();
            api.InitializeProcThreadAttributeList(null,1,0,size);
            attributes=new Memory(size.getValue().longValue());
            check(api.InitializeProcThreadAttributeList(attributes,1,0,size)); initialized=true;
            try (Memory allowed=new Memory(3L*Native.POINTER_SIZE)) {
                allowed.setPointer(0,in[0].getPointer()); allowed.setPointer(Native.POINTER_SIZE,out[1].getPointer()); allowed.setPointer(2L*Native.POINTER_SIZE,err[1].getPointer());
                check(api.UpdateProcThreadAttribute(attributes,0,new BaseTSD.ULONG_PTR(0x20002),allowed,new BaseTSD.SIZE_T(allowed.size()),null,null));
                Startup startup=new Startup(); startup.startup.cb=new WinDef.DWORD(startup.size());
                startup.startup.dwFlags=0x100; startup.startup.hStdInput=in[0]; startup.startup.hStdOutput=out[1]; startup.startup.hStdError=err[1];
                startup.attributes=attributes; startup.write();
                String block=env.entrySet().stream().sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                        .map(e->e.getKey()+"="+e.getValue()+"\0").reduce("",String::concat)+"\0";
                try (Memory environment=new Memory((block.length()+1L)*2)) {
                    environment.setWideString(0,block);
                    String command=String.join(" ",argv.stream().map(WindowsOwnership::quote).toList());
                    check(api.CreateProcessW(argv.get(0),Native.toCharArray(command),null,null,true,
                            0x4|0x400|0x80000|0x08000000,environment,cwd.toString(),startup.getPointer(),pi)); created=true;
                }
                check(api.AssignProcessToJobObject(job,pi.hProcess)); assigned=true;
                check(api.ResumeThread(pi.hThread)!=-1);
            }
            closeHandle(pi.hThread); pi.hThread=null;
            closeHandle(in[0]); handles.remove(in[0]); closeHandle(out[1]); handles.remove(out[1]); closeHandle(err[1]); handles.remove(err[1]);
            // Attribute-list cleanup is still part of startup: a failure must not publish a process.
            if(initialized) { initialized=false; api.DeleteProcThreadAttributeList(attributes); }
            WindowsOwnership owned=new WindowsOwnership(api,job,pi.hProcess,port,in[1],out[0],err[0]);
            success=true;
            return owned;
        } finally {
            try {
                if(initialized) api.DeleteProcThreadAttributeList(attributes);
            } finally {
                try { if(attributes!=null) attributes.close(); }
                finally {
                    if(!success) {
                        try {
                            if(assigned) {
                                // Resume may already have produced descendants. Keep the job/port
                                // until the whole job is empty; root exit or kill-on-close is not drain.
                                check(tryTerminateJob(api,job));
                                awaitJobDrain(api,job,port,false);
                            } else if(created) {
                                // Assignment never succeeded: this root is still suspended and unowned.
                                check(k.TerminateProcess(pi.hProcess,1));
                                check(k.WaitForSingleObject(pi.hProcess,-1)==0);
                            }
                        } finally {
                            try { closeHandle(job); }
                            finally {
                                try { closeHandle(pi.hThread); }
                                finally {
                                    try { closeHandle(pi.hProcess); }
                                    finally {
                                        try { closeHandle(port); }
                                        finally { closeAll(handles); }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private static void closeAll(List<HANDLE> handles) {
        RuntimeException failure=null;
        for(HANDLE handle:handles) try { closeHandle(handle); }
        catch(RuntimeException e) { if(failure==null)failure=e;else failure.addSuppressed(e); }
        if(failure!=null)throw failure;
    }
    private static HANDLE[] pipe(WinBase.SECURITY_ATTRIBUTES sa,List<HANDLE> handles) {
        HANDLEByReference read=new HANDLEByReference(),write=new HANDLEByReference();
        check(NativeBindings.K.CreatePipe(read,write,sa,0)); handles.add(read.getValue()); handles.add(write.getValue());
        return new HANDLE[]{read.getValue(),write.getValue()};
    }
    private static String quote(String text) {
        // Simple argv elements require no CRT quoting. Gratuitous quotes around
        // cmd.exe builtins become part of cmd's /c command syntax and break execution.
        if(!text.isEmpty()&&text.chars().noneMatch(c->Character.isWhitespace(c)||c=='"'))return text;
        StringBuilder b=new StringBuilder("\""); int slashes=0;
        for(char c:text.toCharArray()) {
            if(c=='\\') {slashes++;continue;}
            b.append("\\".repeat(c=='\"'?slashes*2+1:slashes)).append(c); slashes=0;
        }
        return b.append("\\".repeat(slashes*2)).append('"').toString();
    }
    boolean rootAlive() {
        int state=NativeBindings.K.WaitForSingleObject(process,0);
        if(state!=0 && state!=258)check(false);
        return state==258;
    }
    boolean treeAlive() { return treeAlive(api,job); }
    private static boolean treeAlive(Api api, HANDLE job) {
        try(Memory info=new Memory(48)) {
            check(api.QueryInformationJobObject(job,1,info,48,null)); return info.getInt(40)!=0;
        }
    }
    void terminate() { check(api.TerminateJobObject(job,1)); }

    private static boolean tryTerminateJob(Api api, HANDLE job) {
        try { check(api.TerminateJobObject(job,1)); return true; }
        catch(Throwable unavailable) {
            // Keep ownership/query handles live through independent emergency termination.
            try { return Nt.INSTANCE.NtTerminateJobObject(job,1)>=0; }
            catch(Throwable emergencyFailure) { return false; }
        }
    }
    /** Kill-on-close fallback, then actual native drain evidence, never Future.cancel-as-drain. */
    boolean abortAndDrain() {
        if(!tryTerminateJob(api,job)) closeJob();
        return awaitJobDrain(api,job,completionPort,true);
    }
    private static boolean awaitJobDrain(Api api, HANDLE job, HANDLE completionPort, boolean bounded) {
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        IntByReference message=new IntByReference();
        BaseTSD.ULONG_PTRByReference key=new BaseTSD.ULONG_PTRByReference();
        PointerByReference overlapped=new PointerByReference();
        for(;;) {
            // Job completion notifications are not guaranteed by Windows. Keep the ordinary
            // query as an alternative; on persistent query failure only ACTIVE_PROCESS_ZERO proves drain.
            if(valid(job)) try { if(!treeAlive(api,job))return true; } catch(Throwable ignored) { }
            if(NativeBindings.K.GetQueuedCompletionStatus(completionPort,message,key,overlapped,50)) {
                if(key.getValue().longValue()==1 && message.getValue()==4)return true;
            } else if(NativeBindings.K.GetLastError()!=258)check(false);
            // A published run can explicitly report incomplete drain. Failed startup cannot:
            // retain its native ownership until actual drain instead of releasing it on a timer.
            if(bounded && System.nanoTime()>=deadline)return false;
        }
    }
    int exitCode() { IntByReference code=new IntByReference();check(NativeBindings.K.GetExitCodeProcess(process,code));return code.getValue(); }
    private void closeJob() { if(valid(job)) { closeHandle(job); job=null; } }
    @Override public synchronized void close() {
        try { closeJob(); }
        finally {
            try { closeHandle(process); process=null; }
            finally {
                try { closeHandle(completionPort); completionPort=null; }
                finally {
                    try { stdin.close(); }
                    catch(IOException e) { throw new UncheckedIOException(e); }
                    finally {
                        try { stdout.close(); }
                        catch(IOException e) { throw new UncheckedIOException(e); }
                        finally { try { stderr.close(); } catch(IOException e) { throw new UncheckedIOException(e); } }
                    }
                }
            }
        }
    }
    private static final class PipeInput extends InputStream {
        private HANDLE handle;
        PipeInput(HANDLE handle){this.handle=handle;}
        @Override public int read() throws IOException {byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        @Override public int read(byte[] b,int off,int len) throws IOException {
            Objects.checkFromIndexSize(off,len,b.length); if(len==0)return 0;
            byte[] buffer=new byte[Math.min(len,8192)]; IntByReference count=new IntByReference();
            if(!NativeBindings.K.ReadFile(handle,buffer,buffer.length,count,null)) {
                int error=NativeBindings.K.GetLastError(); if(error==109||error==232)return -1;throw new IOException("pipe read failed: "+error);
            }
            if(count.getValue()==0)return -1;System.arraycopy(buffer,0,b,off,count.getValue());return count.getValue();
        }
        @Override public synchronized void close(){closeHandle(handle);handle=null;}
    }
    private static final class PipeOutput extends OutputStream {
        private HANDLE handle;
        PipeOutput(HANDLE handle){this.handle=handle;}
        @Override public void write(int value)throws IOException{write(new byte[]{(byte)value});}
        @Override public synchronized void write(byte[] b,int off,int len)throws IOException {
            Objects.checkFromIndexSize(off,len,b.length);
            while(len>0){byte[] chunk=Arrays.copyOfRange(b,off,off+Math.min(len,8192));IntByReference count=new IntByReference();
                if(!NativeBindings.K.WriteFile(handle,chunk,chunk.length,count,null)||count.getValue()==0)throw new IOException("pipe write failed");
                off+=count.getValue();len-=count.getValue();}
        }
        @Override public synchronized void close(){closeHandle(handle);handle=null;}
    }
}
