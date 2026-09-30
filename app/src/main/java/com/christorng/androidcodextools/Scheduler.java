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

    private static final String KEY_NEXT_KIND="next_event_kind";
    private static final String KEY_NEXT_AUTO_COUNT="next_event_auto_count";
    private static final String KEY_CHAIN_REMAINING="chain_remaining";
    private static final String KEY_CHAIN_RESET="chain_reset_ms";

    private static final String EVENT_AUTO="auto";
    private static final String EVENT_ANCHOR="anchor";
    private static final String EVENT_CHAIN="chain";
    private static final String EVENT_ANCHOR_RETRY="anchor_retry";

    private static final long RESET_TOLERANCE_MS=60_000L;
    private static final long RESET_GRACE_MS=15_000L;
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

    static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    static Snapshot snapshot(Context c){return new Snapshot(prefs(c));}

    static String mode(Context c){
        SharedPreferences p=prefs(c);
        if(p.contains(KEY_MODE))return p.getString(KEY_MODE,MODE_OFF);
        return p.getBoolean("scheduler_enabled",false)?MODE_AUTO:MODE_OFF;
    }

    static void setMode(Context c,String mode){
        prefs(c).edit().putString(KEY_MODE,mode).remove("scheduler_enabled").apply();
        if(MODE_OFF.equals(mode))cancelAlarm(c);
        else reschedule(c);
    }

    static boolean enabled(Context c){return !MODE_OFF.equals(mode(c));}
    static String last(Context c){return prefs(c).getString(KEY_LAST,"尚無紀錄");}
    static void note(Context c,String message){prefs(c).edit().putString(KEY_LAST,message).apply();}
    static long next(Context c){return prefs(c).getLong(KEY_NEXT,0);}
    static String nextReason(Context c){return prefs(c).getString(KEY_NEXT_REASON,"");}

    static int chainRemaining(Context c){return prefs(c).getInt(KEY_CHAIN_REMAINING,0);}
    static long chainResetMs(Context c){return prefs(c).getLong(KEY_CHAIN_RESET,0);}

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
        if(MODE_OFF.equals(m)){cancelAlarm(c);return;}
        String kind=prefs(c).getString(KEY_NEXT_KIND,MODE_AUTO.equals(m)?EVENT_AUTO:EVENT_ANCHOR);
        if(MODE_AUTO.equals(m)||EVENT_AUTO.equals(kind)){
            runAutoCycle(c);
        }else if(EVENT_CHAIN.equals(kind)){
            runChain(c);
        }else{
            runAnchor(c,EVENT_ANCHOR_RETRY.equals(kind));
        }
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
            if(blocked(q)){
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
            scheduleEvent(c,System.currentTimeMillis()+15*60_000L,"15 分鐘後重試",EVENT_AUTO,0);
        }
    }

    private static void runAnchor(Context c,boolean retry){
        int autoCount=prefs(c).getInt(KEY_NEXT_AUTO_COUNT,0);
        try{
            CodexClient cli=new CodexClient(c);
            CodexClient.Quota q=cli.getQuota();
            storeQuota(c,q);
            long now=System.currentTimeMillis();

            if(q.primary!=null){
                long reset=q.primary.resetAt*1000L;
                long wait=reset-now;
                if(wait>=0 && wait<=RESET_TOLERANCE_MS){
                    record(c,"錨點距 5 小時重置僅 "+Math.max(1,wait/1000)+" 秒，延後至重置後再觸發");
                    scheduleEvent(c,reset+RESET_GRACE_MS,"等候 5 小時重置",EVENT_ANCHOR_RETRY,autoCount);
                    return;
                }
                record(c,"錨點到時但 5 小時視窗仍有效，本次略過");
                scheduleNextCustom(c,now+1_000L);
                return;
            }

            if(blocked(q)){
                record(c,"錨點到時但週配額不可用，本次略過");
                scheduleNextCustom(c,now+1_000L);
                return;
            }

            cli.triggerMinimal();
            Thread.sleep(2_000L);
            CodexClient.Quota after=cli.getQuota();
            storeQuota(c,after);
            record(c,retry?"重置後延遲觸發成功":"錨點觸發成功");
            notifyNtfy(c,"Codex 排程觸發成功",quotaNotification(after));
            startChainFrom(c,after,autoCount);
            scheduleNextCustom(c,System.currentTimeMillis()+1_000L);
        }catch(Exception e){
            record(c,"錨點觸發失敗: "+safe(e));
            notifyNtfy(c,"Codex 排程錯誤",safe(e));
            scheduleNextCustom(c,System.currentTimeMillis()+60_000L);
        }
    }

    private static void runChain(Context c){
        int remaining=chainRemaining(c);
        if(remaining<=0){
            clearChain(c);
            scheduleNextCustom(c,System.currentTimeMillis()+1_000L);
            return;
        }

        try{
            CodexClient cli=new CodexClient(c);
            CodexClient.Quota q=cli.getQuota();
            storeQuota(c,q);
            long now=System.currentTimeMillis();

            if(q.primary!=null){
                long reset=q.primary.resetAt*1000L;
                record(c,"自動接續等待實際 5 小時重置");
                prefs(c).edit().putLong(KEY_CHAIN_RESET,reset).apply();
                scheduleEvent(c,Math.max(now+5_000L,reset+RESET_GRACE_MS),
                        "自動接續，尚餘 "+remaining+" 次",EVENT_CHAIN,0);
                return;
            }

            if(blocked(q)){
                record(c,"自動接續因週配額不可用而停止");
                clearChain(c);
                scheduleNextCustom(c,now+1_000L);
                return;
            }

            cli.triggerMinimal();
            Thread.sleep(2_000L);
            CodexClient.Quota after=cli.getQuota();
            storeQuota(c,after);
            remaining--;
            record(c,"自動接續觸發成功"+(remaining>0?"，尚餘 "+remaining+" 次":""));
            notifyNtfy(c,"Codex 自動接續成功",quotaNotification(after));

            if(remaining>0 && after.primary!=null){
                prefs(c).edit()
                        .putInt(KEY_CHAIN_REMAINING,remaining)
                        .putLong(KEY_CHAIN_RESET,after.primary.resetAt*1000L)
                        .apply();
            }else{
                clearChain(c);
            }
            scheduleNextCustom(c,System.currentTimeMillis()+1_000L);
        }catch(Exception e){
            record(c,"自動接續失敗: "+safe(e));
            notifyNtfy(c,"Codex 自動接續錯誤",safe(e));
            scheduleEvent(c,System.currentTimeMillis()+5*60_000L,
                    "自動接續 5 分鐘後重試",EVENT_CHAIN,0);
        }
    }

    private static void startChainFrom(Context c,CodexClient.Quota after,int count){
        if(count>0 && after.primary!=null){
            prefs(c).edit()
                    .putInt(KEY_CHAIN_REMAINING,count)
                    .putLong(KEY_CHAIN_RESET,after.primary.resetAt*1000L)
                    .apply();
        }else{
            clearChain(c);
        }
    }

    private static void clearChain(Context c){
        prefs(c).edit().remove(KEY_CHAIN_REMAINING).remove(KEY_CHAIN_RESET).apply();
    }

    static void reschedule(Context c){
        String m=mode(c);
        if(MODE_OFF.equals(m)){cancelAlarm(c);return;}
        if(MODE_CUSTOM.equals(m)){
            scheduleNextCustom(c,System.currentTimeMillis()+1_000L);
            return;
        }

        Snapshot s=snapshot(c);
        long now=System.currentTimeMillis();
        if(s.primaryActive && s.primaryResetMs>now){
            scheduleEvent(c,s.primaryResetMs+RESET_GRACE_MS,"5 小時視窗重置後",EVENT_AUTO,0);
        }else if(s.secondaryActive && s.secondaryUsed>=100.0 && s.secondaryResetMs>now){
            scheduleEvent(c,s.secondaryResetMs+RESET_GRACE_MS,"週配額重置後",EVENT_AUTO,0);
        }else{
            scheduleEvent(c,now+60_000L,"檢查並視需要觸發",EVENT_AUTO,0);
        }
    }

    private static void scheduleAutoFromQuota(Context c,CodexClient.Quota q){
        long now=System.currentTimeMillis();
        if(q.primary!=null&&q.primary.resetAt>0){
            scheduleEvent(c,q.primary.resetAt*1000L+RESET_GRACE_MS,"5 小時視窗重置後",EVENT_AUTO,0);
        }else if(q.secondary!=null&&q.secondary.usedPercent>=100.0&&q.secondary.resetAt>0){
            scheduleEvent(c,q.secondary.resetAt*1000L+RESET_GRACE_MS,"週配額重置後",EVENT_AUTO,0);
        }else{
            scheduleEvent(c,now+60_000L,"檢查並視需要觸發",EVENT_AUTO,0);
        }
    }

    static void scheduleNextCustom(Context c,long afterMs){
        ScheduleConfig.Slot anchor=ScheduleConfig.nextAnchor(c,afterMs);
        long anchorMs=anchor==null?Long.MAX_VALUE:anchor.whenMs;

        int remaining=chainRemaining(c);
        long reset=chainResetMs(c);
        long chainMs=(remaining>0&&reset>0)?Math.max(afterMs+1_000L,reset+RESET_GRACE_MS):Long.MAX_VALUE;

        if(chainMs==Long.MAX_VALUE && anchorMs==Long.MAX_VALUE){
            cancelAlarm(c);
            prefs(c).edit().putString(KEY_NEXT_REASON,"尚未設定可用時段").apply();
            return;
        }

        if(chainMs<=anchorMs){
            scheduleEvent(c,chainMs,"自動接續，尚餘 "+remaining+" 次",EVENT_CHAIN,0);
        }else{
            scheduleEvent(c,anchor.whenMs,
                    anchor.source+"錨點"+(anchor.autoCount>0?"，後續自動 "+anchor.autoCount+" 次":""),
                    EVENT_ANCHOR,anchor.autoCount);
        }
    }

    static boolean canExact(Context c){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT<31||am.canScheduleExactAlarms();
    }

    static Intent exactSettings(Context c){
        return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+c.getPackageName()));
    }

    private static void scheduleEvent(Context c,long when,String reason,String kind,int autoCount){
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
                .putString(KEY_NEXT_KIND,kind)
                .putInt(KEY_NEXT_AUTO_COUNT,autoCount)
                .apply();
    }

    static void cancelAlarm(Context c){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pendingIntent(c));
        prefs(c).edit()
                .remove(KEY_NEXT)
                .remove(KEY_NEXT_REASON)
                .remove(KEY_NEXT_KIND)
                .remove(KEY_NEXT_AUTO_COUNT)
                .apply();
    }

    private static PendingIntent pendingIntent(Context c){
        return PendingIntent.getBroadcast(c,REQUEST_CODE,new Intent(c,AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    static String formatTime(long ms){
        if(ms<=0)return "—";
        DateTimeFormatter f=DateTimeFormatter.ofPattern("M/d (E) HH:mm:ss",Locale.TAIWAN);
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(f);
    }

    static String formattedNext(Context c){
        long t=next(c);
        if(t<=0)return "尚未排程";
        String reason=nextReason(c);
        return formatTime(t)+(reason.isEmpty()?"":" · "+reason);
    }

    static String customPreview(Context c){
        StringBuilder b=new StringBuilder();
        if(chainRemaining(c)>0 && chainResetMs(c)>0){
            long t=chainResetMs(c)+RESET_GRACE_MS;
            b.append("接續 ").append(chainRemaining(c)).append(" 次：")
                    .append(formatTime(t));
            long estimated=t;
            for(int i=1;i<Math.min(chainRemaining(c),3);i++){
                estimated+=5*60*60_000L;
                b.append(" → ").append(formatTime(estimated));
            }
        }
        ScheduleConfig.Slot a=ScheduleConfig.nextAnchor(c,System.currentTimeMillis()+1_000L);
        if(a!=null){
            if(b.length()>0)b.append("\n");
            b.append("下一錨點：").append(formatTime(a.whenMs));
            if(a.autoCount>0)b.append(" + 自動 ").append(a.autoCount).append(" 次");
        }
        return b.length()==0?"尚無排程":b.toString();
    }

    private static boolean blocked(CodexClient.Quota q){
        return !q.allowed || (q.secondary!=null&&q.secondary.usedPercent>=100.0);
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
