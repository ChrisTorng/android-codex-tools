package com.christorng.androidcodextools;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;

final class Scheduler {
    static final String PREFS="app", KEY_ENABLED="scheduler_enabled", KEY_NTFY="ntfy_url", KEY_LAST="last_status", KEY_NEXT="next_alarm_ms";
    private static final long GRACE=60000L;
    static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    static boolean enabled(Context c){return prefs(c).getBoolean(KEY_ENABLED,false);}
    static void setEnabled(Context c,boolean v){prefs(c).edit().putBoolean(KEY_ENABLED,v).apply();}
    static String last(Context c){return prefs(c).getString(KEY_LAST,"No run yet");}
    static long next(Context c){return prefs(c).getLong(KEY_NEXT,0);}

    static void runCycle(Context c,boolean force){
        try{
            CodexClient cli=new CodexClient(c);
            CodexClient.Quota q=cli.getQuota();
            if(force){
                cli.triggerMinimal(); Thread.sleep(2000); q=cli.getQuota();
                record(c,"Manual trigger OK\n"+q.summary()); notifyNtfy(c,"Codex trigger test OK",q.summary());
                if(enabled(c))scheduleFromQuota(c,q); return;
            }
            if(q.primary!=null){
                record(c,"Quota active\n"+q.summary());
                if(enabled(c))scheduleAt(c,q.primary.resetAt*1000L+GRACE);
                return;
            }
            boolean blocked=!q.allowed || (q.secondary!=null&&q.secondary.usedPercent>=100.0);
            if(blocked){
                record(c,"Weekly/global quota blocked\n"+q.summary());
                notifyNtfy(c,"Codex trigger skipped","Weekly/global quota blocked.");
                if(enabled(c)&&q.secondary!=null&&q.secondary.resetAt>0)scheduleAt(c,q.secondary.resetAt*1000L+GRACE);
                return;
            }
            cli.triggerMinimal(); Thread.sleep(2000);
            CodexClient.Quota after=cli.getQuota();
            record(c,"New 5h window triggered\n"+after.summary());
            notifyNtfy(c,"Codex 5h quota started",after.summary());
            if(enabled(c))scheduleFromQuota(c,after);
        }catch(Exception e){
            record(c,"ERROR: "+e.getMessage());
            notifyNtfy(c,"Codex trigger error",String.valueOf(e.getMessage()));
            if(enabled(c))scheduleAt(c,System.currentTimeMillis()+15*60_000L);
        }
    }

    static void scheduleFromQuota(Context c,CodexClient.Quota q){
        if(q.primary!=null&&q.primary.resetAt>0)scheduleAt(c,q.primary.resetAt*1000L+GRACE);
        else if(q.secondary!=null&&q.secondary.usedPercent>=100&&q.secondary.resetAt>0)scheduleAt(c,q.secondary.resetAt*1000L+GRACE);
        else scheduleAt(c,System.currentTimeMillis()+15*60_000L);
    }

    static boolean canExact(Context c){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT<31||am.canScheduleExactAlarms();
    }

    static Intent exactSettings(Context c){
        return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:"+c.getPackageName()));
    }

    static void scheduleAt(Context c,long when){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi=PendingIntent.getBroadcast(c,1001,new Intent(c,AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        if(Build.VERSION.SDK_INT>=31&&!am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
        prefs(c).edit().putLong(KEY_NEXT,when).apply();
    }

    static String formattedNext(Context c){
        long t=next(c); return t<=0?"none":DateFormat.getDateTimeInstance().format(new Date(t));
    }

    private static void record(Context c,String s){
        prefs(c).edit().putString(KEY_LAST,DateFormat.getDateTimeInstance().format(new Date())+"\n"+s).apply();
    }

    private static void notifyNtfy(Context c,String title,String body){
        String u=prefs(c).getString(KEY_NTFY,"").trim(); if(u.isEmpty())return;
        try{
            byte[] b=body.getBytes(StandardCharsets.UTF_8);
            HttpURLConnection h=(HttpURLConnection)new URL(u).openConnection();
            h.setRequestMethod("POST"); h.setDoOutput(true); h.setConnectTimeout(10000); h.setReadTimeout(10000);
            h.setRequestProperty("Title",title); h.setFixedLengthStreamingMode(b.length);
            try(OutputStream os=h.getOutputStream()){os.write(b);}
            h.getResponseCode(); h.disconnect();
        }catch(Exception ignored){}
    }
}
