package com.zyfdroid.backupfileclient;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.zyfdroid.filebackup.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PerformActivity extends Activity {
    public static List<FileEntry> passedbyItems = null;

    private List<FileEntry> getPassedbyItems(){
        List<FileEntry> items = passedbyItems;
        passedbyItems = null;
        return items;
    }
    PowerManager.WakeLock pwl;
    Handler hWnd = new Handler();
    ConcurrentLinkedQueue<FileEntry> smallQueue = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<FileEntry> mediumQueue = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<FileEntry> largeQueue = new ConcurrentLinkedQueue<>();

    public static final long FILESIZE_LARGE_THREHOLD = 1024*1024*24;
    public static final long FILESIZE_SMALL_THREHOLD = 1024*256;

    public static final int BIG_COUNT=3;
    public static final int MEDIUM_COUNT=8;
    public static final int SMALL_COUNT=39;

    List<FileDownloader> largeCores = new ArrayList<>();
    List<FileDownloader> mediumCores = new ArrayList<>();
    List<FileDownloader> smallCores = new ArrayList<>();

    ConcurrentLinkedQueue<FileEntry> failedQueue = new ConcurrentLinkedQueue<>();

    TextView resultView = null;

    int totalCount = 0;

    Runnable reportProgressRunner = new Runnable() {
        @Override
        public void run() {
            resultView.setText("共计:"+totalCount+" 剩余:"+((smallQueue.size()+mediumQueue.size()+largeQueue.size()))+" 失败:"+failedQueue.size());
            checkStatus();
            hWnd.postDelayed(reportProgressRunner,500);
        }
    };

    Runnable pingRunner = new Runnable() {
        @Override
        public void run() {
            if(get("ping").contains("pong")) {
                hWnd.postDelayed(pingTimer, 5000);
            }else{
                hWnd.post(new Runnable() {
                    @Override
                    public void run() {
                        new AlertDialog.Builder(PerformActivity.this).setTitle("服务器掉线了").setMessage("任务已经暂停，请确认连接后点击开始按钮继续传输，失败的文件可以在传输结束后重试").setPositiveButton(android.R.string.ok,null).setCancelable(false).create().show();
                        notifyStop();
                    }
                });
            }
        }
    };

    Runnable pingTimer = new Runnable() {
        @Override
        public void run() {
            new Thread(pingRunner).start();
        }
    };

    public View createTitle(String s){
        TextView v = (TextView) getLayoutInflater().inflate(R.layout.item_title,null);
        v.setText(s);
        return v;
    }
    public View createEntry(){
        View v =  getLayoutInflater().inflate(R.layout.item_downloader,null);
        return v;
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this).setTitle("退出传输?").setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                notifyStop();
                finish();
            }
        }).setNegativeButton(android.R.string.cancel,null).create().show();
    }
    String ip = "";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_perform);
        resultView = (TextView) findViewById(R.id.txtResult);
        final List<FileEntry> items =getPassedbyItems();
        ip = getIntent().getStringExtra("ip");
        totalCount = items.size();
        createTasks();
        new PDD<Void>(this) {
            @Override
            public Void runWorkAsync() throws Exception {
                Collections.shuffle(items);
                for (int i = 0; i < items.size(); i++) {
                    FileEntry fe = items.get(i);
                    if(fe.size > FILESIZE_LARGE_THREHOLD){
                        largeQueue.offer(fe);
                        continue;
                    }
                    if(fe.size < FILESIZE_SMALL_THREHOLD){
                        smallQueue.offer(fe);
                        continue;
                    }
                    mediumQueue.offer(fe);
                }
                return null;
            }

            @Override
            public void onComplete(Void tr) {

            }
        }.execute();
        hWnd.postDelayed(reportProgressRunner,500);
        pwl = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"backup_server_require_wakeup");
        pwl.acquire();
    }

    public void notifyStop(){
        for (FileDownloader fd :
                largeCores) {
            fd.cancel(false);
        }
        for (FileDownloader fd :
                mediumCores) {
            fd.cancel(false);
        }
        for (FileDownloader fd :
                smallCores) {
            fd.cancel(false);
        }
    }

    public void notifyStart(){
        ExecutorService newFixedThreadPool = Executors.newFixedThreadPool(SMALL_COUNT+MEDIUM_COUNT+BIG_COUNT);
        for (FileDownloader fd :
                largeCores) {
            fd.executeOnExecutor(newFixedThreadPool,largeQueue,failedQueue);
        }
        boolean shouldAdd2 = false;
        for (FileDownloader fd :
                mediumCores) {
            if(shouldAdd2) {
                fd.executeOnExecutor(newFixedThreadPool, mediumQueue, failedQueue,largeQueue);
            }else{

                fd.executeOnExecutor(newFixedThreadPool, mediumQueue, failedQueue);
            }
            shouldAdd2=!shouldAdd2;
        }
        for (FileDownloader fd :
                smallCores) {
            fd.executeOnExecutor(newFixedThreadPool,smallQueue,failedQueue);
        }
        hWnd.postDelayed(pingTimer,5000);
    }

    boolean taskfinished = false;

    public void checkStatus(){
        boolean canStart = true;
        boolean finished = true;
        for (FileDownloader fd :
                largeCores) {
            if(fd.isRunning()){
                canStart = false;
            }
            if(!fd.isRunning()){
            }
            finished = finished & fd.isFinished();
        }
        for (FileDownloader fd :
                mediumCores) {
            if(fd.isRunning()){
                canStart = false;
            }
            if(!fd.isRunning()){
            }
            finished = finished & fd.isFinished();
        }
        for (FileDownloader fd :
                smallCores) {
            if(fd.isRunning()){
                canStart = false;
            }
            if(!fd.isRunning()){
            }
            finished = finished & fd.isFinished();
        }

        findViewById(R.id.btnStart).setEnabled(canStart);
        if(taskfinished!=finished){
            taskfinished = finished;
            if(finished) {
                onComplete();
            }
        }

    }

    public void onComplete(){
        if(failedQueue.size()>0){
            final List<FileEntry> fails = new ArrayList<>();
            while (!failedQueue.isEmpty()){
                fails.add(failedQueue.poll());
            }
            ListView lv = new ListView(this);
            lv.setFastScrollAlwaysVisible(true);
            lv.setFastScrollEnabled(true);
            lv.setAdapter(new ArrayAdapter<FileEntry>(this,R.layout.adapter_list2,R.id.txtText,fails){
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    View v= super.getView(position, convertView, parent);
                    ((TextView)v.findViewById(R.id.txtText2)).setText(getItem(position).failReason);
                    return v;
                }
            });



            AlertDialog ald = new AlertDialog.Builder(this).setTitle("失败的的文件: "+fails.size()).setView(lv).setPositiveButton("放进列表重试", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    new PDD<Void>(PerformActivity.this) {
                        @Override
                        public Void runWorkAsync() throws Exception {
                            for (int i = 0; i < fails.size(); i++) {
                                FileEntry fe = fails.get(i);
                                if(fe.size > FILESIZE_LARGE_THREHOLD){
                                    largeQueue.offer(fe);
                                    continue;
                                }
                                if(fe.size < FILESIZE_SMALL_THREHOLD){
                                    smallQueue.offer(fe);
                                    continue;
                                }
                                mediumQueue.offer(fe);
                            }
                            return null;
                        }

                        @Override
                        public void onComplete(Void tr) {
                            Toast.makeText(PerformActivity.this, "文件已放入列表，点击开始按钮开始传输!", Toast.LENGTH_LONG).show();
                        }
                    }.execute();
                }
            }).create();
            ald.show();
        }
        else{
            new AlertDialog.Builder(PerformActivity.this).setTitle("传输完成").setMessage("所有的文件都已经传输").setPositiveButton(android.R.string.ok,null).setCancelable(false).create().show();
        }
    }

    public void createTasks(){
        smallCores.clear();
        mediumCores.clear();
        largeCores.clear();

        LinearLayout container = (LinearLayout) findViewById(R.id.tblListWrapper);
        container.removeAllViews();
        container.addView(createTitle("大型文件: ("+BIG_COUNT+"线程)"),-1,-2);
        for (int i = 0; i < BIG_COUNT; i++) {
            View v = createEntry();
            FileDownloader fld = new FileDownloader(v,ip);
            container.addView(v,-1,-2);
            largeCores.add(fld);
        }
        container.addView(createTitle("小型文件: ("+MEDIUM_COUNT+"线程)"),-1,-2);
        for (int i = 0; i < MEDIUM_COUNT; i++) {
            View v = createEntry();
            FileDownloader fld = new FileDownloader(v,ip);
            container.addView(v,-1,-2);
            mediumCores.add(fld);
        }
        container.addView(createTitle("零碎文件: ("+SMALL_COUNT+"线程)"),-1,-2);
        for (int i = 0; i < SMALL_COUNT; i++) {
            View v = createEntry();
            FileDownloader fld = new FileDownloader(v,ip);
            container.addView(v,-1,-2);
            smallCores.add(fld);
        }
    }

    public void start(View v){
        createTasks();
        notifyStart();
    }

    public void stop(View v){
        new AlertDialog.Builder(this).setTitle("停止传输?").setMessage("是否停止传输？当前正在传输的文件会中断，并计入失败\n失败的文件可以在传输完成后重试").setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                notifyStop();
                Toast.makeText(PerformActivity.this, "失败的文件可以在传输完成之后重试", Toast.LENGTH_SHORT).show();
            }
        }).setNegativeButton(android.R.string.cancel,null).create().show();
    }


    public String get(String url){
        String baseUrl = "http://"+ip+":54081/"+url;
        String result = "";
        try{
            String realUrl = baseUrl+url;
            HttpURLConnection conn = (HttpURLConnection) new URL(realUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.connect();
            String temp="";
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            while ((temp=br.readLine())!=null){result+=temp+"\n";}
            br.close();
            conn.disconnect();
        }catch (Exception ex)
        {
            ex.printStackTrace();
        }
        return result;
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        pwl.release();
    }
}

class FileDownloader extends AsyncTask<ConcurrentLinkedQueue<FileEntry>,String,Boolean>{


    public FileDownloader(View updatebleView, String ip) {
        this.updatebleView = updatebleView;
        this.ip = ip;
    }

    public View updatebleView;
    public String ip;
    @Override
    protected void onProgressUpdate(String... values) {
        updatebleView.findViewById(R.id.chtIndicator).setBackgroundColor(values[0].isEmpty() ? Color.GREEN : Color.RED);
        ((TextView)updatebleView.findViewById(R.id.txtCurrent)).setText(values[1]);
        ((ProgressBar)updatebleView.findViewById(R.id.numProgress)).setProgress(Integer.parseInt(values[2]));
    }

    boolean isRunning = false;
    public boolean isRunning(){
        return isRunning;
    }
    boolean isFinished = false;
    public boolean isFinished(){
        return isFinished;
    }
    long lastUpdate = -1;

    @Override
    protected Boolean doInBackground(ConcurrentLinkedQueue<FileEntry>... params) {
        Queue<FileEntry> input = params[0];
        Queue<FileEntry> fails = params[1];
        Queue<FileEntry> secondary = null;
        if(params.length>2){secondary=params[2];}
        while (!input.isEmpty() || (null!=secondary && (!secondary.isEmpty()))){
            if(isCancelled()){
                return false;
            }
            FileEntry current = null;
            if(input.isEmpty()){
                current = secondary.poll();
            }
            else{
                current = input.poll();
            }
            if(!current.isFile){
                new File(current.path).mkdirs();
                continue;
            }
            String downloadTargetPath = current.path;
            if(current.isApk){
                downloadTargetPath = "/sdcard/换机客户端"+downloadTargetPath+".apk";
            }
            try {
                if(System.currentTimeMillis() - lastUpdate > 60){
                    publishProgress("",current.path,"0");
                    lastUpdate = System.currentTimeMillis();
                }
                StringBuilder urlBuilder = new StringBuilder("http://");
                urlBuilder.append(ip).append(":54081");
                String[] paths = current.path.split("\\/");
                for (int i = 1; i < paths.length; i++) {
                    urlBuilder.append("/").append(URLEncoder.encode(paths[i],"UTF-8"));
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(urlBuilder.toString()).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setRequestMethod("GET");
                conn.connect();
                InputStream is = conn.getInputStream();
                File target = new File(downloadTargetPath+".dltmp");
                if(target.exists()){target.delete();}
                if(!target.getParentFile().exists()){target.getParentFile().mkdirs();}
                target.createNewFile();
                FileOutputStream fos = new FileOutputStream(target);
                byte[] buffer = new byte[(int)PerformActivity.FILESIZE_SMALL_THREHOLD];
                int len = 0;
                long max = conn.getContentLength();
                long total = 0;
                while ((len = is.read(buffer,0,buffer.length)) > 0){
                    fos.write(buffer,0,len);
                    total+=len;
                    if(isCancelled()){
                        try {
                            fos.close();
                            is.close();
                        }catch (Exception ex){}
                        current.failReason="cancelled";
                        fails.add(current);
                        return false;
                    }
                    if(System.currentTimeMillis() - lastUpdate > 60){
                        int p = 100;
                        try {
                            p=Math.min(100,(int)(total / (max/100)));
                        }catch (Exception ex){}
                        publishProgress("",current.path,""+p);
                        lastUpdate = System.currentTimeMillis();
                    }
                }
                fos.flush();
                fos.close();
                is.close();
                conn.disconnect();
                File destFile = new File(downloadTargetPath);
                if(destFile.exists()){destFile.delete();}
                target.renameTo(destFile);
                if(isCancelled()){
                    return false;
                }
            }catch (Exception ex){
                current.failReason=ex.getMessage();
                fails.offer(current);
                if(isCancelled()){
                    return false;
                }
                ex.printStackTrace();
            }
        }
        return true;
    }

    @Override
    protected void onPreExecute() {
        isRunning = true;
        isFinished = false;
        onProgressUpdate("","初始化...","0");
    }

    @Override
    protected void onPostExecute(Boolean result) {
        isRunning = false;
        if(result) {
            onProgressUpdate("", "传输成功", "100");
            isFinished = true;
        }else{
            onProgressUpdate("s", "已中断", "0");
        }
    }

    @Override
    protected void onCancelled(Boolean result) {
        isRunning = false;
        if(result) {
            onProgressUpdate("", "传输成功", "100");
            isFinished = true;
        }else{
            onProgressUpdate("s", "已中断", "0");
        }
    }
}



abstract class PDD<TResult> extends AsyncTask<Void,Void,TResult>{
    Context ctx;
    ProgressDialog pdd;
    public PDD(Context ctx) {
        this.ctx = ctx;
        pdd = ProgressDialog.show(ctx,null,"正在载入...",true,false);
    }

    @Override
    protected TResult doInBackground(Void... voids) {
        try {
            return runWorkAsync();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    protected void onPostExecute(TResult tResult) {
        super.onPostExecute(tResult);
        pdd.dismiss();
        onComplete(tResult);
    }

    public abstract TResult runWorkAsync() throws Exception;
    public abstract void onComplete(TResult tr);
}