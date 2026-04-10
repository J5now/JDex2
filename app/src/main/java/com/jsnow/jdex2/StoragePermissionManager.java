package com.jsnow.jdex2;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class StoragePermissionManager {

    // 定义请求码
    public static final int REQUEST_CODE_ALL_FILES = 1001;
    public static final int REQUEST_CODE_LEGACY_STORAGE = 1002;
    public static final int REQUEST_CODE_DOCUMENT_TREE = 1003;

    /**
     * 检查并请求存储权限
     * @param activity 当前 Activity
     */
    public static void checkAndRequestPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // --- Android 11 (API 30) 及以上 ---
            // 1. 检查所有文件访问权限 (MANAGE_EXTERNAL_STORAGE)
            if (!Environment.isExternalStorageManager()) {
                requestAllFilesPermission(activity);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // --- Android 6.0 (API 23) - Android 10 (API 29) ---
            if (!hasLegacyStoragePermission(activity)) {
                requestLegacyPermission(activity);
            }
        }
        // Android 6.0 以下无需动态申请，清单文件声明即可
    }

    /**
     * 请求 Android/data 或 Android/obb 的 DocumentTree 授权
     * @param activity Activity
     * @param packageName 目标包名 (Android 13+ 建议直接授权包名目录)
     */
    public static void requestDocumentTreePermission(Activity activity, String packageName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

        String uriStr;
        if (Build.VERSION.SDK_INT >= 33) { // Android 13+
            // 授权具体包名目录
            uriStr = "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2F" + packageName 
                    + "/document/primary%3AAndroid%2Fdata%2F" + packageName;
        } else { // Android 11, 12
            // 授权 Android/data 根目录
            uriStr = "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata/document/primary%3AAndroid%2Fdata";
        }

        Uri treeUri = Uri.parse(uriStr);
        // 注意：某些情况下 DocumentFile.fromTreeUri 可能返回 null 或无效，这里按用户提供的参考实现
        intent.putExtra("android.provider.extra.INITIAL_URI", treeUri);
        
        try {
            activity.startActivityForResult(intent, REQUEST_CODE_DOCUMENT_TREE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 跳转至“所有文件访问权限”设置页 (Android 11+)
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    private static void requestAllFilesPermission(Activity activity) {
        try {
            // 方案 A: 直接跳转到本应用的“所有文件访问权限”开关页 (API 30+)
            // 注意：某些厂商 ROM 可能不支持直接跳到该页面
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivityForResult(intent, REQUEST_CODE_ALL_FILES);
        } catch (Exception e) {
            try {
                // 方案 B: 跳转到“所有文件访问权限”应用列表页
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                activity.startActivityForResult(intent, REQUEST_CODE_ALL_FILES);
            } catch (Exception e2) {
                // 方案 C: 兜底跳转到应用详情页
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
                activity.startActivityForResult(intent, REQUEST_CODE_ALL_FILES);
            }
        }
    }

    /**
     * 请求传统存储权限 (Android 6.0 - 10)
     */
    private static void requestLegacyPermission(Activity activity) {
        ActivityCompat.requestPermissions(activity,
                new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                },
                REQUEST_CODE_LEGACY_STORAGE);
    }

    /**
     * 检查是否有传统权限
     */
    private static boolean hasLegacyStoragePermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

}