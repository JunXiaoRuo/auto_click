package cn.junruo.click;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Build;
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
    private static final String KEY_SCHEMES = "schemes";
    private static final String KEY_CURRENT_SCHEME = "current_scheme";
    private static final String KEY_CLICK_DURATION = "click_duration";
    private static final String KEY_SWIPE_DURATION = "swipe_duration";
    private static final String KEY_STOP_APPS = "stop_apps";
    private static final String KEY_STOP_APPS_ENABLED = "stop_apps_enabled";
    private static final int DEFAULT_CLICK_DURATION = 50;
    private static final int DEFAULT_SWIPE_DURATION = 100;

    // UI 组件
    private EditText etAppActivity;
    private Switch swAutoClick;
    private Button btnSelectApp;
    private Button btnAddOperation;
    private ListView lvOperations;
    private Button btnSettings;
    private TextView tvRootStatus;
    private Switch swStopAppsEnabled;
    private Button btnSelectAppsToStop;
    private Spinner spSchemeSelector;
    private Button btnNewScheme;
    private Button btnSaveScheme;
    private Button btnDeleteScheme;

    private final Handler handler = new Handler();
    private SharedPreferences sharedPreferences;
    private ArrayList<Operation> operations = new ArrayList<>();
    private OperationAdapter operationAdapter;
    private ArrayList<Scheme> schemes = new ArrayList<>();
    private Scheme currentScheme;
    private boolean hasRootPermission = false;
    private List<String> selectedAppsToStop = new ArrayList<>();

    // 修改onCreate方法中的初始化顺序
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().setStatusBarColor(getResources().getColor(R.color.white));
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 1. 初始化视图
        initViews();

        // 2. 检查ROOT权限
        checkRootPermission();

        // 3. 加载所有方案
        loadSchemes();

        // 4. 设置方案选择监听器
        setupSchemeSelector();

        // 5. 设置自动执行开关监听器
        setupAutoClickSwitchListener();
    }
    //////////////////////////
    private void initViews() {
        // 基本视图初始化
        etAppActivity = findViewById(R.id.et_app_activity);
        swAutoClick = findViewById(R.id.sw_auto_click);
        btnSelectApp = findViewById(R.id.btn_select_app);
        btnAddOperation = findViewById(R.id.btn_add_operation);
        lvOperations = findViewById(R.id.lv_operations);
        btnSettings = findViewById(R.id.btn_settings);
        tvRootStatus = findViewById(R.id.tv_root_status);

        // 操作列表初始化
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

        tvRootStatus.setOnClickListener(v -> {
            if (!hasRootPermission) {
                requestRootPermission();
            }
        });

        btnSelectApp.setOnClickListener(v -> selectApp());
        btnAddOperation.setOnClickListener(v -> showOperationDialog(null));

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

    private void setupAutoClickSwitchListener() {
        if (swAutoClick == null) return;

        // 先移除所有监听器
        swAutoClick.setOnCheckedChangeListener(null);

        // 创建监听器实例
        CompoundButton.OnCheckedChangeListener autoClickListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sharedPreferences.edit().putBoolean(KEY_AUTO_CLICK, isChecked).apply();
                if (isChecked) {
                    if (isConfigValid()) {
                        showAutoStartCountdown();
                    } else {
                        // 临时移除监听器避免递归
                        swAutoClick.setOnCheckedChangeListener(null);
                        swAutoClick.setChecked(false);
                        swAutoClick.setOnCheckedChangeListener(this);
                        Toast.makeText(MainActivity.this, "请先配置应用和操作步骤", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "自动执行已关闭：配置无效", Toast.LENGTH_SHORT).show();
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

                // 更新自动执行开关状态
                boolean autoClickEnabled = sharedPreferences.getBoolean(KEY_AUTO_CLICK, false);
                swAutoClick.setChecked(autoClickEnabled);
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
        etAppActivity.setText(currentScheme.appName);
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
        currentScheme.appName = etAppActivity.getText().toString();
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
        if (currentScheme == null) {
            return false;
        }

        boolean hasValidConfig = !TextUtils.isEmpty(currentScheme.appActivity)
                && !currentScheme.operations.isEmpty();

        if (!hasValidConfig) {
            if (TextUtils.isEmpty(currentScheme.appActivity)) {
                Toast.makeText(this, "请先选择目标应用", Toast.LENGTH_SHORT).show();
            } else if (currentScheme.operations.isEmpty()) {
                Toast.makeText(this, "请先添加操作步骤", Toast.LENGTH_SHORT).show();
            }
        }

        return hasValidConfig;
    }

    private void showAutoStartCountdown() {
        if (swAutoClick == null || !swAutoClick.isChecked()) return;

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
                    startAutoClick();
                }
            }
        };

        btnStop.setOnClickListener(v -> {
            countdownHandler.removeCallbacks(countdownRunnable);
            if (swAutoClick != null) {
                swAutoClick.setChecked(false);
            }
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
        if (swAutoClick == null || !swAutoClick.isChecked() || !hasRootPermission() || currentScheme == null) {
            return;
        }

        new Thread(() -> {
            try {
                int clickDuration = sharedPreferences.getInt(KEY_CLICK_DURATION, DEFAULT_CLICK_DURATION);
                int swipeDuration = sharedPreferences.getInt(KEY_SWIPE_DURATION, DEFAULT_SWIPE_DURATION);

                Runtime.getRuntime().exec("su -c am start -n " + currentScheme.appActivity);

                for (Operation op : currentScheme.operations) {
                    Thread.sleep(op.delay);
                    if (op.type == Operation.TYPE_CLICK) {
                        // 使用设置的点击持续时间
                        Runtime.getRuntime().exec(
                                "su -c input swipe " + op.x1 + " " + op.y1 + " " +
                                        op.x1 + " " + op.y1 + " " + clickDuration).waitFor();
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
                        stopSelectedApps();
                    }

                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
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
            if (currentScheme != null) {
                currentScheme.appName = appNames.get(which);
                currentScheme.appActivity = appActivities.get(which);
                saveCurrentScheme();
            }
            sharedPreferences.edit()
                    .putString(KEY_APP_ACTIVITY, appActivities.get(which))
                    .putString(KEY_APP_NAME, appNames.get(which))
                    .apply();
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
////////////////////////////
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
        String currentPackage = getPackageName();

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

    private void checkRootPermission() {
        new Thread(() -> {
            hasRootPermission = hasRootPermission();
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
    }
}