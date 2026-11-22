package io.github.bandbridge.core.data;

import io.github.bandbridge.core.Probe;
import io.github.bandbridge.core.proto.HuaweiSequenceDataFileParser;
import io.github.bandbridge.core.proto.HuaweiTrueSleepSequenceDataParser;
import io.github.bandbridge.core.proto.packets.FileDownloadService2C;
import io.github.bandbridge.core.transport.RequestRunner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 科学睡眠（TruSleep）—— GB downloadDictTrueSleepData：字典式睡眠同步。
 *
 *   下载 sequence_data（类型 0x16，dictId 700013 = SLEEP_DETAILS_CLASS）
 *   → HuaweiSequenceDataFileParser 拆成「每一晚」
 *   → HuaweiTrueSleepSequenceDataParser：摘要（入睡 / 醒来 / 上床 / 起床 / 分数 / 心率 / 血氧 / 呼吸 / HRV / 醒来次数…）
 *     + 明细（每分钟一个阶段码）
 *
 * 前提（GB HuaweiState）：supportsDictSleepSync = 扩展能力第 143 位；supportsBedTime = 第 199 位。
 * ★3.292 第一次上真机：阶段码 → 深睡/浅睡/REM/清醒 的对应 GB 里没写（它原样存库）。
 */
public final class TruSleepSync {

    private final RequestRunner rr;

    public TruSleepSync(RequestRunner rr) { this.rr = rr; }

    /** 返回每一晚一个 JSONObject（见 nightJson）；不支持 / 失败返回空表（探针里写原因） */
    public List<JSONObject> run(int start, int end, byte[] expand) throws InterruptedException {
        List<JSONObject> out = new ArrayList<>();
        boolean dict = bit(expand, 143), bed = bit(expand, 199);
        probe("caps | dictSleep=" + dict + " bedTime=" + bed + " expand=" + (expand == null ? "null" : expand.length + "B"));
        if (!dict) return out;
        byte[] data;
        try {
            data = new FileDownloader(rr).download("sequence_data", FileDownloadService2C.FileType.SEQUENCE_DATA, start, end, 700013);
        } catch (InterruptedException ie) { throw ie; }
        catch (Throwable t) { probe("download fail | " + t.getClass().getSimpleName() + ":" + t.getMessage()); return out; }
        if (data.length == 0) { probe("empty file"); return out; }

        HuaweiSequenceDataFileParser.SequenceFileData sfd;
        try { sfd = HuaweiSequenceDataFileParser.parseSequenceFileData(data); }
        catch (Throwable t) { probe("parse file fail | " + t); return out; }
        if (sfd == null) { probe("parse file null | " + data.length + "B"); return out; }

        for (HuaweiSequenceDataFileParser.SequenceData sd : sfd.getSequenceDataList()) {
            try {
                HuaweiTrueSleepSequenceDataParser.SleepSummary s = new HuaweiTrueSleepSequenceDataParser().parseData(sfd, sd);
                if (s == null) continue;
                HuaweiTrueSleepSequenceDataParser.correctSummary(s);
                long t0 = HuaweiTrueSleepSequenceDataParser.getTime(s.fallAsleepTime, s.bedTime, s.validData, bed);
                List<HuaweiTrueSleepSequenceDataParser.SleepStage> st = HuaweiTrueSleepSequenceDataParser.parseSleepDetails(sd.getDetails(), t0);
                JSONObject n = nightJson(s, st);
                out.add(n);
                probe("night | fall=" + s.fallAsleepTime + " wake=" + s.wakeupTime + " score=" + s.sleepScore
                        + " deepPart=" + s.deepPart + " wakeCount=" + s.wakeCount + " minutesByCode=" + n.optJSONObject("byCode"));
            } catch (Throwable t) { probe("night fail | " + t.getClass().getSimpleName() + ":" + t.getMessage()); }
        }
        probe("done | nights=" + out.size() + " file=" + data.length + "B");
        return out;
    }

    /** 一晚：摘要字段 + 阶段段落（相邻同码合并成 [startMs, minutes, code]）+ 每种码的分钟数 */
    private static JSONObject nightJson(HuaweiTrueSleepSequenceDataParser.SleepSummary s,
                                        List<HuaweiTrueSleepSequenceDataParser.SleepStage> st) throws Exception {
        JSONObject o = new JSONObject();
        o.put("fall", s.fallAsleepTime * 1000L);
        o.put("wake", s.wakeupTime * 1000L);
        o.put("bed", s.bedTime > 0 ? s.bedTime * 1000L : 0);
        o.put("rise", s.risingTime > 0 ? s.risingTime * 1000L : 0);
        o.put("score", s.sleepScore);
        o.put("deepPart", s.deepPart);
        o.put("wakeCount", s.wakeCount);
        o.put("efficiency", s.sleepEfficiency);
        o.put("latency", s.sleepLatency);
        o.put("minHr", s.minHeartRate); o.put("maxHr", s.maxHeartRate); o.put("avgHr", s.avgHeartRate);
        o.put("minSpo", s.minOxygenSaturation); o.put("maxSpo", s.maxOxygenSaturation); o.put("avgSpo", s.avgOxygenSaturation);
        o.put("avgBreath", s.avgBreathRate); o.put("avgHrv", s.avgHrv);
        JSONArray runs = new JSONArray();
        Map<Integer, Integer> byCode = new TreeMap<>();
        if (st != null && !st.isEmpty()) {
            long rs = st.get(0).getTime(); int rc = st.get(0).getStage(); int rn = 0;
            for (HuaweiTrueSleepSequenceDataParser.SleepStage x : st) {
                byCode.put(x.getStage(), (byCode.containsKey(x.getStage()) ? byCode.get(x.getStage()) : 0) + 1);
                if (x.getStage() == rc) { rn++; continue; }
                runs.put(new JSONArray().put(rs * 1000L).put(rn).put(rc));
                rs = x.getTime(); rc = x.getStage(); rn = 1;
            }
            runs.put(new JSONArray().put(rs * 1000L).put(rn).put(rc));
        }
        o.put("runs", runs);
        JSONObject bc = new JSONObject();
        for (Map.Entry<Integer, Integer> e : byCode.entrySet()) bc.put(String.valueOf(e.getKey()), e.getValue());
        o.put("byCode", bc);
        return o;
    }

    private static boolean bit(byte[] caps, int which) {
        if (caps == null || which >= caps.length * 8) return false;
        int b = 1 << (which % 8);
        return (caps[which / 8] & b) == b;
    }

    private static void probe(String s) {
        try { Probe.post("hwtru", s); } catch (Throwable ignored) { }
    }
}
