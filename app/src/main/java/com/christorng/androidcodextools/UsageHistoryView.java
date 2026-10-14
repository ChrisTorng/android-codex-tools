package com.christorng.androidcodextools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

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

    UsageHistoryView(Context c){super(c);}

    void setData(List<UsageHistory.Sample> s,List<UsageHistory.Event> e,List<Marker> m,boolean darkMode){
        samples=s==null?new ArrayList<>():s;
        events=e==null?new ArrayList<>():e;
        markers=m==null?new ArrayList<>():m;
        dark=darkMode;
        invalidate();
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
        LocalDate[] days={today.minusDays(1),today,today.plusDays(1)};
        String[] labels={"昨天","今天","明天"};

        float top=4*d;
        float legendH=24*d;
        float bandH=(getHeight()-top-legendH)/3f;

        for(int i=0;i<3;i++){
            float y0=top+i*bandH;
            float y1=y0+bandH;
            drawBand(canvas,days[i],labels[i],y0,y1,d,fg,muted,grid);
        }

        drawLegend(canvas,getHeight()-7*d,d,fg);
    }

    private void drawBand(Canvas canvas,LocalDate date,String label,float top,float bottom,float d,
                          int fg,int muted,int grid){
        float left=28*d;
        float right=getWidth()-5*d;
        float chartTop=top+3*d;
        float chartBottom=bottom-16*d;
        float h=Math.max(20*d,chartBottom-chartTop);

        // Day label is overlaid inside the plot.
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(11*d);
        paint.setColor(Color.argb(205,Color.red(fg),Color.green(fg),Color.blue(fg)));
        canvas.drawText(label,left+4*d,chartTop+13*d,paint);

        // 100 / 50 / 0 remaining scale.
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(8*d);
        for(int p:new int[]{100,50,0}){
            float y=yFor(p,chartTop,chartBottom);
            paint.setColor(grid);
            canvas.drawLine(left,y,right,y,paint);
            paint.setColor(muted);
            String s=String.valueOf(p);
            canvas.drawText(s,1*d,y+3*d,paint);
        }

        // Time ticks.
        String[] ticks={"00","06","12","18","24"};
        for(int i=0;i<5;i++){
            float x=left+(right-left)*i/4f;
            paint.setColor(grid);
            paint.setStrokeWidth(1f);
            canvas.drawLine(x,chartTop,x,chartBottom,paint);
            paint.setColor(muted);
            float tw=paint.measureText(ticks[i]);
            canvas.drawText(ticks[i],x-tw/2f,chartBottom+11*d,paint);
        }

        drawSeries(canvas,date,left,right,chartTop,chartBottom,d,true,
                Color.rgb(47,128,237),5*60*60_000L);
        drawSeries(canvas,date,left,right,chartTop,chartBottom,d,false,
                Color.rgb(232,145,52),7*24*60*60_000L);

        drawMarkers(canvas,date,left,right,chartTop,chartBottom,d);
        drawEvents(canvas,date,left,right,chartTop,chartBottom,d);
    }

    private void drawSeries(Canvas canvas,LocalDate date,float left,float right,float top,float bottom,
                            float d,boolean primary,int color,long duration){
        List<float[]> pts=new ArrayList<>();

        for(UsageHistory.Sample s:samples){
            ZonedDateTime z=Instant.ofEpochMilli(s.at).atZone(ZoneId.systemDefault());
            if(!z.toLocalDate().equals(date))continue;
            if(primary&&!s.primaryConfirmed)continue;

            double used=primary?s.primaryUsed:s.weeklyUsed;
            double remaining=100.0-Math.max(0,Math.min(100,used));
            long reset=primary?s.primaryReset:s.weeklyReset;
            double ideal=Double.NaN;
            if(reset>0){
                long start=reset-duration;
                ideal=Math.max(0,Math.min(100,(reset-s.at)*100.0/duration));
                if(s.at<start-60_000L||s.at>reset+60_000L)ideal=Double.NaN;
            }

            pts.add(new float[]{
                    timeX(z,left,right),
                    yFor(remaining,top,bottom),
                    (float)remaining,
                    Double.isNaN(ideal)?Float.NaN:yFor(ideal,top,bottom)
            });
        }

        if(pts.isEmpty())return;

        // Actual area.
        Path area=new Path();
        area.moveTo(pts.get(0)[0],bottom);
        for(float[] p:pts)area.lineTo(p[0],p[1]);
        area.lineTo(pts.get(pts.size()-1)[0],bottom);
        area.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(dark?42:32,Color.red(color),Color.green(color),Color.blue(color)));
        canvas.drawPath(area,paint);

        // Ideal line from the observed window geometry.
        path.reset();
        boolean idealStarted=false;
        for(float[] p:pts){
            if(Float.isNaN(p[3])){idealStarted=false;continue;}
            if(!idealStarted){path.moveTo(p[0],p[3]);idealStarted=true;}
            else path.lineTo(p[0],p[3]);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f*d);
        paint.setPathEffect(new DashPathEffect(new float[]{5*d,4*d},0));
        paint.setColor(Color.argb(170,Color.red(color),Color.green(color),Color.blue(color)));
        canvas.drawPath(path,paint);
        paint.setPathEffect(null);

        // Actual line.
        path.reset();
        for(int i=0;i<pts.size();i++){
            float[] p=pts.get(i);
            if(i==0)path.moveTo(p[0],p[1]); else path.lineTo(p[0],p[1]);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.1f*d);
        paint.setColor(color);
        canvas.drawPath(path,paint);

        // Measured points. Hollow point = observed remaining did not change.
        for(int i=0;i<pts.size();i++){
            float[] p=pts.get(i);
            boolean unchanged=i>0&&Math.abs(p[2]-pts.get(i-1)[2])<0.01f;
            paint.setStyle(unchanged?Paint.Style.STROKE:Paint.Style.FILL);
            paint.setStrokeWidth(1.4f*d);
            paint.setColor(color);
            canvas.drawCircle(p[0],p[1],2.3f*d,paint);
        }
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

            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x,top+6*d,3*d,paint);
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
            path.reset();
            float y=top+2*d;
            path.moveTo(x,y);
            path.lineTo(x-4*d,y+8*d);
            path.lineTo(x+4*d,y+8*d);
            path.close();
            canvas.drawPath(path,paint);
        }
    }

    private void drawLegend(Canvas canvas,float y,float d,int fg){
        paint.setTextSize(8.5f*d);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setStyle(Paint.Style.FILL);

        float x=5*d;
        x=legend(canvas,x,y,d,Color.rgb(47,128,237),"5h 剩餘");
        x=legend(canvas,x,y,d,Color.rgb(232,145,52),"週剩餘");
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

    private float yFor(double remaining,float top,float bottom){
        double p=Math.max(0,Math.min(100,remaining))/100.0;
        return (float)(bottom-(bottom-top)*p);
    }

    private float timeX(ZonedDateTime z,float left,float right){
        float minute=z.getHour()*60+z.getMinute()+z.getSecond()/60f;
        return left+(right-left)*minute/(24f*60f);
    }
}
