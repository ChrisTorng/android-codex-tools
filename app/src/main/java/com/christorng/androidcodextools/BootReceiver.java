package com.christorng.androidcodextools;
import android.content.*;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        if(!Scheduler.enabled(c))return;
        PendingResult p=goAsync();
        new Thread(()->{try{Scheduler.runCycle(c.getApplicationContext(),false);}finally{p.finish();}},"codex-boot").start();
    }
}
