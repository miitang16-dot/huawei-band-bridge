package io.github.bandbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.bandbridge.core.BandSession;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 「健康」页 —— a native copy of the Nexus health page (3.307): a hero banner, six tiles
 * (heart rate / SpO2 / steps / rest / sleep / stress), then today's heart rate line, last night's
 * sleep stages, stress + emotion timelines and today's steps per hour.
 *
 * <p>Plain {@link View}s and {@link Charts} (Canvas) only — the project does not use libraries.
 * The page uses the deep (dark) palette on purpose: the Nexus health page she asked it to match is
 * the dark one. The main screen keeps following the system theme.</p>
 *
 * <p>Reads the local stores only ({@link HealthData}); no protocol code here, nothing is invented.
 * With no data every block says 「还没同步过 —— 点上面『立即同步』」.</p>
 *
 * <p>Not verified on a device: layout, charts and the post-sync redraw.</p>
 */
public class HealthActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private int ink, sub, card, bg, stroke, accent;
    private float density;

    private final BandService.Ui myUi = () -> h.post(this::refresh);

    /* hero */
    private TextView heroDate, heroLine, heroBatt, heroData, syncTxt, pillTxt;
    private Charts.DotView pillDot;

    /* tiles */
    private TextView hrVal, hrSub, spoVal, spoSub, stepVal, stepSub, sleepVal, sleepSub;
    private TextView stressVal, stressChip, stressSub, emoVal, emoSub;
    private Charts.PanelBg stressBg, emoBg;
    private int stressTint = -1, emoTint = -1;
    private Charts.SparkView hrSpark;
    private Charts.MiniBarView stepBar;
    private Charts.SleepStageView sleepStages;
    private Charts.OrbView emoOrb;

    /* sections */
    private TextView hrStats, hrEmpty, sleepStats, sleepEmpty, sleepLegendWrap;
    private Charts.HrLineView hrChart;
    private Charts.SleepStageView sleepBar;
    private TextView stressStats, stressEmpty, stressLegendWrap, emoStats, emoEmpty;
    private Charts.StressTimelineView stressChart;
    private Charts.EmotionStripView emoStrip;
    private LinearLayout stressDist;
    private TextView stepsTotal, stepsEmpty;
    private Charts.HourBarsView stepBars;
    private LinearLayout hrAxis, sleepAxis, stressAxis, emoAxis, stepsAxis;

    private volatile boolean loading;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        Charts.Palette p = new Charts.Palette(true);
        ink = p.ink; sub = p.sub; card = p.card; bg = p.bg; stroke = p.stroke; accent = p.accent;
        getWindow().setStatusBarColor(bg);
        if (Build.VERSION.SDK_INT >= 23) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            getWindow().getDecorView().setSystemUiVisibility(flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(32));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        sv.setFillViewport(true);
        sv.addView(col);
        setContentView(sv);

        buildHeader(col);
        buildHero(col);
        buildTiles(col);
        buildHrSection(col);
        buildSleepSection(col);
        buildMoodSection(col);
        buildStepsSection(col);

        TextView foot = tv("数据只在本机，不上传。手环大约每分钟记一格，不是实时；"
                + "手环自带的情绪页要华为运动健康才显示，这里的是同一份原始数据。", 11, sub, false);
        foot.setLineSpacing(0, 1.5f);
        foot.setPadding(dp(2), dp(14), dp(2), 0);
        col.addView(foot);
    }

    /* ── lifecycle ─────────────────────────────────────────────────────────── */

    @Override protected void onResume() {
        super.onResume();
        BandService.ui = myUi;
        refresh();
        h.postDelayed(tick, 5000);
    }

    @Override protected void onPause() {
        super.onPause();
        if (BandService.ui == myUi) BandService.ui = null;
        h.removeCallbacks(tick);
    }

    private final Runnable tick = this::refresh;

    /** Load on a worker thread (the stores are JSON files), then repaint on the UI thread. */
    private void refresh() {
        if (loading) return;
        loading = true;
        final BandSession session = BandService.instance == null ? null : BandService.instance.session;
        final boolean running = BandService.instance != null;
        new Thread(() -> {
            HealthData d;
            try {
                d = HealthData.load(HealthActivity.this, session);
                d.serviceRunning = running;
            } catch (Throwable t) {
                d = null;
            }
            final HealthData data = d;
            runOnUiThread(() -> {
                loading = false;
                if (data != null) apply(data);
            });
        }, "band-health").start();
    }

    /* ── build ─────────────────────────────────────────────────────────────── */

    private void buildHeader(LinearLayout col) {
        LinearLayout head = row();
        TextView back = pillText("‹ 返回");
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView title = tv("健康", 20, ink, true);
        title.setPadding(dp(12), 0, 0, 0);
        head.addView(title);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        col.addView(head);
    }

    private void buildHero(LinearLayout col) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(16), dp(16), dp(16), dp(12));
        Charts.PanelBg heroBg = new Charts.PanelBg(card, stroke, 1, 20, density);
        heroBg.setHeroTints(Charts.Palette.HR, Charts.Palette.SLEEP);
        hero.setBackground(heroBg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(14);
        col.addView(hero, hlp);

        LinearLayout top = row();
        top.setGravity(Gravity.TOP);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        heroDate = tv("", 12, sub, false);
        heroLine = tv("", 16, ink, true);
        heroLine.setLineSpacing(0, 1.4f);
        heroLine.setPadding(0, dp(4), 0, 0);
        left.addView(heroDate);
        left.addView(heroLine);
        top.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout pill = row();
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(Color.argb(46, 255, 255, 255));
        chipBg.setCornerRadius(dp(999));
        chipBg.setStroke(dp(1), stroke);
        pill.setBackground(chipBg);
        pillDot = new Charts.DotView(this);
        pill.addView(pillDot, dp(7), dp(7));
        pillTxt = tv("", 11, ink, true);
        pillTxt.setPadding(dp(6), 0, 0, 0);
        pill.addView(pillTxt);
        pill.setOnClickListener(v -> {
            BandService s = BandService.instance;
            if (s == null || s.session == null || !s.session.isReady()) {
                startActivity(new Intent(this, MainActivity.class));
                finish();
            }
        });
        top.addView(pill, new LinearLayout.LayoutParams(-2, -2));
        hero.addView(top);

        LinearLayout bottom = row();
        bottom.setPadding(0, dp(12), 0, 0);
        heroBatt = tv("", 11, sub, false);
        heroData = tv("", 11, sub, false);
        heroData.setPadding(dp(12), 0, 0, 0);
        bottom.addView(heroBatt);
        bottom.addView(heroData);
        bottom.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        syncTxt = pillText("同步");
        syncTxt.setBackground(pillBg(Color.argb(56, Color.red(accent), Color.green(accent), Color.blue(accent)),
                Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent))));
        syncTxt.setOnClickListener(v -> doSync());
        bottom.addView(syncTxt);
        hero.addView(bottom);
    }

    private void buildTiles(LinearLayout col) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        col.addView(tileRow(tile(Charts.Palette.HR, "心率"), tile(Charts.Palette.SPO, "血氧")), lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, -2);
        lp2.topMargin = dp(10);
        col.addView(tileRow(tile(Charts.Palette.STEP, "步数"), tile(Charts.Palette.SLEEP, "睡眠")), lp2);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(-1, -2);
        lp3.topMargin = dp(10);
        col.addView(tileRow(tile(0xFFC9C3C6, "压力"), tile(0xFFC9C3C6, "情绪")), lp3);
    }

    private LinearLayout tileRow(LinearLayout a, LinearLayout b) {
        LinearLayout r = row();
        r.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(112), 1f);
        lp.rightMargin = dp(5);
        r.addView(a, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(112), 1f);
        lp2.leftMargin = dp(5);
        r.addView(b, lp2);
        return r;
    }

    /** One overview tile; tint 14% from the top-left, like Nexus .hv-tile::before. */
    private LinearLayout tile(int tintColor, String title) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(12), dp(12), dp(12), dp(10));
        Charts.PanelBg bgd = new Charts.PanelBg(card, stroke, 1, 16, density);
        bgd.setTileTint(tintColor, 0.14f);
        t.setBackground(bgd);

        LinearLayout tr = row();
        Charts.DotView dot = new Charts.DotView(this);
        dot.setColor(tintColor, false);
        tr.addView(dot, dp(6), dp(6));
        TextView lab = tv(title, 12, sub, true);
        lab.setPadding(dp(6), 0, 0, 0);
        tr.addView(lab);
        t.addView(tr);

        switch (title) {
            case "心率":
                hrVal = bigValue();
                t.addView(hrVal);
                hrSpark = new Charts.SparkView(this);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(22));
                slp.topMargin = dp(2);
                t.addView(hrSpark, slp);
                t.addView(spacer());
                hrSub = tv("", 11, sub, false);
                t.addView(hrSub);
                break;
            case "血氧":
                spoVal = bigValue();
                t.addView(spoVal);
                t.addView(spacer());
                spoSub = tv("", 11, sub, false);
                t.addView(spoSub);
                break;
            case "步数":
                stepVal = bigValue();
                t.addView(stepVal);
                stepBar = new Charts.MiniBarView(this);
                stepBar.setColors(Charts.Palette.STEP, Color.argb(20, 255, 255, 255));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(6));
                blp.topMargin = dp(6);
                t.addView(stepBar, blp);
                t.addView(spacer());
                stepSub = tv("", 11, sub, false);
                t.addView(stepSub);
                break;
            case "睡眠":
                sleepVal = bigValue();
                t.addView(sleepVal);
                sleepStages = new Charts.SleepStageView(this);
                LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(-1, dp(6));
                stlp.topMargin = dp(6);
                t.addView(sleepStages, stlp);
                t.addView(spacer());
                sleepSub = tv("", 11, sub, false);
                t.addView(sleepSub);
                break;
            case "压力":
                stressBg = bgd;
                LinearLayout vr = row();
                stressVal = bigValue();
                vr.addView(stressVal);
                stressChip = tv("", 11, ink, true);
                stressChip.setPadding(dp(7), dp(2), dp(7), dp(2));
                stressChip.setVisibility(View.GONE);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
                clp.leftMargin = dp(6);
                clp.gravity = Gravity.CENTER_VERTICAL;
                vr.addView(stressChip, clp);
                t.addView(vr);
                t.addView(spacer());
                stressSub = tv("", 11, sub, false);
                t.addView(stressSub);
                break;
            default:   // 情绪
                emoBg = bgd;
                LinearLayout er = row();
                emoOrb = new Charts.OrbView(this);
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dp(22), dp(22));
                olp.rightMargin = dp(7);
                er.addView(emoOrb, olp);
                emoVal = tv("", 21, ink, true);
                er.addView(emoVal);
                t.addView(er);
                t.addView(spacer());
                emoSub = tv("", 11, sub, false);
                t.addView(emoSub);
                break;
        }
        return t;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        return v;
    }

    private void buildHrSection(LinearLayout col) {
        LinearLayout c = card();
        addCardTitle(c, "今日心率", "每分钟一格");
        hrStats = tv("", 12, sub, false);
        hrStats.setPadding(0, dp(4), 0, 0);
        c.addView(hrStats);
        hrChart = new Charts.HrLineView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(96));
        clp.topMargin = dp(8);
        c.addView(hrChart, clp);
        hrAxis = axis("0:00", "6:00", "12:00", "18:00", "24:00");
        c.addView(hrAxis);
        hrEmpty = emptyText();
        c.addView(hrEmpty);
        col.addView(wrap(c));
    }

    private void buildSleepSection(LinearLayout col) {
        LinearLayout c = card();
        addCardTitle(c, "昨晚睡眠", "科学睡眠 · 每分钟阶段");
        sleepStats = tv("", 12, sub, false);
        sleepStats.setPadding(0, dp(4), 0, 0);
        c.addView(sleepStats);
        sleepBar = new Charts.SleepStageView(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(16));
        blp.topMargin = dp(10);
        c.addView(sleepBar, blp);
        sleepLegendWrap = tv("", 12, sub, false);
        sleepLegendWrap.setLineSpacing(dp(3), 1.3f);
        sleepLegendWrap.setPadding(0, dp(8), 0, 0);
        c.addView(sleepLegendWrap);
        sleepAxis = axis("入睡", "", "", "", "醒来");
        c.addView(sleepAxis);
        sleepEmpty = emptyText();
        c.addView(sleepEmpty);
        col.addView(wrap(c));
    }

    private void buildMoodSection(LinearLayout col) {
        LinearLayout c = card();
        addCardTitle(c, "压力与情绪", "手环自测");

        TextView lab1 = subLabel("压力");
        c.addView(lab1);
        stressStats = tv("", 12, sub, false);
        c.addView(stressStats);
        stressChart = new Charts.StressTimelineView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(84));
        clp.topMargin = dp(8);
        c.addView(stressChart, clp);
        stressAxis = axis("0:00", "6:00", "12:00", "18:00", "24:00");
        c.addView(stressAxis);
        stressDist = new LinearLayout(this);
        stressDist.setOrientation(LinearLayout.HORIZONTAL);
        stressDist.setBackground(rounded(Color.argb(16, 255, 255, 255), 999));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(8));
        dlp.topMargin = dp(8);
        c.addView(stressDist, dlp);
        stressLegendWrap = tv("", 12, sub, false);
        stressLegendWrap.setPadding(0, dp(8), 0, 0);
        c.addView(stressLegendWrap);
        stressEmpty = emptyText();
        c.addView(stressEmpty);

        c.addView(subLabel("情绪"));
        emoStats = tv("", 12, sub, false);
        c.addView(emoStats);
        emoStrip = new Charts.EmotionStripView(this);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, dp(22));
        elp.topMargin = dp(8);
        c.addView(emoStrip, elp);
        emoAxis = axis("0:00", "6:00", "12:00", "18:00", "24:00");
        c.addView(emoAxis);
        emoEmpty = emptyText();
        c.addView(emoEmpty);

        col.addView(wrap(c));
    }

    private void buildStepsSection(LinearLayout col) {
        LinearLayout c = card();
        addCardTitle(c, "今日步数", "每小时");
        stepsTotal = tv("", 12, sub, false);
        stepsTotal.setPadding(0, dp(4), 0, 0);
        c.addView(stepsTotal);
        stepBars = new Charts.HourBarsView(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(48));
        blp.topMargin = dp(10);
        c.addView(stepBars, blp);
        stepsAxis = axis("0", "6", "12", "18", "24");
        c.addView(stepsAxis);
        stepsEmpty = emptyText();
        c.addView(stepsEmpty);
        col.addView(wrap(c));
    }

    /* ── apply ─────────────────────────────────────────────────────────────── */

    private void apply(HealthData d) {
        heroDate.setText(dateText(d.nowMs));

        StringBuilder bits = new StringBuilder();
        if (d.hasSleep) bits.append("睡了 ").append(hmShort(d.sleepTotalMin));
        if (d.stepsToday > 0) { if (bits.length() > 0) bits.append(" · "); bits.append("走了 ").append(String.format(Locale.ROOT, "%,d", d.stepsToday)).append(" 步"); }
        if (d.stressScore > 0) { if (bits.length() > 0) bits.append(" · "); bits.append("压力").append(Charts.Palette.stressText(d.stressScore)); }
        if (d.emoCode > 0) { if (bits.length() > 0) bits.append(" · "); bits.append("心情").append(emoText(d.emoCode)); }
        if (bits.length() == 0) bits.append(d.anything ? "今天的数据还在路上" : "还没同步过 —— 点上面「立即同步」");
        heroLine.setText(bits.toString());

        if (!d.serviceRunning) {
            pillDot.setColor(0xFFC9C3C6, false);
            pillTxt.setText("手环没连");
        } else if (!d.connected) {
            pillDot.setColor(Charts.Palette.L3, true);
            pillTxt.setText("连接中…");
        } else {
            pillDot.setColor(0xFF6FC497, true);
            pillTxt.setText(d.model.isEmpty() ? "手环已连接" : d.model + " 已连接");
        }
        heroBatt.setText(d.battery >= 0 ? "🔋 " + d.battery + "%" : "");
        heroBatt.setVisibility(d.battery >= 0 ? View.VISIBLE : View.GONE);
        heroData.setText(d.lastDataMs > 0 ? "数据到 " + hhmm(d.lastDataMs) : "还没有数据");
        boolean canSync = d.serviceRunning && d.connected;
        syncTxt.setAlpha(canSync ? 1f : 0.45f);

        /* heart rate */
        if (d.hasHr) {
            setValue(hrVal, String.valueOf(d.hr), "次/分");
            hrSub.setText(hhmm(d.hrAtMs) + (d.restingHr > 0 ? " · 静息 " + d.restingHr : ""));
            hrSpark.setData(d.hrSpark, Charts.Palette.HR);
        } else {
            hrVal.setText("—");
            hrSub.setText(emptyTileText(d));
            hrSpark.setData(new int[0], Charts.Palette.HR);
        }

        /* SpO2 */
        if (d.spo > 0) {
            setValue(spoVal, String.valueOf(d.spo), "%");
            spoSub.setText(hhmm(d.spoAtMs) + " 测的" + (d.spoMin > 0 && d.spoCount > 1 ? " · 最低 " + d.spoMin + "%" : ""));
        } else {
            spoVal.setText("—");
            spoSub.setText(emptyTileText(d));
        }

        /* steps */
        if (d.stepsToday > 0) {
            setValue(stepVal, String.format(Locale.ROOT, "%,d", d.stepsToday), "步");
            stepBar.setFraction(d.stepsToday / 8000f);
            stepSub.setText("目标 8,000");
        } else {
            stepVal.setText("—");
            stepBar.setFraction(0);
            stepSub.setText(emptyTileText(d));
        }

        /* sleep */
        if (d.hasSleep) {
            String[] hm = hmParts(d.sleepTotalMin);
            setValue(sleepVal, hm[0], "时", hm[1], "分");
            sleepStages.setData(d.sleepFallMs, d.sleepWakeMs, d.sleepRunStart, d.sleepRunMin, d.sleepRunCode,
                    Charts.Palette.SLEEP, Color.argb(18, 255, 255, 255), true);
            sleepSub.setText(hhmm(d.sleepFallMs) + "→" + hhmm(d.sleepWakeMs) + (d.sleepScore > 0 ? " · " + d.sleepScore + " 分" : ""));
        } else {
            sleepVal.setText("—");
            sleepStages.setData(0, 0, new long[0], new int[0], new int[0],
                    Charts.Palette.SLEEP, Color.argb(18, 255, 255, 255), true);
            sleepSub.setText("昨晚没有记录");
        }

        /* stress */
        if (d.stressScore > 0) {
            int color = Charts.Palette.stress(d.stressLevel);
            stressVal.setText(String.valueOf(d.stressScore));
            stressChip.setText(Charts.Palette.stressText(d.stressScore));
            stressChip.setVisibility(View.VISIBLE);
            stressChip.setBackground(rounded(alpha(color, 0.40f), 999));
            if (d.stressLevel != stressTint) { stressBg.setTileTint(color, 0.14f); stressTint = d.stressLevel; }
            stressSub.setText(hhmm(d.stressAtMs) + " 测的");
        } else {
            stressVal.setText("—");
            stressChip.setVisibility(View.GONE);
            if (stressTint != 0) { stressBg.setTileTint(0xFFC9C3C6, 0.14f); stressTint = 0; }
            stressSub.setText(d.calibrated ? "今天还没有" : "还没校准");
        }

        /* emotion */
        if (d.emoCode > 0) {
            int color = Charts.Palette.emotion(d.emoCode);
            emoOrb.setColor(color);
            emoVal.setText(emoText(d.emoCode));
            emoSub.setText(hhmm(d.emoAtMs) + (d.emoValence >= 0 ? " · 愉悦度 " + d.emoValence : ""));
            if (d.emoCode != emoTint) { emoBg.setTileTint(color, 0.14f); emoTint = d.emoCode; }
        } else {
            emoOrb.setColor(0);
            emoVal.setText("—");
            emoSub.setText("还没有");
            if (emoTint != 0) { emoBg.setTileTint(0xFFC9C3C6, 0.14f); emoTint = 0; }
        }

        applyHrSection(d);
        applySleepSection(d);
        applyMoodSection(d);
        applyStepsSection(d);
    }

    private void applyHrSection(HealthData d) {
        boolean has = d.hrCount > 0;
        show(hrStats, has); show(hrChart, has); show(hrAxis, has); show(hrEmpty, !has);
        if (!has) { hrEmpty.setText(emptySectionText(d)); return; }
        hrStats.setText(stats("最高", String.valueOf(d.hrMax), "最低", String.valueOf(d.hrMin),
                "平均", String.valueOf(d.hrAvg), "静息", d.restingHr > 0 ? String.valueOf(d.restingHr) : "—",
                "格数", String.valueOf(d.hrCount)));
        hrChart.setData(d.dayStartMs, d.nowMs, d.hrT, d.hrV, Charts.Palette.HR,
                Color.argb(40, 160, 140, 150), card);
    }

    private void applySleepSection(HealthData d) {
        boolean has = d.hasSleep;
        show(sleepStats, has); show(sleepBar, has); show(sleepLegendWrap, has); show(sleepAxis, has);
        show(sleepEmpty, !has);
        if (!has) { sleepEmpty.setText("昨晚没有记录（没戴手环 / 还没同步）"); return; }
        sleepStats.setText(stats("总时长", hmShort(d.sleepTotalMin), "入睡", hhmm(d.sleepFallMs),
                "醒来", hhmm(d.sleepWakeMs), "睡眠分", d.sleepScore > 0 ? String.valueOf(d.sleepScore) : "—"));
        sleepBar.setData(d.sleepFallMs, d.sleepWakeMs, d.sleepRunStart, d.sleepRunMin, d.sleepRunCode,
                Charts.Palette.SLEEP, Color.argb(18, 255, 255, 255), true);
        SpannableStringBuilder lg = new SpannableStringBuilder();
        lg.append(legendLine(Charts.Palette.SLEEP, "深睡", hmShort(d.sleepDeepMin)));
        lg.append("    ").append(legendLine(alpha(Charts.Palette.SLEEP, 0.55f), "浅睡", hmShort(d.sleepLightMin)));
        lg.append("    ").append(legendLine(0xFF8FB7E8, "快速眼动", hmShort(d.sleepRemMin)));
        lg.append("    ").append(legendLine(0xFFF0C46A, "清醒", hmShort(d.sleepAwakeMin)));
        if (d.sleepAvgHr > 0) lg.append("\n睡时平均心率 ").append(String.valueOf(d.sleepAvgHr));
        if (d.sleepMinSpo > 0) lg.append(d.sleepAvgHr > 0 ? " · " : "\n").append("睡时最低血氧 ").append(d.sleepMinSpo + "%");
        if (d.sleepAvgBreath > 0) lg.append(" · 平均呼吸 ").append(d.sleepAvgBreath + " 次/分");
        sleepLegendWrap.setText(lg);
    }

    private void applyMoodSection(HealthData d) {
        boolean hasStress = d.stressCount > 0;
        boolean hasEmo = d.emoStart.length > 0;
        show(stressStats, hasStress); show(stressChart, hasStress); show(stressAxis, hasStress);
        show(stressDist, hasStress); show(stressLegendWrap, hasStress);
        show(emoStats, hasEmo); show(emoStrip, hasEmo); show(emoAxis, hasEmo);
        show(stressEmpty, !hasStress);
        show(emoEmpty, !hasEmo);
        stressEmpty.setText(!d.calibrated ? "还没校准：先在主界面点「开始校准」，静坐 1 分钟" : "今天还没有压力记录");
        emoEmpty.setText("今天还没有情绪记录");

        if (hasStress) {
            stressStats.setText(stats("平均", String.valueOf(d.stressAvg), "最高", String.valueOf(d.stressMax),
                    "次数", String.valueOf(d.stressCount)));
            stressChart.setData(d.dayStartMs, d.stressT, d.stressV, card);
            String[] names = {"放松", "正常", "中等", "偏高"};
            int[] colors = {Charts.Palette.L1, Charts.Palette.L2, Charts.Palette.L3, Charts.Palette.L4};
            stressDist.removeAllViews();
            for (int i = 1; i <= 4; i++) {
                int n = d.stressDist[i];
                if (n <= 0) continue;
                View v = new View(this);
                GradientDrawable g = new GradientDrawable();
                g.setColor(colors[i - 1]);
                g.setCornerRadius(dp(999));
                v.setBackground(g);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, n);
                lp.leftMargin = dp(2);
                stressDist.addView(v, lp);
            }
            SpannableStringBuilder lg = new SpannableStringBuilder();
            for (int i = 1; i <= 4; i++) {
                if (i > 1) lg.append("    ");
                lg.append(legendLine(colors[i - 1], names[i - 1], String.valueOf(d.stressDist[i])));
            }
            stressLegendWrap.setText(lg);
        }
        if (hasEmo) {
            emoStats.setText(stats("记录", String.valueOf(d.emoStart.length),
                    "愉悦", String.valueOf(d.emoCnt[3]), "平静", String.valueOf(d.emoCnt[2]),
                    "不愉悦", String.valueOf(d.emoCnt[1])));
            emoStrip.setData(d.dayStartMs, d.emoStart, d.emoEnd, d.emoCodeArr, Color.argb(16, 255, 255, 255));
        }
    }

    private void applyStepsSection(HealthData d) {
        boolean has = d.stepsToday > 0;
        show(stepsTotal, has); show(stepBars, has); show(stepsAxis, has); show(stepsEmpty, !has);
        if (!has) { stepsEmpty.setText(emptySectionText(d)); return; }
        int best = 0, bestHour = 0;
        for (int i = 0; i < 24; i++) if (d.stepsByHour[i] > best) { best = d.stepsByHour[i]; bestHour = i; }
        stepsTotal.setText(stats("今天", String.format(Locale.ROOT, "%,d", d.stepsToday) + " 步",
                "最多", String.format(Locale.ROOT, "%02d:00", bestHour) + "（" + String.format(Locale.ROOT, "%,d", best) + " 步）",
                "目标", "8,000"));
        stepBars.setData(d.stepsByHour, Charts.Palette.STEP);
    }

    private void doSync() {
        BandService s = BandService.instance;
        if (s == null || s.session == null) { toast("先在主界面点「连接」"); return; }
        if (!s.session.isReady()) { toast("还在连，等状态变成「已连接」再点"); return; }
        s.session.syncNow();
        syncTxt.setText("同步中…");
        h.postDelayed(() -> syncTxt.setText("同步"), 2500);
        h.postDelayed(this::refresh, 1500);
        h.postDelayed(this::refresh, 6000);
    }

    /* ── helpers ───────────────────────────────────────────────────────────── */

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView tv(CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.2f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView bigValue() {
        TextView t = tv("—", 26, ink, true);
        t.setPadding(0, dp(2), 0, 0);
        return t;
    }

    private TextView pillText(String s) {
        TextView t = tv(s, 12, ink, true);
        t.setPadding(dp(11), dp(5), dp(11), dp(5));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(40, 255, 255, 255));
        g.setCornerRadius(dp(999));
        g.setStroke(dp(1), stroke);
        t.setBackground(g);
        return t;
    }

    private GradientDrawable pillBg(int fill, int border) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(999));
        g.setStroke(dp(1), border);
        return g;
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        c.setBackground(new Charts.PanelBg(card, stroke, 1, 18, density));
        return c;
    }

    private LinearLayout wrap(View v) {
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        w.addView(v, lp);
        return w;
    }

    private void addCardTitle(LinearLayout card, String title, String subText) {
        LinearLayout r = row();
        r.setGravity(Gravity.BOTTOM);
        r.addView(tv(title, 15, ink, true));
        TextView s = tv(subText, 12, sub, false);
        s.setPadding(dp(10), 0, 0, dp(1));
        r.addView(s);
        card.addView(r);
    }

    private TextView subLabel(String s) {
        TextView t = tv(s, 13, ink, true);
        t.setPadding(0, dp(14), 0, 0);
        return t;
    }

    private TextView emptyText() {
        TextView t = tv("", 12, sub, false);
        t.setLineSpacing(0, 1.5f);
        t.setPadding(0, dp(8), 0, 0);
        t.setVisibility(View.GONE);
        return t;
    }

    private String emptyTileText(HealthData d) { return d.anything ? "今天还没有" : "还没同步过"; }

    private String emptySectionText(HealthData d) { return d.anything ? "今天还没有记录" : "还没同步过 —— 点上面「立即同步」"; }

    private void show(View v, boolean visible) { if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE); }

    private void setValue(TextView v, String num, String unit) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        int s = b.length();
        b.append(num);
        b.setSpan(new AbsoluteSizeSpan(26, true), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new StyleSpan(Typeface.BOLD), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new ForegroundColorSpan(ink), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (unit != null) {
            b.append(" ");
            s = b.length();
            b.append(unit);
            b.setSpan(new AbsoluteSizeSpan(11, true), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new ForegroundColorSpan(sub), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        v.setText(b);
    }

    private void setValue(TextView v, String n1, String u1, String n2, String u2) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        span(b, n1, 26, ink, true);
        b.append(" ");
        span(b, u1, 11, sub, false);
        b.append("  ");
        span(b, n2, 26, ink, true);
        b.append(" ");
        span(b, u2, 11, sub, false);
        v.setText(b);
    }

    /** Append {@code text} to {@code b} with size/colour/weight spans and return the same builder. */
    private SpannableStringBuilder span(SpannableStringBuilder b, String text, int sizeSp, int color, boolean bold) {
        int s = b.length();
        b.append(text);
        b.setSpan(new AbsoluteSizeSpan(sizeSp, true), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new ForegroundColorSpan(color), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) b.setSpan(new StyleSpan(Typeface.BOLD), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    private CharSequence stats(String... pairs) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (b.length() > 0) b.append("    ");
            int s = b.length();
            b.append(pairs[i]);
            b.setSpan(new ForegroundColorSpan(sub), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append(" ");
            span(b, pairs[i + 1], 12, ink, true);
        }
        return b;
    }

    /** Legend line "■ 深睡 2 小时 10 分" (coloured square + grey label + bold value). */
    private CharSequence legendLine(int color, String label, String value) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        b.append("■ ");
        b.setSpan(new ForegroundColorSpan(color), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int s = b.length();
        b.append(label);
        b.setSpan(new ForegroundColorSpan(sub), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(" ");
        span(b, value, 12, ink, true);
        return b;
    }

    /** Axis row: first label left, middle centred, last right (Nexus justify-content: space-between). */
    private LinearLayout axis(String... labels) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, 0);
        for (int i = 0; i < labels.length; i++) {
            TextView t = tv(labels[i], 10, sub, false);
            t.setAlpha(0.8f);
            int g = i == 0 ? Gravity.LEFT : i == labels.length - 1 ? Gravity.RIGHT : Gravity.CENTER;
            t.setGravity(g);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return r;
    }

    private String dateText(long nowMs) {
        String[] wk = {"日", "一", "二", "三", "四", "五", "六"};
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        return (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日 · 周" + wk[c.get(Calendar.DAY_OF_WEEK) - 1];
    }

    private static String hhmm(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(ms));
    }

    private static String hmShort(int minutes) {
        int hh = minutes / 60, mm = minutes % 60;
        if (hh <= 0) return mm + " 分";
        return hh + " 小时" + (mm > 0 ? " " + mm + " 分" : "");
    }

    private static String[] hmParts(int minutes) {
        return new String[]{String.valueOf(minutes / 60), String.valueOf(minutes % 60)};
    }

    private static String emoText(int code) {
        return code == 3 ? "愉悦" : code == 2 ? "平静" : code == 1 ? "不愉悦" : "—";
    }

    private static int alpha(int color, float a) {
        return Color.argb(Math.max(0, Math.min(255, (int) (a * 255))), Color.red(color), Color.green(color), Color.blue(color));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
