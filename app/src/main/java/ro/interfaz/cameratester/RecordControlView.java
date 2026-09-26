package ro.interfaz.cameratester;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Large accessible target with the requested small, solid recording symbol. */
final class RecordControlView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean recording;
    RecordControlView(Context context) {
        super(context); setClickable(true); setFocusable(true);
        setMinimumWidth(dp(88)); setMinimumHeight(dp(64)); setState("IDLE");
    }
    void setState(String state) {
        recording = "RECORDING".equals(state);
        setContentDescription(recording ? "Stop recording" : "PREPARING".equals(state)
                ? "Preparing recording" : "STOPPING".equals(state) ? "Finishing recording" : "Start recording");
        invalidate();
    }
    boolean showsStop() { return recording; }
    int symbolColor() { return recording ? Color.RED : Color.WHITE; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(dp(88), widthSpec), resolveSize(dp(64), heightSpec));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(isPressed() ? Color.rgb(61,79,88) : Color.rgb(34,48,58));
        canvas.drawRoundRect(dp(2),dp(2),getWidth()-dp(2),getHeight()-dp(2),dp(12),dp(12),paint);
        paint.setColor(symbolColor());
        float x=getWidth()/2f, y=getHeight()/2f, radius=dp(8);
        if (recording) canvas.drawRect(x-radius,y-radius,x+radius,y+radius,paint);
        else canvas.drawCircle(x,y,radius,paint);
    }
    @Override public boolean performClick() { return super.performClick(); }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.Button");
    }
}
