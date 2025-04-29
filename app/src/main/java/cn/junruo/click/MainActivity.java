package cn.junruo.click;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

public class MainActivity extends AppCompatActivity {

    private static final String TARGET_VIEW_ID = "net.kaaass.zerotierfix:id/network_start_network_switch";
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 移除按钮相关代码，直接延迟1秒执行点击
       handler.postDelayed(this::autoClick, 1000); // 1秒后触发
    }

    // 自动点击逻辑
    private void autoClick() {
        if (hasRootPermission()) {
            clickViewByRoot(TARGET_VIEW_ID);
        } else {
            Toast.makeText(this, "需要Root权限", Toast.LENGTH_SHORT).show();
        }
    }

    // 检查Root权限（原有方法不变）
    private boolean hasRootPermission() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("exit\n");
            os.flush();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    // 通过Root点击控件（原有方法不变）
    private void clickViewByRoot(String viewId) {
        new Thread(() -> {
            try {
                // 1. 启动目标Activity
                Runtime.getRuntime().exec("su -c am start -n net.kaaass.zerotierfix/.ui.NetworkListActivity");
                Thread.sleep(2000);

                // 2. 点击坐标
                int targetX = 412, targetY = 291;

                // 3. 执行点击
                Runtime.getRuntime().exec("su -c input tap " + targetX + " " + targetY).waitFor();

                Log.d("Click", "点击坐标: (" + targetX + ", " + targetY + ")");
                Toast.makeText(this, "需要Root权限", Toast.LENGTH_SHORT).show();
                runOnUiThread(() -> Toast.makeText(this, "自动点击完成", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null); // 防止内存泄漏
    }

}
