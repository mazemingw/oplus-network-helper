import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.system.Os;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import org.json.JSONArray;
import org.json.JSONObject;

/** Short lived renderer. The root watcher alone is allowed to disconnect an AP. */
public final class WifiFluidSession {
    private static final String AUTHORITY = "com.oplus.card.server.systemui.provider";
    private static final String TAG = "coloros-wifi-sense";
    private static final int ID = 19074;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final String token;
    private final boolean demo;
    private final int rssi;
    private final int threshold;
    private final long deadline;
    private final long duration;
    private final Object activityManager;
    private final Class<?> activityManagerType;
    private final IBinder providerToken = new Binder();
    private final Object provider;
    private final Method providerCall;
    private PendingIntent immediate;
    private Object cancelListener;
    private Class<?> cancelListenerType;
    private boolean finished;
    private boolean terminal;
    private boolean actionEmitted;
    private final Runnable tickTask = () -> tick(false);
    private final Runnable acknowledgementTimeout = this::finish;

    public static void main(String[] args) {
        try {
            if (args.length != 6 || Process.myUid() != 0) throw new IllegalArgumentException("Root session arguments required");
            // DEX is already loaded while root can read /data/adb. Only this UI child drops UID.
            Os.setgid(1000);
            Os.setuid(1000);
            Looper.prepareMainLooper();
            new WifiFluidSession(args).run();
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private WifiFluidSession(String[] args) throws Exception {
        demo = "demo".equals(args[0]);
        token = args[1];
        rssi = Integer.parseInt(args[2]);
        threshold = Integer.parseInt(args[3]);
        duration = Long.parseLong(args[4]);
        long requestedDeadline = Long.parseLong(args[5]);
        deadline = requestedDeadline == 0 ? SystemClock.elapsedRealtime() + duration : requestedDeadline;
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        context = system.createPackageContext("android", Context.CONTEXT_IGNORE_SECURITY);
        activityManagerType = Class.forName("android.app.IActivityManager");
        activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Object holder = activityManagerType.getMethod("getContentProviderExternal", String.class,
            int.class, IBinder.class, String.class).invoke(activityManager, AUTHORITY, 0, providerToken, TAG);
        if (holder == null) throw new IllegalStateException("ColorOS fluid provider unavailable");
        provider = holder.getClass().getField("provider").get(holder);
        providerCall = Class.forName("android.content.IContentProvider").getMethod("call",
            android.content.AttributionSource.class, String.class, String.class, String.class, Bundle.class);
    }

    private void run() throws Exception {
        String action = "com.nvmex.wifisense.IMMEDIATE." + token;
        // Sending a one-shot PendingIntent cancels it. Its Binder cancellation callback works
        // without an installed APK or an AMS-managed process for a BroadcastReceiver.
        immediate = PendingIntent.getBroadcast(context, ID, new Intent(action).setPackage("android"),
            PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        cancelListenerType = Class.forName("android.app.PendingIntent$CancelListener");
        cancelListener = Proxy.newProxyInstance(cancelListenerType.getClassLoader(), new Class<?>[]{cancelListenerType},
            (proxy, method, args) -> {
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("toString".equals(method.getName())) return "WifiSenseCancelListener";
                if ("onCanceled".equals(method.getName())) {
                    handler.post(() -> { if (!finished) emit("immediate"); });
                }
                return null;
            });
        Executor executor = task -> handler.post(task);
        boolean registered = (Boolean) PendingIntent.class.getMethod("addCancelListener", Executor.class,
            cancelListenerType).invoke(immediate, executor, cancelListener);
        if (!registered) throw new IllegalStateException("Immediate action already cancelled");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { send("cancelLiveAlert", new Notification()); } catch (Exception ignored) { }
        }, "wifi-fluid-cleanup"));
        Thread input = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String command = line;
                    handler.post(() -> command(command));
                }
            } catch (Exception ignored) { }
            if (!demo) handler.post(this::finish);
        }, "wifi-fluid-input");
        input.setDaemon(true);
        input.start();
        tick(true);
        Looper.loop();
    }

    private void tick(boolean first) {
        if (finished || terminal) return;
        long remaining = Math.max(0, deadline - SystemClock.elapsedRealtime());
        try {
            send("sendLiveAlert", build(remaining, first));
            if (first) System.out.println("WIFI_SENSE ready " + token);
        } catch (Exception error) {
            error.printStackTrace(System.err);
            finish();
            return;
        }
        if (remaining == 0) {
            emit("expired");
        } else {
            handler.postDelayed(tickTask, Math.min(1000, remaining));
        }
    }

    private Notification build(long remaining, boolean first) throws Exception {
        int seconds = (int) ((remaining + 999) / 1000);
        String title = "即将断开当前WIFI";
        String text = "WIFI信号弱，" + seconds + "秒后自动断开。";
        Bundle extras = new Bundle();
        extras.putParcelable("wifiSenseImmediate", immediate);
        JSONObject toggle = obj("style", "BUTTON", "checkedStyle", "SWITCH", "state", "ON",
            "type", "TEXT", "intent", "pi/wifiSenseImmediate", "desc", "保持当前WIFI连接，关闭即断开");
        JSONObject primary = obj("type", "t_standard", "title", obj("text", title),
            "labels", new JSONArray().put(new JSONArray().put(obj("text", text))));
        JSONObject actionRow = obj("type", "t_actions", "arr", "def", "btns", new JSONArray()
            .put(obj("type", "TEXT", "text", "立即断开 →", "style", "TEXT", "state", "DEFAULT"))
            .put(toggle));
        JSONObject progress = obj("progress", (int) remaining, "size", (int) duration,
            "styledByProgress", false, "segList", new JSONArray().put(obj("length", duration, "color", "#FFFFFF")));
        JSONObject extension = obj("type", "t_progress", "progress", progress);
        extras.putString("oplus.livealert.card", obj("primaryData", primary, "subData", actionRow,
            "extensionData", extension).toString());
        extras.putString("oplus.livealert.capsule", obj("capsuleData", obj("type", "t_sym",
            "firstTitle", obj("text", "Wi-Fi"), "lastTitle", obj("text", seconds + " 秒"))).toString());
        // Keep the strong card visible across updates; only its first frame is a milestone.
        extras.putString("oplusLiveAlertOptions", obj("id", TAG, "isMilestone", first,
            "showHostMap", new JSONArray().put(8), "remindType", 13,
            "extensibleActionMap", obj("isDismissible", true)).toString());
        return new Notification.Builder(context, TAG).setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title).setContentText(text).setOngoing(true).setShowWhen(false)
            .addExtras(extras).build();
    }

    private void emit(String event) {
        if (actionEmitted || terminal || finished) return;
        actionEmitted = true;
        handler.removeCallbacks(tickTask);
        System.out.println("WIFI_SENSE " + event + " " + token);
        System.out.flush();
        handler.postDelayed(acknowledgementTimeout, 5000);
    }

    private void command(String command) {
        if (finished) return;
        if ("cancel".equals(command)) { finish(); return; }
        if (!"working".equals(command) && !command.startsWith("result ")) return;
        terminal = true;
        handler.removeCallbacks(tickTask);
        handler.removeCallbacks(acknowledgementTimeout);
        String reason = command.startsWith("result ") ? command.substring(7) : "working";
        try { send("sendLiveAlert", buildResult(reason)); }
        catch (Exception error) { error.printStackTrace(System.err); finish(); return; }
        handler.postDelayed(acknowledgementTimeout, "working".equals(reason) ? 5000 : 15000);
    }

    private Notification buildResult(String reason) throws Exception {
        String text = "manual".equals(reason) ? "WIFI信号弱，已手动断开。"
            : "auto".equals(reason) ? "WIFI信号弱，已自动断开。"
            : "working".equals(reason) ? "正在断开当前WIFI…" : "未能断开当前WIFI。";
        JSONObject primary = obj("type", "t_standard", "title", obj("text", text));
        boolean disconnected = "manual".equals(reason) || "auto".equals(reason);
        String status = disconnected ? "已断开" : "working".equals(reason) ? "正在断开" : "断开失败";
        JSONObject disabledSwitch = obj("style", "BUTTON", "checkedStyle", "SWITCH", "state", disconnected ? "DISABLE" : "ON|DISABLE",
            "type", "TEXT", "desc", status);
        JSONObject actionRow = obj("type", "t_actions", "btns", new JSONArray()
            .put(obj("type", "TEXT", "text", status, "style", "TEXT", "state", "DEFAULT"))
            .put(disabledSwitch));
        Bundle extras = new Bundle();
        extras.putString("oplus.livealert.card", obj("primaryData", primary, "subData", actionRow).toString());
        extras.putString("oplus.livealert.capsule", obj("capsuleData", obj("type", "t_sym",
            "firstTitle", obj("text", "WIFI"), "lastTitle", obj("text", status))).toString());
        extras.putString("oplusLiveAlertOptions", obj("id", TAG, "isMilestone", false,
            "showHostMap", new JSONArray().put(8), "remindType", "working".equals(reason) ? 13 : 0,
            "extensibleActionMap", obj("isDismissible", true)).toString());
        return new Notification.Builder(context, TAG).setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(text).setOngoing(true).setShowWhen(false).addExtras(extras).build();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        try { send("cancelLiveAlert", new Notification()); } catch (Exception ignored) { }
        if (immediate != null) {
            try { PendingIntent.class.getMethod("removeCancelListener", cancelListenerType).invoke(immediate, cancelListener); }
            catch (Exception ignored) { }
            immediate.cancel();
        }
        try {
            activityManagerType.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class,
                int.class).invoke(activityManager, AUTHORITY, providerToken, 0);
        } catch (Exception ignored) { }
        System.exit(0);
    }

    private void send(String method, Notification notification) throws Exception {
        StatusBarNotification sbn = new StatusBarNotification("android", "android", ID, TAG,
            1000, Process.myPid(), 0, notification, Process.myUserHandle(), System.currentTimeMillis());
        Bundle data = new Bundle(); data.putParcelable("content", sbn);
        Bundle result = (Bundle) providerCall.invoke(provider, context.getAttributionSource(), AUTHORITY, method, null, data);
        if (result == null || result.getInt("resultCode", 0) != -1)
            throw new IllegalStateException("Fluid provider rejected " + method + ": " + result);
    }

    private static JSONObject obj(Object... pairs) throws Exception {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
