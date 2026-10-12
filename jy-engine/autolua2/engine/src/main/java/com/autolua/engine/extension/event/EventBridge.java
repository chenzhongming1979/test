package com.autolua.engine.extension.event;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统事件队列桥（event.onToast/onNotification/onTouch/onClick/onKey 的跨进程通路）：
 *
 * <ul>
 *   <li>写端：宿主无障碍服务（AutoLuaAccessibilityService）过滤 toast/notification/click/touch/key
 *       事件，追加式写 JSON-lines 队列文件（单行一个事件，seq 单调递增）</li>
 *   <li>读端：Lua 引擎进程（root uid=0 免权限直读 / 沙箱同进程）经 EventLib 轮询，按
 *       「自上次已读 seq 之后」取新事件；每次 poll 顺手覆盖写心跳文件（60ms 一次）</li>
 *   <li>截断重置：队列文件超过 {@link #MAX_QUEUE_BYTES} 或转发开启时，整体重写并先写一行
 *       reset 标记，seq 继续单调递增；读端遇 reset 标记（或 seq 回退）自动重新同步</li>
 *   <li>心跳：读端每次 poll 更新 {@link #HEARTBEAT_FILE}；无障碍服务据此判定
 *       「脚本正在消费事件」，仅在心跳有效期内拦截音量键（onKeyEvent）</li>
 *   <li>开关：forwardEnabled 默认 false（无监听零文件 IO）；root 引擎经 am broadcast
 *       （ACTION_EVENT_FORWARD）置位，沙箱同进程直接设静态字段</li>
 * </ul>
 *
 * 队列文件格式（JSON-lines，单行一个 JSON 对象，UTF-8）：
 * <pre>
 *   事件行：{"seq":1,"type":"toast","pkg":"...","cls":"...","text":"...","ts":1695000000000}
 *   按键行：{"seq":2,"type":"key","key":24,"action":0,"pkg":"...","cls":"","text":"","ts":...}
 *   重置行：{"seq":3,"reset":1}   （seq = 本轮重置前最后一个已分配序号，读端以此重新对齐）
 * </pre>
 * 字符串字段经 {@link #jsonEsc(String)} 转义（引号/反斜杠/换行/控制符），保证单行完整 JSON。
 */
public final class EventBridge {

    private static final String TAG = "EventBridge";

    /** 宿主 filesDir 固定路径（applicationId 固定；root uid=0 免权限直读） */
    public static final String MIRROR_FILE = "/data/user/0/com.autolua.autolua2/files/event_mirror";
    /** 读端心跳镜像（覆盖写单行 elapsedRealtime 时间戳；服务端 3 秒内有效） */
    public static final String HEARTBEAT_FILE = MIRROR_FILE + ".hb";

    /** 队列文件上限（256KB）：超过即截断重置，避免长跑脚本文件无限增长 */
    public static final long MAX_QUEUE_BYTES = 256L * 1024L;
    /** 心跳有效期：3 秒内有心跳视为「脚本正在消费事件」（音量键智能拦截依据） */
    public static final long HEARTBEAT_TTL_MS = 3000L;

    /** reset 标记行解析后的内部 type 值（仅供读端识别，不对外派发） */
    static final String TYPE_RESET = "__reset__";

    /** root 引擎 → 宿主服务：开启/关闭事件转发（am broadcast） */
    public static final String ACTION_EVENT_FORWARD = "com.autolua.action.EVENT_FORWARD";
    public static final String EXTRA_ENABLE = "enable";

    /** 事件转发开关（无监听时 false，避免高频事件写文件开销） */
    public static volatile boolean forwardEnabled = false;

    /** 写端事件序号：单调递增（写端进程生命周期内，截断重置不回退） */
    private static long sSeq = 0L;
    /** 写端本次开启周期是否已写 reset 标记（false 时下次写入先截断重置） */
    private static boolean sResetWritten = false;

    private EventBridge() {
    }

    // ==================== 事件载体 ====================

    /** 队列事件（只读载体）：key/action 仅 type=key 时有效（其余为 -1） */
    public static final class Event {
        public final long seq;
        public final String type;
        public final int key;
        public final int action;
        public final String pkg;
        public final String cls;
        public final String text;
        public final long ts;

        Event(long seq, String type, int key, int action,
              String pkg, String cls, String text, long ts) {
            this.seq = seq;
            this.type = type;
            this.key = key;
            this.action = action;
            this.pkg = pkg;
            this.cls = cls;
            this.text = text;
            this.ts = ts;
        }
    }

    // ==================== 写端（无障碍服务进程） ====================

    /** 服务端写系统事件（toast/notification/click/touch；追加进队列） */
    public static void writeEvent(String type, String pkg, String cls, String text) {
        if (!forwardEnabled) {
            return; // 禁用时零文件 IO
        }
        appendQueue(buildEventJson(type, pkg, cls, text, -1, -1));
    }

    /** 服务端写按键事件（onKeyEvent 拦截的音量键；payload 含 keyCode/action） */
    public static void writeKey(int keyCode, int action, String pkg) {
        if (!forwardEnabled) {
            return; // 禁用时零文件 IO
        }
        appendQueue(buildEventJson("key", pkg, "", "", keyCode, action));
    }

    /**
     * 转发开启时调用（服务 receiver / EventLib.__event_enable）：
     * 截断重置队列并写 reset 标记，让读端放弃上一轮残留事件（新脚本不收旧事件）。
     */
    public static synchronized void onForwardEnabled() {
        sResetWritten = false;
        try {
            File dst = new File(MIRROR_FILE);
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            // 标记 seq = 当前已分配序号：其后首个事件 seq+1 > 标记 seq，读端全部视为新事件
            byte[] marker = ("{\"seq\":" + sSeq + ",\"reset\":1}\n").getBytes("UTF-8");
            try (FileOutputStream fos = new FileOutputStream(dst, false)) {
                fos.write(marker);
            }
            sResetWritten = true;
        } catch (Throwable t) {
            // 目录不可写等异常不致命：留待下次 appendQueue 重试重置
            Log.w(TAG, "write reset marker failed: " + t);
        }
    }
    /** 事件行 JSON（字段固定；key/action 仅按键事件携带，读端缺省为 -1） */
    private static String buildEventJson(String type, String pkg, String cls, String text,
                                         int key, int action) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"seq\":").append(++sSeq)
          .append(",\"type\":\"").append(jsonEsc(type)).append('"');
        if (key >= 0) {
            sb.append(",\"key\":").append(key).append(",\"action\":").append(action);
        }
        sb.append(",\"pkg\":\"").append(jsonEsc(pkg))
          .append("\",\"cls\":\"").append(jsonEsc(cls))
          .append("\",\"text\":\"").append(jsonEsc(text))
          .append("\",\"ts\":").append(System.currentTimeMillis())
          .append('}');
        return sb.toString();
    }

    /**
     * 追加进队列；文件超限或写端重启后未重置时，先写 reset 标记行并整体截断。
     * O_APPEND 单次 write 追加（不做 fsync：进程崩溃最多损失末行，读端按行容错）；
     * 磁盘满/无权限等异常只记日志，绝不崩服务。
     */
    private static synchronized void appendQueue(String line) {
        try {
            File dst = new File(MIRROR_FILE);
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            byte[] data = (line + "\n").getBytes("UTF-8");
            if (!sResetWritten || dst.length() + data.length > MAX_QUEUE_BYTES) {
                // 截断重置：先写 reset 标记（seq = 重置前最后已分配序号，不回退），
                // 读端据标记清空缓冲并以标记 seq 为新基线重新同步
                byte[] marker = ("{\"seq\":" + (sSeq - 1) + ",\"reset\":1}\n").getBytes("UTF-8");
                try (FileOutputStream fos = new FileOutputStream(dst, false)) {
                    fos.write(marker);
                    fos.write(data);
                }
                sResetWritten = true;
            } else {
                // 常规追加
                try (FileOutputStream fos = new FileOutputStream(dst, true)) {
                    fos.write(data);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "appendQueue failed: " + t);
        }
    }

    // ==================== 读端（Lua 引擎进程） ====================

    /** 读端游标：维护已读 seq（内存态，读端进程生命周期内有效），自动处理截断重置 */
    public static final class Reader {
        private long lastSeq = -1;

        /**
         * 取「自上次已读 seq 之后」的所有新事件（按文件顺序）。
         * 遇 reset 标记：丢弃本次已收集事件并以标记 seq 为新基线（重置前的残留全部放弃）。
         */
        public synchronized List<Event> poll() {
            List<Event> lines = readLines();
            List<Event> out = new ArrayList<>(lines.size());
            for (Event ev : lines) {
                if (TYPE_RESET.equals(ev.type)) {
                    out.clear();
                    lastSeq = ev.seq;
                } else if (ev.seq > lastSeq) {
                    // 其余 seq <= lastSeq 为已读旧行（含写端重启导致的 seq 回退），跳过不重复派发
                    out.add(ev);
                    lastSeq = ev.seq;
                }
            }
            return out;
        }
    }

    /** 兼容旧「最新一条」语义：返回队列中最后一条完整事件（无视读端游标；无事件返回 null） */
    public static Event readLatestEvent() {
        Event last = null;
        for (Event ev : readLines()) {
            if (!TYPE_RESET.equals(ev.type)) {
                last = ev;
            }
        }
        return last;
    }

    /** 整读队列文件并按行解析（残缺/撕裂行解析失败静默跳过；读异常按空处理） */
    private static List<Event> readLines() {
        ArrayList<Event> out = new ArrayList<>();
        try {
            File f = new File(MIRROR_FILE);
            long flen = f.length();
            if (flen <= 0) {
                return out;
            }
            // 文件受 MAX_QUEUE_BYTES 截断保护，此处上限再多留一行余量
            byte[] data = new byte[(int) Math.min(flen, MAX_QUEUE_BYTES + 4096)];
            int n;
            try (FileInputStream fis = new FileInputStream(f)) {
                n = fis.read(data);
            }
            if (n <= 0) {
                return out;
            }
            String content = new String(data, 0, n, "UTF-8");
            int start = 0;
            while (start < content.length()) {
                int nl = content.indexOf('\n', start);
                int end = nl >= 0 ? nl : content.length();
                if (end > start) {
                    Event ev = parseLine(content.substring(start, end));
                    if (ev != null) {
                        out.add(ev);
                    }
                }
                if (nl < 0) {
                    break;
                }
                start = nl + 1;
            }
        } catch (Throwable ignore) {
            // 读失败（权限/竞态）按无事件处理，不打断轮询
        }
        return out;
    }
    // ==================== 心跳（读端写、服务端读） ====================

    /** 读端心跳：EventLib 每次 poll 调用（覆盖写单行 elapsedRealtime；失败静默不打断轮询） */
    public static void writeHeartbeat() {
        try {
            File f = new File(HEARTBEAT_FILE);
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(Long.toString(SystemClock.elapsedRealtime()).getBytes("UTF-8"));
            }
        } catch (Throwable ignore) {
            // 心跳失败只影响音量键拦截判定，不影响事件转发
        }
    }

    /** 服务端判定「脚本正在消费事件」：心跳时间戳距今不超过 {@link #HEARTBEAT_TTL_MS} */
    public static boolean isScriptConsuming() {
        try {
            File f = new File(HEARTBEAT_FILE);
            byte[] buf = new byte[32];
            int n;
            try (FileInputStream fis = new FileInputStream(f)) {
                n = fis.read(buf);
            }
            if (n <= 0) {
                return false;
            }
            long hb = Long.parseLong(new String(buf, 0, n, "UTF-8").trim());
            return hb > 0 && SystemClock.elapsedRealtime() - hb <= HEARTBEAT_TTL_MS;
        } catch (Throwable t) {
            return false; // 无心跳/文件异常一律视为脚本未监听
        }
    }

    // ==================== JSON 迷你编解码（无第三方依赖） ====================

    /**
     * 迷你 JSON 行解析（写端固定扁平对象，字段按名取值）。
     * 严格解析：末行被写端撕裂/截断时解析失败返回 null，下轮轮询拿到完整行再交付。
     */
    static Event parseLine(String s) {
        try {
            int[] idx = {0};
            ws(s, idx);
            if (idx[0] >= s.length() || s.charAt(idx[0]) != '{') {
                return null;
            }
            idx[0]++;
            long seq = -1;
            String type = null;
            int key = -1, action = -1;
            String pkg = "", cls = "", text = "";
            long ts = 0;
            boolean reset = false;
            ws(s, idx);
            if (idx[0] < s.length() && s.charAt(idx[0]) == '}') {
                idx[0]++; // 空对象
            } else {
                while (true) {
                    ws(s, idx);
                    String name = jstr(s, idx);
                    ws(s, idx);
                    if (idx[0] >= s.length() || s.charAt(idx[0]) != ':') {
                        return null;
                    }
                    idx[0]++;
                    ws(s, idx);
                    if (idx[0] >= s.length()) {
                        return null;
                    }
                    if (s.charAt(idx[0]) == '"') {
                        String v = jstr(s, idx);
                        if ("type".equals(name)) type = v;
                        else if ("pkg".equals(name)) pkg = v;
                        else if ("cls".equals(name)) cls = v;
                        else if ("text".equals(name)) text = v;
                    } else {
                        long v = jnum(s, idx);
                        if ("seq".equals(name)) seq = v;
                        else if ("key".equals(name)) key = (int) v;
                        else if ("action".equals(name)) action = (int) v;
                        else if ("ts".equals(name)) ts = v;
                        else if ("reset".equals(name)) reset = v != 0;
                    }
                    ws(s, idx);
                    if (idx[0] >= s.length()) {
                        return null;
                    }
                    char d = s.charAt(idx[0]);
                    if (d == ',') {
                        idx[0]++;
                    } else if (d == '}') {
                        idx[0]++;
                        break;
                    } else {
                        return null;
                    }
                }
            }
            if (reset) {
                return new Event(seq, TYPE_RESET, -1, -1, "", "", "", 0);
            }
            if (seq < 0 || type == null) {
                return null;
            }
            return new Event(seq, type, key, action, pkg, cls, text, ts);
        } catch (Throwable t) {
            return null; // 残缺行/撕裂行：静默丢弃
        }
    }

    /** 跳过空白 */
    private static void ws(String s, int[] idx) {
        while (idx[0] < s.length() && s.charAt(idx[0]) <= ' ') {
            idx[0]++;
        }
    }

    /** 解析 JSON 字符串（支持引号/反斜杠/控制符及 unicode 转义） */
    private static String jstr(String s, int[] idx) throws Exception {
        if (idx[0] >= s.length() || s.charAt(idx[0]) != '"') {
            throw new Exception("expect quote");
        }
        idx[0]++;
        StringBuilder sb = new StringBuilder(s.length() - idx[0]);
        while (idx[0] < s.length()) {
            char c = s.charAt(idx[0]++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (idx[0] >= s.length()) {
                    throw new Exception("bad escape");
                }
                char e = s.charAt(idx[0]++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (idx[0] + 4 > s.length()) {
                            throw new Exception("bad unicode");
                        }
                        sb.append((char) Integer.parseInt(s.substring(idx[0], idx[0] + 4), 16));
                        idx[0] += 4;
                        break;
                    default:
                        throw new Exception("bad escape char");
                }
            } else {
                sb.append(c);
            }
        }
        throw new Exception("unterminated string");
    }

    /** 解析 JSON 数字（本桥仅写整数） */
    private static long jnum(String s, int[] idx) throws Exception {
        int start = idx[0];
        while (idx[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(idx[0])) >= 0) {
            idx[0]++;
        }
        if (idx[0] == start) {
            throw new Exception("expect number");
        }
        return Long.parseLong(s.substring(start, idx[0]).trim());
    }

    /** JSON 字符串转义：保证事件内容（含换行/引号/反斜杠/控制符）不破坏单行格式 */
    private static String jsonEsc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append('\\').append(String.format("u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}