package cn.junruo.click;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "ClickConfig";
    private static final String KEY_APP_ACTIVITY = "app_activity";
    private static final String KEY_APP_NAME = "app_name";
    private static final String KEY_OPERATIONS = "operations";
    private static final String KEY_AUTO_CLICK = "auto_click";

    private EditText etAppActivity;
    private Switch swAutoClick;
    private Button btnSelectApp;
    private Button btnAddOperation;
    private ListView lvOperations;

    private final Handler handler = new Handler();
    private SharedPreferences sharedPreferences;
    private ArrayList<Operation> operations = new ArrayList<>();
    private OperationAdapter operationAdapter;

    // 添加以下常量定义
    private static final String KEY_CLICK_DURATION = "click_duration";
    private static final String KEY_SWIPE_DURATION = "swipe_duration";
    private static final int DEFAULT_CLICK_DURATION = 50; // 默认点击持续时间(ms)
    private static final int DEFAULT_SWIPE_DURATION = 100; // 默认滑动持续时间(ms)
    private Button btnSettings;
    private TextView tvRootStatus;
    private boolean hasRootPermission = false;
    private static final String KEY_STOP_APPS = "stop_apps";
    private static final String KEY_STOP_APPS_ENABLED = "stop_apps_enabled";
    private Switch swStopAppsEnabled;
    private Button btnSelectAppsToStop;
    private List<String> selectedAppsToStop = new ArrayList<>();
    // 在类变量区域添加
    private static final String KEY_SELF_KILL = "self_kill";
    private Switch swSelfKill;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 初始化视图
        initViews();

        // 检查ROOT权限
        checkRootPermission();

        loadConfig();

        // 设置开关监听器
        swAutoClick.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_AUTO_CLICK, isChecked).apply();
            if (isChecked && isConfigValid()) {
                showAutoStartCountdown(); // 改为显示倒计时对话框
            } else if (isChecked) {
                swAutoClick.setChecked(false);
                Toast.makeText(this, "请先配置应用和操作步骤", Toast.LENGTH_SHORT).show();
            }
        });

        btnSelectApp.setOnClickListener(v -> selectApp());
        btnAddOperation.setOnClickListener(v -> showOperationDialog(null));

        // 应用启动时的自动执行检查（修复：临时禁用监听器）
        if (sharedPreferences.getBoolean(KEY_AUTO_CLICK, false)) {
            // 临时禁用监听器，避免触发两次
            swAutoClick.setOnCheckedChangeListener(null);
            swAutoClick.setChecked(true);
            swAutoClick.setOnCheckedChangeListener((buttonView, isChecked) -> {
                sharedPreferences.edit().putBoolean(KEY_AUTO_CLICK, isChecked).apply();
                if (isChecked && isConfigValid()) {
                    showAutoStartCountdown();
                } else if (isChecked) {
                    swAutoClick.setChecked(false);
                    Toast.makeText(this, "请先配置应用和操作步骤", Toast.LENGTH_SHORT).show();
                }
            });

            if (isConfigValid()) {
                showAutoStartCountdown(); // 改为显示倒计时对话框
            } else {
                swAutoClick.setChecked(false);
                sharedPreferences.edit().putBoolean(KEY_AUTO_CLICK, false).apply();
                Toast.makeText(this, "请先配置应用和操作步骤", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void initViews() {
        // 确保 sharedPreferences 已初始化
        if (sharedPreferences == null) {
            sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        }

        etAppActivity = findViewById(R.id.et_app_activity);
        swAutoClick = findViewById(R.id.sw_auto_click);
        btnSelectApp = findViewById(R.id.btn_select_app);
        btnAddOperation = findViewById(R.id.btn_add_operation);
        lvOperations = findViewById(R.id.lv_operations);
        operationAdapter = new OperationAdapter(this, operations);
        lvOperations.setAdapter(operationAdapter);

        btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        tvRootStatus = findViewById(R.id.tv_root_status);
        tvRootStatus.setOnClickListener(v -> {
            if (!hasRootPermission) {
                requestRootPermission();
            }
        });

        // 在执行完成操作区域添加控件
        LinearLayout layoutCompletionActions = findViewById(R.id.layout_completion_actions);
        swStopAppsEnabled = layoutCompletionActions.findViewById(R.id.sw_stop_apps_enabled);
        btnSelectAppsToStop = layoutCompletionActions.findViewById(R.id.btn_select_apps_to_stop);

        // 加载保存的状态
        swStopAppsEnabled.setChecked(sharedPreferences.getBoolean(KEY_STOP_APPS_ENABLED, false));
        String savedApps = sharedPreferences.getString(KEY_STOP_APPS, "");
        if (!savedApps.isEmpty()) {
            selectedAppsToStop = new ArrayList<>(Arrays.asList(savedApps.split(",")));
        }

        // 设置监听器
        swStopAppsEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_STOP_APPS_ENABLED, isChecked).apply();
            btnSelectAppsToStop.setEnabled(isChecked);
        });

        btnSelectAppsToStop.setOnClickListener(v -> showAppSelectionDialog());

        // 初始状态
        btnSelectAppsToStop.setEnabled(swStopAppsEnabled.isChecked());

        // 在执行完成操作区域添加控件
        swSelfKill = layoutCompletionActions.findViewById(R.id.sw_self_kill);

        // 加载自杀开关状态
        swSelfKill.setChecked(sharedPreferences.getBoolean(KEY_SELF_KILL, false));

        // 设置监听器
        swSelfKill.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_SELF_KILL, isChecked).apply();
        });
    }

    private void showAutoStartCountdown() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_countdown, null);
        TextView tvCountdown = dialogView.findViewById(R.id.tv_countdown);
        Button btnStop = dialogView.findViewById(R.id.btn_stop);

        builder.setView(dialogView);
        builder.setCancelable(false);
        AlertDialog dialog = builder.create();

        final int[] countdown = {3}; // 倒计时3秒
        final Handler countdownHandler = new Handler();

        Runnable countdownRunnable = new Runnable() {
            @Override
            public void run() {
                tvCountdown.setText("自动执行，倒计时" + countdown[0] + "秒，点停止则不执行");
                countdown[0]--;

                if (countdown[0] >= 0) {
                    countdownHandler.postDelayed(this, 1000);
                } else {
                    dialog.dismiss();
                    startAutoClick();
                }
            }
        };

        btnStop.setOnClickListener(v -> {
            countdownHandler.removeCallbacks(countdownRunnable);
            swAutoClick.setChecked(false);
            // 关键修复：更新 SharedPreferences，防止下次启动再次触发
            sharedPreferences.edit().putBoolean(KEY_AUTO_CLICK, false).apply();
            dialog.dismiss();
        });

        dialog.show();
        countdownHandler.post(countdownRunnable);
    }

    // 添加显示应用选择对话框的方法
    private void showAppSelectionDialog() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
        String currentPackage = getPackageName(); // 获取本应用包名

        if (apps.isEmpty()) {
            Toast.makeText(this, "未找到可启动的应用", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> appNames = new ArrayList<>();
        final List<String> appPackages = new ArrayList<>();
        final boolean[] checkedItems = new boolean[apps.size()];

        for (int i = 0; i < apps.size(); i++) {
            ResolveInfo info = apps.get(i);
            // 排除本应用
            if (!info.activityInfo.packageName.equals(currentPackage)) {
                appNames.add(info.loadLabel(pm).toString());
                appPackages.add(info.activityInfo.packageName);
                // 检查是否已选中
                checkedItems[i] = selectedAppsToStop.contains(info.activityInfo.packageName);
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("选择要停止的应用")
                .setMultiChoiceItems(appNames.toArray(new String[0]), checkedItems,
                        (dialog, which, isChecked) -> checkedItems[which] = isChecked)
                .setPositiveButton("确定", (dialog, which) -> {
                    selectedAppsToStop.clear();
                    for (int i = 0; i < checkedItems.length; i++) {
                        if (checkedItems[i]) {
                            selectedAppsToStop.add(appPackages.get(i));
                        }
                    }
                    // 保存选择
                    sharedPreferences.edit()
                            .putString(KEY_STOP_APPS, TextUtils.join(",", selectedAppsToStop))
                            .apply();
                })
                .setNegativeButton("取消", null);

        builder.show();
    }

    // 修改停止应用的方法
    private void stopSelectedApps() {
        if (selectedAppsToStop.isEmpty()) {
            Toast.makeText(this, "未选择要停止的应用", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            for (String packageName : selectedAppsToStop) {
                Runtime.getRuntime().exec("su -c am force-stop " + packageName);
            }
            Toast.makeText(this, "已停止" + selectedAppsToStop.size() + "个应用", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "停止应用失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void checkRootPermission() {
        new Thread(() -> {
            hasRootPermission = hasRootPermission();
            runOnUiThread(() -> updateRootStatusUI());
        }).start();
    }

    private void updateRootStatusUI() {
        if (hasRootPermission) {
            tvRootStatus.setText("ROOT已获取");
            tvRootStatus.setBackgroundColor(Color.parseColor("#FF4CAF50")); // 绿色
        } else {
            tvRootStatus.setText("ROOT未获取（点我获取）");
            tvRootStatus.setBackgroundColor(Color.parseColor("#FFF44336")); // 红色
        }
    }

    private void requestRootPermission() {
        new AlertDialog.Builder(this)
                .setTitle("申请ROOT权限")
                .setMessage("此功能需要ROOT权限才能正常工作，是否现在申请？")
                .setPositiveButton("申请", (dialog, which) -> {
                    checkRootPermission();
                    Toast.makeText(this, "正在尝试获取ROOT权限...（部分su软件可能需要打开应用给与权限）", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    // 添加设置对话框方法
    private void showSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);

        EditText etClickDuration = dialogView.findViewById(R.id.et_click_duration);
        EditText etSwipeDuration = dialogView.findViewById(R.id.et_swipe_duration);

        // 加载当前设置
        etClickDuration.setText(String.valueOf(
                sharedPreferences.getInt(KEY_CLICK_DURATION, DEFAULT_CLICK_DURATION)));
        etSwipeDuration.setText(String.valueOf(
                sharedPreferences.getInt(KEY_SWIPE_DURATION, DEFAULT_SWIPE_DURATION)));

        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setTitle("触控速度设置")
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        int clickDuration = Integer.parseInt(etClickDuration.getText().toString());
                        int swipeDuration = Integer.parseInt(etSwipeDuration.getText().toString());

                        sharedPreferences.edit()
                                .putInt(KEY_CLICK_DURATION, clickDuration)
                                .putInt(KEY_SWIPE_DURATION, swipeDuration)
                                .apply();

                        Toast.makeText(this, "速度设置已保存", Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入有效的数值", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }


    private void loadConfig() {
        // 加载应用名称
        etAppActivity.setText(sharedPreferences.getString(KEY_APP_NAME, ""));

        String operationsJson = sharedPreferences.getString(KEY_OPERATIONS, "");
        if (!TextUtils.isEmpty(operationsJson)) {
            operations = Operation.fromJsonArray(operationsJson);
            operationAdapter.clear();  // 清空现有数据
            operationAdapter.addAll(operations);  // 添加新的操作步骤
            operationAdapter.notifyDataSetChanged();  // 刷新适配器
        } else {
            operations.clear();
            operationAdapter.notifyDataSetChanged();  // 刷新适配器
        }
    }



    private void saveConfig() {
        String operationsJson = Operation.toJsonArray(operations);
        sharedPreferences.edit()
                .putString(KEY_OPERATIONS, operationsJson)
                .apply();
        Log.d("MainActivity", "保存操作步骤: " + operationsJson);
        loadConfig();
        Toast.makeText(this, "操作步骤已保存", Toast.LENGTH_SHORT).show();
    }


    private boolean isConfigValid() {
        return !TextUtils.isEmpty(sharedPreferences.getString(KEY_APP_ACTIVITY, ""))
                && !operations.isEmpty();
    }

    //TODO 选择app
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
            etAppActivity.setText(appNames.get(which));
            sharedPreferences.edit()
                    .putString(KEY_APP_ACTIVITY, appActivities.get(which))
                    .putString(KEY_APP_NAME, appNames.get(which))
                    .apply();
        });

        builder.show();
    }

    public void showOperationDialog(Operation operation) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_operation, null);

        // 初始化视图
        Spinner spType = dialogView.findViewById(R.id.sp_type);
        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) TextView tvStartPoint = dialogView.findViewById(R.id.tv_start_point);
        LinearLayout layoutEndPoint = dialogView.findViewById(R.id.layout_end_point);
        LinearLayout layoutXy2 = dialogView.findViewById(R.id.layout_xy2);
        EditText etDelay = dialogView.findViewById(R.id.et_delay);
        EditText etX1 = dialogView.findViewById(R.id.et_x1);
        EditText etY1 = dialogView.findViewById(R.id.et_y1);
        EditText etX2 = dialogView.findViewById(R.id.et_x2);
        EditText etY2 = dialogView.findViewById(R.id.et_y2);

        // 设置类型选择监听
        spType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean isClick = position == 0;
                tvStartPoint.setText(isClick ? "点击坐标:" : "起始坐标:");
                layoutEndPoint.setVisibility(isClick ? View.GONE : View.VISIBLE);
                layoutXy2.setVisibility(isClick ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 如果编辑已有操作，填充数据
        if (operation != null) {
            spType.setSelection(operation.type == Operation.TYPE_CLICK ? 0 : 1);
            etDelay.setText(String.valueOf(operation.delay));
            etX1.setText(String.valueOf(operation.x1));
            etY1.setText(String.valueOf(operation.y1));
            if (operation.type == Operation.TYPE_SWIPE) {
                etX2.setText(String.valueOf(operation.x2));
                etY2.setText(String.valueOf(operation.y2));
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setTitle(operation == null ? "添加操作" : "编辑操作")
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        int type = spType.getSelectedItemPosition() == 0 ?
                                Operation.TYPE_CLICK : Operation.TYPE_SWIPE;
                        int delay = Integer.parseInt(etDelay.getText().toString());
                        int x1 = Integer.parseInt(etX1.getText().toString());
                        int y1 = Integer.parseInt(etY1.getText().toString());
                        int x2 = type == Operation.TYPE_SWIPE ?
                                Integer.parseInt(etX2.getText().toString()) : 0;
                        int y2 = type == Operation.TYPE_SWIPE ?
                                Integer.parseInt(etY2.getText().toString()) : 0;

                        Operation op = new Operation(type, delay, x1, y1, x2, y2);
                        if (operation == null) {
                            operations.add(op);
                        } else {
                            operations.set(operations.indexOf(operation), op);
                        }
                        operationAdapter.notifyDataSetChanged();
                        saveConfig();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入有效的数值", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null);

        if (operation != null) {
            builder.setNeutralButton("删除", (dialog, which) -> {
                operations.remove(operation);
                operationAdapter.notifyDataSetChanged();
                saveConfig();
            });
        }

        AlertDialog dialog = builder.create();
        dialog.show();

        // 初始UI状态
        boolean isClick = spType.getSelectedItemPosition() == 0;
        tvStartPoint.setText(isClick ? "点击坐标:" : "起始坐标:");
        layoutEndPoint.setVisibility(isClick ? View.GONE : View.VISIBLE);
        layoutXy2.setVisibility(isClick ? View.GONE : View.VISIBLE);
    }

    private void startAutoClick() {
        handler.postDelayed(this::performOperations, 1000);
    }

    // 修改performOperations方法，使用设置的速度
    private void performOperations() {
        if (!swAutoClick.isChecked() || !hasRootPermission()) {
            return;
        }

        new Thread(() -> {
            try {
                int clickDuration = sharedPreferences.getInt(KEY_CLICK_DURATION, DEFAULT_CLICK_DURATION);
                int swipeDuration = sharedPreferences.getInt(KEY_SWIPE_DURATION, DEFAULT_SWIPE_DURATION);

                String appActivity = sharedPreferences.getString(KEY_APP_ACTIVITY, "");
                Runtime.getRuntime().exec("su -c am start -n " + appActivity);

                for (Operation op : operations) {
                    Thread.sleep(op.delay);
                    if (op.type == Operation.TYPE_CLICK) {
                        // 使用设置的点击持续时间
                        Runtime.getRuntime().exec(
                                "su -c input tap " + op.x1 + " " + op.y1).waitFor();
                        // 如果需要更精确控制点击时长，可以使用:
                        // "su -c input swipe " + op.x1 + " " + op.y1 + " " +
                        // op.x1 + " " + op.y1 + " " + clickDuration
                    } else {
                        // 使用设置的滑动持续时间
                        Runtime.getRuntime().exec(
                                "su -c input swipe " + op.x1 + " " + op.y1 + " " +
                                        op.x2 + " " + op.y2 + " " + swipeDuration).waitFor();
                    }
                }

                runOnUiThread(() -> {
                    Toast.makeText(this, "操作流程执行完成", Toast.LENGTH_SHORT).show();

                    // 执行完成后的操作
                    if (swStopAppsEnabled.isChecked()) {
                        stopSelectedApps();
                    }

                    // 最后执行自杀
                    if (swSelfKill.isChecked()) {
                        selfKill();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
    // 添加自杀方法
    private void selfKill() {
        try {
            // 先停止自己
            Runtime.getRuntime().exec("su -c am force-stop " + getPackageName());
            // 再杀死进程
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        } catch (Exception e) {
            Toast.makeText(this, "自杀失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasRootPermission() {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("exit\n");
            os.flush();
            boolean hasRoot = process.waitFor() == 0;
            if (!hasRoot) {
                runOnUiThread(() ->
                        Toast.makeText(this, "需要Root权限", Toast.LENGTH_SHORT).show());
            }
            return hasRoot;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}