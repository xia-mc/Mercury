package asia.lira.mercury.impl;

import com.mojang.brigadier.StringReader;

public final class StaticStringReader extends StringReader {
    public StaticStringReader(String string) {
        super(string);
    }

    @Override
    public void setCursor(int cursor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getRemainingLength() {
        return getString().length();
    }

    @Override
    public int getTotalLength() {
        return getString().length();
    }

    @Override
    public int getCursor() {
        return 0;
    }

    @Override
    public String getRead() {
        return "";
    }

    @Override
    public String getRemaining() {
        return getString();
    }

    @Override
    public boolean canRead(int length) {
        return length <= getString().length();
    }

    @Override
    public boolean canRead() {
        return canRead(1);
    }

    @Override
    public char peek() {
        return getString().charAt(0);
    }

    @Override
    public char peek(int offset) {
        return getString().charAt(offset);
    }

    @Override
    public char read() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void skip() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void skipWhitespace() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int readInt() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long readLong() {
        throw new UnsupportedOperationException();
    }

    @Override
    public double readDouble() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float readFloat() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String readUnquotedString() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String readQuotedString() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String readStringUntil(char terminator) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String readString() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean readBoolean() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void expect(char c) {
        throw new UnsupportedOperationException();
    }
}
