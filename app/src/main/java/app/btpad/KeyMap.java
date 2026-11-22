package app.btpad;

import android.view.KeyEvent;

/** 文字やAndroidのキーコードを、USB HIDのキー番号（usage）に変換する。 */
final class KeyMap {
    static final int CTRL = 0x01, SHIFT = 0x02, ALT = 0x04, GUI = 0x08;

    static final int ENTER = 0x28, ESC = 0x29, BACKSPACE = 0x2A, TAB = 0x2B, SPACE = 0x2C;
    static final int DELETE = 0x4C, RIGHT = 0x4F, LEFT = 0x50, DOWN = 0x51, UP = 0x52;
    static final int HOME = 0x4A, END = 0x4D, PAGE_UP = 0x4B, PAGE_DOWN = 0x4E;
    static final int HANKAKU = 0x35;   // JISキーボードの 半角/全角
    static final int KANA = 0x90;      // Macの「かな」(LANG1)
    static final int EISU = 0x91;      // Macの「英数」(LANG2)

    private KeyMap() {}

    /** 戻り値は (修飾キー << 8) | usage。変換できない文字は 0。 */
    static int fromChar(char c, boolean jis) {
        if (c >= 'a' && c <= 'z') return 0x04 + (c - 'a');
        if (c >= 'A' && c <= 'Z') return (SHIFT << 8) | (0x04 + (c - 'A'));
        if (c >= '1' && c <= '9') return 0x1E + (c - '1');
        switch (c) {
            case '0': return 0x27;
            case ' ': return SPACE;
            case '　': return SPACE;
            case '\n': return ENTER;
            case '\t': return TAB;
            case ',': return 0x36;
            case '<': return s(0x36);
            case '.': return 0x37;
            case '>': return s(0x37);
            case '/': return 0x38;
            case '?': return s(0x38);
            case '!': return s(0x1E);
            case '#': return s(0x20);
            case '$': return s(0x21);
            case '%': return s(0x22);
            case '-': return 0x2D;
            default: break;
        }
        return jis ? jisSymbol(c) : usSymbol(c);
    }

    private static int s(int usage) {
        return (SHIFT << 8) | usage;
    }

    private static int usSymbol(char c) {
        switch (c) {
            case '@': return s(0x1F);
            case '^': return s(0x23);
            case '&': return s(0x24);
            case '*': return s(0x25);
            case '(': return s(0x26);
            case ')': return s(0x27);
            case '_': return s(0x2D);
            case '=': return 0x2E;
            case '+': return s(0x2E);
            case '[': return 0x2F;
            case '{': return s(0x2F);
            case ']': return 0x30;
            case '}': return s(0x30);
            case '\\': return 0x31;
            case '¥': return 0x31;
            case '|': return s(0x31);
            case ';': return 0x33;
            case ':': return s(0x33);
            case '\'': return 0x34;
            case '"': return s(0x34);
            case '`': return 0x35;
            case '~': return s(0x35);
            default: return 0;
        }
    }

    private static int jisSymbol(char c) {
        switch (c) {
            case '"': return s(0x1F);
            case '&': return s(0x23);
            case '\'': return s(0x24);
            case '(': return s(0x25);
            case ')': return s(0x26);
            case '=': return s(0x2D);
            case '^': return 0x2E;
            case '~': return s(0x2E);
            case '¥': return 0x89;
            case '|': return s(0x89);
            case '@': return 0x2F;
            case '`': return s(0x2F);
            case '[': return 0x30;
            case '{': return s(0x30);
            case ';': return 0x33;
            case '+': return s(0x33);
            case ':': return 0x34;
            case '*': return s(0x34);
            case ']': return 0x32;
            case '}': return s(0x32);
            case '\\': return 0x87;
            case '_': return s(0x87);
            default: return 0;
        }
    }

    /** 文字にならないキー（矢印やEnterなど）。該当なしは 0。 */
    static int fromKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return ENTER;
            case KeyEvent.KEYCODE_DEL: return BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return DELETE;
            case KeyEvent.KEYCODE_TAB: return TAB;
            case KeyEvent.KEYCODE_SPACE: return SPACE;
            case KeyEvent.KEYCODE_ESCAPE: return ESC;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME: return HOME;
            case KeyEvent.KEYCODE_MOVE_END: return END;
            case KeyEvent.KEYCODE_PAGE_UP: return PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return PAGE_DOWN;
            default: return 0;
        }
    }
}
