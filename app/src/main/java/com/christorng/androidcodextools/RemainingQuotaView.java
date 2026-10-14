package com.christorng.androidcodextools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private float chartLeft,chartRight,chartTop,chartBottom;
    private UsageHistory.Sample selected=null;

    RemainingQuotaView(Context c){super(c);setClickable(true);}

    void setData(List<UsageHistory.Sample> history,boolean isPrimary,long reset,long duration,
                 double remaining,boolean isActive,boolean darkMode){
        samples=history==null?new ArrayList<>():history;
        primary=isPrimary;
        resetMs=reset;
        durationMs=duration;
        currentRemaining=Math.max(0,Math.min(100,remaining));
        active=isActive;
        dark=darkMode;
        selected=null;
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE){
            selected=findNearestSample(e.getX());
            invalidate();
            return true;
        }
        if(e.getAction()==MotionEvent.ACTION_UP){
            performClick();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override public boolean performClick(){super.performClick();return true;}

    private UsageHistory.Sample findNearestSample(float x){
        if(resetMs<=0||samples.isEmpty()||chartRight<=chartLeft)return null;
        long start=resetMs-durationMs;
        UsageHistory.Sample best=null;
        float bestDx=Float.MAX_VALUE;
        for(UsageHistory.Sample s:samples){
            if(s.at<start-60_000L||s.at>resetMs+60_000L)continue;
            if(primary&&!s.primaryConfirmed)continue;
            long sr=primary?s.primaryReset:s.weeklyReset;
            if(sr>0&&Math.abs(sr-resetMs)>5*60_000L)continue;
            float sx=xFor(s.at,start,resetMs,chartLeft,chartRight);
            float dx=Math.abs(sx-x);
            if(dx<bestDx){bestDx=dx;best=s;}
        }
        return best;
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float d=getResources().getDisplayMetrics().density;
        int fg=dark?Color.rgb(223,226,232):Color.rgb(70,75,84);
        int muted=dark?Color.rgb(145,151,161):Color.rgb(115,121,131);
        int grid=dark?Color.rgb(59,64,73):Color.rgb(226,229,234);
        int actual=primary?Color.rgb(47,128,237):Color.rgb(232,145,52);
        int ideal=dark?Color.rgb(124,132,145):Color.rgb(142,149,160);

        chartLeft=5*d;
        chartRight=getWidth()-5*d;
        chartTop=7*d;
        chartBottom=getHeight()-22*d;
        if(chartRight<=chartLeft||chartBottom<=chartTop)return;

        // Horizontal guides only; no left-side 0/50/100 labels.
        paint.setStrokeWidth(1f);
        for(int v:new int[]{100,50,0}){
            float y=yFor(v,chartTop,chartBottom);
            paint.setColor(grid);
            canvas.drawLine(chartLeft,y,chartRight,y,paint);
        }

        if(resetMs<=0){
            paint.setColor(muted);
            paint.setTextSize(11*d);
            canvas.drawText(active?"等待確認視窗":"未啟動",chartLeft+8*d,(chartTop+chartBottom)/2,paint);
            return;
        }

        long start=resetMs-durationMs;
        long now=System.currentTimeMillis();

        drawClockTicks(canvas,start,resetMs,d,grid,muted);

        // Ideal remaining: 100 -> 0 over the current window.
        float x0=xFor(start,start,resetMs,chartLeft,chartRight);
        float x1=xFor(resetMs,start,resetMs,chartLeft,chartRight);
        Path idealFill=new Path();
        idealFill.moveTo(x0,yFor(100,chartTop,chartBottom));
        idealFill.lineTo(x1,yFor(0,chartTop,chartBottom));
        idealFill.lineTo(x1,chartBottom);
        idealFill.lineTo(x0,chartBottom);
        idealFill.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(dark?28:20,Color.red(ideal),Color.green(ideal),Color.blue(ideal)));
        canvas.drawPath(idealFill,paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f*d);
        paint.setPathEffect(new DashPathEffect(new float[]{5*d,4*d},0));
        paint.setColor(ideal);
        canvas.drawLine(x0,yFor(100,chartTop,chartBottom),x1,yFor(0,chartTop,chartBottom),paint);
        paint.setPathEffect(null);

        List<float[]> pts=new ArrayList<>();
        for(UsageHistory.Sample s:samples){
            if(s.at<start-60_000L||s.at>Math.min(now,resetMs)+60_000L)continue;
            if(primary&&!s.primaryConfirmed)continue;
            long sr=primary?s.primaryReset:s.weeklyReset;
            if(sr>0&&Math.abs(sr-resetMs)>5*60_000L)continue;
            double used=primary?s.primaryUsed:s.weeklyUsed;
            double remaining=100.0-Math.max(0,Math.min(100,used));
            pts.add(new float[]{xFor(s.at,start,resetMs,chartLeft,chartRight),
                    yFor(remaining,chartTop,chartBottom),(float)remaining});
        }
        if(pts.isEmpty()&&active){
            pts.add(new float[]{xFor(now,start,resetMs,chartLeft,chartRight),
                    yFor(currentRemaining,chartTop,chartBottom),(float)currentRemaining});
        }

        if(!pts.isEmpty()){
            Path area=new Path();
            area.moveTo(pts.get(0)[0],chartBottom);
            for(float[] p:pts)area.lineTo(p[0],p[1]);
            area.lineTo(pts.get(pts.size()-1)[0],chartBottom);
            area.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(dark?60:46,Color.red(actual),Color.green(actual),Color.blue(actual)));
            canvas.drawPath(area,paint);

            path.reset();
            for(int i=0;i<pts.size();i++){
                float[] p=pts.get(i);
                if(i==0)path.moveTo(p[0],p[1]);else path.lineTo(p[0],p[1]);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f*d);
            paint.setColor(actual);
            canvas.drawPath(path,paint);
        }

        // Current-time marker.
        if(now>=start&&now<=resetMs){
            float x=xFor(now,start,resetMs,chartLeft,chartRight);
            paint.setColor(dark?Color.rgb(220,223,228):Color.rgb(83,88,98));
            paint.setStrokeWidth(1f*d);
            canvas.drawLine(x,chartTop,x,chartBottom,paint);
        }

        // Start/end labels.
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(9*d);
        paint.setColor(fg);
        String st=primary?clock(start):shortDateTime(start);
        String en=primary?clock(resetMs):shortDateTime(resetMs);
        canvas.drawText(st,chartLeft,getHeight()-3*d,paint);
        float ew=paint.measureText(en);
        canvas.drawText(en,Math.max(chartLeft,chartRight-ew),getHeight()-3*d,paint);

        if(selected!=null)drawTooltip(canvas,selected,d,actual,fg);
    }

    private void drawClockTicks(Canvas canvas,long start,long end,float d,int grid,int muted){
        ZoneId zone=ZoneId.systemDefault();
        paint.setTextSize(8*d);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        if(primary){
            ZonedDateTime tick=Instant.ofEpochMilli(start).atZone(zone)
                    .truncatedTo(ChronoUnit.HOURS).plusHours(1);
            while(tick.toInstant().toEpochMilli()<end){
                long ms=tick.toInstant().toEpochMilli();
                float x=xFor(ms,start,end,chartLeft,chartRight);
                paint.setColor(grid);
                canvas.drawLine(x,chartTop,x,chartBottom,paint);
                paint.setColor(muted);
                String label=String.format(Locale.TAIWAN,"%02d",tick.getHour());
                float w=paint.measureText(label);
                canvas.drawText(label,x-w/2f,getHeight()-3*d,paint);
                tick=tick.plusHours(1);
            }
        }else{
            ZonedDateTime tick=Instant.ofEpochMilli(start).atZone(zone)
                    .toLocalDate().plusDays(1).atStartOfDay(zone);
            while(tick.toInstant().toEpochMilli()<end){
                long ms=tick.toInstant().toEpochMilli();
                float x=xFor(ms,start,end,chartLeft,chartRight);
                paint.setColor(grid);
                canvas.drawLine(x,chartTop,x,chartBottom,paint);
                paint.setColor(muted);
                String label=String.format(Locale.TAIWAN,"%d/%d",tick.getMonthValue(),tick.getDayOfMonth());
                float w=paint.measureText(label);
                canvas.drawText(label,Math.max(chartLeft,Math.min(chartRight-w,x-w/2f)),getHeight()-3*d,paint);
                tick=tick.plusDays(1);
            }
        }
    }

    private void drawTooltip(Canvas canvas,UsageHistory.Sample s,float d,int color,int fg){
        double used=primary?s.primaryUsed:s.weeklyUsed;
        double remain=100-Math.max(0,Math.min(100,used));
        String text=String.format(Locale.TAIWAN,"%s  %.0f%%",clock(s.at),remain);
        paint.setTextSize(10*d);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        float pad=6*d,w=paint.measureText(text)+pad*2,h=24*d;
        long start=resetMs-durationMs;
        float sx=xFor(s.at,start,resetMs,chartLeft,chartRight);
        float x=Math.max(chartLeft,Math.min(chartRight-w,sx-w/2f));
        float y=chartTop+4*d;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(dark?Color.rgb(48,52,60):Color.WHITE);
        canvas.drawRoundRect(x,y,x+w,y+h,6*d,6*d,paint);
        paint.setColor(color);
        canvas.drawCircle(sx,y+h+4*d,3*d,paint);
        paint.setColor(fg);
        canvas.drawText(text,x+pad,y+16*d,paint);
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

    private String clock(long ms){
        ZonedDateTime z=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        return String.format(Locale.TAIWAN,"%02d:%02d",z.getHour(),z.getMinute());
    }

    private String shortDateTime(long ms){
        ZonedDateTime z=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        return String.format(Locale.TAIWAN,"%d/%d %02d:%02d",
                z.getMonthValue(),z.getDayOfMonth(),z.getHour(),z.getMinute());
    }
}
