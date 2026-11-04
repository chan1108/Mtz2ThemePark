package com.chan.mtz2themepark;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 小端二进制写入器，以及 Android 资源格式通用的 UTF-16 字符串池。 */
final class Bin {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Bin u8(int v) { out.write(v); return this; }
    Bin u16(int v) { out.write(v); out.write(v >> 8); return this; }
    Bin u32(int v) { u16(v); u16(v >>> 16); return this; }
    Bin bytes(byte[] b) { out.write(b, 0, b.length); return this; }
    Bin zeros(int n) { for (int i = 0; i < n; i++) out.write(0); return this; }
    int size() { return out.size(); }
    byte[] toBytes() { return out.toByteArray(); }

    /** 按出现顺序去重的字符串池。 */
    static final class Pool {
        private final Map<String, Integer> index = new LinkedHashMap<>();

        int add(String s) {
            Integer i = index.get(s);
            if (i != null) return i;
            int n = index.size();
            index.put(s, n);
            return n;
        }

        int size() { return index.size(); }

        byte[] encode() {
            List<byte[]> strings = new ArrayList<>(index.size());
            int dataLen = 0;
            for (String s : index.keySet()) {
                byte[] utf16 = s.getBytes(StandardCharsets.UTF_16LE);
                int chars = utf16.length / 2;
                if (chars >= 0x8000) throw new IllegalArgumentException("字符串过长: " + s);
                Bin e = new Bin().u16(chars).bytes(utf16).u16(0);
                byte[] b = e.toBytes();
                strings.add(b);
                dataLen += b.length;
            }
            int pad = (4 - dataLen % 4) % 4;
            int headerSize = 28;
            int stringsStart = headerSize + 4 * strings.size();
            int total = stringsStart + dataLen + pad;
            Bin b = new Bin();
            b.u16(0x0001).u16(headerSize).u32(total);
            b.u32(strings.size()).u32(0).u32(0).u32(stringsStart).u32(0);
            int offset = 0;
            for (byte[] s : strings) { b.u32(offset); offset += s.length; }
            for (byte[] s : strings) b.bytes(s);
            b.zeros(pad);
            return b.toBytes();
        }
    }
}
