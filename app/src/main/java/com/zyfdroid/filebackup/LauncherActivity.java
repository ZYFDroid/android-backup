package com.zyfdroid.filebackup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.view.View;

import com.zyfdroid.backupfileclient.MainActivity;


public class LauncherActivity extends Activity {

    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE" };


    public void verifyStoragePermissions() {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M){return;}
        try {
            //检测是否有写的权限
            int permission = checkSelfPermission(
                    "android.permission.WRITE_EXTERNAL_STORAGE");
            if (permission != PackageManager.PERMISSION_GRANTED) {
                // 没有写的权限，去申请写的权限，会弹出对话框
                requestPermissions( PERMISSIONS_STORAGE,REQUEST_EXTERNAL_STORAGE);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        verifyStoragePermissions();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectCleartextNetwork().penaltyLog().build());
        }
    }

    public void startServer(View view) {
        startActivity(new Intent(this, com.zyfdroid.backupfileserver.MainActivity.class));
    }

    public void startClient(View view) {
        startActivity(new Intent(this, com.zyfdroid.backupfileclient.MainActivity.class));
    }

    public void showTips(View view) {
        new AlertDialog.Builder(this).setTitle("使用说明").setMessage(MainActivity.readAssetsTxt(this,"tips.txt")).setPositiveButton("好",null).create().show();
    }
}
