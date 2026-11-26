package io.github.bandbridge.core.data;

import io.github.bandbridge.core.Probe;
import io.github.bandbridge.core.proto.HuaweiDeviceType;
import io.github.bandbridge.core.proto.HuaweiPacket;
import io.github.bandbridge.core.proto.HuaweiTLV;
import io.github.bandbridge.core.proto.packets.DeviceConfig;
import io.github.bandbridge.core.proto.packets.FitnessData;
import io.github.bandbridge.core.proto.packets.DataSync;
import io.github.bandbridge.core.proto.packets.HuaweiDataSyncCommon;
import io.github.bandbridge.core.transport.RequestRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 认证之后的初始化 —— 对应 GB {@code HuaweiSupportProvider.initializeDeviceConfigure()}：
 *   ① GetProductInformation  0x01/0x07
 *   ② SetTime                0x01/0x05
 *   ③ GetBatteryLevel        0x01/0x08
 *   ④ GetSupportedServices   0x01/0x02  → GetSupportedCommands 0x01/0x03（分批，直到问完）
 *
 * 为什么要有：真机 3.276 认证通过了，但不发这几步，手环那边判「连接失败」。
 * GB 里 ④ 之后还有 ExpandCapability / 动态服务注册，这里先不做 —— 先看手环认不认。
 *
 */
public final class DeviceInit {

    private static final long T = 5000L;   // GB initTimeout 量级

    private final RequestRunner rr;

    /** 手环支持的 service → commands（④ 的结果；数据层用它判断能不能发 0x07/0x1c 实时心率） */
    public final Map<Integer, byte[]> commands = new TreeMap<>();
    public String model = "";
    public String softwareVersion = "";
    public int battery = -1;
    public int okSteps = 0;
    /** ExpandCapability 的能力位（finishPairing 拿到；情绪开关要查第 83 / 206 位）*/
    public byte[] expand = null;

    public DeviceInit(RequestRunner rr) { this.rr = rr; }

    public void run() throws InterruptedException {
        HuaweiPacket.ParamsProvider pp = rr.params();
        HuaweiDeviceType type = pp.isAW() ? HuaweiDeviceType.AW : HuaweiDeviceType.BLE;

        // ① 设备信息
        try {
            HuaweiPacket p = rr.call(new DeviceConfig.ProductInfo.Request(pp, type), T, "ProductInfo");
            if (p instanceof DeviceConfig.ProductInfo.Response) {
                DeviceConfig.ProductInfo.Response r = (DeviceConfig.ProductInfo.Response) p;
                model = nz(r.productModel);
                softwareVersion = nz(r.softwareVersion);
                probe("product | model=" + model + " sw=" + softwareVersion + " hw=" + nz(r.hardwareVersion)
                        + " name=" + nz(r.deviceName));
            } else {
                probe("product | type=" + p.getClass().getSimpleName());
            }
            okSteps++;
        } catch (InterruptedException ie) { throw ie; }
        catch (Throwable t) { probe("product fail | " + why(t)); }

        // ② 对时
        try {
            rr.call(new DeviceConfig.TimeRequest(pp), T, "SetTime");
            probe("time | ok");
            okSteps++;
        } catch (InterruptedException ie) { throw ie; }
        catch (Throwable t) { probe("time fail | " + why(t)); }

        // ③ 电量
        try {
            HuaweiPacket p = rr.call(new DeviceConfig.BatteryLevel.Request(pp), T, "Battery");
            if (p instanceof DeviceConfig.BatteryLevel.Response) {
                battery = ((DeviceConfig.BatteryLevel.Response) p).level & 0xFF;
            }
            probe("battery | " + battery);
            okSteps++;
        } catch (InterruptedException ie) { throw ie; }
        catch (Throwable t) { probe("battery fail | " + why(t)); }

        // ④ 支持哪些服务 → 每个服务支持哪些命令
        try {
            byte[] known = DeviceConfig.SupportedServices.knownSupportedServices;
            HuaweiPacket p = rr.call(new DeviceConfig.SupportedServices.Request(pp, known), T, "SupportedServices");
            List<Byte> active = new ArrayList<>();
            active.add((byte) 0x01);                         // 0x01 永远支持（GB 同）
            if (p instanceof DeviceConfig.SupportedServices.Response) {
                byte[] sup = ((DeviceConfig.SupportedServices.Response) p).supportedServices;
                if (sup != null) {
                    for (int i = 0; i < sup.length && i < known.length; i++) {
                        if (sup[i] == 1) active.add(known[i]);
                    }
                }
            }
            probe("services | " + hexList(active));
            okSteps++;

            // 只问我们手上有命令表的服务（GB 也是查 commandsPerService）
            List<Byte> todo = new ArrayList<>();
            for (Byte b : active) {
                if (DeviceConfig.SupportedCommands.commandsPerService.containsKey(b & 0xFF)) todo.add(b);
            }
            int guard = 0;
            while (!todo.isEmpty() && guard++ < 16) {
                DeviceConfig.SupportedCommands.Request req = new DeviceConfig.SupportedCommands.Request(pp);
                byte next = todo.remove(0);
                boolean fits = req.addCommandsForService(next,
                        DeviceConfig.SupportedCommands.commandsPerService.get(next & 0xFF));
                while (fits && !todo.isEmpty()) {
                    next = todo.remove(0);
                    fits = req.addCommandsForService(next,
                            DeviceConfig.SupportedCommands.commandsPerService.get(next & 0xFF));
                }
                if (!fits) todo.add(0, next);           // 这一批放不下，留给下一批
                HuaweiPacket rp = rr.call(req, T, "SupportedCommands");
                if (rp instanceof DeviceConfig.SupportedCommands.Response) {
                    for (DeviceConfig.SupportedCommands.Response.CommandsList cl
                            : ((DeviceConfig.SupportedCommands.Response) rp).commandsLists) {
                        commands.put(cl.service, cl.commands);
                    }
                }
            }
            probe("commands | " + describeCommands()
                    + " | realtimeHR(0x07/0x1c)=" + supports(0x07, 0x1c));
            okSteps++;
        } catch (InterruptedException ie) { throw ie; }
        catch (Throwable t) { probe("services fail | " + why(t)); }

        finishPairing(pp);
        enableMonitoring(pp);
    }

    /**
     * 3.282 真机：同步命令手环回了（1 页）但一条数据都没有 —— 出厂后这些开关全是关的，平时是华为运动健康配对时打开的。
     * GB 里对应：SetAutomaticHeartrateRequest / SetAutomaticSpoRequest / SetTruSleepRequest / SetActivateOnLiftRequest。
     * 都先查手环命令表（④）里有没有，有才发。
     * ⚠️ 自动压力（0x20/0x09）GB 要先有一次「压力测试」的结果（分数 + 特征）才能打开 —— 这里不发，见 LATEST。
     */
    private void enableMonitoring(HuaweiPacket.ParamsProvider pp) throws InterruptedException {
        final long t = 3000L;
        if (supports(0x07, 0x17)) soft(new FitnessData.EnableAutomaticHeartrate.Request(pp, true), t, "autoHR on");
        if (supports(0x07, 0x24)) soft(new FitnessData.EnableAutomaticSpo.Request(pp, true), t, "autoSpO2 on");
        if (supports(0x07, 0x16)) soft(new FitnessData.TruSleep.Request(pp, true), t, "truSleep on");
        if (supports(0x01, 0x09)) soft(new DeviceConfig.ActivateOnLiftRequest(pp, true), t, "liftWake on");
        enableEmotion(pp);
    }

    /**
     * GB HuaweiDataSyncEmotion.changeEmotionsState(true)：DataSync 配置命令 0x37/0x01，
     *   src = "hw.health.emotion" → dst = "hw.watch.health.emotion"，configId 900500028，action 1，data = TLV{01: 1}
     * 前提（GB HuaweiState）：supportsDeviceCommandConfig = 扩展能力第 83 位；supportsEmotion = 第 206 位。
     * ⚠️ 手环自动测压力（0x20/0x09）是另一条，GB 要先有一次压力测试结果才能开 —— 这里不发。
     */
    private void enableEmotion(HuaweiPacket.ParamsProvider pp) throws InterruptedException {
        boolean cfg = expandBit(expand, 83), emo = expandBit(expand, 206);
        probe("emotion caps | config=" + cfg + " emotion=" + emo);
        if (!cfg || !emo) return;
        HuaweiDataSyncCommon.ConfigData cd = new HuaweiDataSyncCommon.ConfigData();
        cd.configId = 900500028;
        cd.configAction = 1;
        cd.configData = new HuaweiTLV().put(0x01, (byte) 0x01).serialize();
        List<HuaweiDataSyncCommon.ConfigData> list = new ArrayList<>();
        list.add(cd);
        HuaweiDataSyncCommon.ConfigCommandData data = new HuaweiDataSyncCommon.ConfigCommandData();
        data.setConfigDataList(list);
        HuaweiPacket req = new DataSync.ConfigCommand.Request(pp, "hw.health.emotion", "hw.watch.health.emotion", data);
        req.complete = true;
        soft(req, 3000L, "emotion on");
    }

    /** 用户资料（身高 cm / 体重 kg / 生日 / 性别 1 男 2 女）—— GB SendFitnessUserInfoRequest。手环算卡路里、压力要用 */
    public static final class Profile {
        public final int height, year, month, day, gender;
        public final float weight;
        public Profile(int height, float weight, int year, int month, int day, int gender) {
            this.height = height; this.weight = weight; this.year = year; this.month = month; this.day = day; this.gender = gender;
        }
    }

    public void sendProfile(Profile pf) throws InterruptedException {
        if (pf == null) return;
        java.util.Calendar now = java.util.Calendar.getInstance();
        int age = now.get(java.util.Calendar.YEAR) - pf.year;
        if (now.get(java.util.Calendar.MONTH) + 1 < pf.month
                || (now.get(java.util.Calendar.MONTH) + 1 == pf.month && now.get(java.util.Calendar.DAY_OF_MONTH) < pf.day)) age--;
        int birthday = (pf.year << 16) + (pf.month << 8) + pf.day;
        FitnessData.UserInfo.UserInfoData d = new FitnessData.UserInfo.UserInfoData(
                pf.height, pf.weight, age, birthday, (byte) pf.gender, false, false, 0, 0, false);
        HuaweiPacket req = new FitnessData.UserInfo.Request(rr.params(), d);
        req.complete = true;
        soft(req, 3000L, "profile h=" + pf.height + " age=" + age);
    }

    /**
     * ⑤ GB initializeDynamicServices 里跟「配对收尾」有关的那几步（其余几十个设置项不发）：
     *   ExpandCapability 0x01/0x37 → AcceptAgreement 0x01/0x30 → SetUpDeviceStatus 0x01/0x3E（多设备才发）
     *   → ConnectStatus 0x01/0x35
     * 3.277 真机：认证 + ①~④ 全有响应，但手环一直停在配对界面 —— 手环在等手机「同意协议 / 报告已连上」。
     * 每步都先查 ④ 拿到的命令表，手环不支持就跳过（GB 的 requestSupported() 同理）；没回包不算失败（有的命令不回）。
     */
    private void finishPairing(HuaweiPacket.ParamsProvider pp) throws InterruptedException {
        final long t = 3000L;
        expand = null;

        if (supports(0x01, 0x37)) {
            try {
                HuaweiPacket p = rr.call(new DeviceConfig.ExpandCapability.Request(pp), t, "ExpandCapability");
                if (p instanceof DeviceConfig.ExpandCapability.Response) {
                    expand = ((DeviceConfig.ExpandCapability.Response) p).expandCapabilities;
                }
                probe("expand | " + (expand == null ? "null" : expand.length + "B") + " multiDevice=" + expandBit(expand, 109));
            } catch (InterruptedException ie) { throw ie; }
            catch (Throwable e) { probe("expand fail | " + why(e)); }
        }

        if (supports(0x01, 0x30)) {
            try {
                rr.call(new DeviceConfig.AcceptAgreement.Request(pp), t, "AcceptAgreement");
                probe("agreement | ok");
            } catch (InterruptedException ie) { throw ie; }
            catch (RequestRunner.TimeoutException e) { probe("agreement | sent, no reply"); }
            catch (Throwable e) { probe("agreement fail | " + why(e)); }
        }

        if (expandBit(expand, 109)) {                      // GB supportsMultiDevice()
            try {
                String name = model.isEmpty() ? "HUAWEI Band" : model;
                rr.call(new DeviceConfig.SetUpDeviceStatusRequest(pp, 1, name), t, "SetUpDeviceStatus");
                probe("setupstatus | ok");
            } catch (InterruptedException ie) { throw ie; }
            catch (RequestRunner.TimeoutException e) { probe("setupstatus | sent, no reply"); }
            catch (Throwable e) { probe("setupstatus fail | " + why(e)); }
        }

        /*
           协议层什么都通了，引导页在等「新手机完成设置」。官方 App 配对时会把账号/语言/日期格式这些推过去。
             ExtendedAccount 0x1A/0x05（GB 第 2 个）→ SettingRelated 0x01/0x31 → …（上面已发的）…
             → Language 0x0C/0x01 → DateFormat 0x01/0x04
           哪一步让它退出引导页，GB 里没写 —— 全发，探针逐条记。 */
        if (supports(0x1A, 0x05) && supports(0x1A, 0x06)) {          // GB supportsAccountJudgment && supportsAccountSwitch
            HuaweiTLV tlv = new HuaweiTLV().put(0x01, (byte) 0x00);   // GB：没账号 ⇒ 0x01=0
            if (expandBit(expand, 0xac)) tlv.put(0x03, (byte) 0x01);  // supportsDiffAccountPairingOptimization
            soft(raw(pp, 0x1A, 0x05, tlv), t, "account");
        } else if (supports(0x1A, 0x01)) {
            // 老一点的手环（比如 Band 7）命令表里可能没有 0x1A/0x05，只有老的 SendAccountToDevice 0x1A/0x01。
            // GB AccountRelated.SendAccountToDevice：没账号 ⇒ 0x01 空值。猜它跟新的那条一样，是「新手机设置」的一步。
            soft(raw(pp, 0x1A, 0x01, new HuaweiTLV().put(0x01)), t, "account(legacy)");
        }
        if (supports(0x01, 0x31)) soft(new DeviceConfig.SettingRelated.Request(pp), t, "settingrelated");

        if (supports(0x01, 0x35)) {
            try {
                rr.call(new DeviceConfig.ConnectStatusRequest(pp), t, "ConnectStatus");
                probe("connectstatus | ok");
            } catch (InterruptedException ie) { throw ie; }
            catch (RequestRunner.TimeoutException e) { probe("connectstatus | sent, no reply"); }
            catch (Throwable e) { probe("connectstatus fail | " + why(e)); }
        }

        if (supports(0x0C, 0x01)) {
            Locale lc = Locale.getDefault();
            String country = lc.getCountry().isEmpty() ? lc.getLanguage() : lc.getCountry();
            String loc = lc.getLanguage() + "-" + country.toUpperCase(Locale.ROOT);
            HuaweiTLV tlv = new HuaweiTLV().put(0x01, loc.getBytes(StandardCharsets.UTF_8)).put(0x02, (byte) 0x00);  // 公制
            soft(raw(pp, 0x0C, 0x01, tlv), t, "language " + loc);
        }
        if (supports(0x01, 0x32)) {                                   // GB SetTimeZoneIdRequest：时间 + 时区名
            soft(new DeviceConfig.TimeZoneIdRequest(pp), t, "timezone");
        }
        if (supports(0x01, 0x04)) {
            soft(new DeviceConfig.DateFormat.Request(pp, (byte) DeviceConfig.Date.yearFirst, (byte) DeviceConfig.Time.hours24), t, "dateformat");
        }
        okSteps++;
    }

    /** 发一个设置类请求：有回包记 ok，没回包记 no reply（有的命令本来就不回），都不算失败 */
    private void soft(HuaweiPacket req, long t, String name) throws InterruptedException {
        try {
            HuaweiPacket p = rr.call(req, t, name);
            probe(name + " | ok" + (p.getTlv() == null ? "" : " " + tlvHexShort(p)));
        } catch (InterruptedException ie) { throw ie; }
        catch (RequestRunner.TimeoutException e) { probe(name + " | sent, no reply"); }
        catch (Throwable e) { probe(name + " fail | " + why(e)); }
    }

    /** proto 里没搬的服务（AccountRelated 0x1A / LocaleConfig 0x0C）—— 直接拼一个包，跟 GB 那几个类发的字节一样 */
    private static HuaweiPacket raw(HuaweiPacket.ParamsProvider pp, int svc, int cmd, HuaweiTLV tlv) {
        HuaweiPacket p = new HuaweiPacket(pp);
        p.serviceId = (byte) svc;
        p.commandId = (byte) cmd;
        p.setTlv(tlv);
        p.complete = true;
        return p;
    }

    private static String tlvHexShort(HuaweiPacket p) {
        try {
            byte[] b = p.getTlv().serialize();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.length && i < 24; i++) sb.append(String.format("%02x", b[i]));
            return sb.toString();
        } catch (Throwable e) { return "?"; }
    }

    /** GB HuaweiState.supportsExpandCapability(int) */
    private static boolean expandBit(byte[] caps, int which) {
        if (caps == null || which >= caps.length * 8) return false;
        int bit = 1 << (which % 8);
        return (caps[which / 8] & bit) == bit;
    }

    /** GB {@code HuaweiState.supportsCommandForService} */
    public boolean supports(int service, int command) {
        byte[] cs = commands.get(service);
        if (cs == null) return false;
        for (byte c : cs) if ((c & 0xFF) == command) return true;
        return false;
    }

    private String describeCommands() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, byte[]> e : commands.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Integer.toHexString(e.getKey())).append(':');
            byte[] cs = e.getValue();
            for (int i = 0; cs != null && i < cs.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(Integer.toHexString(cs[i] & 0xFF));
            }
        }
        return sb.toString();
    }

    private static String hexList(List<Byte> l) {
        StringBuilder sb = new StringBuilder();
        for (Byte b : l) { if (sb.length() > 0) sb.append(','); sb.append(Integer.toHexString(b & 0xFF)); }
        return sb.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String why(Throwable t) { return t.getClass().getSimpleName() + ":" + t.getMessage(); }

    private static void probe(String s) {
        try { Probe.post("hwinit", s); } catch (Throwable ignored) { }
    }
}
