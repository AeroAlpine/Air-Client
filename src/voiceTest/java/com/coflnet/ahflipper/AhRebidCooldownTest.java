package com.coflnet.ahflipper;

/** The bid macro's exponential rebid cooldown: long waits far from the end, milliseconds near it. */
public final class AhRebidCooldownTest {
    private AhRebidCooldownTest() {
    }

    private static final long MIN = 60_000L;

    public static void main(String[] args) {
        // defaults: 60-120 s with 2 h left, 1-5 ms in the last 5 min
        long farLow = cd(120 * MIN, 0.0);
        long farHigh = cd(120 * MIN, 1.0);
        check(farLow == 60_000L, "2 h left, shortest wait 60 s: " + farLow);
        check(farHigh == 120_000L, "2 h left, longest wait 120 s: " + farHigh);
        check(cd(10 * 60 * MIN, 0.5) == cd(120 * MIN, 0.5), "more than 2 h: same as 2 h");

        long nearLow = cd(5 * MIN, 0.0);
        long nearHigh = cd(5 * MIN, 1.0);
        check(nearLow == 1L, "last 5 min, shortest wait 1 ms: " + nearLow);
        check(nearHigh == 5L, "last 5 min, longest wait 5 ms: " + nearHigh);
        check(cd(30_000L, 0.0) == 1L && cd(0L, 1.0) == 5L, "under 5 min: still 1-5 ms, never lower");

        // exponential in between, always increasing with the time left
        long previous = 0;
        for (long minutes = 5; minutes <= 120; minutes += 5) {
            long wait = cd(minutes * MIN, 0.5);
            check(wait >= previous, "wait grows with time left at " + minutes + " min: " + wait);
            previous = wait;
        }
        long hour = cd(60 * MIN, 0.5);
        check(hour > 3_000L && hour < 20_000L, "1 h left: some seconds, got " + hour);
        long halfHour = cd(30 * MIN, 0.5);
        check(halfHour > 300L && halfHour < 3_000L, "30 min left: about a second, got " + halfHour);
        long tenMin = cd(10 * MIN, 0.5);
        check(tenMin < 300L, "10 min left: fast, got " + tenMin);

        System.out.println("PASS: rebid cooldown 60-120 s at 2 h, 1-5 ms in the last 5 min, exponential between");
    }

    private static long cd(long remainingMs, double random) {
        return AhBidMacro.rebidCooldownMs(remainingMs, 60_000.0, 120_000.0, 1.0, 5.0, random);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
