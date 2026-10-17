package com.watchreader.fanqie;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.ScrollView;

/** Route the watch's AXIS_SCROLL at Activity level, even when a button owns focus. */
public final class CrownScroller {
    private static final float DEADZONE=.015f,STEP=.10f;
    private float energy;private int direction;private long last;
    public void reset(){energy=0;direction=0;last=0;}
    public boolean handle(MotionEvent event,ScrollView view){
        if(event==null||event.getActionMasked()!=MotionEvent.ACTION_SCROLL||view==null||!view.isShown())return false;
        float raw=event.getAxisValue(MotionEvent.AXIS_SCROLL);
        if(Float.isNaN(raw)||Float.isInfinite(raw)||Math.abs(raw)<DEADZONE)return false;
        long now=SystemClock.uptimeMillis();int next=raw<0?1:-1;
        if(now-last>600||next!=direction)energy=0;
        last=now;direction=next;energy+=Math.min(.5f,Math.abs(raw));
        int steps=(int)(energy/STEP);if(steps==0)return true;
        energy-=steps*STEP;
        int pixels=Math.max(1,Math.round(40*view.getResources().getDisplayMetrics().density));
        view.scrollBy(0,next*Math.min(3,steps)*pixels);
        return true;
    }
}
