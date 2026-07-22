package dev.sebastiano.indexino.distribution;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Starts a command inside a kernel-owned process boundary used by native verification tests. */
public final class CapturedProcessBoundary {
    public static final String ENVIRONMENT_KEY = "INDEXINO_CAPTURE_BOUNDARY";

    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int NO_SUCH_PROCESS = 3;
    private static final int SIGKILL = 9;

    private static Pointer windowsJob;

    private CapturedProcessBoundary() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Expected ready-file path and command");
        }

        String marker = establishBoundary();
        Path readyFile = Path.of(args[0]);
        Files.writeString(readyFile, marker);

        ProcessBuilder processBuilder =
                new ProcessBuilder(Arrays.copyOfRange(args, 1, args.length)).inheritIO();
        processBuilder.environment().put(ENVIRONMENT_KEY, marker);
        Process process = processBuilder.start();
        System.exit(process.waitFor());
    }

    public static boolean terminate(long boundaryPid) {
        if (Platform.isWindows()) {
            ProcessHandle handle = ProcessHandle.of(boundaryPid).orElse(null);
            return handle == null
                    || !handle.isAlive()
                    || handle.destroyForcibly()
                    || !handle.isAlive();
        }
        int result = PosixLibC.INSTANCE.kill(-Math.toIntExact(boundaryPid), SIGKILL);
        return result == 0 || Native.getLastError() == NO_SUCH_PROCESS;
    }

    private static String establishBoundary() {
        if (Platform.isWindows()) {
            establishWindowsJob();
            return "windows-job";
        }
        int sessionId = PosixLibC.INSTANCE.setsid();
        if (sessionId < 0) {
            throw new IllegalStateException("setsid failed with errno " + Native.getLastError());
        }
        return "posix-process-group";
    }

    private static void establishWindowsJob() {
        WindowsKernel32 kernel32 = WindowsKernel32.INSTANCE;
        Pointer job = kernel32.CreateJobObjectW(Pointer.NULL, null);
        if (job == null) {
            throw windowsFailure("CreateJobObjectW");
        }

        JobObjectExtendedLimitInformation limits = new JobObjectExtendedLimitInformation();
        limits.basicLimitInformation.limitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        limits.write();
        if (!kernel32.SetInformationJobObject(
                job,
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                limits.getPointer(),
                limits.size())) {
            throw windowsFailure("SetInformationJobObject");
        }
        if (!kernel32.AssignProcessToJobObject(job, kernel32.GetCurrentProcess())) {
            throw windowsFailure("AssignProcessToJobObject");
        }
        windowsJob = job;
    }

    private static IllegalStateException windowsFailure(String operation) {
        return new IllegalStateException(
                operation + " failed with error " + WindowsKernel32.INSTANCE.GetLastError());
    }

    private interface PosixLibC extends Library {
        PosixLibC INSTANCE = Native.load(Platform.C_LIBRARY_NAME, PosixLibC.class);

        int setsid();

        int kill(int pid, int signal);
    }

    private interface WindowsKernel32 extends Library {
        WindowsKernel32 INSTANCE = Native.load("kernel32", WindowsKernel32.class);

        Pointer CreateJobObjectW(Pointer securityAttributes, WString name);

        boolean SetInformationJobObject(
                Pointer job, int informationClass, Pointer information, int informationLength);

        boolean AssignProcessToJobObject(Pointer job, Pointer process);

        Pointer GetCurrentProcess();

        int GetLastError();
    }

    public static final class JobObjectBasicLimitInformation extends Structure {
        public long perProcessUserTimeLimit;
        public long perJobUserTimeLimit;
        public int limitFlags;
        public Pointer minimumWorkingSetSize;
        public Pointer maximumWorkingSetSize;
        public int activeProcessLimit;
        public Pointer affinity;
        public int priorityClass;
        public int schedulingClass;

        @Override
        protected List<String> getFieldOrder() {
            return List.of(
                    "perProcessUserTimeLimit",
                    "perJobUserTimeLimit",
                    "limitFlags",
                    "minimumWorkingSetSize",
                    "maximumWorkingSetSize",
                    "activeProcessLimit",
                    "affinity",
                    "priorityClass",
                    "schedulingClass");
        }
    }

    public static final class IoCounters extends Structure {
        public long readOperationCount;
        public long writeOperationCount;
        public long otherOperationCount;
        public long readTransferCount;
        public long writeTransferCount;
        public long otherTransferCount;

        @Override
        protected List<String> getFieldOrder() {
            return List.of(
                    "readOperationCount",
                    "writeOperationCount",
                    "otherOperationCount",
                    "readTransferCount",
                    "writeTransferCount",
                    "otherTransferCount");
        }
    }

    public static final class JobObjectExtendedLimitInformation extends Structure {
        public JobObjectBasicLimitInformation basicLimitInformation =
                new JobObjectBasicLimitInformation();
        public IoCounters ioInfo = new IoCounters();
        public Pointer processMemoryLimit;
        public Pointer jobMemoryLimit;
        public Pointer peakProcessMemoryUsed;
        public Pointer peakJobMemoryUsed;

        @Override
        protected List<String> getFieldOrder() {
            return List.of(
                    "basicLimitInformation",
                    "ioInfo",
                    "processMemoryLimit",
                    "jobMemoryLimit",
                    "peakProcessMemoryUsed",
                    "peakJobMemoryUsed");
        }
    }
}
