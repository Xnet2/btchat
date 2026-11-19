package il.btchat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** אייקונים מצוירים בקוד (בסגנון Material Symbols). */
final class Glyph extends View {
    static final int GEAR = 0, BACK = 1, SEARCH = 2, CLOSE = 3, SEND = 4, CHECK = 5, BELL_OFF = 6, BELL = 7,
            IMAGE = 8, MORE = 9, THREAD = 10, EMOJI = 11, COPY = 12, BT = 13, PERSON = 14, GROUP = 15,
            REFRESH = 16, CHEVRON = 17, MOON = 18, DONE_ALL = 19, ERROR = 20, CLOCK = 21,
            MENU = 22, HOME = 23, CHATS = 24, SPACES = 25, REPLY = 26, ADD_REACTION = 27, HOME_FILL = 28, CHATS_FILL = 29,
            PLUS = 30, FORMAT = 31, EDIT = 32, DELETE = 33, QUOTE = 34, MIC = 35;
    private int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color;
    private float weight = 1f;

    Glyph(Context c, int kind, int color) {
        super(c);
        this.kind = kind;
        this.color = color;
    }

    void setColor(int c) { color = c; invalidate(); }
    void setKind(int k) { kind = k; invalidate(); }
    void setWeight(float w) { weight = w; invalidate(); }

    static void draw(Canvas cv, int kind, float cx, float cy, float s, int color) {
        drawWith(new Paint(Paint.ANTI_ALIAS_FLAG), cv, kind, cx, cy, s, color, 1f);
    }

    private static void drawWith(Paint p, Canvas cv, int kind, float cx, float cy, float s, int color, float weight) {
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.085f * weight);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        switch (kind) {
            case GEAR: {
                float ro = s * 0.44f, ri = s * 0.33f;
                Path g = new Path();
                for (int i = 0; i < 8; i++) {
                    double base = Math.toRadians(i * 45 - 90);
                    double a0 = base - Math.toRadians(14), a1 = base - Math.toRadians(8), a2 = base + Math.toRadians(8), a3 = base + Math.toRadians(14);
                    float x0 = cx + (float) Math.cos(a0) * ri, y0 = cy + (float) Math.sin(a0) * ri;
                    if (i == 0) g.moveTo(x0, y0); else g.lineTo(x0, y0);
                    g.lineTo(cx + (float) Math.cos(a1) * ro, cy + (float) Math.sin(a1) * ro);
                    g.lineTo(cx + (float) Math.cos(a2) * ro, cy + (float) Math.sin(a2) * ro);
                    g.lineTo(cx + (float) Math.cos(a3) * ri, cy + (float) Math.sin(a3) * ri);
                    RectF in = new RectF(cx - ri, cy - ri, cx + ri, cy + ri);
                    g.arcTo(in, (float) Math.toDegrees(a3), 17f, false);
                }
                g.close();
                cv.drawPath(g, p);
                cv.drawCircle(cx, cy, s * 0.135f, p);
                break;
            }
            case BACK: {   // חץ ימינה (חזרה בעברית)
                cv.drawLine(cx - s * 0.32f, cy, cx + s * 0.32f, cy, p);
                cv.drawLine(cx + s * 0.34f, cy, cx + s * 0.06f, cy - s * 0.28f, p);
                cv.drawLine(cx + s * 0.34f, cy, cx + s * 0.06f, cy + s * 0.28f, p);
                break;
            }
            case SEARCH: {
                cv.drawCircle(cx - s * 0.07f, cy - s * 0.07f, s * 0.26f, p);
                cv.drawLine(cx + s * 0.13f, cy + s * 0.13f, cx + s * 0.36f, cy + s * 0.36f, p);
                break;
            }
            case CLOSE: {
                cv.drawLine(cx - s * 0.26f, cy - s * 0.26f, cx + s * 0.26f, cy + s * 0.26f, p);
                cv.drawLine(cx + s * 0.26f, cy - s * 0.26f, cx - s * 0.26f, cy + s * 0.26f, p);
                break;
            }
            case SEND: {   // חץ השליחה של Material ("send"), מלא, פונה שמאלה (קדימה בעברית)
                p.setStyle(Paint.Style.FILL);
                p.setStrokeJoin(Paint.Join.ROUND);
                Path t = new Path();
                t.moveTo(cx - s * 0.40f, cy);                    // חוד
                t.lineTo(cx + s * 0.40f, cy - s * 0.36f);        // פינה עליונה
                t.lineTo(cx + s * 0.40f, cy - s * 0.08f);
                t.lineTo(cx - s * 0.18f, cy);                    // קו האמצע
                t.lineTo(cx + s * 0.40f, cy + s * 0.08f);
                t.lineTo(cx + s * 0.40f, cy + s * 0.36f);        // פינה תחתונה
                t.close();
                cv.drawPath(t, p);
                break;
            }
            case MIC: {   // מיקרופון
                RectF cap = new RectF(cx - s * 0.13f, cy - s * 0.38f, cx + s * 0.13f, cy + s * 0.1f);
                p.setStyle(Paint.Style.FILL);
                cv.drawRoundRect(cap, s * 0.13f, s * 0.13f, p);
                p.setStyle(Paint.Style.STROKE);
                cv.drawArc(new RectF(cx - s * 0.27f, cy - s * 0.2f, cx + s * 0.27f, cy + s * 0.24f), 0, 180, false, p);
                cv.drawLine(cx, cy + s * 0.24f, cx, cy + s * 0.38f, p);
                break;
            }
            case CHECK: {
                cv.drawLine(cx - s * 0.30f, cy, cx - s * 0.08f, cy + s * 0.22f, p);
                cv.drawLine(cx - s * 0.08f, cy + s * 0.22f, cx + s * 0.32f, cy - s * 0.20f, p);
                break;
            }
            case DONE_ALL: {
                cv.drawLine(cx - s * 0.42f, cy, cx - s * 0.24f, cy + s * 0.2f, p);
                cv.drawLine(cx - s * 0.24f, cy + s * 0.2f, cx + s * 0.1f, cy - s * 0.2f, p);
                cv.drawLine(cx - s * 0.06f, cy + s * 0.12f, cx + s * 0.02f, cy + s * 0.2f, p);
                cv.drawLine(cx + s * 0.02f, cy + s * 0.2f, cx + s * 0.38f, cy - s * 0.2f, p);
                break;
            }
            case BELL:
            case BELL_OFF: {
                Path b = new Path();
                b.moveTo(cx - s * 0.32f, cy + s * 0.22f);
                b.quadTo(cx - s * 0.24f, cy + s * 0.12f, cx - s * 0.24f, cy - s * 0.04f);
                b.quadTo(cx - s * 0.24f, cy - s * 0.32f, cx, cy - s * 0.32f);
                b.quadTo(cx + s * 0.24f, cy - s * 0.32f, cx + s * 0.24f, cy - s * 0.04f);
                b.quadTo(cx + s * 0.24f, cy + s * 0.12f, cx + s * 0.32f, cy + s * 0.22f);
                b.close();
                cv.drawPath(b, p);
                cv.drawLine(cx - s * 0.08f, cy + s * 0.36f, cx + s * 0.08f, cy + s * 0.36f, p);
                if (kind == BELL_OFF) cv.drawLine(cx - s * 0.38f, cy - s * 0.38f, cx + s * 0.38f, cy + s * 0.38f, p);
                break;
            }
            case IMAGE: {   // כמו Material "image": מסגרת מעוגלת, הרים מלאים ושמש
                p.setStrokeWidth(s * 0.075f * weight);
                RectF r = new RectF(cx - s * 0.37f, cy - s * 0.37f, cx + s * 0.37f, cy + s * 0.37f);
                cv.drawRoundRect(r, s * 0.07f, s * 0.07f, p);
                p.setStyle(Paint.Style.FILL);
                Path m = new Path();
                m.moveTo(cx - s * 0.24f, cy + s * 0.22f);
                m.lineTo(cx - s * 0.08f, cy + s * 0.01f);
                m.lineTo(cx + s * 0.03f, cy + s * 0.14f);
                m.lineTo(cx + s * 0.11f, cy + s * 0.05f);
                m.lineTo(cx + s * 0.24f, cy + s * 0.22f);
                m.close();
                cv.drawPath(m, p);
                cv.drawCircle(cx + s * 0.12f, cy - s * 0.13f, s * 0.065f, p);
                break;
            }
            case MORE: {
                p.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; i++) cv.drawCircle(cx, cy + i * s * 0.26f, s * 0.07f, p);
                break;
            }
            case THREAD: {   // בועה עם שורות
                Path b = new Path();
                RectF r = new RectF(cx - s * 0.36f, cy - s * 0.32f, cx + s * 0.36f, cy + s * 0.2f);
                b.addRoundRect(r, s * 0.1f, s * 0.1f, Path.Direction.CW);
                cv.drawPath(b, p);
                cv.drawLine(cx + s * 0.16f, cy + s * 0.2f, cx + s * 0.26f, cy + s * 0.38f, p);
                cv.drawLine(cx - s * 0.18f, cy - s * 0.1f, cx + s * 0.18f, cy - s * 0.1f, p);
                cv.drawLine(cx - s * 0.18f, cy + s * 0.04f, cx + s * 0.06f, cy + s * 0.04f, p);
                break;
            }
            case EMOJI: {
                cv.drawCircle(cx, cy, s * 0.36f, p);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx - s * 0.13f, cy - s * 0.08f, s * 0.05f, p);
                cv.drawCircle(cx + s * 0.13f, cy - s * 0.08f, s * 0.05f, p);
                p.setStyle(Paint.Style.STROKE);
                RectF m = new RectF(cx - s * 0.18f, cy - s * 0.06f, cx + s * 0.18f, cy + s * 0.2f);
                cv.drawArc(m, 20, 140, false, p);
                break;
            }
            case COPY: {
                RectF a = new RectF(cx - s * 0.22f, cy - s * 0.24f, cx + s * 0.3f, cy + s * 0.36f);
                cv.drawRoundRect(a, s * 0.06f, s * 0.06f, p);
                Path b = new Path();
                b.moveTo(cx - s * 0.32f, cy + s * 0.18f);
                b.lineTo(cx - s * 0.32f, cy - s * 0.36f);
                b.lineTo(cx + s * 0.16f, cy - s * 0.36f);
                cv.drawPath(b, p);
                break;
            }
            case BT: {
                Path t = new Path();
                t.moveTo(cx - s * 0.20f, cy - s * 0.18f);
                t.lineTo(cx + s * 0.20f, cy + s * 0.18f);
                t.lineTo(cx, cy + s * 0.38f);
                t.lineTo(cx, cy - s * 0.38f);
                t.lineTo(cx + s * 0.20f, cy - s * 0.18f);
                t.lineTo(cx - s * 0.20f, cy + s * 0.18f);
                cv.drawPath(t, p);
                break;
            }
            case PERSON: {
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx, cy - s * 0.12f, s * 0.17f, p);
                RectF body = new RectF(cx - s * 0.32f, cy + s * 0.1f, cx + s * 0.32f, cy + s * 0.62f);
                cv.drawArc(body, 180, 180, true, p);
                break;
            }
            case GROUP: {
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx - s * 0.12f, cy - s * 0.1f, s * 0.13f, p);
                cv.drawCircle(cx + s * 0.18f, cy - s * 0.06f, s * 0.11f, p);
                cv.drawArc(new RectF(cx - s * 0.38f, cy + s * 0.08f, cx + s * 0.14f, cy + s * 0.5f), 180, 180, true, p);
                cv.drawArc(new RectF(cx - s * 0.0f, cy + s * 0.1f, cx + s * 0.38f, cy + s * 0.44f), 180, 180, true, p);
                break;
            }
            case REFRESH: {
                RectF r = new RectF(cx - s * 0.3f, cy - s * 0.3f, cx + s * 0.3f, cy + s * 0.3f);
                cv.drawArc(r, -60, 300, false, p);
                float ax = cx + s * 0.15f, ay = cy - s * 0.26f;
                cv.drawLine(ax, ay, ax + s * 0.16f, ay - s * 0.06f, p);
                cv.drawLine(ax, ay, ax + s * 0.04f, ay + s * 0.16f, p);
                break;
            }
            case CHEVRON: {   // חץ קטן שמאלה (כניסה בעברית)
                cv.drawLine(cx + s * 0.12f, cy - s * 0.24f, cx - s * 0.12f, cy, p);
                cv.drawLine(cx - s * 0.12f, cy, cx + s * 0.12f, cy + s * 0.24f, p);
                break;
            }
            case MOON: {
                Path m = new Path();
                RectF o = new RectF(cx - s * 0.34f, cy - s * 0.34f, cx + s * 0.34f, cy + s * 0.34f);
                m.addArc(o, 0, 360);
                cv.drawArc(o, 60, 260, false, p);
                RectF in = new RectF(cx - s * 0.1f, cy - s * 0.42f, cx + s * 0.46f, cy + s * 0.14f);
                cv.drawArc(in, 100, 140, false, p);
                break;
            }
            case ERROR: {
                cv.drawCircle(cx, cy, s * 0.36f, p);
                cv.drawLine(cx, cy - s * 0.18f, cx, cy + s * 0.04f, p);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx, cy + s * 0.17f, s * 0.05f, p);
                break;
            }
            case CLOCK: {
                cv.drawCircle(cx, cy, s * 0.34f, p);
                cv.drawLine(cx, cy - s * 0.18f, cx, cy, p);
                cv.drawLine(cx, cy, cx + s * 0.13f, cy + s * 0.1f, p);
                break;
            }
            case QUOTE: {   // סימן ציטוט (שני "פסיקים" מלאים)
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 2; i++) {
                    float ox = cx + (i == 0 ? -s * 0.2f : s * 0.2f);
                    cv.drawCircle(ox, cy - s * 0.06f, s * 0.15f, p);
                    Path t = new Path();
                    t.moveTo(ox + s * 0.15f, cy - s * 0.06f);
                    t.quadTo(ox + s * 0.14f, cy + s * 0.26f, ox - s * 0.12f, cy + s * 0.34f);
                    t.quadTo(ox + s * 0.02f, cy + s * 0.18f, ox - s * 0.02f, cy + s * 0.08f);
                    t.close();
                    cv.drawPath(t, p);
                }
                break;
            }
            case PLUS: {
                cv.drawLine(cx, cy - s * 0.3f, cx, cy + s * 0.3f, p);
                cv.drawLine(cx - s * 0.3f, cy, cx + s * 0.3f, cy, p);
                break;
            }
            case FORMAT: {   // האות A (בגופן) עם פס מתחתיה — כמו "עיצוב טקסט" בגוגל צ'אט
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                p.setTextSize(s * 0.72f);
                p.setTextAlign(Paint.Align.CENTER);
                cv.drawText("A", cx, cy + s * 0.16f, p);
                cv.drawRoundRect(new RectF(cx - s * 0.36f, cy + s * 0.3f, cx + s * 0.36f, cy + s * 0.42f), s * 0.03f, s * 0.03f, p);
                break;
            }
            case EDIT: {   // עיפרון
                Path pe = new Path();
                pe.moveTo(cx - s * 0.32f, cy + s * 0.32f);
                pe.lineTo(cx - s * 0.3f, cy + s * 0.14f);
                pe.lineTo(cx + s * 0.16f, cy - s * 0.32f);
                pe.lineTo(cx + s * 0.32f, cy - s * 0.16f);
                pe.lineTo(cx - s * 0.14f, cy + s * 0.3f);
                pe.close();
                cv.drawPath(pe, p);
                cv.drawLine(cx + s * 0.06f, cy - s * 0.22f, cx + s * 0.22f, cy - s * 0.06f, p);
                break;
            }
            case DELETE: {   // פח
                cv.drawLine(cx - s * 0.32f, cy - s * 0.24f, cx + s * 0.32f, cy - s * 0.24f, p);
                cv.drawLine(cx - s * 0.1f, cy - s * 0.34f, cx + s * 0.1f, cy - s * 0.34f, p);
                Path t = new Path();
                t.moveTo(cx - s * 0.24f, cy - s * 0.24f);
                t.lineTo(cx - s * 0.2f, cy + s * 0.36f);
                t.lineTo(cx + s * 0.2f, cy + s * 0.36f);
                t.lineTo(cx + s * 0.24f, cy - s * 0.24f);
                cv.drawPath(t, p);
                cv.drawLine(cx - s * 0.07f, cy - s * 0.1f, cx - s * 0.07f, cy + s * 0.22f, p);
                cv.drawLine(cx + s * 0.07f, cy - s * 0.1f, cx + s * 0.07f, cy + s * 0.22f, p);
                break;
            }
            case MENU: {
                for (int i = -1; i <= 1; i++) cv.drawLine(cx - s * 0.34f, cy + i * s * 0.24f, cx + s * 0.34f, cy + i * s * 0.24f, p);
                break;
            }
            case HOME:
            case HOME_FILL: {
                Path hp = new Path();
                hp.moveTo(cx, cy - s * 0.36f);
                hp.lineTo(cx + s * 0.34f, cy - s * 0.08f);
                hp.lineTo(cx + s * 0.34f, cy + s * 0.34f);
                hp.lineTo(cx + s * 0.1f, cy + s * 0.34f);
                hp.lineTo(cx + s * 0.1f, cy + s * 0.1f);
                hp.lineTo(cx - s * 0.1f, cy + s * 0.1f);
                hp.lineTo(cx - s * 0.1f, cy + s * 0.34f);
                hp.lineTo(cx - s * 0.34f, cy + s * 0.34f);
                hp.lineTo(cx - s * 0.34f, cy - s * 0.08f);
                hp.close();
                if (kind == HOME_FILL) p.setStyle(Paint.Style.FILL_AND_STROKE);
                cv.drawPath(hp, p);
                break;
            }
            case CHATS:
            case CHATS_FILL: {
                // שתי בועות: אחת גדולה מאחור, אחת קטנה מקדימה
                if (kind == CHATS_FILL) p.setStyle(Paint.Style.FILL_AND_STROKE);
                Path a = new Path();
                a.addRoundRect(new RectF(cx - s * 0.38f, cy - s * 0.34f, cx + s * 0.16f, cy + s * 0.06f), s * 0.05f, s * 0.05f, Path.Direction.CW);
                a.moveTo(cx - s * 0.28f, cy + s * 0.06f);
                a.lineTo(cx - s * 0.36f, cy + s * 0.18f);
                a.lineTo(cx - s * 0.36f, cy + s * 0.06f);
                cv.drawPath(a, p);
                p.setStyle(Paint.Style.STROKE);
                Path b = new Path();
                b.moveTo(cx - s * 0.08f, cy + s * 0.14f);
                b.lineTo(cx + s * 0.22f, cy + s * 0.14f);
                b.lineTo(cx + s * 0.36f, cy + s * 0.3f);
                b.lineTo(cx + s * 0.36f, cy - s * 0.14f);
                b.lineTo(cx + s * 0.26f, cy - s * 0.14f);
                cv.drawPath(b, p);
                break;
            }
            case SPACES: {   // שני ריבועים מעוגלים זה על זה
                cv.drawRoundRect(new RectF(cx - s * 0.3f, cy - s * 0.36f, cx + s * 0.3f, cy - s * 0.04f), s * 0.07f, s * 0.07f, p);
                cv.drawRoundRect(new RectF(cx - s * 0.3f, cy + s * 0.06f, cx + s * 0.3f, cy + s * 0.36f), s * 0.07f, s * 0.07f, p);
                break;
            }
            case REPLY: {   // חץ מתעקל (ציטוט בתשובה)
                Path r = new Path();
                r.moveTo(cx + s * 0.34f, cy + s * 0.3f);
                r.quadTo(cx + s * 0.3f, cy - s * 0.08f, cx - s * 0.3f, cy - s * 0.08f);
                cv.drawPath(r, p);
                cv.drawLine(cx - s * 0.32f, cy - s * 0.08f, cx - s * 0.1f, cy - s * 0.3f, p);
                cv.drawLine(cx - s * 0.32f, cy - s * 0.08f, cx - s * 0.1f, cy + s * 0.14f, p);
                break;
            }
            case ADD_REACTION: {
                cv.drawCircle(cx - s * 0.04f, cy + s * 0.04f, s * 0.3f, p);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(cx - s * 0.15f, cy - s * 0.04f, s * 0.04f, p);
                cv.drawCircle(cx + s * 0.07f, cy - s * 0.04f, s * 0.04f, p);
                p.setStyle(Paint.Style.STROKE);
                cv.drawArc(new RectF(cx - s * 0.18f, cy - s * 0.02f, cx + s * 0.1f, cy + s * 0.18f), 20, 140, false, p);
                // סימן פלוס בפינה
                p.setColor(color);
                cv.drawLine(cx + s * 0.3f, cy - s * 0.42f, cx + s * 0.3f, cy - s * 0.2f, p);
                cv.drawLine(cx + s * 0.19f, cy - s * 0.31f, cx + s * 0.41f, cy - s * 0.31f, p);
                break;
            }
            default:
                break;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(), getHeight() - getPaddingTop() - getPaddingBottom());
        drawWith(p, c, kind, getWidth() / 2f, getHeight() / 2f, s, color, weight);
    }
}
