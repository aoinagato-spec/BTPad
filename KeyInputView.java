package app.btpad;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * スマホのソフトキーボードで打った文字を受け取るための、見えない入力欄。
 * 英字モード: 打った英数字をそのままキーとして送る。
 * かなモード: フリックで打ったひらがなをローマ字のキー列にして送り、PC側の日本語入力で変換してもらう。
 */
final class KeyInputView extends View {

    interface Sink {
        void typeChar(char c);

        /** usage はHIDのキー番号。 */
        void typeKey(int usage);

        /** その文字をキーとして送れるか。 */
        boolean canType(char c);

        /** 送れない文字（漢字など）が来たとき。 */
        void onUnsupportedText(boolean kanaMode);
    }

    private Sink sink;
    private String composing = "";
    private boolean kanaMode;

    KeyInputView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setSink(Sink s) {
        sink = s;
    }

    boolean isKanaMode() {
        return kanaMode;
    }

    /** 入力モードを切り替え、スマホのキーボードにも反映する。 */
    void setKanaMode(boolean on) {
        if (kanaMode == on) return;
        kanaMode = on;
        composing = "";
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.restartInput(this);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        if (kanaMode) {
            // 普通の文章入力として開き、日本語キーボード（フリック）を使えるようにする
            out.inputType = InputType.TYPE_CLASS_TEXT;
        } else {
            out.inputType = InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        }
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
                if (applyDiff(composing, next)) composing = next;
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
        if (ch > 0 && ch < 0x10000) {
            typeOne((char) ch);
            return true;
        }
        return false;
    }

    private void key(int usage) {
        if (sink != null) sink.typeKey(usage);
    }

    /** 1文字を送る。かなモードならローマ字のキー列にする。 */
    private void typeOne(char c) {
        if (sink == null) return;
        if (kanaMode) {
            String keys = KeyMap.kanaToKeys(c);
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) sink.typeChar(keys.charAt(i));
                return;
            }
        }
        sink.typeChar(c);
    }

    private boolean sendable(char c) {
        if (sink == null) return false;
        if (kanaMode && KeyMap.kanaToKeys(c) != null) return true;
        return sink.canType(c);
    }

    /**
     * 入力中の文字列が変わったとき、差分だけBackspaceと文字入力で反映する。
     * 送れない文字（スマホ側で変換した漢字など）を含むときは何も送らず false を返す。
     * そのときPC側には変換前のひらがなが残るので、PC側で変換してもらう。
     */
    private boolean applyDiff(String before, String after) {
        int p = 0;
        int max = Math.min(before.length(), after.length());
        while (p < max && before.charAt(p) == after.charAt(p)) p++;
        for (int i = p; i < after.length(); i++) {
            if (!sendable(after.charAt(i))) {
                if (sink != null) sink.onUnsupportedText(kanaMode);
                return false;
            }
        }
        for (int i = before.length(); i > p; i--) key(KeyMap.BACKSPACE);
        for (int i = p; i < after.length(); i++) typeOne(after.charAt(i));
        return true;
    }
}
