package com.chan.mtz2themepark;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 整个转换流程：读主题 → 匹配已安装应用 → 渲染缺失图标 → 组装并签名 APK。 */
final class Converter {
    private static final int MIN_SDK = 26;
    private static final int TARGET_SDK = 35;

    /** 系统应用和同品牌其他版本 -> 主题里最接近的图标。 */
    private static final Map<String, String> ALIASES = new HashMap<>();
    static {
        String[][] pairs = {
            {"com.samsung.android.app.contacts", "com.android.contacts"},
            {"com.samsung.android.dialer", "com.android.contacts.activities.DialtactsActivity"},
            {"com.google.android.apps.messaging", "com.android.mms"},
            {"com.samsung.android.messaging", "com.android.mms"},
            {"com.sec.android.app.camera", "com.android.camera"},
            {"com.sec.android.gallery3d", "com.miui.gallery"},
            {"com.sec.android.app.clockpackage", "com.android.deskclock"},
            {"com.sec.android.app.myfiles", "com.android.fileexplorer"},
            {"com.sec.android.app.music", "com.miui.player"},
            {"com.sec.android.app.popupcalculator", "com.miui.calculator"},
            {"com.sec.android.daemonapp", "com.miui.weather2"},
            {"com.sec.android.app.voicenote", "com.android.soundrecorder"},
            {"com.samsung.android.app.notes", "com.miui.notes"},
            {"com.samsung.android.app.reminder", "com.google.android.apps.tasks"},
            {"com.sec.android.app.samsungapps", "com.xiaomi.market"},
            {"com.samsung.android.themestore", "com.android.thememanager"},
            {"com.samsung.android.lool", "com.miui.securitycenter"},
            {"com.samsung.android.scloud", "com.miui.cloudservice"},
            {"com.samsung.android.app.tips", "ic_launcher_tips"},
            {"com.samsung.android.game.gamehome", "ic_launcher_gamespace"},
            {"com.samsung.android.bixby.agent", "com.miui.voiceassist"},
            {"com.samsung.android.spay", "ic_launcher_wallet"},
            {"com.samsung.android.tvplus", "com.miui.video"},
            {"com.samsung.android.app.find", "com.miui.findmy"},
            {"com.android.stk2", "com.android.stk"},
            {"com.starbucks.mobilecard", "com.starbucks.cn"},
            {"com.walmart.android", "cn.walmart.app"},
            {"com.mcdonalds.app", "com.mcdonalds.gma.cn"},
            {"com.ingka.ikea.app", "com.ingka.ikea.app.cn.prod"},
            {"com.didiglobal.passenger", "com.sdu.didi.psnger"},
            {"com.cainiao.cnintl4android", "com.cainiao.wireless"},
            {"dev.imranr.obtainium.fdroid", "dev.imranr.obtainium"},
            {"com.yuanwofei.cardemulator", "com.yuanwofei.cardemulator.pro"},
        };
        for (String[] p : pairs) ALIASES.put(p[0], p[1]);
    }

    /** 原生图标会显示实时日期，静态化反而出错，保持系统图标。 */
    private static final Set<String> KEEP_NATIVE = new HashSet<>(Arrays.asList("com.samsung.android.calendar"));

    interface Progress { void update(int percent, String message); }

    static final class Result {
        File apk; String title; String packageName;
        int total, themed, aliased, generated, kept, failed;
        final List<byte[]> previews = new ArrayList<>();
    }

    private final Context context;
    private final Progress progress;

    Converter(Context context, Progress progress) {
        this.context = context;
        this.progress = progress;
    }

    Result convert(File mtz, boolean generateMissing) throws Exception {
        File work = new File(context.getCacheDir(), "convert");
        deleteTree(work);
        if (!work.mkdirs()) throw new IOException("无法创建临时目录");
        Result result = new Result();
        progress.update(2, "正在读取主题…");
        try (ThemeArchive theme = new ThemeArchive(mtz, work)) {
            result.title = theme.title;
            result.packageName = "com.chan.mtz2themepark.pack.t"
                    + Integer.toHexString(theme.title.hashCode()).toLowerCase(Locale.ROOT);

            IconRenderer renderer = new IconRenderer(theme);
            PackageManager pm = context.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL);
            result.total = apps.size();

            Map<String, byte[]> drawables = new LinkedHashMap<>();
            Map<String, String> themeNames = new HashMap<>();
            Map<String, String> mapping = new LinkedHashMap<>();

            String iconName = theme.has("com.android.thememanager") ? "com.android.thememanager" : null;
            if (iconName != null) drawables.put("pack_icon", theme.read(iconName));

            int done = 0;
            for (ResolveInfo app : apps) {
                ActivityInfo a = app.activityInfo;
                done++;
                progress.update(5 + done * 75 / Math.max(apps.size(), 1), "处理应用 " + done + "/" + apps.size());
                if (KEEP_NATIVE.contains(a.packageName)) { result.kept++; continue; }

                String component = "ComponentInfo{" + a.packageName + "/" + a.name + "}";
                String source = pickThemeIcon(theme, a.packageName, a.name);
                if (source != null) {
                    String res = themeNames.get(source);
                    if (res == null) {
                        res = uniqueName(drawables, "ic_" + sanitize(source));
                        drawables.put(res, theme.read(source));
                        themeNames.put(source, res);
                    }
                    mapping.put(component, res);
                    if (ALIASES.containsKey(a.packageName) && source.equals(ALIASES.get(a.packageName))) result.aliased++;
                    else result.themed++;
                } else if (generateMissing) {
                    try {
                        byte[] png = renderer.renderPng(loadOriginalIcon(pm, app));
                        String res = uniqueName(drawables, "gen_" + sanitize(a.packageName));
                        drawables.put(res, png);
                        mapping.put(component, res);
                        result.generated++;
                        if (result.previews.size() < 24) result.previews.add(png);
                    } catch (Exception | OutOfMemoryError e) {
                        result.failed++;
                    }
                } else {
                    result.kept++;
                }
            }
            if (mapping.isEmpty()) throw new IOException("没有任何应用匹配到图标");
            if (drawables.isEmpty()) throw new IOException("没有可打包的图标");

            progress.update(85, "正在生成 APK…");
            File unsigned = new File(work, "unsigned.apk");
            buildApk(unsigned, result, drawables, mapping);

            progress.update(93, "正在签名…");
            File dir = outputDir(context);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建输出目录");
            File output = new File(dir, theme.title.replaceAll("[\\\\/:*?\"<>|\\s]+", "_") + "_图标包.apk");
            PackSigner.sign(unsigned, output, MIN_SDK);
            result.apk = output;
        } finally {
            deleteTree(work);
        }
        progress.update(100, "完成");
        return result;
    }

    static File outputDir(Context context) {
        File dir = context.getExternalFilesDir("packs");
        return dir != null ? dir : new File(context.getFilesDir(), "packs");
    }

    private static String pickThemeIcon(ThemeArchive theme, String pkg, String cls) {
        String exact = pkg + "#" + cls;
        if (theme.has(exact)) return exact;
        if (theme.has(cls)) return cls;
        if (theme.has(pkg)) return pkg;
        String viaFallback = theme.resolveFallback(cls);
        if (viaFallback == null) viaFallback = theme.resolveFallback(pkg);
        if (viaFallback != null) return viaFallback;
        String alias = ALIASES.get(pkg);
        return alias != null && theme.has(alias) ? alias : null;
    }

    /** 直接从应用自己的资源取图标，尽量绕开当前系统图标主题加的边框和替换。 */
    private static Drawable loadOriginalIcon(PackageManager pm, ResolveInfo app) throws Exception {
        ActivityInfo a = app.activityInfo;
        int id = a.getIconResource();
        if (id != 0) {
            try {
                Resources res = pm.getResourcesForApplication(a.applicationInfo);
                Drawable d = res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) return d;
            } catch (Resources.NotFoundException ignored) {
                // 落到下面系统接口的结果
            }
        }
        return app.loadIcon(pm);
    }

    private void buildApk(File out, Result result, Map<String, byte[]> drawables, Map<String, String> mapping)
            throws IOException {
        ApkZip zip = new ApkZip();
        Arsc arsc = new Arsc(result.packageName);
        int drawableType = arsc.addType("drawable", true);
        int xmlType = arsc.addType("xml", false);
        int stringType = arsc.addType("string", false);

        int iconRes = 0;
        for (Map.Entry<String, byte[]> e : drawables.entrySet()) {
            String path = "res/drawable-nodpi/" + e.getKey() + ".png";
            int id = arsc.addEntry(drawableType, e.getKey(), path);
            if (e.getKey().equals("pack_icon") || iconRes == 0) iconRes = id;
            zip.put(path, e.getValue());
        }

        byte[] appfilterBin = Axml.encode(appfilterNode(mapping), false);
        byte[] drawableBin = Axml.encode(drawableNode(drawables), false);
        arsc.addEntry(xmlType, "appfilter", "res/xml/appfilter.xml");
        arsc.addEntry(xmlType, "drawable", "res/xml/drawable.xml");
        zip.put("res/xml/appfilter.xml", appfilterBin);
        zip.put("res/xml/drawable.xml", drawableBin);
        zip.put("assets/appfilter.xml", appfilterText(mapping));
        zip.put("assets/drawable.xml", drawableText(drawables));

        int labelRes = arsc.addEntry(stringType, "app_name", result.title + " 图标包");
        zip.put("AndroidManifest.xml", Axml.encode(manifest(result.packageName, labelRes, iconRes), true));
        zip.put("resources.arsc", arsc.encode());
        zip.put("classes.dex", readAsset("pack_classes.dex"));
        zip.write(out);
    }

    private static Axml.Node manifest(String pkg, int labelRes, int iconRes) {
        int version = (int) (System.currentTimeMillis() / 60000L);
        Axml.Node manifest = new Axml.Node("manifest",
                Axml.Attr.integer("versionCode", android.R.attr.versionCode, version),
                Axml.Attr.str("versionName", android.R.attr.versionName, "1.0"),
                Axml.Attr.plain("package", pkg));
        manifest.add(new Axml.Node("uses-sdk",
                Axml.Attr.integer("minSdkVersion", android.R.attr.minSdkVersion, MIN_SDK),
                Axml.Attr.integer("targetSdkVersion", android.R.attr.targetSdkVersion, TARGET_SDK)));
        Axml.Node activity = new Axml.Node("activity",
                Axml.Attr.str("name", android.R.attr.name, "com.chan.mtz2themepark.pack.PackActivity"),
                Axml.Attr.bool("exported", android.R.attr.exported, true),
                Axml.Attr.ref("theme", android.R.attr.theme, android.R.style.Theme_NoDisplay));
        for (String category : new String[]{"com.teslacoilsw.launcher.THEME", "com.anddoes.launcher.THEME",
                "com.fede.launcher.THEME_ICONPACK"}) {
            activity.add(filter("android.intent.action.MAIN", category));
        }
        for (String action : new String[]{"org.adw.launcher.THEMES", "com.gau.go.launcherex.theme"}) {
            activity.add(filter(action, "android.intent.category.DEFAULT"));
        }
        Axml.Node application = new Axml.Node("application",
                Axml.Attr.ref("label", android.R.attr.label, labelRes),
                Axml.Attr.ref("icon", android.R.attr.icon, iconRes),
                Axml.Attr.bool("allowBackup", android.R.attr.allowBackup, false));
        application.add(activity);
        manifest.add(application);
        return manifest;
    }

    private static Axml.Node filter(String action, String category) {
        return new Axml.Node("intent-filter")
                .add(new Axml.Node("action", Axml.Attr.str("name", android.R.attr.name, action)))
                .add(new Axml.Node("category", Axml.Attr.str("name", android.R.attr.name, category)));
    }

    private static Axml.Node appfilterNode(Map<String, String> mapping) {
        Axml.Node root = new Axml.Node("resources");
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            root.add(new Axml.Node("item",
                    Axml.Attr.plain("component", e.getKey()),
                    Axml.Attr.plain("drawable", e.getValue())));
        }
        return root;
    }

    private static Axml.Node drawableNode(Map<String, byte[]> drawables) {
        Axml.Node root = new Axml.Node("resources");
        root.add(new Axml.Node("category", Axml.Attr.plain("title", "All")));
        for (String name : drawables.keySet()) {
            if (name.equals("pack_icon")) continue;
            root.add(new Axml.Node("item", Axml.Attr.plain("drawable", name)));
        }
        return root;
    }

    private static byte[] appfilterText(Map<String, String> mapping) {
        StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n  <version>1</version>\n");
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            s.append("  <item component=\"").append(escape(e.getKey())).append("\" drawable=\"").append(e.getValue()).append("\"/>\n");
        }
        return s.append("</resources>\n").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] drawableText(Map<String, byte[]> drawables) {
        StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n  <version>1</version>\n  <category title=\"All\"/>\n");
        for (String name : drawables.keySet()) {
            if (!name.equals("pack_icon")) s.append("  <item drawable=\"").append(name).append("\"/>\n");
        }
        return s.append("</resources>\n").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    private static String sanitize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private static String uniqueName(Map<String, byte[]> taken, String base) {
        String name = base;
        for (int i = 2; taken.containsKey(name); i++) name = base + "_" + i;
        return name;
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ThemeArchive.copy(in, out);
            return out.toByteArray();
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }
}
