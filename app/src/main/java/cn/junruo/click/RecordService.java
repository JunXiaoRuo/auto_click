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
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioFocusRequest;
import android.media.session.PlaybackState;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.view.KeyEvent;
import androidx.core.app.NotificationCompat;
import android.util.Log;

import java.util.ArrayList;

public class RecordService extends Service {
    private static final String TAG = "RecordService";
    private WindowManager windowManager;
    private FrameLayout floatView;
    private boolean recording = false;
    private EventRecorder recorder;
    private String touchDevice;
    private int maxX;
    private int maxY;
    private WindowManager.LayoutParams ballParams;
    private android.widget.TextView ballView;
    private android.media.session.MediaSession mediaSession;
    private boolean keepAliveActive = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand flags=" + flags + ", keepAlive=" + (intent!=null && intent.getBooleanExtra("keep_alive", false)) + ", stopKeepAlive=" + (intent!=null && intent.getBooleanExtra("stop_keep_alive", false)) + ", stopOverlay=" + (intent!=null && intent.getBooleanExtra("stop_overlay", false)));
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
            return START_STICKY;
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
            } catch (Exception ignored) {}
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
        touchDevice = intent != null ? intent.getStringExtra("touch_device") : null;
        maxX = intent != null ? intent.getIntExtra("max_x", 0) : 0;
        maxY = intent != null ? intent.getIntExtra("max_y", 0) : 0;

        showFloatingBall();
        return START_STICKY;
    }

    private void showFloatingBall() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatView = new FrameLayout(this);
        ballView = new android.widget.TextView(this);
        ballView.setText("录制");
        ballView.setTextColor(Color.WHITE);
        ballView.setTextSize(14);
        int size = dp(56);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER;
        ballView.setLayoutParams(lp);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#5500BCD4"));
        bg.setCornerRadius(size/2f);
        ballView.setBackground(bg);
        ballView.setGravity(Gravity.CENTER);
        floatView.addView(ballView);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
                            toggleRecording(ballView);
                        }
                        return true;
                }
                return false;
            }
        });
        windowManager.addView(floatView, params);
        floatView.setFocusable(true);
        floatView.setFocusableInTouchMode(true);
        floatView.requestFocus();
        floatView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && !recording) {
                    toggleRecording(ballView);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && recording) {
                    toggleRecording(ballView);
                    return true;
                }
            }
            return false;
        });
        updateExcludeRect(params, floatView);
        Toast.makeText(this, "点击悬浮球开始/结束录制", Toast.LENGTH_SHORT).show();

        try {
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
        } catch (Exception ignored) {}

        // no foreground for overlay
    }

    private void updateExcludeRect(WindowManager.LayoutParams params, View v) {
        if (v == null || recorder == null) return;
        v.post(() -> {
            int w = v.getWidth();
            int h = v.getHeight();
            recorder.setExcludeRect(params.x, params.y, Math.max(w, 120), Math.max(h, 120));
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
                try { done.setPackage(getPackageName()); } catch (Exception ignored) {}
                done.putExtra("operations_json", Operation.toJsonArray(ops));
                try { done.putExtra("debug_log", recorder.getDebugLog()); } catch (Exception ignored) {}
                sendBroadcast(done);
            }
            removeFloatingBall();
            if (!keepAliveActive) {
                try { stopForeground(true); } catch (Exception ignored) {}
                stopSelf();
            }
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
        try {
            if (mediaSession != null) {
                mediaSession.setActive(false);
                mediaSession.release();
            }
        } catch (Exception ignored) {}
        if (!keepAliveActive) {
            try { stopForeground(true); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private void startForegroundWithText(String text) {
        try {
            String channelId = "record_channel";
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(channelId, "录制服务", NotificationManager.IMPORTANCE_HIGH);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, channelId)
                    .setContentTitle("自动点击")
                    .setContentText(text)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, b.build());
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
}