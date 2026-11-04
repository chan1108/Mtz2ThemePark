package com.chan.mtz2themepark;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** 把生成的 APK 以只读方式临时授权给系统安装器。不导出，只能通过 URI 授权访问。 */
public class ApkProvider extends ContentProvider {
    private static final String AUTHORITY = "com.chan.mtz2themepark.apk";
    static final String MIME_APK = "application/vnd.android.package-archive";

    static Uri uriFor(File apk) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(apk.getName()).build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        String name = uri.getLastPathSegment();
        if (context == null || name == null) throw new FileNotFoundException(String.valueOf(uri));
        try {
            File dir = Converter.outputDir(context).getCanonicalFile();
            File file = new File(dir, name).getCanonicalFile();
            if (!dir.equals(file.getParentFile()) || !file.isFile()) {
                throw new FileNotFoundException(String.valueOf(uri));
            }
            return file;
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("只读");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(row);
        return cursor;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return MIME_APK; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
