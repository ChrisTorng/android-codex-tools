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
    private float selectedX=0;

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

    private List<UsageHistory.Sample> visibleSamples(){
        List<UsageHistory.Sample> out=new ArrayList<>();
        if(resetMs<=0)return out;
        long start=resetMs-durationMs;
        for(UsageHistory.Sample s:samples){
            if(s.at<start-60_000L||s.at>resetMs+60_000L)continue;
            long sr=primary?s.primaryReset:s.weeklyReset;
            if(sr>0&&Math.abs(sr-resetMs)>5*60_000L)continue;
            out.add(s);
        }
        return out;
    }

    private UsageHistory.Sample findNearestSample(float x){
        if(resetMs<=0||chartRight<=chartLeft)return null;
        List<UsageHistory.Sample> visible=visibleSamples();
        if(visible.isEmpty())return null;
        long start=resetMs-durationMs;

        float first=xFor(visible.get(0).at,start,resetMs,chartLeft,chartRight);
        float last=xFor(visible.get(visible.size()-1).at,start,resetMs,chartLeft,chartRight);
        if(x<first||x>last)return null;

        UsageHistory.Sample best=null;
        float bestDx=Float.MAX_VALUE;
        for(UsageHistory.Sample s:visible){
            float sx=xFor(s.at,start,resetMs,chartLeft,chartRight);
            float dx=Math.abs(sx-x);
            if(dx<bestDx){bestDx=dx;best=s;selectedX=sx;}
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

        chartLeft=5*d;
        chartRight=getWidth()-5*d;
        chartTop=7*d;
        chartBottom=getHeight()-22*d;
        if(chartRight<=chartLeft||chartBottom<=chartTop)return;

        for(int v:new int[]{100,50,0}){
            float y=yFor(v,chartTop,chartBottom);
            paint.setColor(grid);
            paint.setStrokeWidth(1f);
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

        // 理想剩餘：目前這一個 window 由 100% 線性降到 0%。
        Path idealFill=new Path();
        idealFill.moveTo(chartLeft,yFor(100,chartTop,chartBottom));
        idealFill.lineTo(chartRight,yFor(0,chartTop,chartBottom));
        idealFill.lineTo(chartRight,chartBottom);
        idealFill.lineTo(chartLeft,chartBottom);
        idealFill.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(dark?28:20,Color.red(actual),Color.green(actual),Color.blue(actual)));
        canvas.drawPath(idealFill,paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f*d);
        paint.setPathEffect(new DashPathEffect(new float[]{5*d,4*d},0));
        paint.setColor(Color.argb(180,Color.red(actual),Color.green(actual),Color.blue(actual)));
        canvas.drawLine(chartLeft,yFor(100,chartTop,chartBottom),
                chartRight,yFor(0,chartTop,chartBottom),paint);
        paint.setPathEffect(null);

        List<UsageHistory.Sample> visible=visibleSamples();
        if(!visible.isEmpty()){
            Path area=new Path();
            Path line=new Path();
            boolean first=true;
            float lastX=chartLeft;
            for(UsageHistory.Sample s:visible){
                double used=primary?s.primaryUsed:s.weeklyUsed;
                double remaining=100.0-Math.max(0,Math.min(100,used));
                float x=xFor(s.at,start,resetMs,chartLeft,chartRight);
                float y=yFor(remaining,chartTop,chartBottom);
                if(first){
                    area.moveTo(x,chartBottom);
                    area.lineTo(x,y);
                    line.moveTo(x,y);
                    first=false;
                }else{
                    area.lineTo(x,y);
                    line.lineTo(x,y);
                }
                lastX=x;
            }
            area.lineTo(lastX,chartBottom);
            area.close();

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(dark?60:46,Color.red(actual),Color.green(actual),Color.blue(actual)));
            canvas.drawPath(area,paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f*d);
            paint.setColor(actual);
            canvas.drawPath(line,paint);
        }

        // 現在時間只是一條游標，不代表此刻已有新採樣。
        if(now>=start&&now<=resetMs){
            float x=xFor(now,start,resetMs,chartLeft,chartRight);
            paint.setColor(dark?Color.rgb(215,218,224):Color.rgb(83,88,98));
            paint.setStrokeWidth(1f*d);
            canvas.drawLine(x,chartTop,x,chartBottom,paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(9*d);
        paint.setColor(fg);
        String st=primary?clock(start):shortDate(start);
        String en=primary?clock(resetMs):shortDate(resetMs);
        canvas.drawText(st,chartLeft,getHeight()-3*d,paint);
        float ew=paint.measureText(en);
        canvas.drawText(en,Math.max(chartLeft,chartRight-ew),getHeight()-3*d,paint);

        if(selected!=null)drawSelection(canvas,selected,d,actual,fg);
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
                String label=String.valueOf(tick.getDayOfMonth());
                float w=paint.measureText(label);
                canvas.drawText(label,Math.max(chartLeft,Math.min(chartRight-w,x-w/2f)),getHeight()-3*d,paint);
                tick=tick.plusDays(1);
            }
        }
    }

    private void drawSelection(Canvas canvas,UsageHistory.Sample s,float d,int color,int fg){
        double used=primary?s.primaryUsed:s.weeklyUsed;
        double remain=100-Math.max(0,Math.min(100,used));
        String text=String.format(Locale.TAIWAN,"%s · %.0f%% 剩餘",clock(s.at),remain);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.3f*d);
        paint.setColor(color);
        canvas.drawLine(selectedX,chartTop,selectedX,chartBottom,paint);

        paint.setTextSize(10*d);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        float pad=6*d,w=paint.measureText(text)+pad*2,h=23*d;
        float x=Math.max(chartLeft,Math.min(chartRight-w,selectedX-w/2f));
        float y=chartTop+2*d;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(dark?Color.rgb(48,52,60):Color.WHITE);
        canvas.drawRoundRect(x,y,x+w,y+h,6*d,6*d,paint);
        paint.setColor(fg);
        canvas.drawText(text,x+pad,y+15.5f*d,paint);
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

    private String shortDate(long ms){
        ZonedDateTime z=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        return String.format(Locale.TAIWAN,"%d/%d",z.getMonthValue(),z.getDayOfMonth());
    }
}
