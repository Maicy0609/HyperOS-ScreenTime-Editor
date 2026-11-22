package com.screentime.editor;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

public class MainActivity extends Activity {
    private static final int REQ_STORAGE = 100;
    private static final int REQ_MANAGE = 101;
    private static final String ASSET = "edit_today_screentime.sh.gz";
    private static final String FILE_NAME = "edit_today_screentime.sh";

    private TextView status;
    private boolean askedManage;
    private boolean extracting;
    private boolean extracted;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP);
        root.setPadding(48, 48, 48, 48);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(Color.WHITE);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(48, 48 + insets.getSystemWindowInsetTop(), 48, 48);
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("ScreenTime Editor");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("\n启动后自动释放脚本到：\n/sdcard/Download/edit_today_screentime.sh\n\n"
                + "模块作用域：\ncom.xiaomi.misettings\ncom.miui.securitycenter");
        desc.setTextSize(16);
        desc.setTextColor(Color.DKGRAY);
        root.addView(desc);

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(Color.BLACK);
        status.setPadding(0, 28, 0, 28);
        root.addView(status);

        Button grant = new Button(this);
        grant.setText("打开所有文件访问权限设置");
        grant.setOnClickListener(v -> openManagePermission());
        root.addView(grant);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            extractAsync();
        } else if (Build.VERSION.SDK_INT < 30
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            extractAsync();
        } else {
            status.setText("需要“所有文件访问”权限才能写入 Download。");
            if (Build.VERSION.SDK_INT < 30) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (extracted || extracting) return;
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            extractAsync();
        } else if (Build.VERSION.SDK_INT < 30
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            extractAsync();
        } else if (Build.VERSION.SDK_INT >= 30 && !askedManage) {
            askedManage = true;
            openManagePermission();
        }
    }

    private void openManagePermission() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_MANAGE);
        } catch (Exception e) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_MANAGE);
            } catch (Exception ignored) {
                status.setText("无法打开权限设置，请手动授予“所有文件访问”权限。");
            }
        }
    }

    private void extractAsync() {
        if (extracting) return;
        extracting = true;
        status.setText("正在释放脚本…");
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建 Download 目录");
                File out = new File(dir, FILE_NAME);
                if (out.exists() && !out.delete()) throw new Exception("无法删除旧脚本");
                try (InputStream raw = getAssets().open(ASSET);
                     GZIPInputStream in = new GZIPInputStream(raw);
                     FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                    fo.getFD().sync();
                }
                out.setReadable(true, false);
                msg = "已释放：\n" + out.getAbsolutePath();
                ok = true;
            } catch (Throwable t) {
                msg = "释放失败：" + t;
            }
            final String finalMsg = msg;
            final boolean finalOk = ok;
            runOnUiThread(() -> {
                extracting = false;
                extracted = finalOk;
                status.setText(finalMsg);
            });
        }, "screentime-extract").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            extractAsync();
        }
    }
}
