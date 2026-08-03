package cn.junruo.click;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioFocusRequest;
import android.media.session.PlaybackState;
import android.content.pm.ServiceInfo;
import androidx.core.app.NotificationCompat;
import android.util.Log;
import android.content.SharedPreferences;
import android.text.TextUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import java.util.ArrayList;
import java.util.Random;

public class RecordService extends Service {
    private static final String TAG = "RecordService";
    private static final int CLICK_MODE_FAST = 0;
    private static final int CLICK_MODE_CUSTOM_PRESS = 1;
    private static final int RECORD_IDLE_COLOR = 0xFF0F766E;
    private static final int EXECUTE_IDLE_COLOR = 0xFF2563EB;
    private static final int ACTIVE_COLOR = 0xFFB42318;
    private static volatile boolean recordingOverlayActive;
    private static volatile boolean executionOverlayActive;
    private WindowManager windowManager;
    private FrameLayout floatView;
    private boolean recording = false;
    private EventRecorder recorder;
    private String touchDevice;
    private int maxX;
    private int maxY;
    private int coordinateRotationMode;
    private WindowManager.LayoutParams ballParams;
    private TextView ballView;
    private android.media.session.MediaSession mediaSession;
    private boolean keepAliveActive = false;
    private Process volKeyProc;
    private Thread volKeyThread;
    private boolean overlayExecute = false;
    private boolean executing = false;
    private Thread execThread;
    private volatile boolean execStopRequested = false;
    private View touchTraceView;
    private WindowManager.LayoutParams touchTraceParams;
    private int touchTraceGeneration = 0;

    static boolean isRecordingOverlayActive() {
        return recordingOverlayActive;
    }

    static boolean isExecutionOverlayActive() {
        return executionOverlayActive;
    }

    private static final class FloatingBallLayout extends FrameLayout {
        FloatingBallLayout(Context context) {
            super(context);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand flags=" + flags + ", keepAlive=" + (intent!=null && intent.getBooleanExtra("keep_alive", false)) + ", stopKeepAlive=" + (intent!=null && intent.getBooleanExtra("stop_keep_alive", false)) + ", stopOverlay=" + (intent!=null && intent.getBooleanExtra("stop_overlay", false)));
        if (intent == null) {
            SharedPreferences preferences = getSharedPreferences("ClickConfig", MODE_PRIVATE);
            recordingOverlayActive = false;
            executionOverlayActive = false;
            preferences.edit()
                    .putBoolean("record_overlay_active", false)
                    .putBoolean("exec_overlay_active", false)
                    .apply();
            if (preferences.getBoolean("keep_alive", false)) {
                keepAliveActive = true;
                startForegroundWithText("服务保活中...");
                return START_STICKY;
            }
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        boolean keepAliveOnly = intent != null && intent.getBooleanExtra("keep_alive", false);
        if (keepAliveOnly) {
            keepAliveActive = true;
            startForegroundWithText("服务保活中...");
            Log.i(TAG, "Started foreground keep-alive");
            return START_STICKY;
        }
        boolean stopKeepAlive = intent != null && intent.getBooleanExtra("stop_keep_alive", false);
        if (stopKeepAlive) {
            keepAliveActive = false;
            try { stopForeground(true); } catch (Exception ignored) {}
            Log.i(TAG, "Stopped keep-alive foreground");
            if (floatView == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        boolean stopOverlay = intent != null && intent.getBooleanExtra("stop_overlay", false);
        if (stopOverlay) {
            boolean cancel = intent != null && intent.getBooleanExtra("cancel", true);
            try {
                if (recorder != null) {
                    recorder.suppressNextGesture();
                    recorder.stop();
                    if (!cancel) {
                        ArrayList<Operation> ops = recorder.getRecordedOperations();
                        Intent done = new Intent(MainActivity.ACTION_RECORDING_COMPLETE);
                        try { done.setPackage(getPackageName()); } catch (Exception ignored) {}
                        done.putExtra("operations_json", Operation.toJsonArray(ops));
                        try { done.putExtra("debug_log", recorder.getDebugLog()); } catch (Exception ignored) {}
                        sendBroadcast(done);
                    }
                }
                if (executing) {
                    execStopRequested = true;
                    try { if (execThread != null) execThread.interrupt(); } catch (Exception ignored) {}
                    executing = false;
                }
            } catch (Exception ignored) {}
            recorder = null;
            recording = false;
            overlayExecute = false;
            recordingOverlayActive = false;
            executionOverlayActive = false;
            getSharedPreferences("ClickConfig", MODE_PRIVATE).edit()
                    .putBoolean("record_overlay_active", false)
                    .putBoolean("exec_overlay_active", false)
                    .apply();
            removeFloatingBall();
            if (!keepAliveActive) {
                try { stopForeground(true); } catch (Exception ignored) {}
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (floatView != null) {
            Toast.makeText(this, "悬浮球已开启", Toast.LENGTH_SHORT).show();
            return START_STICKY;
        }
        boolean execOverlay = intent != null && intent.getBooleanExtra("exec_overlay", false);
        if (execOverlay) {
            overlayExecute = true;
            executionOverlayActive = true;
            recordingOverlayActive = false;
            getSharedPreferences("ClickConfig", MODE_PRIVATE).edit()
                    .putBoolean("exec_overlay_active", true)
                    .apply();
            showExecuteBall();
            boolean autoStart = intent != null && intent.getBooleanExtra("auto_start_exec", false);
            if (autoStart && ballView != null) {
                ballView.post(() -> toggleExecute(ballView));
            }
            return START_STICKY;
        }

        touchDevice = intent != null ? intent.getStringExtra("touch_device") : null;
        maxX = intent != null ? intent.getIntExtra("max_x", 0) : 0;
        maxY = intent != null ? intent.getIntExtra("max_y", 0) : 0;
        coordinateRotationMode = intent != null ? intent.getIntExtra("coordinate_rotation_mode", 0) : 0;

        overlayExecute = false;
        recordingOverlayActive = true;
        executionOverlayActive = false;
        getSharedPreferences("ClickConfig", MODE_PRIVATE).edit()
                .putBoolean("record_overlay_active", true)
                .apply();
        showFloatingBall();
        return START_STICKY;
    }

    private FrameLayout createFloatingBall(String label, int color, String contentDescription) {
        FloatingBallLayout container = new FloatingBallLayout(this);
        TextView ball = new TextView(this);
        int size = dp(56);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER;
        ball.setLayoutParams(lp);
        ball.setGravity(Gravity.CENTER);
        ball.setIncludeFontPadding(false);
        ball.setTextColor(Color.WHITE);
        ball.setTextSize(14);
        ball.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ball.setElevation(dp(8));
        }
        container.setContentDescription(contentDescription);
        container.addView(ball);
        ballView = ball;
        setBallState(ball, label, color);
        return container;
    }

    private void setBallState(TextView ball, String label, int color) {
        if (ball == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        background.setStroke(Math.max(1, dp(2)), 0xB3FFFFFF);
        ball.setBackground(background);
        ball.setText(label);
    }

    private void showFloatingBall() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatView = createFloatingBall("录制", RECORD_IDLE_COLOR, "录制悬浮按钮");
        floatView.setOnClickListener(v -> toggleRecording(ballView));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(120);
        ballParams = params;

        floatView.setOnTouchListener(new View.OnTouchListener() {
            private int lastX, lastY;
            private float touchX, touchY;
            private long downTime;
            private int touchSlop = dp(12);
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        lastX = params.x;
                        lastY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        downTime = System.currentTimeMillis();
                        if (recording && recorder != null) {
                            recorder.suppressNextGesture();
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        params.x = lastX + (int)(event.getRawX() - touchX);
                        params.y = lastY + (int)(event.getRawY() - touchY);
                        try { windowManager.updateViewLayout(floatView, params); } catch (Exception ignored) {}
                        updateExcludeRect(params, floatView);
                        ballParams = params;
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        float dx = event.getRawX() - touchX;
                        float dy = event.getRawY() - touchY;
                        long dt = System.currentTimeMillis() - downTime;
                        if (Math.abs(dx) < touchSlop && Math.abs(dy) < touchSlop && dt < 500) {
                            v.performClick();
                        }
                        return true;
                }
                return false;
            }
        });
        windowManager.addView(floatView, params);
        updateExcludeRect(params, floatView);
        Toast.makeText(this, "点击悬浮球开始/结束录制", Toast.LENGTH_SHORT).show();

        boolean volKeys = getSharedPreferences("ClickConfig", MODE_PRIVATE).getBoolean("volume_keys_control", true);
        if (volKeys) startVolumeKeyMonitor();

        try {
            if (volKeys) {
                mediaSession = new android.media.session.MediaSession(this, "record_session");
                android.media.VolumeProvider vp = new android.media.VolumeProvider(android.media.VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                    @Override
                    public void onAdjustVolume(int direction) {
                        if (direction > 0 && !recording) {
                            toggleRecording(ballView);
                        } else if (direction < 0 && recording) {
                            toggleRecording(ballView);
                        }
                    }
                };
                mediaSession.setFlags(android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
                PlaybackState state = new PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
                        .build();
                mediaSession.setPlaybackState(state);
                mediaSession.setPlaybackToRemote(vp);
                mediaSession.setActive(true);

                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                AudioFocusRequest afr = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(attrs)
                            .setOnAudioFocusChangeListener(focusChange -> {})
                            .build();
                }
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        am.requestAudioFocus(afr);
                    }
                }
            }
        } catch (Exception ignored) {}

        // no foreground for overlay
    }

    private void showExecuteBall() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatView = createFloatingBall("执行", EXECUTE_IDLE_COLOR, "执行悬浮按钮");
        floatView.setOnClickListener(v -> toggleExecute(ballView));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(120);
        ballParams = params;

        floatView.setOnTouchListener(new View.OnTouchListener() {
            private int lastX, lastY;
            private float touchX, touchY;
            private long downTime;
            private int touchSlop = dp(12);
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        lastX = params.x;
                        lastY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        downTime = System.currentTimeMillis();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        params.x = lastX + (int)(event.getRawX() - touchX);
                        params.y = lastY + (int)(event.getRawY() - touchY);
                        try { windowManager.updateViewLayout(floatView, params); } catch (Exception ignored) {}
                        ballParams = params;
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        float dx = event.getRawX() - touchX;
                        float dy = event.getRawY() - touchY;
                        long dt = System.currentTimeMillis() - downTime;
                        if (Math.abs(dx) < touchSlop && Math.abs(dy) < touchSlop && dt < 500) {
                            v.performClick();
                        }
                        return true;
                }
                return false;
            }
        });
        windowManager.addView(floatView, params);
        Toast.makeText(this, "点击悬浮球开始/终止执行", Toast.LENGTH_SHORT).show();

        boolean volKeys2 = getSharedPreferences("ClickConfig", MODE_PRIVATE).getBoolean("volume_keys_control", true);
        if (volKeys2) startVolumeKeyMonitor();
    }

    private void updateExcludeRect(WindowManager.LayoutParams params, View v) {
        if (v == null || recorder == null) return;
        v.post(() -> {
            int w = v.getWidth();
            int h = v.getHeight();
            recorder.setExcludeRect(params.x, params.y, Math.max(w, 120), Math.max(h, 120));
        });
    }

    private void toggleRecording(TextView ball) {
        if (!recording) {
            setBallState(ball, "结束", ACTIVE_COLOR);
            recorder = new EventRecorder(getApplicationContext(), touchDevice, maxX, maxY, coordinateRotationMode);
            recorder.start();
            if (ballParams != null) updateExcludeRect(ballParams, floatView);
            recording = true;
            Toast.makeText(this, "开始录制屏幕操作", Toast.LENGTH_SHORT).show();
        } else {
            setBallState(ball, "录制", RECORD_IDLE_COLOR);
            recording = false;
            if (recorder != null) {
                recorder.suppressNextGesture();
                recorder.stop();
                ArrayList<Operation> ops = recorder.getRecordedOperations();
                Intent done = new Intent(MainActivity.ACTION_RECORDING_COMPLETE);
                try { done.setPackage(getPackageName()); } catch (Exception ignored) {}
                done.putExtra("operations_json", Operation.toJsonArray(ops));
                try { done.putExtra("debug_log", recorder.getDebugLog()); } catch (Exception ignored) {}
                sendBroadcast(done);
            }
            removeFloatingBall();
            recordingOverlayActive = false;
            getSharedPreferences("ClickConfig", MODE_PRIVATE).edit()
                    .putBoolean("record_overlay_active", false)
                    .apply();
            if (!keepAliveActive) {
                try { stopForeground(true); } catch (Exception ignored) {}
                stopSelf();
            }
        }
    }

    private void toggleExecute(TextView ball) {
        if (!executing) {
            setBallState(ball, "终止", ACTIVE_COLOR);
            execStopRequested = false;
            executing = true;
            execThread = new Thread(this::runExecute, "execute_thread");
            execThread.start();
            Toast.makeText(this, "开始执行步骤", Toast.LENGTH_SHORT).show();
        } else {
            setBallState(ball, "执行", EXECUTE_IDLE_COLOR);
            execStopRequested = true;
            try { if (execThread != null) execThread.interrupt(); } catch (Exception ignored) {}
            executing = false;
            Toast.makeText(this, "已终止执行", Toast.LENGTH_SHORT).show();
        }
    }

    private void runExecute() {
        Process rootShell = null;
        BufferedWriter rootWriter = null;
        BufferedReader rootReader = null;
        RawInputClient rawInputClient = null;
        try {
            SharedPreferences sp = getSharedPreferences("ClickConfig", MODE_PRIVATE);
            String schemesJson = sp.getString("schemes", "");
            String current = sp.getString("current_scheme", "");
            ArrayList<Scheme> schemes = TextUtils.isEmpty(schemesJson) ? new ArrayList<>() : Scheme.fromJsonArray(schemesJson);
            Scheme scheme = null;
            for (Scheme s : schemes) { if (s.name.equals(current)) { scheme = s; break; } }
            if (scheme == null) return;

            int clickMode = sp.getInt("click_mode", CLICK_MODE_FAST);
            int clickDuration = sp.getInt("click_duration", 50);
            boolean rawInputEnabled = sp.getBoolean("raw_input_enabled", false);
            int longPressDuration = sp.getInt("long_press_duration", 500);
            int swipeDuration = sp.getInt("swipe_duration", 100);
            boolean loopNotify = sp.getBoolean("exec_loop_notify", false);
            int randomOffsetMin = Math.max(0, sp.getInt("random_offset_min", 0));
            int randomOffsetMax = Math.max(randomOffsetMin, sp.getInt("random_offset_max", 5));
            int randomDelayMin = Math.max(0, sp.getInt("random_delay_min", 0));
            int randomDelayMax = Math.max(randomDelayMin, sp.getInt("random_delay_max", 50));
            boolean showTouchTrace = sp.getBoolean("show_touch_trace", false);
            int touchTraceColor = parseTraceColor(sp.getString("touch_trace_color", "#FF4081"), sp.getInt("touch_trace_alpha", 160));
            int[] screenSize = getScreenSize();
            int executeRotationMode = sp.getInt("coordinate_rotation_mode", 0);
            int displayRotation = getDisplayRotation();
            Random random = new Random();

            rootShell = new ProcessBuilder("su").redirectErrorStream(true).start();
            rootWriter = new BufferedWriter(new OutputStreamWriter(rootShell.getOutputStream()));
            rootReader = new BufferedReader(new InputStreamReader(rootShell.getInputStream()));
            if (!TextUtils.isEmpty(scheme.appActivity)) {
                executeRootCommand(rootWriter, rootReader, rootShell,
                        "am start -n " + scheme.appActivity, 10000);
            }

            if (rawInputEnabled && clickMode == CLICK_MODE_FAST) {
                try {
                    RawInputProfile profile = RawInputProfile.detect(sp.getString("touch_device", ""));
                    rawInputClient = RawInputClient.connect(this, profile);
                    notifyRawInputState("高速模式自检通过：" + profile.description());
                } catch (Exception e) {
                    Log.w(TAG, "Raw input startup failed; using standard input", e);
                    if (rawInputClient != null) rawInputClient.close();
                    rawInputClient = null;
                    notifyRawInputState("高速模式不可用，已回退标准点击");
                }
            }

            int loops = Math.max(1, sp.getInt("exec_loop_count", 1));
            boolean forever = sp.getBoolean("exec_loop_forever", false);
            int i = 0;
            while (!execStopRequested && (forever || i < loops)) {
                for (Operation op : scheme.operations) {
                    if (execStopRequested) break;
                    int delay = Math.max(0, op.delay + randomSignedInRange(random, randomDelayMin, randomDelayMax));
                    try { Thread.sleep(delay); } catch (Exception ignored) {}
                    if (execStopRequested) break;
                    try {
                        int[] p1 = randomizePoint(op.x1, op.y1, randomOffsetMin, randomOffsetMax, screenSize, random);
                        int[] p2 = op.type == Operation.TYPE_SWIPE
                                ? randomizePoint(op.x2, op.y2, randomOffsetMin, randomOffsetMax, screenSize, random)
                                : p1;
                        if (showTouchTrace) clearTouchTraceBeforeInput();
                        if (op.type == Operation.TYPE_CLICK) {
                            boolean rawInjected = false;
                            if (rawInputClient != null && clickMode == CLICK_MODE_FAST) {
                                try {
                                    RawInputProfile profile = rawInputClient.getProfile();
                                    int[] rawPoint = profile.mapScreenPoint(
                                            p1[0], p1[1], screenSize[0], screenSize[1],
                                            executeRotationMode, displayRotation);
                                    rawInputClient.tap(rawPoint[0], rawPoint[1]);
                                    rawInjected = true;
                                } catch (Exception e) {
                                    if (execStopRequested) throw e;
                                    Log.w(TAG, "Raw input click failed; falling back", e);
                                    try { rawInputClient.forceRelease(); } catch (Exception ignored) {}
                                    rawInputClient.close();
                                    rawInputClient = null;
                                    notifyRawInputState("高速注入异常，已强制松开并回退标准点击");
                                }
                            }
                            if (!rawInjected) {
                                String clickCommand = clickMode == CLICK_MODE_CUSTOM_PRESS
                                        ? "input swipe " + p1[0] + " " + p1[1] + " " +
                                                p1[0] + " " + p1[1] + " " + clickDuration
                                        : "input tap " + p1[0] + " " + p1[1];
                                long clickTimeout = clickMode == CLICK_MODE_CUSTOM_PRESS
                                        ? Math.max(5000L, clickDuration + 5000L) : 5000L;
                                executeRootCommand(rootWriter, rootReader, rootShell, clickCommand, clickTimeout);
                            }
                            showTouchTrace(showTouchTrace, p1[0], p1[1], touchTraceColor);
                        } else if (op.type == Operation.TYPE_LONG_PRESS) {
                            executeRootCommand(rootWriter, rootReader, rootShell,
                                    "input swipe " + p1[0] + " " + p1[1] + " " + p1[0] + " " + p1[1] + " " + longPressDuration,
                                    Math.max(5000L, longPressDuration + 5000L));
                            showTouchTrace(showTouchTrace, p1[0], p1[1], touchTraceColor);
                        } else {
                            executeRootCommand(rootWriter, rootReader, rootShell,
                                    "input swipe " + p1[0] + " " + p1[1] + " " + p2[0] + " " + p2[1] + " " + swipeDuration,
                                    Math.max(5000L, swipeDuration + 5000L));
                            showTouchTrace(showTouchTrace, p1[0], p1[1], touchTraceColor);
                            showTouchTrace(showTouchTrace, p2[0], p2[1], touchTraceColor);
                        }
                    } catch (Exception e) {
                        if (!execStopRequested) {
                            Log.e(TAG, "Root input command failed", e);
                            execStopRequested = true;
                            new android.os.Handler(getMainLooper()).post(() ->
                                    Toast.makeText(getApplicationContext(),
                                            "\u6267\u884c\u547d\u4ee4\u5931\u8d25\uff0c\u5df2\u505c\u6b62",
                                            Toast.LENGTH_SHORT).show());
                        }
                        break;
                    }
                }
                i++;
                final int doneLoops = i;
                final String totalStr = forever ? "\u221e" : String.valueOf(loops);
                if (loopNotify) {
                    new android.os.Handler(getMainLooper()).post(() -> {
                        Toast.makeText(getApplicationContext(), "\u5df2\u5faa\u73af" + doneLoops + "\u6b21\uff0c\u5171" + totalStr + "\u6b21", Toast.LENGTH_SHORT).show();
                    });
                }
            }
            if (!execStopRequested) {
                new android.os.Handler(getMainLooper()).post(() -> {
                    Toast.makeText(getApplicationContext(), "\u6267\u884c\u5b8c\u6210", Toast.LENGTH_SHORT).show();
                });
                if (scheme.stopAppsEnabled && scheme.appsToStop != null && !scheme.appsToStop.isEmpty()) {
                    try {
                        for (String pkg : scheme.appsToStop) {
                            executeRootCommand(rootWriter, rootReader, rootShell,
                                    "am force-stop " + pkg, 5000);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (rawInputClient != null) rawInputClient.close();
            closeRootShell(rootWriter, rootReader, rootShell);
        }
        TextView ball = ballView;
        if (ball != null) {
            ball.post(() -> {
                try {
                    setBallState(ball, "执行", EXECUTE_IDLE_COLOR);
                } catch (Exception ignored2) {}
            });
        }
        executing = false;
        execStopRequested = false;
    }

    private void notifyRawInputState(String message) {
        new android.os.Handler(getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    private int getDisplayRotation() {
        try {
            WindowManager wm = windowManager != null
                    ? windowManager : (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                return wm.getDefaultDisplay().getRotation();
            }
        } catch (Exception ignored) {}
        return android.view.Surface.ROTATION_0;
    }

    private void executeRootCommand(BufferedWriter writer, BufferedReader reader, Process process,
                                    String command, long timeoutMs) throws Exception {
        if (writer == null || reader == null || !isProcessAlive(process)) {
            throw new java.io.IOException("Root shell is not alive");
        }
        String marker = "__AUTO_CLICK_DONE_" + System.nanoTime() + "__:";
        writer.write(command + "; echo " + marker + "$?");
        writer.newLine();
        writer.flush();

        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (execStopRequested) throw new InterruptedException("Execution stopped");
            if (!isProcessAlive(process)) throw new java.io.IOException("Root shell exited");
            if (reader.ready()) {
                String line = reader.readLine();
                if (line == null) throw new java.io.IOException("Root shell output closed");
                int markerIndex = line.indexOf(marker);
                if (markerIndex >= 0) {
                    String exitText = line.substring(markerIndex + marker.length()).trim();
                    int exitCode;
                    try { exitCode = Integer.parseInt(exitText); }
                    catch (Exception e) { throw new java.io.IOException("Invalid command result: " + line); }
                    if (exitCode != 0) throw new java.io.IOException("Command exited with " + exitCode);
                    return;
                }
            } else {
                Thread.sleep(1);
            }
        }
        throw new java.io.IOException("Root command timed out");
    }

    private boolean isProcessAlive(Process process) {
        if (process == null) return false;
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private void closeRootShell(BufferedWriter writer, BufferedReader reader, Process process) {
        try {
            if (writer != null) {
                writer.write("exit");
                writer.newLine();
                writer.flush();
                writer.close();
            }
        } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (process != null) process.destroy(); } catch (Exception ignored) {}
    }

    private int[] randomizePoint(int x, int y, int offsetMin, int offsetMax, int[] screenSize, Random random) {
        int rx = x + randomSignedInRange(random, offsetMin, offsetMax);
        int ry = y + randomSignedInRange(random, offsetMin, offsetMax);
        if (screenSize != null && screenSize[0] > 0 && screenSize[1] > 0) {
            rx = clamp(rx, 0, screenSize[0] - 1);
            ry = clamp(ry, 0, screenSize[1] - 1);
        }
        return new int[]{rx, ry};
    }

    private int randomSignedInRange(Random random, int min, int max) {
        if (random == null || max <= 0) return 0;
        int lo = Math.max(0, Math.min(min, max));
        int hi = Math.max(lo, max);
        int magnitude = lo + random.nextInt(hi - lo + 1);
        return random.nextBoolean() ? magnitude : -magnitude;
    }

    private int[] getScreenSize() {
        int w = 0, h = 0;
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
                    w = b.width();
                    h = b.height();
                } else {
                    android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                    android.view.Display d = wm.getDefaultDisplay();
                    if (d != null) {
                        d.getRealMetrics(dm);
                        w = dm.widthPixels;
                        h = dm.heightPixels;
                    }
                }
            }
        } catch (Exception ignored) {}
        return new int[]{w, h};
    }

    private int parseTraceColor(String colorText, int alpha) {
        int color;
        try {
            String value = colorText == null ? "" : colorText.trim();
            if (!value.startsWith("#")) value = "#" + value;
            color = Color.parseColor(value);
        } catch (Exception e) {
            color = Color.parseColor("#FF4081");
        }
        int a = clamp(alpha, 0, 255);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private void showTouchTrace(boolean enabled, int x, int y, int color) {
        if (!enabled) return;
        new android.os.Handler(getMainLooper()).post(() -> {
            try {
                WindowManager wm = windowManager != null ? windowManager : (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm == null) return;
                int size = dp(28);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                bg.setColor((color & 0x00FFFFFF) | 0xFF000000);
                bg.setStroke(Math.max(1, dp(2)), Color.WHITE);
                float requestedAlpha = Color.alpha(color) / 255f;
                float safeAlpha = Math.min(1f, Math.max(0f, requestedAlpha));

                if (touchTraceView == null || touchTraceParams == null) {
                    touchTraceView = new View(this);
                    int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    touchTraceParams = new WindowManager.LayoutParams(
                            size,
                            size,
                            type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            android.graphics.PixelFormat.TRANSLUCENT
                    );
                    touchTraceParams.gravity = Gravity.TOP | Gravity.START;
                    touchTraceParams.x = x - size / 2;
                    touchTraceParams.y = y - size / 2;
                    touchTraceParams.alpha = safeAlpha;
                    touchTraceView.setBackground(bg);
                    wm.addView(touchTraceView, touchTraceParams);
                } else {
                    touchTraceView.setBackground(bg);
                    touchTraceParams.x = x - size / 2;
                    touchTraceParams.y = y - size / 2;
                    touchTraceParams.alpha = safeAlpha;
                    wm.updateViewLayout(touchTraceView, touchTraceParams);
                }

                int generation = ++touchTraceGeneration;
                touchTraceView.postDelayed(() -> {
                    if (generation == touchTraceGeneration) removeTouchTrace();
                }, 180);
            } catch (Exception ignored) {}
        });
    }

    private void clearTouchTraceBeforeInput() throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        new android.os.Handler(getMainLooper()).post(() -> {
            try { removeTouchTrace(); }
            finally { latch.countDown(); }
        });
        if (!latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw new java.io.IOException("Timed out while clearing touch trace");
        }
    }

    private void removeTouchTrace() {
        if (touchTraceView != null) {
            try {
                WindowManager wm = windowManager != null
                        ? windowManager
                        : (WindowManager) getSystemService(WINDOW_SERVICE);
                if (wm != null) wm.removeView(touchTraceView);
            } catch (Exception ignored) {}
        }
        touchTraceView = null;
        touchTraceParams = null;
        touchTraceGeneration++;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void removeFloatingBall() {
        removeTouchTrace();
        if (windowManager != null && floatView != null) {
            try { windowManager.removeView(floatView); } catch (Exception ignored) {}
        }
        floatView = null;
        ballView = null;
        ballParams = null;
        stopVolumeKeyMonitor();
    }

    @Override
    public void onDestroy() {
        execStopRequested = true;
        recordingOverlayActive = false;
        executionOverlayActive = false;
        try { if (execThread != null) execThread.interrupt(); } catch (Exception ignored) {}
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            recorder = null;
        }
        getSharedPreferences("ClickConfig", MODE_PRIVATE).edit()
                .putBoolean("record_overlay_active", false)
                .putBoolean("exec_overlay_active", false)
                .apply();
        removeFloatingBall();
        try {
            if (mediaSession != null) {
                mediaSession.setActive(false);
                mediaSession.release();
            }
        } catch (Exception ignored) {}
        if (!keepAliveActive) {
            try { stopForeground(true); } catch (Exception ignored) {}
        }
        stopVolumeKeyMonitor();
        super.onDestroy();
    }

    private void startForegroundWithText(String text) {
        try {
            String channelId = "keep_alive_channel";
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        channelId, "自动化服务", NotificationManager.IMPORTANCE_LOW);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, channelId)
                    .setContentTitle("自动模拟助手")
                    .setContentText(text)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
            Notification n = b.build();
            n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, n);
            }
            Log.i(TAG, "startForeground posted notification on channel '" + channelId + "'");
        } catch (Exception e) { Log.e(TAG, "startForegroundWithText failed", e); }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    private void startVolumeKeyMonitor() {
        stopVolumeKeyMonitor();
        volKeyThread = new Thread(() -> {
            BufferedReader br = null;
            try {
                volKeyProc = Runtime.getRuntime().exec("su -c getevent -lt");
                br = new BufferedReader(new InputStreamReader(volKeyProc.getInputStream()));
                String line;
                while (floatView != null && (line = br.readLine()) != null) {
                    boolean isDown = isDownLine(line);
                    if (!isDown) continue;
                    if (line.contains("EV_KEY") && line.contains("KEY_VOLUMEUP")) {
                        if (overlayExecute) {
                            if (!executing && ballView != null) ballView.post(() -> toggleExecute(ballView));
                        } else {
                            if (!recording && ballView != null) ballView.post(() -> toggleRecording(ballView));
                        }
                    } else if (line.contains("EV_KEY") && line.contains("KEY_VOLUMEDOWN")) {
                        if (overlayExecute) {
                            if (executing && ballView != null) ballView.post(() -> toggleExecute(ballView));
                        } else {
                            if (recording && ballView != null) ballView.post(() -> toggleRecording(ballView));
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Exception ignored) {}
                try { if (volKeyProc != null) volKeyProc.destroy(); } catch (Exception ignored) {}
                volKeyProc = null;
            }
        }, "vol_key_monitor");
        volKeyThread.start();
    }

    private void stopVolumeKeyMonitor() {
        try { if (volKeyProc != null) volKeyProc.destroy(); } catch (Exception ignored) {}
        volKeyProc = null;
        volKeyThread = null;
    }

    private boolean isDownLine(String line) {
        if (line == null) return false;
        if (line.contains("DOWN")) return true;
        if (line.contains("UP")) return false;
        try {
            int idxSp = Math.max(line.lastIndexOf(' '), line.lastIndexOf('\t'));
            if (idxSp >= 0) {
                String last = line.substring(idxSp + 1).trim();
                return Integer.parseInt(last, 16) == 1;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
