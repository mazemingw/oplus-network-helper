import android.content.Context;
import android.content.ContextWrapper;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileLock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class ColorOsWifiSense {
    private static final String TAG = "ColorOsWifiSense";
    private static final String ROOT_PACKAGE = "root";
    private static final int DEFAULT_THRESHOLD = -80;
    private static final long SAMPLE_MS = 7_000;

    private final File moduleDir;
    private final File thresholdFile;
    private final boolean fluidEnabled;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Network> connections = new HashSet<>();
    private final Runnable sampleTask = this::sample;
    private final Object wifi;
    private final Method connectionInfo;
    private final Method disconnect;

    private final WifiDisconnectPolicy policy = new WifiDisconnectPolicy();
    private String fluidToken;
    private BufferedWriter fluidInput;
    private boolean fluidResult;
    private boolean demo;
    private boolean demoActionSeen;
    private Network disconnectRequested;
    private String disconnectReason;

    private ColorOsWifiSense(File moduleDir, File stateDir) throws ReflectiveOperationException {
        this.moduleDir = moduleDir;
        thresholdFile = new File(stateDir, "threshold");
        fluidEnabled = readFluidEnabled(moduleDir);
        Class<?> wifiInterface = Class.forName("android.net.wifi.IWifiManager");
        wifi = systemInterface("wifi", wifiInterface);
        connectionInfo = findConnectionInfo(wifiInterface);
        disconnect = wifiInterface.getMethod("disconnect", String.class);
    }

    @SuppressWarnings("deprecation")
    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0 || args.length != 2) throw new IllegalArgumentException("Root and module paths required");
            if ("--demo".equals(args[0])) {
                Looper.prepareMainLooper();
                new ColorOsWifiSense(new File(args[1]), new File(args[1])).runDemo();
                return;
            }
            File moduleDir = new File(args[0]);
            File stateDir = new File(args[1]);
            if (!stateDir.isDirectory()) throw new IOException("Module state directory is missing");
            try (RandomAccessFile lockFile = new RandomAccessFile(new File(stateDir, "watcher.lock"), "rw");
                 FileLock lock = lockFile.getChannel().tryLock()) {
                if (lock == null) return;
                Looper.prepareMainLooper();
                new ColorOsWifiSense(moduleDir, stateDir).listen();
            }
        } catch (Exception e) {
            Log.e(TAG, "Wi-Fi watcher stopped", unwrap(e));
            System.exit(1);
        }
    }

    private void listen() throws ReflectiveOperationException {
        if (disabled()) return;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopFluid, "wifi-sense-cleanup"));
        ConnectivityManager connectivity = connectivityManager();
        NetworkRequest request = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (connections.add(network)) updateSampling();
            }

            @Override public void onLost(Network network) {
                if (network.equals(disconnectRequested)) {
                    Log.i(TAG, "Wi-Fi network lost after AP disconnect request");
                    showFluidResult(disconnectReason);
                    disconnectRequested = null;
                }
                if (connections.remove(network)) updateSampling();
            }
        };
        FileObserver moduleObserver = new FileObserver(moduleDir,
            FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.DELETE_SELF | FileObserver.MOVE_SELF) {
            @Override public void onEvent(int event, String path) {
                if ((event & (FileObserver.DELETE_SELF | FileObserver.MOVE_SELF)) != 0) {
                    handler.post(() -> System.exit(0));
                } else if ("disable".equals(path) || "remove".equals(path)) {
                    handler.post(() -> {
                        if (disabled()) System.exit(0);
                    });
                }
            }
        };
        moduleObserver.startWatching();
        if (disabled()) return;
        connectivity.registerNetworkCallback(request, callback, handler);
        Log.i(TAG, "Waiting for Wi-Fi connection events");
        try {
            Looper.loop();
        } finally {
            stopFluid();
            moduleObserver.stopWatching();
            connectivity.unregisterNetworkCallback(callback);
        }
    }

    private boolean disabled() {
        return !moduleDir.isDirectory() || new File(moduleDir, "disable").exists() || new File(moduleDir, "remove").exists();
    }

    private void updateSampling() {
        handler.removeCallbacks(sampleTask);
        policy.clear();
        stopCountdown();
        if (connections.size() == 1) {
            handler.post(sampleTask);
        } else {
            Log.i(TAG, "RSSI sampling paused (Wi-Fi network count: " + connections.size() + ")");
        }
    }

    private void sample() {
        handler.removeCallbacks(sampleTask);
        if (disabled()) System.exit(0);
        if (connections.size() != 1) return;
        try {
            int threshold = readThreshold();
            WifiInfo info = readConnectionInfo();
            int rssi = info.getRssi();
            String bssid = info.getBSSID();
            if (info.getSupplicantState() != SupplicantState.COMPLETED || bssid == null
                    || "02:00:00:00:00:00".equals(bssid) || rssi < -126 || rssi >= 0) {
                policy.clear();
                stopCountdown();
                return;
            }
            WifiDisconnectPolicy.Decision decision = policy.sample(bssid, rssi, threshold, SystemClock.elapsedRealtime());
            Log.d(TAG, "RSSI=" + rssi + " threshold=" + threshold + " decision=" + decision);
            if (decision == WifiDisconnectPolicy.Decision.WARN) {
                startFluid(rssi, threshold);
            } else if (decision == WifiDisconnectPolicy.Decision.DISCONNECT) {
                disconnectAp(rssi, "countdown");
            } else if (!policy.pending()) {
                stopCountdown();
            }
        } catch (Exception e) {
            policy.clear();
            stopCountdown();
            Log.w(TAG, "Unable to process the Wi-Fi sample", unwrap(e));
        } finally {
            handler.postDelayed(sampleTask, policy.pending() && fluidEnabled ? 1_000 : SAMPLE_MS);
        }
    }

    private void disconnectAp(int rssi, String reason) throws ReflectiveOperationException {
        fluidResult = fluidEnabled && fluidInput != null;
        sendFluid("working");
        if (Boolean.TRUE.equals(disconnect.invoke(wifi, ROOT_PACKAGE))) {
            disconnectRequested = connections.iterator().next();
            disconnectReason = "user switch".equals(reason) ? "manual" : "auto";
            Log.i(TAG, "AP disconnect requested at " + rssi + " dBm (" + reason + ")");
            Network requested = disconnectRequested;
            handler.postDelayed(() -> {
                if (requested.equals(disconnectRequested)) {
                    Log.w(TAG, "AP disconnect confirmation timed out");
                    disconnectRequested = null;
                    showFluidResult("failed");
                }
            }, 4_000);
        } else {
            Log.w(TAG, "Android rejected the AP disconnect");
            showFluidResult("failed");
        }
    }

    private void showFluidResult(String reason) {
        if (!fluidEnabled || fluidInput == null) return;
        fluidResult = true;
        sendFluid("result " + (reason == null ? "failed" : reason));
        Log.i(TAG, "Fluid result retained for 15 seconds: " + reason);
    }

    private void stopCountdown() {
        if (!fluidResult) stopFluid();
    }

    private void sendFluid(String command) {
        BufferedWriter input = fluidInput;
        if (input == null) return;
        try { input.write(command); input.write('\n'); input.flush(); }
        catch (IOException error) { Log.w(TAG, "Fluid session command failed", error); }
    }

    private void startFluid(int rssi, int threshold) {
        if (!fluidEnabled) return;
        stopFluid();
        String sessionToken = UUID.randomUUID().toString();
        try {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/system/bin",
                "--nice-name=coloros-wifi-fluid", "WifiFluidSession", demo ? "demo" : "watch", sessionToken,
                String.valueOf(rssi), String.valueOf(threshold), String.valueOf(WifiDisconnectPolicy.COUNTDOWN_MS),
                String.valueOf(policy.deadline()));
            builder.environment().put("CLASSPATH", new File(moduleDir, "wifi-fluid.dex").getAbsolutePath());
            builder.redirectErrorStream(true);
            java.lang.Process child = builder.start();
            fluidToken = sessionToken;
            fluidInput = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), "UTF-8"));
            Thread reader = new Thread(() -> {
                try (BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = output.readLine()) != null) {
                        String[] parts = line.split(" ");
                        if (parts.length == 3 && "WIFI_SENSE".equals(parts[0]) && sessionToken.equals(parts[2])) {
                            String event = parts[1];
                            handler.post(() -> onFluidEvent(sessionToken, event));
                        } else {
                            Log.d(TAG, "Fluid UI: " + line);
                        }
                    }
                } catch (IOException error) {
                    Log.w(TAG, "Fluid session output closed", error);
                } finally {
                    handler.post(() -> {
                        if (sessionToken.equals(fluidToken)) stopFluid();
                    });
                }
            }, "wifi-fluid-events");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException error) {
            Log.w(TAG, "Unable to start fluid alert; AP policy remains active", error);
        }
    }

    private void onFluidEvent(String sessionToken, String event) {
        if (!sessionToken.equals(fluidToken)) return;
        if ("ready".equals(event)) {
            Log.i(TAG, "Fluid alert ready");
            return;
        }
        if (!"immediate".equals(event) && !"expired".equals(event)) return;
        if (demo) {
            if (demoActionSeen) return;
            demoActionSeen = true;
            System.out.println("Demo action=" + event + "; no Wi-Fi disconnect executed");
            policy.clear();
            showFluidResult("immediate".equals(event) ? "manual" : "auto");
            handler.postDelayed(() -> { stopFluid(); System.exit(0); }, 15_500);
            return;
        }
        if (disabled() || connections.size() != 1 || !policy.pending()) return;
        if ("expired".equals(event)) {
            sample();
            return;
        }
        try {
            WifiInfo info = readConnectionInfo();
            int threshold = readThreshold();
            int rssi = info.getRssi();
            if (info.getSupplicantState() == SupplicantState.COMPLETED && rssi >= -126 && rssi < 0
                    && policy.immediate(info.getBSSID(), rssi, threshold, SystemClock.elapsedRealtime())) {
                disconnectAp(rssi, "user switch");
            } else {
                sample();
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to handle immediate disconnect", unwrap(error));
            policy.clear();
            stopFluid();
        }
    }

    private synchronized void stopFluid() {
        fluidToken = null;
        fluidResult = false;
        BufferedWriter input = fluidInput;
        fluidInput = null;
        if (input == null) return;
        try { input.write("cancel\n"); input.flush(); input.close(); }
        catch (IOException ignored) { }
    }

    private void runDemo() {
        if (!fluidEnabled) {
            System.out.println("当前为仅核心模式，请重新安装并选择流体云通知。");
            System.exit(1);
        }
        System.out.println("演示模式：不会断开WIFI，仅测试流体云和开关回调。");
        demo = true;
        policy.sample("demo", -84, -80, SystemClock.elapsedRealtime());
        startFluid(-84, -80);
        handler.postDelayed(() -> {
            if (demoActionSeen) return;
            stopFluid();
            System.out.println("Demo finished; no Wi-Fi disconnect executed");
            System.exit(0);
        }, 10_000);
        Looper.loop();
    }

    private static boolean readFluidEnabled(File moduleDir) {
        if (!new File(moduleDir, "wifi-fluid.dex").isFile()) return false;
        try (BufferedReader input = new BufferedReader(new FileReader(new File(moduleDir, "fluid_enabled")))) {
            return "1".equals(input.readLine());
        } catch (IOException ignored) { return false; }
    }

    private int readThreshold() throws IOException {
        if (!thresholdFile.isFile()) return DEFAULT_THRESHOLD;
        try (BufferedReader input = new BufferedReader(new FileReader(thresholdFile))) {
            String value = input.readLine();
            int threshold = Integer.parseInt(value == null ? "" : value.trim());
            if (threshold < -100 || threshold > -20) throw new IOException("RSSI threshold outside -100 to -20 dBm");
            return threshold;
        }
    }

    private WifiInfo readConnectionInfo() throws ReflectiveOperationException {
        return (WifiInfo) (connectionInfo.getParameterTypes().length == 1
            ? connectionInfo.invoke(wifi, ROOT_PACKAGE)
            : connectionInfo.invoke(wifi, ROOT_PACKAGE, null));
    }

    private static Method findConnectionInfo(Class<?> wifiInterface) throws NoSuchMethodException {
        try {
            return wifiInterface.getMethod("getConnectionInfo", String.class, String.class);
        } catch (NoSuchMethodException e) {
            return wifiInterface.getMethod("getConnectionInfo", String.class);
        }
    }

    private static ConnectivityManager connectivityManager() throws ReflectiveOperationException {
        // app_process has no APK context. Use the framework context with the UID 0 Binder identity.
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context rootContext = new ContextWrapper(system) {
            @Override public String getPackageName() { return ROOT_PACKAGE; }
            @Override public String getOpPackageName() { return ROOT_PACKAGE; }
        };
        Class<?> connectivityInterface = Class.forName("android.net.IConnectivityManager");
        return ConnectivityManager.class.getConstructor(Context.class, connectivityInterface)
            .newInstance(rootContext, systemInterface("connectivity", connectivityInterface));
    }

    private static Object systemInterface(String name, Class<?> iface) throws ReflectiveOperationException {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, name);
        if (binder == null) throw new IllegalStateException("Android service unavailable: " + name);
        return Class.forName(iface.getName() + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static Throwable unwrap(Exception error) {
        if (error instanceof InvocationTargetException && error.getCause() != null) return error.getCause();
        return error;
    }
}
