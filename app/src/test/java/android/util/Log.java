package android.util;

/**
 * The platform's log, for the JVM tests only: PdfBox-Android logs what it repairs or skips in a damaged
 * PDF (`Log.d`, `Log.w`, `Log.e`), and the android.jar of the unit tests throws "not mocked" for every call.
 * These tests open hostile files with PdfBox (PDF to text, data/convert): what it logs is printed, never
 * thrown. The calls are the seven forms PdfBox-Android 2.0.27.0 makes, read in its bytecode (javap).
 * Placed before android.jar on the test classpath, as the kXML parser beside it.
 */
public final class Log {
    private Log() {
    }

    public static int d(String tag, String msg) {
        return print("D", tag, msg, null);
    }

    public static int d(String tag, String msg, Throwable tr) {
        return print("D", tag, msg, tr);
    }

    public static int i(String tag, String msg) {
        return print("I", tag, msg, null);
    }

    public static int w(String tag, String msg) {
        return print("W", tag, msg, null);
    }

    public static int w(String tag, String msg, Throwable tr) {
        return print("W", tag, msg, tr);
    }

    public static int e(String tag, String msg) {
        return print("E", tag, msg, null);
    }

    public static int e(String tag, String msg, Throwable tr) {
        return print("E", tag, msg, tr);
    }

    private static int print(String level, String tag, String msg, Throwable tr) {
        System.out.println(level + "/" + tag + ": " + msg + (tr == null ? "" : " (" + tr + ")"));
        return 0;
    }
}
