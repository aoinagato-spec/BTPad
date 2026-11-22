package app.btpad;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity
        implements HidController.Listener, TouchpadView.Sink, KeyInputView.Sink {

    private static final int REQ_PERM = 1, REQ_ENABLE = 2, REQ_DISCOVER = 3;

    private static final int C_BG = 0xFF101418, C_KEY = 0xFF2A323C, C_KEY_DOWN = 0xFF44536A;
    private static final int C_ACCENT = 0xFF4C9AFF, C_TEXT = 0xFFE6EDF3, C_DIM = 0xFF9AA7B4;
    private static final int C_OK = 0xFF3FB950, C_WARN = 0xFFD29922;

    private HidController hid;
    private SharedPreferences prefs;
    private TextView status;
    private TouchpadView pad;
    private KeyInputView keyIn;
    private float dp;

    private boolean jis;
    private int oneShot, locked;
    private final int[] modMasks = {KeyMap.CTRL, KeyMap.SHIFT, KeyMap.ALT, KeyMap.GUI};
    private final TextView[] modViews = new TextView[4];
    private long lastHintAt;
    private boolean dragLock;
    private final TextView[] dragViews = new TextView[2];
    private final TextView[] modeViews = new TextView[2];
    private View top;
    private LinearLayout fullControls, compactRow, numRow;
    private boolean compact;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("btpad", Context.MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);

        hid = new HidController(this, this);
        setContentView(buildUi());
        loadSettings();

        if (hasBtPermission()) {
            hid.start();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE}, REQ_PERM);
        }
        onStateChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasBtPermission()) hid.start();
        onStateChanged();
    }

    @Override
    protected void onDestroy() {
        hid.stop();
        super.onDestroy();
    }

    private boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_PERM && hasBtPermission()) hid.start();
        onStateChanged();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_ENABLE && hasBtPermission()) hid.start();
        onStateChanged();
    }

    // ---------------------------------------------------------------- 画面

    private int px(float v) {
        return Math.round(v * dp);
    }

    private StateListDrawable keyBg(int normal, int pressed, int stroke) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(pressed, stroke));
        s.addState(new int[]{}, round(normal, stroke));
        return s;
    }

    private GradientDrawable round(int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(10 * dp);
        if (stroke != 0) g.setStroke(px(2), stroke);
        return g;
    }

    private TextView label(String text, float sp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_TEXT);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView keyButton(String text, Runnable onTap) {
        TextView t = label(text, 15);
        t.setBackground(keyBg(C_KEY, C_KEY_DOWN, 0));
        t.setMinWidth(px(54));
        t.setPadding(px(12), 0, px(12), 0);
        t.setClickable(true);
        t.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            onTap.run();
        });
        return t;
    }

    private LinearLayout keyRow(TextView... keys) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (TextView k : keys) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, px(44));
            lp.setMargins(px(3), px(3), px(3), px(3));
            row.addView(k, lp);
        }
        return row;
    }

    private View scrollable(View row) {
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        sv.addView(row);
        return sv;
    }

    private TextView mouseButton(String text, final int mask) {
        TextView t = label(text, 15);
        t.setBackground(keyBg(C_KEY, C_KEY_DOWN, 0));
        t.setClickable(true);
        t.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN && dragLock && mask == HidController.BTN_LEFT) {
                setDragLock(false); // 固定中に左クリックを押したら固定を解除する
            } else if (a == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                hid.setButton(mask, true);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                hid.setButton(mask, false);
            }
            return true;
        });
        return t;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(px(8), px(6), px(8), px(6));
        root.setFitsSystemWindows(true);

        // 上段: 接続状態と設定
        LinearLayout top = new LinearLayout(this);
        this.top = top;
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        status = label("", 14);
        status.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        status.setBackground(keyBg(C_KEY, C_KEY_DOWN, 0));
        status.setPadding(px(14), 0, px(14), 0);
        status.setSingleLine(true);
        status.setClickable(true);
        status.setOnClickListener(v -> onStatusTapped());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, px(46), 1);
        slp.setMargins(px(3), px(3), px(3), px(3));
        top.addView(status, slp);
        TextView settings = keyButton("設定", this::showSettings);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, px(46));
        glp.setMargins(px(3), px(3), px(3), px(3));
        top.addView(settings, glp);
        root.addView(top);

        // タッチパッド
        pad = new TouchpadView(this);
        pad.setSink(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        plp.setMargins(px(3), px(3), px(3), px(3));
        root.addView(pad, plp);

        // キーボードを出していないときの操作部（マウスボタン＋特殊キー）
        fullControls = new LinearLayout(this);
        fullControls.setOrientation(LinearLayout.VERTICAL);

        LinearLayout mouse = new LinearLayout(this);
        mouse.setOrientation(LinearLayout.HORIZONTAL);
        mouse.setBaselineAligned(false); // 2行の文字のボタンが下にずれないようにする
        TextView kb = keyButton("⌨", this::toggleKeyboard);
        kb.setTextColor(C_ACCENT);
        kb.setTextSize(22);
        dragViews[0] = keyButton("", () -> setDragLock(!dragLock));
        View[] mv = {mouseButton("左クリック", HidController.BTN_LEFT), dragViews[0], kb,
                mouseButton("右クリック", HidController.BTN_RIGHT)};
        float[] weights = {3, 2, 1.5f, 3};
        for (int i = 0; i < mv.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(60), weights[i]);
            lp.setMargins(px(3), px(3), px(3), px(3));
            mouse.addView(mv[i], lp);
        }
        fullControls.addView(mouse);

        modeViews[0] = keyButton("", () -> setKanaMode(!keyIn.isKanaMode()));
        fullControls.addView(scrollable(keyRow(
                modeViews[0],
                keyButton("変換/空白", () -> sendKey(0, KeyMap.SPACE)),
                keyButton("確定/Enter", () -> sendKey(0, KeyMap.ENTER)),
                keyButton("BS", () -> sendKey(0, KeyMap.BACKSPACE)),
                keyButton("←", () -> sendKey(0, KeyMap.LEFT)),
                keyButton("↓", () -> sendKey(0, KeyMap.DOWN)),
                keyButton("↑", () -> sendKey(0, KeyMap.UP)),
                keyButton("→", () -> sendKey(0, KeyMap.RIGHT)),
                keyButton("Esc", () -> sendKey(0, KeyMap.ESC)),
                keyButton("Tab", () -> sendKey(0, KeyMap.TAB)),
                keyButton("Del", () -> sendKey(0, KeyMap.DELETE)),
                keyButton("Home", () -> sendKey(0, KeyMap.HOME)),
                keyButton("End", () -> sendKey(0, KeyMap.END)),
                keyButton("PgUp", () -> sendKey(0, KeyMap.PAGE_UP)),
                keyButton("PgDn", () -> sendKey(0, KeyMap.PAGE_DOWN)))));

        String[] modNames = {"Ctrl", "Shift", "Alt", "Win/⌘"};
        TextView[] row2 = new TextView[7];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            modViews[i] = keyButton(modNames[i], () -> cycleModifier(idx));
            row2[i] = modViews[i];
        }
        row2[4] = keyButton("半/全", () -> sendKey(0, KeyMap.HANKAKU));
        row2[5] = keyButton("英数", () -> sendKey(0, KeyMap.EISU));
        row2[6] = keyButton("かな", () -> sendKey(0, KeyMap.KANA));
        fullControls.addView(scrollable(keyRow(row2)));
        root.addView(fullControls);

        // キーボードを出しているときの数字キー（PC側の変換候補を番号で選ぶため）
        numRow = new LinearLayout(this);
        numRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int n = 1; n <= 9; n++) {
            final int usage = 0x1E + (n - 1);
            TextView k = keyButton(String.valueOf(n), () -> sendKey(0, usage));
            k.setMinWidth(0);
            k.setPadding(0, 0, 0, 0);
            k.setTextSize(17);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(46), 1);
            lp.setMargins(px(2), px(2), px(2), px(2));
            numRow.addView(k, lp);
        }
        numRow.setVisibility(View.GONE);
        root.addView(numRow);

        // キーボードを出しているときの操作部（ドラッグ固定・入力モード・閉じる だけ）
        compactRow = new LinearLayout(this);
        compactRow.setOrientation(LinearLayout.HORIZONTAL);
        compactRow.setBaselineAligned(false);
        dragViews[1] = keyButton("", () -> setDragLock(!dragLock));
        modeViews[1] = keyButton("", () -> setKanaMode(!keyIn.isKanaMode()));
        TextView close = keyButton("⌨ 閉じる", this::toggleKeyboard);
        close.setTextColor(C_ACCENT);
        View[] cv = {dragViews[1], modeViews[1], close};
        float[] cw = {2, 1.4f, 1.4f};
        for (int i = 0; i < cv.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(50), cw[i]);
            lp.setMargins(px(3), px(3), px(3), px(3));
            compactRow.addView(cv[i], lp);
        }
        compactRow.setVisibility(View.GONE);
        root.addView(compactRow);

        // キーボードの表示／非表示に合わせて、操作部を切り替える
        // 画面のレイアウトが変わるたびに、ウィンドウ全体の情報からキーボードの有無を調べる
        if (Build.VERSION.SDK_INT >= 30) {
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                WindowInsets in = getWindow().getDecorView().getRootWindowInsets();
                if (in != null) setCompact(in.isVisible(WindowInsets.Type.ime()));
            });
        }

        // ソフトキーボードの入力を受ける見えない欄
        keyIn = new KeyInputView(this);
        keyIn.setSink(this);
        root.addView(keyIn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        refreshModeView();
        refreshDragViews();
        return root;
    }

    // ---------------------------------------------------------------- 接続状態

    @Override
    public void onStateChanged() {
        if (status == null) return;
        String text;
        int color = C_DIM;
        if (!hasBtPermission()) {
            text = "Bluetoothの権限が必要です（タップして許可）";
            color = C_WARN;
        } else if (hid.isUnsupported()) {
            text = "この機種はBluetooth入力機器の機能に非対応です";
            color = C_WARN;
        } else if (!hid.isBluetoothOn()) {
            text = "Bluetoothがオフです（タップしてオン）";
            color = C_WARN;
        } else if (hid.isConnected()) {
            text = "● 接続済み: " + hid.hostName();
            color = C_OK;
        } else if (hid.isConnecting()) {
            text = "接続しています… " + hid.hostName();
        } else if (hid.isRegistered()) {
            text = "未接続（タップして接続先を選ぶ）";
        } else {
            text = "準備中…";
        }
        status.setText(text);
        status.setTextColor(color);
    }

    private void onStatusTapped() {
        if (!hasBtPermission()) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE}, REQ_PERM);
            return;
        }
        if (hid.isUnsupported()) return;
        if (!hid.isBluetoothOn()) {
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
            } catch (Exception e) {
                toast("本体の設定からBluetoothをオンにしてください");
            }
            return;
        }
        showConnectDialog();
    }

    private void showConnectDialog() {
        final List<BluetoothDevice> devices = hid.bondedDevices();
        List<String> items = new ArrayList<>();
        items.add("＋ 新しい機器とペアリングする");
        for (BluetoothDevice d : devices) items.add(hid.nameOf(d));
        final boolean canDisconnect = hid.isConnected() || hid.isConnecting();
        if (canDisconnect) items.add("✕ 切断する");
        new AlertDialog.Builder(this)
                .setTitle("接続先を選ぶ")
                .setItems(items.toArray(new String[0]), (dlg, which) -> {
                    if (which == 0) {
                        showPairingHelp();
                    } else if (which <= devices.size()) {
                        BluetoothDevice d = devices.get(which - 1);
                        if (!hid.connect(d)) toast("まだ準備中です。少し待ってからもう一度どうぞ");
                    } else {
                        hid.disconnect();
                    }
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    private void showPairingHelp() {
        new AlertDialog.Builder(this)
                .setTitle("新しい機器とペアリング")
                .setMessage("1. 下の「検出可能にする」を押します。\n"
                        + "2. PCやiPadのBluetooth設定で「デバイスを追加」を開き、このスマホの名前を選びます。\n"
                        + "3. 両方の画面でペアリングを許可します。\n\n"
                        + "このアプリを開いたまま操作してください。\n\n"
                        + "※ 相手側に以前このスマホを登録していた場合は、相手側でいったん登録を削除してからやり直してください。")
                .setPositiveButton("検出可能にする", (d, w) -> {
                    try {
                        Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180);
                        startActivityForResult(i, REQ_DISCOVER);
                    } catch (Exception e) {
                        toast("本体のBluetooth設定画面を開いたままにすると検出可能になります");
                    }
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 設定

    private void loadSettings() {
        jis = prefs.getBoolean("jis", true);
        pad.pointerSpeed = prefs.getFloat("pointerSpeed", 1f);
        pad.scrollSpeed = prefs.getFloat("scrollSpeed", 1f);
        pad.reverseScroll = prefs.getBoolean("reverseScroll", false);
        keyIn.setKanaMode(prefs.getBoolean("kanaMode", false));
        refreshModeView();
    }

    // ---------------------------------------------------------------- 入力モード

    private void setKanaMode(boolean on) {
        keyIn.setKanaMode(on);
        prefs.edit().putBoolean("kanaMode", on).apply();
        refreshModeView();
        if (on) {
            new AlertDialog.Builder(this)
                    .setTitle("かな入力モード")
                    .setMessage("スマホのキーボードを日本語（フリック）にして、ひらがなで打ってください。\n\n"
                            + "・PC側は日本語入力（ローマ字入力）にしておきます。英字が出るときは「半/全」（Windows）か「かな」（Mac）を押します。\n"
                            + "・漢字にするときは「変換/空白」、決めるときは「確定/Enter」を押します。変換候補はPCの画面に出ます。\n"
                            + "・スマホのキーボードの予測候補で漢字を選んでも送れません。")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void refreshModeView() {
        if (keyIn == null) return;
        boolean kana = keyIn.isKanaMode();
        for (TextView v : modeViews) {
            if (v == null) continue;
            v.setText(kana ? "入力: あ" : "入力: A");
            v.setBackground(keyBg(kana ? C_ACCENT : C_KEY, kana ? C_ACCENT : C_KEY_DOWN, 0));
            v.setTextColor(kana ? 0xFF0B1220 : C_TEXT);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setPadding(px(12), 0, px(12), 0);
        }
    }

    /** キーボード表示中は、タッチパッドと最小限のボタンだけにする。 */
    private void setCompact(boolean on) {
        if (on == compact || fullControls == null) return;
        compact = on;
        top.setVisibility(on ? View.GONE : View.VISIBLE);
        fullControls.setVisibility(on ? View.GONE : View.VISIBLE);
        compactRow.setVisibility(on ? View.VISIBLE : View.GONE);
        numRow.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private static int toProgress(float speed) {
        return Math.round((speed - 0.3f) / 2.7f * 100f);
    }

    private static float toSpeed(int progress) {
        return 0.3f + progress / 100f * 2.7f;
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(22), px(10), px(22), 0);

        TextView l1 = label("カーソルの速さ", 14);
        l1.setGravity(Gravity.START);
        final SeekBar ptr = new SeekBar(this);
        ptr.setMax(100);
        ptr.setProgress(toProgress(pad.pointerSpeed));
        TextView l2 = label("スクロールの速さ", 14);
        l2.setGravity(Gravity.START);
        l2.setPadding(0, px(14), 0, 0);
        final SeekBar scr = new SeekBar(this);
        scr.setMax(100);
        scr.setProgress(toProgress(pad.scrollSpeed));
        final CheckBox rev = new CheckBox(this);
        rev.setText("スクロールの向きを逆にする");
        rev.setChecked(pad.reverseScroll);

        TextView l3 = label("相手側のキーボード配列（記号がずれるときに切り替え）", 14);
        l3.setGravity(Gravity.START);
        l3.setPadding(0, px(14), 0, 0);
        final RadioGroup grp = new RadioGroup(this);
        final RadioButton rJis = new RadioButton(this);
        rJis.setText("日本語配列（JIS）");
        rJis.setId(View.generateViewId());
        final RadioButton rUs = new RadioButton(this);
        rUs.setText("英語配列（US）");
        rUs.setId(View.generateViewId());
        grp.addView(rJis);
        grp.addView(rUs);
        grp.check(jis ? rJis.getId() : rUs.getId());

        box.addView(l1);
        box.addView(ptr);
        box.addView(l2);
        box.addView(scr);
        box.addView(rev);
        box.addView(l3);
        box.addView(grp);

        new AlertDialog.Builder(this)
                .setTitle("設定")
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    pad.pointerSpeed = toSpeed(ptr.getProgress());
                    pad.scrollSpeed = toSpeed(scr.getProgress());
                    pad.reverseScroll = rev.isChecked();
                    jis = grp.getCheckedRadioButtonId() == rJis.getId();
                    prefs.edit()
                            .putFloat("pointerSpeed", pad.pointerSpeed)
                            .putFloat("scrollSpeed", pad.scrollSpeed)
                            .putBoolean("reverseScroll", pad.reverseScroll)
                            .putBoolean("jis", jis)
                            .apply();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ---------------------------------------------------------------- キーボード

    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        boolean shown = false;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets in = getWindow().getDecorView().getRootWindowInsets();
            shown = in != null && in.isVisible(WindowInsets.Type.ime());
        }
        if (shown) {
            imm.hideSoftInputFromWindow(keyIn.getWindowToken(), 0);
        } else {
            keyIn.requestFocus();
            imm.showSoftInput(keyIn, 0);
        }
    }

    /** 修飾キーは タップ1回＝次の1キーだけ / 2回＝押しっぱなし / 3回＝解除。 */
    private void cycleModifier(int idx) {
        int m = modMasks[idx];
        if ((locked & m) != 0) {
            locked &= ~m;
        } else if ((oneShot & m) != 0) {
            oneShot &= ~m;
            locked |= m;
        } else {
            oneShot |= m;
        }
        refreshModifiers();
        hid.setModifiers(oneShot | locked);
    }

    private void refreshModifiers() {
        for (int i = 0; i < 4; i++) {
            int m = modMasks[i];
            TextView v = modViews[i];
            if ((locked & m) != 0) {
                v.setBackground(keyBg(C_ACCENT, C_ACCENT, 0));
                v.setTextColor(0xFF0B1220);
                v.setTypeface(Typeface.DEFAULT_BOLD);
            } else if ((oneShot & m) != 0) {
                v.setBackground(keyBg(C_KEY, C_KEY_DOWN, C_ACCENT));
                v.setTextColor(C_ACCENT);
                v.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                v.setBackground(keyBg(C_KEY, C_KEY_DOWN, 0));
                v.setTextColor(C_TEXT);
                v.setTypeface(Typeface.DEFAULT);
            }
            v.setPadding(px(12), 0, px(12), 0);
        }
    }

    private void sendKey(int mods, int usage) {
        hid.tapKey(oneShot | locked | mods, usage, locked);
        if (oneShot != 0) {
            oneShot = 0;
            refreshModifiers();
        }
    }

    @Override
    public void typeChar(char c) {
        int m = KeyMap.fromChar(c, jis);
        if (m == 0) {
            onUnsupportedText(keyIn.isKanaMode());
            return;
        }
        sendKey(m >> 8, m & 0xFF);
    }

    @Override
    public boolean canType(char c) {
        return KeyMap.fromChar(c, jis) != 0;
    }

    @Override
    public void onUnsupportedText(boolean kanaMode) {
        long now = System.currentTimeMillis();
        if (now - lastHintAt < 4000) return;
        lastHintAt = now;
        if (kanaMode) {
            toast("漢字はスマホ側では送れません。ひらがなのまま「変換/空白」でPC側で変換してください");
        } else {
            toast("英字モードです。日本語を打つときは「入力: A」を押して「あ」にしてください");
        }
    }

    @Override
    public void typeKey(int usage) {
        sendKey(0, usage);
    }

    // ---------------------------------------------------------------- タッチパッド

    @Override
    public void move(int dx, int dy) {
        hid.move(dx, dy);
    }

    @Override
    public void scroll(int wheel, int pan) {
        hid.scroll(wheel, pan);
    }

    @Override
    public void click(int buttonMask) {
        if (dragLock && buttonMask == HidController.BTN_LEFT) {
            setDragLock(false); // 固定中にタップしたら、その場で離す
            return;
        }
        hid.click(buttonMask);
    }

    @Override
    public void button(int buttonMask, boolean down) {
        if (dragLock && buttonMask == HidController.BTN_LEFT) return;
        hid.setButton(buttonMask, down);
    }

    /** ドラッグ固定: オンの間は左ボタンを押しっぱなしにする。 */
    private void setDragLock(boolean on) {
        dragLock = on;
        hid.setButton(HidController.BTN_LEFT, on);
        refreshDragViews();
    }

    private void refreshDragViews() {
        for (TextView v : dragViews) {
            if (v == null) continue;
            if (dragLock) {
                v.setBackground(keyBg(C_ACCENT, C_ACCENT, 0));
                v.setTextColor(0xFF0B1220);
                v.setText("固定中\nタップで解除");
            } else {
                v.setBackground(keyBg(C_KEY, C_KEY_DOWN, 0));
                v.setTextColor(C_TEXT);
                v.setText("ドラッグ\n固定");
            }
            v.setTextSize(13);
            v.setPadding(px(6), 0, px(6), 0);
        }
    }
}
