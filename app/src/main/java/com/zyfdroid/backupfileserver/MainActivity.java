package com.zyfdroid.backupfileserver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.util.Log;
import android.widget.TextView;
import fi.iki.elonen.NanoHTTPD;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.List;
import java.util.HashMap;

public class MainActivity extends Activity {
    BackupFileServer bfs;
    PowerManager.WakeLock pwl;
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setTextColor(Color.BLACK);
        tv.setKeepScreenOn(true);
        setContentView(tv);
        bfs = new BackupFileServer(this,54081);
        try {
            bfs.start();
            pwl = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"backup_server_require_wakeup");
            pwl.acquire();
            tv.setText(" · 服务器正在运行 http://IP地址:54081/\n\n · 本机可用IP地址: \r\n"+getLocalIPAddress()+"\r\n\r\n · 请将手机接通电源并置于散热良好的环境中\r\n\r\n · 新手机可以打开浏览器输入 http://IP地址:54081/getapp 下载应用");
        } catch (IOException e) {
            e.printStackTrace();
            tv.setText("服务器端启动失败，可能有程序占用了54081端口: \n"+e.toString());
        }
    }

    public String getLocalIPAddress()
    {
        String result="";
        try
        {
            for (Enumeration<NetworkInterface> mEnumeration = NetworkInterface.getNetworkInterfaces(); mEnumeration.hasMoreElements();)               {
                NetworkInterface intf = mEnumeration.nextElement();
                for (Enumeration<InetAddress> enumIPAddr = intf.getInetAddresses(); enumIPAddr.hasMoreElements();)
                {
                    InetAddress inetAddress = enumIPAddr.nextElement();
                    //如果不是回环地址
                    if (!inetAddress.isLoopbackAddress() && !inetAddress.getHostAddress().contains(":"))
                    {
                        result+= inetAddress.getHostAddress()+"\n";
                    }
                }
            }
        }
        catch (SocketException ex)
        {
            Log.e("Error", ex.toString());
        }
        return result.trim();
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this).setTitle("停止服务器并退出?").setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                finish();
            }
        }).setNegativeButton(android.R.string.cancel,null).create().show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pwl.release();
        bfs.stop();
    }
}

class BackupFileServer extends NanoHTTPD{

	Context ctx;
	
    public BackupFileServer(Context ctx,int port) {
        super(port);
		this.ctx=ctx;
    }
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if(uri.startsWith("/ping")){
            return newFixedLengthResponse(Response.Status.OK,"text/plain","pong");
        }
        if(uri.startsWith("/sdcard")){
            File f = new File(uri);
            if(f.isFile()) {
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(f);
                    return NanoHTTPD.newFixedLengthResponse(Response.Status.OK,"application/octet-stream",fis,f.length());
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                    return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN,"text/plain",e.getClass().getSimpleName()+":"+e.getMessage());
                }
            }
			else{
				return newFixedLengthResponse(Response.Status.NO_CONTENT,"application/octet-stream","");
			}
        }
        if(uri.startsWith("/list")){
            FileEnumrator enumrator=new FileEnumrator(ctx,Environment.getExternalStorageDirectory().toString());
			enumrator.start();
			return newChunkedResponse(Response.Status.OK,"text/plain",enumrator.getOutputStream());
        }
        if(uri.startsWith("/app")){
            String pkgname = uri.substring("/app/".length());
			if(null==packages){
				getAppList();
			}
			File f=new File(packages.get(pkgname));
			try {
				Response resp = newFixedLengthResponse(Response.Status.OK, "application/vnd.android.package-archive", new FileInputStream(f), f.length());
                resp.addHeader("Content-Disposition","attachment;filename="+pkgname+".apk");
                return resp;
			} catch (FileNotFoundException e) {}
        }
        if(uri.equals("/")){
            return newFixedLengthResponse(Response.Status.OK,"text/plain","这里没有彩蛋。");
        }
        if(uri.equals("/getapp")){
            Response resp = newFixedLengthResponse(Response.Status.TEMPORARY_REDIRECT,"text/plain","");
            resp.addHeader("Location","app/"+ctx.getPackageName());
            return resp;
        }
        return super.serve(session);
    }
	
	HashMap<String,String> packages;

	private void getAppList() {
		this.packages = new HashMap<>();
		PackageManager pm =  ctx.getPackageManager();
		// Return a List of all packages that are installed on the device.
		List<PackageInfo> packages = pm.getInstalledPackages(0);
		for (PackageInfo packageInfo : packages) {
			// 判断系统/非系统应用
			if ((packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) // 非系统应用
			{
				this.packages.put(packageInfo.packageName,packageInfo.applicationInfo.sourceDir);
			}
		}
	}
}

class FileEnumrator extends Thread{
	
	public String rootPath;

	public PipedInputStream pis;
	public PipedOutputStream pos;
	
	public PrintStream ps;

	Context ctx;
	
	public FileEnumrator(Context ctx,String rootPath) {
		this.ctx=ctx;
		this.rootPath = rootPath;
		pos = new PipedOutputStream();
		pis = new PipedInputStream();
        try {
            ps = new PrintStream(pos,true,"UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        try {
			pis.connect(pos);
		} catch (IOException e) {e.printStackTrace();}
	}


	public PipedInputStream getOutputStream() {
		return pis;
	}

	@Override
	public void run() {
		enumrateFiles(new File(rootPath));
		getAppList();
        ps.println("<EOF>");
		ps.close();
		try {
			pos.close();
		} catch (IOException e) {}
	}
	
	public void enumrateFiles(File root){
		if(root.isDirectory()){
			ps.println("-1 /sdcard"+root.getAbsolutePath().substring(rootPath.length()));
			File[] subfiles = root.listFiles();
			for (int i=0;i < subfiles.length;i++) {
				enumrateFiles(subfiles[i]);
			}
		}
		else{
			ps.println(root.length()+" /sdcard"+root.getAbsolutePath().substring(rootPath.length()));
		}
	}
	
	private void getAppList() {
		PackageManager pm =  ctx.getPackageManager();
		// Return a List of all packages that are installed on the device.
		List<PackageInfo> packages = pm.getInstalledPackages(0);
		for (PackageInfo packageInfo : packages) {
			// 判断系统/非系统应用
			if ((packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) // 非系统应用
			{
				File f = new File(packageInfo.applicationInfo.sourceDir);
				ps.println(f.length()+" /app/"+packageInfo.packageName);
			}
		}
	}
}
