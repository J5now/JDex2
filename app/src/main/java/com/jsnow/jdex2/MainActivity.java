package com.jsnow.jdex2;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import com.jsnow.jdex2.databinding.ActivityMainBinding;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private void writeConfigWithRoot(String content, String targetApp) {
        if (targetApp.isEmpty() || !isTargetAppInstalled(targetApp)) {
            Toast.makeText(this,
                    "目标应用不存在：" + targetApp + "\n请确认包名是否正确且已安装",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Process process = null;
        DataOutputStream os = null;
        try {
            File tempFile = new File(getFilesDir(), "config.properties");
            FileOutputStream fos = new FileOutputStream(tempFile, false);
            fos.write(content.getBytes());
            fos.flush();
            fos.close();

            String targetDir = "/data/data/" + targetApp + "/files/";
            String targetPath = targetDir + "config.properties";

            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());

            os.writeBytes("mkdir -p " + targetDir + "\n");

            os.writeBytes("cp " + tempFile.getAbsolutePath() + " " + targetPath + "\n");

            os.writeBytes("chmod 666 " + targetPath + "\n");
            os.writeBytes("chmod 771 " + targetDir + "\n");

            os.writeBytes("chown $(stat -c '%U:%G' " + targetDir + ") " + targetPath + "\n");


            os.writeBytes("exit\n");
            os.flush();

            int exitValue = process.waitFor();

            if (exitValue == 0) {
                Toast.makeText(this, "写入成功：" + targetPath, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "写入失败，错误码: " + exitValue, Toast.LENGTH_LONG).show();
            }

            tempFile.delete();

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Write config failed：" + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            try {
                if (os != null) os.close();
                if (process != null) process.destroy();
            } catch (Exception ignored) {}
        }
    }
    private boolean isTargetAppInstalled(String targetApp) {
        Process process = null;
        DataOutputStream os = null;
        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());

            // 用 test -d 判断目录是否存在，存在返回 0，不存在返回非 0
            os.writeBytes("test -d /data/data/" + targetApp + "\n");
            os.writeBytes("exit $?\n");
            os.flush();

            int exitValue = process.waitFor();
            return exitValue == 0;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            try {
                if (os != null) os.close();
                if (process != null) process.destroy();
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 动态申请读写权限
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                },
                1
        );


        binding.button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

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
                writeConfigWithRoot(content, targetApp);
            }
        });
    }
}
