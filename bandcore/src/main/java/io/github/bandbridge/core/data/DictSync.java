package io.github.bandbridge.core.data;

import io.github.bandbridge.core.Probe;
import io.github.bandbridge.core.proto.HuaweiPacket;
import io.github.bandbridge.core.proto.HuaweiTLV;
import io.github.bandbridge.core.proto.packets.P2P;
import io.github.bandbridge.core.transport.RequestRunner;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * ★3.301 情绪 / HRV / 皮肤温度 —— GB HuaweiP2PDataDictionarySyncService（P2P 服务 0x34/0x01）。
 *
 * 为什么是这条路（3.300 探针里看到的）：
 *   手环每隔一阵推 DataSync 0x37/0x04（hw.device.emotion → hw.unitedevice.emotion），
 *   里面只有【数据类别 500031 + 一段起止时间】—— 那是「这段时间有情绪数据了」的通知，不带数值。
 *   数值要手机自己用 P2P 去拉（GB 就是这么拿情绪的）。
 *
 * 流程（全在 worker 线程，同步）：
 *   ① ping：cmd 1，com.huawei.health → hw.watch.health.filesync；手环回同序号的包，respCode 0xca = 服务在
 *   ② 请求：cmd 2，hw.unitedevice.datadictionarysync → hw.watch.health.filesync（指纹 UniteDeviceManagement / SystemApp），
 *          数据 = 0x01 + TLV{01:1, 02:类别, 05:起始毫秒, 06:现在毫秒, 0d:1}
 *   ③ 手环回 cmd 2（src = hw.watch.health.filesync）：先回它一个 ack（cmd 3，code 0xcf），再解
 *          0x01 + TLV{01:操作, 02:类别, 83[84{05 开始,06 结束,0c 修改, 87[88{09 类型, 0a/0b 值}]}]}
 *   ④ 有数据就从最后一条的时间 +1 秒接着要，直到回空（最多 30 页）
 *
 * 0x34/0x01 的包在开流期间都进 RequestRunner 的 streamBox（不开流时它们走 onAsync，只记探针）。
 */
public final class DictSync {

    public static final int EMOTION = 500031;
    public static final int HRV = 500044;
    public static final int SKIN_TEMP = 400012;

    private static final String FILESYNC = "hw.watch.health.filesync";
    private static final String MODULE = "hw.unitedevice.datadictionarysync";
    private static short seq = 1;

    private final RequestRunner rr;
    private boolean pingOk = false;
    /** 上一次 fetch 是不是超时 / 出错收场（手环不支持这一类时就是这样）*/
    public boolean lastFailed = false;

    public DictSync(RequestRunner rr) { this.rr = rr; }

    private static synchronized short nextSeq() {
        seq = (short) ((seq + 1) % (Short.MAX_VALUE - 1));
        return seq;
    }

    private void send(byte cmd, short s, String src, String dst, String srcFp, String dstFp, byte[] data, int code) throws Exception {
        rr.sendOnly(new P2P.P2PCommand.Request(rr.params(), cmd, s, src, dst, srcFp, dstFp, data, code));
    }

    /** ① 服务在不在。每个 DictSync 实例只问一次 */
    public boolean ping() {
        if (pingOk) return true;
        rr.openStream(P2P.id, P2P.P2PCommand.id);
        try {
            short s = nextSeq();
            send((byte) 1, s, "com.huawei.health", FILESYNC, null, null, null, 0);
            long until = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < until) {
                HuaweiPacket p = rr.pollStream(Math.max(1, until - System.currentTimeMillis()));
                if (!(p instanceof P2P.P2PCommand.Response)) continue;
                P2P.P2PCommand.Response r = (P2P.P2PCommand.Response) p;
                if (r.sequenceId != s) { probe("ping other | " + brief(r)); continue; }
                pingOk = (byte) r.respCode == (byte) 0xca;
                probe("ping | code=0x" + Integer.toHexString(r.respCode & 0xff) + " ok=" + pingOk);
                return pingOk;
            }
            probe("ping timeout");
        } catch (Throwable t) {
            probe("ping fail | " + t.getClass().getSimpleName() + ":" + t.getMessage());
        } finally { rr.closeStream(); }
        return false;
    }

    /**
     * 拉某一类从 fromMs 起的所有记录。每条：{start, end, mod, v:{"类型id": 数值}}（时间都是毫秒）。
     * 失败 / 服务不在返回空表。
     */
    public List<JSONObject> fetch(int dictClass, long fromMs) {
        List<JSONObject> all = new ArrayList<>();
        lastFailed = false;
        if (!ping()) return all;                          // ping 不通不算「不支持」：10 分钟后再试
        long start = Math.max(0, fromMs);
        rr.openStream(P2P.id, P2P.P2PCommand.id);
        try {
            for (int page = 0; page < 30; page++) {
                List<JSONObject> got = requestPage(dictClass, start);
                if (got == null) { lastFailed = page == 0; break; }   // 超时 / 出错（第一页就超时 = 多半不支持这一类）
                if (got.isEmpty()) break;                     // 拉完了
                long last = start;
                for (JSONObject o : got) {
                    all.add(o);
                    last = Math.max(last, Math.max(o.optLong("end"), o.optLong("mod")));
                    last = Math.max(last, o.optLong("start"));
                }
                if (last <= start) break;                     // 防死循环
                start = last + 1000;
            }
        } finally { rr.closeStream(); }
        probe("fetch | cls=" + dictClass + " n=" + all.size());
        return all;
    }

    private List<JSONObject> requestPage(int dictClass, long startMs) {
        try {
            HuaweiTLV tlv = new HuaweiTLV()
                    .put(0x1, (byte) 1)
                    .put(0x2, dictToBytes(dictClass))
                    .put(0x5, startMs)
                    .put(0x6, System.currentTimeMillis())
                    .put(0x0d, (byte) 1);
            byte[] body = tlv.serialize();
            byte[] data = new byte[1 + body.length];
            data[0] = 0x01;
            System.arraycopy(body, 0, data, 1, body.length);
            short s = nextSeq();
            send((byte) 2, s, MODULE, FILESYNC, "UniteDeviceManagement", "SystemApp", data, 0);

            long until = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < until) {
                HuaweiPacket p = rr.pollStream(Math.max(1, until - System.currentTimeMillis()));
                if (!(p instanceof P2P.P2PCommand.Response)) continue;
                P2P.P2PCommand.Response r = (P2P.P2PCommand.Response) p;
                if (r.cmdId == 3) {                           // 手环对我们那条的 ack
                    if (r.sequenceId == s && (r.respCode & 0xff) != 0xca && (r.respCode & 0xff) != 0xcf)
                        probe("req ack | cls=" + dictClass + " code=0x" + Integer.toHexString(r.respCode & 0xff));
                    continue;
                }
                if (r.cmdId == 2 && FILESYNC.equals(r.srcPackage)) {
                    send((byte) 3, r.sequenceId, r.dstPackage, r.srcPackage, null, null, null, 0xcf);   // GB sendAck(… 0xcf)
                    List<JSONObject> res = parse(r.respData, dictClass);
                    if (res != null) return res;
                    continue;                                 // 别的类别的回包
                }
                probe("other | " + brief(r));
            }
            probe("page timeout | cls=" + dictClass);
        } catch (Throwable t) {
            probe("page fail | cls=" + dictClass + " " + t.getClass().getSimpleName() + ":" + t.getMessage());
        }
        return null;
    }

    /** 不是这一类的回包返回 null */
    private List<JSONObject> parse(byte[] data, int want) throws Exception {
        if (data == null || data.length < 2 || data[0] != 1) { probe("data odd | " + hex(data)); return new ArrayList<>(); }
        HuaweiTLV tlv = new HuaweiTLV();
        tlv.parse(data, 1, data.length - 1);
        int op = tlv.getAsInteger(0x01);
        int cls = tlv.getAsInteger(0x02);
        if (cls != want) return null;
        List<JSONObject> out = new ArrayList<>();
        if (op != 1) { probe("op " + op + " | cls=" + cls); return out; }
        for (HuaweiTLV block : tlv.getObjects(0x83)) {
            for (HuaweiTLV l : block.getObjects(0x84)) {
                JSONObject o = new JSONObject();
                o.put("start", l.getAsLong(0x5));
                if (l.contains(0x6)) o.put("end", l.getAsLong(0x6));
                if (l.contains(0xc)) o.put("mod", l.getAsLong(0xc));
                JSONObject v = new JSONObject();
                for (HuaweiTLV l1 : l.getObjects(0x87)) {
                    for (HuaweiTLV ll : l1.getObjects(0x88)) {
                        int type = ll.getAsInteger(0x9);
                        byte[] raw = ll.contains(0xa) ? ll.getBytes(0xa) : (ll.contains(0xb) ? ll.getBytes(0xb) : null);
                        if (raw == null) continue;
                        Double d = toDouble(raw);
                        if (d != null && !d.isNaN() && !d.isInfinite()) v.put(String.valueOf(type), d);
                    }
                }
                o.put("v", v);
                out.add(o);
            }
        }
        if (out.size() > 0) probe("page | cls=" + cls + " n=" + out.size() + " first=" + out.get(0));
        return out;
    }

    /** GB HuaweiUtil.convBytes2Double：8 字节大端 double；别的长度按大端整数 */
    private static Double toDouble(byte[] b) {
        if (b.length == 8) return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getDouble();
        if (b.length == 4) return (double) ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getInt();
        if (b.length == 2) return (double) ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getShort();
        if (b.length == 1) return (double) (b[0] & 0xff);
        return null;
    }

    /** GB dictToBytes：变长大端 */
    static byte[] dictToBytes(int value) {
        if (value < 256) return new byte[]{(byte) value};
        int n = 0, t = value;
        while (t > 0) { t >>= 8; n++; }
        byte[] b = new byte[n];
        for (int j = n - 1; j >= 0; j--) { b[j] = (byte) (value & 0xFF); value >>= 8; }
        return b;
    }

    private static String brief(P2P.P2PCommand.Response r) {
        return "cmd=" + r.cmdId + " seq=" + r.sequenceId + " " + r.srcPackage + "→" + r.dstPackage
                + " code=0x" + Integer.toHexString(r.respCode & 0xff) + " data=" + hex(r.respData);
    }

    private static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, 48); i++) sb.append(String.format("%02x", b[i]));
        if (b.length > 48) sb.append("…+").append(b.length - 48);
        return sb.toString();
    }

    private static void probe(String s) {
        try { Probe.post("hwdict", s); } catch (Throwable ignored) { }
    }
}
