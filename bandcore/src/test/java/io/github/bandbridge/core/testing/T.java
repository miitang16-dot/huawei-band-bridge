package io.github.bandbridge.core.testing;

import java.util.Arrays;
import java.util.Objects;

/**
 * Zero-dependency assertion helper for the offline bandcore self-test.
 *
 * Why not JUnit: the repo must stay buildable offline (see docs/TESTING.md).
 * Gradle has no JUnit artifacts in the local cache, so the protocol tests are
 * plain {@code main()}-driven classes that print {@code ok}/{@code FAIL} lines
 * and exit non-zero on failure. Nothing here is shipped in the APK.
 */
public final class T {
    private static int passed = 0;
    private static int failed = 0;
    private static int checks = 0;

    private T() {}

    /** A named group of assertions (printed as a section in the output). */
    public static void group(String name) {
        System.out.println();
        System.out.println("== " + name);
    }

    public static void ok(String what) {
        checks++;
        passed++;
        System.out.println("  ok   " + what);
    }

    public static void fail(String what, String detail) {
        checks++;
        failed++;
        System.out.println("  FAIL " + what + " -- " + detail);
    }

    public static void eq(String what, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            ok(what);
        } else {
            fail(what, "expected <" + expected + "> got <" + actual + ">");
        }
    }

    public static void bytes(String what, byte[] expected, byte[] actual) {
        if (Arrays.equals(expected, actual)) {
            ok(what);
        } else {
            fail(what, "expected " + hex(expected) + " got " + hex(actual));
        }
    }

    /** expectedHex like "5A 00 03", spaces optional. */
    public static void hex(String what, String expectedHex, byte[] actual) {
        byte[] expected = fromHex(expectedHex);
        bytes(what + " (" + expectedHex.trim() + ")", expected, actual);
    }

    public static void yes(String what, boolean condition) {
        if (condition) ok(what);
        else fail(what, "expected true, got false");
    }

    public static void no(String what, boolean condition) {
        if (!condition) ok(what);
        else fail(what, "expected false, got true");
    }

    /** Assert that {@code body} throws exactly/at least a {@code type}. */
    public static <X extends Throwable> X thrown(String what, Class<X> type, Body body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                ok(what + " -> " + shortTypeName(t));
                return type.cast(t);
            }
            fail(what, "expected " + type.getSimpleName() + " but got " + t.getClass().getName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            return null;
        }
        fail(what, "expected " + type.getSimpleName() + " but nothing was thrown");
        return null;
    }

    /** Anonymous subclasses (used by HuaweiTLV) have an empty simple name. */
    private static String shortTypeName(Throwable t) {
        String simple = t.getClass().getSimpleName();
        if (!simple.isEmpty()) return simple;
        return t.getClass().getSuperclass().getSimpleName();
    }

    public static int checks() {
        return checks;
    }

    public static int passes() {
        return passed;
    }

    public static int failures() {
        return failed;
    }

    /** Print the final tally. Returns the process exit code (0 = all green). */
    public static int summary() {
        System.out.println();
        System.out.println("------------------------------------------------------------");
        if (failed == 0) {
            System.out.println("PASS  " + passed + "/" + checks + " checks");
        } else {
            System.out.println("FAIL  " + failed + " failed, " + passed + " passed, " + checks + " checks");
        }
        System.out.println("------------------------------------------------------------");
        return failed == 0 ? 0 : 1;
    }

    public static String hex(byte[] data) {
        if (data == null) return "<null>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return data.length == 0 ? "<empty>" : sb.toString();
    }

    public static byte[] fromHex(String hex) {
        String s = hex.replaceAll("\\s+", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** A piece of test code that may throw. */
    public interface Body {
        void run() throws Throwable;
    }
}
