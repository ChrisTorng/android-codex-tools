package com.christorng.androidcodextools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

final class QuotaProgressView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private final Path markerPath=new Path();
    private float used=0f;
    private float pace=-1f;
    private String paceLabel="";
    private float[] tickPositions=new float[0];

    QuotaProgressView(Context c){super(c);}
    QuotaProgressView(Context c, AttributeSet a){super(c,a);}

    void setProgress(double usedPercent,double pacePercent){
        setProgress(usedPercent,pacePercent,"");
    }

    void setProgress(double usedPercent,double pacePercent,String label){
        setProgress(usedPercent,pacePercent,label,new float[0]);
    }

    void setProgress(double usedPercent,double pacePercent,String label,float[] ticks){
        used=(float)Math.max(0,Math.min(100,usedPercent));
        pace=pacePercent<0?-1f:(float)Math.max(0,Math.min(100,pacePercent));
        paceLabel=label==null?"":label;
        tickPositions=ticks==null?new float[0]:ticks.clone();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float density=getResources().getDisplayMetrics().density;
        float labelArea=pace>=0?14f*density:0f;
        float barTop=labelArea;
        float barBottom=getHeight()-1f;
        float barHeight=Math.max(6f*density,barBottom-barTop);
        float radius=barHeight/2f;

        rect.set(0,barTop,getWidth(),barBottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(225,229,235));
        canvas.drawRoundRect(rect,radius,radius,paint);

        if(tickPositions.length>0){
            float cy=(barTop+barBottom)/2f;
            float r=Math.max(1.8f*density,barHeight*0.12f);
            for(float p:tickPositions){
                if(p<=0f||p>=100f)continue;
                float x=getWidth()*p/100f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(155,255,255,255));
                canvas.drawCircle(x,cy,r,paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f,density));
                paint.setColor(Color.argb(95,30,35,45));
                canvas.drawCircle(x,cy,r,paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        float fill=getWidth()*used/100f;
        float paceX=pace<0?0:getWidth()*pace/100f;

        if(fill>0){
            float blueEnd=pace>=0?Math.min(fill,paceX):fill;
            if(blueEnd>0){
                paint.setColor(Color.rgb(47,128,237));
                drawFill(canvas,blueEnd,barTop,barBottom,radius,paint);
            }

            if(pace>=0 && fill>paceX){
                float excess=used-pace;
                paint.setColor(excessColor(excess));
                RectF excessRect=new RectF(paceX,barTop,fill,barBottom);
                canvas.save();
                canvas.clipRect(paceX,barTop,fill,barBottom);
                canvas.drawRoundRect(new RectF(0,barTop,Math.max(fill,barHeight),barBottom),radius,radius,paint);
                canvas.restore();
            }
        }

        if(pace>=0){
            float x=Math.max(1f,Math.min(getWidth()-1f,paceX));

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f,2f*density));
            paint.setColor(Color.argb(90,0,0,0));
            canvas.drawLine(x+1f,barTop,x+1f,barBottom,paint);
            paint.setColor(Color.WHITE);
            canvas.drawLine(x,barTop,x,barBottom,paint);

            markerPath.reset();
            float arrow=4f*density;
            markerPath.moveTo(x,barTop);
            markerPath.lineTo(x-arrow,barTop-arrow);
            markerPath.lineTo(x+arrow,barTop-arrow);
            markerPath.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawPath(markerPath,paint);

            if(!paceLabel.isEmpty()){
                paint.setTextSize(10f*density);
                paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                paint.setColor(Color.rgb(82,87,96));
                float width=paint.measureText(paceLabel);
                float tx=Math.max(0,Math.min(getWidth()-width,x-width/2f));
                canvas.drawText(paceLabel,tx,10.5f*density,paint);
            }
        }
    }

    private static void drawFill(Canvas canvas,float end,float top,float bottom,float radius,Paint paint){
        if(end<=0)return;
        canvas.save();
        canvas.clipRect(0,top,end,bottom);
        canvas.drawRoundRect(new RectF(0,top,Math.max(end,bottom-top),bottom),radius,radius,paint);
        canvas.restore();
    }

    private static int excessColor(float excessPercent){
        float t=Math.max(0f,Math.min(1f,excessPercent/30f));
        if(t<0.45f){
            float u=t/0.45f;
            return blend(Color.rgb(49,166,92),Color.rgb(235,157,50),u);
        }
        float u=(t-0.45f)/0.55f;
        return blend(Color.rgb(235,157,50),Color.rgb(211,58,58),u);
    }

    private static int blend(int a,int b,float t){
        int r=(int)(Color.red(a)+(Color.red(b)-Color.red(a))*t);
        int g=(int)(Color.green(a)+(Color.green(b)-Color.green(a))*t);
        int bl=(int)(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t);
        return Color.rgb(r,g,bl);
    }
}
