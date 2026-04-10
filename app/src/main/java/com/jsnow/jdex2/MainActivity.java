package com.jsnow.jdex2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import com.jsnow.jdex2.databinding.ActivityMainBinding;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends AppCompatActivity  {

    private ActivityMainBinding binding;



    private void writeConfig(String content, String targetApp) {
        // 由于不是每个app都会自动创建/storage/emulated/0/Android/data/<package>，所以不判断
//        if (targetApp.isEmpty() || !isTargetAppInstalled(targetApp)) {
//            Toast.makeText(this,
//                    "目标应用不存在：" + targetApp + "\n请确认包名是否正确且已安装",
//                    Toast.LENGTH_LONG).show();
//            return;
//        }

        try {

            // 存储位置放在/storage/emulated/0/Android/data/<package>/files，用零宽漏洞绕过权限
            String targetDir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/\u200bdata/"+targetApp+"/files/";
            File targetDirFile = new File(targetDir);
            // 创建多级文件夹
            targetDirFile.mkdirs();
            // 配置文件
            File targetFile = new File(targetDir+"config.properties");

            FileOutputStream fos = new FileOutputStream(targetFile, false);
            fos.write(content.getBytes());
            fos.flush();
            fos.close();


            Toast.makeText(this, "写入成功：" + targetFile.getAbsolutePath(), Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            e.printStackTrace();
            Log.d("JDEX2",e.getMessage().toString());
            Toast.makeText(this, "Write config failed：" + e.getMessage(), Toast.LENGTH_LONG).show();
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


    /**
     * 处理从设置页面返回的结果
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == StoragePermissionManager.REQUEST_CODE_ALL_FILES) {
            // 用户从“所有文件访问权限”页面回来了
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "未授予权限，无法读写文件", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }


}


