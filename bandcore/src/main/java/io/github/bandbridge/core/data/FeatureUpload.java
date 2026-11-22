package io.github.bandbridge.core.data;

import io.github.bandbridge.core.Probe;
import io.github.bandbridge.core.proto.HuaweiPacket;
import io.github.bandbridge.core.proto.packets.DataSync;
import io.github.bandbridge.core.proto.packets.FileUpload;
import io.github.bandbridge.core.proto.packets.HuaweiDataSyncCommon;
import io.github.bandbridge.core.transport.RequestRunner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * ★3.302 功能清单上传 —— GB HuaweiDataSyncFeatureManager + HuaweiUploadManager（文件上传 0x28）。
 *
 * 而手环一直在推 DataSync 0x37/0x01（featureManager → hw.unitedevice.configManager）问
 * com.huawei.health_deviceFeature_config —— 我们从没回过 ⇒ 手环以为手机端不支持那些功能。
 *
 * GB 的回法（照搬）：
 *   ① DataSync 配置 src=hw.unitedevice.configManager → dst=featureManager，configId 900100007，action 2，data "01"
 *   ② 上传 feature_<国家>_device.txt（类型 0x0f，JSON：featureList 里每项 {devPackageName, support:1, extInfo}）
 *      0x28/0x02 文件信息 → 手环 0x03 要哈希（给 fileId）→ 回 SHA256 → 手环 0x04 协商参数 → 回 ack
 *      → 手环 0x05 一块块要（已传 / 下一块大小）→ 0x06 送块 → 手环 0x07 说完了 → 回 0x07 ok
 *   ③ 传完再发一次配置 action 1，data = 通知 JSON（configName / filterCountry / fileName / version）
 *
 * 全在 worker 线程同步跑，0x28 的包开流期间都进 streamBox。
 */
public final class FeatureUpload {

    private static final int VERSION = 1;
    private final RequestRunner rr;

    public FeatureUpload(RequestRunner rr) { this.rr = rr; }

    private static JSONObject feat(String pkg, JSONObject ext) throws Exception {
        JSONObject o = new JSONObject();
        o.put("devPackageName", pkg);
        o.put("support", 1);
        if (ext != null) o.put("extInfo", ext);
        return o;
    }

    /** GB getFeaturesFileContent：同一份清单（情绪那项是关键）*/
    private static String fileContent(String cc) throws Exception {
        JSONArray list = new JSONArray();
        list.put(feat("com.huawei.health.pwvdetection", new JSONObject().put("medical", false)));
        list.put(feat("com.huawei.health.bloodpressure", new JSONObject()
                .put("ABPM", new JSONArray().put(135).put(85).put(120).put(70).put(130).put(80)).put("local", 2)));
        list.put(feat("com.huawei.watch.health.ecganalysis", new JSONObject().put("medical", false)));
        list.put(feat("com.huawei.health.healthcheck", new JSONObject().put("checkItem", 23)));
        list.put(feat("com.huawei.hmos.watch.emotional", null));
        list.put(feat("com.huawei.hmos.watch.plateaucare", new JSONObject().put("medical", false)));
        list.put(feat("com.huawei.watch.health.arrhythmia", new JSONObject().put("medical", false)));
        JSONObject o = new JSONObject();
        o.put("featureList", list);
        o.put("country", cc);
        o.put("ver", VERSION);
        o.put("name", "feature_" + cc + "_device");
        return o.toString();
    }

    private static String notifyJson(String cc) throws Exception {
        JSONObject o = new JSONObject();
        o.put("filterCondition", new JSONArray().put(new JSONObject().put("filterCountry", cc)));
        o.put("configName", "com.huawei.health_deviceFeature_config");
        o.put("fileData", new JSONArray().put(new JSONObject().put("version", VERSION).put("fileName", "feature_" + cc + "_device.txt")));
        return o.toString();
    }

    private void sendConfig(byte action, byte[] data) throws Exception {
        HuaweiDataSyncCommon.ConfigData cd = new HuaweiDataSyncCommon.ConfigData();
        cd.configId = 900100007;
        cd.configAction = action;
        cd.configData = data;
        List<HuaweiDataSyncCommon.ConfigData> l = new ArrayList<>();
        l.add(cd);
        HuaweiDataSyncCommon.ConfigCommandData d = new HuaweiDataSyncCommon.ConfigCommandData();
        d.setConfigDataList(l);
        HuaweiPacket req = new DataSync.ConfigCommand.Request(rr.params(), "hw.unitedevice.configManager", "featureManager", d);
        req.complete = true;
        rr.sendOnly(req);
    }

    /** 跑一遍。成功 true；失败 false（探针 hwfeat 里写在哪一步）*/
    public boolean run(String cc) throws InterruptedException {
        byte[] file;
        String name = "feature_" + cc + "_device.txt";
        try {
            file = fileContent(cc).getBytes(StandardCharsets.UTF_8);
            sendConfig((byte) 2, new byte[]{0x30, 0x31});
        } catch (Throwable t) { probe("prep fail | " + t.getMessage()); return false; }

        byte[] sha;
        try { sha = MessageDigest.getInstance("SHA-256").digest(file); }
        catch (Throwable t) { probe("sha fail"); return false; }

        rr.openStream(FileUpload.id, -1);
        try {
            rr.sendOnly(new FileUpload.FileInfoSend.Request(rr.params(), file.length, name, (byte) 0x0f, null, null, null, null));
            probe("info sent | " + name + " " + file.length + "B");
            byte fileId = 0;
            int unit = 0;
            boolean enc = true;
            long until = System.currentTimeMillis() + 30000L;
            while (System.currentTimeMillis() < until) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                HuaweiPacket p;
                try { p = rr.pollStream(Math.max(1, until - System.currentTimeMillis())); }
                catch (InterruptedException ie) { throw ie; }
                catch (Throwable t) { probe("parse | " + t.getClass().getSimpleName() + ":" + t.getMessage()); continue; }
                if (p == null) break;
                int cmd = p.commandId & 0xFF;
                if (p instanceof FileUpload.FileInfoSend.Response) {
                    int r = ((FileUpload.FileInfoSend.Response) p).result;
                    probe("info resp | " + r);
                    if (r != 100000) return false;
                } else if (p instanceof FileUpload.FileHashSend.Response) {
                    fileId = ((FileUpload.FileHashSend.Response) p).fileId;
                    rr.sendOnly(new FileUpload.FileHashSend.Request(rr.params(), sha, fileId));
                    probe("hash sent | fileId=" + fileId);
                } else if (p instanceof FileUpload.FileUploadConsultAck.Response) {
                    FileUpload.FileUploadParams fp = ((FileUpload.FileUploadConsultAck.Response) p).fileUploadParams;
                    unit = fp.unit_size;
                    enc = fp.no_encrypt == 0;
                    rr.sendOnly(new FileUpload.FileUploadConsultAck.Request(rr.params(), fp.no_encrypt, fileId));
                    probe("consult | unit=" + unit + " noEnc=" + fp.no_encrypt + " maxApply=" + fp.max_apply_data_size);
                } else if (p instanceof FileUpload.FileNextChunkParams) {
                    FileUpload.FileNextChunkParams np = (FileUpload.FileNextChunkParams) p;
                    int pos = np.bytesUploaded, size = Math.min(np.nextchunkSize, file.length - np.bytesUploaded);
                    if (pos < 0 || size <= 0) { probe("chunk odd | pos=" + pos + " size=" + np.nextchunkSize); continue; }
                    byte[] chunk = new byte[size];
                    System.arraycopy(file, pos, chunk, 0, size);
                    boolean useEnc = enc && rr.params().areTransactionsCrypted();
                    rr.sendSlices(new FileUpload.FileNextChunkSend(rr.params()).serializeFileChunk(chunk, pos, unit, fileId, useEnc));
                    probe("chunk | pos=" + pos + " size=" + size);
                } else if (cmd == FileUpload.FileUploadResult.id) {
                    rr.sendOnly(new FileUpload.FileUploadResult.Request(rr.params(), fileId));
                    probe("upload done");
                    rr.closeStream();
                    sendConfig((byte) 1, notifyJson(cc).getBytes(StandardCharsets.UTF_8));
                    probe("notify sent | cc=" + cc);
                    return true;
                } else if (p instanceof FileUpload.FileUploadDeviceResponse.Response) {
                    probe("device error | code=" + ((FileUpload.FileUploadDeviceResponse.Response) p).code);
                    return false;
                } else {
                    probe("other | cmd=0x" + Integer.toHexString(cmd));
                }
            }
            probe("timeout");
        } catch (InterruptedException ie) {
            throw ie;
        } catch (Throwable t) {
            probe("fail | " + t.getClass().getSimpleName() + ":" + t.getMessage());
        } finally { rr.closeStream(); }
        return false;
    }

    private static void probe(String s) {
        try { Probe.post("hwfeat", s); } catch (Throwable ignored) { }
    }
}
