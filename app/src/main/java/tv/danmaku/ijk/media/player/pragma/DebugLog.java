package tv.danmaku.ijk.media.player.pragma;

import android.util.Log;
import java.util.Locale;

/* JADX INFO: loaded from: classes2.dex */
public class DebugLog {
    public static final boolean ENABLE_DEBUG = true;
    public static final boolean ENABLE_ERROR = true;
    public static final boolean ENABLE_INFO = true;
    public static final boolean ENABLE_VERBOSE = true;
    public static final boolean ENABLE_WARN = true;

    /* JADX INFO: renamed from: e */
    public static void m2324e(String str, String str2) {
        Log.e(str, str2);
    }

    /* JADX INFO: renamed from: e */
    public static void m2325e(String str, String str2, Throwable th) {
        Log.e(str, str2, th);
    }

    public static void efmt(String str, String str2, Object... objArr) {
        Log.e(str, String.format(Locale.US, str2, objArr));
    }

    /* JADX INFO: renamed from: i */
    public static void m2326i(String str, String str2) {
        Log.i(str, str2);
    }

    /* JADX INFO: renamed from: i */
    public static void m2327i(String str, String str2, Throwable th) {
        Log.i(str, str2, th);
    }

    public static void ifmt(String str, String str2, Object... objArr) {
        Log.i(str, String.format(Locale.US, str2, objArr));
    }

    /* JADX INFO: renamed from: w */
    public static void m2330w(String str, String str2) {
        Log.w(str, str2);
    }

    /* JADX INFO: renamed from: w */
    public static void m2331w(String str, String str2, Throwable th) {
        Log.w(str, str2, th);
    }

    public static void wfmt(String str, String str2, Object... objArr) {
        Log.w(str, String.format(Locale.US, str2, objArr));
    }

    /* JADX INFO: renamed from: d */
    public static void m2322d(String str, String str2) {
        Log.d(str, str2);
    }

    /* JADX INFO: renamed from: d */
    public static void m2323d(String str, String str2, Throwable th) {
        Log.d(str, str2, th);
    }

    public static void dfmt(String str, String str2, Object... objArr) {
        Log.d(str, String.format(Locale.US, str2, objArr));
    }

    /* JADX INFO: renamed from: v */
    public static void m2328v(String str, String str2) {
        Log.v(str, str2);
    }

    /* JADX INFO: renamed from: v */
    public static void m2329v(String str, String str2, Throwable th) {
        Log.v(str, str2, th);
    }

    public static void vfmt(String str, String str2, Object... objArr) {
        Log.v(str, String.format(Locale.US, str2, objArr));
    }

    public static void printStackTrace(Throwable th) {
        th.printStackTrace();
    }

    public static void printCause(Throwable th) {
        Throwable cause = th.getCause();
        if (cause != null) {
            th = cause;
        }
        printStackTrace(th);
    }
}
