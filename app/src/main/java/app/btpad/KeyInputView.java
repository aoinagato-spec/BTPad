package app.btpad;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** スマホのソフトキーボードで打った文字を受け取るための、見えない入力欄。 */
final class KeyInputView extends View {

    interface Sink {
        void typeChar(char c);

        /** usage はHIDのキー番号。 */
        void typeKey(int usage);
    }

    private Sink sink;
    private String composing = "";

    KeyInputView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setSink(Sink s) {
        sink = s;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        composing = "";
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                applyDiff(composing, text == null ? "" : text.toString());
                composing = "";
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                String next = text == null ? "" : text.toString();
                applyDiff(composing, next);
                composing = next;
                return true;
            }

            @Override
            public boolean finishComposingText() {
                composing = "";
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < beforeLength; i++) key(KeyMap.BACKSPACE);
                for (int i = 0; i < afterLength; i++) key(KeyMap.DELETE);
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) handleKeyEvent(event);
                return true;
            }

            @Override
            public boolean performEditorAction(int actionCode) {
                key(KeyMap.ENTER);
                return true;
            }
        };
    }

    /** 物理キーボードなどから直接届くキー。 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event);
        if (handleKeyEvent(event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    private boolean handleKeyEvent(KeyEvent event) {
        int usage = KeyMap.fromKeyCode(event.getKeyCode());
        if (usage != 0) {
            key(usage);
            return true;
        }
        int ch = event.getUnicodeChar();
        if (ch > 0 && ch < 0x10000 && sink != null) {
            sink.typeChar((char) ch);
            return true;
        }
        return false;
    }

    private void key(int usage) {
        if (sink != null) sink.typeKey(usage);
    }

    /** 変換中の文字列が変わったとき、差分だけBackspaceと文字入力で反映する。 */
    private void applyDiff(String before, String after) {
        int p = 0;
        int max = Math.min(before.length(), after.length());
        while (p < max && before.charAt(p) == after.charAt(p)) p++;
        for (int i = before.length(); i > p; i--) key(KeyMap.BACKSPACE);
        if (sink == null) return;
        for (int i = p; i < after.length(); i++) sink.typeChar(after.charAt(i));
    }
}
