package com.coflnet.mining;

/**
 * SkyBlock calendar: a day lasts 20 real minutes, a month 31 days, a year 12 months.
 * Day 1 of year 1 began at Unix time 1560275700.
 */
public record SkyBlockClock(int year, int month, int day, int hour, int minute) {
    public static final long EPOCH = 1_560_275_700L;
    public static final long HOUR = 50;
    public static final long DAY = HOUR * 24;
    public static final long MONTH = DAY * 31;
    public static final long YEAR = MONTH * 12;

    public static SkyBlockClock now() {
        return at(System.currentTimeMillis() / 1000L);
    }

    public static SkyBlockClock at(long unixSeconds) {
        long t = Math.max(0, unixSeconds - EPOCH);
        int year = (int) (t / YEAR) + 1;
        t %= YEAR;
        int month = (int) (t / MONTH) + 1;
        t %= MONTH;
        int day = (int) (t / DAY) + 1;
        t %= DAY;
        int hour = (int) (t / HOUR);
        int minute = (int) ((t % HOUR) * 60 / HOUR);
        return new SkyBlockClock(year, month, day, hour, minute);
    }

    /** Unix seconds when the given SkyBlock day (1-based, may overflow into the next months) begins. */
    public static long startOf(int year, int month, int day, int hour) {
        long days = (long) (year - 1) * 372 + (long) (month - 1) * 31 + (day - 1);
        return EPOCH + days * DAY + hour * HOUR;
    }

    public long startOfToday() {
        return startOf(year, month, day, 0);
    }

    /** "12m 30s", "1h 05m" style. */
    public static String format(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return h + "h " + String.format("%02d", m) + "m";
        if (m > 0) return m + "m " + String.format("%02d", s) + "s";
        return s + "s";
    }
}
