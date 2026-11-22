package io.github.bandbridge.core.proto;

import io.github.bandbridge.core.testing.T;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Self-test for the android-free helpers in {@link HuaweiUtil}: wall-clock parsing
 * and the "time + time-zone id" blob the band receives during device init.
 */
public final class HuaweiUtilSelfTest {

    private HuaweiUtilSelfTest() {}

    private static final long FIXED_MILLIS = 1700000000000L; // 2023-11-14T22:13:20Z

    public static void run() {
        groupTimeToByte();
        groupTimeAndZoneId();
        groupNegativeOffsetQuirk();
    }

    private static void groupTimeToByte() {
        T.group("HuaweiUtil.timeToByte (HH:mm -> {hour, minute})");

        T.hex("00:00", "00 00", HuaweiUtil.timeToByte("00:00"));
        T.hex("23:59", "17 3B", HuaweiUtil.timeToByte("23:59"));
        T.hex("07:05", "07 05", HuaweiUtil.timeToByte("07:05"));
        T.hex("12:34", "0C 22", HuaweiUtil.timeToByte("12:34"));

        T.eq("garbage returns null", null, HuaweiUtil.timeToByte("not-a-time"));
        T.eq("empty string returns null", null, HuaweiUtil.timeToByte(""));
    }

    private static void groupTimeAndZoneId() {
        T.group("HuaweiUtil.getTimeAndZoneId (epoch + offset + zone id)");

        byte[] utc = HuaweiUtil.getTimeAndZoneId(fixedCalendar("UTC"));
        T.eq("UTC blob length", 6 + "UTC".length(), utc.length);
        T.bytes("UTC blob", expectedBlob((int) (FIXED_MILLIS / 1000), 0, 0, "UTC"), utc);

        byte[] shanghai = HuaweiUtil.getTimeAndZoneId(fixedCalendar("Asia/Shanghai"));
        T.eq("Asia/Shanghai blob length", 6 + "Asia/Shanghai".length(), shanghai.length);
        T.bytes("Asia/Shanghai blob (UTC+8, whole hours)",
                expectedBlob((int) (FIXED_MILLIS / 1000), 8, 0, "Asia/Shanghai"), shanghai);

        byte[] kathmandu = HuaweiUtil.getTimeAndZoneId(fixedCalendar("Asia/Kathmandu"));
        T.bytes("Asia/Kathmandu blob (UTC+5:45)",
                expectedBlob((int) (FIXED_MILLIS / 1000), 5, 45, "Asia/Kathmandu"), kathmandu);

        // The epoch field is the second-precision timestamp, big-endian.
        T.hex("epoch seconds field", "65 53 F1 00", new byte[]{utc[0], utc[1], utc[2], utc[3]});
    }

    private static void groupNegativeOffsetQuirk() {
        T.group("getTimeAndZoneId west-of-UTC behaviour (pinned, not fixed)");

        // 2023-11-14 is after the US DST change, so Newfoundland is UTC-03:30 (NST).
        Calendar newfoundland = fixedCalendar("America/St_Johns");
        byte[] blob = HuaweiUtil.getTimeAndZoneId(newfoundland);

        // hours: -(-12600s) / 3600 + 128 = 131 (the sign is folded into the +128)
        T.eq("UTC-3:30 hours byte", (byte) 131, blob[4]);
        // minutes: -12600 / 60 % 60 = -30, written as a negative byte.
        // This is the current wire behaviour of the ported code; it is pinned here
        // so a future "cleanup" shows up as a test failure instead of silently
        // changing what the band receives.
        T.eq("UTC-3:30 minutes byte", (byte) -30, blob[5]);
        T.eq("UTC-3:30 zone id", "America/St_Johns",
                new String(blob, 6, blob.length - 6, StandardCharsets.UTF_8));
    }

    private static Calendar fixedCalendar(String timeZoneId) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(timeZoneId));
        calendar.setTimeInMillis(FIXED_MILLIS);
        return calendar;
    }

    private static byte[] expectedBlob(int epochSeconds, int hoursByte, int minutesByte, String zoneId) {
        byte[] id = zoneId.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(6 + id.length)
                .putInt(epochSeconds)
                .put((byte) hoursByte)
                .put((byte) minutesByte)
                .put(id)
                .array();
    }
}
