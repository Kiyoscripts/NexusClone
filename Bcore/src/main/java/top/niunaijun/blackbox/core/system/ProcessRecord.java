package top.niunaijun.blackbox.core.system;

import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.ConditionVariable;
import android.os.IInterface;
import android.os.Process;

import java.util.Arrays;

import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.utils.Slog;

public class ProcessRecord extends Binder {
    private static final String TAG = "ProcessRecord";

    public final ApplicationInfo info;
    final public String processName;
    public IBActivityThread bActivityThread;
    public IInterface appThread;
    public int uid;
    public int pid;
    public int buid;
    public int bpid;
    public int callingBUid;
    public int userId;

    public ConditionVariable initLock = new ConditionVariable();

    public ProcessRecord(ApplicationInfo info, String processName) {
        this.info = info;
        this.processName = processName;
    }

    public int getCallingBUid() {
        return callingBUid;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{processName, pid, buid, bpid, uid, pid, userId});
    }

    public String getProviderAuthority() {
        return ProxyManifest.getProxyAuthorities(bpid);
    }

    public AppConfig getClientConfig() {
        AppConfig config = new AppConfig();
        config.packageName = info.packageName;
        config.processName = processName;
        config.bpid = bpid;
        config.buid = buid;
        config.uid = uid;
        config.callingBUid = callingBUid;
        config.userId = userId;
        config.token = this;
        // The device identity lives in the host app's own database, which the engine cannot
        // reach. The app installs a provider at startup; when none is registered the field
        // stays null and the native layer keeps its compile-time fallback identity.
        config.spoofedProps = resolveSpoofedProps(info.packageName, userId);
        return config;
    }

    /**
     * Ask the host app for this clone's device identity.
     *
     * Kept as a pluggable hook so the engine stays free of app-level storage concerns. A
     * provider that throws must not break the bind, so every failure degrades to null.
     */
    private static String[] resolveSpoofedProps(String packageName, int userId) {
        SpoofedPropsProvider provider = sSpoofedPropsProvider;
        if (provider == null) {
            return null;
        }
        try {
            return provider.spoofedPropsFor(packageName, userId);
        } catch (Throwable e) {
            Slog.w(TAG, "spoofed props provider failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Supplies the alternating name/value identity array for a clone.
     *
     * Implemented by the host app, which owns the identity database.
     */
    public interface SpoofedPropsProvider {
        /**
         * @return alternating property name / value pairs, or null when the clone has no
         *         identity bound.
         */
        String[] spoofedPropsFor(String packageName, int userId);
    }

    private static volatile SpoofedPropsProvider sSpoofedPropsProvider;

    /** Install (or clear, with null) the identity provider. Called once from app startup. */
    public static void setSpoofedPropsProvider(SpoofedPropsProvider provider) {
        sSpoofedPropsProvider = provider;
    }

    public void kill() {
        if (pid > 0) {
            try {
                Process.killProcess(pid);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    public String getPackageName() {
        return info.packageName;
    }
}
