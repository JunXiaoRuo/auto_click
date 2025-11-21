package cn.junruo.click;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.ArrayList;

public class RecordService extends Service {
    private WindowManager windowManager;
    private FrameLayout floatView;
    private boolean recording = false;
    private EventRecorder recorder;
    private String touchDevice;
    private int maxX;
    private int maxY;
    private WindowManager.LayoutParams ballParams;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (floatView != null) {
            Toast.makeText(this, "悬浮球已开启", Toast.LENGTH_SHORT).show();
            return START_STICKY;
        }
        touchDevice = intent != null ? intent.getStringExtra("touch_device") : null;
        maxX = intent != null ? intent.getIntExtra("max_x", 0) : 0;
        maxY = intent != null ? intent.getIntExtra("max_y", 0) : 0;

        showFloatingBall();
        return START_STICKY;
    }

    private void showFloatingBall() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatView = new FrameLayout(this);
        android.widget.TextView ball = new android.widget.TextView(this);
        ball.setText("录制");
        ball.setTextColor(Color.WHITE);
        ball.setTextSize(14);
        int size = dp(56);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER;
        ball.setLayoutParams(lp);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#5500BCD4"));
        bg.setCornerRadius(size/2f);
        ball.setBackground(bg);
        ball.setGravity(Gravity.CENTER);
        floatView.addView(ball);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 50;
        params.y = 200;
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
                            toggleRecording(ball);
                        }
                        return true;
                }
                return false;
            }
        });
        windowManager.addView(floatView, params);
        updateExcludeRect(params, floatView);
        Toast.makeText(this, "点击悬浮球开始/结束录制", Toast.LENGTH_SHORT).show();
    }

    private void updateExcludeRect(WindowManager.LayoutParams params, View v) {
        v.post(() -> {
            int w = v.getWidth();
            int h = v.getHeight();
            if (recorder != null) {
                recorder.setExcludeRect(params.x, params.y, Math.max(w, 120), Math.max(h, 120));
            }
        });
    }

    private void toggleRecording(android.widget.TextView ball) {
        if (!recording) {
            android.graphics.drawable.GradientDrawable bg = (android.graphics.drawable.GradientDrawable) ball.getBackground();
            bg.setColor(Color.parseColor("#AAE91E63"));
            ball.setText("结束");
            recorder = new EventRecorder(getApplicationContext(), touchDevice, maxX, maxY);
            recorder.start();
            if (ballParams != null) updateExcludeRect(ballParams, floatView);
            recording = true;
            Toast.makeText(this, "开始录制屏幕操作", Toast.LENGTH_SHORT).show();
        } else {
            android.graphics.drawable.GradientDrawable bg = (android.graphics.drawable.GradientDrawable) ball.getBackground();
            bg.setColor(Color.parseColor("#5500BCD4"));
            ball.setText("录制");
            recording = false;
            if (recorder != null) {
                recorder.suppressNextGesture();
                recorder.stop();
                ArrayList<Operation> ops = recorder.getRecordedOperations();
                Intent done = new Intent(MainActivity.ACTION_RECORDING_COMPLETE);
                done.putExtra("operations_json", Operation.toJsonArray(ops));
                try { done.putExtra("debug_log", recorder.getDebugLog()); } catch (Exception ignored) {}
                sendBroadcast(done);
            }
            removeFloatingBall();
            stopSelf();
        }
    }

    private void removeFloatingBall() {
        if (windowManager != null && floatView != null) {
            try { windowManager.removeView(floatView); } catch (Exception ignored) {}
        }
        floatView = null;
    }

    @Override
    public void onDestroy() {
        removeFloatingBall();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }
}