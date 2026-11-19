package il.btchat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** תמונת פרופיל עגולה, או עיגול צבעוני עם האות הראשונה (כמו בגוגל צ'אט). */
final class AvatarView extends View {
    private static final int[] COLORS = {0xFF1A73E8, 0xFF188038, 0xFFD93025, 0xFFE37400, 0xFF9334E6, 0xFF007B83, 0xFFC5221F, 0xFF3367D6, 0xFF0B8043, 0xFFAD1457};
    private String url = "", name = "";
    private boolean group;
    private Bitmap bmp;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix m = new Matrix();

    AvatarView(Context c) { super(c); }

    void set(String name, String url, boolean group) {
        this.name = name == null ? "" : name;
        this.url = url == null ? "" : url;
        this.group = group;
        bmp = Pics.avatar(getContext(), this.url);
        invalidate();
    }

    /** נקרא כשהגיעו תמונות חדשות מהמחשב. */
    void refresh() {
        if (bmp == null && url.length() > 0) {
            bmp = Pics.avatar(getContext(), url);
            if (bmp != null) invalidate();
        }
    }

    static int colorFor(String s) {
        return COLORS[(s.hashCode() & 0x7FFFFFFF) % COLORS.length];
    }

    /** מרחבים: ריבוע מעוגל (כמו בגוגל צ'אט). אנשים: עיגול. */
    private void shape(Canvas c, float w, float h, float r) {
        if (group) {
            float s = Math.min(w, h);
            c.drawRoundRect(new android.graphics.RectF((w - s) / 2f, (h - s) / 2f, (w + s) / 2f, (h + s) / 2f), s * 0.26f, s * 0.26f, p);
        } else c.drawCircle(w / 2f, h / 2f, r, p);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
        if (bmp != null) {
            BitmapShader sh = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
            m.setScale(scale, scale);
            m.postTranslate((w - bmp.getWidth() * scale) / 2f, (h - bmp.getHeight() * scale) / 2f);
            sh.setLocalMatrix(m);
            p.setShader(sh);
            shape(c, w, h, r);
            p.setShader(null);
            return;
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(group ? Th.SURFACE2 : name.length() > 0 ? colorFor(name) : Th.SURFACE2);
        if (group && name.length() > 0) p.setColor(Th.alpha(colorFor(name), 0x33));
        shape(c, w, h, r);
        if (group && name.length() > 0) {
            // מרחב בלי תמונה: האות הראשונה בצבע על רקע בהיר
            String l = initial(name);
            if (l.length() > 0) {
                p.setColor(Th.dark ? 0xFFFFFFFF : colorFor(name));
                p.setTextSize(r * 0.95f);
                p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                p.setTextAlign(Paint.Align.CENTER);
                Paint.FontMetrics fm0 = p.getFontMetrics();
                c.drawText(l, w / 2f, h / 2f - (fm0.ascent + fm0.descent) / 2f, p);
                return;
            }
        }
        String letter = initial(name);
        if (letter.length() == 0 || group && name.length() == 0) {
            Glyph.draw(c, group ? Glyph.GROUP : Glyph.PERSON, w / 2f, h / 2f, r * 1.25f, name.length() > 0 ? 0xFFFFFFFF : Th.SUB);
            return;
        }
        p.setColor(0xFFFFFFFF);
        p.setTextSize(r * 0.95f);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(letter, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, p);
    }

    private static String initial(String s) {
        s = s.trim();
        for (int i = 0; i < s.length(); i++) {
            int cp = s.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return new String(Character.toChars(Character.toUpperCase(cp)));
        }
        return "";
    }
}
