package com.christorng.androidcodextools;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.os.PersistableBundle;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class Scheduler {
    static final String PREFS="app";
    static final String KEY_NTFY="ntfy_url";
    static final String KEY_LAST="last_status";
    private static final String KEY_HISTORY="event_history_v1";
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
    private static final String KEY_PRIMARY_CONFIRMED="quota_primary_confirmed";
    private static final String KEY_LAST_ALARM_RECEIVED="last_alarm_received";
    private static final String KEY_LAST_BACKUP_RECEIVED="last_backup_received";
    private static final String KEY_RESET_AVAILABLE="reset_available_count";
    private static final String KEY_RESET_CREDIT_ID="reset_credit_id";
    private static final String KEY_RESET_TITLE="reset_credit_title";
    private static final String KEY_RESET_DESCRIPTION="reset_credit_description";
    private static final String KEY_RESET_EXPIRES="reset_credit_expires";
    private static final String KEY_RESET_LAST_CHECK="reset_credit_last_check";
    private static final String KEY_RESET_ERROR="reset_credit_error";

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
    private static final int BACKUP_JOB_ID=2101;
    private static final int WATCHDOG_JOB_ID=2102;
    private static final long BACKUP_DELAY_MS=90_000L;
    private static final long BACKUP_DEADLINE_EXTRA_MS=8*60_000L;
    private static final long WATCHDOG_PERIOD_MS=15*60_000L;

    static final class Snapshot {
        final String plan;
        final boolean allowed;
        final boolean primaryActive;
        final boolean primaryConfirmed;
        final double primaryUsed;
        final long primaryResetMs;
        final boolean secondaryActive;
        final double secondaryUsed;
        final long secondaryResetMs;
        final long lastCheckMs;
        final int resetAvailableCount;
        final String resetCreditId,resetTitle,resetDescription,resetExpires,resetError;

        Snapshot(SharedPreferences p){
            plan=p.getString(KEY_PLAN,"—");
            allowed=p.getBoolean(KEY_ALLOWED,true);
            primaryActive=p.getBoolean(KEY_PRIMARY_ACTIVE,false);
            primaryConfirmed=p.getBoolean(KEY_PRIMARY_CONFIRMED,false);
            primaryUsed=Double.longBitsToDouble(p.getLong(KEY_PRIMARY_USED,Double.doubleToLongBits(0)));
            primaryResetMs=p.getLong(KEY_PRIMARY_RESET,0);
            secondaryActive=p.getBoolean(KEY_SECONDARY_ACTIVE,false);
            secondaryUsed=Double.longBitsToDouble(p.getLong(KEY_SECONDARY_USED,Double.doubleToLongBits(0)));
            secondaryResetMs=p.getLong(KEY_SECONDARY_RESET,0);
            lastCheckMs=p.getLong(KEY_LAST_CHECK,0);
            resetAvailableCount=p.getInt(KEY_RESET_AVAILABLE,0);
            resetCreditId=p.getString(KEY_RESET_CREDIT_ID,"");
            resetTitle=p.getString(KEY_RESET_TITLE,"");
            resetDescription=p.getString(KEY_RESET_DESCRIPTION,"");
            resetExpires=p.getString(KEY_RESET_EXPIRES,"");
            resetError=p.getString(KEY_RESET_ERROR,"");
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
    static String last(Context c){
        return displayEvent(prefs(c).getString(KEY_LAST,""));
    }
    static String history(Context c){
        SharedPreferences p=prefs(c);
        String raw=p.getString(KEY_HISTORY,"");
        if(raw==null||raw.isEmpty()){
            String last=p.getString(KEY_LAST,"");
            return last==null||last.isEmpty()?"尚無紀錄":displayEvent(last);
        }
        String[] items=raw.split("\\u001e",-1);
        StringBuilder out=new StringBuilder();
        for(int i=items.length-1;i>=0;i--){
            String item=items[i].trim();
            if(item.isEmpty())continue;
            if(out.length()>0)out.append("\n");
            out.append(displayEvent(item));
        }
        return out.length()==0?"尚無紀錄":out.toString();
    }
    static void note(Context c,String message){
        SharedPreferences p=prefs(c);
        String encoded="@"+System.currentTimeMillis()+"|"+message;
        String oldHistory=p.getString(KEY_HISTORY,"");
        StringBuilder h=new StringBuilder();
        if(oldHistory!=null&&!oldHistory.isEmpty()){
            String[] items=oldHistory.split("\\u001e",-1);
            int start=Math.max(0,items.length-19);
            for(int i=start;i<items.length;i++){
                String item=items[i].trim();
                if(item.isEmpty())continue;
                if(h.length()>0)h.append("\u001e");
                h.append(item);
            }
        }else{
            String oldLast=p.getString(KEY_LAST,"");
            if(oldLast!=null&&!oldLast.isEmpty()&&!oldLast.equals("尚無紀錄")){
                h.append(oldLast);
            }
        }
        if(h.length()>0)h.append("\u001e");
        h.append(encoded);
        p.edit().putString(KEY_LAST,encoded).putString(KEY_HISTORY,h.toString()).apply();
    }
    private static String displayEvent(String item){
        if(item==null||item.isEmpty())return "尚無紀錄";
        if(item.startsWith("@")){
            int bar=item.indexOf('|');
            if(bar>1){
                try{
                    long ms=Long.parseLong(item.substring(1,bar));
                    return formatTime(ms)+" · "+item.substring(bar+1);
                }catch(Exception ignored){}
            }
        }
        return item;
    }
    static long next(Context c){return prefs(c).getLong(KEY_NEXT,0);}
    static String nextReason(Context c){return prefs(c).getString(KEY_NEXT_REASON,"");}
    static String lastAlarmReceived(Context c){return prefs(c).getString(KEY_LAST_ALARM_RECEIVED,"—");}
    static String lastBackupReceived(Context c){return prefs(c).getString(KEY_LAST_BACKUP_RECEIVED,"—");}
    static void markAlarmReceived(Context c){
        prefs(c).edit().putString(KEY_LAST_ALARM_RECEIVED,formatTime(System.currentTimeMillis())).apply();
    }
    private static void markBackupReceived(Context c,String source){
        prefs(c).edit().putString(KEY_LAST_BACKUP_RECEIVED,
                formatTime(System.currentTimeMillis())+" · "+source).apply();
    }

    static boolean resetEligibleNow(Context c){
        Snapshot s=snapshot(c);
        return !s.allowed || s.primaryUsed>=99.999 || s.secondaryUsed>=99.999;
    }

    static int chainRemaining(Context c){return prefs(c).getInt(KEY_CHAIN_REMAINING,0);}
    static long chainResetMs(Context c){return prefs(c).getLong(KEY_CHAIN_RESET,0);}

    static void checkNow(Context c){checkNow(c,true);}

    static void checkNow(Context c,boolean logEvent){
        try{
            CodexClient cli=new CodexClient(c);
            CodexClient.Quota q=cli.getQuota();
            storeQuota(c,q,false);
            refreshResetCredits(c,cli,logEvent);
            if(logEvent)record(c,"配額已更新");
            if(MODE_AUTO.equals(mode(c)))scheduleAutoFromQuota(c,q);
            else if(MODE_CUSTOM.equals(mode(c)))scheduleNextCustom(c,System.currentTimeMillis()+1_000L);
        }catch(Exception e){
            if(logEvent)record(c,"更新配額失敗: "+safe(e));
        }
    }

    private static void refreshResetCredits(Context c,CodexClient cli,boolean force){
        SharedPreferences p=prefs(c);
        long now=System.currentTimeMillis();
        long last=p.getLong(KEY_RESET_LAST_CHECK,0);
        if(!force && now-last<10*60_000L)return;
        try{
            CodexClient.ResetCredits credits=cli.getResetCredits();
            CodexClient.ResetCredit first=credits.first();
            SharedPreferences.Editor e=p.edit()
                    .putInt(KEY_RESET_AVAILABLE,credits.availableCount)
                    .putLong(KEY_RESET_LAST_CHECK,now)
                    .putString(KEY_RESET_ERROR,"");
            if(first!=null){
                e.putString(KEY_RESET_CREDIT_ID,first.id)
                        .putString(KEY_RESET_TITLE,first.title==null?"Full reset":first.title)
                        .putString(KEY_RESET_DESCRIPTION,first.description==null?"":first.description)
                        .putString(KEY_RESET_EXPIRES,first.expiresAt==null?"":first.expiresAt);
            }else{
                e.remove(KEY_RESET_CREDIT_ID).remove(KEY_RESET_TITLE)
                        .remove(KEY_RESET_DESCRIPTION).remove(KEY_RESET_EXPIRES);
            }
            e.apply();
        }catch(Exception e){
            p.edit()
                    .putInt(KEY_RESET_AVAILABLE,0)
                    .remove(KEY_RESET_CREDIT_ID)
                    .putLong(KEY_RESET_LAST_CHECK,now)
                    .putString(KEY_RESET_ERROR,safe(e))
                    .apply();
        }
    }

    static void consumeResetCredit(Context c){
        try{
            CodexClient cli=new CodexClient(c);

            // Re-check live quota immediately before spending a reset credit.
            CodexClient.Quota live=cli.getQuota();
            storeQuota(c,live,false);
            boolean exhausted=!live.allowed || live.limitReached ||
                    (live.primary!=null&&live.primary.usedPercent>=99.999) ||
                    (live.secondary!=null&&live.secondary.usedPercent>=99.999);
            if(!exhausted){
                record(c,"Reset 未執行：5 小時與週配額都尚未用盡");
                refreshResetCredits(c,cli,true);
                return;
            }

            CodexClient.ResetCredits credits=cli.getResetCredits();
            CodexClient.ResetCredit credit=credits.first();
            if(credit==null||credits.availableCount<=0){
                prefs(c).edit().putInt(KEY_RESET_AVAILABLE,0).remove(KEY_RESET_CREDIT_ID).apply();
                record(c,"目前沒有可用的 reset");
                return;
            }

            CodexClient.ResetResult result=cli.consumeResetCredit(credit.id);
            prefs(c).edit().putBoolean(KEY_PRIMARY_CONFIRMED,false).apply();
            Thread.sleep(1_000L);

            CodexClient.Quota q=cli.getQuota();
            storeQuota(c,q,false);
            refreshResetCredits(c,cli,true);
            record(c,"Reset 完成 · "+result.windowsReset+" 個視窗");
            notifyNtfy(c,"Codex Reset 完成","已重置 "+result.windowsReset+" 個 usage window");

            if(MODE_AUTO.equals(mode(c)))scheduleAutoFromQuota(c,q);
            else if(MODE_CUSTOM.equals(mode(c)))scheduleNextCustom(c,System.currentTimeMillis()+1_000L);
        }catch(Exception e){
            record(c,"Reset 失敗: "+safe(e));
        }
    }

    static void manualTrigger(Context c){
        try{
            CodexClient cli=new CodexClient(c);
            cli.triggerMinimal();
            Thread.sleep(2_000L);
            CodexClient.Quota q=cli.getQuota();
            storeQuota(c,q,true);
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
            storeQuota(c,q,false);

            Snapshot observed=snapshot(c);
            if(observed.primaryConfirmed && observed.primaryResetMs>System.currentTimeMillis()){
                record(c,"5 小時視窗已確認有效，等待重置");
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
            storeQuota(c,after,true);
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
            storeQuota(c,q,false);
            long now=System.currentTimeMillis();

            Snapshot observed=snapshot(c);
            if(observed.primaryConfirmed && observed.primaryResetMs>now){
                long reset=observed.primaryResetMs;
                long wait=reset-now;
                if(wait>=0 && wait<=RESET_TOLERANCE_MS){
                    record(c,"錨點距 5 小時重置僅 "+Math.max(1,wait/1000)+" 秒，延後至重置後再觸發");
                    scheduleEvent(c,reset+RESET_GRACE_MS,"等候 5 小時重置",EVENT_ANCHOR_RETRY,autoCount);
                    return;
                }
                record(c,"錨點到時但已確認的 5 小時視窗仍有效，本次略過");
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
            storeQuota(c,after,true);
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
            storeQuota(c,q,false);
            long now=System.currentTimeMillis();

            Snapshot observed=snapshot(c);
            if(observed.primaryConfirmed && observed.primaryResetMs>now){
                long reset=observed.primaryResetMs;
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
            storeQuota(c,after,true);
            remaining--;
            record(c,"自動接續觸發成功"+(remaining>0?"，尚餘 "+remaining+" 次":""));
            notifyNtfy(c,"Codex 自動接續成功",quotaNotification(after));

            Snapshot afterObserved=snapshot(c);
            if(remaining>0 && afterObserved.primaryConfirmed && afterObserved.primaryResetMs>System.currentTimeMillis()){
                prefs(c).edit()
                        .putInt(KEY_CHAIN_REMAINING,remaining)
                        .putLong(KEY_CHAIN_RESET,afterObserved.primaryResetMs)
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
        Snapshot s=snapshot(c);
        if(count>0 && s.primaryConfirmed && s.primaryResetMs>System.currentTimeMillis()){
            prefs(c).edit()
                    .putInt(KEY_CHAIN_REMAINING,count)
                    .putLong(KEY_CHAIN_RESET,s.primaryResetMs)
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
        if(s.primaryConfirmed && s.primaryResetMs>now){
            scheduleEvent(c,s.primaryResetMs+RESET_GRACE_MS,"5 小時視窗重置後",EVENT_AUTO,0);
        }else if(s.secondaryActive && s.secondaryUsed>=100.0 && s.secondaryResetMs>now){
            scheduleEvent(c,s.secondaryResetMs+RESET_GRACE_MS,"週配額重置後",EVENT_AUTO,0);
        }else{
            scheduleEvent(c,now+60_000L,"檢查並視需要觸發",EVENT_AUTO,0);
        }
    }

    private static void scheduleAutoFromQuota(Context c,CodexClient.Quota q){
        long now=System.currentTimeMillis();
        Snapshot s=snapshot(c);
        if(s.primaryConfirmed && s.primaryResetMs>now){
            scheduleEvent(c,s.primaryResetMs+RESET_GRACE_MS,"5 小時視窗重置後",EVENT_AUTO,0);
        }else if(q.secondary!=null&&q.secondary.usedPercent>=100.0&&q.secondary.resetAt>0){
            scheduleEvent(c,q.secondary.resetAt*1000L+RESET_GRACE_MS,"週配額重置後",EVENT_AUTO,0);
        }else{
            // No confirmed 5h window. A zero-usage primary_window may merely be a
            // sliding now+5h placeholder, so don't wait for that synthetic reset.
            scheduleEvent(c,now+15_000L,"尚未確認 5 小時視窗，準備觸發",EVENT_AUTO,0);
        }
    }

    static void scheduleNextCustom(Context c,long afterMs){
        ScheduleConfig.Slot anchor=nextEligibleAnchor(c,afterMs);
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

    private static ScheduleConfig.Slot nextEligibleAnchor(Context c,long afterMs){
        ScheduleConfig.Slot anchor=ScheduleConfig.nextAnchor(c,afterMs);
        Snapshot s=snapshot(c);
        if(!s.primaryConfirmed || s.primaryResetMs<=System.currentTimeMillis())return anchor;

        long impossibleBefore=s.primaryResetMs-RESET_TOLERANCE_MS;
        int guard=0;
        while(anchor!=null && anchor.whenMs<impossibleBefore && guard++<64){
            anchor=ScheduleConfig.nextAnchor(c,anchor.whenMs+1_000L);
        }
        return anchor;
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
            Intent show=new Intent(c,MainActivity.class);
            PendingIntent showPi=PendingIntent.getActivity(
                    c,REQUEST_CODE+1,show,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(when,showPi),pi);
        }
        prefs(c).edit()
                .putLong(KEY_NEXT,when)
                .putString(KEY_NEXT_REASON,reason)
                .putString(KEY_NEXT_KIND,kind)
                .putInt(KEY_NEXT_AUTO_COUNT,autoCount)
                .apply();
        scheduleBackupJobs(c,when);
    }

    static void cancelAlarm(Context c){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pendingIntent(c));
        JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(js!=null){
            js.cancel(BACKUP_JOB_ID);
            js.cancel(WATCHDOG_JOB_ID);
        }
        prefs(c).edit()
                .remove(KEY_NEXT)
                .remove(KEY_NEXT_REASON)
                .remove(KEY_NEXT_KIND)
                .remove(KEY_NEXT_AUTO_COUNT)
                .apply();
    }

    private static void scheduleBackupJobs(Context c,long when){
        JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(js==null)return;

        long now=System.currentTimeMillis();
        long delay=Math.max(0,when-now+BACKUP_DELAY_MS);

        PersistableBundle extras=new PersistableBundle();
        extras.putLong("expected_when",when);
        extras.putString("source","單次保底");
        JobInfo backup=new JobInfo.Builder(BACKUP_JOB_ID,
                new ComponentName(c,BackupJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay+BACKUP_DEADLINE_EXTRA_MS)
                .setPersisted(true)
                .setExtras(extras)
                .build();
        js.schedule(backup);

        PersistableBundle watchdogExtras=new PersistableBundle();
        watchdogExtras.putLong("expected_when",0L);
        watchdogExtras.putString("source","15 分鐘 watchdog");
        JobInfo watchdog=new JobInfo.Builder(WATCHDOG_JOB_ID,
                new ComponentName(c,BackupJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(WATCHDOG_PERIOD_MS)
                .setPersisted(true)
                .setExtras(watchdogExtras)
                .build();
        js.schedule(watchdog);
    }

    static void onBackupJob(Context c,long expectedWhen,String source){
        if(!enabled(c))return;
        long now=System.currentTimeMillis();
        long current=next(c);

        if(expectedWhen>0){
            if(current<=0 || Math.abs(current-expectedWhen)>2_000L || now<expectedWhen){
                return; // stale backup for an event that has already been rescheduled.
            }
            markBackupReceived(c,source);
            note(c,"保底排程接管 · "+source);
            onAlarm(c);
            return;
        }

        // Periodic watchdog: only take over if the intended event is already overdue.
        if(current>0 && now>=current+2*60_000L){
            markBackupReceived(c,source);
            note(c,"排程逾時，由 watchdog 接管");
            onAlarm(c);
        }
    }

    private static PendingIntent pendingIntent(Context c){
        return PendingIntent.getBroadcast(c,REQUEST_CODE,new Intent(c,AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    static String formatTime(long ms){
        if(ms<=0)return "—";
        ZonedDateTime z=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        LocalDate d=z.toLocalDate();
        LocalDate today=LocalDate.now(ZoneId.systemDefault());
        String time=String.format(Locale.TAIWAN,"%02d:%02d:%02d",z.getHour(),z.getMinute(),z.getSecond());
        if(d.equals(today))return time;
        if(d.equals(today.plusDays(1)))return "明天 "+time;
        if(d.equals(today.minusDays(1)))return "昨天 "+time;
        String[] weekday={"一","二","三","四","五","六","日"};
        return String.format(Locale.TAIWAN,"%d/%d (%s) %s",
                d.getMonthValue(),d.getDayOfMonth(),weekday[d.getDayOfWeek().getValue()-1],time);
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

    private static void storeQuota(Context c,CodexClient.Quota q,boolean confirmBecauseTriggered){
        SharedPreferences p=prefs(c);
        long now=System.currentTimeMillis();
        long previousCheck=p.getLong(KEY_LAST_CHECK,0);
        long previousReset=p.getLong(KEY_PRIMARY_RESET,0);
        boolean previousConfirmed=p.getBoolean(KEY_PRIMARY_CONFIRMED,false);

        boolean confirmed=false;
        long newReset=0;
        if(q.primary!=null){
            newReset=q.primary.resetAt*1000L;
            if(confirmBecauseTriggered || q.primary.usedPercent>0.0001){
                confirmed=true;
            }else if(previousConfirmed && previousReset>now &&
                    Math.abs(newReset-previousReset)<=120_000L){
                confirmed=true;
            }else if(previousCheck>0 && previousReset>0){
                long elapsed=Math.max(0,now-previousCheck);
                long slide=Math.abs(newReset-previousReset);
                // Idle placeholder: reset moves almost one-for-one with each GET.
                // Real active window: reset remains essentially fixed.
                if(elapsed>=15_000L && slide<=Math.max(5_000L,elapsed/4)){
                    confirmed=true;
                }
            }
        }

        SharedPreferences.Editor e=p.edit()
                .putString(KEY_PLAN,q.plan)
                .putBoolean(KEY_ALLOWED,q.allowed)
                .putLong(KEY_LAST_CHECK,now)
                .putBoolean(KEY_PRIMARY_CONFIRMED,confirmed);

        if(q.primary!=null){
            e.putBoolean(KEY_PRIMARY_ACTIVE,true)
                    .putLong(KEY_PRIMARY_USED,Double.doubleToLongBits(q.primary.usedPercent))
                    .putLong(KEY_PRIMARY_RESET,newReset);
        }else{
            e.putBoolean(KEY_PRIMARY_ACTIVE,false)
                    .putBoolean(KEY_PRIMARY_CONFIRMED,false)
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
        note(c,message);
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
