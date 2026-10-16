package com.christorng.androidcodextools;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

final class AndroidNotifications {
    private static final String CHANNEL_ID="codex_tools_events";
    private static final String PREF_ENABLED="android_notifications_enabled";

    private AndroidNotifications(){}

    static boolean enabled(Context context){
        return Scheduler.prefs(context).getBoolean(PREF_ENABLED,true);
    }

    static void setEnabled(Context context,boolean enabled){
        Scheduler.prefs(context).edit().putBoolean(PREF_ENABLED,enabled).apply();
    }

    static void ensureChannel(Context context){
        if(Build.VERSION.SDK_INT<26)return;
        NotificationManager manager=(NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager==null)return;
        NotificationChannel channel=new NotificationChannel(CHANNEL_ID,
                "Codex Tools 事件通知",NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("配額觸發、排程結果、Banked Reset 與錯誤事件");
        manager.createNotificationChannel(channel);
    }

    static boolean permitted(Context context){
        if(Build.VERSION.SDK_INT>=33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        !=PackageManager.PERMISSION_GRANTED)return false;
        NotificationManager manager=(NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager!=null && manager.areNotificationsEnabled();
    }

    static void post(Context context,String title,String message){
        if(!enabled(context)||!permitted(context))return;
        try{
            ensureChannel(context);
            NotificationManager manager=(NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if(manager==null)return;
            Intent launch=new Intent(context,MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent click=PendingIntent.getActivity(context,0,launch,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification notification=new Notification.Builder(context,CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setContentIntent(click)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .build();
            manager.notify((int)(System.currentTimeMillis() & 0x7fffffff),notification);
        }catch(Exception e){
            Scheduler.note(context,"Android 通知失敗: "+
                    e.getClass().getSimpleName()+": "+e.getMessage());
        }
    }
}
