public final class WifiDisconnectPolicyTest {
    public static void main(String[] args) {
        WifiDisconnectPolicy p = new WifiDisconnectPolicy();
        expect(p.sample("ap-a", -84, -80, 0), "WARN");
        expect(p.sample("ap-a", -85, -80, 6999), "WAIT");
        expect(p.sample("ap-a", -85, -80, 7000), "DISCONNECT");
        expect(p.sample("ap-a", -90, -80, 66999), "NONE");
        expect(p.sample("ap-a", -90, -80, 67000), "WARN");
        expect(p.sample("ap-a", -70, -80, 68000), "CANCEL");
        expect(p.sample("ap-a", -70, -80, 75000), "NONE");

        p = new WifiDisconnectPolicy();
        expect(p.sample("ap-a", -80, -80, 1000), "WARN");
        expect(p.sample("ap-b", -84, -80, 5000), "WARN");
        expect(p.sample("ap-b", -84, -80, 8000), "WAIT");
        if (p.immediate("ap-a", -90, -80, 8000)) fail("Stale AP action accepted");
        if (p.immediate("ap-b", -70, -80, 8000)) fail("Recovered signal action accepted");
        if (p.immediate("ap-b", -90, -85, 8000)) fail("Stale threshold action accepted");
        if (!p.immediate("ap-b", -90, -80, 8000)) fail("Current immediate action rejected");
        if (p.immediate("ap-b", -90, -80, 8001)) fail("Duplicate action accepted");
        expect(p.sample("ap-b", -90, -80, 9000), "NONE");

        p = new WifiDisconnectPolicy();
        expect(p.sample("ap-a", -90, -80, 0), "WARN");
        expect(p.sample("ap-a", -90, -85, 6000), "WARN");
        expect(p.sample("ap-a", -90, -85, 7000), "WAIT");
        expect(p.sample("ap-a", -90, -85, 13000), "DISCONNECT");
        p.clear();
        expect(p.sample("ap-a", -90, -85, 14000), "NONE");

        p = new WifiDisconnectPolicy();
        expect(p.sample("ap-a", -90, -80, 0), "WARN");
        p.clear();
        if (p.immediate("ap-a", -90, -80, 1000)) fail("Lost-network action accepted");
        expect(p.sample("ap-a", -90, -80, 2000), "WARN");
        expect(p.sample(null, -90, -80, 3000), "CANCEL");
        System.out.println("PASS: countdown, recovery, AP change, threshold change, cooldown and stale actions");
    }
    private static void expect(WifiDisconnectPolicy.Decision actual, String expected) {
        if (!actual.name().equals(expected)) fail("Expected " + expected + ", got " + actual);
    }
    private static void fail(String message) { throw new AssertionError(message); }
}
