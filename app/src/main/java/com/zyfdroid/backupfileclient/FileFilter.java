package com.zyfdroid.backupfileclient;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Administrator on 7/19/2020.
 */

public class FileFilter implements Runnable{
    private Callback<Integer> amountCallback = null;

    public void setListCallback(Callback<List<FileEntry>> listCallback) {
        this.listCallback = listCallback;
    }
    private Callback<List<FileEntry>> listCallback = null;
    public void setAmountCallback(Callback<Integer> cbk){amountCallback = cbk;}
    Handler handler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            if(msg.what==4){
                if(null!=amountCallback){amountCallback.onCallback(msg.arg1);}
            }
        }
    };

    List<Filter> allowList = new ArrayList<>();
    List<Filter> blockList = new ArrayList<>();

    private List<FileEntry> resultList = new ArrayList<>();

    public FileFilter(String list) {
        String[] lists = list.split("\\n");
        for (int i = 0; i < lists.length; i++) {
            if(lists[i].startsWith("+")){
                allowList.add(FilterFactory.createFromString(lists[i].substring(1)));
            }else if(lists[i].startsWith("-")){
                blockList.add(FilterFactory.createFromString(lists[i].substring(1)));
            }
        }
        Log.w("emm","emm");
    }

    public List<FileEntry> getResult(){
        return resultList;
    }

    String addr = null;
    public void filterFile(String address){
        addr = address;
        new Thread(this).start();
    }

    @Override
    public void run() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://"+addr+":54081/list").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.connect();

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(),"UTF-8"));
            String line = null;
            boolean eof = false;
            outerloop:
            while ((line = br.readLine())!=null){
                if(line.startsWith("<EOF>")){
                    eof = true;
                    continue;
                }
                String[] struct = line.split(" ",2);
                long len = Long.parseLong(struct[0]);
                String path = struct[1];

                for(Filter f : allowList){
                    if(f.match(path)){
                        resultList.add(new FileEntry(path,len));
                        reportProgress(resultList.size());
                        continue outerloop;
                    }
                }
                for(Filter f : blockList){
                    if(f.match(path)){
                        continue outerloop;
                    }
                }
                resultList.add(new FileEntry(path,len));
                reportProgress(resultList.size());
            }
            if(eof){
                handler.post(new Runnable() {
                    @Override
                    public void run() {

                        listCallback.onCallback(resultList);
                    }
                });
            }
            else{
                reportProgress(-2);
            }
            br.close();
            conn.disconnect();
        } catch (IOException e) {
            e.printStackTrace();
            reportProgress(-1);
        }
    }

    private void reportProgress(int amount){
        Message msg = new Message();
        msg.what=4;
        msg.arg1=amount;
        handler.sendMessage(msg);
    }

    public interface Callback<T>{void onCallback(T cbk);}

    public interface Filter{boolean match(String path);}

    public static class FilterFactory{

        public static Filter createFromString(String raw){
            String real = raw.trim();
            if(real.startsWith("*")){
                return new SuffixFilter(real.substring(1));
            }
            return new PrefixFilter(real);
        }

    }
    public static class PrefixFilter implements Filter{
        String prefix;

        public PrefixFilter(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public boolean match(String path) {
            return path.toLowerCase().startsWith(prefix.toLowerCase());
        }
    }
    public static class SuffixFilter implements Filter{
        String suffix;
        public SuffixFilter(String suffix) {
            this.suffix = suffix;
        }
        @Override
        public boolean match(String path) {
            return path.toLowerCase().endsWith(suffix.toLowerCase());
        }
    }

}

class FileEntry{
    boolean isFile;
    long size;
    String path;
    boolean isApk;

    public String failReason = "";

    public FileEntry(String path,long size) {
        this.isFile = size >= 0;
        this.size = size;
        this.path = path;
        isApk = path.startsWith("/app");
    }

    @Override
    public String toString() {
        return (path);
    }
}