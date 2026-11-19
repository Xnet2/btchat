package il.btchat;

import java.util.Calendar;
import java.util.Locale;

/** תאריכים ושעות בעברית, כמו בגוגל צ'אט. */
final class Fmt {
    private Fmt() { }

    private static final String[] DAYS = {"ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת"};
    private static final String[] DAYS_SHORT = {"א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳"};
    private static final String[] MONTHS = {"ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר"};
    private static final String[] MONTHS_SHORT = {"ינו׳", "פבר׳", "מרץ", "אפר׳", "מאי", "יוני", "יולי", "אוג׳", "ספט׳", "אוק׳", "נוב׳", "דצמ׳"};

    private static Calendar cal(long usec) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(usec / 1000);
        return c;
    }

    /** הפרש בימים קלנדריים מהיום (0 = היום, 1 = אתמול). */
    static int daysAgo(long usec) {
        Calendar a = cal(usec), b = Calendar.getInstance();
        a.set(Calendar.HOUR_OF_DAY, 12); a.set(Calendar.MINUTE, 0);
        b.set(Calendar.HOUR_OF_DAY, 12); b.set(Calendar.MINUTE, 0);
        return (int) Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0);
    }

    static String time(long usec) {
        Calendar c = cal(usec);
        return String.format(Locale.US, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** הזמן מעל הודעה: "עכשיו", "5 דק'", ואחר כך השעה (כמו בגוגל צ'אט). */
    static String rel(long usec) {
        long min = (System.currentTimeMillis() - usec / 1000) / 60000;
        if (min < 1) return "עכשיו";
        if (min < 60) return min + " דק'";
        if (daysAgo(usec) <= 0) return time(usec);
        if (daysAgo(usec) == 1) return "אתמול " + time(usec);
        // לא מהיום: גם התאריך, כמו "1 בנוב׳, 22:26"
        Calendar c = cal(usec);
        String d = c.get(Calendar.DAY_OF_MONTH) + " ב" + MONTHS_SHORT[c.get(Calendar.MONTH)];
        if (c.get(Calendar.YEAR) != Calendar.getInstance().get(Calendar.YEAR)) d += " " + c.get(Calendar.YEAR);
        return d + ", " + time(usec);
    }

    /** הזמן ברשימת הצ'אטים. */
    static String listTime(long usec) {
        if (usec <= 0) return "";
        int d = daysAgo(usec);
        Calendar c = cal(usec);
        if (d <= 0) return time(usec);
        if (d == 1) return "אתמול";
        if (d < 7) return "יום " + DAYS_SHORT[c.get(Calendar.DAY_OF_WEEK) - 1];
        if (c.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR))
            return c.get(Calendar.DAY_OF_MONTH) + " ב" + MONTHS_SHORT[c.get(Calendar.MONTH)];
        return c.get(Calendar.DAY_OF_MONTH) + "." + (c.get(Calendar.MONTH) + 1) + "." + (c.get(Calendar.YEAR) % 100);
    }

    /** כותרת יום בתוך הצ'אט. */
    static String day(long usec) {
        int d = daysAgo(usec);
        Calendar c = cal(usec);
        if (d <= 0) return "היום";
        if (d == 1) return "אתמול";
        String s = "יום " + DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + ", " + c.get(Calendar.DAY_OF_MONTH) + " ב" + MONTHS[c.get(Calendar.MONTH)];
        if (c.get(Calendar.YEAR) != Calendar.getInstance().get(Calendar.YEAR)) s += " " + c.get(Calendar.YEAR);
        return s;
    }

    static boolean sameDay(long a, long b) {
        Calendar x = cal(a), y = cal(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    static String size(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
