package app.btpad;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** スマホをBluetoothのマウス＋キーボードとして登録し、操作を送る。 */
final class HidController {

    interface Listener {
        void onStateChanged();
    }

    static final int ID_MOUSE = 1;
    static final int ID_KEYBOARD = 2;

    static final int BTN_LEFT = 1, BTN_RIGHT = 2, BTN_MIDDLE = 4;

    /** マウス（レポートID 1）とキーボード（レポートID 2）のHIDディスクリプタ。 */
    static final byte[] DESCRIPTOR = bytes(
            // ---- マウス: ボタン3つ, X, Y, 縦ホイール, 横ホイール ----
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x02,       // Usage (Mouse)
            0xA1, 0x01,       // Collection (Application)
            0x85, ID_MOUSE,   //   Report ID
            0x09, 0x01,       //   Usage (Pointer)
            0xA1, 0x00,       //   Collection (Physical)
            0x05, 0x09,       //     Usage Page (Buttons)
            0x19, 0x01,       //     Usage Minimum (1)
            0x29, 0x03,       //     Usage Maximum (3)
            0x15, 0x00,       //     Logical Minimum (0)
            0x25, 0x01,       //     Logical Maximum (1)
            0x95, 0x03,       //     Report Count (3)
            0x75, 0x01,       //     Report Size (1)
            0x81, 0x02,       //     Input (Data, Variable, Absolute)
            0x95, 0x01,       //     Report Count (1)
            0x75, 0x05,       //     Report Size (5)
            0x81, 0x03,       //     Input (Constant) 余りビット
            0x05, 0x01,       //     Usage Page (Generic Desktop)
            0x09, 0x30,       //     Usage (X)
            0x09, 0x31,       //     Usage (Y)
            0x09, 0x38,       //     Usage (Wheel)
            0x15, 0x81,       //     Logical Minimum (-127)
            0x25, 0x7F,       //     Logical Maximum (127)
            0x75, 0x08,       //     Report Size (8)
            0x95, 0x03,       //     Report Count (3)
            0x81, 0x06,       //     Input (Data, Variable, Relative)
            0x05, 0x0C,       //     Usage Page (Consumer)
            0x0A, 0x38, 0x02, //     Usage (AC Pan) 横スクロール
            0x15, 0x81,       //     Logical Minimum (-127)
            0x25, 0x7F,       //     Logical Maximum (127)
            0x75, 0x08,       //     Report Size (8)
            0x95, 0x01,       //     Report Count (1)
            0x81, 0x06,       //     Input (Data, Variable, Relative)
            0xC0,             //   End Collection
            0xC0,             // End Collection
            // ---- キーボード: 修飾キー1バイト, 予約1バイト, キー6つ ----
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x06,       // Usage (Keyboard)
            0xA1, 0x01,       // Collection (Application)
            0x85, ID_KEYBOARD, //  Report ID
            0x05, 0x07,       //   Usage Page (Keyboard)
            0x19, 0xE0,       //   Usage Minimum (Left Ctrl)
            0x29, 0xE7,       //   Usage Maximum (Right GUI)
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x01,       //   Logical Maximum (1)
            0x75, 0x01,       //   Report Size (1)
            0x95, 0x08,       //   Report Count (8)
            0x81, 0x02,       //   Input (Data, Variable, Absolute)
            0x95, 0x01,       //   Report Count (1)
            0x75, 0x08,       //   Report Size (8)
            0x81, 0x01,       //   Input (Constant) 予約
            0x95, 0x05,       //   Report Count (5)
            0x75, 0x01,       //   Report Size (1)
            0x05, 0x08,       //   Usage Page (LEDs)
            0x19, 0x01,       //   Usage Minimum (Num Lock)
            0x29, 0x05,       //   Usage Maximum (Kana)
            0x91, 0x02,       //   Output (Data, Variable, Absolute)
            0x95, 0x01,       //   Report Count (1)
            0x75, 0x03,       //   Report Size (3)
            0x91, 0x01,       //   Output (Constant)
            0x95, 0x06,       //   Report Count (6)
            0x75, 0x08,       //   Report Size (8)
            0x15, 0x00,       //   Logical Minimum (0)
            0x26, 0xFF, 0x00, //   Logical Maximum (255)
            0x05, 0x07,       //   Usage Page (Keyboard)
            0x19, 0x00,       //   Usage Minimum (0)
            0x2A, 0xFF, 0x00, //   Usage Maximum (255)
            0x81, 0x00,       //   Input (Data, Array)
            0xC0              // End Collection
    );

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService keyThread = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;

    private BluetoothAdapter adapter;
    private volatile BluetoothHidDevice hid;
    private volatile BluetoothDevice host;
    private volatile boolean registered;
    private volatile int connState = BluetoothProfile.STATE_DISCONNECTED;
    private boolean proxyRequested;
    private boolean unsupported;
    private boolean triedAutoConnect;

    private int buttons;
    private int lastMods; // keyThread からのみ触る

    HidController(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.prefs = this.ctx.getSharedPreferences("btpad", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 状態

    boolean isBluetoothOn() {
        return adapter != null && adapter.isEnabled();
    }

    boolean isUnsupported() {
        return unsupported;
    }

    boolean isRegistered() {
        return registered;
    }

    boolean isConnected() {
        return host != null && connState == BluetoothProfile.STATE_CONNECTED;
    }

    boolean isConnecting() {
        return connState == BluetoothProfile.STATE_CONNECTING;
    }

    String hostName() {
        BluetoothDevice d = host;
        if (d == null) return "";
        return nameOf(d);
    }

    String nameOf(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n != null ? n : d.getAddress();
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    List<BluetoothDevice> bondedDevices() {
        List<BluetoothDevice> out = new ArrayList<>();
        try {
            if (adapter != null) {
                Set<BluetoothDevice> set = adapter.getBondedDevices();
                if (set != null) out.addAll(set);
            }
        } catch (SecurityException ignored) {
        }
        return out;
    }

    // ---------------------------------------------------------------- 開始と終了

    /** Bluetoothの権限がそろってから呼ぶ。何度呼んでもよい。 */
    void start() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) {
            unsupported = true;
            notifyState();
            return;
        }
        if (!adapter.isEnabled()) {
            notifyState();
            return;
        }
        if (hid != null) {
            if (!registered) register();
            return;
        }
        if (proxyRequested) return;
        try {
            proxyRequested = adapter.getProfileProxy(ctx, serviceListener, BluetoothProfile.HID_DEVICE);
            if (!proxyRequested) unsupported = true;
        } catch (SecurityException e) {
            proxyRequested = false;
        }
        notifyState();
    }

    void stop() {
        BluetoothHidDevice h = hid;
        if (h != null) {
            try {
                h.unregisterApp();
            } catch (Exception ignored) {
            }
            try {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, h);
            } catch (Exception ignored) {
            }
        }
        hid = null;
        host = null;
        registered = false;
        proxyRequested = false;
        connState = BluetoothProfile.STATE_DISCONNECTED;
        keyThread.shutdownNow();
    }

    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = (BluetoothHidDevice) proxy;
            register();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            hid = null;
            host = null;
            registered = false;
            proxyRequested = false;
            connState = BluetoothProfile.STATE_DISCONNECTED;
            notifyState();
        }
    };

    private void register() {
        BluetoothHidDevice h = hid;
        if (h == null) return;
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "BT Pad", "Touchpad and keyboard", "BT Pad",
                BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR);
        try {
            h.registerApp(sdp, null, null, Runnable::run, callback);
        } catch (SecurityException ignored) {
        }
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            registered = isRegistered;
            if (!isRegistered) {
                host = null;
                connState = BluetoothProfile.STATE_DISCONNECTED;
                triedAutoConnect = false;
            } else {
                main.post(HidController.this::autoConnect);
            }
            notifyState();
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                connState = state;
                prefs.edit().putString("lastHost", device.getAddress()).apply();
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                host = device;
                connState = state;
            } else if (host == null || device.equals(host)) {
                if (state == BluetoothProfile.STATE_DISCONNECTED) host = null;
                connState = state;
            }
            notifyState();
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            BluetoothHidDevice h = hid;
            if (h == null) return;
            try {
                if (id == ID_MOUSE) h.replyReport(device, type, id, new byte[5]);
                else if (id == ID_KEYBOARD) h.replyReport(device, type, id, new byte[8]);
                else h.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
            } catch (SecurityException ignored) {
            }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            BluetoothHidDevice h = hid;
            if (h == null) return;
            try {
                h.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
            } catch (SecurityException ignored) {
            }
        }
    };

    /** 前回つないだ相手がいれば、起動時に一度だけ自動でつなぎにいく。 */
    private void autoConnect() {
        if (triedAutoConnect || host != null) return;
        triedAutoConnect = true;
        String addr = prefs.getString("lastHost", null);
        if (addr == null) return;
        for (BluetoothDevice d : bondedDevices()) {
            if (addr.equals(d.getAddress())) {
                connect(d);
                return;
            }
        }
    }

    boolean connect(BluetoothDevice device) {
        BluetoothHidDevice h = hid;
        if (h == null || !registered) return false;
        try {
            BluetoothDevice cur = host;
            if (cur != null && !cur.equals(device)) h.disconnect(cur);
            return h.connect(device);
        } catch (SecurityException e) {
            return false;
        }
    }

    void disconnect() {
        BluetoothHidDevice h = hid;
        BluetoothDevice d = host;
        if (h == null || d == null) return;
        try {
            h.disconnect(d);
        } catch (SecurityException ignored) {
        }
    }

    private void notifyState() {
        main.post(listener::onStateChanged);
    }

    // ---------------------------------------------------------------- マウス

    private boolean send(int id, byte[] data) {
        BluetoothHidDevice h = hid;
        BluetoothDevice d = host;
        if (h == null || d == null || connState != BluetoothProfile.STATE_CONNECTED) return false;
        try {
            return h.sendReport(d, id, data);
        } catch (Exception e) {
            return false;
        }
    }

    private static int clamp(int v) {
        return Math.max(-127, Math.min(127, v));
    }

    private void mouseReport(int dx, int dy, int wheel, int pan) {
        send(ID_MOUSE, new byte[]{(byte) buttons, (byte) dx, (byte) dy, (byte) wheel, (byte) pan});
    }

    void move(int dx, int dy) {
        while (dx != 0 || dy != 0) {
            int sx = clamp(dx), sy = clamp(dy);
            mouseReport(sx, sy, 0, 0);
            dx -= sx;
            dy -= sy;
        }
    }

    void scroll(int wheel, int pan) {
        while (wheel != 0 || pan != 0) {
            int sw = clamp(wheel), sp = clamp(pan);
            mouseReport(0, 0, sw, sp);
            wheel -= sw;
            pan -= sp;
        }
    }

    void setButton(int mask, boolean down) {
        int next = down ? (buttons | mask) : (buttons & ~mask);
        if (next == buttons) return;
        buttons = next;
        mouseReport(0, 0, 0, 0);
    }

    void click(final int mask) {
        setButton(mask, true);
        main.postDelayed(() -> setButton(mask, false), 25);
    }

    // ---------------------------------------------------------------- キーボード

    private void keyReport(int mods, int usage) {
        send(ID_KEYBOARD, new byte[]{(byte) mods, 0, (byte) usage, 0, 0, 0, 0, 0});
        lastMods = mods;
        try {
            Thread.sleep(6);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * キーを1回押して離す。
     * @param mods 押している間の修飾キー
     * @param usage HIDのキー番号
     * @param modsAfter 離したあとも押しっぱなしにする修飾キー
     */
    void tapKey(final int mods, final int usage, final int modsAfter) {
        if (keyThread.isShutdown()) return;
        keyThread.execute(() -> {
            if (mods != lastMods) keyReport(mods, 0);
            keyReport(mods, usage);
            keyReport(mods, 0);
            if (modsAfter != mods) keyReport(modsAfter, 0);
        });
    }

    /** 修飾キーだけを押した／離した状態にする（Ctrl+クリックなどのため）。 */
    void setModifiers(final int mods) {
        if (keyThread.isShutdown()) return;
        keyThread.execute(() -> {
            if (mods != lastMods) keyReport(mods, 0);
        });
    }
}
