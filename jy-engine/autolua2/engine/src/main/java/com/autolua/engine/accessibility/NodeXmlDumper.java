package com.autolua.engine.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.GridLayout;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.TableLayout;

import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 无障碍整树 XML 序列化器（对照懒人 l1Lll.java / NodeDumpByObject 逐行实现）：
 * {@link android.util.Xml#newSerializer()} 流式输出，把整个窗口节点树一次序列化为 XML，
 * 替代"逐节点跨进程遍历匹配"——一次序列化即可让脚本侧拿到与 uiautomator dump 同构的整树。
 *
 * <p>序列化规则（对照 l1Lll.java:68-195）：</p>
 * <ul>
 *   <li>属性集：index/depth/text/resource-id/class/package/content-desc + 9 个 boolean 态
 *       + visible-to-user/bounds/bounds-in-parent；drawing-order 仅 API≥24 取 getDrawingOrder()，
 *       否则写 0（l1Lll.java:102-106）；</li>
 *   <li>可见性剪枝：非 includeInvisible 时 isVisibleToUser()==false 的子树整体跳过（记得 recycle）；
 *   <li>NAF 标记：clickable+enabled 且全树无 text/desc（递归检查），或类名 ∈
 *       {GridView, GridLayout, ListView, TableLayout} → empty="true"（l1Lll.java:99-101/208-224）；
 *   <li>坐标修正：bounds 先 intersect 屏幕矩形裁剪；targetW/H 有效时按比例缩放；
 *       旋转奇偶（90°/270°）导致坐标系宽高方向变化时做宽高对调修正。</li>
 * </ul>
 */
public final class NodeXmlDumper {
    private static final String TAG = "NodeXmlDumper";

    /** NAF 容器类名白名单：这些容器直接判定为空节点（对照 l1Lll.java:25） */
    private static final String[] NAF_CONTAINER_CLASSES = {
            GridView.class.getName(),
            GridLayout.class.getName(),
            ListView.class.getName(),
            TableLayout.class.getName()
    };

    private NodeXmlDumper() {
    }

    /**
     * dump 当前所有窗口节点树为 XML 字符串（synchronized 防并发，对照文档 4.3.3）。
     *
     * @param service          无障碍服务（需已连接且 canRetrieveWindowContent=true）
     * @param includeInvisible true=包含不可见节点；false=可见性剪枝
     * @param targetW          目标坐标系宽（虚拟屏缩放用，&lt;=0 表示不缩放）
     * @param targetH          目标坐标系高
     * @return XML 字符串；失败返回 null
     */
    public static synchronized String dumpXml(AccessibilityService service, boolean includeInvisible,
                                              int targetW, int targetH) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        if (dumpXml(service, includeInvisible, targetW, targetH, baos)) {
            try {
                return baos.toString("UTF-8");
            } catch (Exception e) {
                Log.w(TAG, "decode xml failed", e);
            }
        }
        return null;
    }

    /**
     * 重载：整树 XML 直接写入输出流（供 {@link AccessibilityNodeTree} 经
     * ParcelFileDescriptor 管道流式传输使用，规避 Binder 1MB 事务缓冲限制）。
     *
     * @return true=序列化成功
     */
    public static synchronized boolean dumpXml(AccessibilityService service, boolean includeInvisible,
                                               int targetW, int targetH, OutputStream outputStream) {
        if (service == null || outputStream == null) {
            return false;
        }
        try {
            // —— 当前屏幕宽高与旋转角（用于坐标裁剪与缩放，对照 l1Lll.java:146-195） ——
            int curW = 0;
            int curH = 0;
            int rotation = 0;
            try {
                WindowManager wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
                Display display = wm != null ? wm.getDefaultDisplay() : null;
                if (display != null) {
                    Point size = new Point();
                    display.getRealSize(size);
                    curW = size.x;
                    curH = size.y;
                    rotation = display.getRotation();
                }
            } catch (Throwable t) {
                Log.w(TAG, "read display size failed", t);
            }
            if (curW <= 0 || curH <= 0) {
                // 兜底：拿不到屏幕尺寸时以目标尺寸近似
                curW = Math.max(targetW, 0);
                curH = Math.max(targetH, 0);
            }
            // 目标坐标系与当前旋转的宽高方向不一致（90°/270° 奇数旋转的典型表现）时，
            // 交换目标宽高，保证缩放比例方向正确（对照参考实现"奇数旋转交换宽高"修正规则）
            if (targetW > 0 && targetH > 0 && curW > 0 && curH > 0
                    && (curW > curH) != (targetW > targetH)) {
                int tmp = targetW;
                targetW = targetH;
                targetH = tmp;
            }

            XmlSerializer serializer = android.util.Xml.newSerializer();
            try {
                // 缩进输出（对照参考实现 setFeature，序列化器不支持时忽略）
                serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            } catch (Throwable ignored) {
            }
            serializer.setOutput(outputStream, "UTF-8");
            serializer.startDocument("UTF-8", Boolean.TRUE);
            serializer.startTag("", "hierarchy");
            serializer.attribute("", "rotation", Integer.toString(rotation));

            // 遍历所有窗口根节点（getWindows 需 FLAG_RETRIEVE_INTERACTIVE_WINDOWS，API 21+）
            int serializedCount = 0;
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null && !windows.isEmpty()) {
                for (int i = 0; i < windows.size(); i++) {
                    AccessibilityWindowInfo window = windows.get(i);
                    AccessibilityNodeInfo root = window == null ? null : window.getRoot();
                    if (root == null) {
                        continue;
                    }
                    // 可见性剪枝：非 includeInvisible 时不可见子树跳过（记得 recycle，对照 l1Lll.java:68-76）
                    if (includeInvisible || root.isVisibleToUser()) {
                        serializeNode(root, serializer, i, curW, curH, 1, targetW, targetH, includeInvisible);
                        serializedCount++;
                    }
                    safeRecycle(root);
                }
            }
            if (serializedCount == 0) {
                // 兜底：无窗口列表时取活动窗口根节点
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root != null) {
                    serializeNode(root, serializer, 0, curW, curH, 1, targetW, targetH, includeInvisible);
                    safeRecycle(root);
                }
            }

            serializer.endTag("", "hierarchy");
            serializer.endDocument();
            serializer.flush();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "dumpXml failed", t);
            return false;
        }
    }

    /**
     * 递归序列化单个节点（对照 l1Lll.java:95-144 的 ILil()）。
     * 不回收入参 node（由调用方负责）。
     */
    private static void serializeNode(AccessibilityNodeInfo node, XmlSerializer serializer,
                                      int index, int curW, int curH, int depth,
                                      int targetW, int targetH, boolean includeInvisible)
            throws Exception {
        serializer.startTag("", "node");
        // NAF 标记（对照 l1Lll.java:99-101）：非容器类 且 clickable+enabled 且全树无 text/desc
        if (!isNafContainerClass(node) && isNafNode(node)) {
            serializer.attribute("", "empty", Boolean.toString(true));
        }
        // drawing-order：仅 API≥24 取 getDrawingOrder()，否则写 0（对照 l1Lll.java:102-106）
        if (Build.VERSION.SDK_INT >= 24) {
            serializer.attribute("", "drawing-order", Integer.toString(node.getDrawingOrder()));
        } else {
            serializer.attribute("", "drawing-order", Integer.toString(0));
        }
        serializer.attribute("", "index", Integer.toString(index));
        serializer.attribute("", "depth", Integer.toString(depth));
        serializer.attribute("", "text", safeText(node.getText()));
        serializer.attribute("", "resource-id", safeText(node.getViewIdResourceName()));
        serializer.attribute("", "class", safeText(node.getClassName()));
        serializer.attribute("", "package", safeText(node.getPackageName()));
        serializer.attribute("", "content-desc", safeText(node.getContentDescription()));
        serializer.attribute("", "checkable", Boolean.toString(node.isCheckable()));
        serializer.attribute("", "checked", Boolean.toString(node.isChecked()));
        serializer.attribute("", "clickable", Boolean.toString(node.isClickable()));
        serializer.attribute("", "enabled", Boolean.toString(node.isEnabled()));
        serializer.attribute("", "focusable", Boolean.toString(node.isFocusable()));
        serializer.attribute("", "focused", Boolean.toString(node.isFocused()));
        serializer.attribute("", "scrollable", Boolean.toString(node.isScrollable()));
        serializer.attribute("", "long-clickable", Boolean.toString(node.isLongClickable()));
        serializer.attribute("", "password", Boolean.toString(node.isPassword()));
        serializer.attribute("", "selected", Boolean.toString(node.isSelected()));
        serializer.attribute("", "visible-to-user", Boolean.toString(node.isVisibleToUser()));
        // bounds：先与屏幕矩形 intersect 裁剪，再按 targetW/H 比例缩放（对照 l1Lll.java:146-169）
        serializer.attribute("", "bounds",
                scaleBounds(node, false, curW, curH, targetW, targetH).toShortString());
        serializer.attribute("", "bounds-in-parent",
                scaleBounds(node, true, curW, curH, targetW, targetH).toShortString());

        // 子节点递归（对照 l1Lll.java:127-142）：index 取原始子位置，depth+1
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child;
            try {
                child = node.getChild(i);
            } catch (Throwable t) {
                continue; // 节点已失效等异常，跳过该子节点
            }
            if (child == null) {
                continue;
            }
            if (includeInvisible || child.isVisibleToUser()) {
                serializeNode(child, serializer, i, curW, curH, depth + 1, targetW, targetH, includeInvisible);
                safeRecycle(child);
            } else {
                safeRecycle(child);
            }
        }
        serializer.endTag("", "node");
    }

    /**
     * 坐标修正（对照 l1Lll.java:146-195 的 Ilil()/m859IL()）：
     * bounds 先 intersect 屏幕矩形(0,0,curW,curH) 裁剪；
     * targetW/H 有效时按 targetW/curW、targetH/curH 比例缩放全部坐标。
     *
     * @param inParent true=取 boundsInParent，false=取 boundsInScreen
     */
    private static Rect scaleBounds(AccessibilityNodeInfo node, boolean inParent,
                                    int curW, int curH, int targetW, int targetH) {
        Rect rect = new Rect();
        if (inParent) {
            node.getBoundsInParent(rect);
        } else {
            node.getBoundsInScreen(rect);
        }
        if (curW > 0 && curH > 0) {
            Rect screen = new Rect(0, 0, curW, curH);
            rect.intersect(screen);
        }
        if (targetW > 0 && targetH > 0 && curW > 0 && curH > 0) {
            float sx = (targetW * 1.0f) / curW;
            float sy = (targetH * 1.0f) / curH;
            rect.left = (int) (rect.left * sx);
            rect.top = (int) (rect.top * sy);
            rect.right = (int) (rect.right * sx);
            rect.bottom = (int) (rect.bottom * sy);
        }
        return rect;
    }

    /**
     * NAF 判定（对照 l1Lll.java:219-224 的 lLi1LL()）：
     * clickable+enabled 且自身 text/desc 全空 且 全树（递归）无任何 text/desc → 空节点。
     */
    private static boolean isNafNode(AccessibilityNodeInfo node) {
        if (!safeText(node.getText()).isEmpty() || !safeText(node.getContentDescription()).isEmpty()) {
            return false;
        }
        if (!node.isClickable() || !node.isEnabled()) {
            return false;
        }
        return !hasTextOrDescInSubtree(node);
    }

    /** 递归检查子树中是否存在任何 text/desc（对照 l1Lll.java:82-93 的 IL1Iii()） */
    private static boolean hasTextOrDescInSubtree(AccessibilityNodeInfo node) {
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child;
            try {
                child = node.getChild(i);
            } catch (Throwable t) {
                continue;
            }
            if (child == null) {
                continue;
            }
            boolean found = !safeText(child.getContentDescription()).isEmpty()
                    || !safeText(child.getText()).isEmpty()
                    || hasTextOrDescInSubtree(child);
            safeRecycle(child);
            if (found) {
                return true;
            }
        }
        return false;
    }

    /** NAF 容器白名单判定：类名以 {GridView, GridLayout, ListView, TableLayout} 结尾（对照 l1Lll.java:208-216） */
    private static boolean isNafContainerClass(AccessibilityNodeInfo node) {
        CharSequence className = node.getClassName();
        if (className == null) {
            return false;
        }
        String name = className.toString();
        for (String container : NAF_CONTAINER_CLASSES) {
            if (name.endsWith(container)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 文本清洗：null → ""，去除 XML 1.0 非法控制字符（0x00-0x08/0x0B/0x0C/0x0E-0x1F），
     * 替换为 '?'（对照参考实现的字符清洗；&lt;&gt;&amp;&quot; 由 XmlSerializer 自行转义）
     */
    private static String safeText(CharSequence cs) {
        if (cs == null) {
            return "";
        }
        return cs.toString().replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "?");
    }

    /**
     * 节点回收（兼容处理）：部分机型/ROM 对已失效或重复回收的节点会抛异常，
     * 统一 try-catch 吞掉，保证序列化流程不中断
     */
    private static void safeRecycle(AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        try {
            node.recycle();
        } catch (Throwable ignored) {
        }
    }
}
