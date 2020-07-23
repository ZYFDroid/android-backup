package com.zyfdroid.backupfileclient;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.zyfdroid.filebackup.R;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class MainActivity extends Activity {

    public void setViewText(int id,String s){((TextView)findViewById(id)).setText(s);}
    public String getViewText(int id){return ((TextView)findViewById(id)).getText().toString();}
    public void save(String key,String value){getSharedPreferences("0",0).edit().putString(key,value).commit();}
    public String load(String key,String def){return getSharedPreferences("0",0).getString(key,def);}


    public static String readAssetsTxt(Context context, String fileName){
        try {
            InputStream is = context.getAssets().open(fileName);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String text = new String(buffer, "utf-8");
            return text;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "Error!";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setViewText(R.id.txtAllowBlockList,load("rule",readAssetsTxt(this,"default_rule.txt")));
        setViewText(R.id.txtIp,load("ip","192.168.8.159"));

    }

    @Override
    protected void onDestroy() {
        save("rule",getViewText(R.id.txtAllowBlockList));
        save("ip",getViewText(R.id.txtIp));
        super.onDestroy();
    }

    public void onPreview(View view) {
        final ProgressDialog pdd = ProgressDialog.show(this,"获取文件列表...","正在连接到服务器",true,false);
        final FileFilter ff = new FileFilter(getViewText(R.id.txtAllowBlockList));
        ff.setAmountCallback(new FileFilter.Callback<Integer>() {
            @Override
            public void onCallback(Integer cbk) {
                int i = cbk.intValue();
                if(i==-1){
                    Toast.makeText(MainActivity.this, "无法连接到服务器", Toast.LENGTH_SHORT).show();
                    pdd.dismiss();
                    return;
                }
                if(i==-2){
                    Toast.makeText(MainActivity.this, "服务器没有发送完整的数据。", Toast.LENGTH_SHORT).show();
                    pdd.dismiss();
                    return;
                }
                pdd.setMessage("已获取"+cbk+"个文件");
            }
        });
        ff.setListCallback(new FileFilter.Callback<List<FileEntry>>() {
            @Override
            public void onCallback(List<FileEntry> cbk) {
                pdd.dismiss();
                ListView lv = new ListView(MainActivity.this);
                lv.setFastScrollAlwaysVisible(true);
                lv.setFastScrollEnabled(true);
                lv.setAdapter(new ArrayAdapter<FileEntry>(MainActivity.this,R.layout.adapter_list,R.id.txtText,cbk));
                lv.setDividerHeight(0);
                AlertDialog ald = new AlertDialog.Builder(MainActivity.this).setTitle("文件总数: "+cbk.size()).setView(lv).setPositiveButton("下一步", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        PerformActivity.passedbyItems = ff.getResult();
                        Intent intent = new Intent(MainActivity.this,PerformActivity.class);
                        intent.putExtra("ip",getViewText(R.id.txtIp));
                        startActivity(intent);
                        finish();
                    }
                }).setNegativeButton("取消",null).create();
                ald.show();
            }
        });
        ff.filterFile(getViewText(R.id.txtIp));
    }

    public void onResetConf(View view) {
        new AlertDialog.Builder(this).setTitle("恢复默认配置文件?").setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                setViewText(R.id.txtAllowBlockList,readAssetsTxt(MainActivity.this,"default_rule.txt"));
            }
        }).setNegativeButton(android.R.string.cancel,null).create().show();
    }
}
