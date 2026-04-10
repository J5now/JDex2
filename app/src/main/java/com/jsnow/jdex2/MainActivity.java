package com.jsnow.jdex2;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import com.jsnow.jdex2.databinding.ActivityMainBinding;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity  {

    private ActivityMainBinding binding;



    private void writeConfig(String content, String targetApp) {
        try {

            // 对于 Android 12-16，用户提到使用零宽字符绕过
            if (Build.VERSION.SDK_INT >= 31) {
                String targetDir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/\u200bdata/" + targetApp + "/files/";

                File targetDirFile = new File(targetDir);
                if (!targetDirFile.exists()) targetDirFile.mkdirs();
                File targetFile = new File(targetDir + "config.properties");
                if (!targetFile.exists()) {
                    targetFile.createNewFile();
                }
                FileOutputStream fos = new FileOutputStream(targetFile, false);
                fos.write(content.getBytes());
                fos.flush();
                fos.close();
                Toast.makeText(this, "写入成功：" + targetFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                return;
            }

            // 对于 Android 11，尝试使用 DocumentFile (如果已授权)
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                String path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/" + targetApp + "/files";
                Uri uri = pathToUri(path);
                DocumentFile df = DocumentFile.fromTreeUri(this, uri);
                
                // 检查是否能直接写，或者是否需要请求权限
                if (df != null && df.canWrite()) {
                    DocumentFile configFile = df.findFile("config.properties");
                    if (configFile == null) {
                        configFile = df.createFile("text/plain", "config.properties");
                    }
                    if (configFile != null) {
                        OutputStream os = getContentResolver().openOutputStream(configFile.getUri());
                        os.write(content.getBytes());
                        os.flush();
                        os.close();
                        Toast.makeText(this, "写入成功：" + path, Toast.LENGTH_LONG).show();
                        return;
                    }
                } else {
                    // 如果没权限，且不是零宽能解决的，提示授权
                    Toast.makeText(this, "请先点击授权 Android/data 目录", Toast.LENGTH_SHORT).show();
                    StoragePermissionManager.requestDocumentTreePermission(this, targetApp);
                    return;
                }
            }

            // Android 10 及以下，或者作为兜底
            String targetDir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/" + targetApp + "/files/";
            File targetDirFile = new File(targetDir);
            if (!targetDirFile.exists()) targetDirFile.mkdirs();
            File targetFile = new File(targetDir + "config.properties");
            FileOutputStream fos = new FileOutputStream(targetFile, false);
            fos.write(content.getBytes());
            fos.flush();
            fos.close();
            Toast.makeText(this, "写入成功：" + targetFile.getAbsolutePath(), Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            e.printStackTrace();
            Log.d("JDEX2", e.getMessage());
            Toast.makeText(this, "写入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 将普通路径转换为 SAF 树状 URI
     */
    private Uri pathToUri(String path) {
        String subPath = path.replace(Environment.getExternalStorageDirectory().getAbsolutePath() + "/", "");
        String encodedPath = subPath.replace("/", "%2F");
        
        if (Build.VERSION.SDK_INT >= 33) { // Android 13+
            // 按照参考：tree/primary:Android/data/com.xxx.yyy/document/primary:Android/data/com.xxx.yyy
            // 这里我们简化处理，如果路径包含包名，截取到包名层级作为 tree root
            String[] segments = subPath.split("/");
            StringBuilder rootPath = new StringBuilder();
            if (segments.length >= 3) { // Android/data/package
                rootPath.append(segments[0]).append("%2F").append(segments[1]).append("%2F").append(segments[2]);
            } else {
                rootPath.append(encodedPath);
            }
            return Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A" + rootPath + "/document/primary%3A" + encodedPath);
        } else {
            // Android 11-12
            return Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata/document/primary%3A" + encodedPath);
        }
    }


//    private boolean isTargetAppInstalled(String targetApp) {
//        Process process = null;
//        DataOutputStream os = null;
//        try {
//            process = Runtime.getRuntime().exec("su");
//            os = new DataOutputStream(process.getOutputStream());
//
//            // 用 test -d 判断目录是否存在，存在返回 0，不存在返回非 0
//            os.writeBytes("test -d /data/data/" + targetApp + "\n");
//            os.writeBytes("exit $?\n");
//            os.flush();
//
//            int exitValue = process.waitFor();
//            return exitValue == 0;
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            return false;
//        } finally {
//            try {
//                if (os != null) os.close();
//                if (process != null) process.destroy();
//            } catch (Exception ignored) {}
//        }
//    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());


        // 封装了一个类用于权限检查和请求
        StoragePermissionManager.checkAndRequestPermission(this);




        binding.button.setOnClickListener(v -> {

            String targetApp = binding.editTextTextPersonName.getText().toString().trim();
            String whiteList = binding.editTextTextPersonName2.getText().toString().trim();
            String blackList = binding.editTextTextPersonName3.getText().toString().trim();

            boolean hook = binding.switch2.isChecked();
            boolean debugger = binding.switch1.isChecked();
            boolean innerclassesFilter =binding.switch3.isChecked();
            boolean invokeConstructors = binding.invoke.isChecked();
            String content =
                    "targetApp=" + targetApp + "\n" +
                            "hook=" + hook + "\n" +
                            "invokeDebugger=" + debugger + "\n" +
                            "whiteList=" + whiteList + "\n" +
                            "blackList=" + blackList + "\n" +
                            "innerclassesFilter=" + innerclassesFilter + "\n" +
                            "invokeConstructors=" + invokeConstructors + "\n";

            // 没招了，高版本Android的读写权限太严了，只能用root了
            // 全部改用了零宽漏洞绕过权限，至少目前我的安卓16还存在这个漏洞
            writeConfig(content, targetApp);
        });
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == StoragePermissionManager.REQUEST_CODE_ALL_FILES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "所有文件访问权限已授予", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "未授予权限，无法读写文件", Toast.LENGTH_SHORT).show();
                }
            }
        } else if (requestCode == StoragePermissionManager.REQUEST_CODE_DOCUMENT_TREE) {
            if (data != null && data.getData() != null) {
                Uri uri = data.getData();
                // 授权成功，保存持久化权限
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                Toast.makeText(this, "目录授权成功", Toast.LENGTH_SHORT).show();
            }
        }
    }


}


