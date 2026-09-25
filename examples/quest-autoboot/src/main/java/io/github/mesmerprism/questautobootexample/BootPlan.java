package io.github.mesmerprism.questautobootexample;

/** Pure decision boundary shared by boot execution and host tests. */
final class BootPlan {
    static final long DEADLINE_MS = 240_000L;

    enum Step { WAIT, LAUNCH, STOP_DISABLED, STOP_TARGET, STOP_EXPIRED }

    static Step next(boolean enabled, boolean targetAvailable, boolean bootReady,
                     boolean interactive, boolean waitForWearer, boolean worn, long ageMs) {
        if (!enabled) return Step.STOP_DISABLED;
        if (!targetAvailable) return Step.STOP_TARGET;
        if (ageMs < 0 || ageMs >= DEADLINE_MS) return Step.STOP_EXPIRED;
        if (!bootReady || (waitForWearer && (!interactive || !worn))) return Step.WAIT;
        return Step.LAUNCH;
    }

    private BootPlan() { }
}
