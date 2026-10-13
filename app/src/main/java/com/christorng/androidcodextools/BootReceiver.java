package com.christorng.androidcodextools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        PendingResult p=goAsync();
        new Thread(()->{
            try{
                Context app=c.getApplicationContext();
                if(new CodexClient(app).signedIn())Scheduler.scheduleQuotaSampling(app);
                if(Scheduler.enabled(app))Scheduler.reschedule(app);
            }finally{p.finish();}
        },"codex-reschedule").start();
    }
}
