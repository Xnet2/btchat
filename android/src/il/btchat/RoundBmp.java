package il.btchat;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** תמונה בפינות מעוגלות (אפשר פינה שונה לכל צד) שממלאת את המקום — חיתוך מהמרכז. */
final class RoundBmp extends Drawable {
    private final Bitmap bmp;
    private final float tl, tr, br, bl;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();

    RoundBmp(Bitmap bmp, float radius) { this(bmp, radius, radius, radius, radius); }

    /** פינות: שמאל-עליון, ימין-עליון, ימין-תחתון, שמאל-תחתון. */
    RoundBmp(Bitmap bmp, float tl, float tr, float br, float bl) {
        this.bmp = bmp;
        this.tl = tl; this.tr = tr; this.br = br; this.bl = bl;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        BitmapShader sh = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = Math.max(b.width() / (float) bmp.getWidth(), b.height() / (float) bmp.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(b.left + (b.width() - bmp.getWidth() * scale) / 2f, b.top + (b.height() - bmp.getHeight() * scale) / 2f);
        sh.setLocalMatrix(m);
        paint.setShader(sh);
        path.reset();
        path.addRoundRect(new RectF(b), new float[]{tl, tl, tr, tr, br, br, bl, bl}, Path.Direction.CW);
    }

    @Override
    public void draw(Canvas c) { c.drawPath(path, paint); }

    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
