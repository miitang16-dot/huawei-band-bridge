package io.github.bandbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.View;

/**
 * Charts for the health page. Plain {@link View} + {@link Canvas}, no libraries — the project rule.
 *
 * <p>Colours and shapes are copied from the Nexus health page (3.307: hero banner, six tiles,
 * record panes) so both apps look the same: hero tints 22% / 18%, tile tint 14% from the top-left,
 * card radii 20 / 16 / 18 dp, metric colours #EF8FA3 (heart rate), #7FB6E0 (SpO2), #F0B860 (steps),
 * #9C8FD6 (sleep), stress levels #8FC9A8 / #9CC3E6 / #F0C46A / #E98A7A and emotion
 * #F2A3B8 / #A9C8EA / #B3A4CC.</p>
 *
 * <p>Everything here is verified by compiling only; rendering was not checked on a device.</p>
 */
final class Charts {

    private Charts() { }

    /* ── palette (same values MainActivity uses, plus the per-metric accents) ── */

    static final class Palette {
        final boolean dark;
        final int bg, card, ink, sub, stroke, accent;
        static final int HR = 0xFFEF8FA3, SPO = 0xFF7FB6E0, STEP = 0xFFF0B860, SLEEP = 0xFF9C8FD6;
        static final int L1 = 0xFF8FC9A8, L2 = 0xFF9CC3E6, L3 = 0xFFF0C46A, L4 = 0xFFE98A7A;
        static final int E3 = 0xFFF2A3B8, E2 = 0xFFA9C8EA, E1 = 0xFFB3A4CC;

        Palette(boolean dark) {
            this.dark = dark;
            bg = dark ? 0xFF16131F : 0xFFFBF2F5;
            card = dark ? 0xFF221D21 : 0xFFFFFFFF;
            ink = dark ? 0xFFF1ECEE : 0xFF2B2326;
            sub = dark ? 0xFFA79CA1 : 0xFF7A6E73;
            stroke = dark ? Color.argb(28, 120, 80, 95) : Color.argb(34, 90, 62, 70);
            accent = 0xFFE58FA6;
        }

        static int stress(int level) { return level <= 1 ? L1 : level == 2 ? L2 : level == 3 ? L3 : level == 4 ? L4 : L1; }

        static int emotion(int code) { return code == 3 ? E3 : code == 2 ? E2 : code == 1 ? E1 : 0; }

        /** 0 = none, 1 relaxed, 2 normal, 3 medium, 4 high. */
        static int stressLevel(int v) { return v <= 0 ? 0 : v < 30 ? 1 : v < 60 ? 2 : v < 80 ? 3 : 4; }

        static String stressText(int v) { return v < 30 ? "放松" : v < 60 ? "正常" : v < 80 ? "中等" : "偏高"; }
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    private static int alpha(int color, float a) {
        return Color.argb(Math.max(0, Math.min(255, (int) (a * 255))), Color.red(color), Color.green(color), Color.blue(color));
    }

    /* ── card background: rounded fill + border, optional Nexus tint ── */

    static final class PanelBg extends android.graphics.drawable.Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path clip = new Path();
        private final float radius;
        private int color;
        private int tintColor;
        private float tintAlpha;
        private int heroPink, heroPurple;
        private boolean hero;

        PanelBg(int color, int strokeColor, float strokeDp, float radiusDp, float density) {
            this.color = color;
            this.radius = radiusDp * density;
            fill.setColor(color);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(strokeDp * density);
            stroke.setColor(strokeColor);
        }

        /** Nexus tile::before — linear gradient from the top-left, 14% opacity. */
        void setTileTint(int color, float a) {
            hero = false;
            tintColor = color;
            tintAlpha = a;
            tint.setShader(new LinearGradient(0, 0, 1, 1, alpha(color, a), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            invalidateSelf();
        }

        /** Nexus hero banner — pink radial top-right 22%, purple radial bottom-left 18%. */
        void setHeroTints(int pink, int purple) {
            hero = true;
            heroPink = pink;
            heroPurple = purple;
            invalidateSelf();
        }

        @Override public void draw(Canvas c) {
            r.set(getBounds());
            clip.reset();
            clip.addRoundRect(r, radius, radius, Path.Direction.CW);
            fill.setColor(color);
            c.drawPath(clip, fill);
            if (hero) {
                int save = c.save();
                c.clipPath(clip);
                Paint p = tint;
                float w = r.width(), h = r.height();
                p.setShader(new RadialGradient(r.right, r.top, Math.max(w, h) * 0.95f,
                        alpha(heroPink, 0.22f), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                c.drawRect(r, p);
                p.setShader(new RadialGradient(r.left, r.bottom, Math.max(w, h) * 0.85f,
                        alpha(heroPurple, 0.18f), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                c.drawRect(r, p);
                c.restoreToCount(save);
            } else if (tintColor != 0) {
                int save = c.save();
                c.clipPath(clip);
                tint.setShader(new LinearGradient(r.left, r.top, r.left + r.width() * 0.7f, r.top + r.height() * 1.35f,
                        alpha(tintColor, tintAlpha), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                c.drawRect(r, tint);
                c.restoreToCount(save);
            }
            c.drawPath(clip, stroke);
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { fill.setColorFilter(cf); }
    }

    /* ── tiny views: coloured dot / emotion orb / progress bar ── */

    static final class DotView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = 0xFFC9C3C6;
        private boolean glow;

        DotView(Context c) { super(c); }

        void setColor(int color, boolean glow) { this.color = color; this.glow = glow; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, rad = Math.min(cx, cy);
            if (glow) {
                p.setColor(alpha(color, 0.25f));
                c.drawCircle(cx, cy, rad * 2f, p);
            }
            p.setColor(color);
            c.drawCircle(cx, cy, rad, p);
        }
    }

    /** Emotion orb: Nexus .bm-orb / .hv-orb — a coloured ball with a highlight at 35% 30%. */
    static final class OrbView extends View {
        private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hi = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color;
        private boolean none = true;

        OrbView(Context c) { super(c); }

        void setColor(int color) { this.color = color; this.none = color == 0; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, rad = Math.min(cx, cy);
            if (none) {
                base.setShader(new RadialGradient(cx, cy, rad, alpha(0xFFFFFFFF, 0.10f), alpha(0xFFFFFFFF, 0.04f), Shader.TileMode.CLAMP));
            } else {
                base.setShader(new RadialGradient(cx, cy, rad, color, alpha(color, 0f), Shader.TileMode.CLAMP));
                hi.setColor(alpha(0xFFFFFFFF, 0.55f));
                c.drawCircle(cx - rad * 0.15f, cy - rad * 0.25f, rad * 0.42f, hi);
            }
            c.drawCircle(cx, cy, rad, base);
        }
    }

    /** Thin progress track (Nexus .hv-bar): 6dp, rounded, filled from the left. */
    static final class MiniBarView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float fraction;
        private int color, trackColor;

        MiniBarView(Context c) { super(c); }

        void setFraction(float f) { this.fraction = Math.max(0, Math.min(1, f)); invalidate(); }

        void setColors(int color, int trackColor) { this.color = color; this.trackColor = trackColor; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), rad = h / 2f;
            track.setColor(trackColor);
            c.drawRoundRect(new RectF(0, 0, getWidth(), h), rad, rad, track);
            if (fraction <= 0) return;
            fill.setColor(color);
            c.drawRoundRect(new RectF(0, 0, getWidth() * fraction, h), rad, rad, fill);
        }
    }

    /* ── heart-rate tile sparkline (Nexus .hv-spark: today in 15-minute bins) ── */

    static final class SparkView extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int[] vals = new int[0];
        private int color;

        SparkView(Context c) { super(c); line.setStyle(Paint.Style.STROKE); line.setStrokeCap(Paint.Cap.ROUND); line.setStrokeJoin(Paint.Join.ROUND); }

        void setData(int[] vals, int color) { this.vals = vals == null ? new int[0] : vals; this.color = color; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            if (vals.length < 2) return;
            float w = getWidth(), h = getHeight();
            line.setColor(color);
            line.setStrokeWidth(dp(getContext(), 1.6f));
            int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
            for (int v : vals) { if (v > 0) { mn = Math.min(mn, v); mx = Math.max(mx, v); } }
            if (mn == Integer.MAX_VALUE) return;
            if (mx - mn < 10) { mn -= 5; mx += 5; }
            Path p = new Path();
            boolean started = false;
            for (int i = 0; i < vals.length; i++) {
                if (vals[i] <= 0) { started = false; continue; }
                float x = i / (float) (vals.length - 1) * w;
                float y = h - 1 - (vals[i] - mn) / (float) (mx - mn) * (h - 2);
                if (started) p.lineTo(x, y); else { p.moveTo(x, y); started = true; }
            }
            c.drawPath(p, line);
        }
    }

    /* ── today's heart rate, 24 h line (Nexus BandHistory .bh-chart) ── */

    static final class HrLineView extends View {
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long dayStart;
        private long now;
        private long[] t = new long[0];
        private int[] v = new int[0];
        private int color, gridColor, cardColor;

        HrLineView(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
        }

        void setData(long dayStart, long now, long[] t, int[] v, int color, int gridColor, int cardColor) {
            this.dayStart = dayStart; this.now = Math.max(now, dayStart + 60_000L);
            this.t = t; this.v = v;
            this.color = color; this.gridColor = gridColor; this.cardColor = cardColor;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            bgPaint.setColor(alpha(color, 0.05f));
            c.drawRoundRect(new RectF(0, 0, w, h), dp(getContext(), 8), dp(getContext(), 8), bgPaint);

            grid.setColor(gridColor);
            grid.setStrokeWidth(1);
            for (int hour : new int[]{6, 12, 18}) {
                float x = hour / 24f * w;
                c.drawLine(x, 0, x, h, grid);
            }
            if (t.length == 0) return;
            line.setColor(color);
            line.setStrokeWidth(dp(getContext(), 1.6f));
            // same mapping as Nexus: value 40–180 across the height, 11-minute gaps break the line
            long span = Math.max(1L, 24L * 3600_000L);
            Path p = new Path();
            long prev = 0;
            float lastX = 0, lastY = 0;
            boolean any = false;
            for (int i = 0; i < t.length; i++) {
                if (v[i] <= 0) continue;
                long tt = t[i];
                if (tt < dayStart || tt > dayStart + span) continue;
                float frac = (tt - dayStart) / (float) span;
                float x = frac * w;
                float y = h - 2 - clamp((v[i] - 40f) / 140f) * (h - 4);
                if (prev > 0 && tt - prev <= 11 * 60_000L) p.lineTo(x, y);
                else p.moveTo(x, y);
                prev = tt; lastX = x; lastY = y; any = true;
            }
            if (!any) return;
            c.drawPath(p, line);
            // the "current" point: newest sample, ringed so it reads on top of the line
            dot.setColor(cardColor);
            c.drawCircle(lastX, lastY, dp(getContext(), 4.6f), dot);
            dot.setColor(color);
            c.drawCircle(lastX, lastY, dp(getContext(), 3.1f), dot);
        }

        private static float clamp(float f) { return f < 0 ? 0 : f > 1 ? 1 : f; }
    }

    /* ── sleep stages for one night (Nexus BandHistory .bh-hyp) ── */

    static final class SleepStageView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private long[] start = new long[0];
        private int[] min = new int[0];
        private int[] code = new int[0];
        private long fall, wake;
        private int sleepColor, trackColor;
        private boolean dark;

        SleepStageView(Context c) { super(c); }

        void setData(long fall, long wake, long[] start, int[] min, int[] code, int sleepColor, int trackColor, boolean dark) {
            this.fall = fall; this.wake = wake; this.start = start; this.min = min; this.code = code;
            this.sleepColor = sleepColor; this.trackColor = trackColor; this.dark = dark;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), rad = Math.min(dp(getContext(), 6), h / 2f);
            track.setColor(trackColor);
            r.set(0, 0, getWidth(), h);
            c.drawRoundRect(r, rad, rad, track);
            if (wake <= fall || start.length == 0) return;
            float span = wake - fall;
            float gap = dp(getContext(), 0.7f);
            for (int i = 0; i < start.length; i++) {
                if (min[i] <= 0) continue;
                float x0 = Math.max(0, (start[i] - fall) / span * getWidth());
                float x1 = Math.min(getWidth(), (start[i] + min[i] * 60_000L - fall) / span * getWidth());
                if (x1 - x0 < 1) x1 = x0 + 1;
                p.setColor(stageColor(code[i]));
                r.set(x0 + (i == 0 ? 0 : gap), 0, x1, h);
                float rr = Math.min(rad, (x1 - x0) / 2f);
                c.drawRoundRect(r, rr, rr, p);
            }
        }

        private int stageColor(int code) {
            switch (code) {
                case 3: return mix(sleepColor, 0xFF3A2A6A, 0.05f);              // deep: sleep colour into dark violet
                case 1: return alpha(sleepColor, dark ? 0.55f : 0.62f);         // light
                case 2: return 0xFF8FB7E8;                                      // REM
                case 4: return 0xFFF0C46A;                                      // awake
                default: return alpha(sleepColor, 0.30f);
            }
        }
    }

    private static int mix(int a, int b, float f) {
        return Color.rgb((int) (Color.red(a) * (1 - f) + Color.red(b) * f),
                (int) (Color.green(a) * (1 - f) + Color.green(b) * f),
                (int) (Color.blue(a) * (1 - f) + Color.blue(b) * f));
    }

    /* ── stress over a day (Nexus BandMood .bm-plot: four level bands, dots, dashed line) ── */

    static final class StressTimelineView extends View {
        private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long dayStart;
        private long[] t = new long[0];
        private int[] v = new int[0];
        private int cardColor;

        StressTimelineView(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(c, 1.2f));
            line.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(c, 3), dp(c, 4)}, 0));
        }

        void setData(long dayStart, long[] t, int[] v, int cardColor) {
            this.dayStart = dayStart; this.t = t; this.v = v; this.cardColor = cardColor;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            band.setColor(alpha(Palette.L4, 0.16f)); c.drawRect(0, 0, w, h * 0.20f, band);
            band.setColor(alpha(Palette.L3, 0.16f)); c.drawRect(0, h * 0.20f, w, h * 0.40f, band);
            band.setColor(alpha(Palette.L2, 0.16f)); c.drawRect(0, h * 0.40f, w, h * 0.70f, band);
            band.setColor(alpha(Palette.L1, 0.16f)); c.drawRect(0, h * 0.70f, w, h, band);
            if (t.length == 0) return;
            line.setColor(Color.argb(0x4D, 0x80, 0x80, 0x80));
            Path p = new Path();
            for (int i = 0; i < t.length; i++) {
                float x = xOf(t[i], w), y = yOf(v[i], h);
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            if (t.length > 1) c.drawPath(p, line);
            for (int i = 0; i < t.length; i++) {
                float x = xOf(t[i], w), y = yOf(v[i], h);
                ring.setColor(cardColor);
                c.drawCircle(x, y, dp(getContext(), 4.7f), ring);
                dot.setColor(Palette.stress(Palette.stressLevel(v[i])));
                c.drawCircle(x, y, dp(getContext(), 3.6f), dot);
            }
        }

        private float xOf(long tms, float w) {
            float f = (tms - dayStart) / 86_400_000f;
            f = f < 0 ? 0 : f > 1 ? 1 : f;
            return dp(getContext(), 4) + f * (w - dp(getContext(), 8));
        }

        private float yOf(int v, float h) {
            float f = Math.max(0, Math.min(100, v)) / 100f;
            return h - f * h;
        }
    }

    /* ── emotion blocks over a day (Nexus BandMood .bm-strip) ── */

    static final class EmotionStripView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private long dayStart;
        private long[] start = new long[0];
        private long[] end = new long[0];
        private int[] code = new int[0];
        private int trackColor;

        EmotionStripView(Context c) { super(c); }

        void setData(long dayStart, long[] start, long[] end, int[] code, int trackColor) {
            this.dayStart = dayStart; this.start = start; this.end = end; this.code = code; this.trackColor = trackColor;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = dp(getContext(), 8);
            track.setColor(trackColor);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, rad, rad, track);
            for (int i = 0; i < start.length; i++) {
                float x0 = Math.max(0, (start[i] - dayStart) / 86_400_000f * w);
                long e = end[i] > start[i] ? end[i] : start[i] + 60_000L;
                float x1 = Math.min(w, (e - dayStart) / 86_400_000f * w);
                if (x1 - x0 < dp(getContext(), 2)) x1 = x0 + dp(getContext(), 2);
                p.setColor(Palette.emotion(code[i]));
                float rr = Math.min(dp(getContext(), 3), (x1 - x0) / 2);
                r.set(x0, 0, x1, h);
                c.drawRoundRect(r, rr, rr, p);
            }
        }
    }

    /* ── steps per hour (Nexus BandHistory .bh-bars) ── */

    static final class HourBarsView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int[] hours = new int[24];
        private int color;

        HourBarsView(Context c) { super(c); }

        void setData(int[] hours, int color) { this.hours = hours == null ? new int[24] : hours; this.color = color; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            int max = 0;
            for (int v : hours) max = Math.max(max, v);
            if (max <= 0) return;
            float slot = w / 24f;
            float barW = slot * 0.70f;
            float gap = (slot - barW) / 2f;
            p.setColor(color);
            float rad = dp(getContext(), 1.5f);
            for (int i = 0; i < 24; i++) {
                if (hours[i] <= 0) continue;
                float bh = Math.max(dp(getContext(), 1), hours[i] / (float) max * (h - 2));
                float x = i * slot + gap;
                c.drawRoundRect(new RectF(x, h - bh, x + barW, h), rad, rad, p);
            }
        }
    }
}
