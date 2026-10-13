package com.christorng.androidcodextools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
        int bg=dark?Color.rgb(28,30,34):Color.rgb(255,255,255);
        int fg=dark?Color.rgb(232,234,238):Color.rgb(45,49,56);
        int muted=dark?Color.rgb(150,156,166):Color.rgb(112,118,128);
        int grid=dark?Color.rgb(60,64,72):Color.rgb(226,229,234);

        canvas.drawColor(bg);
        LocalDate today=LocalDate.now();
        LocalDate[] days={today.minusDays(1),today,today.plusDays(1)};
        String[] labels={"昨天","今天","明天"};

        float top=8*d;
        float bottomLegend=34*d;
        float bandH=(getHeight()-top-bottomLegend)/3f;

        for(int i=0;i<3;i++){
            float y0=top+i*bandH;
            float y1=y0+bandH;
            drawBand(canvas,days[i],labels[i],y0,y1,d,fg,muted,grid);
        }

        drawLegend(canvas,getHeight()-24*d,d,fg,muted);
    }

    private void drawBand(Canvas canvas,LocalDate date,String label,float top,float bottom,float d,
                          int fg,int muted,int grid){
        float left=46*d;
        float right=getWidth()-12*d;
        float chartTop=top+27*d;
        float chartBottom=bottom-24*d;
        float chartH=Math.max(20*d,chartBottom-chartTop);

        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(14*d);
        paint.setColor(fg);
        canvas.drawText(label,8*d,top+18*d,paint);

        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(9*d);
        paint.setColor(muted);
        String[] ticks={"00","06","12","18","24"};
        for(int i=0;i<5;i++){
            float x=left+(right-left)*i/4f;
            paint.setStrokeWidth(1f);
            paint.setColor(grid);
            canvas.drawLine(x,chartTop,x,chartBottom,paint);
            paint.setColor(muted);
            float tw=paint.measureText(ticks[i]);
            canvas.drawText(ticks[i],x-tw/2f,chartBottom+14*d,paint);
        }

        for(int p=0;p<=100;p+=50){
            float y=chartBottom-chartH*p/100f;
            paint.setColor(grid);
            canvas.drawLine(left,y,right,y,paint);
        }

        drawSamples(canvas,date,left,right,chartTop,chartBottom,d);
        drawMarkers(canvas,date,left,right,chartTop,chartBottom,d);
        drawEvents(canvas,date,left,right,chartTop,chartBottom,d);
    }

    private void drawSamples(Canvas canvas,LocalDate date,float left,float right,float top,float bottom,float d){
        drawSampleLine(canvas,date,left,right,top,bottom,d,true,Color.rgb(47,128,237));
        drawSampleLine(canvas,date,left,right,top,bottom,d,false,Color.rgb(232,145,52));
    }

    private void drawSampleLine(Canvas canvas,LocalDate date,float left,float right,float top,float bottom,float d,
                                boolean primary,int color){
        path.reset();
        boolean started=false;
        float lastX=0,lastY=0;
        for(UsageHistory.Sample s:samples){
            ZonedDateTime z=Instant.ofEpochMilli(s.at).atZone(ZoneId.systemDefault());
            if(!z.toLocalDate().equals(date))continue;
            if(primary && !s.primaryConfirmed)continue;

            float x=timeX(z,left,right);
            double value=primary?s.primaryUsed:s.weeklyUsed;
            float y=(float)(bottom-(bottom-top)*Math.max(0,Math.min(100,value))/100.0);
            if(!started){path.moveTo(x,y);started=true;} else path.lineTo(x,y);
            lastX=x;lastY=y;
        }
        if(!started)return;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f*d);
        paint.setPathEffect(null);
        paint.setColor(color);
        canvas.drawPath(path,paint);

        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(lastX,lastY,2.8f*d,paint);
    }

    private void drawMarkers(Canvas canvas,LocalDate date,float left,float right,float top,float bottom,float d){
        for(Marker m:markers){
            ZonedDateTime z=Instant.ofEpochMilli(m.at).atZone(ZoneId.systemDefault());
            if(!z.toLocalDate().equals(date))continue;
            float x=timeX(z,left,right);
            int color="auto".equals(m.type)?Color.rgb(153,94,220):Color.rgb(46,165,113);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f*d);
            paint.setPathEffect(new DashPathEffect(new float[]{5*d,4*d},0));
            paint.setColor(color);
            canvas.drawLine(x,top,x,bottom,paint);
            paint.setPathEffect(null);

            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x,top+7*d,3.2f*d,paint);
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
            float y=top+3*d;
            path.moveTo(x,y);
            path.lineTo(x-4*d,y+8*d);
            path.lineTo(x+4*d,y+8*d);
            path.close();
            canvas.drawPath(path,paint);
        }
    }

    private void drawLegend(Canvas canvas,float y,float d,int fg,int muted){
        paint.setTextSize(10*d);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setStyle(Paint.Style.FILL);

        float x=10*d;
        x=legend(canvas,x,y,d,Color.rgb(47,128,237),"5h");
        x=legend(canvas,x,y,d,Color.rgb(232,145,52),"週");
        x=legend(canvas,x,y,d,Color.rgb(46,165,113),"錨點(虛線)");
        x=legend(canvas,x,y,d,Color.rgb(153,94,220),"自動");
        legend(canvas,x,y,d,Color.rgb(214,65,65),"Reset");
    }

    private float legend(Canvas canvas,float x,float y,float d,int color,String label){
        paint.setColor(color);
        canvas.drawCircle(x,y-3*d,3*d,paint);
        paint.setColor(dark?Color.rgb(210,214,220):Color.rgb(80,85,94));
        canvas.drawText(label,x+6*d,y,paint);
        return x+8*d+paint.measureText(label)+10*d;
    }

    private float timeX(ZonedDateTime z,float left,float right){
        float minute=z.getHour()*60+z.getMinute()+z.getSecond()/60f;
        return left+(right-left)*minute/(24f*60f);
    }
}
