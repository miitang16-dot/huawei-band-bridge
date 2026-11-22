package android.text;

/**
 * Minimal stand-in for android.text.TextUtils for the offline self-test.
 * Only {@code isEmpty} is referenced by the ported protocol code
 * (Notifications.java); see docs/TESTING.md.
 */
public final class TextUtils {
    private TextUtils() {}

    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }
}
