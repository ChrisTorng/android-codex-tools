package com.christorng.androidcodextools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        if(!Scheduler.enabled(c))return;
        PendingResult p=goAsync();
        new Thread(()->{
            try{Scheduler.reschedule(c.getApplicationContext());}
            finally{p.finish();}
        },"codex-reschedule").start();
    }
}
