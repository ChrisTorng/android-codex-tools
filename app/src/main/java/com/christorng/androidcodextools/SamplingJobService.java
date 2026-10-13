package com.christorng.androidcodextools;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.PowerManager;

public class SamplingJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params){
        final JobParameters p=params;
        new Thread(()->{
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wl=pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CodexTools:QuotaSample");
            wl.setReferenceCounted(false);
            wl.acquire(60_000L);
            try{
                Scheduler.runQuotaSample(getApplicationContext());
            }catch(Throwable t){
                Scheduler.note(getApplicationContext(),
                        "配額採樣失敗: "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));
            }finally{
                if(wl.isHeld())wl.release();
                jobFinished(p,false);
            }
        },"codex-quota-sample").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params){
        return true;
    }
}
