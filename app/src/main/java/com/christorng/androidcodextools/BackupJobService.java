package com.christorng.androidcodextools;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.PowerManager;

public class BackupJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params){
        final JobParameters p=params;
        new Thread(()->{
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wl=pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AndroidCodexTools:BackupJob");
            wl.setReferenceCounted(false);
            wl.acquire(90_000L);
            try{
                long expected=params.getExtras().getLong("expected_when",0L);
                String source=params.getExtras().getString("source","job");
                Scheduler.onBackupJob(getApplicationContext(),expected,source);
            }catch(Throwable t){
                Scheduler.note(getApplicationContext(),
                        "保底 Job 例外: "+t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));
            }finally{
                if(wl.isHeld())wl.release();
                jobFinished(p,false);
            }
        },"codex-backup-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params){
        return true;
    }
}
