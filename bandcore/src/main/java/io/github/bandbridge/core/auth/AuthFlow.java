package io.github.bandbridge.core.auth;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import io.github.bandbridge.core.Probe;
import io.github.bandbridge.core.proto.CryptoUtils;
import io.github.bandbridge.core.proto.HuaweiConstants;
import io.github.bandbridge.core.proto.HuaweiCrypto;
import io.github.bandbridge.core.proto.HuaweiPacket;
import io.github.bandbridge.core.proto.StringUtils;
import io.github.bandbridge.core.proto.packets.DeviceConfig;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 华为/荣耀手环（HUAWEI Band 10）原生 BLE 认证握手。
 *
 * <h3>它是什么</h3>
 * 把 Gadgetbridge 里 7 个 {@code requests/*.java} 的报文构造 + 响应处理，平移到本类里，
 * 直接收发 {@link HuaweiPacket}，不再需要 GB 的 {@code Request} 基类、{@code HuaweiSupportProvider}、
 * {@code ResponseManager}、{@code TransactionBuilder}。
 *
 * <h3>线程模型（重要）</h3>
 * <ul>
 *   <li>{@link #run()} <b>阻塞</b>在内部 {@link LinkedBlockingQueue} 上等响应 —— 必须跑在独立 worker 线程。</li>
 *   <li>{@link #onPacket(Object)} 由 BLE 回调线程调用，只往队列里投包，不做任何重活。</li>
 *   <li>两者<b>不能</b>在同一个线程里（会自锁）。</li>
 * </ul>
 *
 * <h3>握手顺序（Band 10，deviceSupportType 决定分叉）</h3>
 * <pre>
 * 0. 前置（Lead 负责）：connect → notify(FE02, true)
 * 1. LinkParams        (0x01/0x01)  ← 无条件第一个包
 *      → 灌 deviceSupportType / sliceSize / mtu / serverNonce / authVersion / authAlgo / encryptMethod
 *      → 之后所有包的切片与加密才正确
 * 2. 分叉：isHiChain()?   deviceSupportType ∈ {1,3,4} 或 ==2(HiChainLite)
 *    ├─ 否 → Normal 模式：createSecretKey → Auth → BondParams → Bond
 *    └─ 是 → SecurityNegotiation (0x01/0x33)，authMode = (dST==4)?4:2
 *            ├─ authType==0x0186A0 或 isHiChain3(authType) → HiChain 模式
 *            │    (firstConnection?  Pincode → HiChain op=1 → HiChain op=2
 *            │                     :  HiChain op=2)
 *            └─ authType ∈ {0x01,0x02} → HiChain Lite 模式
 *                 createSecretKey → (pinCode==null && authVersion!=2 ? Pincode) → Auth → BondParams → Bond
 * </pre>
 * Band 10 走 BLE ⇒ <b>跳过</b> GB 的 {@code GetDeviceStatusRequest}（那张表只有非 BLE 才查）。
 *
 * <h3>没搬的部分（诚实声明）</h3>
 * <ul>
 *   <li><b>HiChain PAKE / STS</b>（GB {@code GetHiChainPakeRequest}，Honor Watch 5 专用，
 *       由 {@code HuaweiCoordinator.supportsHiChainPake()} 门控）。Band 10 不走这条，
 *       所以本类里没有实现 —— 见 {@link #PAKE_NOT_IMPLEMENTED}。</li>
 *   <li>GB 的 request 链式调度（{@code nextRequest} / {@code setFinalizeReq} / {@code stopChain}）
 *       被换成了本类里的同步状态机；语义一一对应，但形态不同。</li>
 * </ul>
 *
 * <h3>契约</h3>
 * SPEC 要求的四个方法一字未改：
 * {@code Link}、{@code AuthFlow(Link)}、{@code run()}、{@code getSession()}、{@code getLastError()}。
 * 其余是给 Lead 用的附加便利方法。
 */
public final class AuthFlow {

    private static final String TAG = "HuaweiAuthFlow";

    /** GB GetHiChainPakeRequest 的 PAKE/STS 路径 —— Band 10 不走，仅作占位说明。 */
    public static final String PAKE_NOT_IMPLEMENTED =
            "HiChain PAKE/STS (Honor Watch 5 path) is not ported into AuthFlow; "
                    + "Band 10 uses the classic HiChain / HiChain-Lite / Normal paths. "
                    + "See Gadgetbridge GetHiChainPakeRequest (service 0x01 cmd 0x28, operationCode 1/2).";

    // ==================================================================
    // 对外契约
    // ==================================================================

    /** 发送一个已编码的包体（framing 之后的字节）。由 Lead 实现（写 FE01）。 */
    public interface Link {
        void send(byte[] packet) throws Exception;
    }

    // ==================================================================
    // 超时（GB 里只有 HiChain 那两步设了超时，其余靠人工断连；这里全给上限）
    // ==================================================================

    private static final long TIMEOUT_LINK_PARAMS = 30_000L;
    private static final long TIMEOUT_SECURITY_NEGOTIATION = 15_000L;
    /** 要用户在表盘上确认/输码，给足时间（GB: firstAuthenticateTimeout）。 */
    private static final long TIMEOUT_PINCODE = 120_000L;
    private static final long TIMEOUT_AUTH = 15_000L;
    private static final long TIMEOUT_BOND_PARAMS = 15_000L;
    private static final long TIMEOUT_BOND = 15_000L;
    /** GB: GetHiChainRequest 的 firstAuthenticateTimeout。 */
    private static final long TIMEOUT_HICHAIN_BIND_STEP = 30_000L;
    /** GB 只给 5s，这里放宽到 10s（重连本该很快，但别被一次 GATT 抖动打死）。 */
    private static final long TIMEOUT_HICHAIN_RECONNECT_STEP = 10_000L;

    private static final int MAX_CACHED_ASYNC_PACKETS = 256;

    private static final byte[] HKDF_INFO_SESSION_KEY = "hichain_iso_session_key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HKDF_INFO_RETURN_KEY = "hichain_return_key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_ISO_EXCHANGE = "hichain_iso_exchange".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_ISO_RESULT = "hichain_iso_result".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ZERO_FOUR = new byte[]{0x00, 0x00, 0x00, 0x00};

    // ==================================================================
    // 字段
    // ==================================================================

    private final Link link;
    private final AuthState state;

    /** BLE 回调线程 → run() 线程。 */
    private final LinkedBlockingQueue<HuaweiPacket> inbox = new LinkedBlockingQueue<>();

    /** 收到但"不是当前这一步要的"包 —— 缓存，别丢（手环会主动推）。只在 run() 线程里读写。 */
    private final List<HuaweiPacket> pending = new ArrayList<>();

    /** 认证期间手环主动推的异步包，认证结束后由 Lead 取走交给数据层。 */
    private final List<HuaweiPacket> asyncPackets = new ArrayList<>();

    /** 分片重组状态（GB: ResponseManager 里那个 receivedPackets）。 */
    private HuaweiPacket assembler;

    private final SecureRandom random = new SecureRandom();

    /** 0 = 用上面那套按步骤的默认值；>0 = 所有步骤统一用它。 */
    private volatile long overrideTimeoutMillis = 0L;

    private volatile boolean running;
    private volatile boolean authenticated;
    private volatile String currentStep = "init";
    private volatile String lastError;
    private volatile Exception lastCause;
    private volatile String authPath = "unknown";

    private Session session;

    // 协商结果（SecurityNegotiation.Response）
    private int negotiatedAuthType = -1;
    private int negotiatedHonorPairType = -1;
    private byte[] negotiatedResponseNonce;

    // ==================================================================
    // 构造
    // ==================================================================

    public AuthFlow(Link link) {
        this(link, null, null);
    }

    public AuthFlow(Link link, Context context) {
        this(link, context, null);
    }

    /**
     * @param context       用来拿 SharedPreferences 持久化 authkey / 伪造 MAC / 伪造 androidID。
     *                      传 null 也能跑，但<b>不持久化</b>，每次连接都算新设备（手环会拒绝/重新配对）。
     * @param deviceAddress 真实 BLE 设备地址（{@code AA:BB:CC:DD:EE:FF}）。只有 GetBond 那一步用它派生
     *                      encryptionKey，但<b>必须</b>在走非 HiChainLite 分支时提供，否则那步会报错。
     */
    public AuthFlow(Link link, Context context, String deviceAddress) {
        if (link == null) {
            throw new IllegalArgumentException("Link must not be null");
        }
        this.link = link;
        this.state = new AuthState(context, deviceAddress);
    }

    // ------------------------------------------------------------------
    // Lead 用的附加配置 / 取值
    // ------------------------------------------------------------------

    public AuthFlow setContext(Context context) {
        state.setContext(context);
        return this;
    }

    public AuthFlow setDeviceAddress(String deviceAddress) {
        state.setDeviceAddress(deviceAddress);
        return this;
    }

    public AuthFlow setFirstConnection(boolean firstConnection) {
        state.setFirstConnection(firstConnection);
        return this;
    }

    public boolean isFirstConnection() {
        return state.isFirstConnection();
    }

    /** 统一覆盖所有步骤的等待上限（毫秒）。0 / 负数 = 恢复按步骤默认值。 */
    public AuthFlow setTimeoutMillis(long millis) {
        this.overrideTimeoutMillis = Math.max(0L, millis);
        return this;
    }

    /** 认证后交给数据层用的 ParamsProvider（加解密 / 切片 / mtu 都在里面）。 */
    public HuaweiPacket.ParamsProvider getParamsProvider() {
        return state.getParamsProvider();
    }

    public AuthState getState() {
        ensureStateLoaded();
        return state;
    }

    /** 伪造本机 MAC（UTF-8），BondParams 的 tag 0x07 用。 */
    public byte[] getMacAddress() {
        ensureStateLoaded();
        return state.getMacAddress();
    }

    /** 伪造 MAC 去掉冒号后的 [6,12)，Bond / BondParams 的 clientSerial 用。 */
    public byte[] getSerial() {
        ensureStateLoaded();
        return state.getSerial();
    }

    /** 伪造 androidID（UTF-8）。 */
    public byte[] getAndroidId() {
        ensureStateLoaded();
        return state.getAndroidId();
    }

    /** Band 10 = BLE。只用于让 Lead 打日志 / 决策后续数据层。 */
    public String getDeviceTypeHint() {
        return state.getParamsProvider().isAW() ? "AW" : "BLE";
    }

    public String getAuthPath() {
        return authPath;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    // ==================================================================
    // 契约方法
    // ==================================================================

    /**
     * 跑完整个认证握手（阻塞）。
     *
     * @return true = 认证完成，{@link #getSession()} 可用；false = 失败，原因见 {@link #getLastError()}。
     *         本实现不主动抛异常（承 {@code throws Exception} 只是为了对齐 SPEC 且允许调用方 catch），
     *         真正的 cause 在 {@link #getLastCause()}。
     */
    public boolean run() throws Exception {
        resetForRun();
        running = true;
        try {
            ensureStateLoaded();
            Log.i(TAG, "=== Huawei auth start: " + state);

            doLinkParams();
            doAuthDispatch();

            session = buildSession();
            authenticated = true;
            lastError = null;
            lastCause = null;
            currentStep = "done";
            drainLeftoverInbox();
            Log.i(TAG, "=== Huawei auth OK via " + authPath
                    + ", sessionKey=" + (state.getSessionSecretKey() == null ? "null" : state.getSessionSecretKey().length + "B (not logged)"));
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            markFailed("打断于 [" + currentStep + "]", ie);
            return false;
        } catch (Exception e) {
            markFailed("[" + currentStep + "] " + describe(e), e);
            return false;
        } finally {
            running = false;
        }
    }

    /** 认证完成后的会话对象；未完成时为 null。{@link Session} 里带密钥与协议参数。 */
    public Object getSession() {
        return session;
    }

    public String getLastError() {
        return lastError;
    }

    public Exception getLastCause() {
        return lastCause;
    }

    public boolean isRunning() {
        return running;
    }

    // ==================================================================
    // 收包入口
    // ==================================================================

    /**
     * BLE 回调线程调用：把已经 framing + 解码完的包投进队列。
     *
     * <p>接受 {@link HuaweiPacket}（推荐）或裸 {@code byte[]}（会用内部重组器解析，便于测试）。
     * 非 complete 的包会被丢弃 —— 分片重组是 {@link #parsePacket(byte[])} / Lead 的重组器的事。</p>
     */
    public void onPacket(Object packet) {
        if (packet == null) {
            return;
        }
        HuaweiPacket pkt;
        if (packet instanceof HuaweiPacket) {
            pkt = (HuaweiPacket) packet;
        } else if (packet instanceof byte[]) {
            pkt = parsePacket((byte[]) packet);
            if (pkt == null) {
                return;
            }
        } else {
            Log.w(TAG, "onPacket ignored unsupported type: " + packet.getClass().getName());
            return;
        }
        if (!pkt.complete) {
            Log.d(TAG, "onPacket dropped incomplete packet svc=0x"
                    + hex(pkt.serviceId) + " cmd=0x" + hex(pkt.commandId));
            return;
        }
        inbox.offer(pkt);
    }

    /**
     * 分片重组 + 解码。返回 null 表示"还缺后续分片"或"解析失败"。
     *
     * <p><b>只用于认证期间</b>。认证结束后数据层请用 Lead 的 transport 重组器，
     * 别用这个 —— 二者语义相同（都是包 {@code HuaweiPacket.parse} + {@code getLeft()}），
     * 但各自持有一份分片状态，混用会把分片搞错。</p>
     */
    public synchronized HuaweiPacket parsePacket(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            HuaweiPacket base = assembler;
            if (base == null) {
                base = new HuaweiPacket(state.getParamsProvider());
            }
            HuaweiPacket parsed = base.parse(data);
            if (parsed == null) {
                assembler = null;
                return null;
            }
            if (parsed.getLeft() > 0) {
                assembler = parsed;
                Log.d(TAG, "packet incomplete, waiting " + parsed.getLeft() + " more byte(s)");
                return null;
            }
            assembler = null;
            return parsed;
        } catch (HuaweiPacket.ParseException e) {
            assembler = null;
            Log.e(TAG, "packet parse failed", e);
            return null;
        }
    }

    /** {@link #parsePacket} + {@link #onPacket} 的合并便捷方法。 */
    public void feed(byte[] data) {
        HuaweiPacket pkt = parsePacket(data);
        if (pkt != null) {
            onPacket(pkt);
        }
    }

    /** 取一个认证期间被跳过的异步包（没有就 null）。 */
    public HuaweiPacket pollAsyncPacket() {
        synchronized (asyncPackets) {
            if (asyncPackets.isEmpty()) {
                return null;
            }
            return asyncPackets.remove(0);
        }
    }

    /** 取走所有认证期间被跳过的异步包（认证结束后交给数据层，别丢）。 */
    public List<HuaweiPacket> drainAsyncPackets() {
        synchronized (asyncPackets) {
            List<HuaweiPacket> out = new ArrayList<>(asyncPackets);
            asyncPackets.clear();
            return out;
        }
    }

    // ==================================================================
    // run() 内部的步骤实现
    // ==================================================================

    private void resetForRun() {
        inbox.clear();
        pending.clear();
        synchronized (asyncPackets) {
            asyncPackets.clear();
        }
        synchronized (this) {
            assembler = null;
        }
        session = null;
        authenticated = false;
        authPath = "unknown";
        lastError = null;
        lastCause = null;
        negotiatedAuthType = -1;
        negotiatedHonorPairType = -1;
        negotiatedResponseNonce = null;
        currentStep = "init";
    }

    // ---------------- 0. LinkParams ----------------

    /**
     * 连上之后第一个包。GB: {@code HuaweiSupportProvider.initializeDevice()}。
     *
     * <p>TLV = {0x01, 0x02, 0x03, 0x04}，AW 类型额外加 0x06；Band 10 = BLE 所以只有前四个。
     * proto 层已提供 {@code LinkParams.Request(ParamsProvider)}（内部固定按 BLE 构造），直接用。</p>
     */
    private void doLinkParams() throws Exception {
        step("link-params");
        final HuaweiPacket.ParamsProvider pp = state.getParamsProvider();

        send(new DeviceConfig.LinkParams.Request(pp));

        HuaweiPacket rx = awaitResponse(
                DeviceConfig.id, DeviceConfig.LinkParams.id, TIMEOUT_LINK_PARAMS, "LinkParams");
        parseTlv(rx, "LinkParams");
        DeviceConfig.LinkParams.Response resp = expect(rx, DeviceConfig.LinkParams.Response.class, "LinkParams");

        state.setProtocolVersion(resp.protocolVersion);
        state.setDeviceSupportType(resp.deviceSupportType);
        state.setSliceSize(resp.sliceSize & 0xFFFF);
        state.setMtu(resp.mtu & 0xFFFF);
        state.setAuthVersion(resp.authVersion);
        state.setAuthAlgo(resp.authAlgo);
        state.setEncryptMethod(resp.encryptMethod);
        state.setBondState(resp.bondState);
        state.setServerNonce(resp.serverNonce);

        Log.i(TAG, "LinkParams: protocolVersion=0x" + hex(resp.protocolVersion)
                + " deviceSupportType=" + resp.deviceSupportType
                + " authVersion=" + resp.authVersion
                + " authAlgo=" + resp.authAlgo
                + " encryptMethod=" + resp.encryptMethod
                + " bondState=" + resp.bondState
                + " sliceSize=" + state.getSliceSize()
                + " mtu=" + state.getMtu()
                + " serverNonce=" + hex(resp.serverNonce));
        probe("linkparams | dst=" + resp.deviceSupportType
                + " proto=0x" + hex(resp.protocolVersion)
                + " authVer=" + resp.authVersion
                + " algo=" + resp.authAlgo
                + " enc=" + resp.encryptMethod
                + " bond=" + resp.bondState
                + " slice=" + state.getSliceSize()
                + " mtu=" + state.getMtu());
    }

    private static void probe(String text) {
        try { Probe.post("hwauth", text); } catch (Throwable ignored) { }
    }

    // ---------------- 1. 分叉 ----------------

    /** GB {@code HuaweiSupportProvider.initializeDeviceDealHiChain()} + {@code isHiChain()}。 */
    private void doAuthDispatch() throws Exception {
        byte supportType = state.getDeviceSupportType();
        if (!isHiChain()) {
            authPath = "normal";
            Log.i(TAG, "deviceSupportType=" + supportType + " -> normal 模式（无 HiChain）");
            doNormalMode();
            return;
        }

        doSecurityNegotiation();

        if (negotiatedAuthType == 0x0186A0 || isHiChain3(negotiatedAuthType)) {
            authPath = "hichain";
            Log.i(TAG, "authType=0x" + Integer.toHexString(negotiatedAuthType) + " -> HiChain 模式");
            doHiChainMode();
        } else if (negotiatedAuthType == 0x01 || negotiatedAuthType == 0x02) {
            authPath = "hichain-lite";
            Log.i(TAG, "authType=0x" + Integer.toHexString(negotiatedAuthType) + " -> HiChain Lite 模式");
            doHiChainLiteMode();
        } else {
            throw new AuthException("SecurityNegotiation 返回未知 authType=0x"
                    + Integer.toHexString(negotiatedAuthType)
                    + " (honorPairType=" + negotiatedHonorPairType
                    + ", responseNonce=" + hex(negotiatedResponseNonce) + ")");
        }
    }

    /** GB: {@code deviceSupportType ∈ {1,3,4} 或 == 2(HichainLite)}。 */
    private boolean isHiChain() {
        byte supportType = state.getDeviceSupportType();
        return supportType == 0x01 || supportType == 0x03 || supportType == 0x04
                || isHiChainLite();
    }

    private boolean isHiChainLite() {
        return state.getDeviceSupportType() == 0x02;
    }

    /** GB: {@code HuaweiSupportProvider.isHiChain3(int)}。 */
    private boolean isHiChain3(int authType) {
        return (authType ^ 0x01) == 0x04 || (authType ^ 0x02) == 0x04;
    }

    // ---------------- 2. SecurityNegotiation ----------------

    private void doSecurityNegotiation() throws Exception {
        step("security-negotiation");
        final HuaweiPacket.ParamsProvider pp = state.getParamsProvider();

        // GB: deviceSupportType==4 -> authMode 4，其余 -> 2
        byte authMode = (state.getDeviceSupportType() == 0x04) ? (byte) 0x04 : (byte) 0x02;
        state.setAuthMode(authMode);

        byte[] androidId = state.getAndroidId();
        if (androidId == null) {
            throw new AuthException("androidID 未初始化（AuthState.load() 没跑？）");
        }

        // PAKE 设备（Honor Watch 5）在 GB 里走另一个构造器；Band 10 不走 PAKE，用这个通用构造器。
        send(new DeviceConfig.SecurityNegotiation.Request(pp, authMode, androidId, Build.MODEL));

        HuaweiPacket rx = awaitResponse(DeviceConfig.id, DeviceConfig.SecurityNegotiation.id,
                TIMEOUT_SECURITY_NEGOTIATION, "SecurityNegotiation");
        parseTlv(rx, "SecurityNegotiation");
        DeviceConfig.SecurityNegotiation.Response resp =
                expect(rx, DeviceConfig.SecurityNegotiation.Response.class, "SecurityNegotiation");

        negotiatedAuthType = resp.authType;
        negotiatedHonorPairType = resp.honorPairType;
        negotiatedResponseNonce = resp.responseNonce;

        Log.i(TAG, "SecurityNegotiation: authType=0x" + Integer.toHexString(resp.authType)
                + " honorPairType=" + resp.honorPairType
                + " responseNonce=" + hex(resp.responseNonce));
        probe("secneg | authType=0x" + Integer.toHexString(resp.authType)
                + " honorPairType=" + resp.honorPairType + " authMode=" + authMode);
    }

    // ---------------- 3a. HiChain 模式 ----------------

    /**
     * GB: {@code initializeDeviceHiChainMode()}。
     * firstConnection 时先取 PIN，再跑 op=1(bind) + op=2(重连)；否则直接 op=2。
     */
    private void doHiChainMode() throws Exception {
        /* ★3.281 手环自己会「忘掉」我们（3.280 真机：停在出厂引导页一段时间后，secneg 从 0x6 变回 0x5）。
           实测：第一次绑定 authType=0x5 / honorPairType=1；之后每次重连 authType=0x6 / honorPairType=2。
           我们本地记着 paired=true ⇒ 直接 op=2 重连 ⇒ 手环不认，等 10s 超时（hichain-reconnect-1）。
           ⇒ 手环说 0x5（要绑定）就按首次连接走 op=1 绑定，不管本地怎么记的。 */
        if (!state.isFirstConnection() && negotiatedAuthType == 0x05) {
            probe("rebind | authType=0x5 but local paired ⇒ 改走首次绑定 op=1");
            state.setFirstConnection(true);
        }
        if (state.isFirstConnection()) {
            if (state.getPinCode() == null) {
                doPincode();
            }
            runHiChainChain(0x01);
            runHiChainChain(0x02);
        } else {
            runHiChainChain(0x02);
        }
    }

    // ---------------- 3b. HiChain Lite 模式 ----------------

    /** GB: {@code initializeDeviceHiChainLiteMode()}。 */
    private void doHiChainLiteMode() throws Exception {
        createSecretKey();

        if (state.getPinCode() == null && state.getAuthVersion() != 0x02) {
            doPincode();
        }
        doAuth();

        boolean skipBond = doBondParams();
        if (!skipBond) {
            doBond();
        }
    }

    // ---------------- 3c. Normal 模式 ----------------

    /** GB: {@code initializeDeviceNormalMode()}。Band 10 = BLE ⇒ 一定有 BondParams + Bond。 */
    private void doNormalMode() throws Exception {
        createSecretKey();
        doAuth();
        boolean skipBond = doBondParams();
        if (!skipBond) {
            doBond();
        }
    }

    // ---------------- 4. secretKey ----------------

    /**
     * GB: {@code HuaweiSupportProvider.createSecretKey()} —— 读持久化 authkey（没有就生成），灌进 ParamsProvider。
     * {@link AuthState#load()} 已经做过等价的事，这里只是再显式同步一次，保证值一定到了 ParamsProvider。
     */
    private void createSecretKey() throws AuthException {
        byte[] key = state.getSecretKey();
        if (key == null || key.length == 0) {
            state.load();
            key = state.getSecretKey();
        }
        if (key == null || key.length == 0) {
            throw new AuthException("无法创建 secretKey：AuthState 里没有 authkey 且无法生成（缺 Context？）");
        }
        state.getParamsProvider().setSecretKey(key);
        // 密钥不进日志：这是本机与手环的长期 authkey，公开仓库/分享日志都不能带出来
        Log.d(TAG, "createSecretKey: ok | " + key.length + " bytes (value not logged)");
    }

    // ---------------- 5. Pincode ----------------

    /**
     * GB: {@code GetPincodeRequest}。用户在<b>手环上</b>确认/输码后手环回包，
     * 所以这一步的等待上限给到 120s（{@link #TIMEOUT_PINCODE}）。
     */
    private void doPincode() throws Exception {
        step("pincode");
        final HuaweiPacket.ParamsProvider pp = state.getParamsProvider();

        send(new DeviceConfig.PinCode.Request(pp));

        HuaweiPacket rx = awaitResponse(DeviceConfig.id, DeviceConfig.PinCode.id,
                timeout(TIMEOUT_PINCODE), "PinCode");
        parseTlv(rx, "PinCode");
        DeviceConfig.PinCode.Response resp = expect(rx, DeviceConfig.PinCode.Response.class, "PinCode");

        state.setPinCode(resp.pinCode);
        Log.i(TAG, "PinCode received, pinCode=" + hex(resp.pinCode));
    }

    // ---------------- 6. Auth ----------------

    /**
     * GB: {@code GetAuthRequest}。<b>不是</b> HiChain，是老的 challenge-response：
     * {@code challenge = digestChallenge(key, serverNonce||clientNonce)[0:32]}，
     * 同时把 [32:48] 存成 firstKey（Bond 那步当 encryptionKey 用）。
     */
    private void doAuth() throws Exception {
        step("auth");
        final HuaweiPacket.ParamsProvider pp = state.getParamsProvider();

        byte[] clientNonce = HuaweiCrypto.generateNonce();
        byte[] serverNonce = state.getServerNonce();
        if (serverNonce == null || serverNonce.length == 0) {
            throw new AuthException("LinkParams 没给出 serverNonce，Auth 无法计算");
        }
        byte[] doubleNonce = ByteBuffer.allocate(serverNonce.length + clientNonce.length)
                .put(serverNonce)
                .put(clientNonce)
                .array();

        short authVersion = pp.getAuthVersion();
        byte authAlgo = pp.getAuthAlgo();
        byte deviceSupportType = pp.getDeviceSupportType();
        byte authMode = pp.getAuthMode();
        HuaweiCrypto huaweiCrypto = new HuaweiCrypto(authVersion, authAlgo, deviceSupportType, authMode);

        // GB: 这一步的 key 在 createRequest 里定死，processResponse 复用同一个变量。
        byte[] key = null;
        if (authMode == 0x02) {
            key = state.getPinCode();
            if (authVersion == 0x02) {
                key = state.getParamsProvider().getSecretKey();
            }
        }
        Log.d(TAG, "Auth: authVersion=" + authVersion + " authAlgo=" + authAlgo
                + " authMode=" + authMode + " key=" + (key == null ? "null" : key.length + "B"));

        byte[] nonce = ByteBuffer.allocate(18)
                .putShort(authVersion)
                .put(clientNonce)
                .array();

        byte[] digested = huaweiCrypto.digestChallenge(key, doubleNonce);
        if (digested == null || digested.length < 0x30) {
            throw new AuthException("digestChallenge 输出太短: "
                    + (digested == null ? "null" : digested.length + "B") + "（需要 >= 48B）");
        }
        byte[] challenge = Arrays.copyOfRange(digested, 0x00, 0x20);
        byte[] firstKey = Arrays.copyOfRange(digested, 0x20, 0x30);
        state.setFirstKey(firstKey);
        // challenge / firstKey 都由 authkey 派生 —— 只记长度，不记值
        Log.d(TAG, "Auth digest ready | challenge=" + challenge.length + "B firstKey=" + firstKey.length + "B (values not logged)");

        send(new DeviceConfig.Auth.Request(pp, challenge, nonce));

        HuaweiPacket rx = awaitResponse(DeviceConfig.id, DeviceConfig.Auth.id,
                timeout(TIMEOUT_AUTH), "Auth");
        parseTlv(rx, "Auth");
        DeviceConfig.Auth.Response resp = expect(rx, DeviceConfig.Auth.Response.class, "Auth");

        byte[] digestedResp = huaweiCrypto.digestResponse(key, doubleNonce);
        if (digestedResp == null || digestedResp.length < 0x20) {
            throw new AuthException("digestResponse 输出太短: "
                    + (digestedResp == null ? "null" : digestedResp.length + "B"));
        }
        byte[] expectedAnswer = Arrays.copyOfRange(digestedResp, 0, 0x20);
        byte[] actualAnswer = resp.challengeResponse;
        if (actualAnswer == null || !Arrays.equals(expectedAnswer, actualAnswer)) {
            throw new AuthException("Auth challenge 应答不匹配：手环给 "
                    + (actualAnswer == null ? "null" : actualAnswer.length + "B")
                    + "，本地算 " + expectedAnswer.length + "B（值不打印）");
        }
        Log.i(TAG, "Auth challenge VERIFIED");
    }

    // ---------------- 7. BondParams ----------------

    /**
     * GB: {@code GetBondParamsRequest}。响应里的 encryptionCounter 是后续 Bond / 数据包 IV 的计数器。
     *
     * @return true = 应该跳过 GetBond（GB 里的 {@code stopChain}）
     */
    private boolean doBondParams() throws Exception {
        step("bond-params");
        final HuaweiPacket.ParamsProvider pp = state.getParamsProvider();

        send(new DeviceConfig.BondParams.Request(pp, state.getSerial(), state.getMacAddress()));

        HuaweiPacket rx = awaitResponse(DeviceConfig.id, DeviceConfig.BondParams.id,
                timeout(TIMEOUT_BOND_PARAMS), "BondParams");
        parseTlv(rx, "BondParams");
        DeviceConfig.BondParams.Response resp =
                expect(rx, DeviceConfig.BondParams.Response.class, "BondParams");

        state.setEncryptionCounter(resp.encryptionCounter);
        Log.i(TAG, "BondParams: status=" + resp.status
                + " encryptionCounter=" + resp.encryptionCounter
                + " authMode=" + pp.getAuthMode());

        // GB: authMode != 0x02 且 status == 1（已绑定）→ stopChain，跳过 GetBond。
        if (pp.getAuthMode() != 0x02 && resp.status == 1) {
            Log.w(TAG, "BondParams status=1 (already bonded) -> 跳过 GetBond");
            return true;
        }
        return false;
    }

    // ---------------- 8. Bond ----------------

    /**
     * GB: {@code GetBondRequest}。把会话密钥用 {@code encryptionKey} 加密后交给手环存起来。
     * 手环的响应 GB 也没做内容校验（{@code processResponse} 只打日志），这里同样只确认收到。
     */
    private void doBond() throws Exception {
        step("bond");
        final HuaweiPacket.ParamsProvider pp = state.getParamsProvider();

        byte[] iv = pp.getIv();
        HuaweiCrypto huaweiCrypto = new HuaweiCrypto(
                pp.getAuthVersion(), pp.getAuthAlgo(), pp.getDeviceSupportType(), pp.getAuthMode());

        byte[] encryptionKey;
        if (pp.getAuthMode() == 0x02) {
            // HiChainLite：用 Auth 那步算出来的 firstKey
            encryptionKey = state.getFirstKey();
            if (encryptionKey == null) {
                throw new AuthException("Bond: authMode==0x02 但没有 firstKey（Auth 步骤没成功？）");
            }
        } else {
            // 其余：用真实 BLE 设备地址派生（GB: getDeviceMac()）
            String deviceMac = state.getDeviceAddress();
            if (deviceMac == null || deviceMac.isEmpty()) {
                throw new AuthException("Bond: 需要真实 BLE 设备地址才能派生 encryptionKey，"
                        + "请先调用 AuthFlow.setDeviceAddress(\"AA:BB:CC:DD:EE:FF\")");
            }
            encryptionKey = huaweiCrypto.createSecretKey(deviceMac);
        }

        byte[] secretKey = pp.getSecretKey();
        if (secretKey == null) {
            throw new AuthException("Bond: paramsProvider.secretKey 为 null");
        }

        byte[] key = huaweiCrypto.encryptBondingKey(
                pp.getEncryptMethod(), secretKey, encryptionKey, iv);
        // Bond 的 iv/key 是密钥材料（iv 虽然公开、但同一行带上 key 就一起泄了）—— 只记长度
        Log.d(TAG, "Bond: iv=" + iv.length + "B key=" + key.length + "B (values not logged)");

        send(new DeviceConfig.Bond.Request(pp, state.getSerial(), key, iv));

        // 手环一般会回一个 Bond ack。GB 里这条请求没有超时（不回就永远挂着），这里给个上限：
        // 超时<b>不算失败</b> —— 前面所有校验都过了，只剩这条收尾 ack 没来，
        // 把整个认证判死没意义。只打 warning，让数据层去暴露真正的问题。
        try {
            HuaweiPacket rx = awaitResponse(DeviceConfig.id, DeviceConfig.Bond.id,
                    timeout(TIMEOUT_BOND), "Bond");
            Log.i(TAG, "Bond acked (svc=0x" + hex(rx.serviceId)
                    + " cmd=0x" + hex(rx.commandId) + ")");
        } catch (AuthTimeoutException te) {
            Log.w(TAG, "Bond 没有回 ack（" + te.timeoutMillis + "ms）—— 继续，认证仍视为成功");
        }
    }

    // ==================================================================
    // HiChain（op=1 首次绑定 / op=2 重连）—— GB GetHiChainRequest 的完整平移
    // ==================================================================

    /**
     * 跑一条 HiChain 消息链（4 步）。
     *
     * <p>op=0x01（BIND）和 op=0x02（重连）的差别：</p>
     * <ul>
     *   <li>op=1 第 1 步的 psk 基来自 PIN（{@code digest(hex(pinCode))}），
     *       op=2 来自持久化的长期 authkey。</li>
     *   <li>op=1 第 3 步会发 StepThree（encData=加密的 challenge），并把响应里的 encAuthToken
     *       解出来存成新的长期 authkey；op=2 <b>不发 StepThree</b>。</li>
     *   <li>op=1 第 4 步结束后不结束认证，而是接着跑 op=2；op=2 第 4 步才派生最终会话密钥。</li>
     * </ul>
     *
     * <p>⚠️ 注意 messageId 的取值：GB 用 {@code int messageId = step;} 在自增<b>之前</b>固定，
     * 所以 op=2 最后那条 StepFour 带的是 message 19（0x13）而不是 20（0x14）。
     * 这是协议行为（op=2 只有 3 条报文），别"顺手修好"。</p>
     */
    private void runHiChainChain(int operationCode) throws Exception {
        final HuaweiPacket.ParamsProvider pp = state.getParamsProvider();
        final long requestId = System.currentTimeMillis();
        final byte[] selfAuthId = state.getAndroidId();
        if (selfAuthId == null) {
            throw new AuthException("androidID 未初始化");
        }
        final DeviceConfig.HiChain.Request req = new DeviceConfig.HiChain.Request(
                operationCode, requestId, selfAuthId, HuaweiConstants.GROUP_ID);

        byte[] authIdSelf = null;
        byte[] authIdPeer = null;
        byte[] randSelf = null;
        byte[] randPeer = null;
        byte[] seed = null;
        byte[] psk = null;
        byte[] sessionKey = null;
        byte[] challenge = null;

        int step = 0x01;
        while (true) {
            step(operationCode == 0x01 ? "hichain-bind-" + step : "hichain-reconnect-" + step);

            // GB 在自增之前就把 messageId 钉住了（见方法注释）。
            final int messageId = step;

            // op=2 的第 3 步不发包：先派生 sessionKey，再把 step 推到 4 直接发 StepFour。
            if (step == 0x03) {
                byte[] salt = concat(randSelf, randPeer);
                sessionKey = CryptoUtils.hkdfSha256(psk, salt, HKDF_INFO_SESSION_KEY, 32);
                // 会话密钥不进日志
                Log.d(TAG, "HiChain sessionKey ready | " + sessionKey.length + "B (value not logged)");
                if (operationCode == 0x02) {
                    step = 0x04;
                }
            }

            HuaweiPacket out;
            if (step == 0x01) {
                // 第 1 步：isoSalt(randSelf) + seed
                seed = randomBytes(32);
                randSelf = randomBytes(16);
                out = req.new StepOne(pp, messageId, randSelf, seed);
            } else if (step == 0x02) {
                // 第 2 步：用 psk 对自己这一侧的 message 做 HMAC
                byte[] message = concat(randSelf, randPeer, authIdPeer, authIdSelf);
                byte[] selfToken = CryptoUtils.calcHmacSha256(psk, message);
                out = req.new StepTwo(pp, messageId, selfToken);
            } else if (step == 0x03) {
                // 第 3 步（仅 op=1）：AES-GCM 加密 challenge
                byte[] nonce = randomBytes(12);
                challenge = randomBytes(16);
                byte[] encData = CryptoUtils.encryptAES_GCM_NoPad(challenge, sessionKey, nonce, AAD_ISO_EXCHANGE);
                out = req.new StepThree(pp, messageId, nonce, encData);
            } else if (step == 0x04) {
                // 第 4 步：AES-GCM 加密固定 4 个 0
                byte[] nonce = randomBytes(12);
                byte[] encResult = CryptoUtils.encryptAES_GCM_NoPad(
                        ZERO_FOUR, sessionKey, nonce, AAD_ISO_RESULT);
                out = req.new StepFour(pp, messageId, nonce, encResult);
            } else {
                throw new AuthException("HiChain: 非法 step=" + step);
            }

            long t = (operationCode == 0x01)
                    ? timeout(TIMEOUT_HICHAIN_BIND_STEP)
                    : timeout(TIMEOUT_HICHAIN_RECONNECT_STEP);

            send(out);
            DeviceConfig.HiChain.Response resp = awaitHiChainResponse(step, t,
                    "HiChain op=" + operationCode + " step=" + step);

            if (resp.errorCode != 0) {
                throw new AuthException("HiChain op=" + operationCode + " step=" + step
                        + " 手环回 errorCode=0x" + Integer.toHexString(resp.errorCode));
            }

            // ---- 处理响应 ----
            if (step == 0x01) {
                if (resp.step1Data == null) {
                    throw new AuthException("HiChain step1 响应里没有 isoSalt/peerAuthId/token");
                }
                authIdSelf = selfAuthId;
                authIdPeer = resp.step1Data.peerAuthId;
                randPeer = resp.step1Data.isoSalt;
                byte[] peerToken = resp.step1Data.token;

                byte[] baseKey;
                if (operationCode == 0x01) {
                    byte[] pinCode = state.getPinCode();
                    if (pinCode == null) {
                        throw new AuthException("HiChain bind 缺 pinCode（Pincode 步骤没跑成）");
                    }
                    // GB: digest( hex(pinCode) 的 UTF-8 字节 )
                    baseKey = CryptoUtils.digest(
                            StringUtils.bytesToHex(pinCode).getBytes(StandardCharsets.UTF_8));
                } else {
                    baseKey = state.getSecretKey();
                    if (baseKey == null) {
                        throw new AuthException("HiChain 重连缺长期 authkey —— 需要先完成一次首次配对");
                    }
                }
                psk = CryptoUtils.calcHmacSha256(baseKey, seed);

                // 注意顺序与 createRequest 里那个是镜像的：这里验的是「手环那一侧」
                byte[] message = concat(randPeer, randSelf, authIdSelf, authIdPeer);
                byte[] tokenCheck = CryptoUtils.calcHmacSha256(psk, message);
                if (!Arrays.equals(peerToken, tokenCheck)) {
                    throw new AuthException("HiChain step1 token 校验失败：手环给 "
                            + (peerToken == null ? "null" : peerToken.length + "B")
                            + "，本地算 " + (tokenCheck == null ? "null" : tokenCheck.length + "B")
                            + "（值不打印）");
                }
                Log.d(TAG, "HiChain step1 token OK");
                step = 0x02;

            } else if (step == 0x02) {
                if (resp.step2Data == null) {
                    throw new AuthException("HiChain step2 响应里没有 returnCodeMac");
                }
                byte[] returnCodeMac = resp.step2Data.returnCodeMac;
                byte[] returnCodeMacCheck = CryptoUtils.calcHmacSha256(psk, ZERO_FOUR);
                if (!Arrays.equals(returnCodeMacCheck, returnCodeMac)) {
                    throw new AuthException("HiChain step2 returnCodeMac 校验失败：手环给 "
                            + (returnCodeMac == null ? "null" : returnCodeMac.length + "B")
                            + "，本地算 " + (returnCodeMacCheck == null ? "null" : returnCodeMacCheck.length + "B")
                            + "（值不打印）");
                }
                Log.d(TAG, "HiChain step2 returnCodeMac OK");
                step = 0x03;

            } else if (step == 0x03) {
                if (operationCode == 0x01) {
                    if (resp.step3Data == null) {
                        throw new AuthException("HiChain step3 响应里没有 nonce/encAuthToken");
                    }
                    byte[] authToken = CryptoUtils.decryptAES_GCM_NoPad(
                            resp.step3Data.encAuthToken, sessionKey,
                            resp.step3Data.nonce, challenge);
                    // GB: supportProvider.setSecretKey(authToken) —— 持久化 + 灌 ParamsProvider
                    state.setSecretKey(authToken);
                    // authToken 会成为长期 authkey：不进日志
                    Log.d(TAG, "HiChain bind: authToken saved as long-term authkey | "
                            + authToken.length + "B (value not logged)");
                }
                step = 0x04;

            } else if (step == 0x04) {
                if (operationCode == 0x01) {
                    Log.d(TAG, "HiChain bind 阶段完成，接着跑 op=2 重连阶段");
                    return;
                }
                // op=2 终局：会话密钥 = HKDF(sessionKey, randSelf||randPeer, "hichain_return_key", 32)
                byte[] salt = concat(randSelf, randPeer);
                byte[] finalKey = CryptoUtils.hkdfSha256(sessionKey, salt, HKDF_INFO_RETURN_KEY, 32);
                // GB 只写 paramsProvider，不写持久化 —— 下次连接还要用长期 authkey 重新握手。
                state.setSessionSecretKey(finalKey);
                Log.i(TAG, "HiChain 重连完成，最终 sessionKey | " + finalKey.length + "B (value not logged)");
                return;

            } else {
                throw new AuthException("HiChain: 无法处理响应 step=" + step);
            }
        }
    }

    // ==================================================================
    // 收发基础件
    // ==================================================================

    /** 序列化切片后逐片写出。MTU 小于切片长度时再按 MTU 拆（GB: Request.doPerform）。 */
    private void send(HuaweiPacket packet) throws Exception {
        if (packet == null) {
            throw new AuthException("内部错误：要发送的包是 null");
        }
        List<byte[]> slices = packet.serialize();
        if (slices == null || slices.isEmpty()) {
            throw new AuthException("序列化没有产出任何字节: svc=0x" + hex(packet.serviceId)
                    + " cmd=0x" + hex(packet.commandId));
        }
        int mtu = state.getParamsProvider().getMtu();
        for (byte[] slice : slices) {
            if (slice == null || slice.length == 0) {
                continue;
            }
            if (mtu > 0 && slice.length >= mtu) {
                ByteBuffer buffer = ByteBuffer.wrap(slice);
                while (buffer.hasRemaining()) {
                    int len = Math.min(mtu, buffer.remaining());
                    byte[] chunk = new byte[len];
                    buffer.get(chunk, 0, len);
                    link.send(chunk);
                }
            } else {
                link.send(slice);
            }
        }
        Log.d(TAG, "sent svc=0x" + hex(packet.serviceId) + " cmd=0x" + hex(packet.commandId)
                + " slices=" + slices.size() + " mtu=" + mtu);
    }

    /** 等一个 (serviceId, commandId) 匹配的响应。 */
    private HuaweiPacket awaitResponse(int serviceId, int commandId, long timeoutMs, String stepName)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            // 先看缓存
            for (int i = 0; i < pending.size(); i++) {
                HuaweiPacket p = pending.get(i);
                if (matches(p, serviceId, commandId)) {
                    pending.remove(i);
                    Log.d(TAG, stepName + ": 从缓存里取到响应");
                    return p;
                }
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                throw new AuthTimeoutException(stepName, timeoutMs);
            }
            HuaweiPacket p = inbox.poll(remaining, TimeUnit.MILLISECONDS);
            if (p == null) {
                throw new AuthTimeoutException(stepName, timeoutMs);
            }
            if (matches(p, serviceId, commandId)) {
                return p;
            }
            cacheUnrelated(p, stepName);
        }
    }

    /**
     * 等一条 HiChain 响应，并要求它的 step 与当前期望一致。
     *
     * <p>所有 HiChain 报文共用 (0x01, 0x28)，光靠 service/command 分不出是哪一步，
     * 所以这里额外用响应里解出的 {@code step} 校验。顺序错乱的包缓存起来，等下一轮。</p>
     */
    private DeviceConfig.HiChain.Response awaitHiChainResponse(int expectedStep, long timeoutMs, String stepName)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            for (int i = 0; i < pending.size(); i++) {
                HuaweiPacket p = pending.get(i);
                if (isHiChainPacket(p)) {
                    parseTlvQuietly(p);
                    if (hiChainStep(p) == expectedStep) {
                        pending.remove(i);
                        return asHiChainResponse(p, stepName);
                    }
                }
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                throw new AuthTimeoutException(stepName, timeoutMs);
            }
            HuaweiPacket p = inbox.poll(remaining, TimeUnit.MILLISECONDS);
            if (p == null) {
                throw new AuthTimeoutException(stepName, timeoutMs);
            }
            if (isHiChainPacket(p)) {
                parseTlvQuietly(p);
                if (hiChainStep(p) == expectedStep) {
                    return asHiChainResponse(p, stepName);
                }
                Log.w(TAG, stepName + ": 收到乱序 HiChain 响应 step=" + hiChainStep(p)
                        + "（期望 " + expectedStep + "），先缓存");
                pending.add(p);
            } else {
                cacheUnrelated(p, stepName);
            }
        }
    }

    private static boolean matches(HuaweiPacket p, int serviceId, int commandId) {
        return (p.serviceId & 0xFF) == (serviceId & 0xFF)
                && (p.commandId & 0xFF) == (commandId & 0xFF);
    }

    private static boolean isHiChainPacket(HuaweiPacket p) {
        return matches(p, DeviceConfig.id, DeviceConfig.HiChain.id);
    }

    private static int hiChainStep(HuaweiPacket p) {
        return (p instanceof DeviceConfig.HiChain.Response)
                ? (((DeviceConfig.HiChain.Response) p).step & 0xFF)
                : -1;
    }

    private static DeviceConfig.HiChain.Response asHiChainResponse(HuaweiPacket p, String stepName)
            throws AuthException {
        if (p instanceof DeviceConfig.HiChain.Response) {
            return (DeviceConfig.HiChain.Response) p;
        }
        throw new AuthException(stepName + ": svc/cmd 对上了但类型是 "
                + p.getClass().getName() + "，不是 DeviceConfig.HiChain.Response —— "
                + "检查 proto 层 HuaweiPacket.parse() 里 HiChain.id 的 Response 映射");
    }

    /** 把不属于当前步骤的包缓存起来（手环会主动推），别丢。 */
    private void cacheUnrelated(HuaweiPacket p, String stepName) {
        Log.d(TAG, stepName + ": 缓存非本步骤的包 svc=0x" + hex(p.serviceId)
                + " cmd=0x" + hex(p.commandId));
        synchronized (asyncPackets) {
            if (asyncPackets.size() >= MAX_CACHED_ASYNC_PACKETS) {
                HuaweiPacket dropped = asyncPackets.remove(0);
                Log.w(TAG, "异步包缓存已满，丢弃最旧的 svc=0x" + hex(dropped.serviceId)
                        + " cmd=0x" + hex(dropped.commandId));
            }
            asyncPackets.add(p);
        }
    }

    private void parseTlv(HuaweiPacket p, String stepName) throws AuthException {
        try {
            p.parseTlv();
        } catch (HuaweiPacket.ParseException e) {
            throw new AuthException(stepName + ": TLV 解析失败: " + e, e);
        } catch (RuntimeException e) {
            // 例如 tlv 为 null（包根本没走 TLV 编码）导致的 NPE —— 包起来给个能看懂的错。
            throw new AuthException(stepName + ": TLV 解析异常: " + e, e);
        }
    }

    private void parseTlvQuietly(HuaweiPacket p) {
        try {
            p.parseTlv();
        } catch (Exception e) {
            Log.w(TAG, "TLV 解析失败（非致命，可能在等别的包）: " + e);
        }
    }

    /** 认证结束时把还在队列里的包挪进异步缓存，别丢 —— 数据层可以取走。 */
    private void drainLeftoverInbox() {
        HuaweiPacket leftover;
        while ((leftover = inbox.poll()) != null) {
            cacheUnrelated(leftover, "end-of-auth");
        }
    }

    private static <T> T expect(HuaweiPacket packet, Class<T> type, String stepName) throws AuthException {
        if (type.isInstance(packet)) {
            return type.cast(packet);
        }
        throw new AuthException(stepName + ": 响应类型不对，实际是 " + packet.getClass().getName()
                + "，期望 " + type.getName()
                + " —— 检查 proto 层 HuaweiPacket.parse() 里该 commandId 的 Response 映射");
    }

    private long timeout(long fallback) {
        long override = overrideTimeoutMillis;
        return override > 0L ? override : fallback;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private void step(String name) {
        currentStep = name;
        Log.d(TAG, "-> " + name);
    }

    private void markFailed(String reason, Exception cause) {
        lastError = reason;
        lastCause = cause;
        authenticated = false;
        session = null;
        Log.e(TAG, "auth FAILED " + reason, cause);
    }

    private static String describe(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = e.getClass().getSimpleName();
        }
        return msg;
    }

    private void ensureStateLoaded() {
        if (state.getAuthKeyHex() == null) {
            state.load();
        }
    }

    private byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        random.nextBytes(out);
        return out;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) {
            if (a != null) {
                total += a.length;
            }
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            if (a == null || a.length == 0) {
                continue;
            }
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }

    private static String hex(byte[] data) {
        return (data == null) ? "null" : StringUtils.bytesToHex(data);
    }

    private static String hex(byte b) {
        return String.format(java.util.Locale.ROOT, "%02x", b & 0xFF);
    }

    private Session buildSession() {
        return new Session(
                state.getParamsProvider(),
                state.getSessionSecretKey(),
                state.getMacAddress(),
                state.getSerial(),
                state.getAndroidId(),
                authPath,
                state.getAuthMode(),
                state.getAuthVersion(),
                state.getAuthAlgo(),
                state.getDeviceSupportType(),
                state.getEncryptMethod(),
                state.getEncryptionCounter(),
                state.isFirstConnection(),
                negotiatedAuthType,
                negotiatedHonorPairType,
                System.currentTimeMillis());
    }

    // ==================================================================
    // 会话对象
    // ==================================================================

    /**
     * 认证完成后的会话信息，交给数据层用。
     *
     * <p>解密数据包只需要 {@link #paramsProvider}（{@code HuaweiPacket.attemptDecrypt()} 从里面取
     * secretKey / encryptionCounter），{@link #secretKey} 单独拎出来便于直接比较/持久化。</p>
     */
    public static final class Session {

        /** 后续所有数据包构造/解析都要用它。 */
        public final HuaweiPacket.ParamsProvider paramsProvider;
        /** 最终会话密钥（HiChain op=2 的 HKDF 输出 / Normal 模式的 authkey）。 */
        public final byte[] secretKey;
        /** 本机伪造 MAC（UTF-8）。 */
        public final byte[] macAddress;
        /** Bond 用的 clientSerial（6 字节）。 */
        public final byte[] serial;
        /** 本机伪造 androidID（UTF-8）。 */
        public final byte[] androidId;
        /** "hichain" / "hichain-lite" / "normal"。 */
        public final String authPath;
        public final byte authMode;
        public final byte authVersion;
        public final byte authAlgo;
        public final byte deviceSupportType;
        public final byte encryptMethod;
        public final long encryptionCounter;
        public final boolean firstConnection;
        /** SecurityNegotiation 里手环给的 authType（-1 = 没走 SecurityNegotiation）。 */
        public final int negotiatedAuthType;
        /** SecurityNegotiation 里手环给的 pairType（-1 = 未提供）。 */
        public final int negotiatedHonorPairType;
        public final long completedAtMillis;

        Session(HuaweiPacket.ParamsProvider paramsProvider,
                byte[] secretKey,
                byte[] macAddress,
                byte[] serial,
                byte[] androidId,
                String authPath,
                byte authMode,
                byte authVersion,
                byte authAlgo,
                byte deviceSupportType,
                byte encryptMethod,
                long encryptionCounter,
                boolean firstConnection,
                int negotiatedAuthType,
                int negotiatedHonorPairType,
                long completedAtMillis) {
            this.paramsProvider = paramsProvider;
            this.secretKey = secretKey;
            this.macAddress = macAddress;
            this.serial = serial;
            this.androidId = androidId;
            this.authPath = authPath;
            this.authMode = authMode;
            this.authVersion = authVersion;
            this.authAlgo = authAlgo;
            this.deviceSupportType = deviceSupportType;
            this.encryptMethod = encryptMethod;
            this.encryptionCounter = encryptionCounter;
            this.firstConnection = firstConnection;
            this.negotiatedAuthType = negotiatedAuthType;
            this.negotiatedHonorPairType = negotiatedHonorPairType;
            this.completedAtMillis = completedAtMillis;
        }

        public byte[] getSecretKey() {
            return secretKey;
        }

        public HuaweiPacket.ParamsProvider getParamsProvider() {
            return paramsProvider;
        }

        public String getAuthPath() {
            return authPath;
        }

        @Override
        public String toString() {
            // 不打印 secretKey：日志可能被贴到 issue 里
            return "Session{path=" + authPath
                    + ", secretKey=" + (secretKey == null ? "null" : secretKey.length + "B")
                    + ", authMode=" + authMode
                    + ", authVersion=" + authVersion
                    + ", authAlgo=" + authAlgo
                    + ", deviceSupportType=" + deviceSupportType
                    + ", encryptMethod=" + encryptMethod
                    + ", encryptionCounter=" + encryptionCounter
                    + '}';
        }
    }

    // ==================================================================
    // 异常
    // ==================================================================

    /** 认证阶段的通用失败。 */
    public static class AuthException extends Exception {
        public AuthException(String message) {
            super(message);
        }

        public AuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 等响应超时。{@link #stepName} 告诉你卡在哪一步。 */
    public static final class AuthTimeoutException extends AuthException {
        public final String stepName;
        public final long timeoutMillis;

        AuthTimeoutException(String stepName, long timeoutMillis) {
            super("等待 " + stepName + " 的响应超时（" + timeoutMillis + "ms）");
            this.stepName = stepName;
            this.timeoutMillis = timeoutMillis;
        }
    }
}
