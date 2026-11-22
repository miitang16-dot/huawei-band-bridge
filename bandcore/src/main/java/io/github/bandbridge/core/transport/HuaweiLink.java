package io.github.bandbridge.core.transport;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import io.github.bandbridge.core.Probe;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/**
 * 华为手环 BLE 链路 —— 只干一件事：把 FE01 写出去、把 FE02 的通知收回来。
 *
 * 这个类认的是华为私有主通道 0xFE86。两者互不干扰。
 *
 * 协议细节全部在 proto/auth 层，这里不做任何编解码 —— 只当一根管子。
 */
public final class HuaweiLink {

    private static final String TAG = "NxHwLink";

    public static final UUID SVC_HUAWEI  = UUID.fromString("0000fe86-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_WRITE   = UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_NOTIFY  = UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        /** 服务发现完、FE02 通知订阅成功 —— 可以开始认证握手了 */
        void onReady();
        /** 手环推上来的一段原始字节（可能是一片，也可能是半片；攒包由上层做）*/
        void onPacket(byte[] data);
        /** 链路掉了（主动 stop 不会触发）*/
        void onLost(String why);
    }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChr;

    private volatile boolean want = false;
    private volatile boolean ready = false;
    private String curAddr = "";
    private String lastErr = "";

    /* 扫描侧 */
    private boolean pickArmed = false;
    private int pickId = 0;
    private BluetoothDevice bestDev;
    private String bestAddr = "";
    private int retry = 0;
    private static final long PICK_WIN = 2500L;
    private static final int  MAX_RETRY = 45;

    /* 写队列 —— 一次只许一片在飞 */
    private final ArrayDeque<byte[]> wq = new ArrayDeque<>();
    private boolean wBusy = false;
    private long wSince = 0L;                    // 上一片发出去的时刻（等不到回调时靠它解锁）
    private int wRetry = 0;                      // 同一片连续写失败的次数
    private static final long WRITE_GAP = 22L;   // 跟 GB 的节奏对齐：片与片之间给协议栈喘口气
    private static final long WRITE_STUCK = 300L;// NO_RESPONSE 的写回调不保证来 —— 超过这个就当它写完了
    private static final int  WRITE_MAX_RETRY = 25;
    private volatile boolean gaveUp = false;

    public HuaweiLink(Context c, Listener l) {
        this.ctx = c.getApplicationContext();
        this.listener = l;
    }

    /* ── 对外 ─────────────────────────────────────────────── */

    public void start() {
        if (want) return;
        want = true;
        retry = 0;
        gaveUp = false;
        lastErr = "";
        beginScan();
    }

    public void stop() {
        want = false;
        ready = false;
        stopScan();
        closeGatt();
        synchronized (wq) { wq.clear(); wBusy = false; }
        h.removeCallbacksAndMessages(null);
    }

    /** 把一片协议包写进 FE01。可以连续调，内部排队。 */
    public void send(byte[] one) {
        if (one == null || one.length == 0) return;
        synchronized (wq) { wq.addLast(one); }
        h.post(this::pump);                       // 写一律回主线程做，别在 worker 线程碰 gatt
    }

    public void sendAll(List<byte[]> parts) {
        if (parts == null) return;
        synchronized (wq) { wq.addAll(parts); }
        h.post(this::pump);
    }

    public boolean isReady() { return ready; }
    /** 扫了 MAX_RETRY 轮都没成，已经停了 —— 要重新 start() 才会再扫 */
    public boolean hasGivenUp() { return gaveUp; }
    public String getAddr()  { return curAddr; }
    public String lastError(){ return lastErr; }

    public String describe() {
        return "ready=" + ready + " addr=" + curAddr + " err=" + (lastErr.isEmpty() ? "-" : lastErr)
                + " q=" + (wq.size()) + " retry=" + retry;
    }

    /* ── 扫描 ─────────────────────────────────────────────── */

    private boolean ensureAdapter() {
        if (adapter != null && scanner != null) return adapter.isEnabled();
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = bm == null ? null : bm.getAdapter();
        } catch (Throwable t) { adapter = null; }
        if (adapter == null || !adapter.isEnabled()) { report("nobt"); return false; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { report("scanfail"); return false; }
        return true;
    }

    @SuppressLint("MissingPermission")
    private void beginScan() {
        if (!want) return;
        if (!ensureAdapter()) { retryLater(); return; }
        if (gatt != null) {
            if (ready) return;
            closeGatt();
        }
        clearPick();
        try {
            /*
               按服务 UUID 过滤等于自己把门锁上。改成全扫 + 自己认名字/广告。 */
            ScanSettings s = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, s, scanCb);
            report("scanning");
        } catch (Throwable t) {
            lastErr = "scanfail:" + t.getClass().getSimpleName();
            report("scanfail");
            retryLater();
        }
    }

    private void retryLater() {
        if (!want) return;
        retry++;
        if (retry > MAX_RETRY) {
            lastErr = "giveup";
            gaveUp = true;
            stopScan();
            report("giveup");
            listener.onLost("giveup");            // 让上层知道（否则界面一直停在「在找手环…」）
            return;
        }
        stopScan();
        h.postDelayed(this::beginScan, 5000);
    }

    private void stopScan() {
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Throwable t) {}
    }

    private void clearPick() {
        pickArmed = false;
        pickId++;
        bestDev = null;
        bestAddr = "";
    }

    /** 这台像不像要的：名字里带 HUAWEI/HONOR，或者广告里带了 FE86 */
    private boolean looksRight(ScanResult r) {
        try {
            ScanRecord sr = r.getScanRecord();
            String n = sr == null ? null : sr.getDeviceName();
            if (n == null) { try { n = r.getDevice().getName(); } catch (Throwable t) { n = null; } }
            if (n != null) {
                String up = n.toUpperCase();
                if (up.contains("HUAWEI") || up.contains("HONOR")) return true;
            }
            if (sr != null && sr.getServiceUuids() != null) {
                for (ParcelUuid u : sr.getServiceUuids()) {
                    if (SVC_HUAWEI.equals(u.getUuid())) return true;
                }
            }
        } catch (Throwable t) {}
        return false;
    }

    private static String advBrief(ScanResult r) {
        try {
            ScanRecord sr = r.getScanRecord();
            if (sr == null || sr.getServiceUuids() == null) return "none";
            StringBuilder b = new StringBuilder();
            for (ParcelUuid u : sr.getServiceUuids()) {
                String x = String.valueOf(u).toLowerCase();
                if (b.length() > 0) b.append(',');
                b.append(x.length() >= 8 ? x.substring(4, 8) : x);
            }
            return b.toString();
        } catch (Throwable t) { return "?"; }
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult res) {
            if (!want || res == null) return;
            BluetoothDevice d = res.getDevice();
            if (d == null) return;
            String addr = "";
            try { addr = d.getAddress(); } catch (Throwable t) {}
            if (!looksRight(res)) return;                // 不是华为系的东西，看都不看

            if (!curAddr.isEmpty() && curAddr.equals(addr)) {
                pickIt(d, addr, "cached");               // 上次连成功那台，一枪命中
                return;
            }
            if (!pickArmed) {
                pickArmed = true;
                final int myId = ++pickId;
                h.postDelayed(() -> {
                    if (!want || pickId != myId) return;
                    pickId++;
                    if (bestDev != null) { pickIt(bestDev, bestAddr, "win"); return; }
                    retryLater();                        // 一台候选都没有
                }, PICK_WIN);
            }
            if (bestDev == null) {
                bestDev = d;
                bestAddr = addr;
                report("cand", advBrief(res) + " rssi=" + res.getRssi());
            }
        }
        @Override public void onScanFailed(int code) {
            lastErr = "scanfail:" + code;
            report("scanfail");
            retryLater();
        }
    };

    /** 唯一会 stopScan + connectGatt 的地方 */
    @SuppressLint("MissingPermission")
    private void pickIt(BluetoothDevice d, String addr, String why) {
        clearPick();
        stopScan();
        curAddr = addr == null ? "" : addr;
        ready = false;
        report("pick", why + " " + curAddr);
        try {
            gatt = d.connectGatt(ctx, false, gattCb);     // autoConnect=false：主动连更可控
            if (gatt == null) { lastErr = "gattnull"; retryLater(); }
        } catch (Throwable t) {
            lastErr = "connectx:" + t.getClass().getSimpleName();
            retryLater();
        }
    }

    /* ── 连接 / 订阅 ──────────────────────────────────────── */

    private void closeGatt() {
        BluetoothGatt g = gatt;
        gatt = null;
        writeChr = null;
        ready = false;
        /* 上一条链路没写完的片属于上一轮认证，绝不能带到新链路上 */
        synchronized (wq) { wq.clear(); wBusy = false; wRetry = 0; }
        if (g == null) return;
        try { g.disconnect(); } catch (Throwable t) {}
        try { g.close(); } catch (Throwable t) {}
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {

        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int st) {
            if (!want) { try { g.close(); } catch (Throwable t) {} return; }
            if (st == BluetoothProfile.STATE_CONNECTED) {
                report("connected", "status=" + status);
                final int myId = ++pickId;
                h.postDelayed(() -> {
                    if (!want || gatt != g || pickId != myId) return;
                    boolean ok = false;
                    try { ok = g.discoverServices(); } catch (Throwable t) {}
                    report("discgo", "ret=" + (ok ? 1 : 0));
                    if (ok) {
                        h.postDelayed(() -> {
                            if (want && gatt == g && pickId == myId) fail("discTimeout");
                        }, 8000);
                    } else {
                        fail("discFalse");
                    }
                }, 320);
            } else if (st == BluetoothProfile.STATE_DISCONNECTED) {
                if (g != gatt) { try { g.close(); } catch (Throwable t) {} return; }
                report("disconnected", "status=" + status);
                closeGatt();
                ready = false;
                if (want) { listener.onLost("disconnected"); retryLater(); }
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            pickId++;                                   // 回调到了，作废看门狗
            try {
                List<BluetoothGattService> svcs = g.getServices();
                StringBuilder sb = new StringBuilder();
                if (svcs != null) {
                    for (BluetoothGattService s : svcs) {
                        if (s == null || s.getUuid() == null) continue;
                        String u = s.getUuid().toString().toLowerCase();
                        if (sb.length() > 0) sb.append(',');
                        sb.append(u.length() >= 8 ? u.substring(4, 8) : u);
                    }
                }
                report("disc", "status=" + status + " n=" + (svcs == null ? -1 : svcs.size()) + " uuids=" + sb);

                if (status != BluetoothGatt.GATT_SUCCESS) { fail("disc" + status); return; }

                BluetoothGattService svc = g.getService(SVC_HUAWEI);
                if (svc == null) { fail("nosvc"); return; }
                writeChr = svc.getCharacteristic(CHR_WRITE);
                BluetoothGattCharacteristic notifyChr = svc.getCharacteristic(CHR_NOTIFY);
                if (writeChr == null)   { fail("nowrite"); return; }
                if (notifyChr == null)  { fail("nonotify"); return; }

                boolean ok = g.setCharacteristicNotification(notifyChr, true);
                if (!ok) { fail("setnotify"); return; }

                BluetoothGattDescriptor d = notifyChr.getDescriptor(CCCD);
                if (d == null) { fail("nocccd"); return; }
                int r;
                if (Build.VERSION.SDK_INT >= 33) {
                    r = g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (r != BluetoothGatt.GATT_SUCCESS) { fail("cccd" + r); return; }
                } else {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (!g.writeDescriptor(d)) { fail("cccd"); return; }
                }
                report("cccd", "sent");
            } catch (Throwable t) {
                fail("discx:" + t.getClass().getSimpleName());
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (!want) return;
            if (d == null || !CCCD.equals(d.getUuid())) return;
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("cccds" + status); return; }
            ready = true;
            retry = 0;
            lastErr = "";
            report("ready", curAddr);
            listener.onReady();
            h.post(HuaweiLink.this::pump);
        }

        /* 两个签名都覆写 —— 只写一个的话某些机型回调不进来 */
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (ch == null) return;
            byte[] v = null;
            try { v = ch.getValue(); } catch (Throwable t) {}
            if (v != null) listener.onPacket(v);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            if (value != null && value.length > 0) listener.onPacket(value);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            /* NO_RESPONSE 下这条不一定回调；真回调了就把队列往前推 */
            if (status != BluetoothGatt.GATT_SUCCESS) report("wr" + status);
            h.post(() -> {
                synchronized (wq) { if (!wBusy) return; wBusy = false; }
                pump();
            });
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            report("mtu", "mtu=" + mtu + " status=" + status);
        }
    };

    private void fail(String why) {
        lastErr = why;
        report("fail", why);
        closeGatt();
        ready = false;
        retryLater();
    }

    /* ── 写 ───────────────────────────────────────────────── */

    /**
     * 只在主线程跑（send/sendAll/回调都 h.post 过来）。
     *
     * 规则：
     *  · 一次只许一片在飞。wBusy 由写回调清；NO_RESPONSE 下回调不保证来，
     *    所以超过 WRITE_STUCK 也当它写完了 —— 否则漏一次回调整个队列就永远卡死。
     *  · 写失败的那片放回队头重试（Android 13+ 上 201 = 上一片还没走完，很常见），
     *    连续失败 WRITE_MAX_RETRY 次才认定链路坏了交给重连。绝不悄悄丢片。
     */
    @SuppressLint("MissingPermission")
    private void pump() {
        if (!want) return;
        boolean again;
        synchronized (wq) {
            if (wq.isEmpty()) return;
            if (gatt == null || writeChr == null || !ready) return;   // onDescriptorWrite 会再 pump
            if (wBusy) {
                if (System.currentTimeMillis() - wSince < WRITE_STUCK) {
                    h.postDelayed(this::pump, WRITE_GAP);
                    return;
                }
                wBusy = false;                    // 回调没来，按超时放行
            }
            byte[] b = wq.pollFirst();
            if (b == null) return;
            int r = writeNow(b);
            if (r != BluetoothGatt.GATT_SUCCESS) {
                wq.addFirst(b);                   // 放回去，别丢
                wRetry++;
                lastErr = "write" + r;
                if (wRetry == 1 || wRetry % 5 == 0) report("writefail", "r=" + r + " len=" + b.length + " try=" + wRetry);
                if (wRetry >= WRITE_MAX_RETRY) {
                    wRetry = 0;
                    wq.clear();
                    fail("write" + r);            // 真坏了 —— 交给重连
                    return;
                }
                h.postDelayed(this::pump, WRITE_GAP * 2);
                return;
            }
            wRetry = 0;
            wBusy = true;
            wSince = System.currentTimeMillis();
            again = !wq.isEmpty();
        }
        /* NO_RESPONSE 不保证回调 ⇒ 自己按固定节奏推下一片 */
        if (again) h.postDelayed(this::pump, WRITE_GAP);
    }

    @SuppressLint("MissingPermission")
    private int writeNow(byte[] b) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = writeChr;
        if (g == null || c == null) return BluetoothGatt.GATT_FAILURE;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return g.writeCharacteristic(c, b, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                c.setValue(b);
                return g.writeCharacteristic(c) ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE;
            }
        } catch (Throwable t) {
            return BluetoothGatt.GATT_FAILURE;
        }
    }

    private void report(String what) { report(what, ""); }

    private void report(String what, String extra) {
        try {
            Log.d(TAG, what + " " + extra);
            Probe.post("hwlink", what + (extra.isEmpty() ? "" : " | " + extra));
        } catch (Throwable t) {}
    }
}
