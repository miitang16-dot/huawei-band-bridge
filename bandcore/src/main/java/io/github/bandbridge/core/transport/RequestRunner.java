package io.github.bandbridge.core.transport;

import io.github.bandbridge.core.proto.HuaweiPacket;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 认证之后的「发一个请求、等它的响应」—— AuthFlow 那套同步写法抽出来给数据层用。
 *
 * 线程模型跟认证一样：
 *   · {@link #offer} 在 BLE 回调线程（PacketAssembler.Sink.onPacket 里）调，只投递不阻塞
 *   · {@link #call} 只能在 worker 线程调 —— 它阻塞等响应
 *
 * 同一时刻只等一个 (svc, cmd)。不是在等的包 offer 返回 false，交给 onAsync（记 hwasync 探针）。
 */
public final class RequestRunner {

    public interface Sender { void send(byte[] slice); }

    public static final class TimeoutException extends Exception {
        public TimeoutException(String what, long ms) { super(what + " timeout " + ms + "ms"); }
    }

    private final HuaweiPacket.ParamsProvider pp;
    private final Sender sender;
    private final LinkedBlockingQueue<HuaweiPacket> inbox = new LinkedBlockingQueue<>();
    private volatile int waitSvc = -1;
    private volatile int waitCmd = -1;

    public RequestRunner(HuaweiPacket.ParamsProvider pp, Sender sender) {
        this.pp = pp;
        this.sender = sender;
    }

    public HuaweiPacket.ParamsProvider params() { return pp; }

    /* ★3.292 文件下载：手环在一个 RequestBlock 之后会【连续推】好几个 0x2C/0x05 数据块，
       不是一问一答 ⇒ 另开一条「流」：开流期间所有 (streamSvc, streamCmd) 的包都进 streamBox */
    private final LinkedBlockingQueue<HuaweiPacket> streamBox = new LinkedBlockingQueue<>();
    private volatile int streamSvc = -1;
    private volatile int streamCmd = -1;

    /** cmd 传 -1 = 这个服务的所有命令都进流（★3.302 文件上传 0x28：手环按 02/03/04/05/07/08 一步步要）*/
    public void openStream(int svc, int cmd) { streamBox.clear(); streamSvc = svc & 0xFF; streamCmd = cmd < 0 ? -1 : (cmd & 0xFF); }
    public void closeStream() { streamSvc = -1; streamCmd = -1; streamBox.clear(); }
    /** 取下一个流里的包（已 parseTlv）；超时返回 null */
    public HuaweiPacket pollStream(long timeoutMs) throws Exception {
        HuaweiPacket p = streamBox.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (p != null) p.parseTlv();
        return p;
    }

    /** ★3.302 已经切好的片直接发（文件上传的数据块是 serializeFileChunk 自己切的）*/
    public void sendSlices(List<byte[]> slices) {
        if (slices == null) return;
        for (byte[] sl : slices) if (sl != null && sl.length > 0) sender.send(sl);
    }

    /** 只发不等（RequestBlock 这种：回的是一串流里的包，不是同号响应）*/
    public void sendOnly(HuaweiPacket req) throws Exception {
        List<byte[]> slices = req.serialize();
        if (slices == null) return;
        int mtu = pp.getMtu();
        for (byte[] slice : slices) {
            if (slice == null || slice.length == 0) continue;
            if (mtu > 0 && slice.length > mtu) {
                for (int off = 0; off < slice.length; off += mtu) {
                    int len = Math.min(mtu, slice.length - off);
                    byte[] chunk = new byte[len];
                    System.arraycopy(slice, off, chunk, 0, len);
                    sender.send(chunk);
                }
            } else {
                sender.send(slice);
            }
        }
    }

    /** BLE 回调线程：是我正在等的响应就收下（true），否则 false */
    public boolean offer(HuaweiPacket p) {
        if (p == null) return false;
        if (streamSvc >= 0 && (p.serviceId & 0xFF) == streamSvc && (streamCmd < 0 || (p.commandId & 0xFF) == streamCmd)) {
            streamBox.offer(p);
            return true;
        }
        int s = waitSvc, c = waitCmd;
        if (s < 0) return false;
        if ((p.serviceId & 0xFF) != s || (p.commandId & 0xFF) != c) return false;
        inbox.offer(p);
        return true;
    }

    /**
     * 发 req，阻塞等 (svc, cmd) 的响应，解好 TLV 返回。
     * 跟 AuthFlow.send 一样：serialize() 已经按 sliceSize 切好；某片比 mtu 还长再按 mtu 拆（GB Request.doPerform）。
     */
    public HuaweiPacket call(HuaweiPacket req, long timeoutMs, String what) throws Exception {
        final int svc = req.serviceId & 0xFF;
        final int cmd = req.commandId & 0xFF;
        inbox.clear();
        waitSvc = svc;
        waitCmd = cmd;
        try {
            List<byte[]> slices = req.serialize();
            if (slices == null || slices.isEmpty()) throw new IllegalStateException(what + ": serialize 空");
            int mtu = pp.getMtu();
            for (byte[] slice : slices) {
                if (slice == null || slice.length == 0) continue;
                if (mtu > 0 && slice.length > mtu) {
                    for (int off = 0; off < slice.length; off += mtu) {
                        int len = Math.min(mtu, slice.length - off);
                        byte[] chunk = new byte[len];
                        System.arraycopy(slice, off, chunk, 0, len);
                        sender.send(chunk);
                    }
                } else {
                    sender.send(slice);
                }
            }
            HuaweiPacket p = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (p == null) throw new TimeoutException(what, timeoutMs);
            p.parseTlv();
            return p;
        } finally {
            waitSvc = -1;
            waitCmd = -1;
        }
    }
}
