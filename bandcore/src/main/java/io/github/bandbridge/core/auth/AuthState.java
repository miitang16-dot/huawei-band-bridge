package io.github.bandbridge.core.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import io.github.bandbridge.core.proto.HuaweiCrypto;
import io.github.bandbridge.core.proto.HuaweiPacket;
import io.github.bandbridge.core.proto.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * 认证层的精简状态容器。
 *
 * <p>取代 Gadgetbridge 的 {@code HuaweiState} + {@code HuaweiSupportProvider} 里跟认证相关的那部分字段。
 * 只承载认证真正用到的东西：</p>
 *
 * <ul>
 *   <li>三个必须<b>跨会话持久化</b>的本机身份：{@code authkey}、伪造本机 MAC、伪造 androidID。
 *       任何一个丢了，手环都会当成新设备重新配对。</li>
 *   <li>握手过程里由 {@code LinkParams.Response} 灌进来的协议参数（也镜像写进 {@link HuaweiPacket.ParamsProvider}，
 *       因为 {@code HuaweiPacket} 的加解密/切片只认 ParamsProvider）。</li>
 *   <li>{@code pinCode} / {@code firstKey} / {@code encryptionCounter} 这些一次性参数。</li>
 * </ul>
 *
 * <p>字段名刻意与 GB 一一对应（{@code macAddress} / {@code androidID} / {@code secretKey} /
 * {@code firstConnection} / {@code huaweiType} ...），方便对着 GB 源码排错。</p>
 *
 * <p>用法：{@code new AuthState(context, deviceAddress).load();} 然后交给 {@link AuthFlow}。
 * 没有 Context 时退化为内存态（不持久化），会打 warn —— 只用于单元测试。</p>
 */
public final class AuthState {

    private static final String TAG = "HuaweiAuthState";

    public static final String PREF_FILE = "bandbridge_auth";
    public static final String KEY_AUTHKEY = "authkey";
    public static final String KEY_DEVICE_MAC = "device_mac";
    public static final String KEY_ANDROID_ID = "android_id";

    // ------------------------------------------------------------------
    // 持久化身份
    // ------------------------------------------------------------------

    private Context context;
    private String deviceAddress;
    private SharedPreferences prefs;

    /** authkey 的 hex 串（GB: sharedPrefs "authkey"）。首配时随机生成，长期保持。 */
    private String authKeyHex;

    /** 伪造的本机 MAC，形如 "FF:FF:FF:AA:BB:CC"（GB: HuaweiConstants.PREF_HUAWEI_ADDRESS）。 */
    private String macAddress;

    /** 伪造的 androidID，hex 串（GB: DeviceSettingsPreferenceConst.PREF_FAKE_ANDROID_ID），默认 32 字符。 */
    private String androidID;

    /** 是否首次连接（决定 HiChain 走 op=1 bind 还是 op=2 重连，以及要不要取 PIN）。 */
    private boolean firstConnection = true;

    // ------------------------------------------------------------------
    // 握手期协议参数（镜像自 ParamsProvider，保留一份便于排查/落 session）
    // ------------------------------------------------------------------

    /** 1..4 / 0x02 = HiChainLite，来自 LinkParams.Response tag 0x07。 */
    private byte deviceSupportType = 0x00;
    /** 来自 LinkParams.Response tag 0x05[1]。 */
    private byte authVersion = 0x00;
    /** 来自 LinkParams.Response tag 0x08。 */
    private byte authAlgo = 0x00;
    /** 由 deviceSupportType 推导：4 → 4，其余 → 2（见 AuthFlow#doSecurityNegotiation）。 */
    private byte authMode = 0x00;
    /** 来自 LinkParams.Response tag 0x0C。 */
    private byte encryptMethod = 0x00;
    /** 来自 LinkParams.Response tag 0x09。 */
    private byte bondState = 0x00;
    /** 来自 LinkParams.Response tag 0x02。 */
    private int sliceSize = 0x00f4;
    /** 来自 LinkParams.Response tag 0x03。 */
    private int mtu = 0x0014;
    /** 来自 LinkParams.Response tag 0x01。 */
    private byte protocolVersion = 0x00;
    /** LinkParams.Response tag 0x05 的 [2..18) —— 16 字节，GetAuth 的 doubleNonce 前半段。 */
    private byte[] serverNonce = new byte[16];
    /** 来自 BondParams.Response tag 0x09。 */
    private long encryptionCounter = 0L;

    /** 来自 Pincode.Response（GB: paramsProvider.setPinCode）。 */
    private byte[] pinCode;
    /** GetAuthRequest 里 digestChallenge 的后半段（GB: paramsProvider.setFirstKey）。 */
    private byte[] firstKey;

    /** {@code HuaweiPacket} 的加解密/切片唯一入口。 */
    private final HuaweiPacket.ParamsProvider paramsProvider = new HuaweiPacket.ParamsProvider();

    // ------------------------------------------------------------------

    public AuthState() {
    }

    public AuthState(Context context, String deviceAddress) {
        this.context = (context == null) ? null : context.getApplicationContext();
        this.deviceAddress = deviceAddress;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 绑定 Context（会自己取 applicationContext）。必须在 {@link #load()} 之前调。
     * 允许重复调用（例如 Service 重建）。
     */
    public AuthState setContext(Context context) {
        this.context = (context == null) ? null : context.getApplicationContext();
        this.prefs = null; // 让 load() 重新拿
        return this;
    }

    /**
     * 绑定真实 BLE 设备地址（GB: {@code supportProvider.getDeviceMac()}）。
     * 只用在 {@code HuaweiCrypto.createSecretKey(mac)}（GetBond 那一步），不参与持久化命名。
     */
    public AuthState setDeviceAddress(String deviceAddress) {
        this.deviceAddress = deviceAddress;
        return this;
    }

    public String getDeviceAddress() {
        return deviceAddress;
    }

    /**
     * 读/补齐三个持久化身份，并把已经存在的值同步进 {@link #paramsProvider}。
     * 幂等。没有 Context 时只保证内存里有值。
     */
    public AuthState load() {
        prefs = obtainPrefs();

        // ---- authkey ----
        String storedKey = (prefs == null) ? null : prefs.getString(KEY_AUTHKEY, null);
        if (storedKey == null || storedKey.isEmpty()) {
            storedKey = StringUtils.bytesToHex(HuaweiCrypto.generateNonce()); // 16 bytes → 32 hex
            if (prefs != null) {
                prefs.edit().putString(KEY_AUTHKEY, storedKey).apply();
            }
            Log.d(TAG, "created authkey (" + storedKey.length() + " hex chars, value not logged)");
        }
        authKeyHex = storedKey;
        paramsProvider.setSecretKey(StringUtils.hexToBytes(authKeyHex));

        // ---- 伪造本机 MAC ----
        String storedMac = (prefs == null) ? null : prefs.getString(KEY_DEVICE_MAC, null);
        if (storedMac == null || storedMac.isEmpty()) {
            StringBuilder mac = new StringBuilder("FF:FF:FF");
            Random r = new Random();
            for (int i = 0; i < 3; i++) {
                int n = r.nextInt(255);
                mac.append(String.format(java.util.Locale.ROOT, ":%02x", n));
            }
            storedMac = mac.toString().toUpperCase();
            if (prefs != null) {
                prefs.edit().putString(KEY_DEVICE_MAC, storedMac).apply();
            }
            Log.d(TAG, "created fake mac (value not logged)");
        }
        macAddress = storedMac;

        // ---- 伪造 androidID ----
        String storedId = (prefs == null) ? null : prefs.getString(KEY_ANDROID_ID, null);
        if (storedId == null || storedId.isEmpty()) {
            storedId = StringUtils.bytesToHex(HuaweiCrypto.generateNonce());
            if (prefs != null) {
                prefs.edit().putString(KEY_ANDROID_ID, storedId).apply();
            }
            Log.d(TAG, "created androidID (" + storedId.length() + " hex chars, value not logged)");
        }
        androidID = storedId;

        return this;
    }

    private SharedPreferences obtainPrefs() {
        if (context == null) {
            Log.w(TAG, "no Context: auth identity will NOT persist across sessions");
            return null;
        }
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /** 清掉持久化身份（重新配对/解绑设备时用）。 */
    public void clearPersistedIdentity() {
        if (prefs == null) {
            prefs = obtainPrefs();
        }
        if (prefs != null) {
            prefs.edit()
                    .remove(KEY_AUTHKEY)
                    .remove(KEY_DEVICE_MAC)
                    .remove(KEY_ANDROID_ID)
                    .apply();
        }
        authKeyHex = null;
        macAddress = null;
        androidID = null;
        paramsProvider.setSecretKey(null);
    }

    // ------------------------------------------------------------------
    // secretKey / authkey（GB 里分得很清楚，别混）
    // ------------------------------------------------------------------
    //
    //  - getSecretKey()      → 长期 authkey（持久化）。HiChain op=2 的 psk 基、Auth 的 key 都取这个。
    //  - setSecretKey(byte[]) → 写持久化 + 写 paramsProvider。用于 HiChain op=1 第 3 步拿到的 authToken。
    //  - getParamsProvider().getSecretKey() → "当前会话密钥"，GetBond 加密、包加解密都用它。
    //    注意 HiChain op=2 结束时 GB 只写 paramsProvider（不写持久化），这是故意的：
    //    下次连接还要先用持久化的 authkey 重新握手。

    /** 长期 authkey（持久化值）。可能是 null（从未配对且未 load）。 */
    public byte[] getSecretKey() {
        if (authKeyHex == null) {
            return null;
        }
        return StringUtils.hexToBytes(authKeyHex);
    }

    /** 长期 authkey 的 hex 串。 */
    public String getAuthKeyHex() {
        return authKeyHex;
    }

    /** 写长期 authkey（持久化 + paramsProvider）。 */
    public void setSecretKey(byte[] secretKey) {
        if (secretKey == null) {
            return;
        }
        authKeyHex = StringUtils.bytesToHex(secretKey);
        if (prefs == null) {
            prefs = obtainPrefs();
        }
        if (prefs != null) {
            prefs.edit().putString(KEY_AUTHKEY, authKeyHex).apply();
        }
        paramsProvider.setSecretKey(secretKey);
    }

    /** 只写 paramsProvider 的会话密钥，不动持久化（HiChain op=2 终局用）。 */
    public void setSessionSecretKey(byte[] sessionKey) {
        paramsProvider.setSecretKey(sessionKey);
    }

    /** 当前会话密钥（paramsProvider 里那份）。 */
    public byte[] getSessionSecretKey() {
        return paramsProvider.getSecretKey();
    }

    // ------------------------------------------------------------------
    // 本机身份
    // ------------------------------------------------------------------

    /** 伪造本机 MAC 字符串，形如 "FF:FF:FF:AA:BB:CC"。 */
    public String getMacAddressString() {
        return macAddress;
    }

    /** GB: supportProvider.getMacAddress() —— UTF-8 字节。BondParams 的 tag 0x07。 */
    public byte[] getMacAddress() {
        return (macAddress == null) ? null : macAddress.getBytes(StandardCharsets.UTF_8);
    }

    /** GB: supportProvider.getSerial() —— mac 去掉冒号后取 [6,12)，6 个字符的 UTF-8 字节。 */
    public byte[] getSerial() {
        if (macAddress == null) {
            return null;
        }
        return macAddress.replace(":", "").substring(6, 12).getBytes(StandardCharsets.UTF_8);
    }

    /** 伪造 androidID 的 hex 串。 */
    public String getAndroidIdString() {
        return androidID;
    }

    /** GB: supportProvider.getAndroidId() —— UTF-8 字节。 */
    public byte[] getAndroidId() {
        return (androidID == null) ? null : androidID.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // 协议参数（大多只是 ParamsProvider 的镜像，setter 同时写两边）
    // ------------------------------------------------------------------

    public HuaweiPacket.ParamsProvider getParamsProvider() {
        return paramsProvider;
    }

    public boolean isFirstConnection() {
        return firstConnection;
    }

    public void setFirstConnection(boolean firstConnection) {
        this.firstConnection = firstConnection;
    }

    public byte getDeviceSupportType() {
        return deviceSupportType;
    }

    public void setDeviceSupportType(byte deviceSupportType) {
        this.deviceSupportType = deviceSupportType;
        paramsProvider.setDeviceSupportType(deviceSupportType);
    }

    public byte getAuthVersion() {
        return authVersion;
    }

    public void setAuthVersion(byte authVersion) {
        this.authVersion = authVersion;
        paramsProvider.setAuthVersion(authVersion);
    }

    public byte getAuthAlgo() {
        return authAlgo;
    }

    public void setAuthAlgo(byte authAlgo) {
        this.authAlgo = authAlgo;
        paramsProvider.setAuthAlgo(authAlgo);
    }

    public byte getAuthMode() {
        return authMode;
    }

    public void setAuthMode(byte authMode) {
        this.authMode = authMode;
        paramsProvider.setAuthMode(authMode);
    }

    public byte getEncryptMethod() {
        return encryptMethod;
    }

    public void setEncryptMethod(byte encryptMethod) {
        this.encryptMethod = encryptMethod;
        paramsProvider.setEncryptMethod(encryptMethod);
    }

    public byte getBondState() {
        return bondState;
    }

    public void setBondState(byte bondState) {
        this.bondState = bondState;
    }

    public int getSliceSize() {
        return sliceSize;
    }

    public void setSliceSize(int sliceSize) {
        this.sliceSize = sliceSize;
        paramsProvider.setSliceSize(sliceSize);
    }

    public int getMtu() {
        return mtu;
    }

    public void setMtu(int mtu) {
        this.mtu = mtu;
        paramsProvider.setMtu(mtu);
    }

    public byte getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(byte protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public byte[] getServerNonce() {
        return serverNonce;
    }

    public void setServerNonce(byte[] serverNonce) {
        this.serverNonce = serverNonce;
    }

    public long getEncryptionCounter() {
        return encryptionCounter;
    }

    public void setEncryptionCounter(long encryptionCounter) {
        this.encryptionCounter = encryptionCounter;
        paramsProvider.setEncryptionCounter(encryptionCounter);
    }

    public byte[] getPinCode() {
        return pinCode;
    }

    public void setPinCode(byte[] pinCode) {
        this.pinCode = pinCode;
        paramsProvider.setPinCode(pinCode);
    }

    public byte[] getFirstKey() {
        return firstKey;
    }

    public void setFirstKey(byte[] firstKey) {
        this.firstKey = firstKey;
        paramsProvider.setFirstKey(firstKey);
    }

    // ------------------------------------------------------------------

    @Override
    public String toString() {
        return "AuthState{device=" + deviceAddress
                + ", firstConnection=" + firstConnection
                + ", authKey=" + (authKeyHex == null ? "null" : authKeyHex.length() + "hex")
                + ", mac=" + (macAddress == null ? "null" : "set")
                + ", androidId=" + (androidID == null ? "null" : "set")
                + ", deviceSupportType=" + deviceSupportType
                + ", authVersion=" + authVersion
                + ", authMode=" + authMode
                + ", authAlgo=" + authAlgo
                + ", encryptMethod=" + encryptMethod
                + ", sliceSize=" + sliceSize
                + ", mtu=" + mtu
                + '}';
    }
}
