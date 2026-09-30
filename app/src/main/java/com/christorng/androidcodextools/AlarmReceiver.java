package com.christorng.androidcodextools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        PendingResult p=goAsync();
        new Thread(()->{
            try{Scheduler.onAlarm(c.getApplicationContext());}
            finally{p.finish();}
        },"codex-alarm").start();
    }
}
