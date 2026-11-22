package io.github.bandbridge.core.proto;

import io.github.bandbridge.core.testing.T;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Self-test for {@link CheckSums#getCRC16(byte[])} / {@link CheckSums#getCRC16(byte[], int)}.
 *
 * Three layers, so the test does not just restate the implementation:
 *  1. published check values for CRC-16/CCITT-FALSE and CRC-16/XMODEM;
 *  2. cross-check against an independently written bit-by-bit MSB-first
 *     implementation (polynomial 0x1021) over deterministic pseudo-random data;
 *  3. the incremental property the packet parser relies on: feeding a second
 *     chunk with the previous CRC as the starting value equals one-shot CRC.
 */
public final class CheckSumsSelfTest {

    private CheckSumsSelfTest() {}

    public static void run() {
        groupKnownVectors();
        groupCrossCheck();
        groupIncremental();
    }

    private static void groupKnownVectors() {
        T.group("CRC16 known vectors");

        byte[] check = "123456789".getBytes(StandardCharsets.US_ASCII);
        T.eq("CRC-16/CCITT-FALSE check value (init 0xFFFF)",
                0x29B1, CheckSums.getCRC16(check));
        T.eq("CRC-16/XMODEM check value (init 0x0000)",
                0x31C3, CheckSums.getCRC16(check, 0x0000));

        T.eq("empty input keeps the init value", 0xFFFF, CheckSums.getCRC16(new byte[0]));
        T.eq("empty input with init 0", 0x0000, CheckSums.getCRC16(new byte[0], 0x0000));
        T.eq("single 'A' byte", 0xB915, CheckSums.getCRC16(new byte[]{0x41}));

        // First six bytes of a Huawei frame: magic, length, unsliced marker, svc/cmd.
        byte[] framePrefix = {(byte) 0x5A, 0x00, 0x03, 0x00, 0x0A, 0x05};
        T.eq("Huawei frame prefix", 0xD224, CheckSums.getCRC16(framePrefix, 0x0000));

        // Result is always a 16-bit unsigned value, never sign-extended.
        T.yes("result is masked to 16 bits",
                CheckSums.getCRC16(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}) <= 0xFFFF);
    }

    private static void groupCrossCheck() {
        T.group("CRC16 vs independent bit-by-bit implementation (poly 0x1021)");

        Random random = new Random(0x5EED_2026L);
        boolean sameDefault = true;
        boolean sameCustomInit = true;
        int mismatches = 0;

        for (int trial = 0; trial < 500; trial++) {
            byte[] data = new byte[random.nextInt(64)];
            random.nextBytes(data);
            int init = random.nextInt(0x10000);

            int fast = CheckSums.getCRC16(data, init);
            int bitwise = referenceCrc16(data, init);
            if (fast != bitwise) mismatches++;
            if (CheckSums.getCRC16(data) != referenceCrc16(data, 0xFFFF)) sameDefault = false;
            if (fast != bitwise) sameCustomInit = false;
        }

        T.eq("mismatches over 500 deterministic random buffers", 0, mismatches);
        T.yes("getCRC16(data) uses init 0xFFFF", sameDefault);
        T.yes("getCRC16(data, init) matches reference for random starts", sameCustomInit);
    }

    private static void groupIncremental() {
        T.group("CRC16 is incremental (chunked == one-shot)");

        Random random = new Random(0xC0FFEE_21L);
        boolean ok = true;
        for (int trial = 0; trial < 200; trial++) {
            byte[] whole = new byte[random.nextInt(80) + 1];
            random.nextBytes(whole);
            int split = random.nextInt(whole.length);

            byte[] head = new byte[split];
            byte[] tail = new byte[whole.length - split];
            System.arraycopy(whole, 0, head, 0, split);
            System.arraycopy(whole, split, tail, 0, tail.length);

            int oneShot = CheckSums.getCRC16(whole);
            int chunked = CheckSums.getCRC16(tail, CheckSums.getCRC16(head));
            if (oneShot != chunked) ok = false;
        }
        T.yes("200 split points agree with one-shot CRC", ok);

        byte[] frame = {(byte) 0x5A, 0x00, 0x03, 0x00, 0x0A, 0x05};
        int full = CheckSums.getCRC16(frame, 0x0000);
        int first = CheckSums.getCRC16(new byte[]{0x5A, 0x00}, 0x0000);
        int rest = CheckSums.getCRC16(new byte[]{0x03, 0x00, 0x0A, 0x05}, first);
        T.eq("frame CRC split into 2-byte + 4-byte chunks", full, rest);
    }

    /** Independent reference: MSB-first, polynomial 0x1021, no reflection, no final xor. */
    private static int referenceCrc16(byte[] data, int init) {
        int crc = init & 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc;
    }
}
