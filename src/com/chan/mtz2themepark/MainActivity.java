package com.chan.mtz2themepark;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 主界面：选 mtz → 转换 → 安装或另存 APK。 */
public class MainActivity extends Activity {
    private static final int REQUEST_PICK = 1;
    private static final int REQUEST_SAVE = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Button pickButton, convertButton, installButton, saveButton;
    private CheckBox generateBox;
    private ProgressBar progressBar;
    private TextView fileText, statusText;
    private GridLayout previewGrid;
    private Uri selected;
    private Converter.Result lastResult;
    /** 等待系统安装器装好的版本号；-1 表示没有待确认的安装。 */
    private long pendingVersion = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reportIfInstalled();
    }

    /** 系统安装器不返回结果，所以回到应用时检查图标包版本来判断是否装好。 */
    private void reportIfInstalled() {
        if (pendingVersion < 0 || lastResult == null) return;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(lastResult.packageName, 0);
            if (info.getLongVersionCode() >= pendingVersion) {
                pendingVersion = -1;
                statusText.append("\n\n安装成功。打开 Good Lock → Theme Park → 图标，选择这个图标包，保存并应用。");
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // 用户取消了安装，可以再点一次按钮
        }
    }

    private void buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(56), pad, pad);

        TextView title = new TextView(this);
        title.setText("MTZ 转主题公园");
        title.setTextSize(28);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("选择小米主题 mtz，生成三星主题公园（Theme Park）专用的图标包。主题没有适配的应用，会用主题的蒙版生成同风格图标。");
        hint.setPadding(0, dp(8), 0, dp(16));
        root.addView(hint);

        pickButton = new Button(this);
        pickButton.setText("选择 mtz 文件");
        pickButton.setOnClickListener(v -> pick());
        root.addView(pickButton);

        fileText = new TextView(this);
        fileText.setText("尚未选择文件");
        fileText.setPadding(0, dp(8), 0, dp(8));
        root.addView(fileText);

        generateBox = new CheckBox(this);
        generateBox.setText("给未适配的应用生成蒙版图标");
        generateBox.setChecked(true);
        root.addView(generateBox);

        convertButton = new Button(this);
        convertButton.setText("开始转换");
        convertButton.setEnabled(false);
        convertButton.setOnClickListener(v -> convert());
        root.addView(convertButton);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        statusText = new TextView(this);
        statusText.setPadding(0, dp(12), 0, dp(12));
        statusText.setTextIsSelectable(true);
        root.addView(statusText);

        previewGrid = new GridLayout(this);
        previewGrid.setColumnCount(6);
        root.addView(previewGrid);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, dp(12), 0, 0);
        installButton = new Button(this);
        installButton.setText("安装到手机");
        installButton.setVisibility(View.GONE);
        installButton.setOnClickListener(v -> install());
        actions.addView(installButton, new LinearLayout.LayoutParams(0, -2, 1f));
        saveButton = new Button(this);
        saveButton.setText("另存 APK");
        saveButton.setVisibility(View.GONE);
        saveButton.setOnClickListener(v -> save());
        actions.addView(saveButton, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(actions);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(intent, REQUEST_PICK);
    }

    private void convert() {
        final Uri uri = selected;
        final boolean generate = generateBox.isChecked();
        setBusy(true);
        lastResult = null;
        previewGrid.removeAllViews();
        installButton.setVisibility(View.GONE);
        saveButton.setVisibility(View.GONE);
        statusText.setText("");
        new Thread(() -> {
            File copy = new File(getCacheDir(), "source.mtz");
            try {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(copy)) {
                    if (in == null) throw new java.io.IOException("无法读取所选文件");
                    ThemeArchive.copy(in, out);
                }
                Converter converter = new Converter(this, (percent, message) -> main.post(() -> {
                    progressBar.setProgress(percent);
                    statusText.setText(message);
                }));
                Converter.Result result = converter.convert(copy, generate);
                main.post(() -> showResult(result));
            } catch (Throwable t) {
                String reason = t.getMessage() == null ? t.toString() : t.getMessage();
                main.post(() -> {
                    statusText.setText("转换失败：" + reason);
                    setBusy(false);
                });
            } finally {
                copy.delete();
            }
        }, "convert").start();
    }

    private void showResult(Converter.Result r) {
        lastResult = r;
        setBusy(false);
        StringBuilder s = new StringBuilder();
        s.append("主题：").append(r.title);
        s.append("\n启动项：").append(r.total);
        s.append("\n主题自带图标：").append(r.themed);
        s.append("\n映射到同类图标：").append(r.aliased);
        s.append("\n蒙版生成：").append(r.generated);
        s.append("\n保持原样：").append(r.kept);
        if (r.failed > 0) s.append("\n生成失败：").append(r.failed);
        s.append("\nAPK：").append(r.apk.length() / 1024 / 1024).append(" MB");
        statusText.setText(s);
        int size = dp(48), margin = dp(4);
        for (byte[] png : r.previews) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(BitmapFactory.decodeByteArray(png, 0, png.length));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size; lp.height = size;
            lp.setMargins(margin, margin, margin, margin);
            previewGrid.addView(image, lp);
        }
        installButton.setVisibility(View.VISIBLE);
        saveButton.setVisibility(View.VISIBLE);
    }

    private void install() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            statusText.append("\n\n请先允许本应用安装未知应用，返回后再点一次“安装到手机”。");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        File apk = lastResult.apk;
        PackageInfo archive = getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
        pendingVersion = archive != null ? archive.getLongVersionCode() : -1;
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(ApkProvider.uriFor(apk), ApkProvider.MIME_APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            pendingVersion = -1;
            statusText.append("\n\n没有找到系统安装器，请用“另存 APK”后手动安装。");
        }
    }

    private void save() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_TITLE, lastResult.apk.getName());
        startActivityForResult(intent, REQUEST_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_PICK) {
            selected = data.getData();
            fileText.setText(displayName(selected));
            convertButton.setEnabled(true);
        } else if (requestCode == REQUEST_SAVE) {
            final Uri target = data.getData();
            final File apk = lastResult.apk;
            new Thread(() -> {
                String message;
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) throw new java.io.IOException("无法写入");
                    ThemeArchive.copy(in, out);
                    message = "\n\nAPK 已保存。";
                } catch (Exception e) {
                    message = "\n\n保存失败：" + e.getMessage();
                }
                final String text = message;
                main.post(() -> statusText.append(text));
            }, "save").start();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // 部分文件提供方不支持查询，直接显示 URI
        }
        return uri.toString();
    }

    private void setBusy(boolean busy) {
        pickButton.setEnabled(!busy);
        convertButton.setEnabled(!busy && selected != null);
        generateBox.setEnabled(!busy);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) progressBar.setProgress(0);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
