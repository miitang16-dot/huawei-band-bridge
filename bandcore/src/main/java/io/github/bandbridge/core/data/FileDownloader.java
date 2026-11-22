package io.github.bandbridge.core.data;

import io.github.bandbridge.core.Probe;
import io.github.bandbridge.core.proto.HuaweiPacket;
import io.github.bandbridge.core.proto.packets.FileDownloadService2C;
import io.github.bandbridge.core.transport.RequestRunner;

/**
 * 手环 → 手机 的文件下载（新同步，服务 0x2C）—— GB HuaweiFileDownloadManager 里 newSync 那条路，压成一个同步函数。
 *
 *   ① FileDownloadInit 0x2C/0x01（文件名 + 类型 + 时间范围 [+ dictId]）→ fileId + fileSize
 *   ② FileInfo        0x2C/0x03（fileId）→ 每块上限 maxBlockSize、是否不加密、超时
 *   ③ RequestBlock    0x2C/0x04（fileId, offset, size）只发不等 ⇒ 手环连续推 0x2C/0x05 数据块（带 offset）
 *      收满一块（或文件收完）再要下一块
 *   ④ FileDownloadCompleteRequest 0x2C/0x06（fileId, 1=成功）
 *
 * Band 10 是新同步：SettingRelated 回包 tag2 = 0x0e ⇒ truSleepNewSync（bit 2）= 1（3.280 探针）。
 * 只能在 worker 线程跑。失败抛异常（带人能看懂的原因），探针 hwfile 每步都记。
 */
public final class FileDownloader {

    private final RequestRunner rr;

    public FileDownloader(RequestRunner rr) { this.rr = rr; }

    public byte[] download(String filename, FileDownloadService2C.FileType type, int start, int end, int dictId) throws Exception {
        HuaweiPacket.ParamsProvider pp = rr.params();

        HuaweiPacket ip = rr.call(new FileDownloadService2C.FileDownloadInit.Request(pp, filename, type, start, end, dictId), 8000L, "FileInit");
        if (!(ip instanceof FileDownloadService2C.FileDownloadInit.Response))
            throw new IllegalStateException("init 回包类型不对：" + ip.getClass().getSimpleName());
        FileDownloadService2C.FileDownloadInit.Response init = (FileDownloadService2C.FileDownloadInit.Response) ip;
        final byte fileId = init.fileId;
        final int size = init.fileSize;
        probe("init | " + filename + " -> name=" + init.fileName + " id=" + fileId + " size=" + size);
        if (size < 0) throw new IllegalStateException("手环报的文件大小不对：" + size);
        if (size == 0) {
            rr.sendOnly(new FileDownloadService2C.FileDownloadCompleteRequest(pp, fileId, (byte) 1));
            return new byte[0];
        }

        HuaweiPacket fp = rr.call(new FileDownloadService2C.FileInfo.Request(pp, fileId), 8000L, "FileInfo");
        if (!(fp instanceof FileDownloadService2C.FileInfo.Response))
            throw new IllegalStateException("fileinfo 回包类型不对：" + fp.getClass().getSimpleName());
        FileDownloadService2C.FileInfo.Response info = (FileDownloadService2C.FileInfo.Response) fp;
        int maxBlock = info.maxBlockSize > 0 ? info.maxBlockSize : 4096;
        long waitMs = Math.max(5000L, (info.timeout & 0xFF) * 1000L);
        probe("info | maxBlock=" + maxBlock + " burst=" + info.burstSize + " noEncrypt=" + info.noEncrypt + " timeout=" + (info.timeout & 0xFF));

        byte[] buf = new byte[size];
        int got = 0;
        rr.openStream(FileDownloadService2C.id, FileDownloadService2C.BlockResponse.id);
        try {
            int blocks = 0;
            while (got < size) {
                if (++blocks > 4096) throw new IllegalStateException("块数异常");
                final int blockStart = got;
                int want = Math.min(size - got, maxBlock);
                rr.sendOnly(new FileDownloadService2C.RequestBlock(pp, fileId, got, want, info.noEncrypt, dictId));
                while (got < size && got - blockStart < want) {
                    HuaweiPacket bp = rr.pollStream(waitMs);
                    if (bp == null) throw new IllegalStateException("等数据块超时（已收 " + got + "/" + size + "）");
                    if (!(bp instanceof FileDownloadService2C.BlockResponse)) continue;
                    FileDownloadService2C.BlockResponse b = (FileDownloadService2C.BlockResponse) bp;
                    if (b.fileId != fileId || b.data == null) continue;
                    int off = b.offset;
                    if (off < 0 || off >= size) continue;
                    int n = Math.min(b.data.length, size - off);
                    System.arraycopy(b.data, 0, buf, off, n);
                    got = Math.max(got, off + n);
                }
            }
        } finally {
            rr.closeStream();
        }
        rr.sendOnly(new FileDownloadService2C.FileDownloadCompleteRequest(pp, fileId, (byte) 1));
        probe("done | " + filename + " " + got + "B");
        return buf;
    }

    private static void probe(String s) {
        try { Probe.post("hwfile", s); } catch (Throwable ignored) { }
    }
}
