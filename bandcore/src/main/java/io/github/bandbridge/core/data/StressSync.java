package io.github.bandbridge.core.data;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.bandbridge.core.Probe;
import io.github.bandbridge.core.proto.HuaweiPacket;
import io.github.bandbridge.core.proto.HuaweiStressParser;
import io.github.bandbridge.core.proto.packets.FileDownloadService2C;
import io.github.bandbridge.core.proto.packets.HrRriTest;
import io.github.bandbridge.core.proto.packets.Stress;
import io.github.bandbridge.core.proto.stress.HuaweiStressHRVCalculation;
import io.github.bandbridge.core.proto.stress.HuaweiStressScoreCalculation;
import io.github.bandbridge.core.transport.RequestRunner;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ★3.301 压力 —— 照 GB（HuaweiStressCalibration / SetStressRequest / downloadStressData）。
 *
 * 手环自己不会开自动压力，要手机先给它一份【校准】：
 *      → 60 秒后 type=4 关 → HuaweiStressHRVCalculation 算 10 个 HRV 特征（+2 个 0）
 *      → HuaweiStressScoreCalculation 出分数 / 等级
 *   ② 开自动压力：0x20/0x09 {01:1, 02:分数, 03:12 个 float 特征, 04:校准结束时间(秒)}
 *   ③ 之后手环自己测，结果在 rrisqi_data.bin（0x2C 下载，类型 0x10）→ HuaweiStressParser.parseRri
 *      每条 = 开始 / 结束 / 分数(1–99) / 等级；最新一条的特征当新的校准存下来（GB 也这么做）
 *
 * 校准存在 SharedPreferences「bandbridge_stress」.last（GB stressDataToJsonStr 的格式）。
 */
public final class StressSync {

    private static final String SP = "bandbridge_stress";
    private static final String KEY = "last";

    private final RequestRunner rr;
    private final Context app;

    public interface Progress { void on(int secondsLeft, int beats); }

    public StressSync(RequestRunner rr, Context app) { this.rr = rr; this.app = app; }

    public static HuaweiStressParser.StressData loadCalib(Context app) {
        try {
            String s = app.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY, null);
            return s == null ? null : HuaweiStressParser.stressDataFromJsonStr(s);
        } catch (Throwable t) { return null; }
    }

    private void saveCalib(HuaweiStressParser.StressData d) {
        try {
            String s = HuaweiStressParser.stressDataToJsonStr(d);
            if (s != null) app.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY, s).apply();
        } catch (Throwable ignored) { }
    }

    /** ① 校准：阻塞约 60 秒。成功返回结果（已存、已开自动压力），失败返回 null（探针里写原因） */
    public HuaweiStressParser.StressData calibrate(Progress cb) throws InterruptedException {
        List<Integer> rri = new ArrayList<>(), sqi = new ArrayList<>();
        long t0 = System.currentTimeMillis(), t1;
        rr.openStream(HrRriTest.id, HrRriTest.RriData.id);
        try {
            rr.sendOnly(new HrRriTest.OpenOrClose.Request(rr.params(), (byte) 3));
            probe("calib start");
            long until = t0 + 60000L;
            int lastLeft = -1;
            while (System.currentTimeMillis() < until) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                HuaweiPacket p = rr.pollStream(Math.min(1000, Math.max(1, until - System.currentTimeMillis())));
                if (p instanceof HrRriTest.RriData.Response) {
                    List<HrRriTest.RriData.Response.rriSqiData> cs = ((HrRriTest.RriData.Response) p).containers;
                    if (cs != null) for (HrRriTest.RriData.Response.rriSqiData d : cs) {
                        if (d.sqi != 0) { rri.add((int) d.rri); sqi.add((int) d.sqi); }
                    }
                }
                int left = (int) Math.max(0, (until - System.currentTimeMillis()) / 1000L);
                if (left != lastLeft && cb != null) { lastLeft = left; cb.on(left, rri.size()); }
            }
        } catch (InterruptedException ie) {
            throw ie;
        } catch (Throwable t) {
            probe("calib fail | " + t.getClass().getSimpleName() + ":" + t.getMessage());
        } finally {
            t1 = System.currentTimeMillis();
            rr.closeStream();
            try { rr.sendOnly(new HrRriTest.OpenOrClose.Request(rr.params(), (byte) 4)); } catch (Throwable ignored) { }
        }
        probe("calib rri | n=" + rri.size() + " head=" + (rri.size() > 8 ? rri.subList(0, 8) : rri));
        if (rri.size() < 20) { probe("calib too few beats | n=" + rri.size()); return null; }

        float[] hrv = new HuaweiStressHRVCalculation().calculateStressHRVParameters(rri, sqi, (int) ((t1 - t0) / 1000L));
        if (hrv == null || hrv.length != 10) { probe("calib hrv null"); return null; }
        float factor = HuaweiStressScoreCalculation.calculateScoreFactor(hrv);
        byte score = HuaweiStressScoreCalculation.calculateNormalizedFinalScore(factor);
        HuaweiStressParser.StressData d = new HuaweiStressParser.StressData();
        for (float f : hrv) d.features.add(f);
        d.features.add(0.0F);   // GB：第 11、12 个恒为 0
        d.features.add(0.0F);
        d.startTime = t0;
        d.endTime = t1;
        d.score = score;
        d.scoreFactor = factor;
        d.level = HuaweiStressScoreCalculation.calculateLevel(score);
        probe("calib done | score=" + score + " level=" + d.level);
        if (score == 0) return null;
        saveCalib(d);
        enableAuto(d);
        return d;
    }

    /** ② 开自动压力（有校准才开）。每次连上都发一次（手环可能被重置过） */
    public boolean enableAuto(HuaweiStressParser.StressData d) {
        if (d == null) d = loadCalib(app);
        if (d == null || d.score == 0 || d.endTime == 0 || d.features.size() != 12) return false;
        try {
            rr.sendOnly(new Stress.AutomaticStress.Request(rr.params(), (byte) 1, d.score, d.features, (int) (d.endTime / 1000L)));
            probe("auto on | score=" + d.score);
            return true;
        } catch (Throwable t) {
            probe("auto fail | " + t.getClass().getSimpleName() + ":" + t.getMessage());
            return false;
        }
    }

    /** ③ 下载 [start, end]（秒）里手环自己测的压力。每条 {start, end, v:{score, level}}（毫秒）*/
    public List<JSONObject> download(int start, int end) throws InterruptedException {
        List<JSONObject> out = new ArrayList<>();
        byte[] data;
        try {
            data = new FileDownloader(rr).download("rrisqi_data.bin", FileDownloadService2C.FileType.RRI, start, end, 0);
        } catch (InterruptedException ie) {
            throw ie;
        } catch (Throwable t) {
            probe("dl fail | " + t.getClass().getSimpleName() + ":" + t.getMessage());
            return out;
        }
        if (data == null || data.length == 0) { probe("dl empty"); return out; }
        HuaweiStressParser.RriFileData f = HuaweiStressParser.parseRri(data);
        if (f == null || f.stressData == null) { probe("parse null | " + data.length + "B ver=" + (f == null ? "?" : f.version)); return out; }
        HuaweiStressParser.StressData newest = null;
        for (HuaweiStressParser.StressData d : f.stressData) {
            try {
                JSONObject o = new JSONObject();
                o.put("start", d.startTime);
                o.put("end", d.endTime);
                JSONObject v = new JSONObject();
                v.put("score", d.score & 0xff);
                v.put("level", d.level & 0xff);
                o.put("v", v);
                out.add(o);
                if (newest == null || d.endTime > newest.endTime) newest = d;
            } catch (Throwable ignored) { }
        }
        /* GB：手环最新一次自测的特征当新校准存下来 */
        HuaweiStressParser.StressData cur = loadCalib(app);
        if (newest != null && newest.score != 0 && newest.features.size() == 12 && (cur == null || newest.endTime > cur.endTime)) saveCalib(newest);
        probe("dl | " + data.length + "B n=" + out.size() + (newest == null ? "" : " last=" + (newest.score & 0xff)));
        return out;
    }

    private static void probe(String s) {
        try { Probe.post("hwstress", s); } catch (Throwable ignored) { }
    }
}
