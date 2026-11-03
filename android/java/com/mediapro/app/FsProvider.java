package com.mediapro.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

public class FsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.mediapro.app.fs";

    @Override
    public boolean onCreate() {
        return true;
    }

    public static Uri uriFor(String filePath) {
        if (filePath == null) return null;
        return Uri.parse("content://" + AUTHORITY + "/open/" + Uri.encode(filePath));
    }

    public static File fileFor(Uri uri) {
        if (uri == null) return null;
        String path = uri.getPath();
        if (path != null && path.startsWith("/open/")) {
            path = path.substring(6);
            return new File(Uri.decode(path));
        }
        return null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = fileFor(uri);
        if (file == null || !file.exists()) {
            throw new FileNotFoundException("File not found: " + uri);
        }
        int pfdMode = ParcelFileDescriptor.MODE_READ_ONLY;
        if ("w".equalsIgnoreCase(mode) || "wt".equalsIgnoreCase(mode)) {
            pfdMode = ParcelFileDescriptor.MODE_WRITE_ONLY;
        } else if ("rw".equalsIgnoreCase(mode) || "rwt".equalsIgnoreCase(mode)) {
            pfdMode = ParcelFileDescriptor.MODE_READ_WRITE;
        }
        return ParcelFileDescriptor.open(file, pfdMode);
    }

    @Override
    public String getType(Uri uri) {
        File file = fileFor(uri);
        if (file != null) {
            String name = file.getName();
            if (name.toLowerCase().endsWith(".apk")) {
                return "application/vnd.android.package-archive";
            }
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                String ext = name.substring(dot + 1).toLowerCase();
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime != null) return mime;
            }
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = fileFor(uri);
        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }
        MatrixCursor cursor = new MatrixCursor(projection, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : projection) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add(file != null ? file.getName() : "file");
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(file != null ? file.length() : 0);
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
