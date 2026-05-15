package asia.lira.mercury.jit.runtime;

public final class MercuryJitRuntime {
    private static volatile boolean enabled = false;

    private MercuryJitRuntime() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean enabled) {
        MercuryJitRuntime.enabled = enabled;
    }
}
