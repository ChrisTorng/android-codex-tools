package com.christorng.androidcodextools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

final class RemainingQuotaView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private List<UsageHistory.Sample> samples=new ArrayList<>();
    private boolean primary=true;
    private long resetMs=0;
    private long durationMs=5*60*60_000L;
    private double currentRemaining=100;
    private boolean active=false;
    private boolean dark=false;

    RemainingQuotaView(Context c){super(c);}

    void setData(List<UsageHistory.Sample> history,boolean isPrimary,long reset,long duration,
                 double remaining,boolean isActive,boolean darkMode){
        samples=history==null?new ArrayList<>():history;
        primary=isPrimary;
        resetMs=reset;
        durationMs=duration;
        currentRemaining=Math.max(0,Math.min(100,remaining));
        active=isActive;
        dark=darkMode;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float d=getResources().getDisplayMetrics().density;
        int fg=dark?Color.rgb(223,226,232):Color.rgb(70,75,84);
        int muted=dark?Color.rgb(145,151,161):Color.rgb(115,121,131);
        int grid=dark?Color.rgb(59,64,73):Color.rgb(226,229,234);
        int actual=primary?Color.rgb(47,128,237):Color.rgb(232,145,52);
        int ideal=dark?Color.rgb(124,132,145):Color.rgb(142,149,160);

        float left=30*d,right=getWidth()-8*d,top=7*d,bottom=getHeight()-18*d;
        if(right<=left||bottom<=top)return;

        // 100/50/0 grid.
        paint.setStrokeWidth(1f);
        paint.setTextSize(8.5f*d);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        for(int v:new int[]{100,50,0}){
            float y=yFor(v,top,bottom);
            paint.setColor(grid);
            canvas.drawLine(left,y,right,y,paint);
            paint.setColor(muted);
            String s=String.valueOf(v);
            canvas.drawText(s,2*d,y+3*d,paint);
        }

        if(resetMs<=0){
            paint.setColor(muted);
            paint.setTextSize(11*d);
            canvas.drawText(active?"等待確認視窗":"未啟動",left+8*d,(top+bottom)/2,paint);
            return;
        }

        long start=resetMs-durationMs;
        long now=System.currentTimeMillis();

        // Ideal remaining: 100 -> 0 over the window.
        float idealStartX=xFor(start,start,resetMs,left,right);
        float idealEndX=xFor(resetMs,start,resetMs,left,right);
        Path idealFill=new Path();
        idealFill.moveTo(idealStartX,yFor(100,top,bottom));
        idealFill.lineTo(idealEndX,yFor(0,top,bottom));
        idealFill.lineTo(idealEndX,bottom);
        idealFill.lineTo(idealStartX,bottom);
        idealFill.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(dark?30:22,Color.red(ideal),Color.green(ideal),Color.blue(ideal)));
        canvas.drawPath(idealFill,paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f*d);
        paint.setPathEffect(new DashPathEffect(new float[]{5*d,4*d},0));
        paint.setColor(ideal);
        canvas.drawLine(idealStartX,yFor(100,top,bottom),idealEndX,yFor(0,top,bottom),paint);
        paint.setPathEffect(null);

        // Actual samples from the current window.
        List<float[]> pts=new ArrayList<>();
        for(UsageHistory.Sample s:samples){
            if(s.at<start-60_000L || s.at>Math.min(now,resetMs)+60_000L)continue;
            if(primary && !s.primaryConfirmed)continue;
            long sampleReset=primary?s.primaryReset:s.weeklyReset;
            if(sampleReset>0 && Math.abs(sampleReset-resetMs)>5*60_000L)continue;
            double used=primary?s.primaryUsed:s.weeklyUsed;
            double remain=100.0-Math.max(0,Math.min(100,used));
            pts.add(new float[]{xFor(s.at,start,resetMs,left,right),yFor(remain,top,bottom),(float)remain});
        }

        if(pts.isEmpty() && active){
            pts.add(new float[]{xFor(now,start,resetMs,left,right),yFor(currentRemaining,top,bottom),(float)currentRemaining});
        }

        if(!pts.isEmpty()){
            // Actual area under line.
            Path area=new Path();
            area.moveTo(pts.get(0)[0],bottom);
            for(int i=0;i<pts.size();i++){
                float[] p=pts.get(i);
                if(i==0)area.lineTo(p[0],p[1]); else area.lineTo(p[0],p[1]);
            }
            area.lineTo(pts.get(pts.size()-1)[0],bottom);
            area.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(dark?62:48,Color.red(actual),Color.green(actual),Color.blue(actual)));
            canvas.drawPath(area,paint);

            path.reset();
            for(int i=0;i<pts.size();i++){
                float[] p=pts.get(i);
                if(i==0)path.moveTo(p[0],p[1]); else path.lineTo(p[0],p[1]);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f*d);
            paint.setColor(actual);
            canvas.drawPath(path,paint);

            // Points: hollow means observed value unchanged from prior sample.
            for(int i=0;i<pts.size();i++){
                float[] p=pts.get(i);
                boolean unchanged=i>0 && Math.abs(p[2]-pts.get(i-1)[2])<0.01f;
                paint.setStrokeWidth(1.5f*d);
                paint.setColor(actual);
                paint.setStyle(unchanged?Paint.Style.STROKE:Paint.Style.FILL);
                canvas.drawCircle(p[0],p[1],2.6f*d,paint);
            }
        }

        // Window labels.
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(9*d);
        paint.setColor(fg);
        String startText=time(start),endText=time(resetMs);
        canvas.drawText(startText,left,getHeight()-3*d,paint);
        float ew=paint.measureText(endText);
        canvas.drawText(endText,Math.max(left,right-ew),getHeight()-3*d,paint);

        // Current clock marker, no label.
        if(now>=start&&now<=resetMs){
            float x=xFor(now,start,resetMs,left,right);
            paint.setColor(dark?Color.rgb(220,223,228):Color.rgb(83,88,98));
            paint.setStrokeWidth(1f*d);
            canvas.drawLine(x,top,x,bottom,paint);
        }
    }

    private float xFor(long t,long start,long end,float left,float right){
        if(end<=start)return left;
        double p=(t-start)/(double)(end-start);
        p=Math.max(0,Math.min(1,p));
        return (float)(left+(right-left)*p);
    }

    private float yFor(double remaining,float top,float bottom){
        double p=Math.max(0,Math.min(100,remaining))/100.0;
        return (float)(bottom-(bottom-top)*p);
    }

    private String time(long ms){
        ZonedDateTime z=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        return String.format(java.util.Locale.TAIWAN,"%02d:%02d",z.getHour(),z.getMinute());
    }
}
