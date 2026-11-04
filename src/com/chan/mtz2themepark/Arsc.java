package com.chan.mtz2themepark;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 生成 resources.arsc。每个类型只有一个配置，所有条目都是字符串值（文件路径或文本）。 */
final class Arsc {
    private static final int PACKAGE_ID = 0x7f;
    private static final int CONFIG_SIZE = 64;
    private static final int DENSITY_NODPI = 0xFFFF;

    private static final class Entry {
        final String key, value;
        Entry(String key, String value) { this.key = key; this.value = value; }
    }

    private static final class Type {
        final String name; final int density; final List<Entry> entries = new ArrayList<>();
        Type(String name, int density) { this.name = name; this.density = density; }
    }

    private final List<Type> types = new ArrayList<>();
    private final String packageName;

    Arsc(String packageName) { this.packageName = packageName; }

    /** 新建类型，返回类型 ID（从 1 开始）。 */
    int addType(String name, boolean nodpi) {
        types.add(new Type(name, nodpi ? DENSITY_NODPI : 0));
        return types.size();
    }

    /** 添加条目，返回完整资源 ID。 */
    int addEntry(int typeId, String key, String value) {
        Type t = types.get(typeId - 1);
        t.entries.add(new Entry(key, value));
        return (PACKAGE_ID << 24) | (typeId << 16) | (t.entries.size() - 1);
    }

    byte[] encode() {
        Bin.Pool values = new Bin.Pool(), typeNames = new Bin.Pool(), keys = new Bin.Pool();
        for (Type t : types) {
            typeNames.add(t.name);
            for (Entry e : t.entries) { values.add(e.value); keys.add(e.key); }
        }
        byte[] typePool = typeNames.encode(), keyPool = keys.encode();

        Bin typeChunks = new Bin();
        for (int i = 0; i < types.size(); i++) writeType(typeChunks, i + 1, types.get(i), values, keys);

        int pkgHeader = 288;
        int pkgSize = pkgHeader + typePool.length + keyPool.length + typeChunks.size();
        Bin pkg = new Bin();
        pkg.u16(0x0200).u16(pkgHeader).u32(pkgSize).u32(PACKAGE_ID);
        byte[] name = packageName.getBytes(StandardCharsets.UTF_16LE);
        if (name.length >= 256) throw new IllegalArgumentException("包名过长");
        pkg.bytes(name).zeros(256 - name.length);
        pkg.u32(pkgHeader).u32(types.size());
        pkg.u32(pkgHeader + typePool.length).u32(keys.size());
        pkg.u32(0);
        pkg.bytes(typePool).bytes(keyPool).bytes(typeChunks.toBytes());

        byte[] valuePool = values.encode();
        Bin table = new Bin();
        table.u16(0x0002).u16(12).u32(12 + valuePool.length + pkg.size()).u32(1);
        table.bytes(valuePool).bytes(pkg.toBytes());
        return table.toBytes();
    }

    private static void writeType(Bin b, int id, Type t, Bin.Pool values, Bin.Pool keys) {
        int count = t.entries.size();
        b.u16(0x0202).u16(16).u32(16 + 4 * count).u8(id).u8(0).u16(0).u32(count);
        for (int i = 0; i < count; i++) b.u32(0);

        int entriesStart = 20 + CONFIG_SIZE + 4 * count;
        int entrySize = 16;
        b.u16(0x0201).u16(20 + CONFIG_SIZE).u32(entriesStart + entrySize * count);
        b.u8(id).u8(0).u16(0).u32(count).u32(entriesStart);
        b.u32(CONFIG_SIZE).zeros(10).u16(t.density).zeros(CONFIG_SIZE - 16);
        for (int i = 0; i < count; i++) b.u32(i * entrySize);
        for (Entry e : t.entries) {
            b.u16(8).u16(0).u32(keys.add(e.key));
            b.u16(8).u8(0).u8(0x03).u32(values.add(e.value));
        }
    }
}
