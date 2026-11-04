package com.chan.mtz2themepark;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** 写未压缩且 4 字节对齐的 ZIP。系统要求 resources.arsc 未压缩且对齐，PNG 本身也无需再压缩。 */
final class ApkZip {
    private static final int FIXED_DOS_TIME = 0;
    private static final int FIXED_DOS_DATE = (1 << 5) | 1 | ((2008 - 1980) << 9);

    private final Map<String, byte[]> entries = new LinkedHashMap<>();

    void put(String path, byte[] data) { entries.put(path, data); }

    void write(File file) throws IOException {
        Bin central = new Bin();
        long offset = 0;
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] data = e.getValue();
                CRC32 crc = new CRC32();
                crc.update(data);
                int crcValue = (int) crc.getValue();

                int headerEnd = (int) (offset % 4) + 30 + name.length;
                int pad = (4 - headerEnd % 4) % 4;
                Bin local = new Bin();
                local.u32(0x04034b50).u16(10).u16(0x0800).u16(0).u16(FIXED_DOS_TIME).u16(FIXED_DOS_DATE);
                local.u32(crcValue).u32(data.length).u32(data.length).u16(name.length).u16(pad);
                local.bytes(name).zeros(pad);
                out.write(local.toBytes());
                out.write(data);

                central.u32(0x02014b50).u16(10).u16(10).u16(0x0800).u16(0).u16(FIXED_DOS_TIME).u16(FIXED_DOS_DATE);
                central.u32(crcValue).u32(data.length).u32(data.length).u16(name.length).u16(0).u16(0);
                central.u16(0).u16(0).u32(0).u32((int) offset).bytes(name);

                offset += local.size() + data.length;
            }
            out.write(central.toBytes());
            Bin end = new Bin();
            end.u32(0x06054b50).u16(0).u16(0).u16(entries.size()).u16(entries.size());
            end.u32(central.size()).u32((int) offset).u16(0);
            out.write(end.toBytes());
        }
    }
}
