// "ביטיצ'אט" — תוכנת המחשב.
// מחזיקה חלון Chrome (פרופיל נפרד) מחובר לגוגל צ'אט, מדברת עם האתר דרך פרוטוקול הפיתוח של Chrome (CDP),
// ומעבירה צ'אטים והודעות לנגן בבלוטות' (RFCOMM), ובחזרה: שליחה, תגובות וסימון כנקרא.
// הבקשות לגוגל נשלחות מתוך הדף עצמו, באותו פורמט JSON שהאתר משתמש בו (כך נטפרי מאשרת אותן).
// נבנה עם csc של ‎.NET Framework 4 (C# 5), בלי ספריות חיצוניות.
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: System.Reflection.AssemblyTitle("ביטיצ'אט")]
[assembly: System.Reflection.AssemblyProduct("ביטיצ'אט")]
[assembly: System.Reflection.AssemblyDescription("BTChat - Google Chat on an offline player over Bluetooth")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]
[assembly: System.Reflection.AssemblyFileVersion("1.0.0.0")]

namespace BTChat
{
    static class Program
    {
        public static readonly Guid ServiceUuid = new Guid("6f1c2a8e-3b7d-4e59-a1c4-9d8e7f6a5b32");
        public const string Version = "1.0.0";
        public const string Name = "ביטיצ'אט";
        public static bool TestMode;

        [STAThread]
        static void Main(string[] args)
        {
            TestMode = HasArg(args, "/test");
            if (args.Length >= 2 && args[0] == "/probe")
            {
                // בדיקה בלי נגן: מתחבר ל-Chrome ולגוגל צ'אט ומדפיס סיכום (בלי תוכן הודעות).
                File.WriteAllText(args[1], Core.Probe(args.Length >= 3 ? args[2] : null), Encoding.UTF8);
                return;
            }
            if (args.Length >= 3 && args[0] == "/sim")
            {
                // בדיקה בלי נגן: /sim <out.txt> <gid>
                File.WriteAllText(args[1], Core.Simulate(args[2]), Encoding.UTF8);
                return;
            }
            if (args.Length >= 2 && args[0] == "/icon") { Theme.SaveIco(args[1]); return; }
            if (args.Length >= 3 && args[0] == "/png")
            {
                // אייקונים לאפליקציה בנגן: /png <גודל> <קובץ> [mono]
                int sz = int.Parse(args[1]);
                using (var b = args.Length >= 4 && args[3] == "mono" ? Theme.Mono(sz) : Theme.Logo(sz, null)) b.Save(args[2], System.Drawing.Imaging.ImageFormat.Png);
                return;
            }
            bool created;
            var mutex = new Mutex(true, "Local\\BTChat-PC", out created);
            var showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, "Local\\BTChat-PC-Show");
            if (!created) { showEvent.Set(); return; }
            ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072;
            try { SetProcessDPIAware(); } catch { }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            if (AutoStart.RunningFromTemp() && !TestMode)
            {
                MessageBox.Show(
                    "צריך לחלץ את הקבצים לפני ההפעלה.\n\nלוחצים לחיצה ימנית על קובץ ה-ZIP ← \"חלץ הכל\", ואז מפעילים את BTChat.exe מהתיקייה שנוצרה.",
                    Name, MessageBoxButtons.OK, MessageBoxIcon.Information, MessageBoxDefaultButton.Button1,
                    MessageBoxOptions.RightAlign | MessageBoxOptions.RtlReading);
                return;
            }
            var core = new Core();
            core.Start();
            var app = new TrayApp(core, !HasArg(args, "/tray"), showEvent);
            Application.Run(app);
            core.Stop();
            GC.KeepAlive(mutex);
        }

        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();

        static bool HasArg(string[] args, string a)
        {
            foreach (var s in args) if (string.Equals(s, a, StringComparison.OrdinalIgnoreCase)) return true;
            return false;
        }
    }

    // ---------------------------------------------------------------- JSON

    static class Json
    {
        static readonly JavaScriptSerializer js = new JavaScriptSerializer { MaxJsonLength = int.MaxValue, RecursionLimit = 400 };
        public static string Ser(object o) { lock (js) return js.Serialize(o); }
        public static object Parse(string s) { lock (js) return js.DeserializeObject(s); }
        public static Dictionary<string, object> Obj(object o) { return o as Dictionary<string, object>; }
        public static Dictionary<string, object> New(params object[] kv)
        {
            var d = new Dictionary<string, object>();
            for (int i = 0; i + 1 < kv.Length; i += 2) d[(string)kv[i]] = kv[i + 1];
            return d;
        }
        public static string Str(Dictionary<string, object> d, string k)
        {
            object v; if (d == null || !d.TryGetValue(k, out v) || v == null) return "";
            return Convert.ToString(v, CultureInfo.InvariantCulture);
        }
        public static long Long(Dictionary<string, object> d, string k)
        {
            object v; if (d == null || !d.TryGetValue(k, out v) || v == null) return 0;
            try { return Convert.ToInt64(v, CultureInfo.InvariantCulture); } catch { return 0; }
        }
        public static bool Bool(Dictionary<string, object> d, string k, bool def)
        {
            object v; if (d == null || !d.TryGetValue(k, out v) || v == null) return def;
            if (v is bool) return (bool)v;
            try { return Convert.ToInt64(v, CultureInfo.InvariantCulture) != 0; } catch { return def; }
        }
    }

    /// <summary>
    /// גישה לשדות בפורמט PBLite של גוגל: הודעת protobuf כמערך JSON.
    /// מערך עם תגית ("dfe.…") בתא הראשון: שדה n בתא n. בלי תגית: שדה n בתא n-1.
    /// שדות עם מספר גבוה יכולים להופיע באובייקט בסוף המערך ({"12": …}).
    /// </summary>
    static class Pb
    {
        public static object F(object a, int f)
        {
            var arr = a as object[];
            if (arr == null || arr.Length == 0) return null;
            var last = arr[arr.Length - 1] as Dictionary<string, object>;
            if (last != null)
            {
                object v;
                if (last.TryGetValue(f.ToString(CultureInfo.InvariantCulture), out v)) return v;
            }
            var tag = arr[0] as string;
            int i = tag != null && tag.StartsWith("dfe.") ? f : f - 1;
            return i >= 0 && i < arr.Length && !(arr[i] is Dictionary<string, object>) ? arr[i] : null;
        }
        public static object P(object a, params int[] path)
        {
            foreach (int f in path) { a = F(a, f); if (a == null) return null; }
            return a;
        }
        public static string S(object a, params int[] path)
        {
            var v = P(a, path);
            return v == null || v is object[] ? "" : Convert.ToString(v, CultureInfo.InvariantCulture);
        }
        public static long L(object a, params int[] path)
        {
            var v = P(a, path);
            if (v == null || v is object[]) return 0;
            if (v is bool) return (bool)v ? 1 : 0;
            long r;
            if (v is string) return long.TryParse((string)v, NumberStyles.Integer, CultureInfo.InvariantCulture, out r) ? r : 0;
            try { return Convert.ToInt64(v, CultureInfo.InvariantCulture); } catch { return 0; }
        }
        public static bool B(object a, params int[] path)
        {
            var v = P(a, path);
            if (v is bool) return (bool)v;
            return L(a, path) != 0;
        }
        public static object[] A(object a, params int[] path)
        {
            return P(a, path) as object[] ?? new object[0];
        }
        /// <summary>בונה הודעה: זוגות של (מספר שדה, ערך). בלי תגית, שדה n בתא n-1.</summary>
        public static object[] M(params object[] pairs)
        {
            int max = 0;
            for (int i = 0; i < pairs.Length; i += 2) max = Math.Max(max, (int)pairs[i]);
            var arr = new object[max];
            for (int i = 0; i + 1 < pairs.Length; i += 2) arr[(int)pairs[i] - 1] = pairs[i + 1];
            return arr;
        }

        public static object[] GroupId(string gid)
        {
            int slash = gid.IndexOf('/');
            string kind = slash > 0 ? gid.Substring(0, slash) : "space", id = slash > 0 ? gid.Substring(slash + 1) : gid;
            return kind == "dm" ? M(3, new object[] { id }) : M(1, new object[] { id });
        }
        public static string GroupStr(object g)
        {
            string s = S(g, 1, 1);
            if (s.Length > 0) return "space/" + s;
            s = S(g, 3, 1);
            return s.Length > 0 ? "dm/" + s : "";
        }
        public static object[] TopicId(string gid, string topic) { return M(2, topic, 3, GroupId(gid)); }
        public static object[] MessageId(string gid, string topic, string msg)
        {
            return M(1, M(4, TopicId(gid, topic)), 2, msg);
        }
    }

    // ---------------------------------------------------------------- מצב שמור

    class State
    {
        public string DeviceAddr = "";
        public string LastAddr = "";
        public bool AutoStartDone;
        public bool HideChrome = true;
        public List<int> Accounts = new List<int> { 0 };   // מספרי החשבונות בכרום (u/0, u/1…)

        public static string Dir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "BTChat"); }
        }
        static string FilePath { get { return Path.Combine(Dir, "state.json"); } }

        public static State Load()
        {
            var s = new State();
            try
            {
                if (!File.Exists(FilePath)) return s;
                var d = Json.Obj(Json.Parse(File.ReadAllText(FilePath, Encoding.UTF8)));
                if (d == null) return s;
                s.DeviceAddr = Json.Str(d, "deviceAddr");
                s.LastAddr = Json.Str(d, "lastAddr");
                s.AutoStartDone = Json.Bool(d, "autoStartDone", false);
                s.HideChrome = Json.Bool(d, "hideChrome", true);
                var acc = d.ContainsKey("accounts") ? d["accounts"] as object[] : null;
                if (acc != null && acc.Length > 0)
                {
                    s.Accounts.Clear();
                    foreach (var x in acc) { int n = Convert.ToInt32(x); if (!s.Accounts.Contains(n)) s.Accounts.Add(n); }
                }
            }
            catch { }
            return s;
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(Dir);
                var d = Json.New("deviceAddr", DeviceAddr, "lastAddr", LastAddr, "autoStartDone", AutoStartDone, "hideChrome", HideChrome, "accounts", Accounts);
                var tmp = FilePath + ".tmp";
                File.WriteAllText(tmp, Json.Ser(d), Encoding.UTF8);
                if (File.Exists(FilePath)) File.Delete(FilePath);
                File.Move(tmp, FilePath);
            }
            catch { }
        }
    }

    // ---------------------------------------------------------------- בלוטות'

    class BtDevice
    {
        public ulong Addr; public string Name; public uint Cod; public bool Connected;
        public string AddrText { get { return Addr.ToString("X12"); } }
        public bool IsAccessory { get { uint major = (Cod >> 8) & 0x1F; return major == 4 || major == 5 || major == 6; } }
        public override string ToString() { return Name; }
    }

    class BtEndPoint : EndPoint
    {
        public readonly ulong Addr; public readonly Guid Service; public readonly int Port;
        public BtEndPoint(ulong addr, Guid service, int port) { Addr = addr; Service = service; Port = port; }
        public override AddressFamily AddressFamily { get { return (AddressFamily)32; } }
        public override SocketAddress Serialize()
        {
            // SOCKADDR_BTH: family(2) btAddr(8) serviceClassId(16) port(4). port=0 → Windows מוצא את הערוץ לפי ה-UUID.
            var sa = new SocketAddress((AddressFamily)32, 30);
            var a = BitConverter.GetBytes(Addr); for (int i = 0; i < 8; i++) sa[2 + i] = a[i];
            var g = Service.ToByteArray(); for (int i = 0; i < 16; i++) sa[10 + i] = g[i];
            var p = BitConverter.GetBytes(Port); for (int i = 0; i < 4; i++) sa[26 + i] = p[i];
            return sa;
        }
        public override EndPoint Create(SocketAddress sa)
        {
            var a = new byte[8]; for (int i = 0; i < 8; i++) a[i] = sa[2 + i];
            var g = new byte[16]; for (int i = 0; i < 16; i++) g[i] = sa[10 + i];
            var p = new byte[4]; for (int i = 0; i < 4; i++) p[i] = sa[26 + i];
            return new BtEndPoint(BitConverter.ToUInt64(a, 0), new Guid(g), BitConverter.ToInt32(p, 0));
        }
    }

    static class Bt
    {
        [StructLayout(LayoutKind.Sequential)]
        struct SearchParams
        {
            public int dwSize;
            public int fReturnAuthenticated, fReturnRemembered, fReturnUnknown, fReturnConnected, fIssueInquiry;
            public byte cTimeoutMultiplier;
            public IntPtr hRadio;
        }
        [StructLayout(LayoutKind.Sequential)]
        struct SystemTime { public ushort a, b, c, d, e, f, g, h; }
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct DeviceInfo
        {
            public int dwSize;
            public ulong Address;
            public uint ulClassofDevice;
            public int fConnected, fRemembered, fAuthenticated;
            public SystemTime stLastSeen, stLastUsed;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 248)] public string szName;
        }
        [DllImport("bthprops.cpl", SetLastError = true)] static extern IntPtr BluetoothFindFirstDevice(ref SearchParams p, ref DeviceInfo info);
        [DllImport("bthprops.cpl", SetLastError = true)] static extern bool BluetoothFindNextDevice(IntPtr h, ref DeviceInfo info);
        [DllImport("bthprops.cpl")] static extern bool BluetoothFindDeviceClose(IntPtr h);

        public static List<BtDevice> Paired()
        {
            var list = new List<BtDevice>();
            try
            {
                var p = new SearchParams();
                p.dwSize = Marshal.SizeOf(typeof(SearchParams));
                p.fReturnAuthenticated = 1; p.fReturnRemembered = 1; p.fReturnConnected = 1;
                var info = new DeviceInfo();
                info.dwSize = Marshal.SizeOf(typeof(DeviceInfo));
                IntPtr h = BluetoothFindFirstDevice(ref p, ref info);
                if (h == IntPtr.Zero) return list;
                do
                {
                    if (info.fRemembered != 0 || info.fAuthenticated != 0)
                    {
                        bool dup = false;
                        foreach (var d in list) if (d.Addr == info.Address) dup = true;
                        if (!dup) list.Add(new BtDevice
                        {
                            Addr = info.Address,
                            Name = string.IsNullOrEmpty(info.szName) ? info.Address.ToString("X12") : info.szName,
                            Cod = info.ulClassofDevice,
                            Connected = info.fConnected != 0
                        });
                    }
                    info = new DeviceInfo(); info.dwSize = Marshal.SizeOf(typeof(DeviceInfo));
                } while (BluetoothFindNextDevice(h, ref info));
                BluetoothFindDeviceClose(h);
            }
            catch { }
            return list;
        }
    }

    // ---------------------------------------------------------------- Chrome ו-CDP

    class CdpException : Exception { public CdpException(string m) : base(m) { } }

    /// <summary>חיבור WebSocket לכרטיסייה של Chrome (Chrome DevTools Protocol).</summary>
    class Cdp : IDisposable
    {
        class Pending { public ManualResetEvent Done = new ManualResetEvent(false); public Dictionary<string, object> Msg; }

        readonly ClientWebSocket ws = new ClientWebSocket();
        readonly Dictionary<int, Pending> pend = new Dictionary<int, Pending>();
        readonly object sendLock = new object();
        int nextId;
        public volatile bool Alive;

        public static Cdp Connect(string url)
        {
            var c = new Cdp();
            c.ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(20);
            if (!c.ws.ConnectAsync(new Uri(url), CancellationToken.None).Wait(8000)) throw new CdpException("timeout");
            c.Alive = true;
            var t = new Thread(c.ReadLoop) { IsBackground = true, Name = "cdp" };
            t.Start();
            return c;
        }

        void ReadLoop()
        {
            var buf = new byte[256 * 1024];
            var ms = new MemoryStream();
            try
            {
                while (ws.State == WebSocketState.Open)
                {
                    var r = ws.ReceiveAsync(new ArraySegment<byte>(buf), CancellationToken.None).Result;
                    if (r.MessageType == WebSocketMessageType.Close) break;
                    ms.Write(buf, 0, r.Count);
                    if (!r.EndOfMessage) continue;
                    string text = Encoding.UTF8.GetString(ms.GetBuffer(), 0, (int)ms.Length);
                    ms.SetLength(0);
                    Dictionary<string, object> m;
                    try { m = Json.Obj(Json.Parse(text)); } catch { continue; }
                    if (m == null || !m.ContainsKey("id")) continue;
                    int id = (int)Json.Long(m, "id");
                    Pending p;
                    lock (pend) { if (pend.TryGetValue(id, out p)) pend.Remove(id); }
                    if (p != null) { p.Msg = m; p.Done.Set(); }
                }
            }
            catch { }
            Alive = false;
            lock (pend) { foreach (var p in pend.Values) p.Done.Set(); pend.Clear(); }
        }

        public Dictionary<string, object> Send(string method, Dictionary<string, object> prms, int timeoutMs)
        {
            if (!Alive) throw new CdpException("disconnected");
            var p = new Pending();
            int id;
            lock (pend) { id = ++nextId; pend[id] = p; }
            var body = Encoding.UTF8.GetBytes(Json.Ser(Json.New("id", id, "method", method, "params", prms ?? new Dictionary<string, object>())));
            lock (sendLock)
            {
                if (!ws.SendAsync(new ArraySegment<byte>(body), WebSocketMessageType.Text, true, CancellationToken.None).Wait(10000))
                    throw new CdpException("send timeout");
            }
            if (!p.Done.WaitOne(timeoutMs)) { lock (pend) pend.Remove(id); throw new CdpException("timeout: " + method); }
            if (p.Msg == null) throw new CdpException("disconnected");
            if (p.Msg.ContainsKey("error")) throw new CdpException(Json.Ser(p.Msg["error"]));
            return Json.Obj(p.Msg.ContainsKey("result") ? p.Msg["result"] : null) ?? new Dictionary<string, object>();
        }

        /// <summary>מריץ JavaScript בדף (כולל await) ומחזיר את הערך.</summary>
        public object Eval(string expr, int timeoutMs)
        {
            var r = Send("Runtime.evaluate", Json.New("expression", expr, "awaitPromise", true, "returnByValue", true), timeoutMs);
            if (r.ContainsKey("exceptionDetails"))
            {
                var ex = Json.Obj(r["exceptionDetails"]);
                var exo = ex != null ? Json.Obj(ex.ContainsKey("exception") ? ex["exception"] : null) : null;
                throw new CdpException("JS: " + (exo != null ? Json.Str(exo, "description") : Json.Str(ex, "text")));
            }
            var res = Json.Obj(r.ContainsKey("result") ? r["result"] : null);
            return res != null && res.ContainsKey("value") ? res["value"] : null;
        }

        public void Dispose()
        {
            Alive = false;
            try { ws.Abort(); } catch { }
        }
    }

    /// <summary>מפעיל את Chrome עם פרופיל נפרד ופורט פיתוח מקומי.</summary>
    static class Browser
    {
        public const int Port = 9334;
        public static string ProfileDir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BTChat", "chrome"); }
        }

        public static string FindExe()
        {
            var pf = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            var pf86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
            var la = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string[] cands = {
                Path.Combine(pf, @"Google\Chrome\Application\chrome.exe"),
                Path.Combine(pf86, @"Google\Chrome\Application\chrome.exe"),
                Path.Combine(la, @"Google\Chrome\Application\chrome.exe"),
                Path.Combine(pf86, @"Microsoft\Edge\Application\msedge.exe"),
                Path.Combine(pf, @"Microsoft\Edge\Application\msedge.exe"),
            };
            foreach (var c in cands) if (File.Exists(c)) return c;
            return null;
        }

        public static string Http(string path, string method, int timeout)
        {
            var r = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:" + Port + path);
            r.Method = method; r.Timeout = timeout; r.ReadWriteTimeout = timeout; r.Proxy = null;
            using (var resp = r.GetResponse())
            using (var sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8)) return sr.ReadToEnd();
        }

        public static bool Alive()
        {
            try { Http("/json/version", "GET", 1500); return true; } catch { return false; }
        }

        public static bool Launch() { return Launch(false); }

        /// <summary>hidden: החלון נפתח מחוץ למסך ומוסתר מיד (בלי הבהוב ובלי סמל בשורת המשימות).</summary>
        public static bool Launch(bool hidden)
        {
            string exe = FindExe();
            if (exe == null) return false;
            Directory.CreateDirectory(ProfileDir);
            string args = "--user-data-dir=\"" + ProfileDir + "\" --remote-debugging-port=" + Port +
                " --no-first-run --no-default-browser-check --disable-background-timer-throttling" +
                // סנכרון Chrome ותוספים מסונכרנים גרמו לפרופיל לא לטעון/לשמור את ההתחברות אחרי הפעלה מחדש.
                " --disable-sync --disable-extensions" +
                " --disable-renderer-backgrounding --disable-backgrounding-occluded-windows" +
                " --disable-features=CalculateNativeWinOcclusion --window-size=1000,760" +
                (hidden ? " --window-position=-32000,-32000" : "") + " https://chat.google.com/";
            Process.Start(new ProcessStartInfo(exe, args) { UseShellExecute = false });
            return true;
        }

        static List<Dictionary<string, object>> Pages()
        {
            var res = new List<Dictionary<string, object>>();
            var list = Json.Parse(Http("/json/list", "GET", 4000)) as object[];
            if (list != null)
                foreach (var o in list)
                {
                    var d = Json.Obj(o);
                    if (d != null && Json.Str(d, "type") == "page") res.Add(d);
                }
            return res;
        }

        /// <summary>מספר החשבון לפי כתובת גוגל צ'אט (…/u/1/… = 1), או -1 אם זה לא דף של גוגל צ'אט.</summary>
        public static int IndexOf(string url)
        {
            if (!url.StartsWith("https://chat.google.com")) return -1;
            var m = System.Text.RegularExpressions.Regex.Match(url, "^https://chat\\.google\\.com/u/(\\d+)(/|$)");
            return m.Success ? int.Parse(m.Groups[1].Value) : 0;
        }

        /// <summary>
        /// הכרטיסייה של חשבון מסוים: זו שכבר שמורה לו (targetId), או כרטיסיית גוגל צ'אט של החשבון, או חדשה.
        /// </summary>
        public static string TabFor(int index, ref string targetId)
        {
            for (int attempt = 0; attempt < 8; attempt++)
            {
                var pages = Pages();
                foreach (var d in pages)
                    if (targetId.Length > 0 && Json.Str(d, "id") == targetId) return Json.Str(d, "webSocketDebuggerUrl");
                foreach (var d in pages)
                    if (IndexOf(Json.Str(d, "url")) == index) { targetId = Json.Str(d, "id"); return Json.Str(d, "webSocketDebuggerUrl"); }
                if (index > 0) break;
                // החשבון הראשון: אחרי הפעלה הכרטיסייה עוד בדרך (דף התחברות / ריקה)
                Dictionary<string, object> google = null, any = null;
                foreach (var d in pages)
                {
                    string url = Json.Str(d, "url");
                    if (google == null && (url.StartsWith("https://accounts.google.com") || url.StartsWith("https://workspace.google.com"))) google = d;
                    if (any == null && !url.StartsWith("https://chat.google.com")) any = d;
                }
                if (google != null) { targetId = Json.Str(google, "id"); return Json.Str(google, "webSocketDebuggerUrl"); }
                if (any == null) break;
                if (attempt == 7) { targetId = Json.Str(any, "id"); return Json.Str(any, "webSocketDebuggerUrl"); }
                Thread.Sleep(1000);
            }
            var n = Json.Obj(Json.Parse(Http("/json/new?" + (index > 0 ? "https://chat.google.com/u/" + index + "/" : "https://chat.google.com/"), "PUT", 6000)));
            targetId = Json.Str(n, "id");
            return Json.Str(n, "webSocketDebuggerUrl");
        }

        /// <summary>פותח כרטיסייה חדשה ומחזיר את המזהה שלה.</summary>
        public static string NewTab(string url)
        {
            var n = Json.Obj(Json.Parse(Http("/json/new?" + url, "PUT", 6000)));
            return Json.Str(n, "id");
        }

        public static void CloseTab(string id)
        {
            Http("/json/close/" + id, "GET", 4000);
        }

        /// <summary>כתובת ה-WebSocket של כרטיסיית גוגל צ'אט. אם אין — פותח אחת.</summary>
        public static string ChatTarget()
        {
            string tid = "";
            return TabFor(0, ref tid);
        }

        static string ChatTargetOld()
        {
            // אחרי הפעלה הכרטיסייה עוד בדרך לגוגל צ'אט: מחכים לה קצת לפני שפותחים חדשה
            string firstPage = null;
            for (int attempt = 0; attempt < 10; attempt++)
            {
                var list = Json.Parse(Http("/json/list", "GET", 4000)) as object[];
                string google = null;
                firstPage = null;
                if (list != null)
                    foreach (var o in list)
                    {
                        var d = Json.Obj(o);
                        if (d == null || Json.Str(d, "type") != "page") continue;
                        string url = Json.Str(d, "url");
                        if (url.StartsWith("https://chat.google.com")) return Json.Str(d, "webSocketDebuggerUrl");
                        if (google == null && (url.StartsWith("https://accounts.google.com") || url.StartsWith("https://workspace.google.com"))) google = Json.Str(d, "webSocketDebuggerUrl");
                        if (firstPage == null) firstPage = Json.Str(d, "webSocketDebuggerUrl");
                    }
                if (google != null) return google;
                if (firstPage == null) break;
                Thread.Sleep(1000);
            }
            if (firstPage != null) return firstPage;   // EnsureGoogle ינווט אותה לגוגל צ'אט
            var n = Json.Obj(Json.Parse(Http("/json/new?https://chat.google.com/", "PUT", 6000)));
            return Json.Str(n, "webSocketDebuggerUrl");
        }
    }

    /// <summary>הסתרה והצגה של חלונות ה-Chrome שלנו (גם משורת המשימות), דרך Windows.</summary>
    static class Win
    {
        delegate bool EnumProc(IntPtr h, IntPtr l);
        [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc f, IntPtr l);
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
        [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
        [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr h, int cmd);
        [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder sb, int n);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowTextLength(IntPtr h);

        static HashSet<uint> pids = new HashSet<uint>();
        static DateTime pidsAt = DateTime.MinValue;

        /// <summary>התהליכים של ה-Chrome שלנו (לפי תיקיית הפרופיל בשורת הפקודה).</summary>
        static HashSet<uint> Pids()
        {
            if ((DateTime.UtcNow - pidsAt).TotalSeconds < 15 && pids.Count > 0) return pids;
            var set = new HashSet<uint>();
            string mark = Browser.ProfileDir.ToLowerInvariant();
            using (var s = new System.Management.ManagementObjectSearcher("SELECT ProcessId, CommandLine FROM Win32_Process WHERE Name='chrome.exe' OR Name='msedge.exe'"))
                foreach (System.Management.ManagementObject o in s.Get())
                {
                    string cl = (o["CommandLine"] as string ?? "").ToLowerInvariant();
                    if (cl.Contains(mark)) set.Add(Convert.ToUInt32(o["ProcessId"]));
                }
            pids = set; pidsAt = DateTime.UtcNow;
            return set;
        }

        static List<IntPtr> Windows(bool visible)
        {
            var ps = Pids();
            var res = new List<IntPtr>();
            if (ps.Count == 0) return res;
            var sb = new StringBuilder(64);
            EnumWindows((h, l) =>
            {
                uint pid;
                GetWindowThreadProcessId(h, out pid);
                if (!ps.Contains(pid) || IsWindowVisible(h) != visible) return true;
                sb.Length = 0;
                GetClassName(h, sb, 64);
                if (sb.ToString() == "Chrome_WidgetWin_1" && GetWindowTextLength(h) > 0) res.Add(h);
                return true;
            }, IntPtr.Zero);
            return res;
        }

        public static void Hide()
        {
            foreach (var h in Windows(true)) ShowWindow(h, 0);   // SW_HIDE
        }

        public static void Show()
        {
            pidsAt = DateTime.MinValue;
            foreach (var h in Windows(false)) { ShowWindow(h, 5); SetForegroundWindow(h); }   // SW_SHOW
        }
    }

    /// <summary>חשבון גוגל אחד: כרטיסייה משלו ב-Chrome (u/0, u/1…).</summary>
    class Account
    {
        public int Index;
        public string TargetId = "";
        public Cdp Cdp;
        public GChat Gc;
        public volatile bool Ok;
        public string Status = "מתחבר…";
        public string Key = "", Name = "", Email = "", Avatar = "";
        public bool ShownLogin, ShownSlow, Navigated;
        public DateTime LoadingSince = DateTime.MinValue, LastFullWorld = DateTime.MinValue;
        public readonly Dictionary<string, long> SortSeen = new Dictionary<string, long>();
        public readonly Dictionary<string, string> RowSent = new Dictionary<string, string>();

        public void Drop()
        {
            Ok = false;
            var c = Cdp; Cdp = null;
            if (c != null) c.Dispose();
        }

        public string Label { get { return Email.Length > 0 ? Email : Name.Length > 0 ? Name : "חשבון " + (Index + 1); } }
    }

    // ---------------------------------------------------------------- גוגל צ'אט

    class GChatException : Exception
    {
        public readonly int Status;
        public GChatException(int status, string m) : base(m) { Status = status; }
    }

    class Member { public string Id, Name, Avatar, Email; }

    class ChatRow
    {
        public string Gid, Name = "", Avatar = "";
        public bool Dm;
        public List<string> Users = new List<string>();
        public long Sort, LastRead, Unread;
        public bool Muted;
        public Dictionary<string, object> Snippet;

        public Dictionary<string, object> ToJson()
        {
            var d = Json.New("g", Gid, "n", Name, "dm", Dm, "u", Users, "s", Sort, "lr", LastRead, "un", Unread);
            if (Avatar.Length > 0) d["av"] = Avatar;
            if (Snippet != null) d["sn"] = Snippet;
            return d;
        }
    }

    /// <summary>הבקשות לגוגל צ'אט, דרך הדף הפתוח ב-Chrome.</summary>
    class GChat
    {
        readonly Cdp cdp;
        public readonly int Index;          // מספר החשבון בכרום (u/0, u/1…)
        public string SelfId = "";
        public readonly Dictionary<string, Member> Users = new Dictionary<string, Member>();

        public GChat(Cdp cdp) : this(cdp, 0) { }
        public GChat(Cdp cdp, int index) { this.cdp = cdp; Index = index; }

        string AuthUser { get { return Index > 0 ? "&authuser=" + Index : ""; } }

        static object[] Hdr() { return new object[] { 0, 3, 1, "iw", null }; }

        static object[] WithHeader(object[] body)
        {
            // ברוב הבקשות כותרת הבקשה היא שדה 100
            if (body.Length < 100) Array.Resize(ref body, 100);
            body[99] = Hdr();
            return body;
        }

        public object[] Call(string ep, object[] body)
        {
            string js = "(async()=>{window.__btc=(window.__btc||1000)+1;" +
                "const r=await fetch('/u/" + Index + "/api/" + ep + "?c='+window.__btc,{method:'POST',credentials:'include'," +
                "headers:{'Content-Type':'application/json','Accept-Language':'iw','X-Framework-Xsrf-Token':((window.WIZ_global_data||{}).SMqcke||'')}," +
                "body:" + Json.Ser(Json.Ser(body)) + "});return {s:r.status,t:await r.text()};})()";
            var v = Json.Obj(cdp.Eval(js, 45000));
            if (v == null) throw new GChatException(0, "אין תשובה מהדף");
            int status = (int)Json.Long(v, "s");
            string text = Json.Str(v, "t");
            if (status != 200)
            {
                if (text.IndexOf("netfree", StringComparison.OrdinalIgnoreCase) >= 0) throw new GChatException(status, "נטפרי חסמה את הבקשה (" + ep + ")");
                throw new GChatException(status, "שגיאה " + status + " (" + ep + ")");
            }
            int nl = text.IndexOf('\n');
            if (text.StartsWith(")]}'") && nl > 0) text = text.Substring(nl + 1);
            var arr = Json.Parse(text) as object[];
            if (arr == null || arr.Length == 0) throw new GChatException(status, "תשובה ריקה (" + ep + ")");
            return arr[0] as object[];
        }

        /// <summary>1 = מחובר, 2 = צריך להתחבר (דף ההתחברות של גוגל), 3 = החשבון הזה לא קיים בכרום, 0 = עדיין נטען.</summary>
        public int LoginState(out string url)
        {
            var v = Json.Obj(cdp.Eval("({u:location.href,t:!!(window.WIZ_global_data||{}).SMqcke})", 8000));
            url = Json.Str(v, "u");
            if (url.StartsWith("https://chat.google.com") && Json.Bool(v, "t", false))
            {
                // חשבון נוסף: הכרטיסייה חייבת להיות על u/N שלו (אחרת גוגל העבירה אותה לחשבון אחר)
                if (Index > 0 && url.IndexOf("/u/" + Index + "/", StringComparison.Ordinal) < 0) return 3;
                return 1;
            }
            if (url.StartsWith("https://accounts.google.com") || url.StartsWith("https://workspace.google.com") || url.StartsWith("https://www.google.com")) return 2;
            return 0;
        }

        public bool LoggedIn() { string u; return LoginState(out u) == 1; }

        public void LoadSelf()
        {
            var m = Call("get_self_user_status", WithHeader(new object[0]));
            SelfId = Pb.S(m, 1, 1, 1);
        }

        // ---------------- רשימת הצ'אטים

        public List<ChatRow> World(int count)
        {
            // PaginatedWorldRequest: 1 כותרת, 2 בקשות מקטע (גודל עמוד, 5 = כמה עם קטע הודעה), 4 אפשרויות, 5 מרחבים
            var req = Pb.M(1, Hdr(), 2, new object[] { Pb.M(1, count, 5, count) }, 4, new object[] { 1 }, 5, true);
            var m = Call("paginated_world", req);
            var rows = new List<ChatRow>();
            var items = new List<object>(Pb.A(m, 4));
            foreach (var sec in Pb.A(m, 1)) foreach (var it in Pb.A(sec, 2)) items.Add(it);
            var seen = new HashSet<string>();
            foreach (var it in items)
            {
                var rs = Pb.F(it, 4);
                string gid = Pb.GroupStr(Pb.F(it, 1));
                if (gid.Length == 0 || !seen.Add(gid)) continue;
                if (Pb.B(rs, 15) || Pb.L(rs, 9) > 0) continue;                  // חסום / מוסתר
                long ms = Pb.L(rs, 16);
                if (ms != 0 && ms != 2) continue;                                  // לא חבר בצ'אט
                var row = new ChatRow { Gid = gid, Dm = gid.StartsWith("dm/") };
                row.Sort = Pb.L(it, 3);
                row.LastRead = Pb.L(rs, 2);
                row.Unread = Pb.L(rs, 4);
                row.Name = Pb.S(it, 5);
                string av = Pb.S(it, 16);
                if (av.StartsWith("//")) av = "https:" + av;
                row.Avatar = av;
                foreach (var u in Pb.A(it, 6, 1)) { string id = Pb.S(u, 1); if (id.Length > 0 && id != SelfId) row.Users.Add(id); }
                if (row.Users.Count == 0)
                    foreach (var u in Pb.A(it, 20, 1)) { string id = Pb.S(u, 1); if (id.Length > 0 && id != SelfId) row.Users.Add(id); }
                if (row.Name.Length == 0) row.Name = Pb.S(it, 20, 3);
                var msg = Pb.F(it, 13);
                if (msg != null)
                {
                    var sm = ParseMessage(gid, msg, false, null);
                    if (sm != null) row.Snippet = sm;
                }
                rows.Add(row);
            }
            rows.Sort((a, b) => b.Sort.CompareTo(a.Sort));
            return rows;
        }

        // ---------------- הודעות

        /// <summary>
        /// הודעות של צ'אט. before=0 → העמוד האחרון. אחרת: נושאים שזמן המיון שלהם מוקדם מ-before.
        /// </summary>
        public List<Dictionary<string, object>> Messages(string gid, int topics, long before, out bool more)
        {
            var body = Pb.M(8, Pb.GroupId(gid), 2, before > 0 ? Math.Max(topics * 2, 20) : topics, 5, new object[] { 3, 4 }, 6, 1000, 7, 30, 11, 2);
            if (before > 0) body[3] = Pb.M(5, new object[] { before });
            var m = Call("list_topics", WithHeader(body));
            var list = new List<Dictionary<string, object>>();
            int older = 0;
            foreach (var tp in Pb.A(m, 1))
            {
                long sort = Pb.L(tp, 2);
                if (before > 0 && sort >= before) continue;
                older++;
                var reps = Pb.A(tp, 7);
                Dictionary<string, object> head = null;
                for (int i = 0; i < reps.Length; i++)
                {
                    var d = ParseMessage(gid, reps[i], i > 0, reps.Length > 1 && i == 0 ? (int?)reps.Length : null);
                    if (d == null) continue;
                    if (i == 0) head = d;
                    list.Add(d);
                }
                if (head != null && reps.Length > 1)
                {
                    // סיכום השרשור: זמן התשובה האחרונה ומי ענה (עד 3, האחרונים קודם)
                    head["rl"] = Pb.L(reps[reps.Length - 1], 3);
                    var who = new List<string>();
                    for (int i = reps.Length - 1; i >= 1 && who.Count < 3; i--)
                    {
                        string s = Pb.S(reps[i], 2, 1, 1);
                        if (s.Length > 0 && !who.Contains(s)) who.Add(s);
                    }
                    head["rp"] = who;
                }
            }
            more = before > 0 ? older > 0 : !Pb.B(m, 4);
            list.Sort((a, b) => ((long)a["time"]).CompareTo((long)b["time"]));
            return list;
        }

        public Dictionary<string, object> ParseMessage(string gid, object msg, bool inThread, int? threadSize)
        {
            string id = Pb.S(msg, 1, 2);
            if (id.Length == 0) return null;
            string topic = Pb.S(msg, 1, 1, 4, 2);
            string sender = Pb.S(msg, 2, 1, 1);
            if (Pb.L(msg, 8) > 0)
            {
                // נמחקה: נשאר רק סימון "ההודעה נמחקה", כמו בגוגל צ'אט
                var del = Json.New("g", gid, "id", id, "th", topic, "s", sender, "time", Pb.L(msg, 3), "text", "", "del", true);
                if (inThread) del["in"] = true;
                if (sender == SelfId) del["me"] = true;
                return del;
            }
            string text = Pb.S(msg, 10);
            var d = Json.New("g", gid, "id", id, "th", topic, "s", sender, "time", Pb.L(msg, 3), "text", text);
            if (sender.Length > 0 && sender == SelfId) d["me"] = true;
            if (Pb.L(msg, 17) > 0) d["ed"] = true;
            if (Pb.L(msg, 28) == 2) d["sys"] = true;
            if (inThread) d["in"] = true;
            if (threadSize.HasValue) d["rc"] = threadSize.Value - 1;
            string senderName = Pb.S(msg, 2, 2);
            if (sender.Length > 0 && senderName.Length > 0) Remember(sender, senderName, Pb.S(msg, 2, 3), Pb.S(msg, 2, 4));

            var atts = new List<object>();
            var auds = new List<object>();
            var notes = new List<string>();
            var fmts = new List<object>();
            foreach (var an in Pb.A(msg, 11))
            {
                long type = Pb.L(an, 1);
                var fm = Pb.F(an, 8);
                if (fm != null)
                {
                    // עיצוב טקסט: [התחלה, אורך, סוג] — 1 מודגש, 2 נטוי, 3 קו חוצה, 4/5/7 קוד, 8 קו תחתון
                    long ft = Pb.L(fm, 1);
                    if (ft == 1 || ft == 2 || ft == 3 || ft == 4 || ft == 5 || ft == 7 || ft == 8)
                        fmts.Add(new object[] { Pb.L(an, 2), Pb.L(an, 3), ft });
                    continue;
                }
                var up = Pb.F(an, 10);
                if (up != null)
                {
                    string ct = Pb.S(up, 4), tok = Pb.S(up, 1), nm = Pb.S(up, 3);
                    if (ct.StartsWith("image/") && tok.Length > 0) atts.Add(Json.New("k", tok, "ct", ct, "n", nm));
                    else if (ct.StartsWith("audio/") && tok.Length > 0)
                    {
                        // הודעה קולית: שדה 18 = [משך {שניות, ננו}, גל קול (עוצמות)]
                        var vm = Pb.F(up, 18);
                        long ms = Pb.L(vm, 1, 1) * 1000 + Pb.L(vm, 1, 2) / 1000000;
                        var wave = new List<object>();
                        foreach (var x in Pb.A(vm, 2)) { try { wave.Add(Convert.ToInt32(x)); } catch { } }
                        auds.Add(Json.New("k", tok, "ct", ct, "n", nm, "d", ms, "w", wave, "tr", Pb.S(up, 20)));   // 20 = תמלול
                    }
                    else notes.Add((ct.StartsWith("video/") ? "🎬 " : "📎 ") + (nm.Length > 0 ? nm : "קובץ"));
                    continue;
                }
                var drive = Pb.F(an, 4);
                if (drive != null) { string t = Pb.S(drive, 2); notes.Add("📄 " + (t.Length > 0 ? t : "קובץ בדרייב")); continue; }
                if (type == 12 && text.Length == 0)
                {
                    var mc = Pb.F(an, 11);
                    long kind = Pb.L(mc, 2);
                    notes.Add(kind == 2 || kind == 3 ? "הצטרפו לצ'אט" : kind == 4 || kind == 5 ? "יצאו מהצ'אט" : "עדכון חברים בצ'אט");
                    d["sys"] = true;
                }
                if (type == 11 && text.Length == 0) notes.Add("📹 שיחת וידאו");
            }
            if (atts.Count > 0) d["a"] = atts;
            if (auds.Count > 0) d["au"] = auds;
            if (fmts.Count > 0) d["f"] = fmts;
            if (notes.Count > 0) d["note"] = string.Join("\n", notes.ToArray());
            if (text.Length == 0 && atts.Count == 0 && auds.Count == 0 && notes.Count == 0) return null;

            var reacts = new List<object>();
            foreach (var r in Pb.A(msg, 21))
            {
                string e = Pb.S(r, 1, 1);
                long n = Pb.L(r, 2);
                if (e.Length == 0 || n <= 0) continue;
                var rd = Json.New("e", e, "n", n);
                if (Pb.B(r, 3)) rd["m"] = true;
                reacts.Add(rd);
            }
            if (reacts.Count > 0) d["r"] = reacts;

            var rt = Pb.F(msg, 37);
            if (rt != null)
            {
                string rs = Pb.S(rt, 4, 1);
                if (rs.Length == 0) rs = Pb.S(rt, 5, 1, 1);
                d["q"] = Json.New("s", rs, "text", Pb.S(rt, 6), "id", Pb.S(rt, 1, 2));
            }
            return d;
        }

        void Remember(string id, string name, string avatar, string email)
        {
            lock (Users)
            {
                Member m;
                if (!Users.TryGetValue(id, out m)) Users[id] = m = new Member { Id = id };
                if (!string.IsNullOrEmpty(name)) m.Name = name;
                if (!string.IsNullOrEmpty(avatar)) m.Avatar = avatar.StartsWith("//") ? "https:" + avatar : avatar;
                if (!string.IsNullOrEmpty(email)) m.Email = email;
            }
        }

        /// <summary>משלים שמות ותמונות של משתמשים שעוד לא מוכרים.</summary>
        public void ResolveUsers(IEnumerable<string> ids)
        {
            var missing = new List<string>();
            lock (Users)
                foreach (var id in ids)
                    if (!string.IsNullOrEmpty(id) && !missing.Contains(id) && (!Users.ContainsKey(id) || string.IsNullOrEmpty(Users[id].Name))) missing.Add(id);
            for (int i = 0; i < missing.Count; i += 40)
            {
                var batch = new List<object>();
                for (int j = i; j < Math.Min(missing.Count, i + 40); j++) batch.Add(Pb.M(1, new object[] { missing[j] }));
                var m = Call("get_members", WithHeader(Pb.M(1, batch.ToArray())));
                foreach (var mem in Pb.A(m, 1))
                {
                    var u = Pb.F(mem, 1);
                    string id = Pb.S(u, 1, 1);
                    if (id.Length > 0) Remember(id, Pb.S(u, 2), Pb.S(u, 3), Pb.S(u, 4));
                }
            }
        }

        // ---------------- פעולות

        /// <summary>הערות עיצוב לשליחה, מתוך [[התחלה, אורך, סוג], …] שהנגן שולח.</summary>
        public static List<object> FormatAnnotations(object fmts)
        {
            var list = new List<object>();
            var arr = fmts as object[];
            if (arr == null) return list;
            foreach (var o in arr)
            {
                var f = o as object[];
                if (f == null || f.Length < 3) continue;
                long s = Convert.ToInt64(f[0]), l = Convert.ToInt64(f[1]), t = Convert.ToInt64(f[2]);
                if (l <= 0) continue;
                // Annotation: 1 סוג (8 = עיצוב), 2 התחלה, 3 אורך, 8 פרטי העיצוב, 20 = לא להציג כשבב
                list.Add(Pb.M(1, 8, 2, s, 3, l, 8, Pb.M(1, t), 20, 3));
            }
            return list;
        }

        /// <summary>שליחת הודעה. quoteId (לא חובה) = ציטוט של הודעה קיימת, כמו "ציטוט בתשובה" בגוגל צ'אט.</summary>
        public Dictionary<string, object> Send(string gid, string text, string thread, string localId, string quoteId, string quoteThread, long quoteTime)
        {
            return Send(gid, text, thread, localId, quoteId, quoteThread, quoteTime, null);
        }

        public Dictionary<string, object> Send(string gid, string text, string thread, string localId, string quoteId, string quoteThread, long quoteTime, List<object> annotations)
        {
            // MessageInfo: 1 = קבלת עיצוב, 3 = הודעה מצוטטת (SendReplyTarget: מזהה + זמן יצירה)
            object[] info = Pb.M(1, true);
            if (!string.IsNullOrEmpty(quoteId))
                info = Pb.M(1, true, 3, Pb.M(1, Pb.MessageId(gid, string.IsNullOrEmpty(quoteThread) ? quoteId : quoteThread, quoteId), 2, quoteTime));
            object ann = annotations != null && annotations.Count > 0 ? annotations.ToArray() : null;
            object[] m;
            if (string.IsNullOrEmpty(thread))
            {
                m = Call("create_topic", WithHeader(Pb.M(5, Pb.GroupId(gid), 2, text, 3, ann, 4, localId, 8, true, 9, info)));
                var reps = Pb.A(m, 1, 7);
                return reps.Length > 0 ? ParseMessage(gid, reps[0], false, null) : null;
            }
            m = Call("create_message", WithHeader(Pb.M(1, Pb.M(4, Pb.TopicId(gid, thread)), 2, text, 3, ann, 4, localId, 7, info)));
            return ParseMessage(gid, Pb.F(m, 1), true, null);
        }

        public Dictionary<string, object> Edit(string gid, string thread, string msgId, string text, List<object> annotations)
        {
            object ann = annotations != null && annotations.Count > 0 ? annotations.ToArray() : null;
            var m = Call("edit_message", WithHeader(Pb.M(1, Pb.MessageId(gid, string.IsNullOrEmpty(thread) ? msgId : thread, msgId), 2, text, 3, ann, 5, Pb.M(1, true))));
            return ParseMessage(gid, Pb.F(m, 1), !string.IsNullOrEmpty(thread) && thread != msgId, null);
        }

        /// <summary>הצ'אט האישי הקיים עם משתמש (מחפש בכל רשימת הצ'אטים), או null.</summary>
        public ChatRow FindDm(string userId)
        {
            foreach (var r in World(400))
                if (r.Dm && r.Users.Count == 1 && r.Users[0] == userId) return r;
            return null;
        }

        public void Delete(string gid, string thread, string msgId)
        {
            Call("delete_message", WithHeader(Pb.M(1, Pb.MessageId(gid, string.IsNullOrEmpty(thread) ? msgId : thread, msgId))));
        }

        /// <summary>מעלה תמונה לגוגל צ'אט ומחזיר הערת צירוף (Annotation) לשליחה עם ההודעה.</summary>
        public object[] Upload(string gid, byte[] data, string name, string ctype) { return Upload(gid, data, name, ctype, 0, null); }

        /// <summary>voiceMs > 0: הודעה קולית — מוסיפים את המשך ואת גל הקול, כמו בהקלטה מגוגל צ'אט.</summary>
        public object[] Upload(string gid, byte[] data, string name, string ctype, long voiceMs, object[] wave)
        {
            string plain = gid.Substring(gid.IndexOf('/') + 1);
            string js = "(async()=>{const raw=Uint8Array.from(atob(" + Json.Ser(Convert.ToBase64String(data)) + "),c=>c.charCodeAt(0));" +
                "const r1=await fetch('/uploads?group_id=" + Uri.EscapeDataString(plain) + "&key=AIzaSyD7InnYR3VKdb4j2rMUEbTCIr2VyEazl6k" + AuthUser + "',{method:'POST',credentials:'include',headers:{" +
                "'x-goog-upload-protocol':'resumable','x-goog-upload-command':'start','x-goog-upload-content-length':String(raw.length)," +
                "'x-goog-upload-content-type':" + Json.Ser(ctype) + ",'x-goog-upload-file-name':" + Json.Ser(name) + "," +
                "'X-Framework-Xsrf-Token':((window.WIZ_global_data||{}).SMqcke||'')}});" +
                "const u=r1.headers.get('x-goog-upload-url');if(!u)return {s:r1.status,e:'start'};" +
                "const r2=await fetch(u,{method:'PUT',credentials:'include',headers:{'x-goog-upload-command':'upload, finalize','x-goog-upload-protocol':'resumable','x-goog-upload-offset':'0'},body:raw});" +
                "return {s:r2.status,t:await r2.text()};})()";
            var v = Json.Obj(cdp.Eval(js, 120000));
            if (v == null || Json.Long(v, "s") != 200) throw new GChatException((int)Json.Long(v, "s"), "העלאת התמונה נכשלה");
            var meta = Proto.ToPblite(Convert.FromBase64String(Json.Str(v, "t").Trim()));
            if (voiceMs > 0)
            {
                if (meta.Length < 18) Array.Resize(ref meta, 18);
                meta[17] = new object[] { new object[] { voiceMs / 1000, (voiceMs % 1000) * 1000000 }, wave ?? new object[0] };
            }
            // Annotation: 1 סוג (13 = קובץ שהועלה), 10 פרטי הקובץ, 20 = להציג
            return Pb.M(1, 13, 10, meta, 20, 1);
        }

        public void React(string gid, string thread, string msgId, string emoji, bool remove)
        {
            Call("update_reaction", WithHeader(Pb.M(1, Pb.MessageId(gid, string.IsNullOrEmpty(thread) ? msgId : thread, msgId), 2, Pb.M(1, emoji), 3, remove ? 2 : 1)));
        }

        public void MarkRead(string gid, long time)
        {
            Call("mark_group_readstate", WithHeader(Pb.M(1, Pb.GroupId(gid), 2, time)));
        }

        /// <summary>מוריד קובץ מתוך הדף (עם העוגיות של החשבון) ומחזיר את הבתים.</summary>
        public byte[] Fetch(string url)
        {
            string js = "(async()=>{const r=await fetch(" + Json.Ser(url) + ",{credentials:'include'});if(!r.ok)return {s:r.status};" +
                "const b=await r.blob();const d=await new Promise((ok,no)=>{const f=new FileReader();f.onload=()=>ok(f.result);f.onerror=no;f.readAsDataURL(b);});" +
                "return {s:200,d:d};})()";
            var v = Json.Obj(cdp.Eval(js, 60000));
            if (v == null || Json.Long(v, "s") != 200) throw new GChatException((int)Json.Long(v, "s"), "ההורדה נכשלה");
            string data = Json.Str(v, "d");
            int comma = data.IndexOf(',');
            return Convert.FromBase64String(comma >= 0 ? data.Substring(comma + 1) : data);
        }

        /// <summary>קובץ קול (או כל קובץ) כמו שהוא, בלי שינוי גודל.</summary>
        public byte[] Download(string token, string ctype)
        {
            // הקישור מפנה לשרת קבצים אחר, ומהדף עצמו הדפדפן חוסם את הקריאה (CORS).
            // לכן מורידים דרך Chrome עצמו (עם העוגיות של החשבון), בחלקים.
            string url = "https://chat.google.com/api/get_attachment_url?url_type=DOWNLOAD_URL&content_type=" + Uri.EscapeDataString(ctype) +
                "&attachment_token=" + Uri.EscapeDataString(token) + AuthUser;
            var tree = Json.Obj(cdp.Send("Page.getFrameTree", null, 8000)["frameTree"]);
            string frameId = Json.Str(Json.Obj(tree["frame"]), "id");
            var r = cdp.Send("Network.loadNetworkResource", Json.New("frameId", frameId, "url", url,
                "options", Json.New("disableCache", true, "includeCredentials", true)), 60000);
            var res = Json.Obj(r.ContainsKey("resource") ? r["resource"] : null);
            if (res == null || !Json.Bool(res, "success", false)) throw new GChatException((int)Json.Long(res, "httpStatusCode"), "ההורדה נכשלה");
            string h = Json.Str(res, "stream");
            var ms = new MemoryStream();
            try
            {
                while (ms.Length < 30 * 1024 * 1024)
                {
                    var c = cdp.Send("IO.read", Json.New("handle", h, "size", 262144), 30000);
                    string data = Json.Str(c, "data");
                    byte[] b = Json.Bool(c, "base64Encoded", false) ? Convert.FromBase64String(data) : Encoding.GetEncoding(28591).GetBytes(data);
                    ms.Write(b, 0, b.Length);
                    if (Json.Bool(c, "eof", false)) break;
                }
            }
            finally { try { cdp.Send("IO.close", Json.New("handle", h), 5000); } catch { } }
            return ms.ToArray();
        }

        public byte[] Attachment(string token, string ctype)
        {
            return Fetch("/api/get_attachment_url?url_type=FIFE_URL&content_type=" + Uri.EscapeDataString(ctype) +
                "&attachment_token=" + Uri.EscapeDataString(token) + "&sz=w1080" + AuthUser);
        }
    }

    /// <summary>ממיר protobuf בינארי (התשובה של העלאת קובץ) לפורמט PBLite.</summary>
    static class Proto
    {
        static ulong Var(byte[] b, ref int i)
        {
            ulong r = 0; int s = 0;
            while (true) { byte x = b[i++]; r |= (ulong)(x & 0x7F) << s; s += 7; if ((x & 0x80) == 0) return r; }
        }

        static bool LooksText(byte[] b)
        {
            try
            {
                string s = new UTF8Encoding(false, true).GetString(b);
                foreach (char c in s) if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') return false;
                return true;
            }
            catch { return false; }
        }

        public static object[] ToPblite(byte[] b)
        {
            var fields = new SortedDictionary<int, object>();
            int i = 0;
            while (i < b.Length)
            {
                ulong key = Var(b, ref i);
                int f = (int)(key >> 3), wt = (int)(key & 7);
                object v;
                if (wt == 0) v = (long)Var(b, ref i);
                else if (wt == 2)
                {
                    int len = (int)Var(b, ref i);
                    var sub = new byte[len];
                    Array.Copy(b, i, sub, 0, len);
                    i += len;
                    if (LooksText(sub)) v = Encoding.UTF8.GetString(sub);
                    else { try { v = ToPblite(sub); } catch { v = Convert.ToBase64String(sub); } }
                }
                else if (wt == 1) { v = BitConverter.ToInt64(b, i); i += 8; }
                else if (wt == 5) { v = BitConverter.ToInt32(b, i); i += 4; }
                else throw new FormatException("wire type " + wt);
                if (f > 0) fields[f] = v;
            }
            int max = 0;
            foreach (var k in fields.Keys) max = Math.Max(max, k);
            var arr = new object[max];
            foreach (var kv in fields) arr[kv.Key - 1] = kv.Value;
            return arr;
        }
    }

    // ---------------------------------------------------------------- תמונות

    static class ImageTools
    {
        public static byte[] ToJpeg(byte[] raw, int maxSide, int quality, bool square)
        {
            byte[] result = null;
            Exception error = null;
            var t = new Thread(() =>
            {
                try { result = Convert(raw, maxSide, quality, square); }
                catch (Exception e) { error = e; }
            });
            t.SetApartmentState(ApartmentState.STA);
            t.IsBackground = true;
            t.Start();
            t.Join(60000);
            if (error != null) throw new Exception("לא ניתן לפתוח את התמונה");
            if (result == null) throw new Exception("עיבוד התמונה לקח יותר מדי זמן");
            return result;
        }

        static byte[] Convert(byte[] raw, int maxSide, int quality, bool square)
        {
            using (var ms = new MemoryStream(raw))
            {
                var dec = System.Windows.Media.Imaging.BitmapDecoder.Create(ms,
                    System.Windows.Media.Imaging.BitmapCreateOptions.IgnoreColorProfile,
                    System.Windows.Media.Imaging.BitmapCacheOption.OnLoad);
                var frame = dec.Frames[0];
                double w = frame.PixelWidth, h = frame.PixelHeight;
                System.Windows.Rect src = new System.Windows.Rect(0, 0, w, h);
                int ow, oh;
                if (square)
                {
                    ow = oh = maxSide;
                    double s = Math.Min(w, h);
                    double scale = maxSide / s;
                    src = new System.Windows.Rect(-(w - s) / 2 * scale, -(h - s) / 2 * scale, w * scale, h * scale);
                }
                else
                {
                    double scale = Math.Min(1.0, maxSide / Math.Max(w, h));
                    ow = Math.Max(1, (int)Math.Round(w * scale)); oh = Math.Max(1, (int)Math.Round(h * scale));
                    src = new System.Windows.Rect(0, 0, ow, oh);
                }
                var dv = new System.Windows.Media.DrawingVisual();
                using (var dc = dv.RenderOpen())
                {
                    dc.DrawRectangle(System.Windows.Media.Brushes.White, null, new System.Windows.Rect(0, 0, ow, oh));
                    dc.DrawImage(frame, src);
                }
                System.Windows.Media.RenderOptions.SetBitmapScalingMode(dv, System.Windows.Media.BitmapScalingMode.HighQuality);
                var rtb = new System.Windows.Media.Imaging.RenderTargetBitmap(ow, oh, 96, 96, System.Windows.Media.PixelFormats.Pbgra32);
                rtb.Render(dv);
                var enc = new System.Windows.Media.Imaging.JpegBitmapEncoder { QualityLevel = quality };
                enc.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(rtb));
                using (var outMs = new MemoryStream())
                {
                    enc.Save(outMs);
                    return outMs.ToArray();
                }
            }
        }
    }

    // ---------------------------------------------------------------- הליבה

    class Core
    {
        public State St;
        public event Action Changed;
        readonly object stLock = new object();
        volatile bool running;

        // מצב החיבור לנגן
        public volatile bool Connected;
        public string PeerName = "", Searching = "";
        Stream stream;
        int linkGen;
        readonly object writeLock = new object();
        DateTime lastRecv, lastPing;
        readonly AutoResetEvent linkWake = new AutoResetEvent(false);

        // מצב גוגל צ'אט: חשבון לכל כרטיסייה (u/0, u/1…) באותו Chrome מוסתר
        public volatile bool GOk;
        public string GStatus = "מתחבר לגוגל צ'אט…";
        readonly List<Account> accounts = new List<Account>();
        public volatile bool ChromeShown;
        volatile bool shownForLogin;
        string lastAccSig = "";
        readonly AutoResetEvent googleWake = new AutoResetEvent(false);

        // עבודה מול גוגל: תור אחד, לפי הסדר
        readonly BlockingCollection<Action> jobs = new BlockingCollection<Action>();
        volatile bool pollQueued;
        DateTime lastPoll = DateTime.MinValue;
        readonly HashSet<string> usersSent = new HashSet<string>();
        bool syncPending;
        Dictionary<string, long> phoneKnown = new Dictionary<string, long>();
        public int SentToday; DateTime sentDay = DateTime.Today;

        readonly List<string> log = new List<string>();
        public List<string> LogLines { get { lock (log) return new List<string>(log); } }

        public List<Account> Accounts { get { lock (accounts) return new List<Account>(accounts); } }

        public void Start()
        {
            St = State.Load();
            running = true;
            Spawn(LinkLoop, "link");
            Spawn(GoogleLoop, "google");
            Spawn(WorkLoop, "work");
            Log("התוכנה הופעלה");
        }

        public void Stop()
        {
            running = false;
            linkWake.Set(); googleWake.Set();
            jobs.CompleteAdding();
            Drop(null);
            lock (stLock) St.Save();
        }

        static void Spawn(ThreadStart f, string name)
        {
            new Thread(f) { IsBackground = true, Name = name }.Start();
        }

        public void Log(string s)
        {
            lock (log)
            {
                log.Insert(0, DateTime.Now.ToString("HH:mm:ss") + "   " + s);
                if (log.Count > 200) log.RemoveAt(log.Count - 1);
                try
                {
                    // יומן גם בקובץ (לבדיקת תקלות), עד חצי מגה
                    Directory.CreateDirectory(State.Dir);
                    string f = Path.Combine(State.Dir, "log.txt");
                    if (File.Exists(f) && new FileInfo(f).Length > 512 * 1024) File.Delete(f);
                    File.AppendAllText(f, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + s + "\r\n", Encoding.UTF8);
                }
                catch { }
            }
            Fire();
        }

        void Fire()
        {
            var h = Changed;
            if (h != null) try { h(); } catch { }
        }

        public void SaveState() { lock (stLock) St.Save(); }

        public void SetDevice(string addr)
        {
            lock (stLock) { St.DeviceAddr = addr ?? ""; St.Save(); }
            if (Connected) Drop("החלפת נגן");
            linkWake.Set();
        }

        public void Reconnect() { linkWake.Set(); googleWake.Set(); }

        public static long NowUs() { return (DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).Ticks / 10; }

        // ---------------- גוגל

        Account Find(int index)
        {
            lock (accounts) return accounts.Find(a => a.Index == index);
        }

        void GoogleLoop()
        {
            while (running)
            {
                try { EnsureGoogle(); }
                catch (Exception e) { DropAll(); SetG(false, "אין חיבור ל-Chrome (" + e.Message + ")"); }
                try { if (St.HideChrome && !ChromeShown) Win.Hide(); } catch { }
                if (GOk && Connected && !pollQueued && (DateTime.UtcNow - lastPoll).TotalSeconds >= 4)
                {
                    pollQueued = true;
                    jobs.Add(() => { pollQueued = false; Poll(); });
                }
                googleWake.WaitOne(GOk ? 1000 : 3000);
            }
        }

        void EnsureGoogle()
        {
            if (!Browser.Alive())
            {
                DropAll();
                if (Browser.FindExe() == null) { SetG(false, "לא נמצא Chrome או Edge במחשב"); return; }
                SetG(false, "פותח את גוגל צ'אט…");
                Browser.Launch(St.HideChrome && !ChromeShown);
                for (int i = 0; i < 30 && !Browser.Alive(); i++) Thread.Sleep(500);
                if (!Browser.Alive()) { SetG(false, "Chrome לא נפתח"); return; }
                Thread.Sleep(2500);
            }
            List<int> idx;
            lock (stLock) idx = new List<int>(St.Accounts);
            lock (accounts)
            {
                foreach (var a in accounts.FindAll(x => !idx.Contains(x.Index))) a.Drop();
                accounts.RemoveAll(x => !idx.Contains(x.Index));
                foreach (int n in idx) if (accounts.Find(x => x.Index == n) == null) accounts.Add(new Account { Index = n });
                accounts.Sort((x, y) => x.Index.CompareTo(y.Index));
            }
            foreach (var a in Accounts)
            {
                try { EnsureAccount(a); }
                catch (Exception e) { a.Drop(); a.Status = "אין חיבור לכרטיסייה (" + e.Message + ")"; }
            }
            UpdateSummary();
        }

        static string ChatUrl(int index) { return index > 0 ? "https://chat.google.com/u/" + index + "/" : "https://chat.google.com/"; }

        void EnsureAccount(Account a)
        {
            if (a.Cdp != null && !a.Cdp.Alive) a.Drop();
            if (a.Cdp == null)
            {
                string tid = a.TargetId;
                string ws = Browser.TabFor(a.Index, ref tid);
                a.TargetId = tid;
                a.Cdp = Cdp.Connect(ws);
                a.Gc = new GChat(a.Cdp, a.Index);
            }
            if (a.Ok) return;
            string url;
            int ls = a.Gc.LoginState(out url);
            if (ls == 0)
            {
                if (a.LoadingSince == DateTime.MinValue) a.LoadingSince = DateTime.UtcNow;
                if (!url.StartsWith("https://") || !url.Contains(".google.com"))
                    try { a.Cdp.Send("Page.navigate", Json.New("url", ChatUrl(a.Index)), 8000); } catch { }
                bool slow = (DateTime.UtcNow - a.LoadingSince).TotalSeconds > 40;
                a.Status = slow ? "גוגל צ'אט לא נטען — כדאי להציץ בחלון" : "טוען את גוגל צ'אט…";
                if (slow && !a.ShownSlow) { a.ShownSlow = true; Log("הדף לא נטען: " + (url.Length > 60 ? url.Substring(0, 60) : url)); ShowChrome(false); }
                return;
            }
            a.LoadingSince = DateTime.MinValue;
            if (ls == 2)
            {
                a.Status = "צריך להתחבר לחשבון גוגל בחלון של Chrome";
                if (!a.ShownLogin) { a.ShownLogin = true; shownForLogin = true; ShowChrome(false, a); }
                return;
            }
            if (ls == 3)
            {
                // הכרטיסייה עברה לחשבון אחר: מנסים פעם אחת את הכתובת של החשבון הזה
                if (!a.Navigated) { a.Navigated = true; try { a.Cdp.Send("Page.navigate", Json.New("url", ChatUrl(a.Index)), 8000); } catch { } }
                a.Status = "החשבון " + (a.Index + 1) + " לא מחובר בכרום";
                return;
            }
            a.ShownLogin = a.ShownSlow = a.Navigated = false;
            a.Gc.LoadSelf();
            foreach (var o in Accounts)
                if (o != a && o.Ok && o.Key == a.Gc.SelfId) { a.Status = "החשבון כבר קיים ברשימה"; return; }
            a.Key = a.Gc.SelfId;
            try
            {
                a.Gc.ResolveUsers(new[] { a.Key });
                Member me;
                lock (a.Gc.Users) if (a.Gc.Users.TryGetValue(a.Key, out me)) { a.Name = me.Name ?? ""; a.Email = me.Email ?? ""; a.Avatar = me.Avatar ?? ""; }
            }
            catch { }
            lock (a.SortSeen) a.SortSeen.Clear();
            a.LastFullWorld = DateTime.MinValue;
            a.Ok = true;
            a.Status = "מחובר";
            Log("מחובר לגוגל צ'אט: " + (a.Email.Length > 0 ? a.Email : a.Name));
            if (shownForLogin && Accounts.TrueForAll(x => x.Ok)) HideChrome();
            if (Connected) QueueSync();
        }

        void UpdateSummary()
        {
            var list = Accounts;
            bool ok = list.Exists(a => a.Ok);
            string s;
            if (list.Count == 0) s = "אין חשבונות";
            else if (list.TrueForAll(a => a.Ok))
                s = list.Count == 1 ? "מחובר לגוגל צ'אט" + (list[0].Email.Length > 0 ? ": " + list[0].Email : "") : "מחובר לגוגל צ'אט · " + list.Count + " חשבונות";
            else s = list.Find(a => !a.Ok).Status;
            SetG(ok, s);
        }

        string AccountsSig()
        {
            var sb = new StringBuilder();
            foreach (var a in Accounts) sb.Append(a.Index).Append(':').Append(a.Ok).Append(':').Append(a.Key).Append(':').Append(a.Name).Append(';');
            return sb.ToString();
        }

        void SetG(bool ok, string status)
        {
            string sig = AccountsSig();
            bool changed = ok != GOk || status != GStatus || sig != lastAccSig;
            if (status != GStatus && !ok) Log(status);
            GOk = ok; GStatus = status; lastAccSig = sig;
            if (changed) { Fire(); SendStatus(); }
        }

        void DropAll()
        {
            foreach (var a in Accounts) a.Drop();
        }

        // ---------------- החלון של Chrome (מוסתר כברירת מחדל)

        public void ShowChrome(bool userAsked) { ShowChrome(userAsked, null); }

        void ShowChrome(bool userAsked, Account focus)
        {
            ChromeShown = true;
            try { Win.Show(); } catch { }
            var a = focus ?? Accounts.Find(x => x.Cdp != null && !x.Ok) ?? Accounts.Find(x => x.Cdp != null);
            if (a != null && a.Cdp != null)
            {
                try
                {
                    int wid = (int)Json.Long(a.Cdp.Send("Browser.getWindowForTarget", null, 5000), "windowId");
                    a.Cdp.Send("Browser.setWindowBounds", Json.New("windowId", wid, "bounds", Json.New("windowState", "normal")), 5000);
                    a.Cdp.Send("Browser.setWindowBounds", Json.New("windowId", wid, "bounds", Json.New("left", 80, "top", 40, "width", 1000, "height", 760)), 5000);
                    a.Cdp.Send("Page.bringToFront", null, 5000);
                }
                catch { }
            }
            else if (userAsked) googleWake.Set();
            Fire();
        }

        public void HideChrome()
        {
            ChromeShown = false;
            shownForLogin = false;
            try { Win.Hide(); } catch { }
            Fire();
        }

        /// <summary>חשבון נוסף: כרטיסייה חדשה עם "הוספת חשבון" של גוגל, שממשיכה לגוגל צ'אט של החשבון החדש.</summary>
        public void AddAccount()
        {
            new Thread(() =>
            {
                try
                {
                    int n;
                    lock (stLock)
                    {
                        n = 0;
                        foreach (int i in St.Accounts) n = Math.Max(n, i + 1);
                        St.Accounts.Add(n);
                        St.Save();
                    }
                    string tid = Browser.NewTab("https://accounts.google.com/AddSession?continue=" + Uri.EscapeDataString(ChatUrl(n)));
                    var a = new Account { Index = n, TargetId = tid };
                    lock (accounts) accounts.Add(a);
                    shownForLogin = true;
                    ShowChrome(true, null);
                    Log("הוספת חשבון: מתחברים בחלון של Chrome");
                    googleWake.Set();
                }
                catch (Exception e) { Log("הוספת חשבון נכשלה: " + e.Message); }
            }) { IsBackground = true }.Start();
        }

        public void RemoveAccount(int index)
        {
            lock (stLock)
            {
                St.Accounts.Remove(index);
                if (St.Accounts.Count == 0) St.Accounts.Add(0);
                St.Save();
            }
            var a = Find(index);
            if (a != null)
            {
                try { if (index > 0 && a.TargetId.Length > 0) Browser.CloseTab(a.TargetId); } catch { }
                a.Drop();
                lock (accounts) accounts.Remove(a);
            }
            Log("החשבון הוסר");
            UpdateSummary();
            googleWake.Set();
        }

        /// <summary>שגיאה מגוגל: אם החיבור נפל או שפג תוקף ההתחברות — בודקים מחדש.</summary>
        void GoogleFailed(Account a, Exception e)
        {
            if (e is CdpException || e is NullReferenceException)
            {
                if (a != null) a.Drop();
                UpdateSummary();
                googleWake.Set();
                return;
            }
            var ge = e as GChatException;
            if (a != null && ge != null && (ge.Status == 401 || ge.Status == 403))
            {
                try { a.Cdp.Send("Page.reload", null, 5000); } catch { }
                a.Ok = false;
                a.Status = "מרענן את גוגל צ'אט…";
                UpdateSummary();
                googleWake.Set();
                return;
            }
            Log(e.Message);
        }

        void Job(Account a, Action f)
        {
            jobs.Add(() =>
            {
                try { f(); }
                catch (Exception e) { GoogleFailed(a, e); }
            });
        }

        void WorkLoop()
        {
            foreach (var job in jobs.GetConsumingEnumerable())
            {
                if (!running) return;
                try { job(); }
                catch (Exception e) { GoogleFailed(null, e); }
            }
        }

        // ---------------- סנכרון עם הנגן

        void QueueSync()
        {
            if (syncPending) return;
            syncPending = true;
            jobs.Add(() => { syncPending = false; Sync(); });
        }

        static string Comp(Account a, string gid) { return a.Key + "|" + gid; }

        Dictionary<string, object> RowJson(Account a, ChatRow r)
        {
            var d = r.ToJson();
            d["g"] = Comp(a, r.Gid);
            if (r.Snippet != null)
            {
                var sn = new Dictionary<string, object>(r.Snippet);
                sn["g"] = d["g"];
                d["sn"] = sn;
            }
            return d;
        }

        /// <summary>אחרי חיבור: כל רשימת הצ'אטים (מכל החשבונות), וההודעות שהנגן פספס.</summary>
        void Sync()
        {
            if (!Connected) return;
            var list = new List<object>();
            var all = new List<KeyValuePair<Account, List<ChatRow>>>();
            foreach (var a in Accounts)
            {
                if (!a.Ok) continue;
                try
                {
                    var rows = a.Gc.World(80);
                    a.Gc.ResolveUsers(AllUsers(a, rows));
                    lock (a.RowSent) a.RowSent.Clear();
                    SendUsers(a, rows, null);
                    foreach (var r in rows)
                    {
                        FillName(a, r);
                        var j = RowJson(a, r);
                        list.Add(j);
                        lock (a.RowSent) a.RowSent[r.Gid] = Json.Ser(j);
                    }
                    all.Add(new KeyValuePair<Account, List<ChatRow>>(a, rows));
                }
                catch (Exception e) { GoogleFailed(a, e); }
            }
            SendLine(Json.Ser(Json.New("t", "chats", "full", true, "items", list)));
            Dictionary<string, long> known;
            lock (stLock) known = phoneKnown;
            foreach (var kv in all)
            {
                var a = kv.Key;
                foreach (var r in kv.Value)
                {
                    long k;
                    bool has = known.TryGetValue(Comp(a, r.Gid), out k);
                    bool missed = has ? r.Sort > k : r.Unread > 0;
                    if (missed && Connected) try { SendRecent(a, r.Gid, 12, true); } catch (Exception e) { GoogleFailed(a, e); }
                }
                lock (a.SortSeen) { a.SortSeen.Clear(); foreach (var r in kv.Value) a.SortSeen[r.Gid] = r.Sort; }
                a.LastFullWorld = DateTime.UtcNow;
            }
            lastPoll = DateTime.UtcNow;
        }

        /// <summary>בדיקה תקופתית: מה השתנה ברשימת הצ'אטים, ולהביא הודעות חדשות (בכל החשבונות).</summary>
        void Poll()
        {
            lastPoll = DateTime.UtcNow;
            if (!Connected || syncPending) return;
            foreach (var a in Accounts)
            {
                if (!a.Ok) continue;
                try { PollAccount(a); }
                catch (Exception e) { GoogleFailed(a, e); }
            }
        }

        void PollAccount(Account a)
        {
            var rows = a.Gc.World(40);
            var changed = new List<ChatRow>();
            var upd = new List<object>();
            a.Gc.ResolveUsers(AllUsers(a, rows));
            SendUsers(a, rows, null);
            foreach (var r in rows)
            {
                FillName(a, r);
                var j = RowJson(a, r);
                string js = Json.Ser(j);
                string prev;
                lock (a.RowSent) { a.RowSent.TryGetValue(r.Gid, out prev); a.RowSent[r.Gid] = js; }
                if (prev != js) upd.Add(j);
                long seen;
                bool has;
                lock (a.SortSeen) { has = a.SortSeen.TryGetValue(r.Gid, out seen); a.SortSeen[r.Gid] = r.Sort; }
                if (has ? r.Sort > seen : (DateTime.UtcNow - a.LastFullWorld).TotalSeconds > 10) changed.Add(r);
            }
            if (upd.Count > 0) SendLine(Json.Ser(Json.New("t", "chats", "items", upd)));
            foreach (var r in changed) if (Connected) SendRecent(a, r.Gid, 6, true);
        }

        IEnumerable<string> AllUsers(Account a, List<ChatRow> rows)
        {
            var ids = new List<string>();
            if (a.Gc != null && a.Gc.SelfId.Length > 0) ids.Add(a.Gc.SelfId);   // התמונה שלך, לפינה של מסך הבית
            foreach (var r in rows)
            {
                ids.AddRange(r.Users);
                if (r.Snippet != null) ids.Add(Json.Str(r.Snippet, "s"));
            }
            return ids;
        }

        void FillName(Account a, ChatRow r)
        {
            if (r.Name.Length > 0 || r.Users.Count == 0) return;
            var names = new List<string>();
            lock (a.Gc.Users)
                foreach (var id in r.Users)
                {
                    Member m;
                    if (a.Gc.Users.TryGetValue(id, out m) && !string.IsNullOrEmpty(m.Name)) names.Add(r.Users.Count > 1 ? m.Name.Split(' ')[0] : m.Name);
                }
            r.Name = string.Join(", ", names.ToArray());
        }

        static void MsgUserIds(List<Dictionary<string, object>> msgs, List<string> ids)
        {
            foreach (var m in msgs)
            {
                ids.Add(Json.Str(m, "s"));
                var q = Json.Obj(m.ContainsKey("q") ? m["q"] : null);
                if (q != null) ids.Add(Json.Str(q, "s"));
                object rp;
                if (m.TryGetValue("rp", out rp) && rp is List<string>) ids.AddRange((List<string>)rp);
            }
        }

        /// <summary>שולח לנגן את פרטי המשתמשים שהוא עוד לא קיבל.</summary>
        void SendUsers(Account a, List<ChatRow> rows, List<Dictionary<string, object>> msgs)
        {
            var ids = new List<string>();
            if (rows != null) ids.AddRange(AllUsers(a, rows));
            if (msgs != null) MsgUserIds(msgs, ids);
            var items = new List<object>();
            lock (a.Gc.Users)
                foreach (var id in ids)
                {
                    Member m;
                    if (string.IsNullOrEmpty(id) || !a.Gc.Users.TryGetValue(id, out m) || string.IsNullOrEmpty(m.Name)) continue;
                    string key = id + "|" + m.Name + "|" + m.Avatar;
                    lock (usersSent) if (!usersSent.Add(key)) continue;
                    items.Add(Json.New("id", id, "n", m.Name, "av", m.Avatar ?? "", "e", m.Email ?? ""));
                }
            if (items.Count > 0) SendLine(Json.Ser(Json.New("t", "users", "items", items)));
        }

        void SendRecent(Account a, string gid, int topics, bool live)
        {
            bool more;
            var msgs = a.Gc.Messages(gid, topics, 0, out more);
            SendMsgs(a, gid, msgs, Json.New("live", live));
        }

        void SendMsgs(Account a, string gid, List<Dictionary<string, object>> msgs, Dictionary<string, object> extra)
        {
            var ids = new List<string>();
            MsgUserIds(msgs, ids);
            a.Gc.ResolveUsers(ids);
            SendUsers(a, null, msgs);
            string comp = Comp(a, gid);
            foreach (var m in msgs) m["g"] = comp;
            var d = Json.New("t", "msgs", "g", comp, "items", msgs);
            foreach (var kv in extra) d[kv.Key] = kv.Value;
            SendLine(Json.Ser(d));
        }

        // ---------------- בקשות מהנגן

        /// <summary>מזהה צ'אט מהנגן: "&lt;חשבון&gt;|space/…". בלי חשבון — החשבון הראשון.</summary>
        Account Route(string g, out string gid)
        {
            int bar = g.IndexOf('|');
            gid = bar >= 0 ? g.Substring(bar + 1) : g;
            string key = bar >= 0 ? g.Substring(0, bar) : null;
            foreach (var a in Accounts) if (a.Ok && (key == null || a.Key == key)) return a;
            return null;
        }

        static byte[] B64(string s)
        {
            try { return Convert.FromBase64String(s); } catch { return null; }
        }

        void HandleLine(string line)
        {
            var d = Json.Obj(Json.Parse(line));
            if (d == null) return;
            string t = Json.Str(d, "t");
            if (t == "ping") { SendLine("{\"t\":\"pong\"}"); return; }
            if (t == "pong") return;
            if (t == "sync")
            {
                var known = new Dictionary<string, long>();
                var k = Json.Obj(d.ContainsKey("known") ? d["known"] : null);
                if (k != null) foreach (var kv in k) { try { known[kv.Key] = Convert.ToInt64(kv.Value, CultureInfo.InvariantCulture); } catch { } }
                lock (stLock) phoneKnown = known;
                SendStatus();
                if (GOk) QueueSync();
                return;
            }
            string g = Json.Str(d, "g");
            string gid = "";
            Account acc = t == "av" ? Accounts.Find(x => x.Ok) : Route(g, out gid);
            if (acc == null)
            {
                const string err = "המחשב לא מחובר לגוגל צ'אט";
                string lid = Json.Str(d, "lid");
                if (t == "send" || t == "upload") SendLine(Json.Ser(Json.New("t", "sent", "lid", lid, "g", g, "ok", false, "err", err)));
                if (t == "img") SendLine(Json.Ser(Json.New("t", "img", "id", Json.Str(d, "id"), "i", Json.Long(d, "i"), "err", err)));
                if (t == "aud") SendLine(Json.Ser(Json.New("t", "aud", "id", Json.Str(d, "id"), "i", Json.Long(d, "i"), "err", err)));
                if (t == "av") SendLine(Json.Ser(Json.New("t", "av", "url", Json.Str(d, "url"), "err", err)));
                if (t == "dm") SendLine(Json.Ser(Json.New("t", "dm", "u", Json.Str(d, "u"), "g", "", "err", err)));
                if (t == "edit" || t == "del") SendLine(Json.Ser(Json.New("t", t == "edit" ? "edited" : "deleted", "g", g, "id", Json.Str(d, "id"), "ok", false, "err", err)));
                if (t == "open" || t == "older") SendLine(Json.Ser(Json.New("t", "msgs", "g", g, "items", new object[0], "err", err, t == "older" ? "older" : "open", true)));
                return;
            }
            var a = acc;
            switch (t)
            {
                case "open":
                    Job(a, () => { bool more; var m = a.Gc.Messages(gid, 25, 0, out more); SendMsgs(a, gid, m, Json.New("open", true, "more", more)); });
                    break;
                case "older":
                    {
                        long before = Json.Long(d, "before");
                        Job(a, () => { bool more; var m = a.Gc.Messages(gid, 15, before, out more); SendMsgs(a, gid, m, Json.New("older", true, "more", more)); });
                        break;
                    }
                case "send":
                case "upload":
                    {
                        string text = Json.Str(d, "text"), th = Json.Str(d, "th"), lid = Json.Str(d, "lid");
                        string qid = Json.Str(d, "qid"), qth = Json.Str(d, "qth");
                        long qt = Json.Long(d, "qt");
                        var ann = GChat.FormatAnnotations(d.ContainsKey("f") ? d["f"] : null);
                        byte[] img = t == "upload" ? B64(Json.Str(d, "data")) : null;
                        string name = Json.Str(d, "name"), ct = Json.Str(d, "ct");
                        jobs.Add(() =>
                        {
                            Dictionary<string, object> m = null;
                            string err = null;
                            try
                            {
                                if (t == "upload")
                                {
                                    if (img == null || img.Length == 0) throw new Exception("התמונה לא הגיעה מהנגן");
                                    var voice = Json.Obj(d.ContainsKey("voice") ? d["voice"] : null);
                                    if (voice != null)
                                    {
                                        long vms = Json.Long(voice, "d");
                                        var w = voice.ContainsKey("w") ? voice["w"] as object[] : null;
                                        try { ann.Insert(0, a.Gc.Upload(gid, img, name, ct, vms, w)); }
                                        catch (GChatException) { ann.Insert(0, a.Gc.Upload(gid, img, name, ct)); }
                                    }
                                    else ann.Insert(0, a.Gc.Upload(gid, img, name.Length > 0 ? name : "image.jpg", ct.Length > 0 ? ct : "image/jpeg"));
                                }
                                m = a.Gc.Send(gid, text, th, "btchat%" + lid, qid, qth, qt, ann);
                            }
                            catch (Exception e) { err = e.Message; if (e is CdpException) GoogleFailed(a, e); }
                            var r = Json.New("t", "sent", "lid", lid, "g", Comp(a, gid), "ok", err == null);
                            if (m != null) { m["g"] = Comp(a, gid); r["msg"] = m; }
                            if (err != null) r["err"] = err;
                            SendLine(Json.Ser(r));
                            if (err == null) { Log((t == "upload" ? "נשלחה תמונה מהנגן" : "נשלחה הודעה מהנגן") + (m == null ? " (בלי פרטי ההודעה בתשובה)" : "")); lastPoll = DateTime.MinValue; }
                            else Log("השליחה נכשלה: " + err);
                        });
                        break;
                    }
                case "edit":
                    {
                        string th = Json.Str(d, "th"), id = Json.Str(d, "id"), text = Json.Str(d, "text");
                        var ann = GChat.FormatAnnotations(d.ContainsKey("f") ? d["f"] : null);
                        jobs.Add(() =>
                        {
                            Dictionary<string, object> m = null;
                            string err = null;
                            try { m = a.Gc.Edit(gid, th, id, text, ann); }
                            catch (Exception e) { err = e.Message; if (e is CdpException) GoogleFailed(a, e); }
                            var r = Json.New("t", "edited", "g", Comp(a, gid), "id", id, "ok", err == null);
                            if (m != null) { m["g"] = Comp(a, gid); r["msg"] = m; }
                            if (err != null) r["err"] = err;
                            SendLine(Json.Ser(r));
                        });
                        break;
                    }
                case "del":
                    {
                        string th = Json.Str(d, "th"), id = Json.Str(d, "id");
                        jobs.Add(() =>
                        {
                            string err = null;
                            try { a.Gc.Delete(gid, th, id); }
                            catch (Exception e) { err = e.Message; if (e is CdpException) GoogleFailed(a, e); }
                            var r = Json.New("t", "deleted", "g", Comp(a, gid), "id", id, "ok", err == null);
                            if (err != null) r["err"] = err;
                            SendLine(Json.Ser(r));
                        });
                        break;
                    }
                case "dm":
                    {
                        // לחיצה על פרופיל בקבוצה: הצ'אט האישי עם אותו אדם
                        string u = Json.Str(d, "u");
                        jobs.Add(() =>
                        {
                            var r = Json.New("t", "dm", "u", u, "g", "");
                            try
                            {
                                var row = a.Gc.FindDm(u);
                                if (row != null)
                                {
                                    FillName(a, row);
                                    r["g"] = Comp(a, row.Gid);
                                    r["row"] = RowJson(a, row);
                                }
                            }
                            catch (Exception e) { r["err"] = e.Message; if (e is CdpException) GoogleFailed(a, e); }
                            SendLine(Json.Ser(r));
                        });
                        break;
                    }
                case "react":
                    {
                        string th = Json.Str(d, "th"), id = Json.Str(d, "id"), e = Json.Str(d, "e");
                        bool rm = Json.Bool(d, "rm", false);
                        Job(a, () =>
                        {
                            a.Gc.React(gid, th, id, e, rm);
                            bool more;
                            var m = a.Gc.Messages(gid, 25, 0, out more);
                            SendMsgs(a, gid, m, Json.New("live", false));
                        });
                        break;
                    }
                case "read":
                    {
                        long ts = Json.Long(d, "ts");
                        Job(a, () => { a.Gc.MarkRead(gid, Math.Max(ts, 1)); lastPoll = DateTime.MinValue; });
                        break;
                    }
                case "aud":
                    {
                        string id = Json.Str(d, "id"), k = Json.Str(d, "k"), ct = Json.Str(d, "ct");
                        int idx = (int)Json.Long(d, "i");
                        jobs.Add(() =>
                        {
                            try
                            {
                                var raw = a.Gc.Download(k, ct);
                                SendLine(Json.Ser(Json.New("t", "aud", "id", id, "i", idx, "data", Convert.ToBase64String(raw))));
                            }
                            catch (Exception e)
                            {
                                SendLine(Json.Ser(Json.New("t", "aud", "id", id, "i", idx, "err", e.Message)));
                                if (e is CdpException) GoogleFailed(a, e);
                            }
                        });
                        break;
                    }
                case "img":
                    {
                        string id = Json.Str(d, "id"), k = Json.Str(d, "k"), ct = Json.Str(d, "ct");
                        int idx = (int)Json.Long(d, "i");
                        jobs.Add(() =>
                        {
                            try
                            {
                                var raw = a.Gc.Attachment(k, ct);
                                var jpg = ImageTools.ToJpeg(raw, 720, 72, false);
                                SendLine(Json.Ser(Json.New("t", "img", "id", id, "i", idx, "data", Convert.ToBase64String(jpg))));
                            }
                            catch (Exception e)
                            {
                                SendLine(Json.Ser(Json.New("t", "img", "id", id, "i", idx, "err", e.Message)));
                                if (e is CdpException) GoogleFailed(a, e);
                            }
                        });
                        break;
                    }
                case "av":
                    {
                        string url = Json.Str(d, "url");
                        jobs.Add(() =>
                        {
                            try
                            {
                                string u = url.StartsWith("//") ? "https:" + url : url;
                                var raw = a.Gc.Fetch(u);
                                var jpg = ImageTools.ToJpeg(raw, 96, 80, true);
                                SendLine(Json.Ser(Json.New("t", "av", "url", url, "data", Convert.ToBase64String(jpg))));
                            }
                            catch (Exception e)
                            {
                                SendLine(Json.Ser(Json.New("t", "av", "url", url, "err", e.Message)));
                                if (e is CdpException) GoogleFailed(a, e);
                            }
                        });
                        break;
                    }
            }
        }

        void SendStatus()
        {
            if (!Connected) return;
            var accs = new List<object>();
            string self = "";
            foreach (var a in Accounts)
            {
                if (a.Key.Length == 0) continue;
                if (self.Length == 0 && a.Ok) self = a.Key;
                accs.Add(Json.New("k", a.Key, "n", a.Name, "e", a.Email, "av", a.Avatar, "ok", a.Ok));
            }
            SendLine(Json.Ser(Json.New("t", "status", "ok", GOk, "msg", GStatus, "self", self, "accounts", accs)));
        }

        // ---------------- חיבור לנגן

        bool SendLine(string json)
        {
            lock (writeLock)
            {
                var s = stream;
                if (s == null) return false;
                try
                {
                    var b = Encoding.UTF8.GetBytes(json + "\n");
                    s.Write(b, 0, b.Length);
                    s.Flush();
                    if (DateTime.Today != sentDay) { sentDay = DateTime.Today; SentToday = 0; }
                    return true;
                }
                catch
                {
                    Drop("הקשר עם הנגן נותק");
                    return false;
                }
            }
        }

        void Drop(string why)
        {
            Stream s;
            lock (writeLock) { s = stream; stream = null; }
            bool was = Connected;
            Connected = false;
            if (s != null) try { s.Close(); } catch { }
            if (was && why != null) Log(why);
            Fire();
            linkWake.Set();
        }

        void LinkLoop()
        {
            int cycle = 0;
            while (running)
            {
                if (!Connected)
                {
                    try { TryAllDevices(cycle++); } catch (Exception e) { Log("שגיאת בלוטות': " + e.Message); }
                    if (!Connected) { Searching = ""; Fire(); linkWake.WaitOne(10000); }
                }
                else
                {
                    linkWake.WaitOne(5000);
                    if (!Connected) continue;
                    if ((DateTime.UtcNow - lastRecv).TotalSeconds > 65) { Drop("הנגן לא עונה — מתחבר מחדש"); continue; }
                    if ((DateTime.UtcNow - lastPing).TotalSeconds >= 20)
                    {
                        lastPing = DateTime.UtcNow;
                        SendLine("{\"t\":\"ping\"}");
                    }
                }
            }
        }

        public List<BtDevice> PairedDevices() { return Bt.Paired(); }

        void TryAllDevices(int cycle)
        {
            var all = Bt.Paired();
            var cands = new List<BtDevice>();
            string fixedAddr, lastAddr;
            lock (stLock) { fixedAddr = St.DeviceAddr; lastAddr = St.LastAddr; }
            if (fixedAddr.Length > 0)
            {
                foreach (var d in all) if (d.AddrText == fixedAddr) cands.Add(d);
                if (cands.Count == 0) { Searching = "הנגן שנבחר לא משויך למחשב"; return; }
            }
            else
            {
                foreach (var d in all) if (d.AddrText == lastAddr) cands.Add(d);
                foreach (var d in all) if (d.AddrText != lastAddr && !d.IsAccessory) cands.Add(d);
                if (cycle % 4 == 3) foreach (var d in all) if (d.AddrText != lastAddr && d.IsAccessory) cands.Add(d);
            }
            if (all.Count == 0) { Searching = "אין מכשירים משויכים בבלוטות'"; return; }
            foreach (var d in cands)
            {
                if (!running || Connected) return;
                Searching = "מחפש את הנגן… (" + d.Name + ")";
                Fire();
                if (TryConnect(d)) return;
            }
        }

        bool TryConnect(BtDevice d)
        {
            Socket s = null;
            try
            {
                s = new Socket((AddressFamily)32, SocketType.Stream, (ProtocolType)3);
                s.Connect(new BtEndPoint(d.Addr, Program.ServiceUuid, 0));
                var ns = new NetworkStream(s, true);
                int gen;
                lock (writeLock) { stream = ns; gen = ++linkGen; }
                lock (usersSent) usersSent.Clear();
                foreach (var acc in Accounts) lock (acc.RowSent) acc.RowSent.Clear();
                lastRecv = DateTime.UtcNow; lastPing = DateTime.UtcNow;
                PeerName = d.Name;
                Connected = true;
                Searching = "";
                lock (stLock) { St.LastAddr = d.AddrText; St.Save(); }
                Log("התחבר לנגן: " + d.Name);
                var reader = new Thread(() => ReadLoop(ns, gen)) { IsBackground = true, Name = "reader" };
                reader.Start();
                SendLine(Json.Ser(Json.New("t", "hello", "name", Environment.MachineName, "ver", Program.Version)));
                SendStatus();
                Fire();
                return true;
            }
            catch
            {
                if (s != null) try { s.Close(); } catch { }
                return false;
            }
        }

        void ReadLoop(Stream ns, int gen)
        {
            try
            {
                var r = new StreamReader(ns, new UTF8Encoding(false));
                string line;
                while ((line = r.ReadLine()) != null)
                {
                    lastRecv = DateTime.UtcNow;
                    line = line.Trim();
                    if (line.Length == 0) continue;
                    try { HandleLine(line); } catch { }
                }
            }
            catch { }
            if (gen == linkGen && Connected) Drop("הנגן התנתק");
        }

        // ---------------- בדיקה

        static Account TestAccount(int index)
        {
            var a = new Account { Index = index };
            string tid = "";
            a.Cdp = Cdp.Connect(Browser.TabFor(index, ref tid));
            a.TargetId = tid;
            a.Gc = new GChat(a.Cdp, index);
            a.Gc.LoadSelf();
            a.Key = a.Gc.SelfId;
            a.Ok = true;
            return a;
        }

        /// <summary>בדיקה בלי בלוטות': מדמה נגן, מריץ סנכרון/פתיחה/הודעות קודמות/בדיקה תקופתית ומחזיר מה היה נשלח.</summary>
        public static string Simulate(string gid)
        {
            var sb = new StringBuilder();
            var c = new Core { St = State.Load(), running = true };
            var cap = new MemoryStream();
            Account a = null;
            try
            {
                a = TestAccount(0);
                lock (c.accounts) c.accounts.Add(a);
                c.GOk = true; c.GStatus = "מחובר לגוגל צ'אט";
                c.stream = cap; c.Connected = true;
                Action drain = () => { Action j; while (c.jobs.TryTake(out j)) { try { j(); } catch (Exception e) { sb.AppendLine("JOB ERROR: " + e.Message); } } };
                var sw = Stopwatch.StartNew();
                c.HandleLine("{\"t\":\"sync\",\"known\":{}}"); drain();
                sb.AppendLine("sync ms: " + sw.ElapsedMilliseconds); sw.Restart();
                string g = a.Key + "|" + gid;
                c.HandleLine(Json.Ser(Json.New("t", "open", "g", g))); drain();
                sb.AppendLine("open ms: " + sw.ElapsedMilliseconds); sw.Restart();
                c.HandleLine(Json.Ser(Json.New("t", "older", "g", g, "before", NowUs() - 3L * 86400 * 1000000))); drain();
                sb.AppendLine("older ms: " + sw.ElapsedMilliseconds); sw.Restart();
                a.LastFullWorld = DateTime.UtcNow.AddMinutes(-1);
                lock (a.SortSeen) { var keys = new List<string>(a.SortSeen.Keys); if (keys.Count > 0) a.SortSeen[keys[0]] -= 1; }
                c.Poll();
                sb.AppendLine("poll ms: " + sw.ElapsedMilliseconds);
            }
            catch (Exception e) { sb.AppendLine("ERROR: " + e); }
            var lines = Encoding.UTF8.GetString(cap.ToArray()).Split('\n');
            sb.AppendLine("lines: " + lines.Length + ", bytes: " + cap.Length);
            foreach (var l in lines)
            {
                if (l.Trim().Length == 0) continue;
                var d = Json.Obj(Json.Parse(l));
                string t = Json.Str(d, "t");
                var items = d.ContainsKey("items") ? d["items"] as object[] : null;
                string extra = "";
                if (t == "msgs" && items != null && items.Length > 0)
                {
                    int fm = 0, del = 0, rp = 0;
                    foreach (var x in items) { var o = Json.Obj(x); if (o.ContainsKey("f")) fm++; if (o.ContainsKey("del")) del++; if (o.ContainsKey("rp")) rp++; }
                    extra = " formatted=" + fm + " deleted=" + del + " threadHeads=" + rp;
                }
                if (t == "chats" && items != null && items.Length > 0) extra = " g0=" + Json.Str(Json.Obj(items[0]), "g").Split('|')[0].Length + "ch-key full=" + Json.Bool(d, "full", false);
                if (t == "status") extra = " accounts=" + ((d["accounts"] as object[]) ?? new object[0]).Length;
                sb.AppendLine("  " + t + " items=" + (items != null ? items.Length : 0) + " len=" + l.Length +
                    (d.ContainsKey("open") ? " open" : "") + (d.ContainsKey("older") ? " older" : "") + (d.ContainsKey("live") ? " live=" + Json.Str(d, "live") : "") + extra);
            }
            c.running = false;
            if (a != null) a.Drop();
            return sb.ToString();
        }

        /// <summary>בדיקה: אילו כרטיסיות/חשבונות יש ב-Chrome, ומה כל אחד מחזיר.</summary>
        public static string Probe(string unused)
        {
            var sb = new StringBuilder();
            try
            {
                if (!Browser.Alive()) { sb.AppendLine("chrome: not running"); return sb.ToString(); }
                for (int i = 0; i < 3; i++)
                {
                    try
                    {
                        string tid = "";
                        string ws = Browser.TabFor(i, ref tid);
                        using (var c = Cdp.Connect(ws))
                        {
                            var g = new GChat(c, i);
                            string url;
                            int ls = g.LoginState(out url);
                            sb.AppendLine("account " + i + ": state=" + ls + " url=" + (url.Length > 50 ? url.Substring(0, 50) : url));
                            if (ls == 1)
                            {
                                g.LoadSelf();
                                var rows = g.World(20);
                                sb.AppendLine("   self=" + g.SelfId.Length + "ch chats=" + rows.Count);
                            }
                        }
                    }
                    catch (Exception e) { sb.AppendLine("account " + i + ": " + e.Message); }
                    if (i == 0) continue;
                    break;
                }
            }
            catch (Exception e) { sb.AppendLine("ERROR: " + e); }
            return sb.ToString();
        }
    }

    // ---------------------------------------------------------------- ממשק

    static class Theme
    {
        public static readonly Color Bg = Color.FromArgb(246, 248, 252);
        public static readonly Color Card = Color.White;
        public static readonly Color Text = Color.FromArgb(31, 31, 31);
        public static readonly Color Sub = Color.FromArgb(94, 100, 112);
        public static readonly Color Line = Color.FromArgb(227, 231, 238);
        public static readonly Color Accent = Color.FromArgb(11, 87, 208);
        public static readonly Color Green = Color.FromArgb(30, 142, 62);
        public static readonly Color Amber = Color.FromArgb(234, 134, 0);
        public static readonly Color Gray = Color.FromArgb(160, 167, 182);

        public static Font F(float size, bool bold) { return new Font("Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular); }

        public static GraphicsPath Round(RectangleF r, float rad)
        {
            var p = new GraphicsPath();
            float d = rad * 2;
            p.AddArc(r.X, r.Y, d, d, 180, 90);
            p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }

        /// <summary>הלוגו: בועת צ'אט לבנה עם שלוש נקודות על ריבוע מעוגל בגרדיאנט ירוק-טורקיז. (זהה ל-logo.ps1)</summary>
        public static Bitmap Logo(int size, Color? dot)
        {
            var bmp = new Bitmap(size, size);
            using (var g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                g.Clear(Color.Transparent);
                float w = size;
                // הלוגו לפי העיצוב שנבחר (נמדד מהתמונה, בקנה מידה של 240):
                // בועה אחורית ירוקה כהה, בועה קדמית רחבה עם מעבר תכלת→טורקיז→ירוק למעלה, זנב בצד שמאל, ו-BT מעוגל
                float u = w / 240f;
                using (var back = Round(new RectangleF(20 * u, 24 * u, 200 * u, 92 * u), 46 * u))
                using (var bb = new SolidBrush(Color.FromArgb(0, 175, 87)))
                    g.FillPath(bb, back);
                using (var front = new Region(Round(new RectangleF(2 * u, 52 * u, 236 * u, 140 * u), 66 * u)))
                using (var tail = new GraphicsPath())
                {
                    tail.AddPolygon(new PointF[] { new PointF(56 * u, 178 * u), new PointF(56 * u, 228 * u), new PointF(128 * u, 186 * u), new PointF(128 * u, 178 * u) });
                    front.Union(tail);
                    var old = g.Clip;
                    g.Clip = front;
                    using (var green = new SolidBrush(Color.FromArgb(14, 188, 95))) g.FillRectangle(green, 0, 0, w, w);
                    var top = new RectangleF(0, 52 * u, w, 58 * u);
                    using (var grad = new LinearGradientBrush(new PointF(0, top.Top - 1), new PointF(0, top.Bottom + 1), Color.White, Color.White))
                    {
                        var cb = new ColorBlend(3);
                        cb.Colors = new[] { Color.FromArgb(122, 202, 255), Color.FromArgb(69, 192, 181), Color.FromArgb(14, 188, 95) };
                        cb.Positions = new[] { 0f, 0.5f, 1f };
                        grad.InterpolationColors = cb;
                        g.FillRectangle(grad, top);
                    }
                    g.Clip = old;
                }
                DrawBT(g, w, Brushes.White, 125 * u, 0.3f);
                if (dot.HasValue)
                {
                    float ds = size * 0.38f;
                    using (var b = new SolidBrush(dot.Value)) g.FillEllipse(b, size - ds, size - ds, ds - 1, ds - 1);
                    using (var pen = new Pen(Color.White, Math.Max(1f, size / 16f))) g.DrawEllipse(pen, size - ds, size - ds, ds - 1, ds - 1);
                }
            }
            return bmp;
        }

        public static Icon IconOf(Bitmap bmp) { return Icon.FromHandle(bmp.GetHicon()); }

        /// <summary>אייקון לבן לשורת ההתראות: בועה עם נקודות שקופות.</summary>
        /** צורת הבועה: גוף רחב ומעוגל + זנב קטן בפינה השמאלית התחתונה (כאיחוד, בלי חור בחפיפה). */
        static Region Bubble(float w)
        {
            var r = new Region(Round(new RectangleF(w * 0.06f, w * 0.15f, w * 0.88f, w * 0.58f), w * 0.24f));
            using (var tail = new GraphicsPath())
            {
                tail.AddPolygon(new PointF[] { new PointF(w * 0.13f, w * 0.6f), new PointF(w * 0.13f, w * 0.86f), new PointF(w * 0.38f, w * 0.7f), new PointF(w * 0.38f, w * 0.6f) });
                r.Union(tail);
            }
            return r;
        }

        /** האותיות BT במרכז הבועה, בפינות מעוגלות: מילוי + קו מתאר עבה עם חיבורים עגולים. */
        static void DrawBT(Graphics g, float w, Brush b) { DrawBT(g, w, b, w * 0.44f, 0.3f); }

        /** BT מעוגל: cy = מרכז האותיות, size = גודל הגופן ביחס לרוחב. */
        static void DrawBT(Graphics g, float w, Brush b, float cy, float size)
        {
            using (var path = new GraphicsPath())
            using (var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center })
            {
                path.AddString("BT", new FontFamily("Segoe UI"), (int)FontStyle.Bold, w * size, new RectangleF(0, cy - w * 0.3f, w, w * 0.6f), sf);
                g.FillPath(b, path);
                using (var pen = new Pen(b, w * 0.035f) { LineJoin = LineJoin.Round, StartCap = LineCap.Round, EndCap = LineCap.Round })
                    g.DrawPath(pen, path);
            }
        }

        /// <summary>אייקון לבן לשורת ההתראות: בועה עם BT שקוף.</summary>
        public static Bitmap Mono(int size)
        {
            var bmp = new Bitmap(size, size);
            using (var g = Graphics.FromImage(bmp))
            {
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.Clear(Color.Transparent);
                float w = size;
                using (var shape = Bubble(w)) g.FillRegion(Brushes.White, shape);
                g.CompositingMode = CompositingMode.SourceCopy;
                using (var clear = new SolidBrush(Color.FromArgb(0, 255, 255, 255))) DrawBT(g, w, clear);
            }
            return bmp;
        }

        public static void SaveIco(string path)
        {
            int[] sizes = { 16, 24, 32, 48, 256 };
            var pngs = new List<byte[]>();
            foreach (int s in sizes)
                using (var b = Logo(s, null))
                using (var ms = new MemoryStream()) { b.Save(ms, System.Drawing.Imaging.ImageFormat.Png); pngs.Add(ms.ToArray()); }
            using (var fs = File.Create(path))
            using (var bw = new BinaryWriter(fs))
            {
                bw.Write((ushort)0); bw.Write((ushort)1); bw.Write((ushort)sizes.Length);
                int offset = 6 + 16 * sizes.Length;
                for (int i = 0; i < sizes.Length; i++)
                {
                    bw.Write((byte)(sizes[i] % 256)); bw.Write((byte)(sizes[i] % 256)); bw.Write((byte)0); bw.Write((byte)0);
                    bw.Write((ushort)1); bw.Write((ushort)32); bw.Write((uint)pngs[i].Length); bw.Write((uint)offset);
                    offset += pngs[i].Length;
                }
                foreach (var p in pngs) bw.Write(p);
            }
        }
    }

    class CardPanel : Panel
    {
        public CardPanel() { DoubleBuffered = true; BackColor = Theme.Bg; SetStyle(ControlStyles.ResizeRedraw, true); }
        protected override void OnPaintBackground(PaintEventArgs e)
        {
            e.Graphics.Clear(Theme.Bg);
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            var r = new RectangleF(0.5f, 0.5f, Width - 2f, Height - 2f);
            using (var p = Theme.Round(r, 14))
            {
                using (var b = new SolidBrush(Theme.Card)) e.Graphics.FillPath(b, p);
                using (var pen = new Pen(Theme.Line)) e.Graphics.DrawPath(pen, p);
            }
        }
    }

    class Dot : Control
    {
        Color color = Theme.Gray;
        public Color Color { get { return color; } set { color = value; Invalidate(); } }
        public Dot() { DoubleBuffered = true; Size = new Size(12, 12); BackColor = Theme.Card; }
        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using (var b = new SolidBrush(color)) e.Graphics.FillEllipse(b, 1, 1, Width - 3, Height - 3);
        }
    }

    class RText : Control
    {
        public RText(string text, Font font, Color color)
        {
            Text = text; Font = font; ForeColor = color;
            BackColor = Theme.Card;
            RightToLeft = RightToLeft.No;
            DoubleBuffered = true;
            SetStyle(ControlStyles.ResizeRedraw | ControlStyles.SupportsTransparentBackColor, true);
        }
        protected override void OnTextChanged(EventArgs e) { base.OnTextChanged(e); Invalidate(); }
        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.Clear(BackColor);
            TextRenderer.DrawText(e.Graphics, Text, Font, ClientRectangle, ForeColor,
                TextFormatFlags.Right | TextFormatFlags.RightToLeft | TextFormatFlags.VerticalCenter |
                TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix);
        }
    }

    class FlatBtn : Button
    {
        public FlatBtn(string text, bool primary)
        {
            Text = text;
            FlatStyle = FlatStyle.Flat;
            Font = Theme.F(10, true);
            Cursor = Cursors.Hand;
            Height = 38;
            FlatAppearance.BorderSize = primary ? 0 : 1;
            FlatAppearance.BorderColor = Theme.Line;
            BackColor = primary ? Theme.Accent : Theme.Card;
            ForeColor = primary ? Color.White : Theme.Text;
            FlatAppearance.MouseOverBackColor = primary ? Color.FromArgb(8, 72, 175) : Color.FromArgb(242, 245, 250);
            UseVisualStyleBackColor = false;
        }
    }

    class MainForm : Form
    {
        readonly Core core;
        readonly Dot gDot = new Dot(), pDot = new Dot();
        readonly RText gLabel = new RText("", Theme.F(11.5f, true), Theme.Text), pLabel = new RText("", Theme.F(11.5f, true), Theme.Text);
        readonly ComboBox deviceBox = new ComboBox();
        readonly ListBox logBox = new ListBox();
        readonly CheckBox autoStart = new CheckBox();
        readonly RText accLabel = new RText("", Theme.F(9.5f, false), Theme.Sub);
        FlatBtn showBtn;
        bool syncing, loadingDevices;
        public bool AllowClose;

        public void SyncAutoStart() { syncing = true; autoStart.Checked = AutoStart.IsOn(); syncing = false; }

        public MainForm(Core core)
        {
            this.core = core;
            SuspendLayout();
            AutoScaleDimensions = new SizeF(96F, 96F);
            AutoScaleMode = AutoScaleMode.Dpi;
            Text = Program.Name;
            RightToLeft = RightToLeft.Yes;
            RightToLeftLayout = true;
            BackColor = Theme.Bg;
            Font = Theme.F(10, false);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            ClientSize = new Size(470, 566);
            StartPosition = FormStartPosition.CenterScreen;
            using (var b = Theme.Logo(64, null)) Icon = Theme.IconOf(b);

            var logo = new PictureBox { Image = Theme.Logo(128, null), Size = new Size(46, 46), Location = new Point(20, 16), BackColor = Theme.Bg, SizeMode = PictureBoxSizeMode.Zoom };
            var title = new Label { Text = Program.Name, Font = Theme.F(19, true), ForeColor = Theme.Text, AutoSize = true, Location = new Point(74, 8), BackColor = Theme.Bg };
            var sub = new Label { Text = "הצ'אטים של גוגל צ'אט בנגן, בבלוטות'", ForeColor = Theme.Sub, AutoSize = true, Location = new Point(77, 44), BackColor = Theme.Bg };
            Controls.AddRange(new Control[] { logo, title, sub });

            // כרטיס גוגל צ'אט
            var c1 = new CardPanel { Location = new Point(16, 76), Size = new Size(438, 110) };
            Put(c1, gDot, 20, 19, 12, 12);
            Put(c1, gLabel, 40, 10, 380, 28);
            Put(c1, accLabel, 40, 38, 380, 22);
            showBtn = new FlatBtn("הצגת החלון", false);
            showBtn.Click += (s, e) => { if (core.ChromeShown) core.HideChrome(); else core.ShowChrome(true); RefreshView(); };
            Put(c1, showBtn, 20, 66, 130, 32);
            var addBtn = new FlatBtn("הוספת חשבון", false);
            addBtn.Click += (s, e) =>
            {
                var ans = MessageBox.Show("ייפתח החלון של Chrome עם דף ההתחברות של גוגל. מתחברים שם לחשבון הנוסף, והוא יתווסף לבד.\n\nלהמשיך?",
                    Program.Name, MessageBoxButtons.OKCancel, MessageBoxIcon.Information, MessageBoxDefaultButton.Button1, MessageBoxOptions.RightAlign | MessageBoxOptions.RtlReading);
                if (ans == DialogResult.OK) core.AddAccount();
            };
            Put(c1, addBtn, 158, 66, 130, 32);
            var removeBtn = new FlatBtn("הסרת חשבון", false);
            removeBtn.Click += (s, e) =>
            {
                var menu = new ContextMenuStrip { RightToLeft = RightToLeft.Yes, Font = Theme.F(10, false) };
                foreach (var a in core.Accounts)
                {
                    var acc = a;
                    var it = menu.Items.Add(acc.Label, null, (s2, e2) =>
                    {
                        var ok = MessageBox.Show("להסיר את " + acc.Label + " מ" + Program.Name + "?\n\n(החשבון לא מתנתק מגוגל.)", Program.Name, MessageBoxButtons.YesNo,
                            MessageBoxIcon.Question, MessageBoxDefaultButton.Button2, MessageBoxOptions.RightAlign | MessageBoxOptions.RtlReading);
                        if (ok == DialogResult.Yes) core.RemoveAccount(acc.Index);
                    });
                    it.Enabled = acc.Index > 0 || core.Accounts.Count > 1;
                }
                menu.Show(removeBtn, new Point(0, removeBtn.Height));
            };
            Put(c1, removeBtn, 296, 66, 122, 32);
            Controls.Add(c1);

            // כרטיס נגן
            var c2 = new CardPanel { Location = new Point(16, 196), Size = new Size(438, 104) };
            Put(c2, pDot, 20, 21, 12, 12);
            Put(c2, pLabel, 40, 12, 380, 28);
            Put(c2, new RText("הנגן:", Theme.F(10, false), Theme.Text), 20, 56, 46, 28);
            deviceBox.DropDownStyle = ComboBoxStyle.DropDownList;
            deviceBox.SelectedIndexChanged += (s, e) => DeviceChosen();
            Put(c2, deviceBox, 70, 57, 240, 28);
            var refresh = new FlatBtn("רענון", false);
            refresh.Click += (s, e) => { LoadDevices(); core.Reconnect(); };
            Put(c2, refresh, 322, 54, 96, 32);
            Controls.Add(c2);

            // כרטיס פעילות
            var c3 = new CardPanel { Location = new Point(16, 310), Size = new Size(438, 210) };
            Put(c3, new RText("פעילות אחרונה", Theme.F(9.5f, true), Theme.Sub), 20, 10, 400, 22);
            logBox.BorderStyle = BorderStyle.None;
            logBox.ForeColor = Theme.Text; logBox.BackColor = Theme.Card;
            logBox.Font = Theme.F(9, false);
            logBox.IntegralHeight = false;
            Put(c3, logBox, 16, 34, 406, 166);
            Controls.Add(c3);

            autoStart.Text = "להפעיל את " + Program.Name + " אוטומטית עם Windows";
            autoStart.AutoSize = true;
            autoStart.Location = new Point(20, 532);
            autoStart.ForeColor = Theme.Text; autoStart.BackColor = Theme.Bg;
            autoStart.Checked = AutoStart.IsOn();
            autoStart.CheckedChanged += (s, e) => { if (!syncing) AutoStart.Set(autoStart.Checked); };
            Controls.Add(autoStart);
            ResumeLayout(false);
            PerformLayout();

            LoadDevices();
            RefreshView();
        }

        static void Put(Control parent, Control c, int right, int y, int w, int h)
        {
            c.SetBounds(parent.Width - right - w, y, w, h);
            parent.Controls.Add(c);
        }

        void LoadDevices()
        {
            loadingDevices = true;
            deviceBox.Items.Clear();
            deviceBox.Items.Add("זיהוי אוטומטי");
            int sel = 0;
            foreach (var d in core.PairedDevices())
            {
                deviceBox.Items.Add(d);
                if (d.AddrText == core.St.DeviceAddr) sel = deviceBox.Items.Count - 1;
            }
            deviceBox.SelectedIndex = sel;
            loadingDevices = false;
        }

        void DeviceChosen()
        {
            if (loadingDevices) return;
            var d = deviceBox.SelectedItem as BtDevice;
            core.SetDevice(d == null ? "" : d.AddrText);
        }

        public void RefreshView()
        {
            if (IsDisposed) return;
            gDot.Color = core.GOk ? Theme.Green : Theme.Amber;
            gLabel.Text = core.GStatus;
            var accs = core.Accounts;
            var parts = new List<string>();
            foreach (var a in accs) parts.Add((a.Ok ? "✓ " : "· ") + a.Label);
            accLabel.Text = string.Join("    ", parts.ToArray());
            if (showBtn != null) showBtn.Text = core.ChromeShown ? "הסתרת החלון" : "הצגת החלון";
            if (core.Connected) { pDot.Color = Theme.Green; pLabel.Text = "מחובר לנגן: " + core.PeerName; }
            else { pDot.Color = Theme.Amber; pLabel.Text = core.Searching.Length > 0 ? core.Searching : "ממתין לנגן…"; }
            var lines = core.LogLines;
            logBox.BeginUpdate();
            logBox.Items.Clear();
            foreach (var l in lines) logBox.Items.Add(l);
            logBox.EndUpdate();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!AllowClose && e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); return; }
            base.OnFormClosing(e);
        }
    }

    static class AutoStart
    {
        const string Key = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
        const string ValueName = "BTChat";
        static string Command { get { return "\"" + Application.ExecutablePath + "\" /tray"; } }

        public static bool IsOn()
        {
            try { using (var k = Registry.CurrentUser.OpenSubKey(Key)) return k != null && k.GetValue(ValueName) != null; }
            catch { return false; }
        }

        public static bool PointsHere()
        {
            try { using (var k = Registry.CurrentUser.OpenSubKey(Key)) return k != null && string.Equals(k.GetValue(ValueName) as string, Command, StringComparison.OrdinalIgnoreCase); }
            catch { return false; }
        }

        public static void Set(bool on)
        {
            try
            {
                using (var k = Registry.CurrentUser.CreateSubKey(Key))
                {
                    if (on) k.SetValue(ValueName, Command);
                    else if (k.GetValue(ValueName) != null) k.DeleteValue(ValueName);
                }
            }
            catch { }
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
        static extern int GetLongPathName(string shortPath, StringBuilder longPath, int size);

        static string LongPath(string p)
        {
            try
            {
                var sb = new StringBuilder(1024);
                int n = GetLongPathName(p, sb, sb.Capacity);
                return n > 0 && n < sb.Capacity ? sb.ToString() : p;
            }
            catch { return p; }
        }

        public static bool RunningFromTemp()
        {
            string exe = LongPath(Application.ExecutablePath);
            string[] temps = {
                Path.GetTempPath(),
                Environment.GetEnvironmentVariable("TEMP"),
                Environment.GetEnvironmentVariable("TMP"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Temp")
            };
            foreach (var t in temps)
            {
                if (string.IsNullOrEmpty(t)) continue;
                string lt;
                try { lt = LongPath(Path.GetFullPath(t)).TrimEnd('\\') + "\\"; } catch { continue; }
                if (exe.StartsWith(lt, StringComparison.OrdinalIgnoreCase)) return true;
            }
            return exe.IndexOf("\\Rar$", StringComparison.OrdinalIgnoreCase) >= 0
                || exe.IndexOf("\\7zO", StringComparison.OrdinalIgnoreCase) >= 0
                || exe.IndexOf(".zip\\", StringComparison.OrdinalIgnoreCase) >= 0;
        }
    }

    class TrayApp : ApplicationContext
    {
        readonly Core core;
        readonly NotifyIcon tray = new NotifyIcon();
        readonly MainForm form;
        readonly ToolStripMenuItem statusItem;
        readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
        Icon iconOn, iconOff;
        bool dirty = true, lastOk;

        public TrayApp(Core core, bool showWindow, EventWaitHandle showEvent)
        {
            this.core = core;
            form = new MainForm(core);
            using (var b = Theme.Logo(32, Theme.Green)) iconOn = Theme.IconOf(b);
            using (var b = Theme.Logo(32, Theme.Gray)) iconOff = Theme.IconOf(b);

            var menu = new ContextMenuStrip { RightToLeft = RightToLeft.Yes, Font = Theme.F(10, false) };
            statusItem = new ToolStripMenuItem(Program.Name) { Enabled = false };
            menu.Items.Add(statusItem);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("פתיחת החלון", null, (s, e) => ShowForm());
            menu.Items.Add("הצגת גוגל צ'אט", null, (s, e) => core.ShowChrome(true));
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("יציאה", null, (s, e) => Exit());
            tray.ContextMenuStrip = menu;
            tray.Icon = iconOff;
            tray.Text = Program.Name;
            tray.Visible = true;
            tray.DoubleClick += (s, e) => ShowForm();
            tray.MouseClick += (s, e) => { if (e.Button == MouseButtons.Left) ShowForm(); };

            if (!core.St.AutoStartDone && !Program.TestMode)
            {
                var ans = MessageBox.Show(
                    "להפעיל את " + Program.Name + " אוטומטית עם כל הדלקה של המחשב?\n\nכך הנגן יקבל הודעות בלי שצריך לפתוח את התוכנה כל פעם. אפשר לשנות את זה בכל זמן בחלון התוכנה.",
                    Program.Name, MessageBoxButtons.YesNo, MessageBoxIcon.Question, MessageBoxDefaultButton.Button1,
                    MessageBoxOptions.RightAlign | MessageBoxOptions.RtlReading);
                AutoStart.Set(ans == DialogResult.Yes);
                core.St.AutoStartDone = true;
                core.SaveState();
                form.SyncAutoStart();
            }
            else if (!Program.TestMode && AutoStart.IsOn() && !AutoStart.PointsHere()) AutoStart.Set(true);

            core.Changed += () => { dirty = true; };
            timer.Interval = 500;
            timer.Tick += (s, e) => Tick();
            timer.Start();

            var t = new Thread(() =>
            {
                while (true)
                {
                    showEvent.WaitOne();
                    try { form.BeginInvoke(new Action(ShowForm)); } catch { }
                }
            }) { IsBackground = true };
            t.Start();

            form.CreateControl();
            var h = form.Handle;
            if (showWindow) ShowForm();
            else tray.ShowBalloonTip(3000, Program.Name, Program.Name + " רץ ברקע. לחיצה על האייקון פותחת את החלון.", ToolTipIcon.Info);
        }

        void Tick()
        {
            bool ok = core.Connected && core.GOk;
            if (ok != lastOk) { lastOk = ok; tray.Icon = ok ? iconOn : iconOff; dirty = true; }
            if (!dirty) return;
            dirty = false;
            string s = (core.GOk ? "גוגל צ'אט: מחובר" : "גוגל צ'אט: לא מחובר") + " · " + (core.Connected ? "נגן: מחובר" : "נגן: ממתין");
            statusItem.Text = s;
            string tip = Program.Name + " — " + s;
            tray.Text = tip.Length > 63 ? tip.Substring(0, 63) : tip;
            if (form.Visible) form.RefreshView();
        }

        void ShowForm()
        {
            form.RefreshView();
            form.Show();
            if (form.WindowState == FormWindowState.Minimized) form.WindowState = FormWindowState.Normal;
            form.Activate();
        }

        void Exit()
        {
            tray.Visible = false;
            form.AllowClose = true;
            form.Close();
            ExitThread();
        }
    }
}
