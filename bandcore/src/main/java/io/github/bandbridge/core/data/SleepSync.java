package io.github.bandbridge.core.data;

import io.github.bandbridge.core.Probe;
import io.github.bandbridge.core.proto.HuaweiPacket;
import io.github.bandbridge.core.proto.packets.FitnessData;
import io.github.bandbridge.core.transport.RequestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * 睡眠（基础那份）—— GB GetSleepDataCountRequest → GetSleepDataRequest。
 *
 *   ① MessageCount 0x07/0x0C（start, end 秒）→ 一共几页
 *   ② MessageData  0x07/0x0D（第 i 页）    → SleepResponse：每格 = 一段睡眠（类型 + 开始 + 分钟数）
 *      类型：0x06 浅睡 / 0x07 深睡（GB HuaweiSampleProvider.RawTypes）
 *      时间：6 字节 —— 前 4 字节 = 开始（秒），后 2 字节 = 时长（分钟）
 *
 * ★3.290 先做这条（跟心率同一个拉法）。科学睡眠（TruSleep：REM / 清醒）在 GB 里是走【文件下载】的另一条路，
 *   这一条如果拉回来是空的，探针会写 empty ⇒ 说明 Band 10 只走文件那条，下一步再补。
 */
public final class SleepSync {

    public static final class Seg {
        public final long start, end;   // 秒
        public final int type;          // 6 浅 / 7 深（其它原样留着）
        Seg(long start, long end, int type) { this.start = start; this.end = end; this.type = type; }
    }

    private static final long T = 6000L;
    private static final int MAX_PAGES = 200;

    private final RequestRunner rr;

    public SleepSync(RequestRunner rr) { this.rr = rr; }

    public List<Seg> run(int startSec, int endSec) throws InterruptedException {
        List<Seg> out = new ArrayList<>();
        HuaweiPacket.ParamsProvider pp = rr.params();
        int count;
        try {
            HuaweiPacket p = rr.call(new FitnessData.MessageCount.Request(pp, FitnessData.MessageCount.sleepId, startSec, endSec), T, "SleepCount");
            if (!(p instanceof FitnessData.MessageCount.Response)) { probe("count | type=" + p.getClass().getSimpleName()); return out; }
            count = ((FitnessData.MessageCount.Response) p).count & 0xFFFF;
            probe("count | " + count + " pages  from=" + startSec + " to=" + endSec);
        } catch (InterruptedException ie) { throw ie; }
        catch (Throwable t) { probe("count fail | " + why(t)); return out; }

        int bad = 0;
        for (int i = 0; i < count && i < MAX_PAGES; i++) {
            try {
                HuaweiPacket p = rr.call(new FitnessData.MessageData.Request(pp, FitnessData.MessageData.sleepId, (short) i), T, "SleepData#" + i);
                if (!(p instanceof FitnessData.MessageData.SleepResponse)) { bad++; continue; }
                FitnessData.MessageData.SleepResponse r = (FitnessData.MessageData.SleepResponse) p;
                for (FitnessData.MessageData.SleepResponse.SubContainer c : r.containers) {
                    byte[] ts = c.timestamp;
                    if (ts == null || ts.length < 6) continue;
                    long start = ((long) (ts[0] & 0xFF) << 24) | ((ts[1] & 0xFF) << 16) | ((ts[2] & 0xFF) << 8) | (ts[3] & 0xFF);
                    int minutes = ((ts[4] & 0xFF) << 8) | (ts[5] & 0xFF);
                    if (start <= 0 || minutes <= 0 || minutes > 24 * 60) continue;
                    out.add(new Seg(start, start + minutes * 60L, c.type & 0xFF));
                }
            } catch (InterruptedException ie) { throw ie; }
            catch (Throwable t) {
                bad++;
                if (bad <= 3) probe("page " + i + " fail | " + why(t));
                if (bad >= 8) { probe("too many bad pages, stop at " + i); break; }
            }
        }
        long deep = 0, light = 0, other = 0;
        java.util.Map<Integer, Integer> types = new java.util.TreeMap<>();
        for (Seg s : out) {
            long m = (s.end - s.start) / 60;
            if (s.type == 7) deep += m; else if (s.type == 6) light += m; else other += m;
            types.put(s.type, (types.containsKey(s.type) ? types.get(s.type) : 0) + 1);
        }
        probe((out.isEmpty() ? "empty" : "done") + " | segs=" + out.size() + " deep=" + deep + "m light=" + light + "m other=" + other + "m types=" + types + " bad=" + bad);
        return out;
    }

    private static String why(Throwable t) { return t.getClass().getSimpleName() + ":" + t.getMessage(); }

    private static void probe(String s) {
        try { Probe.post("hwsleep", s); } catch (Throwable ignored) { }
    }
}
