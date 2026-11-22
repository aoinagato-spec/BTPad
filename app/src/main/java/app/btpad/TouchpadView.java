package app.btpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * タッチパッド。
 * 1本指でなぞる＝カーソル移動 / タップ＝左クリック / 2本指タップ＝右クリック /
 * 3本指タップ＝中クリック / 2本指でなぞる＝スクロール / タップしてすぐ押さえてなぞる＝ドラッグ
 */
final class TouchpadView extends View {

    interface Sink {
        void move(int dx, int dy);

        void scroll(int wheel, int pan);

        void click(int buttonMask);

        void button(int buttonMask, boolean down);
    }

    private static final long TAP_MS = 300;
    private static final long DRAG_WINDOW_MS = 280;

    private final float density;
    private final float slop;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private Sink sink;
    float pointerSpeed = 1f;
    float scrollSpeed = 1f;
    boolean reverseScroll;

    private float lastX, lastY, travel;
    private float fracX, fracY, fracWheel, fracPan;
    private long downTime, lastMoveTime, lastTapUp;
    private float lastTapX, lastTapY;
    private int maxPointers;
    private boolean moved, dragging;

    TouchpadView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        slop = 4f * density;
        bg.setColor(0xFF1B2128);
        hint.setColor(0x55E6EDF3);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(13f * density);
        setHapticFeedbackEnabled(true);
    }

    void setSink(Sink s) {
        sink = s;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = 14f * density;
        rect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(rect, r, r, bg);
        float cx = getWidth() / 2f, cy = getHeight() / 2f, lh = 20f * density;
        canvas.drawText("タップ＝左クリック　2本指タップ＝右クリック", cx, cy - lh * 0.5f, hint);
        canvas.drawText("2本指でなぞる＝スクロール", cx, cy + lh * 0.5f, hint);
        canvas.drawText("タップ→すぐ押さえてなぞる＝ドラッグ", cx, cy + lh * 1.5f, hint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (sink == null) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lastX = e.getX();
                lastY = e.getY();
                travel = 0;
                fracX = fracY = fracWheel = fracPan = 0;
                moved = false;
                maxPointers = 1;
                downTime = lastMoveTime = e.getEventTime();
                float d = (float) Math.hypot(lastX - lastTapX, lastY - lastTapY);
                if (downTime - lastTapUp < DRAG_WINDOW_MS && d < 48f * density) {
                    dragging = true;
                    sink.button(HidController.BTN_LEFT, true);
                }
                lastTapUp = 0;
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                maxPointers = Math.max(maxPointers, e.getPointerCount());
                setCentroid(e, -1);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                long now = e.getEventTime();
                float dt = Math.max(1, now - lastMoveTime);
                lastMoveTime = now;
                float x, y;
                int n = e.getPointerCount();
                if (n == 1) {
                    x = e.getX();
                    y = e.getY();
                } else {
                    x = 0;
                    y = 0;
                    for (int i = 0; i < n; i++) {
                        x += e.getX(i);
                        y += e.getY(i);
                    }
                    x /= n;
                    y /= n;
                }
                float dx = x - lastX, dy = y - lastY;
                lastX = x;
                lastY = y;
                if (!moved) {
                    travel += (float) Math.hypot(dx, dy);
                    if (travel < slop) return true;
                    moved = true;
                    return true; // しきい値までの動きは捨てる（タップのブレ対策）
                }
                if (n == 1) {
                    if (maxPointers == 1) movePointer(dx, dy, dt);
                } else {
                    scrollBy(dx, dy);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                setCentroid(e, e.getActionIndex());
                return true;
            }
            case MotionEvent.ACTION_UP: {
                long dur = e.getEventTime() - downTime;
                if (dragging) {
                    dragging = false;
                    sink.button(HidController.BTN_LEFT, false);
                } else if (!moved && dur < TAP_MS) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (maxPointers == 1) {
                        sink.click(HidController.BTN_LEFT);
                        lastTapUp = e.getEventTime();
                        lastTapX = e.getX();
                        lastTapY = e.getY();
                    } else if (maxPointers == 2) {
                        sink.click(HidController.BTN_RIGHT);
                    } else {
                        sink.click(HidController.BTN_MIDDLE);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (dragging) {
                    dragging = false;
                    sink.button(HidController.BTN_LEFT, false);
                }
                return true;
            }
            default:
                return true;
        }
    }

    /** 指の本数が変わったとき、基準位置を取り直してカーソルが飛ばないようにする。 */
    private void setCentroid(MotionEvent e, int skipIndex) {
        float x = 0, y = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skipIndex) continue;
            x += e.getX(i);
            y += e.getY(i);
            n++;
        }
        if (n == 0) return;
        lastX = x / n;
        lastY = y / n;
    }

    private void movePointer(float dxPx, float dyPx, float dtMs) {
        float dx = dxPx / density, dy = dyPx / density;
        float v = (float) Math.hypot(dx, dy) / dtMs; // dp / ms
        float accel = 1f + 0.9f * Math.min(v / 0.6f, 3f);
        float gain = 1.5f * pointerSpeed * accel;
        fracX += dx * gain;
        fracY += dy * gain;
        int ix = (int) fracX, iy = (int) fracY;
        fracX -= ix;
        fracY -= iy;
        if (ix != 0 || iy != 0) sink.move(ix, iy);
    }

    private void scrollBy(float dxPx, float dyPx) {
        float step = 16f * density / scrollSpeed;
        float dir = reverseScroll ? -1f : 1f;
        // 斜めのブレで縦横が混ざらないよう、大きく動いた方向だけ使う
        if (Math.abs(dyPx) >= Math.abs(dxPx)) fracWheel += dir * dyPx / step;
        else fracPan += -dir * dxPx / step;
        int w = (int) fracWheel, p = (int) fracPan;
        fracWheel -= w;
        fracPan -= p;
        if (w != 0 || p != 0) sink.scroll(w, p);
    }
}
