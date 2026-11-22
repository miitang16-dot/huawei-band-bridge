package org.json;

/**
 * Self-test only stand-in for org.json.JSONException.
 *
 * The real org.json comes from the Android framework at runtime. The offline
 * self-test compiles the pure protocol sources on a plain JVM, where neither
 * android.* nor org.json exist; these stubs supply just enough API surface to
 * compile (and, for the paths the self-tests actually touch, to behave).
 */
public class JSONException extends Exception {
    public JSONException(String message) {
        super(message);
    }

    public JSONException(String message, Throwable cause) {
        super(message, cause);
    }

    public JSONException(Throwable cause) {
        super(cause);
    }
}
