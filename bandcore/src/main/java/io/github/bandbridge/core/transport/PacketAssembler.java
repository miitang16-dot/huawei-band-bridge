package io.github.bandbridge.core.transport;

import android.util.Log;

import io.github.bandbridge.core.Probe;
import io.github.bandbridge.core.proto.HuaweiPacket;

/**
 * 攒包 + 分发。等价于 Gadgetbridge 的 ResponseManager，只是把「谁认领这个包」的判定
 * 交给外面（认证流程 / 数据请求各自实现 Sink），不引入 GB 的 Request 体系。
 *
 * 为什么要攒：BLE 通知一次最多 MTU-3 字节，而华为协议包是分片的。
 * HuaweiPacket.parse() 会自己累积（getLeft() 告诉你还剩多少），所以这里只负责循环喂。
 */
public final class PacketAssembler {

    private static final String TAG = "NxHwAsm";

    public interface Sink {
        /**
         * 一个完整的包到了。
         * @return true = 我认领了（不往下传）；false = 不认识（当异步包）
         */
        boolean onPacket(HuaweiPacket p);
        /** 没人认领的包 —— 手环主动推的实时心率/通知等 */
        void onAsync(HuaweiPacket p);
    }

    private final HuaweiPacket.ParamsProvider params;
    private final Sink sink;

    private HuaweiPacket partial;

    public PacketAssembler(HuaweiPacket.ParamsProvider pp, Sink s) {
        this.params = pp;
        this.sink = s;
    }

    public synchronized void feed(byte[] data) {
        if (data == null || data.length == 0) return;

        int left = 0;
        byte[] buf = data;
        int guard = 0;
        do {
            if (guard++ > 64) {                 // 防呆：正常不可能一轮喂出 64 个包
                Log.w(TAG, "too many packets in one feed, dropping");
                partial = null;
                return;
            }
            if (left > 0) {
                byte[] rest = new byte[left];
                System.arraycopy(buf, buf.length - left, rest, 0, left);
                buf = rest;
            }

            HuaweiPacket got;
            try {
                if (partial == null) got = new HuaweiPacket(params).parse(buf);
                else                 got = partial.parse(buf);
                partial = got;
                left = got.getLeft();
            } catch (HuaweiPacket.ParseException e) {
                Log.w(TAG, "parse: " + e.getClass().getSimpleName() + " " + e.getMessage());
                probe("parse " + e.getClass().getSimpleName() + ":" + e.getMessage(), buf);
                partial = null;                // 丢掉半包，避免下一包跟着错
                return;
            } catch (Throwable t) {
                Log.w(TAG, "parse crash", t);
                probe("crash " + t.getClass().getSimpleName() + ":" + t.getMessage(), buf);
                partial = null;
                return;
            }

            if (got.complete) {
                boolean claimed = false;
                try { claimed = sink.onPacket(got); } catch (Throwable t) { Log.w(TAG, "sink", t); }
                if (!claimed) {
                    try { sink.onAsync(got); } catch (Throwable t) { Log.w(TAG, "sinkAsync", t); }
                }
                partial = null;
                left = got.getLeft();
            }
        } while (left > 0);
    }

    private static void probe(String what, byte[] b) {
        try {
            StringBuilder sb = new StringBuilder();
            int n = b == null ? 0 : Math.min(b.length, 32);
            for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i]));
            if (b != null && b.length > n) sb.append("…+").append(b.length - n);
            Probe.post("hwasm", what + " | " + (b == null ? 0 : b.length) + "B " + sb);
        } catch (Throwable ignored) { }
    }

    /** 断线重连时清掉半包，别把上一条链路的尾巴接到新链路上 */
    public synchronized void reset() { partial = null; }
}
