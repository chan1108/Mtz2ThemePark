package com.chan.mtz2themepark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 生成 Android 二进制 XML（AXML），用于清单和 res/xml 文件。 */
final class Axml {
    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    static final int TYPE_REFERENCE = 0x01, TYPE_STRING = 0x03, TYPE_INT_DEC = 0x10, TYPE_BOOL = 0x12;

    /** 一个属性：resId 为 0 表示普通属性（无命名空间）。 */
    static final class Attr {
        final String name; final int resId; final int type; final int data; final String str;
        private Attr(String name, int resId, int type, int data, String str) {
            this.name = name; this.resId = resId; this.type = type; this.data = data; this.str = str;
        }
        static Attr str(String name, int resId, String value) { return new Attr(name, resId, TYPE_STRING, 0, value); }
        static Attr plain(String name, String value) { return str(name, 0, value); }
        static Attr ref(String name, int resId, int target) { return new Attr(name, resId, TYPE_REFERENCE, target, null); }
        static Attr integer(String name, int resId, int value) { return new Attr(name, resId, TYPE_INT_DEC, value, null); }
        static Attr bool(String name, int resId, boolean v) { return new Attr(name, resId, TYPE_BOOL, v ? -1 : 0, null); }
    }

    static final class Node {
        final String name; final List<Attr> attrs = new ArrayList<>(); final List<Node> children = new ArrayList<>();
        Node(String name, Attr... list) { this.name = name; for (Attr a : list) attrs.add(a); }
        Node add(Node c) { children.add(c); return this; }
    }

    /** 编码。useAndroidNs 为 true 时声明 android 命名空间。 */
    static byte[] encode(Node root, boolean useAndroidNs) {
        // 带资源 ID 的属性名必须排在字符串池最前面，与资源映射表一一对应。
        Map<String, Integer> attrIds = new LinkedHashMap<>();
        collectAttrIds(root, attrIds);
        Bin.Pool pool = new Bin.Pool();
        Bin resMap = new Bin();
        for (Map.Entry<String, Integer> e : attrIds.entrySet()) { pool.add(e.getKey()); resMap.u32(e.getValue()); }
        int nsIdx = -1, prefixIdx = -1;
        if (useAndroidNs) { prefixIdx = pool.add("android"); nsIdx = pool.add(ANDROID_NS); }
        collectStrings(root, pool);

        Bin body = new Bin();
        if (useAndroidNs) nsChunk(body, 0x0100, prefixIdx, nsIdx);
        writeNode(body, root, pool, nsIdx);
        if (useAndroidNs) nsChunk(body, 0x0101, prefixIdx, nsIdx);

        byte[] poolBytes = pool.encode();
        byte[] map = resMap.toBytes();
        Bin file = new Bin();
        int mapChunk = map.length == 0 ? 0 : 8 + map.length;
        file.u16(0x0003).u16(8).u32(8 + poolBytes.length + mapChunk + body.size());
        file.bytes(poolBytes);
        if (mapChunk > 0) file.u16(0x0180).u16(8).u32(mapChunk).bytes(map);
        file.bytes(body.toBytes());
        return file.toBytes();
    }

    private static void collectAttrIds(Node n, Map<String, Integer> ids) {
        for (Attr a : n.attrs) if (a.resId != 0) ids.put(a.name, a.resId);
        for (Node c : n.children) collectAttrIds(c, ids);
    }

    private static void collectStrings(Node n, Bin.Pool pool) {
        pool.add(n.name);
        for (Attr a : n.attrs) { pool.add(a.name); if (a.str != null) pool.add(a.str); }
        for (Node c : n.children) collectStrings(c, pool);
    }

    private static void nsChunk(Bin b, int type, int prefix, int uri) {
        b.u16(type).u16(16).u32(24).u32(1).u32(-1).u32(prefix).u32(uri);
    }

    private static void writeNode(Bin b, Node n, Bin.Pool pool, int nsIdx) {
        List<Attr> attrs = new ArrayList<>(n.attrs);
        // 系统按资源 ID 升序查找；无资源 ID 的属性放在最后。
        attrs.sort(Comparator.comparingLong(a -> a.resId == 0 ? Long.MAX_VALUE : (long) a.resId));
        b.u16(0x0102).u16(16).u32(16 + 20 + 20 * attrs.size()).u32(1).u32(-1);
        b.u32(-1).u32(pool.add(n.name));
        b.u16(20).u16(20).u16(attrs.size()).u16(0).u16(0).u16(0);
        for (Attr a : attrs) {
            b.u32(a.resId != 0 ? nsIdx : -1).u32(pool.add(a.name));
            int strIdx = a.str != null ? pool.add(a.str) : -1;
            b.u32(strIdx).u16(8).u8(0).u8(a.type).u32(a.str != null ? strIdx : a.data);
        }
        for (Node c : n.children) writeNode(b, c, pool, nsIdx);
        b.u16(0x0103).u16(16).u32(24).u32(1).u32(-1).u32(-1).u32(pool.add(n.name));
    }

    private Axml() {}
}
