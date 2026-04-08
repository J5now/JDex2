package com.jsnow.jdex2;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexFile;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class JSHook implements IXposedHookLoadPackage {
    private static String TAG = "JDex2";
    private static String ErrTAG = "JDex2 Error";
    private static String DebugTag = "JDex2 Debugger";
    private static final String SO1_PATH = "/data/local/tmp/libjdex2.so";

    private static boolean innerclassesFilter = false;
    private static boolean nativeLoaded = false;
    private static boolean invokeDebugger = false;
    private static boolean invokeConstructors = false;
    private final HashSet<DexFile> dumpedDexFiles = new HashSet<>();

    private static final String[] CLASS_FILTER_PREFIXES = {
            "android.",
            "androidx.",
            "android.support.",
            "java.",
            "javax.",
            "dalvik.",
            "kotlin.",
            "kotlinx.",
            "com.google.",
            "com.android.",
            "org.apache.",
            "org.json.",
            "org.jetbrains.",
            "org.jspecify.",
            "org.intellij.",
            "org.xmlpull.",
            "sun.",
            "libcore.",
            // 添加了一个BlackList过滤更方便一点
//            "com.alibaba.",     // 某些JSON相关类实例化会出现大量报错，如果不是我们关心的类可以过滤掉
//            "com.baidu.location",
    };

    private static synchronized void ensureNativeLoaded() {
        if (nativeLoaded)
            return;
        try {
            System.load(SO1_PATH);
            Log.e(TAG, "Native loaded: " + SO1_PATH);
            nativeLoaded = true;
        } catch (Throwable t) {
            Log.e(ErrTAG, "Load Library failed", t);
        }
    }

    void LogInfo(XC_LoadPackage.LoadPackageParam lpparam) {
        Log.e(TAG, "packageName: " + lpparam.packageName);
        Log.e(TAG, "processName: " + lpparam.processName);
        Log.e(TAG, "appInfo: " + lpparam.appInfo);
        Log.e(TAG, "classLoader: " + lpparam.classLoader);
    }

    /**
     * 获取壳替换后的真实 ClassLoader
     * mBoundApplication获取LoadedApk这个调用链是Android中也在用的
     */
    private ClassLoader getRealClassLoader(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            if (at == null)
                throw new RuntimeException("ActivityThread is null");

            Object boundApp = XposedHelpers.getObjectField(at, "mBoundApplication");
            if (boundApp == null)
                throw new RuntimeException("mBoundApplication is null");

            Object loadedApk = XposedHelpers.getObjectField(boundApp, "info");
            if (loadedApk == null)
                throw new RuntimeException("LoadedApk is null");

            ClassLoader cl = (ClassLoader) XposedHelpers.callMethod(loadedApk, "getClassLoader");
            if (cl != null) {
                Log.e(TAG, "Got real ClassLoader: " + cl);
                Log.e(TAG, "ClassLoader type: " + cl.getClass().getName());
                return cl;
            }
        } catch (Throwable t) {
            Log.e(ErrTAG, "getRealClassLoader failed", t);
        }
        Log.w(TAG, "Fallback to lpparam.classLoader");
        return lpparam.classLoader;
    }

    /**
     * 遍历 ClassLoader 的所有 dexElements 并全部 dump
     */
    private List<DexFile> getAllDexFiles(ClassLoader loader) {
        List<DexFile> dexFiles = new ArrayList<>();
        try {
            if (!(loader instanceof BaseDexClassLoader)) {
                Log.e(TAG, "Not BaseDexClassLoader: " + loader.getClass().getName());
                return dexFiles;
            }
            Object pathList = XposedHelpers.getObjectField(loader, "pathList");
            Object[] dexElements = (Object[]) XposedHelpers
                    .getObjectField(pathList, "dexElements");
            Log.e(TAG, "dexElements count: " + dexElements.length);

            for (Object element : dexElements) {
                // 某些 element 本身也可能是 null
                if (element == null)
                    continue;
                Object dexFileObj = XposedHelpers.getObjectField(element, "dexFile");
                if (dexFileObj instanceof DexFile) {
                    dexFiles.add((DexFile) dexFileObj);
                }
            }
            Log.e(TAG, "Valid DexFile count: " + dexFiles.size());

        } catch (Throwable t) {
            Log.e(ErrTAG, "getAllDexFiles failed", t);
        }
        return dexFiles;
    }

    private void dumpDex(DexFile dexFile, String outDir) {
        // 防止重复dump，减少性能开销
        synchronized (dumpedDexFiles) {
            if (dumpedDexFiles.contains(dexFile))
                return;
            dumpedDexFiles.add(dexFile);
        }
        try {
            // 通过mCookie去拿到指针进行Dump
            Object cookie = XposedHelpers.getObjectField(dexFile, "mCookie");
            if (cookie instanceof long[]) {
                long[] c = (long[]) cookie;
                dumpDexByCookie(c, outDir);
            }
        } catch (Throwable t) {
            Log.e(ErrTAG, "dumpDex failed", t);
        }
    }

    // 封装一下，全写一块太乱了
    private boolean inClassFilter(String className) {
        for (String prefix : CLASS_FILTER_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
    private boolean matchPrefix(List<String> list, String className) {
        for (String s : list) {
            if (className.startsWith(s)) {
                return true;
            }
        }
        return false;
    }

    private String[] getAllClassName(DexFile dexFile, List<String> whiteList, List<String> blackList) {
        Object mCookie = XposedHelpers.getObjectField(dexFile, "mCookie");
        String[] classes = (String[]) XposedHelpers.callStaticMethod(
                DexFile.class, "getClassNameList", mCookie);
        List<String> filtered = new ArrayList<>();
        // 有白名单就按照白名单 + 黑名单过滤
        for (String className : classes) {
            if (!whiteList.isEmpty()) {
                if (matchPrefix(whiteList, className) && !matchPrefix(blackList, className)) {
                    // 如果类很多的情况下导致JNI全局引用爆了，可以选择开启过滤内部类的主动调用
                    if(innerclassesFilter) {
                        if(!className.contains("$")){

                            filtered.add(className);
                        }
                    } else {
                        filtered.add(className);
                    }
                }
            } else {
                // 否则只过滤黑名单和系统类
                if (!inClassFilter(className) && !matchPrefix(blackList, className)) {
                    if(innerclassesFilter) {
                        if(!className.contains("$")){

                            filtered.add(className);
                        }
                    } else {
                        filtered.add(className);
                    }
                }
            }
        }
        return filtered.toArray(new String[0]);
    }

    private Object[] makeDefaultArgs(Class<?>[] paramTypes) {
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> t = paramTypes[i];
            // 对于引用类型，传入null是合法的（没必要再去构造对应类型参数）
            if (t == boolean.class)
                args[i] = false;
            else if (t == byte.class)
                args[i] = (byte) 0;
            else if (t == char.class)
                args[i] = (char) 0;
            else if (t == short.class)
                args[i] = (short) 0;
            else if (t == int.class)
                args[i] = 0;
            else if (t == long.class)
                args[i] = 0L;
            else if (t == float.class)
                args[i] = 0.0f;
            else if (t == double.class)
                args[i] = 0.0;
            else
                args[i] = null;
        }
        return args;
    }
    // 在类级别定义一个共享实例，避免每次循环都创建新对象
    private static final XC_MethodHook BLOCK_CONSTRUCTOR = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(null); // 构造方法返回 void，用 null
        }
    };

    private void invokeAllConstructors(String[] names, BaseDexClassLoader loader, List<String> blackList) {
        for (String name : names) {
            try {
                // 将黑名单进行剔除，因为在某些APP的某些类中，可能会继承一些当前Android版本不存在的类
                // 这时候尝试new实例会直接导致APP崩溃
                if (matchPrefix(blackList, name)) continue;  // 修复：应该用 matchPrefix 而不是 contains
                Class<?> clazz = XposedHelpers.findClass(name, loader);
                int mod = clazz.getModifiers();
                if (clazz.isInterface() || clazz.isAnnotation()
                        || clazz.isEnum() || Modifier.isAbstract(mod))
                    continue;

                Constructor<?>[] constructors = clazz.getDeclaredConstructors();
                if (constructors.length == 0) continue;

                // 选择任意一个构造方法即可
                Constructor<?> target = constructors[0];
                target.setAccessible(true);
                XC_MethodHook.Unhook unhook = XposedBridge.hookMethod(target, BLOCK_CONSTRUCTOR);
//                Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllConstructors(clazz, BLOCK_CONSTRUCTOR);
                try {
                    // 获取参数列表，通过正确的参数类型调用可以避免在调用中途遇到无法预期的拦截
                    Object[] args = makeDefaultArgs(target.getParameterTypes());
                    if (invokeDebugger) Log.e(DebugTag, "Invoke Class: " + clazz);
                    // 调用构造方法，触发代码的回填
                    target.newInstance(args);
                } catch (Throwable ignored) {
                } finally {
                    // 为了防止某些加固通过检测方法是否被转为Native方法来检测Hook，无论构造是否成功都要解除Hook
                    unhook.unhook();
                }
            } catch (Throwable e) {
                Log.e(ErrTAG, "Failed: " + name, e);
            }
        }
    }

    // 如果在主动调用过程中遇到崩溃情况，可以根据Debugger输出的信息，调用invokeTest来判断是哪些类出了问题
    private void invokeTest(ClassLoader loader, String clazzName) {
        Class<?> clazz = XposedHelpers.findClass(clazzName, loader);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Object obj = clazz.newInstance();
                Log.e(TAG, "New Instance Success: " + obj);
            } catch (Throwable t) {
                Log.e(ErrTAG, "Failed to invoke constructor: " + clazz, t);
            }
        });
    }
    private void scheduleDump(BaseDexClassLoader loader, String outDir,
                              List<String> whiteList, List<String> blackList, AtomicBoolean dumped) {
        // 预留一点时间，让壳对类进行加载
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                ensureNativeLoaded();
                if (!nativeLoaded) return;
                doDump(loader, outDir, whiteList, blackList);
                dumped.set(true);
            } catch (Throwable t) {
                Log.e(ErrTAG, "scheduleDump failed", t);
            }
        }).start();
    }

    private void doDump(BaseDexClassLoader loader, String outDir, List<String>whiteList, List<String> blackList) {
        List<DexFile> allDexFile = getAllDexFiles(loader);
        Log.e(TAG, "doDump: found " + allDexFile.size() + " DexFiles in " + loader);
        for (DexFile dexFile : allDexFile) {
            if (dexFile != null) {
                if(isDexAlreadyDumped(dexFile, outDir)){
                    continue;
                }
                String[] names = getAllClassName(dexFile, whiteList, blackList);
                if(invokeConstructors){
                    invokeAllConstructors(names, loader, blackList);
                }
                dumpDex(dexFile, outDir);
            }
        }
    }
    private boolean isDexAlreadyDumped(DexFile dexFile, String outDir) {
        try {
            Object cookie = XposedHelpers.getObjectField(dexFile, "mCookie");
            if (!(cookie instanceof long[])) return false;

            long[] dexSizes = getDexSizesByCookie((long[]) cookie);
            if (dexSizes == null || dexSizes.length == 0) return false;

            // 构造路径检查
            for (long size : dexSizes) {
                File dexFile2 = new File(outDir + size + ".dex");
                if (!dexFile2.exists()) {
                    // 有 dex 没 dump 过，不能跳过
                    return false;
                }
            }

            Log.e(TAG, "All DEX in this cookie already dumped, skip invoke. sizes=" + Arrays.toString(dexSizes));
            return true;
        } catch (Throwable t) {
            Log.e(ErrTAG, "isDexAlreadyDumped check failed", t);
        }
        return false;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        // 根据TARGET_FILE判断是否是我们想要脱壳的APP
        // 使用前记得给予目标APP内存读写权限
        Properties props = new Properties();
        try (InputStream input = new FileInputStream("/data/data/" + loadPackageParam.packageName + "/files/config.properties")) {
                props.load(input);
        } catch (Throwable e){
//            Log.e(ErrTAG, "Load config failed!" + e);
        }

        String targetApp = props.getProperty("targetApp");
        if(!targetApp.equals(loadPackageParam.packageName)){
            return;
        }
        invokeDebugger = Boolean.parseBoolean(props.getProperty("invokeDebugger"));
        boolean hook = Boolean.parseBoolean(props.getProperty("hook"));
        innerclassesFilter = Boolean.parseBoolean(props.getProperty("innerclassesFilter"));
        invokeConstructors = Boolean.parseBoolean(props.getProperty("invokeConstructors"));

        // 使用","分隔多个黑名单/白名单字符串
        // 使用白名单进行类的筛选，避免触发其它无法预知的逻辑导致App崩溃（也可以不设置白名单和黑名单进行脱壳）
        List<String> whiteList = Arrays.stream(
                        props.getProperty("whiteList", "").split(",")
                ).map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        // 会通过startsWith进行黑名单类名字符串匹配
        List<String> blackList = Arrays.stream(
                        props.getProperty("blackList", "").split(",")
                ).map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        for(String black : blackList){
            Log.e(TAG, "Black List: " + black);
        }
        for(String white : whiteList){
            Log.e(TAG, "White List: " + white);
        }
        // 对包名和线程名都做过滤，否则会导致对新开的线程也进行脱壳
        if (!loadPackageParam.processName.equals(targetApp))
            return;
        if (!loadPackageParam.packageName.equals(targetApp))
            return;
        LogInfo(loadPackageParam);
        final String outDir = "/data/data/" + loadPackageParam.packageName + "/dumpDex/";
        File dir = new File(outDir);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Failed to create output directory: " + outDir);
            return;
        }

        // 使用Hook模式去脱壳
        // 为了应对Native层进行Load导致某些类脱不全的情况，但是Hook方法会更容易被检测到
        if (hook) {
            Log.e(TAG, "Dump Dex By Hook");
            ensureNativeLoaded();
            if (!nativeLoaded) return;

            final AtomicBoolean dumped = new AtomicBoolean(false);
            XposedBridge.hookAllConstructors(BaseDexClassLoader.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Log.e(TAG, "New BaseDexClassLoader: " + param.thisObject);
                    scheduleDump((BaseDexClassLoader) param.thisObject, outDir, whiteList, blackList, dumped);
                }
            });

            //   Hook DexPathList 的 makeDexElements / makePathElements
            //   因为一些壳根本就不新建classloader，而是向其中插入Dex，所以完美想要Hook创建dex成员的方法
            Class<?> dexPathListClass = XposedHelpers.findClass(
                    "dalvik.system.DexPathList", loadPackageParam.classLoader);
            String[] methodNames = {
                    "makeDexElements",        // DexClassLoader
                    "makePathElements",       // PathClassLoader
                    "makeInMemoryDexElements" // InMemoryDexClassLoader
            };

            for (String methodName : methodNames) {
                try {
                    XposedBridge.hookAllMethods(dexPathListClass, methodName, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
//                            Log.e(TAG, methodName + " called on: " + param.thisObject);
                            // param.thisObject 是 DexPathList 实例，通过 definingContext 字段拿到对应的 ClassLoader
                            try {
                                ClassLoader cl = (ClassLoader) XposedHelpers
                                        .getObjectField(param.thisObject, "definingContext");
                                if (cl instanceof BaseDexClassLoader) {
                                    // 对每一个Hook到的ClassLoader都进行主动调用Dump
                                    scheduleDump((BaseDexClassLoader) cl, outDir, whiteList, blackList, dumped);
                                }
                            } catch (Throwable t) {
                                Log.e(ErrTAG, "Failed to get definingContext", t);
                            }
                        }
                    });
                    Log.e(TAG, "Hooked: " + methodName);
                } catch (Throwable t) {
                    Log.w(TAG, methodName + " not found, skip");
                }
            }

            // 从原始 classLoader 也进行提取，有些壳可能会使用原始App的Loader进行加载
            new Thread(() -> {
                try {
                    Thread.sleep(10000);
                    if (dumped.get()) {
                        Log.e(TAG, "Already dumped via hook, skip fallback");
                        return;
                    }
                    Log.e(TAG, "Fallback: dumping from lpparam.classLoader");
                    ensureNativeLoaded();
                    if (!nativeLoaded) return;

                    ClassLoader cl = loadPackageParam.classLoader;
                    if (cl instanceof BaseDexClassLoader) {
                        doDump((BaseDexClassLoader) cl, outDir, whiteList, blackList);
                    }
                    // 也尝试从 ActivityThread 获取真实的ClassLoader
                    ClassLoader realCl = getRealClassLoader(loadPackageParam);
                    if (realCl != cl && realCl instanceof BaseDexClassLoader) {
                        doDump((BaseDexClassLoader) realCl, outDir, whiteList, blackList);
                    }
                } catch (Throwable t) {
                    Log.e(ErrTAG, "Fallback dump failed", t);
                }
            }).start();
        } else {
            // Hook onCreate，attachBaseContent等方法很容易被检测出来，所以选择延时反射脱壳，加强检测难度
            // 新开一个线程，等待APP壳执行结束之后获取ClassLoader去脱壳
            Log.e(TAG, "Dump Dex By Reflect");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // sleep 10秒，等待壳加载结束
                        Thread.sleep(10000);
                        ensureNativeLoaded();
                        if (!nativeLoaded) {
                            Log.e(TAG, "Native library not loaded, abort!");
                            return;
                        }
                        // 通过LoadedApk获取APP的真实ClassLoader
                        ClassLoader realLoader = getRealClassLoader(loadPackageParam);
                        if (!(realLoader instanceof BaseDexClassLoader)) {
                            Log.e(TAG, "realLoader is not BaseDexClassLoader, skip invokeAllMethod");
                            return;
                        }
                        // 通过DexFile的Cookie将所有Dex文件Dump下来
                        List<DexFile> allDexFile = getAllDexFiles(realLoader);
                        // 调用DexFile内部的getClassNameList方法获取所有的Class名称，然后去进行主动调用
                        for (DexFile dexFile : allDexFile) {
                            if (dexFile != null) {
                                // 如果该 DEX 已经被 dump 过直接跳过
                                if(isDexAlreadyDumped(dexFile, outDir)){
                                    continue;
                                }
                                String[] names = getAllClassName(dexFile, whiteList, blackList);
                                if (invokeConstructors) {
                                    // 只有未 dump 过的 DEX 才需要主动调用触发回填
                                    invokeAllConstructors(names, (BaseDexClassLoader) realLoader, blackList);
                                }

                                // dump 仍然执行（dumpDex 内部有去重逻辑，会自动跳过）
                                dumpDex(dexFile, outDir);
                            }
                        }
                        Log.e(TAG, "All Dex dumps completed!");
                    } catch (Throwable e) {
                        Log.e(ErrTAG, "Reflect dump failed", e);
                    }
                }
            }).start();
        }
    }

    public static native void dumpDexByCookie(long[] cookie, String outDir);
    public static native long[] getDexSizesByCookie(long[] cookie);

}