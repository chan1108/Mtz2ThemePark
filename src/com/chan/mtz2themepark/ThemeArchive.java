package com.chan.mtz2themepark;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 读取 mtz：外层是 zip，其中的 icons 条目又是一个 zip，图标在 res/drawable-* 下。 */
final class ThemeArchive implements Closeable {
    private static final Pattern POINT = Pattern.compile("fromX=\"([-0-9.]+)\"[^>]*toX=\"([-0-9.]+)\"");
    private static final Pattern FALLBACK = Pattern.compile("<drawable name=\"([^\"]+)\\.png\">([^<]+)\\.png</drawable>");
    private static final Pattern TITLE = Pattern.compile("<title>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</title>", Pattern.DOTALL);
    private static final String[] DENSITIES = {"xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi", "nodpi"};

    final String title;
    final float iconScale;
    private final ZipFile icons;
    private final Map<String, ZipEntry> index = new HashMap<>();
    private final Map<String, String> fallback = new HashMap<>();

    ThemeArchive(File mtz, File workDir) throws IOException {
        File iconsFile = new File(workDir, "icons.zip");
        String description;
        try (ZipFile outer = new ZipFile(mtz)) {
            ZipEntry iconsEntry = outer.getEntry("icons");
            if (iconsEntry == null) throw new IOException("这个 mtz 里没有 icons，无法转换");
            try (InputStream in = outer.getInputStream(iconsEntry); OutputStream out = new FileOutputStream(iconsFile)) {
                copy(in, out);
            }
            ZipEntry desc = outer.getEntry("description.xml");
            description = desc == null ? "" : text(outer, desc);
        }
        Matcher t = TITLE.matcher(description);
        title = t.find() && !t.group(1).trim().isEmpty() ? t.group(1).trim() : "主题";

        icons = new ZipFile(iconsFile);
        Map<String, Integer> ranks = new HashMap<>();
        Enumeration<? extends ZipEntry> all = icons.entries();
        while (all.hasMoreElements()) {
            ZipEntry e = all.nextElement();
            String n = e.getName();
            if (!n.startsWith("res/drawable") || !n.endsWith(".png") || n.indexOf('/', 4) < 0) continue;
            int slash = n.lastIndexOf('/');
            String name = n.substring(slash + 1, n.length() - 4);
            int rank = rank(n.substring(0, slash));
            Integer old = ranks.get(name);
            if (old == null || rank < old) { ranks.put(name, rank); index.put(name, e); }
        }
        if (index.isEmpty()) throw new IOException("这个 mtz 里没有找到静态图标");

        ZipEntry fb = icons.getEntry("theme_fallback.xml");
        if (fb != null) {
            Matcher m = FALLBACK.matcher(text(icons, fb));
            while (m.find()) fallback.put(m.group(1), m.group(2));
        }
        iconScale = readScale();
    }

    int iconCount() { return index.size(); }

    boolean has(String name) { return index.containsKey(name); }

    /** 按 theme_fallback.xml 解析别名；没有则返回 null。 */
    String resolveFallback(String name) {
        String target = fallback.get(name);
        return target != null && has(target) ? target : null;
    }

    byte[] read(String name) throws IOException {
        ZipEntry e = index.get(name);
        if (e == null) throw new IOException("缺少图标: " + name);
        try (InputStream in = icons.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toByteArray();
        }
    }

    /** 读取 icon_mask / icon_pattern / icon_border，没有时返回 null。 */
    Bitmap readBitmap(String name) throws IOException {
        if (!has(name)) return null;
        byte[] data = read(name);
        return BitmapFactory.decodeByteArray(data, 0, data.length);
    }

    /** transform_config.xml 把 0..90 映射到 -3..92，即图标整体放大约 5.6%。 */
    private float readScale() throws IOException {
        ZipEntry cfg = icons.getEntry("transform_config.xml");
        if (cfg == null) return 1f;
        Matcher m = POINT.matcher(text(icons, cfg));
        float fromMin = Float.MAX_VALUE, fromMax = -Float.MAX_VALUE, toMin = Float.MAX_VALUE, toMax = -Float.MAX_VALUE;
        while (m.find()) {
            float f = Float.parseFloat(m.group(1)), to = Float.parseFloat(m.group(2));
            fromMin = Math.min(fromMin, f); fromMax = Math.max(fromMax, f);
            toMin = Math.min(toMin, to); toMax = Math.max(toMax, to);
        }
        if (fromMax <= fromMin || toMax <= toMin) return 1f;
        float scale = (toMax - toMin) / (fromMax - fromMin);
        return scale >= 0.5f && scale <= 2f ? scale : 1f;
    }

    private static int rank(String dir) {
        for (int i = 0; i < DENSITIES.length; i++) if (dir.contains(DENSITIES[i])) return i;
        return DENSITIES.length;
    }

    private static String text(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    @Override
    public void close() throws IOException { icons.close(); }
}
