package com.autolua.engine.input.inject;

import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.Display;
import android.view.textclassifier.TextClassificationManager;

/**
 * 伪造 Context（对照派大星 FakeContext.java）：
 * 包名伪装为 com.android.shell、uid 伪装为 2000（shell）。
 * 供 app_process 注入进程在 initFramework 阶段构造 mInitialApplication 使用。
 * <p>
 * 注意：本类运行于无 app classloader 环境（app_process / Shizuku UserService），
 * 只允许依赖 android.* framework 与 java.*，不得引用 androidx/support。
 */
public class FakeContext extends ContextWrapper {

    public static final String PACKAGE_NAME = "com.android.shell";
    /** 伪装 uid：shell */
    public static final int FAKE_UID = 2000;

    private final ApplicationInfo applicationInfo;

    public FakeContext(Context base) {
        super(base);
        applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = PACKAGE_NAME;
        applicationInfo.uid = FAKE_UID;
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        return applicationInfo;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public String getAttributionTag() {
        return null;
    }

    /**
     * API 31+ 必须伪造 AttributionSource（对照 FakeContext.java:41-45）。
     * AttributionSource$Builder 在 API 31 才进入 SDK，这里用反射构造，
     * 避免低版本类校验问题。
     */
    @Override
    public AttributionSource getAttributionSource() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Class<?> builderClass = Class.forName("android.content.AttributionSource$Builder");
                Object builder = builderClass.getConstructor(int.class).newInstance(FAKE_UID);
                builder = builderClass.getMethod("setPackageName", String.class)
                        .invoke(builder, PACKAGE_NAME);
                return (AttributionSource) builderClass.getMethod("build").invoke(builder);
            } catch (Throwable t) {
                System.err.println("[FakeContext] build AttributionSource failed: " + t);
            }
        }
        return null;
    }

    @Override
    public PackageManager getPackageManager() {
        return getBaseContext().getPackageManager();
    }

    @Override
    public ContentResolver getContentResolver() {
        return getBaseContext().getContentResolver();
    }

    @Override
    public Display getDisplay() {
        return getBaseContext().getDisplay();
    }

    @Override
    public Object getSystemService(String name) {
        // textclassification/textservices 在注入进程内不可用，返回 null 防止初始化崩溃
        if ("textclassification".equals(name) || "textservices".equals(name)) {
            return null;
        }
        return getBaseContext().getSystemService(name);
    }

    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        if (TextClassificationManager.class.equals(serviceClass)) {
            return null;
        }
        return super.getSystemServiceName(serviceClass);
    }
}
