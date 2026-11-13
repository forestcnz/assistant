package com.github.sisong;

/** JNI entry point for the HDiffPatch Android SDK. */
public final class HPatch {
    static {
        System.loadLibrary("hpatchz");
    }

    private HPatch() {}

    public static native int patch(String oldFileName, String diffFileName, String outNewFileName,
                                   long cacheMemory, int threadNum, boolean checksumNewData);
}
