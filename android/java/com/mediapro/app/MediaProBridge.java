package com.mediapro.app;

import android.os.Environment;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

public class MediaProBridge {
    private final MainActivity activity;

    public MediaProBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void saveFile(String name, String base64Data) {
        try {
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File target = new File(dir, name != null ? name : "download");
            FileOutputStream fos = new FileOutputStream(target);
            fos.write(bytes);
            fos.flush();
            fos.close();
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, "تم الحفظ في التنزيلات: " + target.getName(), Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            final String err = e.getMessage();
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, "خطأ في الحفظ: " + err, Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}
