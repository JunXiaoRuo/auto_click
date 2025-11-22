package cn.junruo.click;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.app.NotificationManager;
import android.Manifest;
import androidx.core.app.ActivityCompat;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.LayoutInflater;
import android.view.View;
import android.content.ComponentName;
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
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "ClickConfig";// SharedPreferences 文件名
    private static final String KEY_APP_ACTIVITY = "app_activity";
    private static final String KEY_APP_NAME = "app_name";
    private static final String KEY_OPERATIONS = "operations";
    private static final String KEY_AUTO_CLICK = "auto_click";
    private static final String KEY_SCHEMES = "schemes";
    private static final String KEY_CURRENT_SCHEME = "current_scheme";
    private static final String KEY_CLICK_DURATION = "click_duration";
    private static final String KEY_LONG_PRESS_DURATION = "long_press_duration";
    private static final String KEY_SWIPE_DURATION = "swipe_duration";
    private static final String KEY_FALLBACK_NO_XY = "fallback_no_xy";
    private static final String KEY_STOP_APPS = "stop_apps";
    private static final String KEY_STOP_APPS_ENABLED = "stop_apps_enabled";
    private static final int DEFAULT_CLICK_DURATION = 50;// 默认点击持续时间
    private static final int DEFAULT_LONG_PRESS_DURATION = 500;// 默认长按持续时间
    private static final int DEFAULT_SWIPE_DURATION = 100;// 默认滑动持续时间
    private static final String KEY_TOUCH_DEVICE = "touch_device";
    private static final String KEY_MAX_X = "max_x";
    private static final String KEY_MAX_Y = "max_y";
    private static final String KEY_MOVE_TOLERANCE_PX = "move_tolerance_px";
    private static final String KEY_MOTION_THRESHOLD = "motion_threshold";
    private static final String KEY_SHOW_DEBUG = "show_debug";
    private static final String KEY_FIRST_ACTION_DELAY = "first_action_delay";
    private static final String KEY_KEEP_ALIVE = "keep_alive";
    private static final String KEY_VOLUME_KEYS = "volume_keys_control";
    private static final String KEY_EXEC_LOOP_COUNT = "exec_loop_count";
    private static final String KEY_EXEC_LOOP_FOREVER = "exec_loop_forever";
    private static final String KEY_EXEC_LOOP_INTERVAL_MS = "exec_loop_interval_ms";
    private static final String KEY_EXEC_LOOP_NOTIFY = "exec_loop_notify";
    private static final String KEY_PERMISSIONS_DIALOG_SHOWN = "permissions_dialog_shown";
    private static final String KEY_ROOT_GRANTED = "root_granted";
    public static final String ACTION_RECORDING_COMPLETE = "cn.junruo.click.RECORDING_COMPLETE";

    // UI 组件
    private EditText etAppActivity;// 显示/输入目标应用名
    private Switch swAutoClick;// 是否启用自动执行
    private Button btnExecute;
    private Button btnSelectApp;
    private Button btnClearApp;
    private Button btnAddOperation;
    private ListView lvOperations;// 操作步骤列表
    private Button btnSettings;
    private TextView tvRootStatus;
    private Switch swStopAppsEnabled;
    private Button btnSelectAppsToStop;
    private Spinner spSchemeSelector;
    private Button btnNewScheme;
    private Button btnSaveScheme;
    private Button btnDeleteScheme;
    private Button btnStartRecording;

    private final Handler handler = new Handler();
    private SharedPreferences sharedPreferences;
    private ArrayList<Operation> operations = new ArrayList<>();// 当前方案的操作步骤
    private OperationAdapter operationAdapter;
    private ArrayList<Scheme> schemes = new ArrayList<>();
    private Scheme currentScheme;// 当前选中的方案
    private boolean hasRootPermission = false;// 是否拥有ROOT权限
    private List<String> selectedAppsToStop = new ArrayList<>();
    private BroadcastReceiver recordingReceiver;
    private boolean pendingStartKeepAlive = false;
    private static final String TAG = "MainActivity";
    private AlertDialog permissionsDialog;
    private boolean execOverlayActive = false;

    // 修改onCreate方法中的初始化顺序
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 设置状态栏样式
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().setStatusBarColor(getResources().getColor(R.color.white));
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        // 初始化SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        try { sharedPreferences.edit().putBoolean("exec_overlay_active", false).apply(); } catch (Exception ignored) {}

        // 初始化UI、权限与数据
        initViews();// 初始化控件与事件


        hasRootPermission = sharedPreferences.getBoolean(KEY_ROOT_GRANTED, false);
        updateRootStatusUI();


        loadSchemes();// 加载已有的所有方案


        setupSchemeSelector();// 设置方案选择器事件


        

        registerRecordingReceiver();

        if (sharedPreferences.getBoolean(KEY_KEEP_ALIVE, false)) {
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingStartKeepAlive = true;
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
                android.util.Log.i(TAG, "Auto-start keep-alive requires POST_NOTIFICATIONS; requesting");
            } else {
                startKeepAliveServiceSafely();
            }
        }

        boolean shown = sharedPreferences.getBoolean(KEY_PERMISSIONS_DIALOG_SHOWN, false);
        if (!shown) {
            showPermissionsDialog();
            sharedPreferences.edit().putBoolean(KEY_PERMISSIONS_DIALOG_SHOWN, true).apply();
        }

        boolean autoClickEnabled = sharedPreferences.getBoolean(KEY_AUTO_CLICK, false);
        if (autoClickEnabled) {
            showAutoStartCountdown();
        }
    }

    private void initViews() {
        // 基本视图初始化
        etAppActivity = findViewById(R.id.et_app_activity);
        btnExecute = findViewById(R.id.btn_execute);
        btnSelectApp = findViewById(R.id.btn_select_app);
        btnClearApp = findViewById(R.id.btn_clear_app);
        btnAddOperation = findViewById(R.id.btn_add_operation);
        btnStartRecording = findViewById(R.id.btn_start_recording);
        lvOperations = findViewById(R.id.lv_operations);
        btnSettings = findViewById(R.id.btn_settings);
        tvRootStatus = findViewById(R.id.tv_root_status);
        Button btnPermissions = findViewById(R.id.btn_permissions);

        // 设置适配器
        operationAdapter = new OperationAdapter(this, operations);
        lvOperations.setAdapter(operationAdapter);

        // 方案管理视图初始化
        spSchemeSelector = findViewById(R.id.sp_scheme_selector);
        btnNewScheme = findViewById(R.id.btn_new_scheme);
        btnSaveScheme = findViewById(R.id.btn_save_scheme);
        btnDeleteScheme = findViewById(R.id.btn_delete_scheme);

        // 执行完成操作区域初始化
        LinearLayout layoutCompletionActions = findViewById(R.id.layout_completion_actions);
        if (layoutCompletionActions != null) {
            swStopAppsEnabled = layoutCompletionActions.findViewById(R.id.sw_stop_apps_enabled);
            btnSelectAppsToStop = layoutCompletionActions.findViewById(R.id.btn_select_apps_to_stop);
        }

        // 设置按钮点击监听器
        btnSettings.setOnClickListener(v -> showSettingsDialog());
        if (btnExecute != null) {
            btnExecute.setOnClickListener(v -> showExecuteDialog());
        }
        if (btnPermissions != null) {
            btnPermissions.setOnClickListener(v -> showPermissionsDialog());
        }

        btnSelectApp.setOnClickListener(v -> selectApp());// 选择目标App
        if (btnClearApp != null) {
            btnClearApp.setOnClickListener(v -> {
                String[] options = new String[]{"清除目标应用", "清除步骤", "全部清除"};
                new AlertDialog.Builder(this)
                        .setTitle("选择清除内容")
                        .setItems(options, (d, which) -> {
                            if (currentScheme == null) {
                                Scheme def = findSchemeByName("默认方案");
                                if (def == null) {
                                    def = new Scheme("默认方案");
                                    schemes.add(def);
                                }
                                currentScheme = def;
                                updateSchemeSpinner();
                            }
                            boolean clearApp = which == 0 || which == 2;
                            boolean clearOps = which == 1 || which == 2;
                            if (clearApp) {
                                currentScheme.appName = "";
                                currentScheme.appActivity = "";
                                sharedPreferences.edit()
                                        .putString(KEY_APP_ACTIVITY, "")
                                        .putString(KEY_APP_NAME, "")
                                        .apply();
                                etAppActivity.setText("未选择则不跳转");
                            }
                            if (clearOps) {
                                operations.clear();
                                operationAdapter.notifyDataSetChanged();
                                sharedPreferences.edit()
                                        .putString(KEY_OPERATIONS, Operation.toJsonArray(operations))
                                        .apply();
                                if (swAutoClick != null && swAutoClick.isChecked()) {
                                    swAutoClick.setChecked(false);
                                }
                            }
                            saveCurrentScheme();
                            String tip = which == 0 ? "已清除目标应用并保存" : which == 1 ? "已清除步骤并保存" : "已清除目标应用与步骤并保存";
                            Toast.makeText(this, tip, Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }

        tvRootStatus.setOnClickListener(v -> {
            if (!hasRootPermission) {
                requestRootPermission();
            }
        });

        // 设置点击事件
        btnAddOperation.setOnClickListener(v -> showOperationDialog(null));// 添加操作
        btnStartRecording.setOnClickListener(v -> toggleRecordingOverlay());

        // 方案操作按钮监听器
        btnNewScheme.setOnClickListener(v -> createNewScheme());
        btnSaveScheme.setOnClickListener(v -> saveCurrentSchemeWithToast());
        btnDeleteScheme.setOnClickListener(v -> deleteCurrentScheme());

        // 执行完成操作监听器
        if (swStopAppsEnabled != null) {
            swStopAppsEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (currentScheme != null) {
                    currentScheme.stopAppsEnabled = isChecked;
                    saveCurrentScheme();
                }
                if (btnSelectAppsToStop != null) {
                    btnSelectAppsToStop.setEnabled(isChecked);
                }
            });
        }

        if (btnSelectAppsToStop != null) {
            btnSelectAppsToStop.setOnClickListener(v -> showAppSelectionDialog());
        }

        // 操作列表点击监听
        lvOperations.setOnItemClickListener((parent, view, position, id) -> {
            Operation op = operations.get(position);
            showOperationDialog(op);
        });
    }

    private void saveCurrentSchemeWithToast() {
        if (currentScheme != null) {
            saveCurrentScheme();
            Toast.makeText(this, "方案 '" + currentScheme.name + "' 已保存", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "没有可保存的方案", Toast.LENGTH_SHORT).show();
        }
    }

    //自动点击开关监听逻辑
    private void setupAutoClickSwitchListener() {
        if (swAutoClick == null) return;

        swAutoClick.setOnCheckedChangeListener(null);// 移除旧监听

        // 创建监听器实例
        CompoundButton.OnCheckedChangeListener autoClickListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sharedPreferences.edit().putBoolean(KEY_AUTO_CLICK, isChecked).apply();
                if (isChecked) {
                    if (isConfigValid()) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                                boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
                                if (!ignoring) {
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("建议关闭电池优化")
                                            .setMessage("为保证跨应用执行流程稳定，请将本应用设为不受电池优化限制。小米设备请设置为‘无限制’。")
                                            .setPositiveButton("去设置", (d, w) -> {
                                                try {
                                                    if (android.os.Build.MANUFACTURER != null && android.os.Build.MANUFACTURER.toLowerCase().contains("xiaomi")) {
                                                        Intent miui = new Intent();
                                                        miui.setComponent(new ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
                                                        miui.putExtra("package_name", getPackageName());
                                                        miui.putExtra("package_label", getApplicationInfo().loadLabel(getPackageManager()).toString());
                                                        startActivity(miui);
                                                    } else {
                                                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                                        i.setData(Uri.parse("package:" + getPackageName()));
                                                        startActivity(i);
                                                    }
                                                } catch (Exception e1) {
                                                    try {
                                                        Intent i2 = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                                                        startActivity(i2);
                                                    } catch (Exception ignored) {}
                                                }
                                            })
                                            .setNegativeButton("暂不", null)
                                            .show();
                                }
                            }
                        } catch (Exception ignored) {}
                        showAutoStartCountdown();// 显示3秒倒计时
                    } else {
                        // 临时移除监听器避免递归
                        swAutoClick.setOnCheckedChangeListener(null);
                        swAutoClick.setChecked(false);
                        swAutoClick.setOnCheckedChangeListener(this);
                        Toast.makeText(MainActivity.this, "请先添加操作步骤", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        };

        // 设置监听器
        swAutoClick.setOnCheckedChangeListener(autoClickListener);

        // 处理启动时的自动执行状态
        boolean autoClickEnabled = sharedPreferences.getBoolean(KEY_AUTO_CLICK, false);
        swAutoClick.setChecked(autoClickEnabled);

        // 不需要在这里再次调用showAutoStartCountdown()，因为设置checked会触发监听器
        if (autoClickEnabled && !isConfigValid()) {
            // 配置无效时关闭自动执行
            sharedPreferences.edit().putBoolean(KEY_AUTO_CLICK, false).apply();
            swAutoClick.setChecked(false);
            Toast.makeText(this, "自动执行已关闭：请先添加操作步骤", Toast.LENGTH_SHORT).show();
        }
    }
        // 修改方案选择监听器
    private void setupSchemeSelector() {
        spSchemeSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 保存当前方案更改
                saveCurrentScheme();

                // 切换到新选择的方案
                currentScheme = schemes.get(position);
                loadCurrentScheme();

                
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    // 修改loadSchemes方法
    private void loadSchemes() {
        String schemesJson = sharedPreferences.getString(KEY_SCHEMES, "");
        if (!TextUtils.isEmpty(schemesJson)) {
            schemes = Scheme.fromJsonArray(schemesJson);
        } else {
            // 默认创建一个空方案
            Scheme defaultScheme = new Scheme("默认方案");
            schemes.add(defaultScheme);
        }

        // 加载上次选择的方案
        String lastSchemeName = sharedPreferences.getString(KEY_CURRENT_SCHEME, "");
        currentScheme = findSchemeByName(lastSchemeName);

        // 如果没有上次选择的方案或找不到，使用第一个方案
        if (currentScheme == null && !schemes.isEmpty()) {
            currentScheme = schemes.get(0);
        }

        // 修复点：确保selectedAppsToStop与当前方案同步
        selectedAppsToStop.clear();
        if (currentScheme != null && currentScheme.appsToStop != null) {
            selectedAppsToStop.addAll(currentScheme.appsToStop);
        } else {
            // 兼容旧版本：从单独的KEY_STOP_APPS加载
            String savedApps = sharedPreferences.getString(KEY_STOP_APPS, "");
            if (!savedApps.isEmpty()) {
                selectedAppsToStop.addAll(Arrays.asList(savedApps.split(",")));
                if (currentScheme != null) {
                    currentScheme.appsToStop = new ArrayList<>(selectedAppsToStop);
                }
            }
        }

        updateSchemeSpinner();
        loadCurrentScheme();
    }

    private Scheme findSchemeByName(String name) {
        for (Scheme scheme : schemes) {
            if (scheme.name.equals(name)) {
                return scheme;
            }
        }
        return null;
    }

    private void updateSchemeSpinner() {
        List<String> schemeNames = new ArrayList<>();
        for (Scheme scheme : schemes) {
            schemeNames.add(scheme.name);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, schemeNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSchemeSelector.setAdapter(adapter);

        // 设置当前选中的方案
        if (currentScheme != null) {
            int position = schemes.indexOf(currentScheme);
            if (position >= 0) {
                spSchemeSelector.setSelection(position);
            }
        }
    }

// 在MainActivity.java中添加或修改以下方法

    private void loadCurrentScheme() {
        if (currentScheme == null) return;

        // 更新UI显示当前方案的配置
        etAppActivity.setText(TextUtils.isEmpty(currentScheme.appName) ? "未选择则不跳转" : currentScheme.appName);
        operations.clear();
        operations.addAll(currentScheme.operations);
        operationAdapter.notifyDataSetChanged();

        // 更新执行完成操作设置
        if (swStopAppsEnabled != null) {
            swStopAppsEnabled.setChecked(currentScheme.stopAppsEnabled);
            btnSelectAppsToStop.setEnabled(currentScheme.stopAppsEnabled);
        }

        // 确保selectedAppsToStop与当前方案的appsToStop同步
        selectedAppsToStop.clear();
        selectedAppsToStop.addAll(currentScheme.appsToStop);


        // 更新SharedPreferences中的当前方案
        sharedPreferences.edit()
                .putString(KEY_APP_ACTIVITY, currentScheme.appActivity)
                .putString(KEY_APP_NAME, currentScheme.appName)
                .putString(KEY_OPERATIONS, Operation.toJsonArray(currentScheme.operations))
                .putBoolean(KEY_STOP_APPS_ENABLED, currentScheme.stopAppsEnabled)
                .putString(KEY_STOP_APPS, TextUtils.join(",", currentScheme.appsToStop))
                .putString(KEY_CURRENT_SCHEME, currentScheme.name)
                .apply();
    }


    // 修改saveCurrentScheme方法
    private void saveCurrentScheme() {
        if (currentScheme == null) return;

        // 从UI获取最新配置
        String displayName = etAppActivity.getText().toString();
        currentScheme.appName = "未选择则不跳转".equals(displayName) ? "" : displayName;
        currentScheme.appActivity = sharedPreferences.getString(KEY_APP_ACTIVITY, "");
        currentScheme.operations = new ArrayList<>(operations);
        currentScheme.stopAppsEnabled = swStopAppsEnabled != null && swStopAppsEnabled.isChecked();


        currentScheme.appsToStop = new ArrayList<>(selectedAppsToStop);

        // 保存到SharedPreferences
        sharedPreferences.edit()
                .putString(KEY_APP_ACTIVITY, currentScheme.appActivity)
                .putString(KEY_APP_NAME, currentScheme.appName)
                .putString(KEY_OPERATIONS, Operation.toJsonArray(currentScheme.operations))
                .putBoolean(KEY_STOP_APPS_ENABLED, currentScheme.stopAppsEnabled)
                .putString(KEY_STOP_APPS, TextUtils.join(",", currentScheme.appsToStop))
                .putString(KEY_CURRENT_SCHEME, currentScheme.name)
                .apply();

        // 保存所有方案
        saveAllSchemes();
    }

    private void saveAllSchemes() {
        // 确保当前方案的appsToStop与selectedAppsToStop同步
        if (currentScheme != null) {
            currentScheme.appsToStop = new ArrayList<>(selectedAppsToStop);
        }

        // 保存到SharedPreferences
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_SCHEMES, Scheme.toJsonArray(schemes));

        // 单独保存stop_apps以便兼容旧版本
        if (currentScheme != null && !currentScheme.appsToStop.isEmpty()) {
            editor.putString(KEY_STOP_APPS, TextUtils.join(",", currentScheme.appsToStop));
        } else {
            editor.remove(KEY_STOP_APPS);
        }

        editor.putString(KEY_CURRENT_SCHEME, currentScheme != null ? currentScheme.name : "");
        editor.apply();
    }

    private void createNewScheme() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_new_scheme, null);
        EditText etSchemeName = dialogView.findViewById(R.id.et_scheme_name);

        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setTitle("新建方案")
                .setPositiveButton("确定", (dialog, which) -> {
                    String name = etSchemeName.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "方案名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 检查名称是否已存在
                    if (findSchemeByName(name) != null) {
                        Toast.makeText(this, "方案名称已存在", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 保存当前方案
                    saveCurrentScheme();

                    // 创建新方案 - 清空所有配置
                    Scheme newScheme = new Scheme(name);
                    // 清空选择的应用列表
                    newScheme.appsToStop = new ArrayList<>();
                    selectedAppsToStop.clear();

                    schemes.add(newScheme);
                    currentScheme = newScheme;

                    // 更新UI
                    updateSchemeSpinner();
                    loadCurrentScheme();

                    Toast.makeText(this, "新建方案成功", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteCurrentScheme() {
        if (currentScheme == null || schemes.size() <= 1) {
            Toast.makeText(this, "至少保留一个方案", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("删除方案")
                .setMessage("确定要删除方案 '" + currentScheme.name + "' 吗?")
                .setPositiveButton("删除", (dialog, which) -> {
                    int position = schemes.indexOf(currentScheme);
                    schemes.remove(currentScheme);

                    // 选择另一个方案
                    if (position >= schemes.size()) {
                        position = schemes.size() - 1;
                    }
                    currentScheme = schemes.get(position);

                    // 更新UI
                    updateSchemeSpinner();
                    loadCurrentScheme();
                    saveAllSchemes();

                    Toast.makeText(this, "方案已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean isConfigValid() {
        boolean hasOps = currentScheme != null ? !currentScheme.operations.isEmpty() : !operations.isEmpty();
        if (!hasOps) {
            Toast.makeText(this, "请先添加操作步骤", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void showAutoStartCountdown() {
        if (!sharedPreferences.getBoolean(KEY_AUTO_CLICK, false)) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_countdown, null);
        TextView tvCountdown = dialogView.findViewById(R.id.tv_countdown);
        Button btnStop = dialogView.findViewById(R.id.btn_stop);

        builder.setView(dialogView);
        builder.setCancelable(false);
        AlertDialog dialog = builder.create();

        final int[] countdown = {3};
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
                    startExecOverlay(true);
                }
            }
        };

        btnStop.setOnClickListener(v -> {
            countdownHandler.removeCallbacks(countdownRunnable);
            sharedPreferences.edit().putBoolean(KEY_AUTO_CLICK, false).apply();
            dialog.dismiss();
        });

        dialog.show();
        countdownHandler.post(countdownRunnable);
    }

    private void startAutoClick() {
        handler.postDelayed(this::performOperations, 1000);
    }

    private void performOperations() {
        if (!sharedPreferences.getBoolean(KEY_AUTO_CLICK, false) || !hasRootPermission() || currentScheme == null) {
            return;
        }

        new Thread(() -> {
            try {
                int clickDuration = sharedPreferences.getInt(KEY_CLICK_DURATION, DEFAULT_CLICK_DURATION);
                int longPressDuration = sharedPreferences.getInt(KEY_LONG_PRESS_DURATION, DEFAULT_LONG_PRESS_DURATION);
                int swipeDuration = sharedPreferences.getInt(KEY_SWIPE_DURATION, DEFAULT_SWIPE_DURATION);

                // 启动目标Activity（可选）
                if (!TextUtils.isEmpty(currentScheme.appActivity)) {
                    Runtime.getRuntime().exec("su -c am start -n " + currentScheme.appActivity);
                }

                for (Operation op : currentScheme.operations) {
                    Thread.sleep(op.delay);
                    if (op.type == Operation.TYPE_CLICK) {
                        // 使用设置的点击持续时间
                        Runtime.getRuntime().exec(
                                "su -c input swipe " + op.x1 + " " + op.y1 + " " +
                                        op.x1 + " " + op.y1 + " " + clickDuration).waitFor();
                    } else if (op.type == Operation.TYPE_LONG_PRESS) {
                        Runtime.getRuntime().exec(
                                "su -c input swipe " + op.x1 + " " + op.y1 + " " +
                                        op.x1 + " " + op.y1 + " " + longPressDuration).waitFor();
                    } else {
                        // 使用设置的滑动持续时间
                        Runtime.getRuntime().exec(
                                "su -c input swipe " + op.x1 + " " + op.y1 + " " +
                                        op.x2 + " " + op.y2 + " " + swipeDuration).waitFor();
                    }
                }

                runOnUiThread(() -> {
                    Toast.makeText(this, "操作流程执行完成", Toast.LENGTH_SHORT).show();

                    if (currentScheme.stopAppsEnabled) {
                        stopSelectedApps();// 执行完后停止应用
                    }

                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showExecuteDialog() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        android.widget.LinearLayout row1 = new android.widget.LinearLayout(this);
        row1.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.TextView tv1 = new android.widget.TextView(this);
        tv1.setText("自动执行开关（软件启动则自动执行）");
        tv1.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        android.widget.Switch sw1 = new android.widget.Switch(this);
        sw1.setChecked(sharedPreferences.getBoolean(KEY_AUTO_CLICK, false));
        row1.addView(tv1);
        row1.addView(sw1);
        root.addView(row1);

        android.widget.LinearLayout row2 = new android.widget.LinearLayout(this);
        row2.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.TextView tv2 = new android.widget.TextView(this);
        tv2.setText("悬浮球执行");
        tv2.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        android.widget.Switch sw2 = new android.widget.Switch(this);
        boolean overlayActive = execOverlayActive;
        sw2.setChecked(overlayActive);
        row2.addView(tv2);
        row2.addView(sw2);
        root.addView(row2);

        android.widget.LinearLayout row3 = new android.widget.LinearLayout(this);
        row3.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.TextView tv3 = new android.widget.TextView(this);
        tv3.setText("循环次数");
        tv3.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        android.widget.EditText etLoopCount = new android.widget.EditText(this);
        etLoopCount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etLoopCount.setHint("1");
        etLoopCount.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        etLoopCount.setText(String.valueOf(Math.max(1, sharedPreferences.getInt(KEY_EXEC_LOOP_COUNT, 1))));
        android.widget.CheckBox cbLoopForever = new android.widget.CheckBox(this);
        cbLoopForever.setText("一直执行下去");
        cbLoopForever.setChecked(sharedPreferences.getBoolean(KEY_EXEC_LOOP_FOREVER, false));
        row3.addView(tv3);
        row3.addView(etLoopCount);
        row3.addView(cbLoopForever);
        root.addView(row3);

        android.widget.LinearLayout row4 = new android.widget.LinearLayout(this);
        row4.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.TextView tv4 = new android.widget.TextView(this);
        tv4.setText("循环间隔(ms)");
        tv4.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        android.widget.EditText etLoopInterval = new android.widget.EditText(this);
        etLoopInterval.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etLoopInterval.setHint("1000");
        etLoopInterval.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        etLoopInterval.setText(String.valueOf(Math.max(0, sharedPreferences.getInt(KEY_EXEC_LOOP_INTERVAL_MS, 1000))));
        row4.addView(tv4);
        row4.addView(etLoopInterval);
        root.addView(row4);

        android.widget.LinearLayout row5 = new android.widget.LinearLayout(this);
        row5.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.CheckBox cbLoopNotify = new android.widget.CheckBox(this);
        cbLoopNotify.setText("每轮结束提示");
        cbLoopNotify.setChecked(sharedPreferences.getBoolean(KEY_EXEC_LOOP_NOTIFY, false));
        row5.addView(cbLoopNotify);
        root.addView(row5);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("执行")
                .setView(root)
                .setNegativeButton("关闭", null)
                .create();

        sw1.setOnCheckedChangeListener((b, ck) -> {
            sharedPreferences.edit().putBoolean(KEY_AUTO_CLICK, ck).apply();
            if (ck) {
                if (isConfigValid()) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                            boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
                            if (!ignoring) {
                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("建议关闭电池优化")
                                        .setMessage("为保证跨应用执行流程稳定，请将本应用设为不受电池优化限制。小米设备请设置为‘无限制’。")
                                        .setPositiveButton("去设置", (d, w) -> {
                                            try {
                                                if (android.os.Build.MANUFACTURER != null && android.os.Build.MANUFACTURER.toLowerCase().contains("xiaomi")) {
                                                    Intent miui = new Intent();
                                                    miui.setComponent(new ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
                                                    miui.putExtra("package_name", getPackageName());
                                                    miui.putExtra("package_label", getApplicationInfo().loadLabel(getPackageManager()).toString());
                                                    startActivity(miui);
                                                } else {
                                                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                                    i.setData(Uri.parse("package:" + getPackageName()));
                                                    startActivity(i);
                                                }
                                            } catch (Exception e1) {
                                                try {
                                                    Intent i2 = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                                                    startActivity(i2);
                                                } catch (Exception ignored) {}
                                            }
                                        })
                                        .setNegativeButton("暂不", null)
                                        .show();
                            }
                        }
                    } catch (Exception ignored) {}
                    showAutoStartCountdown();
                } else {
                    sharedPreferences.edit().putBoolean(KEY_AUTO_CLICK, false).apply();
                    Toast.makeText(MainActivity.this, "请先添加操作步骤", Toast.LENGTH_SHORT).show();
                }
            }
        });

        cbLoopForever.setOnCheckedChangeListener((buttonView, isChecked) -> {
            etLoopCount.setEnabled(!isChecked);
            sharedPreferences.edit().putBoolean(KEY_EXEC_LOOP_FOREVER, isChecked).apply();
        });

        etLoopCount.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                int v = parseIntSafe(s.toString());
                if (v <= 0) v = 1;
                sharedPreferences.edit().putInt(KEY_EXEC_LOOP_COUNT, v).apply();
            }
        });

        cbLoopNotify.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_EXEC_LOOP_NOTIFY, isChecked).apply();
        });

        etLoopInterval.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                int v = parseIntSafe(s.toString());
                if (v < 0) v = 0;
                sharedPreferences.edit().putInt(KEY_EXEC_LOOP_INTERVAL_MS, v).apply();
            }
        });

        CompoundButton.OnCheckedChangeListener execOverlayListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    if (sharedPreferences.getBoolean("record_overlay_active", false)) {
                        Toast.makeText(MainActivity.this, "录制悬浮球已开启，请先终止录制后再开启执行悬浮球", Toast.LENGTH_SHORT).show();
                        sw2.setOnCheckedChangeListener(null);
                        sw2.setChecked(false);
                        sw2.setOnCheckedChangeListener(this);
                        return;
                    }
                    int v = parseIntSafe(etLoopCount.getText().toString());
                    if (v <= 0) v = 1;
                    sharedPreferences.edit()
                            .putInt(KEY_EXEC_LOOP_COUNT, v)
                            .putBoolean(KEY_EXEC_LOOP_FOREVER, cbLoopForever.isChecked())
                            .putInt(KEY_EXEC_LOOP_INTERVAL_MS, Math.max(0, parseIntSafe(etLoopInterval.getText().toString())))
                            .putBoolean(KEY_EXEC_LOOP_NOTIFY, cbLoopNotify.isChecked())
                            .apply();
                    startExecOverlay();
                } else {
                    stopExecOverlay();
                }
            }
        };
        sw2.setOnCheckedChangeListener(execOverlayListener);

        dlg.show();
    }

    private void startExecOverlay(boolean autoStart) {
        if (execOverlayActive) {
            Toast.makeText(this, "执行悬浮球已开启", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sharedPreferences.getBoolean("record_overlay_active", false)) {
            Toast.makeText(this, "录制悬浮球已开启，请先终止录制后再开启执行悬浮球", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Intent permIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(permIntent);
                Toast.makeText(this, "请授予悬浮窗权限后重试", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception ignored) {}
        Intent intent = new Intent(this, RecordService.class);
        intent.putExtra("exec_overlay", true);
        intent.putExtra("auto_start_exec", autoStart);
        startService(intent);
        execOverlayActive = true;
        try { sharedPreferences.edit().putBoolean("exec_overlay_active", true).apply(); } catch (Exception ignored) {}
    }

    private void startExecOverlay() {
        startExecOverlay(false);
    }

    private void stopExecOverlay() {
        Intent intent = new Intent(this, RecordService.class);
        intent.putExtra("stop_overlay", true);
        startService(intent);
        execOverlayActive = false;
        try { sharedPreferences.edit().putBoolean("exec_overlay_active", false).apply(); } catch (Exception ignored) {}
        //Toast.makeText(this, "已关闭执行悬浮球", Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
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
            etAppActivity.setText(appNames.get(which));
            if (currentScheme == null) {
                Scheme def = findSchemeByName("默认方案");
                if (def == null) {
                    def = new Scheme("默认方案");
                    schemes.add(def);
                }
                currentScheme = def;
                updateSchemeSpinner();
            }
            currentScheme.appName = appNames.get(which);
            currentScheme.appActivity = appActivities.get(which);
            saveCurrentScheme();
            sharedPreferences.edit()
                    .putString(KEY_APP_ACTIVITY, appActivities.get(which))
                    .putString(KEY_APP_NAME, appNames.get(which))
                    .apply();
            Toast.makeText(this, "已选择并保存目标应用", Toast.LENGTH_SHORT).show();
        });

        builder.show();
    }

    void showOperationDialog(Operation operation) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_operation, null);

        Spinner spType = dialogView.findViewById(R.id.sp_type);
        TextView tvStartPoint = dialogView.findViewById(R.id.tv_start_point);
        LinearLayout layoutEndPoint = dialogView.findViewById(R.id.layout_end_point);
        LinearLayout layoutXy2 = dialogView.findViewById(R.id.layout_xy2);
        EditText etDelay = dialogView.findViewById(R.id.et_delay);
        EditText etX1 = dialogView.findViewById(R.id.et_x1);
        EditText etY1 = dialogView.findViewById(R.id.et_y1);
        EditText etX2 = dialogView.findViewById(R.id.et_x2);
        EditText etY2 = dialogView.findViewById(R.id.et_y2);

        spType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean singlePoint = position == 0 || position == 1;
                tvStartPoint.setText(singlePoint ? "点击/长按坐标:" : "起始坐标:");
                layoutEndPoint.setVisibility(singlePoint ? View.GONE : View.VISIBLE);
                layoutXy2.setVisibility(singlePoint ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        if (operation != null) {
            int sel = operation.type == Operation.TYPE_CLICK ? 0 : (operation.type == Operation.TYPE_LONG_PRESS ? 1 : 2);
            spType.setSelection(sel);
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
                        int typeSel = spType.getSelectedItemPosition();
                        int type = typeSel == 0 ? Operation.TYPE_CLICK : (typeSel == 1 ? Operation.TYPE_LONG_PRESS : Operation.TYPE_SWIPE);
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
                        saveCurrentScheme();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入有效的数值", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null);

        if (operation != null) {
            builder.setNeutralButton("删除", (dialog, which) -> {
                operations.remove(operation);
                operationAdapter.notifyDataSetChanged();
                saveCurrentScheme();
            });
        }

        AlertDialog dialog = builder.create();
        dialog.show();

        boolean isClick = spType.getSelectedItemPosition() == 0;
        tvStartPoint.setText(isClick ? "点击坐标:" : "起始坐标:");
        layoutEndPoint.setVisibility(isClick ? View.GONE : View.VISIBLE);
        layoutXy2.setVisibility(isClick ? View.GONE : View.VISIBLE);
    }

    private void showAppSelectionDialog() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);

        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);

        if (apps.isEmpty()) {
            Toast.makeText(this, "未找到可启动的应用", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> appNames = new ArrayList<>();
        final List<String> appPackages = new ArrayList<>();
        final boolean[] checkedItems = new boolean[apps.size()];

        for (int i = 0; i < apps.size(); i++) {
            ResolveInfo info = apps.get(i);
            appNames.add(info.loadLabel(pm).toString());
            appPackages.add(info.activityInfo.packageName);
            checkedItems[i] = selectedAppsToStop.contains(info.activityInfo.packageName);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("选择要停止的应用")
                .setMultiChoiceItems(appNames.toArray(new String[0]), checkedItems,
                        (dialog, which, isChecked) -> checkedItems[which] = isChecked)
                .setPositiveButton("确定", (dialog, which) -> {
                    // 更新selectedAppsToStop
                    selectedAppsToStop.clear();
                    for (int i = 0; i < checkedItems.length; i++) {
                        if (checkedItems[i]) {
                            selectedAppsToStop.add(appPackages.get(i));
                        }
                    }
                    // 同步到当前方案并保存
                    if (currentScheme != null) {
                        currentScheme.appsToStop = new ArrayList<>(selectedAppsToStop);
                        saveAllSchemes(); // 确保立即保存
                    }
                    Toast.makeText(MainActivity.this, "已选择 " + selectedAppsToStop.size() + " 个应用", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null);

        builder.show();
    }

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

    private void showSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);

        EditText etClickDuration = dialogView.findViewById(R.id.et_click_duration);
        EditText etSwipeDuration = dialogView.findViewById(R.id.et_swipe_duration);
        EditText etLongPressDuration = dialogView.findViewById(R.id.et_long_press_duration);
        EditText etMoveTolerancePx = dialogView.findViewById(R.id.et_move_tolerance_px);
        EditText etMotionThreshold = dialogView.findViewById(R.id.et_motion_threshold);
        EditText etFirstActionDelay = dialogView.findViewById(R.id.et_first_action_delay);
        android.widget.CheckBox cbFallbackNoXY = dialogView.findViewById(R.id.cb_fallback_no_xy);
        android.widget.CheckBox cbShowDebug = dialogView.findViewById(R.id.cb_show_debug);
        android.widget.CheckBox cbKeepAlive = dialogView.findViewById(R.id.cb_keep_alive);
        android.widget.CheckBox cbVolumeKeys = dialogView.findViewById(R.id.cb_volume_keys);
        EditText etTouchDevice = dialogView.findViewById(R.id.et_touch_device);
        EditText etMaxX = dialogView.findViewById(R.id.et_max_x);
        EditText etMaxY = dialogView.findViewById(R.id.et_max_y);
        Button btnAutoDetect = dialogView.findViewById(R.id.btn_auto_detect_device);
        TextView tvScreenPx = dialogView.findViewById(R.id.tv_screen_px);

        etClickDuration.setText(String.valueOf(
                sharedPreferences.getInt(KEY_CLICK_DURATION, DEFAULT_CLICK_DURATION)));
        etSwipeDuration.setText(String.valueOf(
                sharedPreferences.getInt(KEY_SWIPE_DURATION, DEFAULT_SWIPE_DURATION)));
        etLongPressDuration.setText(String.valueOf(
                sharedPreferences.getInt(KEY_LONG_PRESS_DURATION, DEFAULT_LONG_PRESS_DURATION)));
        etMoveTolerancePx.setText(String.valueOf(
                sharedPreferences.getInt(KEY_MOVE_TOLERANCE_PX, 20)));
        etMotionThreshold.setText(String.valueOf(
                sharedPreferences.getInt(KEY_MOTION_THRESHOLD, 9999)));
        etFirstActionDelay.setText(String.valueOf(
                sharedPreferences.getInt(KEY_FIRST_ACTION_DELAY, 2000)));
        cbFallbackNoXY.setChecked(sharedPreferences.getBoolean(KEY_FALLBACK_NO_XY, true));
        cbShowDebug.setChecked(sharedPreferences.getBoolean(KEY_SHOW_DEBUG, false));
        cbKeepAlive.setChecked(sharedPreferences.getBoolean(KEY_KEEP_ALIVE, false));
        cbVolumeKeys.setChecked(sharedPreferences.getBoolean(KEY_VOLUME_KEYS, true));

        cbKeepAlive.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                boolean notifyEnabled;
                try {
                    androidx.core.app.NotificationManagerCompat nmc = androidx.core.app.NotificationManagerCompat.from(this);
                    boolean enabled = nmc.areNotificationsEnabled();
                    boolean granted = Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
                    notifyEnabled = enabled && granted;
                } catch (Exception e) { notifyEnabled = false; }

                if (notifyEnabled) {
                    startKeepAliveServiceSafely();
                    sharedPreferences.edit().putBoolean(KEY_KEEP_ALIVE, true).apply();
                } else {
                    ensureNotificationPermission();
                    buttonView.setChecked(false);
                    sharedPreferences.edit().putBoolean(KEY_KEEP_ALIVE, false).apply();
                }
            } else {
                sharedPreferences.edit().putBoolean(KEY_KEEP_ALIVE, false).apply();
                try {
                    Intent stop = new Intent(this, RecordService.class).putExtra("stop_keep_alive", true);
                    startService(stop);
                } catch (Exception ignored) {}
            }
        });

        etTouchDevice.setText(sharedPreferences.getString(KEY_TOUCH_DEVICE, ""));
        etMaxX.setText(String.valueOf(sharedPreferences.getInt(KEY_MAX_X, 0)));
        etMaxY.setText(String.valueOf(sharedPreferences.getInt(KEY_MAX_Y, 0)));

        try {
            int w = 0, h = 0;
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
                    w = b.width();
                    h = b.height();
                } else {
                    DisplayMetrics dm = new DisplayMetrics();
                    android.view.Display d = wm.getDefaultDisplay();
                    if (d != null) {
                        d.getRealMetrics(dm);
                        w = dm.widthPixels;
                        h = dm.heightPixels;
                    }
                }
            }
            if (w <= 0 || h <= 0) {
                DisplayMetrics dm2 = getResources().getDisplayMetrics();
                w = dm2.widthPixels;
                h = dm2.heightPixels;
            }
            tvScreenPx.setText(w + " x " + h);
        } catch (Throwable ignored) {}

        btnAutoDetect.setOnClickListener(v -> {
            autoDetectTouchDevice(etTouchDevice, etMaxX, etMaxY, tvScreenPx);
        });

        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setTitle("设置")
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        int clickDuration = Integer.parseInt(etClickDuration.getText().toString());
                        int longPressDuration = Integer.parseInt(etLongPressDuration.getText().toString());
                        int swipeDuration = Integer.parseInt(etSwipeDuration.getText().toString());
                        int moveTolerancePx = parseIntSafe(etMoveTolerancePx.getText().toString());
                        int motionThreshold = parseIntSafe(etMotionThreshold.getText().toString());
                        int firstActionDelay = parseIntSafe(etFirstActionDelay.getText().toString());
                        boolean showDebug = cbShowDebug.isChecked();
                        String touchDevice = etTouchDevice.getText().toString().trim();
                        int maxX = parseIntSafe(etMaxX.getText().toString());
                        int maxY = parseIntSafe(etMaxY.getText().toString());

                        sharedPreferences.edit()
                                .putInt(KEY_CLICK_DURATION, clickDuration)
                                .putInt(KEY_LONG_PRESS_DURATION, longPressDuration)
                                .putInt(KEY_SWIPE_DURATION, swipeDuration)
                                .putInt(KEY_MOVE_TOLERANCE_PX, moveTolerancePx)
                                .putInt(KEY_MOTION_THRESHOLD, motionThreshold)
                                .putInt(KEY_FIRST_ACTION_DELAY, firstActionDelay)
                                .putBoolean(KEY_FALLBACK_NO_XY, cbFallbackNoXY.isChecked())
                                .putBoolean(KEY_SHOW_DEBUG, showDebug)
                                .putBoolean(KEY_KEEP_ALIVE, cbKeepAlive.isChecked())
                                .putBoolean(KEY_VOLUME_KEYS, cbVolumeKeys.isChecked())
                                .putString(KEY_TOUCH_DEVICE, touchDevice)
                                .putInt(KEY_MAX_X, maxX)
                                .putInt(KEY_MAX_Y, maxY)
                                .apply();

                        //Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入有效的数值", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPermissionsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_permissions, null);
        TextView tvRootPerm = dialogView.findViewById(R.id.tv_root_perm);
        TextView tvBatteryStatus = dialogView.findViewById(R.id.tv_battery_status);
        TextView tvNotifyStatus = dialogView.findViewById(R.id.tv_notify_status);
        TextView tvOverlayStatus = dialogView.findViewById(R.id.tv_overlay_status);
        Button btnGetRoot = dialogView.findViewById(R.id.btn_get_root);
        Button btnIgnoreBattery = dialogView.findViewById(R.id.btn_ignore_battery);
        Button btnOpenNotify = dialogView.findViewById(R.id.btn_open_notify);
        Button btnOverlayPerm = dialogView.findViewById(R.id.btn_overlay_perm);
        android.widget.CheckBox cbKeepAlivePerm = dialogView.findViewById(R.id.cb_keep_alive_perm);

        tvRootPerm.setText(hasRootPermission ? "已获取" : "未获取");
        btnGetRoot.setEnabled(!hasRootPermission);
        btnGetRoot.setAlpha(hasRootPermission ? 0.5f : 1f);
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean ignoring = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            }
            tvBatteryStatus.setText(ignoring ? "已关闭优化" : "未关闭");
            btnIgnoreBattery.setEnabled(!ignoring);
            btnIgnoreBattery.setAlpha(ignoring ? 0.5f : 1f);
        } catch (Exception e) {
            tvBatteryStatus.setText("未知");
        }
        cbKeepAlivePerm.setChecked(sharedPreferences.getBoolean(KEY_KEEP_ALIVE, false));
        try {
            androidx.core.app.NotificationManagerCompat nmc = androidx.core.app.NotificationManagerCompat.from(this);
            boolean enabled = nmc.areNotificationsEnabled();
            boolean granted = Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            boolean notifyOk = enabled && granted;
            tvNotifyStatus.setText(notifyOk ? "已开启" : "未开启");
            btnOpenNotify.setEnabled(!notifyOk);
            btnOpenNotify.setAlpha(notifyOk ? 0.5f : 1f);
        } catch (Exception e) {
            tvNotifyStatus.setText("未知");
        }
        try {
            boolean overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this);
            tvOverlayStatus.setText(overlayGranted ? "已授权" : "未授权");
            btnOverlayPerm.setEnabled(!overlayGranted);
            btnOverlayPerm.setAlpha(overlayGranted ? 0.5f : 1f);
        } catch (Exception e) {
            tvOverlayStatus.setText("未知");
        }

        btnGetRoot.setOnClickListener(v -> {
            requestRootPermission();
            tvRootPerm.setText(hasRootPermission ? "已获取" : "未获取");
        });

        btnIgnoreBattery.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception ignored) {}
        });

        btnOpenNotify.setOnClickListener(v -> {
            ensureNotificationPermission();
        });

        btnOverlayPerm.setOnClickListener(v -> {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignored) {}
        });

        cbKeepAlivePerm.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                boolean notifyEnabled;
                try {
                    androidx.core.app.NotificationManagerCompat nmc = androidx.core.app.NotificationManagerCompat.from(this);
                    boolean enabled = nmc.areNotificationsEnabled();
                    boolean granted = Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
                    notifyEnabled = enabled && granted;
                } catch (Exception e) { notifyEnabled = false; }

                if (notifyEnabled) {
                    startKeepAliveServiceSafely();
                    sharedPreferences.edit().putBoolean(KEY_KEEP_ALIVE, true).apply();
                } else {
                    ensureNotificationPermission();
                    buttonView.setChecked(false);
                    sharedPreferences.edit().putBoolean(KEY_KEEP_ALIVE, false).apply();
                }
            } else {
                sharedPreferences.edit().putBoolean(KEY_KEEP_ALIVE, false).apply();
                try {
                    Intent stop = new Intent(this, RecordService.class).putExtra("stop_keep_alive", true);
                    startService(stop);
                } catch (Exception ignored) {}
            }
        });

        permissionsDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setTitle("权限设置")
                .setNegativeButton("关闭", (d, w) -> { permissionsDialog = null; })
                .create();
        permissionsDialog.show();
    }

    private void refreshPermissionsDialogStatuses() {
        if (permissionsDialog == null || !permissionsDialog.isShowing()) return;
        TextView tvRootPerm = permissionsDialog.findViewById(R.id.tv_root_perm);
        TextView tvBatteryStatus = permissionsDialog.findViewById(R.id.tv_battery_status);
        TextView tvNotifyStatus = permissionsDialog.findViewById(R.id.tv_notify_status);
        TextView tvOverlayStatus = permissionsDialog.findViewById(R.id.tv_overlay_status);
        Button btnGetRoot = permissionsDialog.findViewById(R.id.btn_get_root);
        Button btnIgnoreBattery = permissionsDialog.findViewById(R.id.btn_ignore_battery);
        Button btnOpenNotify = permissionsDialog.findViewById(R.id.btn_open_notify);
        Button btnOverlayPerm = permissionsDialog.findViewById(R.id.btn_overlay_perm);
        if (tvRootPerm != null) tvRootPerm.setText(hasRootPermission ? "已获取" : "未获取");
        if (btnGetRoot != null) { btnGetRoot.setEnabled(!hasRootPermission); btnGetRoot.setAlpha(hasRootPermission ? 0.5f : 1f); }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean ignoring = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            }
            if (tvBatteryStatus != null) tvBatteryStatus.setText(ignoring ? "已关闭优化" : "未关闭");
            if (btnIgnoreBattery != null) { btnIgnoreBattery.setEnabled(!ignoring); btnIgnoreBattery.setAlpha(ignoring ? 0.5f : 1f); }
        } catch (Exception e) { if (tvBatteryStatus != null) tvBatteryStatus.setText("未知"); }
        try {
            androidx.core.app.NotificationManagerCompat nmc = androidx.core.app.NotificationManagerCompat.from(this);
            boolean enabled = nmc.areNotificationsEnabled();
            boolean granted = Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            if (tvNotifyStatus != null) tvNotifyStatus.setText(enabled && granted ? "已开启" : "未开启");
            boolean notifyOk = enabled && granted;
            if (btnOpenNotify != null) { btnOpenNotify.setEnabled(!notifyOk); btnOpenNotify.setAlpha(notifyOk ? 0.5f : 1f); }
        } catch (Exception e) { if (tvNotifyStatus != null) tvNotifyStatus.setText("未知"); }
        try {
            boolean overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this);
            if (tvOverlayStatus != null) tvOverlayStatus.setText(overlayGranted ? "已授权" : "未授权");
            if (btnOverlayPerm != null) { btnOverlayPerm.setEnabled(!overlayGranted); btnOverlayPerm.setAlpha(overlayGranted ? 0.5f : 1f); }
        } catch (Exception e) { if (tvOverlayStatus != null) tvOverlayStatus.setText("未知"); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionsDialogStatuses();
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private void startRecordingOverlay() {
        if (execOverlayActive) {
            Toast.makeText(this, "执行悬浮球已开启，请先在“执行”弹窗中关闭", Toast.LENGTH_SHORT).show();
            return;
        }
        String tdCheck = sharedPreferences.getString(KEY_TOUCH_DEVICE, "");
        if (TextUtils.isEmpty(tdCheck)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要配置触摸设备")
                    .setMessage("请前往设置，填入触摸设备路径（可使用自动解析）。")
                    .setPositiveButton("去设置", (d, w) -> showSettingsDialog())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
                if (!ignoring) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("建议关闭电池优化")
                            .setMessage("为保证录制与跨应用执行稳定，请将本应用设为不受电池优化限制。小米设备请设置为‘无限制’。")
                            .setPositiveButton("去设置", (d, w) -> {
                                try {
                                    if (android.os.Build.MANUFACTURER != null && android.os.Build.MANUFACTURER.toLowerCase().contains("xiaomi")) {
                                        Intent miui = new Intent();
                                        miui.setComponent(new ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
                                        miui.putExtra("package_name", getPackageName());
                                        miui.putExtra("package_label", getApplicationInfo().loadLabel(getPackageManager()).toString());
                                        startActivity(miui);
                                    } else {
                                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                        i.setData(Uri.parse("package:" + getPackageName()));
                                        startActivity(i);
                                    }
                                } catch (Exception e1) {
                                    try {
                                        Intent i2 = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                                        startActivity(i2);
                                    } catch (Exception ignored) {}
                                }
                            })
                            .setNegativeButton("暂不", null)
                            .show();
                }
            }
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent permIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(permIntent);
            Toast.makeText(this, "请授予悬浮窗权限后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, RecordService.class);
        intent.putExtra("touch_device", sharedPreferences.getString(KEY_TOUCH_DEVICE, ""));
        intent.putExtra("max_x", sharedPreferences.getInt(KEY_MAX_X, 0));
        intent.putExtra("max_y", sharedPreferences.getInt(KEY_MAX_Y, 0));
        startService(intent);
        btnStartRecording.setText("终止录制");
        sharedPreferences.edit().putBoolean("record_overlay_active", true).apply();
        //Toast.makeText(this, "已开启悬浮球，点击开始/结束录制", Toast.LENGTH_SHORT).show();
    }

    private void stopRecordingOverlay(boolean cancelSteps) {
        Intent intent = new Intent(this, RecordService.class);
        intent.putExtra("stop_overlay", true);
        intent.putExtra("cancel", cancelSteps);
        startService(intent);
        btnStartRecording.setText("录制动作");
        sharedPreferences.edit().putBoolean("record_overlay_active", false).apply();
        if (cancelSteps) Toast.makeText(this, "已终止录制，不保存步骤", Toast.LENGTH_SHORT).show();
    }

    private void toggleRecordingOverlay() {
        boolean active = sharedPreferences.getBoolean("record_overlay_active", false);
        if (!active) {
            startRecordingOverlay();
        } else {
            stopRecordingOverlay(true);
        }
    }

    private void autoDetectTouchDevice(EditText etTouchDevice, EditText etMaxX, EditText etMaxY, TextView tvScreenPx) {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec("su -c getevent -pl");
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                String line;
                String currentDevice = null;
                String currentPath = null;
                int foundMaxX = 0;
                int foundMaxY = 0;
                while ((line = br.readLine()) != null) {
                    if (line.contains("add device") && line.contains("/dev/input/")) {
                        int idx = line.indexOf("/dev/input/");
                        if (idx >= 0) {
                            currentPath = line.substring(idx).split(" ")[0].trim();
                        }
                        currentDevice = currentPath;
                        foundMaxX = 0; foundMaxY = 0;
                    } else if (line.contains("ABS_MT_POSITION_X") || line.contains("ABS X")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("max\\s+(\\d+)").matcher(line);
                        if (m.find()) {
                            foundMaxX = Integer.parseInt(m.group(1));
                        }
                    } else if (line.contains("ABS_MT_POSITION_Y") || line.contains("ABS Y")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("max\\s+(\\d+)").matcher(line);
                        if (m.find()) {
                            foundMaxY = Integer.parseInt(m.group(1));
                        }
                    }
                    if (currentDevice != null && foundMaxX > 0 && foundMaxY > 0) {
                        final String path = currentDevice;
                        final int mx = foundMaxX;
                        final int my = foundMaxY;
                        runOnUiThread(() -> {
                            etTouchDevice.setText(path);
                            etMaxX.setText(String.valueOf(mx));
                            etMaxY.setText(String.valueOf(my));
                            try {
                                int w = 0, h = 0;
                                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                                if (wm != null) {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                        android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
                                        w = b.width();
                                        h = b.height();
                                    } else {
                                        DisplayMetrics dm = new DisplayMetrics();
                                        android.view.Display d = wm.getDefaultDisplay();
                                        if (d != null) {
                                            d.getRealMetrics(dm);
                                            w = dm.widthPixels;
                                            h = dm.heightPixels;
                                        }
                                    }
                                }
                                if (w <= 0 || h <= 0) {
                                    DisplayMetrics dm2 = getResources().getDisplayMetrics();
                                    w = dm2.widthPixels;
                                    h = dm2.heightPixels;
                                }
                                if (tvScreenPx != null) tvScreenPx.setText(w + " x " + h);
                            } catch (Throwable ignored) {}
                        });
                        break;
                    }
                }
                br.close();
                p.destroy();
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "解析失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void checkRootPermission() {
        new Thread(() -> {
            hasRootPermission = hasRootPermission();
            sharedPreferences.edit().putBoolean(KEY_ROOT_GRANTED, hasRootPermission).apply();
            runOnUiThread(() -> updateRootStatusUI());
        }).start();
    }

    private void updateRootStatusUI() {
        if (hasRootPermission) {
            tvRootStatus.setText("ROOT已获取");
            tvRootStatus.setBackgroundColor(Color.parseColor("#FF4CAF50"));
        } else {
            tvRootStatus.setText("ROOT未获取（点我获取）");
            tvRootStatus.setBackgroundColor(Color.parseColor("#FFF44336"));
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
        if (recordingReceiver != null) {
            try { unregisterReceiver(recordingReceiver); } catch (Exception ignored) {}
        }
    }

    private void registerRecordingReceiver() {
        recordingReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                if (ACTION_RECORDING_COMPLETE.equals(intent.getAction())) {
                    String json = intent.getStringExtra("operations_json");
                    String debugLog = intent.getStringExtra("debug_log");
                    if (json != null) {
                        ArrayList<Operation> ops = Operation.fromJsonArray(json);
                        try {
                            int firstDelay = sharedPreferences.getInt(KEY_FIRST_ACTION_DELAY, 2000);
                            if (!ops.isEmpty()) ops.get(0).delay = firstDelay;
                        } catch (Exception ignored) {}
                        if (!ops.isEmpty()) {
                            operations.clear();
                            operations.addAll(ops);
                            operationAdapter.notifyDataSetChanged();
                            saveCurrentSchemeWithToast();
                            Toast.makeText(MainActivity.this, "录制完成，已填入步骤", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "未录入任何动作，保留原有步骤", Toast.LENGTH_SHORT).show();
                        }
                        sharedPreferences.edit().putBoolean("record_overlay_active", false).apply();
                        if (btnStartRecording != null) btnStartRecording.setText("录制动作");
                    }
                    boolean showDebug = sharedPreferences.getBoolean(KEY_SHOW_DEBUG, false);
                    if (showDebug && debugLog != null && !debugLog.isEmpty()) {
                        new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("录制调试信息")
                                .setMessage(debugLog)
                                .setPositiveButton("复制", (d, w) -> {
                                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                    if (cm != null) {
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("record_debug", debugLog));
                                        Toast.makeText(MainActivity.this, "已复制调试信息", Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_RECORDING_COMPLETE);
        ContextCompat.registerReceiver(this, recordingReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void ensureNotificationPermission() {
        try {
            NotificationManagerCompat nmc = NotificationManagerCompat.from(this);
            boolean enabled = nmc.areNotificationsEnabled();
            if (!enabled) {
                new AlertDialog.Builder(this)
                        .setTitle("开启通知以提升稳定性")
                        .setMessage("请为本应用开启通知，以确保保活通知正常显示并提升录制/执行稳定性。")
                        .setPositiveButton("去设置", (d, w) -> {
                            try {
                                Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                                i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                                startActivity(i);
                            } catch (Exception ignored) {}
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
            // 在此处仅提示并跳转系统设置，不主动弹出运行时权限弹窗
        } catch (Exception ignored) {}
    }

    private void startKeepAliveServiceSafely() {
        try {
            android.util.Log.i(TAG, "Starting keep-alive foreground service");
            Intent svc = new Intent(this, RecordService.class).putExtra("keep_alive", true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            //Toast.makeText(this, "已开启保活通知", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to start keep-alive", e);
            Toast.makeText(this, "无法启动保活，请检查通知权限", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            boolean granted = grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingStartKeepAlive) {
                pendingStartKeepAlive = false;
                android.util.Log.i(TAG, "POST_NOTIFICATIONS granted; starting keep-alive");
                startKeepAliveServiceSafely();
            }
        }
    }
}