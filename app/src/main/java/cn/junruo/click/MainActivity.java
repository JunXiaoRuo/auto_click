package cn.junruo.click;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SELECT_APP = 1;
    private static final String PREFS_NAME = "ClickConfig";
    private static final String KEY_APP_ACTIVITY = "app_activity";
    private static final String KEY_TARGET_X = "target_x";
    private static final String KEY_TARGET_Y = "target_y";
    private static final String KEY_AUTO_CLICK = "auto_click";

    private EditText etAppActivity;
    private EditText etTargetX;
    private EditText etTargetY;
    private Switch swAutoClick;
    private Button btnSave;
    private Button btnSelectApp;

    private final Handler handler = new Handler();
    private SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化视图
        initViews();

        // 初始化SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 加载保存的配置
        loadConfig();

        // 设置开关监听
        swAutoClick.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                // 保存开关状态
                sharedPreferences.edit().putBoolean(KEY_AUTO_CLICK, isChecked).apply();

                if (isChecked) {
                    // 检查配置是否完整
                    if (isConfigValid()) {
                        startAutoClick();
                    } else {
                        swAutoClick.setChecked(false);
                        Toast.makeText(MainActivity.this, "请先配置应用和坐标", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        // 设置选择应用按钮点击事件
        btnSelectApp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectApp();
            }
        });

        // 设置保存按钮点击事件
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfig();
            }
        });

        // 检查开关状态，如果开启则执行自动点击
        if (sharedPreferences.getBoolean(KEY_AUTO_CLICK, false)) {
            swAutoClick.setChecked(true);
            if (isConfigValid()) {
                startAutoClick();
            }
        }
    }

    private void initViews() {
        etAppActivity = findViewById(R.id.et_app_activity);
        etTargetX = findViewById(R.id.et_target_x);
        etTargetY = findViewById(R.id.et_target_y);
        swAutoClick = findViewById(R.id.sw_auto_click);
        btnSave = findViewById(R.id.btn_save);
        btnSelectApp = findViewById(R.id.btn_select_app);
    }

    private void loadConfig() {
        // 优先显示应用名称，如果没有则显示Activity路径
        String savedName = sharedPreferences.getString("app_name", "");
        String savedActivity = sharedPreferences.getString(KEY_APP_ACTIVITY, "");
        etAppActivity.setText(!TextUtils.isEmpty(savedName) ? savedName : savedActivity);

        etTargetX.setText(String.valueOf(sharedPreferences.getInt(KEY_TARGET_X, 0)));
        etTargetY.setText(String.valueOf(sharedPreferences.getInt(KEY_TARGET_Y, 0)));
        swAutoClick.setChecked(sharedPreferences.getBoolean(KEY_AUTO_CLICK, false));
    }

    private void saveConfig() {
        try {
            // 注意：这里获取的是显示的名称，但实际使用的是之前保存的Activity路径
            String displayedName = etAppActivity.getText().toString().trim();
            int targetX = Integer.parseInt(etTargetX.getText().toString());
            int targetY = Integer.parseInt(etTargetY.getText().toString());

            if (TextUtils.isEmpty(displayedName)) {
                Toast.makeText(this, "请选择应用", Toast.LENGTH_SHORT).show();
                return;
            }

            // 只更新坐标（Activity路径已在选择时保存）
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt(KEY_TARGET_X, targetX);
            editor.putInt(KEY_TARGET_Y, targetY);
            editor.apply();

            Toast.makeText(this, "坐标已保存", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有效的坐标", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isConfigValid() {
        String appActivity = sharedPreferences.getString(KEY_APP_ACTIVITY, "");
        int targetX = sharedPreferences.getInt(KEY_TARGET_X, 0);
        int targetY = sharedPreferences.getInt(KEY_TARGET_Y, 0);

        return !TextUtils.isEmpty(appActivity) && targetX > 0 && targetY > 0;
    }

    private void selectApp() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);

        if (apps.isEmpty()) {
            Toast.makeText(this, "未找到可启动的应用", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择应用");

        List<String> appNames = new ArrayList<>();
        final List<String> appActivities = new ArrayList<>();

        for (ResolveInfo info : apps) {
            appNames.add(info.loadLabel(pm).toString());
            appActivities.add(info.activityInfo.packageName + "/" + info.activityInfo.name);
        }

        builder.setItems(appNames.toArray(new String[0]), (dialog, which) -> {
            // 显示应用名称（用户友好）
            etAppActivity.setText(appNames.get(which));

            // 保存Activity路径（实际使用）
            sharedPreferences.edit()
                    .putString(KEY_APP_ACTIVITY, appActivities.get(which))
                    .putString("app_name", appNames.get(which))
                    .apply();
        });

        builder.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_SELECT_APP && resultCode == Activity.RESULT_OK && data != null) {
            String packageName = data.getComponent().getPackageName();
            String className = data.getComponent().getClassName();
            String fullActivity = packageName + "/" + className;

            etAppActivity.setText(fullActivity);
        }
    }

    private void startAutoClick() {
        handler.postDelayed(this::performAutoClick, 1000); // 延迟1秒执行
    }

    private void performAutoClick() {
        if (!swAutoClick.isChecked()) {
            return;
        }

        if (hasRootPermission()) {
            clickByRoot();
        } else {
            Toast.makeText(this, "需要Root权限", Toast.LENGTH_SHORT).show();
        }
    }

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

    private void clickByRoot() {
        new Thread(() -> {
            try {
                String appActivity = sharedPreferences.getString(KEY_APP_ACTIVITY, "");
                int targetX = sharedPreferences.getInt(KEY_TARGET_X, 0);
                int targetY = sharedPreferences.getInt(KEY_TARGET_Y, 0);

                // 1. 启动目标Activity
                Runtime.getRuntime().exec("su -c am start -n " + appActivity);
                Thread.sleep(2000);

                // 2. 执行点击
                Runtime.getRuntime().exec("su -c input tap " + targetX + " " + targetY).waitFor();

                Log.d("Click", "点击坐标: (" + targetX + ", " + targetY + ")");
                runOnUiThread(() -> Toast.makeText(this, "自动点击完成", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }



    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}