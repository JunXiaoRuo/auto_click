package cn.junruo.click;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadConfig();

        swAutoClick.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPreferences.edit().putBoolean(KEY_AUTO_CLICK, isChecked).apply();
            if (isChecked && isConfigValid()) {
                startAutoClick();
            } else if (isChecked) {
                swAutoClick.setChecked(false);
                Toast.makeText(this, "请先配置应用和操作步骤", Toast.LENGTH_SHORT).show();
            }
        });

        btnSelectApp.setOnClickListener(v -> selectApp());
        btnAddOperation.setOnClickListener(v -> showOperationDialog(null));

        if (sharedPreferences.getBoolean(KEY_AUTO_CLICK, false)) {
            swAutoClick.setChecked(true);
            if (isConfigValid()) {
                startAutoClick();
            }
        }
    }

    private void initViews() {
        etAppActivity = findViewById(R.id.et_app_activity);
        swAutoClick = findViewById(R.id.sw_auto_click);
        btnSelectApp = findViewById(R.id.btn_select_app);
        btnAddOperation = findViewById(R.id.btn_add_operation);
        lvOperations = findViewById(R.id.lv_operations);
        operationAdapter = new OperationAdapter(this, operations);
        lvOperations.setAdapter(operationAdapter);

        btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v -> showSettingsDialog());
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

                runOnUiThread(() ->
                        Toast.makeText(this, "操作流程执行完成", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "错误: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
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

    //sdsadada

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}