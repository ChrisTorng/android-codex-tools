package com.christorng.androidcodextools;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class Scheduler {
    static final String PREFS="app";
    static final String KEY_NTFY="ntfy_url";
    static final String KEY_LAST="last_status";
    static final String KEY_NEXT="next_alarm_ms";
    static final String KEY_NEXT_REASON="next_alarm_reason";
    static final String KEY_MODE="scheduler_mode";

    static final String MODE_OFF="off";
    static final String MODE_AUTO="auto_reset";
    static final String MODE_CUSTOM="custom";

    private static final String KEY_PLAN="quota_plan";
    private static final String KEY_ALLOWED="quota_allowed";
    private static final String KEY_PRIMARY_ACTIVE="quota_primary_active";
    private static final String KEY_PRIMARY_USED="quota_primary_used";
    private static final String KEY_PRIMARY_RESET="quota_primary_reset";
    private static final String KEY_SECONDARY_ACTIVE="quota_secondary_active";
    private static final String KEY_SECONDARY_USED="quota_secondary_used";
    private static final String KEY_SECONDARY_RESET="quota_secondary_reset";
    private static final String KEY_LAST_CHECK="quota_last_check";

    private static final long GRACE=60_000L;
    private static final int REQUEST_CODE=1001;

    static final class Snapshot {
        final String plan;
        final boolean allowed;
        final boolean primaryActive;
        final double primaryUsed;
        final long primaryResetMs;
        final boolean secondaryActive;
        final double secondaryUsed;
        final long secondaryResetMs;
        final long lastCheckMs;

        Snapshot(SharedPreferences p){
            plan=p.getString(KEY_PLAN,"—");
            allowed=p.getBoolean(KEY_ALLOWED,true);
            primaryActive=p.getBoolean(KEY_PRIMARY_ACTIVE,false);
            primaryUsed=Double.longBitsToDouble(p.getLong(KEY_PRIMARY_USED,Double.doubleToLongBits(0)));
            primaryResetMs=p.getLong(KEY_PRIMARY_RESET,0);
            secondaryActive=p.getBoolean(KEY_SECONDARY_ACTIVE,false);
            secondaryUsed=Double.longBitsToDouble(p.getLong(KEY_SECONDARY_USED,Double.doubleToLongBits(0)));
            secondaryResetMs=p.getLong(KEY_SECONDARY_RESET,0);
            lastCheckMs=p.getLong(KEY_LAST_CHECK,0);
        }
    }

    static SharedPreferences prefs(Context c){
        return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    static Snapshot snapshot(Context c){
        return new Snapshot(prefs(c));
    }

    static String mode(Context c){
        SharedPreferences p=prefs(c);
        if(p.contains(KEY_MODE))return p.getString(KEY_MODE,MODE_OFF);
        // Migrate the old boolean scheduler flag from early test builds.
        return p.getBoolean("scheduler_enabled",false)?MODE_AUTO:MODE_OFF;
    }

    static void setMode(Context c,String mode){
        prefs(c).edit()
                .putString(KEY_MODE,mode)
                .remove("scheduler_enabled")
                .apply();
        if(MODE_OFF.equals(mode))cancelAlarm(c);
        else reschedule(c);
    }

    static boolean enabled(Context c){
        return !MODE_OFF.equals(mode(c));
    }

    static String last(Context c){
        return prefs(c).getString(KEY_LAST,"尚無紀錄");
    }

    static void note(Context c,String message){
        prefs(c).edit().putString(KEY_LAST,message).apply();
    }

    static long next(Context c){
        return prefs(c).getLong(KEY_NEXT,0);
    }

    static String nextReason(Context c){
        return prefs(c).getString(KEY_NEXT_REASON,"");
    }

    static void checkNow(Context c){
        try{
            CodexClient.Quota q=new CodexClient(c).getQuota();
            storeQuota(c,q);
            record(c,"配額已更新");
            if(MODE_AUTO.equals(mode(c)))scheduleAutoFromQuota(c,q);
            else if(MODE_CUSTOM.equals(mode(c)))scheduleNextCustom(c,System.currentTimeMillis()+1_000L);
        }catch(Exception e){
            record(c,"更新配額失敗: "+safe(e));
        }
    }

    static void manualTrigger(Context c){
        try{
            CodexClient cli=new CodexClient(c);
            cli.triggerMinimal();
            Thread.sleep(2_000L);
            CodexClient.Quota q=cli.getQuota();
            storeQuota(c,q);
            record(c,"手動觸發成功");
            notifyNtfy(c,"Codex 手動觸發成功",quotaNotification(q));
            if(MODE_AUTO.equals(mode(c)))scheduleAutoFromQuota(c,q);
            else if(MODE_CUSTOM.equals(mode(c)))scheduleNextCustom(c,System.currentTimeMillis()+1_000L);
        }catch(Exception e){
            record(c,"手動觸發失敗: "+safe(e));
            notifyNtfy(c,"Codex 觸發失敗",safe(e));
        }
    }

    static void onAlarm(Context c){
        String m=mode(c);
        if(MODE_OFF.equals(m)){
            cancelAlarm(c);
            return;
        }
        if(MODE_CUSTOM.equals(m))runCustomSlot(c);
        else runAutoCycle(c);
    }

    private static void runAutoCycle(Context c){
        try{
            CodexClient cli=new CodexClient(c);
            CodexClient.Quota q=cli.getQuota();
            storeQuota(c,q);

            if(q.primary!=null){
                record(c,"5 小時視窗仍有效，等待重置");
                scheduleAutoFromQuota(c,q);
                return;
            }

            boolean blocked=!q.allowed || (q.secondary!=null&&q.secondary.usedPercent>=100.0);
            if(blocked){
                record(c,"週配額已達限制，本次不觸發");
                notifyNtfy(c,"Codex 自動觸發略過","週配額目前不可用");
                scheduleAutoFromQuota(c,q);
                return;
            }

            cli.triggerMinimal();
            Thread.sleep(2_000L);
            CodexClient.Quota after=cli.getQuota();
            storeQuota(c,after);
            record(c,"已自動建立新的 5 小時視窗");
            notifyNtfy(c,"Codex 5 小時配額已啟動",quotaNotification(after));
            scheduleAutoFromQuota(c,after);
        }catch(Exception e){
            record(c,"自動排程失敗: "+safe(e));
            notifyNtfy(c,"Codex 排程錯誤",safe(e));
            scheduleAt(c,System.currentTimeMillis()+15*60_000L,"15 分鐘後重試");
        }
    }

    private static void runCustomSlot(Context c){
        try{
            CodexClient cli=new CodexClient(c);
            CodexClient.Quota q=cli.getQuota();
            storeQuota(c,q);

            if(q.primary!=null){
                record(c,"排程時間已到，但 5 小時視窗仍有效，本次略過");
            }else if(!q.allowed || (q.secondary!=null&&q.secondary.usedPercent>=100.0)){
                record(c,"排程時間已到，但週配額不可用，本次略過");
                notifyNtfy(c,"Codex 排程略過","週配額目前不可用");
            }else{
                cli.triggerMinimal();
                Thread.sleep(2_000L);
                CodexClient.Quota after=cli.getQuota();
                storeQuota(c,after);
                record(c,"自訂排程觸發成功");
                notifyNtfy(c,"Codex 排程觸發成功",quotaNotification(after));
            }
        }catch(Exception e){
            record(c,"自訂排程失敗: "+safe(e));
            notifyNtfy(c,"Codex 排程錯誤",safe(e));
        }finally{
            scheduleNextCustom(c,System.currentTimeMillis()+1_000L);
        }
    }

    static void reschedule(Context c){
        String m=mode(c);
        if(MODE_OFF.equals(m)){
            cancelAlarm(c);
            return;
        }

        if(MODE_CUSTOM.equals(m)){
            scheduleNextCustom(c,System.currentTimeMillis()+1_000L);
            return;
        }

        Snapshot s=snapshot(c);
        long now=System.currentTimeMillis();
        if(s.primaryActive && s.primaryResetMs>now){
            scheduleAt(c,s.primaryResetMs+GRACE,"5 小時視窗重置後");
        }else if(s.secondaryActive && s.secondaryUsed>=100.0 && s.secondaryResetMs>now){
            scheduleAt(c,s.secondaryResetMs+GRACE,"週配額重置後");
        }else{
            scheduleAt(c,now+60_000L,"檢查並視需要觸發");
        }
    }

    private static void scheduleAutoFromQuota(Context c,CodexClient.Quota q){
        long now=System.currentTimeMillis();
        if(q.primary!=null&&q.primary.resetAt>0){
            scheduleAt(c,q.primary.resetAt*1000L+GRACE,"5 小時視窗重置後");
        }else if(q.secondary!=null&&q.secondary.usedPercent>=100.0&&q.secondary.resetAt>0){
            scheduleAt(c,q.secondary.resetAt*1000L+GRACE,"週配額重置後");
        }else{
            scheduleAt(c,now+60_000L,"檢查並視需要觸發");
        }
    }

    static void scheduleNextCustom(Context c,long afterMs){
        long next=ScheduleConfig.nextCustomSlot(c,afterMs);
        if(next<=0){
            cancelAlarm(c);
            prefs(c).edit().putString(KEY_NEXT_REASON,"尚未設定可用的自訂時段").apply();
            return;
        }
        scheduleAt(c,next,"自訂週排程");
    }

    static boolean canExact(Context c){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT<31||am.canScheduleExactAlarms();
    }

    static Intent exactSettings(Context c){
        return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:"+c.getPackageName()));
    }

    static void scheduleAt(Context c,long when,String reason){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi=pendingIntent(c);
        if(Build.VERSION.SDK_INT>=31&&!am.canScheduleExactAlarms()){
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
        }else{
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
        }
        prefs(c).edit()
                .putLong(KEY_NEXT,when)
                .putString(KEY_NEXT_REASON,reason)
                .apply();
    }

    static void cancelAlarm(Context c){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pendingIntent(c));
        prefs(c).edit()
                .remove(KEY_NEXT)
                .remove(KEY_NEXT_REASON)
                .apply();
    }

    private static PendingIntent pendingIntent(Context c){
        return PendingIntent.getBroadcast(
                c,
                REQUEST_CODE,
                new Intent(c,AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    static String formatTime(long ms){
        if(ms<=0)return "—";
        DateTimeFormatter f=DateTimeFormatter.ofPattern("M/d (E) HH:mm",Locale.TAIWAN);
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(f);
    }

    static String formattedNext(Context c){
        long t=next(c);
        if(t<=0)return "尚未排程";
        String reason=nextReason(c);
        return formatTime(t)+(reason.isEmpty()?"":" · "+reason);
    }

    private static void storeQuota(Context c,CodexClient.Quota q){
        SharedPreferences.Editor e=prefs(c).edit()
                .putString(KEY_PLAN,q.plan)
                .putBoolean(KEY_ALLOWED,q.allowed)
                .putLong(KEY_LAST_CHECK,System.currentTimeMillis());

        if(q.primary!=null){
            e.putBoolean(KEY_PRIMARY_ACTIVE,true)
                    .putLong(KEY_PRIMARY_USED,Double.doubleToLongBits(q.primary.usedPercent))
                    .putLong(KEY_PRIMARY_RESET,q.primary.resetAt*1000L);
        }else{
            e.putBoolean(KEY_PRIMARY_ACTIVE,false)
                    .putLong(KEY_PRIMARY_USED,Double.doubleToLongBits(0))
                    .putLong(KEY_PRIMARY_RESET,0);
        }

        if(q.secondary!=null){
            e.putBoolean(KEY_SECONDARY_ACTIVE,true)
                    .putLong(KEY_SECONDARY_USED,Double.doubleToLongBits(q.secondary.usedPercent))
                    .putLong(KEY_SECONDARY_RESET,q.secondary.resetAt*1000L);
        }else{
            e.putBoolean(KEY_SECONDARY_ACTIVE,false)
                    .putLong(KEY_SECONDARY_USED,Double.doubleToLongBits(0))
                    .putLong(KEY_SECONDARY_RESET,0);
        }
        e.apply();
    }

    private static void record(Context c,String message){
        note(c,formatTime(System.currentTimeMillis())+" · "+message);
    }

    private static String quotaNotification(CodexClient.Quota q){
        String p=q.primary==null?"5 小時：未啟動":
                String.format(Locale.TAIWAN,"5 小時已使用 %.0f%%，重置 %s",
                        q.primary.usedPercent,formatTime(q.primary.resetAt*1000L));
        String w=q.secondary==null?"週配額：無資料":
                String.format(Locale.TAIWAN,"週配額已使用 %.0f%%，重置 %s",
                        q.secondary.usedPercent,formatTime(q.secondary.resetAt*1000L));
        return p+"\n"+w;
    }

    private static String safe(Exception e){
        String m=e.getMessage();
        return e.getClass().getSimpleName()+(m==null?"":": "+m);
    }

    private static void notifyNtfy(Context c,String title,String body){
        String u=prefs(c).getString(KEY_NTFY,"").trim();
        if(u.isEmpty())return;
        try{
            byte[] b=body.getBytes(StandardCharsets.UTF_8);
            HttpURLConnection h=(HttpURLConnection)new URL(u).openConnection();
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setConnectTimeout(10_000);
            h.setReadTimeout(10_000);
            h.setRequestProperty("Title",title);
            h.setFixedLengthStreamingMode(b.length);
            try(OutputStream os=h.getOutputStream()){os.write(b);}
            h.getResponseCode();
            h.disconnect();
        }catch(Exception ignored){}
    }
}
