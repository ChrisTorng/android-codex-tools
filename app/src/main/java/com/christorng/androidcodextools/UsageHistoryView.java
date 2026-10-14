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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class UsageHistoryView extends View {
    static final class Marker {
        final long at;
        final String type;
        Marker(long at,String type){this.at=at;this.type=type;}
    }

    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private List<UsageHistory.Sample> samples=new ArrayList<>();
    private List<UsageHistory.Event> events=new ArrayList<>();
    private List<Marker> markers=new ArrayList<>();
    private boolean dark=false;

    private UsageHistory.Sample selectedSample=null;
    private UsageHistory.Event selectedEvent=null;
    private float selectedX=0,selectedY=0;

    UsageHistoryView(Context c){super(c);setClickable(true);}

    void setData(List<UsageHistory.Sample> s,List<UsageHistory.Event> e,List<Marker> m,boolean darkMode){
        samples=s==null?new ArrayList<>():s;
        events=e==null?new ArrayList<>():e;
        markers=m==null?new ArrayList<>():m;
        dark=darkMode;
        selectedSample=null;
        selectedEvent=null;
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE){
            pickNearest(e.getX(),e.getY());
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

    private void pickNearest(float x,float y){
        float d=getResources().getDisplayMetrics().density;
        float top=4*d,legendH=24*d;
        float bandH=(getHeight()-top-legendH)/2f;
        if(bandH<=0)return;

        int band=(int)((y-top)/bandH);
        if(band<0||band>1){
            selectedSample=null;selectedEvent=null;return;
        }

        LocalDate date=LocalDate.now().plusDays(band-1L);
        float left=4*d,right=getWidth()-5*d;
        float chartTop=top+band*bandH+3*d;
        float chartBottom=top+(band+1)*bandH-16*d;

        List<UsageHistory.Sample> daySamples=new ArrayList<>();
        for(UsageHistory.Sample s:samples){
            ZonedDateTime z=Instant.ofEpochMilli(s.at).atZone(ZoneId.systemDefault());
            if(z.toLocalDate().equals(date))daySamples.add(s);
        }

        UsageHistory.Event bestE=null;
        float bestED=18*d;
        for(UsageHistory.Event ev:events){
            ZonedDateTime z=Instant.ofEpochMilli(ev.at).atZone(ZoneId.systemDefault());
            if(!z.toLocalDate().equals(date))continue;
            float ex=timeX(z,left,right);
            float dx=Math.abs(ex-x);
            if(dx<bestED){bestED=dx;bestE=ev;}
        }
        if(bestE!=null){
            selectedEvent=bestE;
            selectedSample=null;
            selectedX=timeX(Instant.ofEpochMilli(bestE.at).atZone(ZoneId.systemDefault()),left,right);
            selectedY=chartTop;
            return;
        }

        if(daySamples.isEmpty()){
            selectedSample=null;selectedEvent=null;return;
        }
        float first=timeX(Instant.ofEpochMilli(daySamples.get(0).at).atZone(ZoneId.systemDefault()),left,right);
        float last=timeX(Instant.ofEpochMilli(daySamples.get(daySamples.size()-1).at).atZone(ZoneId.systemDefault()),left,right);
        if(x<first||x>last){
            selectedSample=null;selectedEvent=null;return;
        }

        UsageHistory.Sample bestS=null;
        float bestSD=Float.MAX_VALUE;
        float bestX=0;
        for(UsageHistory.Sample s:daySamples){
            ZonedDateTime z=Instant.ofEpochMilli(s.at).atZone(ZoneId.systemDefault());
            float sx=timeX(z,left,right);
            float dx=Math.abs(sx-x);
            if(dx<bestSD){bestSD=dx;bestS=s;bestX=sx;}
        }

        selectedEvent=null;
        selectedSample=bestS;
        selectedX=bestX;
        selectedY=chartTop;
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float d=getResources().getDisplayMetrics().density;
        int bg=dark?Color.rgb(27,29,33):Color.WHITE;
        int fg=dark?Color.rgb(232,234,238):Color.rgb(45,49,56);
        int muted=dark?Color.rgb(150,156,166):Color.rgb(112,118,128);
        int grid=dark?Color.rgb(58,63,71):Color.rgb(227,230,235);

        canvas.drawColor(bg);

        LocalDate today=LocalDate.now();
        LocalDate[] days={today.minusDays(1),today};
        String[] labels={"昨天","今天"};

        float top=4*d;
        float legendH=24*d;
        float bandH=(getHeight()-top-legendH)/2f;

        UsageHistory.Sample latestPrimary=latestSample(true);
        UsageHistory.Sample latestWeekly=latestSample(false);

        for(int i=0;i<2;i++){
            float y0=top+i*bandH;
            float y1=y0+bandH;
            drawBand(canvas,days[i],labels[i],y0,y1,d,fg,muted,grid,latestPrimary,latestWeekly);
        }

        drawLegend(canvas,getHeight()-7*d,d,fg);

        if(selectedSample!=null)drawSampleTooltip(canvas,selectedSample,d,fg);
        else if(selectedEvent!=null)drawEventTooltip(canvas,selectedEvent,d,fg);
    }

    private UsageHistory.Sample latestSample(boolean primary){
        for(int i=samples.size()-1;i>=0;i--){
            UsageHistory.Sample s=samples.get(i);
            if(primary){
                if(s.primaryConfirmed&&s.primaryReset>0)return s;
            }else if(s.weeklyReset>0)return s;
        }
        return null;
    }

    private void drawBand(Canvas canvas,LocalDate date,String label,float top,float bottom,float d,
                          int fg,int muted,int grid,
                          UsageHistory.Sample latestPrimary,UsageHistory.Sample latestWeekly){
        float left=4*d;
        float right=getWidth()-5*d;
        float chartTop=top+3*d;
        float chartBottom=bottom-16*d;

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(11*d);
        paint.setColor(Color.argb(205,Color.red(fg),Color.green(fg),Color.blue(fg)));
        canvas.drawText(label,left+6*d,chartTop+13*d,paint);

        // Horizontal guides only; maximize width by omitting left-side numeric labels.
        for(int p:new int[]{100,50,0}){
            float y=yFor(p,chartTop,chartBottom);
            paint.setColor(grid);
            paint.setStrokeWidth(1f);
            canvas.drawLine(left,y,right,y,paint);
        }

        String[] ticks={"00","06","12","18","24"};
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(8*d);
        for(int i=0;i<5;i++){
            float x=left+(right-left)*i/4f;
            paint.setColor(grid);
            canvas.drawLine(x,chartTop,x,chartBottom,paint);
            paint.setColor(muted);
            float tw=paint.measureText(ticks[i]);
            canvas.drawText(ticks[i],Math.max(left,Math.min(right-tw,x-tw/2f)),chartBottom+11*d,paint);
        }

        // Current-window ideal guides only. Never connect different reset windows.
        if(latestPrimary!=null){
            drawIdealWindow(canvas,date,left,right,chartTop,chartBottom,d,
                    latestPrimary.primaryReset,5*60*60_000L,Color.rgb(47,128,237));
        }
        if(latestWeekly!=null){
            drawIdealWindow(canvas,date,left,right,chartTop,chartBottom,d,
                    latestWeekly.weeklyReset,7L*24*60*60_000L,Color.rgb(232,145,52));
        }

        drawActualSeries(canvas,date,left,right,chartTop,chartBottom,d,true,
                Color.rgb(47,128,237));
        drawActualSeries(canvas,date,left,right,chartTop,chartBottom,d,false,
                Color.rgb(232,145,52));

        drawMarkers(canvas,date,left,right,chartTop,chartBottom,d);
        drawEvents(canvas,date,left,right,chartTop,chartBottom,d);
    }

    private void drawIdealWindow(Canvas canvas,LocalDate date,float left,float right,float top,float bottom,
                                 float d,long reset,long duration,int color){
        if(reset<=0)return;
        ZoneId zone=ZoneId.systemDefault();
        long start=reset-duration;
        long dayStart=date.atStartOfDay(zone).toInstant().toEpochMilli();
        long dayEnd=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long segStart=Math.max(start,dayStart);
        long segEnd=Math.min(reset,dayEnd);
        if(segStart>=segEnd)return;

        double r0=(reset-segStart)*100.0/duration;
        double r1=(reset-segEnd)*100.0/duration;
        float x0=timeXForDay(segStart,date,left,right);
        float x1=timeXForDay(segEnd,date,left,right);
        float y0=yFor(r0,top,bottom),y1=yFor(r1,top,bottom);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.25f*d);
        paint.setPathEffect(new DashPathEffect(new float[]{5*d,4*d},0));
        paint.setColor(Color.argb(175,Color.red(color),Color.green(color),Color.blue(color)));
        canvas.drawLine(x0,y0,x1,y1,paint);
        paint.setPathEffect(null);
    }

    private void drawActualSeries(Canvas canvas,LocalDate date,float left,float right,float top,float bottom,
                                  float d,boolean primary,int color){
        List<UsageHistory.Sample> day=new ArrayList<>();
        for(UsageHistory.Sample s:samples){
            ZonedDateTime z=Instant.ofEpochMilli(s.at).atZone(ZoneId.systemDefault());
            if(!z.toLocalDate().equals(date))continue;
            day.add(s);
        }
        if(day.isEmpty())return;

        Path area=new Path();
        Path line=new Path();
        boolean started=false;
        float firstX=0,lastX=0;
        long previousReset=Long.MIN_VALUE;

        for(UsageHistory.Sample s:day){
            long reset=primary?s.primaryReset:s.weeklyReset;
            double used=primary?s.primaryUsed:s.weeklyUsed;
            double remaining=100-Math.max(0,Math.min(100,used));
            ZonedDateTime z=Instant.ofEpochMilli(s.at).atZone(ZoneId.systemDefault());
            float x=timeX(z,left,right);
            float y=yFor(remaining,top,bottom);

            boolean newSegment=started&&previousReset>0&&reset>0&&Math.abs(reset-previousReset)>5*60_000L;
            if(!started||newSegment){
                if(started){
                    area.lineTo(lastX,bottom);
                    area.close();
                    drawArea(canvas,area,color,d);
                    drawLine(canvas,line,color,d);
                    area.reset();line.reset();
                }
                area.moveTo(x,bottom);
                area.lineTo(x,y);
                line.moveTo(x,y);
                firstX=x;
                started=true;
            }else{
                area.lineTo(x,y);
                line.lineTo(x,y);
            }
            lastX=x;
            previousReset=reset;
        }

        if(started){
            area.lineTo(lastX,bottom);
            area.close();
            drawArea(canvas,area,color,d);
            drawLine(canvas,line,color,d);
        }
    }

    private void drawArea(Canvas canvas,Path area,int color,float d){
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(dark?40:30,Color.red(color),Color.green(color),Color.blue(color)));
        canvas.drawPath(area,paint);
    }

    private void drawLine(Canvas canvas,Path line,int color,float d){
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.0f*d);
        paint.setColor(color);
        paint.setPathEffect(null);
        canvas.drawPath(line,paint);
    }

    private void drawMarkers(Canvas canvas,LocalDate date,float left,float right,float top,float bottom,float d){
        for(Marker m:markers){
            ZonedDateTime z=Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault());
            if(!z.toLocalDate().equals(date))continue;
            float x=timeX(z,left,right);
            int color="auto".equals(m.type)?Color.rgb(153,94,220):Color.rgb(46,165,113);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.4f*d);
            paint.setPathEffect(new DashPathEffect(new float[]{4*d,4*d},0));
            paint.setColor(color);
            canvas.drawLine(x,top,x,bottom,paint);
            paint.setPathEffect(null);

            // Special schedule points keep their distinct colored circles.
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x,top+6*d,3.2f*d,paint);
        }
    }

    private void drawEvents(Canvas canvas,LocalDate date,float left,float right,float top,float bottom,float d){
        for(UsageHistory.Event e:events){
            ZonedDateTime z=Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault());
            if(!z.toLocalDate().equals(date))continue;
            float x=timeX(z,left,right);
            int color=e.type.contains("banked")?Color.rgb(230,176,45):
                    e.type.contains("global")?Color.rgb(214,65,65):Color.rgb(120,126,136);

            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x,top+6*d,4*d,paint);
        }
    }

    private void drawLegend(Canvas canvas,float y,float d,int fg){
        paint.setTextSize(8.5f*d);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setStyle(Paint.Style.FILL);

        float x=5*d;
        x=legend(canvas,x,y,d,Color.rgb(47,128,237),"5h");
        x=legend(canvas,x,y,d,Color.rgb(232,145,52),"週");
        x=legend(canvas,x,y,d,Color.rgb(46,165,113),"錨點");
        x=legend(canvas,x,y,d,Color.rgb(153,94,220),"自動");
        legend(canvas,x,y,d,Color.rgb(214,65,65),"Reset");
    }

    private float legend(Canvas canvas,float x,float y,float d,int color,String label){
        paint.setColor(color);
        canvas.drawCircle(x,y-3*d,2.5f*d,paint);
        paint.setColor(dark?Color.rgb(210,214,220):Color.rgb(80,85,94));
        canvas.drawText(label,x+5*d,y,paint);
        return x+7*d+paint.measureText(label)+8*d;
    }

    private void drawSampleTooltip(Canvas canvas,UsageHistory.Sample s,float d,int fg){
        double p=100-Math.max(0,Math.min(100,s.primaryUsed));
        double w=100-Math.max(0,Math.min(100,s.weeklyUsed));
        String text=String.format(Locale.TAIWAN,"%s  5h %.0f%% · 週 %.0f%%",clock(s.at),p,w);
        drawTooltipBox(canvas,text,d,fg,Color.rgb(47,128,237));
    }

    private void drawEventTooltip(Canvas canvas,UsageHistory.Event e,float d,int fg){
        String text=clock(e.at)+"  "+e.label;
        drawTooltipBox(canvas,text,d,fg,Color.rgb(214,65,65));
    }

    private void drawTooltipBox(Canvas canvas,String text,float d,int fg,int accent){
        float top=4*d,legendH=24*d;
        float bandH=(getHeight()-top-legendH)/2f;
        float bandTop=selectedY;
        float bandBottom=bandTop+bandH-16*d;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f*d);
        paint.setColor(accent);
        canvas.drawLine(selectedX,bandTop,selectedX,bandBottom,paint);

        paint.setTextSize(10*d);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        float pad=6*d;
        float w=Math.min(getWidth()-10*d,paint.measureText(text)+pad*2);
        float x=Math.max(5*d,Math.min(getWidth()-w-5*d,selectedX-w/2f));
        float y=bandTop+3*d;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(dark?Color.rgb(48,52,60):Color.WHITE);
        canvas.drawRoundRect(x,y,x+w,y+24*d,6*d,6*d,paint);
        paint.setColor(fg);
        canvas.drawText(text,x+pad,y+16*d,paint);
    }

    private String clock(long ms){
        ZonedDateTime z=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        return String.format(Locale.TAIWAN,"%02d:%02d",z.getHour(),z.getMinute());
    }

    private float timeXForDay(long at,LocalDate date,float left,float right){
        ZoneId zone=ZoneId.systemDefault();
        long dayStart=date.atStartOfDay(zone).toInstant().toEpochMilli();
        long dayEnd=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        double p=(at-dayStart)/(double)(dayEnd-dayStart);
        p=Math.max(0,Math.min(1,p));
        return (float)(left+(right-left)*p);
    }

    private float yFor(double remaining,float top,float bottom){
        double p=Math.max(0,Math.min(100,remaining))/100.0;
        return (float)(bottom-(bottom-top)*p);
    }

    private float timeX(ZonedDateTime z,float left,float right){
        float minute=z.getHour()*60+z.getMinute()+z.getSecond()/60f;
        return left+(right-left)*minute/(24f*60f);
    }
}
