package top.niunaijun.blackbox.proxy;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.os.Binder;
import android.os.Build;
import android.os.Process;

import top.niunaijun.blackbox.utils.Slog;

/**
 * Defence-in-depth caller check for the proxy stubs.
 *
 * The primary control is {@code android:exported="false"} on every proxy component in
 * {@code Bcore/src/main/AndroidManifest.xml}. This class exists because a manifest flag is
 * a single point of failure: a merge conflict, a fork that re-exports a stub, or a future
 * component added without the attribute would silently reopen the confused-deputy hole
 * documented in the audit (an external app handing the host an arbitrary Intent, AppConfig
 * or ServiceInfo and having it executed with the host's uid and permissions).
 *
 * The check is deliberately conservative. It only rejects a call when the caller is
 * positively identified as a different application. Anything it cannot determine is
 * allowed, because breaking clone launch is worse than the residual risk: the manifest
 * flag already blocks the external path, and this layer only has to catch regressions.
 */
public final class ProxyCallerGuard {

    private static final String TAG = "ProxyCallerGuard";

    private ProxyCallerGuard() {
    }

    /**
     * True when {@code uid} is this process, root, or the system server.
     *
     * Root (0) and system (1000) are allowed because the engine's own helper processes and
     * the framework's activity manager legitimately drive these stubs.
     */
    private static boolean isTrustedUid(int uid) {
        return uid == Process.myUid()
                || uid == Process.ROOT_UID
                || uid == Process.SYSTEM_UID;
    }

    /**
     * Guard for a binder entry point: {@code ContentProvider.call}, {@code Service.onBind},
     * {@code Service.onStartCommand}, {@code BroadcastReceiver.onReceive}.
     *
     * Returns true when the call may proceed.
     */
    public static boolean allowBinderCall(String component) {
        final int callingUid;
        try {
            callingUid = Binder.getCallingUid();
        } catch (Throwable t) {
            // Cannot determine the caller. Allow: the manifest flag is the real control.
            return true;
        }
        if (isTrustedUid(callingUid)) {
            return true;
        }
        Slog.w(TAG, component + ": rejected binder call from uid " + callingUid);
        return false;
    }

    /**
     * Guard for an Activity stub.
     *
     * {@code Binder.getCallingUid()} is not meaningful inside {@code onCreate} because the
     * activity may be created long after the start request, so the launched-from uid is
     * used instead. That API is 24+; below it the caller cannot be determined and the call
     * is allowed.
     *
     * Returns true when the activity may proceed.
     */
    public static boolean allowActivityLaunch(Activity activity, String component) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true;
        }
        final int launchedFromUid;
        try {
            launchedFromUid = activity.getLaunchedFromUid();
        } catch (Throwable t) {
            return true;
        }
        // -1 means the framework could not attribute the launch (for example a launch from
        // a PendingIntent whose creator is gone). Treat that as unknown, not as hostile.
        if (launchedFromUid < 0) {
            return true;
        }
        if (isTrustedUid(launchedFromUid)) {
            return true;
        }
        Slog.w(TAG, component + ": rejected activity launch from uid " + launchedFromUid);
        return false;
    }

    /**
     * Guard for a broadcast entry point.
     *
     * {@code getSendingUid()} is 34+; below that the binder caller uid is the best
     * available signal.
     *
     * Returns true when the broadcast may proceed.
     */
    public static boolean allowBroadcast(BroadcastReceiver receiver, String component) {
        if (Build.VERSION.SDK_INT >= 34) {
            final int sendingUid;
            try {
                sendingUid = receiver.getSendingUid();
            } catch (Throwable t) {
                return allowBinderCall(component);
            }
            if (sendingUid < 0) {
                return true;
            }
            if (isTrustedUid(sendingUid)) {
                return true;
            }
            Slog.w(TAG, component + ": rejected broadcast from uid " + sendingUid);
            return false;
        }
        return allowBinderCall(component);
    }
}
