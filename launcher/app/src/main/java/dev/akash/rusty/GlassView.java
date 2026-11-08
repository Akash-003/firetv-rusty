package dev.akash.rusty;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** One translucent "glass" highlight that springs from tile to tile. No blur: fill + sheen + hairline. */
public class GlassView extends View {

    /** Fills {@code out} with the target's bounds in this view's coordinates; returns corner radius, or -1 if not on screen. */
    public interface Target { float bounds(RectF out); }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sheenMatrix = new Matrix();
    private final LinearGradient sheenShader =   // unit height, scaled per frame
            new LinearGradient(0, 0, 0, 1, new int[]{0x2EFFFFFF, 0x0AFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
    private final RectF from = new RectF(), to = new RectF(), cur = new RectF();
    private float fromRadius, curRadius;
    private float t = 1f;
    private Target target;
    private ValueAnimator anim;

    public GlassView(Context c, AttributeSet a) {
        super(c, a);
        fill.setColor(0x1AFFFFFF);
        sheen.setShader(sheenShader);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(c.getResources().getDisplayMetrics().density * 1.5f);
        stroke.setColor(0x66FFFFFF);
    }

    public void moveTo(Target next, boolean animate) {
        if (anim != null) anim.cancel();
        from.set(cur);
        fromRadius = curRadius;
        target = next;
        if (!animate || cur.isEmpty()) {
            t = 1f;
        } else {
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(340);
            anim.setInterpolator(new OvershootInterpolator(1.1f)); // ponytail: overshoot reads as a spring; real SpringAnimation if it ever feels off
            anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = target == null ? -1 : target.bounds(to);
        if (r < 0) { cur.setEmpty(); return; }
        if (from.isEmpty()) {
            cur.set(to);
            curRadius = r;
        } else {
            cur.set(lerp(from.left, to.left), lerp(from.top, to.top), lerp(from.right, to.right), lerp(from.bottom, to.bottom));
            curRadius = Math.max(0, lerp(fromRadius, r));
        }
        canvas.drawRoundRect(cur, curRadius, curRadius, fill);
        sheenMatrix.setScale(1, cur.height());
        sheenMatrix.postTranslate(0, cur.top);
        sheenShader.setLocalMatrix(sheenMatrix);
        canvas.drawRoundRect(cur, curRadius, curRadius, sheen);
        canvas.drawRoundRect(cur, curRadius, curRadius, stroke);
    }

    private float lerp(float a, float b) { return a + (b - a) * t; }
}
