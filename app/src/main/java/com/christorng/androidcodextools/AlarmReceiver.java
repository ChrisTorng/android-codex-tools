package com.christorng.androidcodextools;
import android.content.*;
public class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        PendingResult p=goAsync();
        new Thread(()->{try{Scheduler.runCycle(c.getApplicationContext(),false);}finally{p.finish();}},"codex-alarm").start();
    }
}
