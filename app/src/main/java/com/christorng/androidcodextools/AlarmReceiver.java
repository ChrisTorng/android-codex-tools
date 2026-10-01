package com.christorng.androidcodextools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        Scheduler.markAlarmReceived(c);
        final PendingResult pending=goAsync();

        PowerManager pm=(PowerManager)c.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wakeLock=pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AndroidCodexTools:AlarmWork");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(90_000L);

        new Thread(()->{
            try{
                Scheduler.onAlarm(c.getApplicationContext());
            }catch(Throwable t){
                Scheduler.note(c.getApplicationContext(),
                        "Alarm 執行例外: "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));
            }finally{
                if(wakeLock.isHeld())wakeLock.release();
                pending.finish();
            }
        },"codex-alarm").start();
    }
}
