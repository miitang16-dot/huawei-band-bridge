package io.github.bandbridge;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The app's small UI kit: colour tokens for light / dark, type styles, buttons, grouped cards,
 * and the edge-to-edge scaffold that keeps content clear of the status bar and navigation bar.
 * Plain framework views only (the project has no libraries).
 *
 * <p>Layout idea: section labels sit outside the cards, cards hold rows split by hairlines,
 * and only the connection card on the main screen gets a tinted background.</p>
 */
final class Ui {

    /* ── tokens ─────────────────────────────────────────────────────────────── */

    static final class Theme {
        final boolean dark;
        final int bg, surface, surface2, line, ink, sub, faint, accent, onAccent, accentSoft, good, warn, bad, track;

        Theme(Context c) {
            dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            // neutrals lean slightly rose so they sit with the pink accent instead of reading as default grey
            bg       = dark ? 0xFF131013 : 0xFFF6F2F3;
            surface  = dark ? 0xFF1E1A1D : 0xFFFFFFFF;
            surface2 = dark ? 0xFF2A2428 : 0xFFF2ECEE;
            line     = dark ? 0xFF302A2E : 0xFFEBE3E6;
            ink      = dark ? 0xFFF2ECEE : 0xFF221B1E;
            sub      = dark ? 0xFFAA9EA3 : 0xFF6E6267;
            faint    = dark ? 0xFF7D7277 : 0xFF9D9196;
            accent   = dark ? 0xFFEE8CA5 : 0xFFD25C7A;
            onAccent = dark ? 0xFF2A1219 : 0xFFFFFFFF;
            accentSoft = Color.argb(dark ? 46 : 30, Color.red(accent), Color.green(accent), Color.blue(accent));
            good     = dark ? 0xFF6FC497 : 0xFF2F9A68;
            warn     = dark ? 0xFFF0C46A : 0xFFC08417;
            bad      = dark ? 0xFFE98A7A : 0xFFC9533F;
            track    = dark ? 0xFF2C262A : 0xFFEFE8EA;
        }
    }

    final Activity a;
    final Theme t;
    private final float density;

    Ui(Activity a) {
        this.a = a;
        this.t = new Theme(a);
        this.density = a.getResources().getDisplayMetrics().density;
    }

    int dp(float v) { return Math.round(v * density); }

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    static final Typeface MONO = Typeface.MONOSPACE;

    /* ── scaffold: edge to edge, fixed top bar, scrolling body ──────────────── */

    /** Root = vertical [top bar][scroll]. The top bar takes the status-bar inset, the scroll takes the bottom inset (nav bar or keyboard). */
    static final class Screen {
        final LinearLayout root, bar, body;
        final ScrollView scroll;
        Screen(LinearLayout root, LinearLayout bar, ScrollView scroll, LinearLayout body) {
            this.root = root; this.bar = bar; this.scroll = scroll; this.body = body;
        }
    }

    Screen screen() {
        Window w = a.getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) { w.setStatusBarContrastEnforced(false); w.setNavigationBarContrastEnforced(false); }
        // getDecorView() first: on Android 16 getInsetsController() throws (NPE inside PhoneWindow)
        // when it is called before the window's decor view exists.
        w.getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            try {
                WindowInsetsController ic = w.getInsetsController();
                if (ic != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    ic.setSystemBarsAppearance(t.dark ? 0 : mask, mask);
                }
            } catch (Throwable ignored) { /* bar icon colour is cosmetic; the theme's windowLightStatusBar still applies */ }
        } else {
            int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!t.dark) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(f);
        }

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(t.bg);

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(t.bg);
        bar.setMinimumHeight(dp(56));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        View hair = new View(a);
        hair.setBackgroundColor(t.line);
        hair.setAlpha(0f);
        root.addView(hair, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))));

        ScrollView sv = new ScrollView(a);
        sv.setClipToPadding(false);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        // the hairline under the bar appears once the content scrolls under it
        sv.setOnScrollChangeListener((v, x, y, ox, oy) -> hair.setAlpha(Math.min(1f, y / (float) dp(12))));

        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = in.getInsets(WindowInsets.Type.ime());
                top = i.top; bottom = Math.max(i.bottom, ime.bottom); left = i.left; right = i.right;
            } else {
                top = in.getSystemWindowInsetTop(); bottom = in.getSystemWindowInsetBottom();
                left = in.getSystemWindowInsetLeft(); right = in.getSystemWindowInsetRight();
            }
            bar.setPadding(left + dp(8), top, right + dp(8), 0);
            body.setPadding(left + dp(16), dp(4), right + dp(16), bottom + dp(28));
            return in;
        });
        a.setContentView(root);
        root.requestApplyInsets();
        return new Screen(root, bar, sv, body);
    }

    /**
     * Force the whole screen to lay out and redraw after a data update. On her HarmonyOS phone
     * (edge-to-edge, transparent bars) changed text did not reach the screen until she scrolled,
     * so a plain setText() is not trusted to repaint by itself.
     */
    void repaint(Screen s) {
        if (s == null) return;
        s.root.requestLayout();
        invalidateTree(s.root);
    }

    private static void invalidateTree(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }

    /** Title in the top bar, with an optional back button in front of it. */
    TextView barTitle(Screen s, String title, boolean back) {
        if (back) {
            View b = iconButton(new Chevron(a, t.ink, true));
            b.setContentDescription("返回");
            b.setOnClickListener(v -> a.finish());
            s.bar.addView(b, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        TextView tv = text(title, back ? 18 : 22, t.ink, back ? MEDIUM : BOLD);
        tv.setPadding(back ? dp(4) : dp(8), 0, 0, 0);
        s.bar.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        return tv;
    }

    /* ── text ───────────────────────────────────────────────────────────────── */

    TextView text(CharSequence s, float sp, int color, Typeface face) {
        TextView v = new TextView(a);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face == null ? Typeface.DEFAULT : face);
        v.setLineSpacing(0, 1.25f);
        v.setIncludeFontPadding(false);
        v.setFontFeatureSettings("tnum");
        return v;
    }

    TextView body(CharSequence s) { return text(s, 14, t.ink, null); }
    TextView meta(CharSequence s) { return text(s, 13, t.sub, null); }
    TextView small(CharSequence s) { return text(s, 12, t.faint, null); }

    /** A section label above a card ("心跳直播"). */
    TextView section(LinearLayout parent, String label) {
        TextView v = text(label, 13, t.sub, MEDIUM);
        v.setLetterSpacing(0.04f);
        v.setPadding(dp(4), dp(22), dp(4), dp(8));
        parent.addView(v);
        return v;
    }

    /* ── surfaces ───────────────────────────────────────────────────────────── */

    GradientDrawable round(int fill, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** A plain card: surface fill, 1px line border, 16dp corners. Rows go inside. */
    LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = round(t.surface, 16);
        g.setStroke(Math.max(1, dp(0.75f)), t.line);
        c.setBackground(g);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        parent.addView(c, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    /** A card made of rows (no inner padding; each row pads itself) — like a settings group. */
    LinearLayout group(LinearLayout parent) {
        LinearLayout c = card(parent);
        c.setPadding(0, 0, 0, 0);
        return c;
    }

    void hairline(LinearLayout group) {
        View v = new View(a);
        v.setBackgroundColor(t.line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.75f)));
        lp.leftMargin = dp(16);
        group.addView(v, lp);
    }

    /** Row inside a group: title + optional subtitle on the left, an optional trailing view. Returns the subtitle view. */
    TextView row(LinearLayout group, String title, String subtitle, View trailing, View.OnClickListener onClick) {
        if (group.getChildCount() > 0) hairline(group);
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(13), dp(12), dp(13));
        r.setMinimumHeight(dp(56));
        LinearLayout txt = new LinearLayout(a);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(text(title, 15, t.ink, MEDIUM));
        TextView st = meta(subtitle == null ? "" : subtitle);
        st.setPadding(0, dp(3), 0, 0);
        st.setVisibility(subtitle == null ? View.GONE : View.VISIBLE);
        txt.addView(st);
        r.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        if (trailing != null) {
            ViewGroup.LayoutParams own = trailing.getLayoutParams();
            LinearLayout.LayoutParams lp = own instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) own : new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(12);
            r.addView(trailing, lp);
        }
        if (onClick != null) {
            r.setOnClickListener(onClick);
            r.setBackground(ripple(Color.TRANSPARENT, 0));
        }
        group.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return st;
    }

    /* ── buttons ────────────────────────────────────────────────────────────── */

    Drawable ripple(int fill, float radiusDp) {
        GradientDrawable content = round(fill, radiusDp);
        GradientDrawable mask = round(Color.WHITE, radiusDp);
        int rc = Color.argb(t.dark ? 40 : 28, Color.red(t.ink), Color.green(t.ink), Color.blue(t.ink));
        return new RippleDrawable(ColorStateList.valueOf(rc), content, mask);
    }

    private TextView btn(String label, int fill, int fg, View.OnClickListener l) {
        TextView b = text(label, 15, fg, MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinHeight(dp(46));
        b.setBackground(ripple(fill, 12));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    TextView primary(String label, View.OnClickListener l) { return btn(label, t.accent, t.onAccent, l); }
    TextView tonal(String label, View.OnClickListener l) { return btn(label, t.surface2, t.ink, l); }

    /** Small inline action for a row ("校准", "去开启"). */
    TextView chipButton(String label, View.OnClickListener l) {
        TextView b = text(label, 13, t.accent, MEDIUM);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(ripple(t.accentSoft, 999));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    /** A status pill: coloured dot + text. */
    TextView pill(String label, int color) {
        TextView v = text(label, 12, t.sub, MEDIUM);
        v.setPadding(dp(10), dp(5), dp(10), dp(5));
        v.setBackground(round(t.surface2, 999));
        setPill(v, label, color);
        return v;
    }

    void setPill(TextView v, String label, int color) {
        v.setText(label);
        Dot d = new Dot(color, dp(7));
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null);
        v.setCompoundDrawablePadding(dp(6));
    }

    /** Lay buttons side by side with equal widths and an 8dp gap. */
    LinearLayout buttons(LinearLayout parent, View... bs) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < bs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            r.addView(bs[i], lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        parent.addView(r, lp);
        return r;
    }

    static void enable(View v, boolean on) { v.setEnabled(on); v.setAlpha(on ? 1f : 0.4f); }

    /** Round 44dp icon button holding a drawn icon view. */
    View iconButton(View icon) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(a);
        f.setBackground(ripple(Color.TRANSPARENT, 999));
        f.setClickable(true);
        f.setFocusable(true);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER);
        f.addView(icon, lp);
        return f;
    }

    /* ── inputs ─────────────────────────────────────────────────────────────── */

    /** Labelled text field. */
    EditText field(LinearLayout parent, String label, String hint, String value, int type) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(label, 12, t.sub, MEDIUM);
        l.setPadding(dp(2), 0, 0, dp(6));
        box.addView(l);
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type == 0 ? InputType.TYPE_CLASS_TEXT : type);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setTextColor(t.ink);
        e.setHintTextColor(t.faint);
        e.setFontFeatureSettings("tnum");
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setMinHeight(dp(46));
        e.setBackground(round(t.surface2, 12));
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    /* ── drawn bits ─────────────────────────────────────────────────────────── */

    /** Filled circle drawable used in pills. */
    static final class Dot extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;
        Dot(int color, int size) { p.setColor(color); this.size = size; }
        @Override public void draw(Canvas c) { float r = size / 2f; c.drawCircle(getBounds().left + r, getBounds().top + r, r, p); }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** A chevron (‹ or ›) drawn with a round stroke, so it does not depend on font glyphs. */
    static final class Chevron extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean left;
        Chevron(Context c, int color, boolean left) {
            super(c);
            this.left = left;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStrokeWidth(Math.max(2f, w * 0.11f));
            Path path = new Path();
            float x0 = w * (left ? 0.62f : 0.38f), x1 = w * (left ? 0.32f : 0.68f);
            path.moveTo(x0, h * 0.18f);
            path.lineTo(x1, h * 0.5f);
            path.lineTo(x0, h * 0.82f);
            c.drawPath(path, p);
        }
    }
}
