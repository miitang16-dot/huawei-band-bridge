package io.github.bandbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;

/**
 * 心跳回放的指示器：只负责「按时间表闪」，不算任何数据。
 *
 * <p>时间表来自 {@code bandcore} 的 {@link io.github.bandbridge.core.proto.hr.BeatReplay}
 * （一包 RRI → 相对时刻 + 每拍心率）。手环是**攒几秒成批推送**的（真机约 4 秒一包，lag 5.9 秒），
 * 所以这里演的是「刚收到的那几拍」的回放 —— **不是「此刻这一拍」**，卡片上的文字也这么说。</p>
 *
 * <p>动画用 {@link View#postInvalidateOnAnimation()} 自己驱动，不占工作线程；没有新包时会在
 * 最后一拍结束后自动停（画一个静止的点）。</p>
 */
final class PulseView extends View {

    /** 一拍闪多久（环的淡出时长）。比最小心跳间隔长，允许两拍重叠。 */
    private static final long PULSE_MS = 900L;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accentColor, trackColor, faintColor;

    private int[] offsets = new int[0];
    private int[] bpms = new int[0];
    private long baseAt;
    private boolean running;

    PulseView(Context c, int accentColor, int trackColor, int faintColor) {
        super(c);
        this.accentColor = accentColor;
        this.trackColor = trackColor;
        this.faintColor = faintColor;
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2f));
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** 开始按 {@code offsetsMs}（第 0 拍 = 现在）回放；{@code bpmPerPulse} 可用来调点的呼吸幅度。 */
    void start(int[] offsetsMs, int[] bpmPerPulse) {
        offsets = offsetsMs == null ? new int[0] : offsetsMs;
        bpms = bpmPerPulse == null ? new int[0] : bpmPerPulse;
        baseAt = SystemClock.uptimeMillis();
        running = offsets.length > 0;
        invalidate();
    }

    /** 停止并画静止状态（测量结束 / 没数据时调用）。 */
    void stop() {
        running = false;
        invalidate();
    }

    boolean isRunning() { return running; }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - dp(2f);
        if (r <= 0) return;

        trackPaint.setColor(trackColor);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(1.5f));
        c.drawCircle(cx, cy, r, trackPaint);

        if (!running || offsets.length == 0) {
            dotPaint.setColor(faintColor);
            c.drawCircle(cx, cy, r * 0.28f, dotPaint);
            return;
        }

        long elapsed = SystemClock.uptimeMillis() - baseAt;
        int active = -1;
        for (int i = 0; i < offsets.length; i++) {
            if (offsets[i] <= elapsed) active = i; else break;
        }
        if (active >= 0) {
            long age = elapsed - offsets[active];
            if (age <= PULSE_MS) {
                float k = 1f - age / (float) PULSE_MS;      // 1 → 0
                // 外扩的环
                ringPaint.setColor(withAlpha(accentColor, 0.42f * k));
                float ringR = r * (0.30f + 0.70f * (1f - k)); // 由小到大
                c.drawCircle(cx, cy, ringR, ringPaint);
                // 中间的点：每拍鼓一下
                float bump = 0.30f + 0.16f * k;
                dotPaint.setColor(accentColor);
                glowPaint.setColor(withAlpha(accentColor, 0.20f * k));
                c.drawCircle(cx, cy, r * (bump + 0.18f), glowPaint);
                c.drawCircle(cx, cy, r * bump, dotPaint);
                // 这一拍的心率（数字由卡片上的大字负责，这里只用来决定点的幅度）
                int bpm = active < bpms.length ? bpms[active] : 0;
                if (bpm > 0) {
                    dotPaint.setColor(withAlpha(accentColor, 0.35f));
                    c.drawCircle(cx, cy, r * (bump + 0.05f * Math.min(1f, bpm / 150f)), dotPaint);
                }
            } else {
                dotPaint.setColor(faintColor);
                c.drawCircle(cx, cy, r * 0.28f, dotPaint);
            }
        } else {
            dotPaint.setColor(faintColor);
            c.drawCircle(cx, cy, r * 0.28f, dotPaint);
        }

        long end = offsets[offsets.length - 1] + PULSE_MS;
        if (elapsed < end) postInvalidateOnAnimation();
        else { running = false; invalidate(); }
    }

    @Override protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private static int withAlpha(int color, float a) {
        int alpha = Math.max(0, Math.min(255, Math.round(a * 255)));
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
