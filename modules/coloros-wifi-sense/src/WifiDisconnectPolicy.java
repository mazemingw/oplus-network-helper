import java.util.Objects;

/** Timing and AP identity checks shared by sampling and the immediate-disconnect action. */
final class WifiDisconnectPolicy {
    static final long COUNTDOWN_MS = 7_000;
    static final long COOLDOWN_MS = 60_000;
    enum Decision { NONE, WARN, WAIT, CANCEL, DISCONNECT }

    private String bssid;
    private int threshold;
    private long deadline;
    private long nextDisconnectAt;

    Decision sample(String currentBssid, int rssi, int currentThreshold, long now) {
        boolean wasPending = pending();
        if (currentBssid == null || rssi > currentThreshold || now < nextDisconnectAt) {
            clear();
            return wasPending ? Decision.CANCEL : Decision.NONE;
        }
        if (!pending() || !Objects.equals(bssid, currentBssid) || threshold != currentThreshold) {
            bssid = currentBssid;
            threshold = currentThreshold;
            deadline = now + COUNTDOWN_MS;
            return Decision.WARN;
        }
        if (now >= deadline) {
            commit(now);
            return Decision.DISCONNECT;
        }
        return Decision.WAIT;
    }

    boolean immediate(String currentBssid, int rssi, int currentThreshold, long now) {
        if (!pending() || !Objects.equals(bssid, currentBssid) || threshold != currentThreshold
                || rssi > currentThreshold || now < nextDisconnectAt) return false;
        commit(now);
        return true;
    }

    private void commit(long now) {
        nextDisconnectAt = now + COOLDOWN_MS;
        clear();
    }

    boolean pending() { return bssid != null; }
    long deadline() { return deadline; }
    void clear() { bssid = null; deadline = 0; }
}
