package il.btchat;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * עיצוב טקסט כמו בגוגל צ'אט: מודגש, נטוי, קו תחתון, קו חוצה וקוד.
 * בגוגל העיצוב נשמר כרשימת טווחים: [התחלה, אורך, סוג] (1 מודגש, 2 נטוי, 3 קו חוצה, 5 קוד, 8 קו תחתון).
 */
final class Rich {
    static final int BOLD = 1, ITALIC = 2, STRIKE = 3, MONO = 5, UNDER = 8;
    static final int[] TYPES = {BOLD, ITALIC, UNDER, STRIKE, MONO};

    // סימונים שלנו בשורת הכתיבה (כדי לא להתבלבל עם קו תחתון של המקלדת בזמן הקלדה)
    interface Mark { int type(); }
    static final class B extends StyleSpan implements Mark { B() { super(Typeface.BOLD); } public int type() { return BOLD; } }
    static final class I extends StyleSpan implements Mark { I() { super(Typeface.ITALIC); } public int type() { return ITALIC; } }
    static final class S extends StrikethroughSpan implements Mark { public int type() { return STRIKE; } }
    static final class U extends UnderlineSpan implements Mark { public int type() { return UNDER; } }
    static final class M extends TypefaceSpan implements Mark { M() { super("monospace"); } public int type() { return MONO; } }

    static Object mark(int type) {
        switch (type) {
            case BOLD: return new B();
            case ITALIC: return new I();
            case STRIKE: return new S();
            case UNDER: return new U();
            default: return new M();
        }
    }

    /** טקסט של הודעה עם העיצוב שלה, לתצוגה. */
    static CharSequence render(String text, JSONArray f, int codeBg) {
        if (f == null || f.length() == 0) return text;
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int len = text.length();
        for (int i = 0; i < f.length(); i++) {
            JSONArray r = f.optJSONArray(i);
            if (r == null || r.length() < 3) continue;
            int s = Math.max(0, r.optInt(0)), e = Math.min(len, s + r.optInt(1)), t = r.optInt(2);
            if (e <= s) continue;
            if (t == 4 || t == 7) t = MONO;
            sb.setSpan(mark(t), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (t == MONO) sb.setSpan(new BackgroundColorSpan(codeBg), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /** טקסט עם עיצוב לעריכה בשורת הכתיבה. */
    static SpannableStringBuilder editable(String text, JSONArray f) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        if (f == null) return sb;
        for (int i = 0; i < f.length(); i++) {
            JSONArray r = f.optJSONArray(i);
            if (r == null || r.length() < 3) continue;
            int s = Math.max(0, r.optInt(0)), e = Math.min(text.length(), s + r.optInt(1)), t = r.optInt(2);
            if (t == 4 || t == 7) t = MONO;
            if (e > s) sb.setSpan(mark(t), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /** הטווחים לשליחה. offset/limit: אחרי חיתוך רווחים מההתחלה ומהסוף. */
    static JSONArray extract(Spanned s, int offset, int limit) {
        JSONArray out = new JSONArray();
        for (Object o : s.getSpans(0, s.length(), Mark.class)) {
            int st = Math.max(s.getSpanStart(o) - offset, 0), en = Math.min(s.getSpanEnd(o) - offset, limit);
            if (en <= st) continue;
            out.put(new JSONArray().put(st).put(en - st).put(((Mark) o).type()));
        }
        return out;
    }

    private static List<Object> spans(Spannable e, int start, int end, int type) {
        List<Object> l = new ArrayList<>();
        for (Object o : e.getSpans(Math.max(0, start), Math.min(e.length(), end), Mark.class))
            if (((Mark) o).type() == type) l.add(o);
        return l;
    }

    /** האם העיצוב פעיל במקום הסמן / בכל הבחירה. */
    static boolean active(Spannable e, int start, int end, int type) {
        if (start == end) {
            for (Object o : spans(e, start, end, type)) {
                int ss = e.getSpanStart(o), se = e.getSpanEnd(o), fl = e.getSpanFlags(o);
                boolean inclusiveEnd = (fl & Spanned.SPAN_POINT_MARK_MASK) == Spanned.SPAN_EXCLUSIVE_INCLUSIVE || (fl & Spanned.SPAN_POINT_MARK_MASK) == Spanned.SPAN_INCLUSIVE_INCLUSIVE;
                if (ss < start && se > start) return true;
                if (se == start && inclusiveEnd) return true;
            }
            return false;
        }
        // כל התווים בבחירה מכוסים?
        for (int i = start; i < end; i++) {
            boolean cov = false;
            for (Object o : spans(e, i, i + 1, type)) if (e.getSpanStart(o) <= i && e.getSpanEnd(o) > i) { cov = true; break; }
            if (!cov) return false;
        }
        return true;
    }

    /** הפעלה/כיבוי של עיצוב על הבחירה, או להמשך ההקלדה אם אין בחירה. */
    static void toggle(Editable e, int start, int end, int type) {
        boolean on = active(e, start, end, type);
        if (start == end) {
            if (on) {
                // מפסיקים את העיצוב מהמקום הזה (הטקסט שכבר נכתב נשאר מעוצב)
                for (Object o : spans(e, start, end, type)) {
                    int ss = e.getSpanStart(o), se = e.getSpanEnd(o);
                    e.removeSpan(o);
                    if (ss < start) e.setSpan(mark(type), ss, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (se > start) e.setSpan(mark(type), start, se, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                // טווח ריק שה"סוף" שלו זז עם ההקלדה: כל מה שיוקלד מכאן יקבל את העיצוב
                e.setSpan(mark(type), start, start, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            }
            return;
        }
        int ns = start, ne = end;
        for (Object o : spans(e, start, end, type)) {
            int ss = e.getSpanStart(o), se = e.getSpanEnd(o);
            e.removeSpan(o);
            if (on) {
                if (ss < start) e.setSpan(mark(type), ss, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (se > end) e.setSpan(mark(type), end, se, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                ns = Math.min(ns, ss);
                ne = Math.max(ne, se);
            }
        }
        if (!on) e.setSpan(mark(type), ns, ne, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** מנקה טווחים ריקים שנשארו (כדי שלא "יידבקו" להקלדה הבאה). */
    static void clearEmpty(Editable e) {
        for (Object o : e.getSpans(0, e.length(), Mark.class))
            if (e.getSpanStart(o) == e.getSpanEnd(o)) e.removeSpan(o);
    }
}
