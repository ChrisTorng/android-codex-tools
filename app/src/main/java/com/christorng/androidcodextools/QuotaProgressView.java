package com.christorng.androidcodextools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

final class QuotaProgressView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private float used=0f;
    private float pace=-1f;

    QuotaProgressView(Context c){super(c);}
    QuotaProgressView(Context c, AttributeSet a){super(c,a);}

    void setProgress(double usedPercent,double pacePercent){
        used=(float)Math.max(0,Math.min(100,usedPercent));
        pace=pacePercent<0?-1f:(float)Math.max(0,Math.min(100,pacePercent));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float radius=getHeight()/2f;
        rect.set(0,0,getWidth(),getHeight());

        paint.setColor(Color.rgb(225,229,235));
        canvas.drawRoundRect(rect,radius,radius,paint);

        float fill=getWidth()*used/100f;
        if(fill>0){
            paint.setColor(used>=90?Color.rgb(211,70,70):used>=70?Color.rgb(224,143,43):Color.rgb(47,128,237));
            RectF usedRect=new RectF(0,0,Math.max(fill,getHeight()),getHeight());
            canvas.save();
            canvas.clipRect(0,0,fill,getHeight());
            canvas.drawRoundRect(usedRect,radius,radius,paint);
            canvas.restore();
        }

        if(pace>=0){
            float x=getWidth()*pace/100f;
            paint.setStrokeWidth(Math.max(2f,getResources().getDisplayMetrics().density*2f));
            paint.setColor(Color.argb(90,0,0,0));
            canvas.drawLine(x+1,1,x+1,getHeight()-1,paint);
            paint.setColor(Color.WHITE);
            canvas.drawLine(x,1,x,getHeight()-1,paint);
        }
    }
}
