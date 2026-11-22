package io.github.bandbridge.core.data;

import io.github.bandbridge.core.Probe;
import io.github.bandbridge.core.proto.HuaweiPacket;
import io.github.bandbridge.core.proto.packets.FitnessData;
import io.github.bandbridge.core.transport.RequestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * 心率 / 血氧 / 步数 / 静息心率 —— 手环按「每分钟一格」存的那份（GB GetStepDataCountRequest → GetStepDataRequest）。
 *
 *   ① MessageCount 0x07/0x0A（start, end 秒）→ 一共几页
 *   ② MessageData  0x07/0x0B（第 i 页）    → StepResponse：一页若干格，每格 = 某一分钟的 steps/hr/spo/rhr
 *
 * 只能在 worker 线程跑（RequestRunner.call 阻塞）。每页失败只记一笔、继续下一页。
 */
public final class StepSync {

    public static final class Sample {
        public final long t;      // 秒
        public final int hr, spo, steps, rhr;
        Sample(long t, int hr, int spo, int steps, int rhr) { this.t = t; this.hr = hr; this.spo = spo; this.steps = steps; this.rhr = rhr; }
    }

    private static final long T = 6000L;
    private static final int MAX_PAGES = 400;   // 防呆：正常一天几十页

    private final RequestRunner rr;

    public StepSync(RequestRunner rr) { this.rr = rr; }

    /* ★3.294 每分钟一轮之后，count / done 两条探针每分钟都写太吵 ⇒ quiet 时只写失败 */
    private boolean quiet = false;
    public StepSync quiet(boolean q) { this.quiet = q; return this; }

    public List<Sample> run(int startSec, int endSec) throws InterruptedException {
        List<Sample> out = new ArrayList<>();
        HuaweiPacket.ParamsProvider pp = rr.params();
        int count;
        try {
            HuaweiPacket p = rr.call(new FitnessData.MessageCount.Request(pp, FitnessData.MessageCount.stepId, startSec, endSec), T, "StepCount");
            if (!(p instanceof FitnessData.MessageCount.Response)) { probe("count | type=" + p.getClass().getSimpleName()); return out; }
            count = ((FitnessData.MessageCount.Response) p).count & 0xFFFF;
            if (!quiet) probe("count | " + count + " pages  from=" + startSec + " to=" + endSec);
        } catch (InterruptedException ie) { throw ie; }
        catch (Throwable t) { probe("count fail | " + why(t)); return out; }

        int bad = 0;
        for (int i = 0; i < count && i < MAX_PAGES; i++) {
            try {
                HuaweiPacket p = rr.call(new FitnessData.MessageData.Request(pp, FitnessData.MessageData.stepId, (short) i), T, "StepData#" + i);
                if (!(p instanceof FitnessData.MessageData.StepResponse)) { bad++; continue; }
                FitnessData.MessageData.StepResponse r = (FitnessData.MessageData.StepResponse) p;
                for (FitnessData.MessageData.StepResponse.SubContainer c : r.containers) {
                    if (c.parsedData == null) continue;
                    /* 心率/血氧/静息心率在包里是单字节、按有符号读出来的：
                       -1 = 这一分钟没有（也是 0xFF 无效值）；>127 的心率读出来是负数 ⇒ &0xFF 还原，别当成「没有」 */
                    int hr = c.heartrate == -1 ? 0 : (c.heartrate & 0xFF);
                    int spo = c.spo == -1 ? 0 : (c.spo & 0xFF);
                    int rhr = c.restingHeartRate == -1 ? 0 : (c.restingHeartRate & 0xFF);
                    int steps = c.steps < 0 ? 0 : c.steps;
                    if (spo > 100) spo = 0;
                    if (hr == 0 && spo == 0 && steps == 0 && rhr == 0) continue;
                    out.add(new Sample(c.timestamp, hr, spo, steps, rhr));
                }
            } catch (InterruptedException ie) { throw ie; }
            catch (Throwable t) {
                bad++;
                if (bad <= 3) probe("page " + i + " fail | " + why(t));
                if (bad >= 8) { probe("too many bad pages, stop at " + i); break; }
            }
        }
        int nHr = 0, nSpo = 0, lastHr = 0, lastSpo = 0;
        for (Sample s : out) { if (s.hr > 0) { nHr++; lastHr = s.hr; } if (s.spo > 0) { nSpo++; lastSpo = s.spo; } }
        if (!quiet || bad > 0) probe("done | samples=" + out.size() + " hr=" + nHr + " (last " + lastHr + ") spo=" + nSpo + " (last " + lastSpo + ") bad=" + bad);
        return out;
    }

    private static String why(Throwable t) { return t.getClass().getSimpleName() + ":" + t.getMessage(); }

    private static void probe(String s) {
        try { Probe.post("hwsync", s); } catch (Throwable ignored) { }
    }
}
