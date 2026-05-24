package cn.junruo.click;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;

class EventRecorder {
    private static final int ROTATION_MODE_AUTO = 0;
    private static final int ROTATION_MODE_OFF = 1;
    private static final int ROTATION_MODE_REVERSE_LANDSCAPE = 2;
    private final Context context;
    private final String devicePath;
    private final int maxX;
    private final int maxY;
    private final int coordinateRotationMode;
    private Process process;
    private Thread thread;
    private volatile boolean running = false;
    private final ArrayList<Operation> recorded = new ArrayList<>();

    private float screenW;
    private float screenH;
    private int displayRotation = android.view.Surface.ROTATION_0;
    private volatile boolean suppressNextGesture = false;
    private int exLeft = -1, exTop = -1, exRight = -1, exBottom = -1;
    private int currentSlot = 0;
    private int prevX = -1, prevY = -1;
    private int motionSamples = 0;
    private int motionThreshold = 2;
    private int synSamples = 0;
    private int synThreshold = 3;
    private int longPressDetectMs = 500;
    private int smallMoveDist2 = 400;
    private final StringBuilder debug = new StringBuilder();
    private long gestureStartWallMs = 0;
    private long lastEndWallMs = -1;
    private StringBuilder rawSegment;
    private boolean fallbackNoXY = true;

    EventRecorder(Context ctx, String devicePath, int maxX, int maxY, int coordinateRotationMode) {
        this.context = ctx;
        this.devicePath = devicePath;
        this.maxX = maxX;
        this.maxY = maxY;
        this.coordinateRotationMode = coordinateRotationMode;
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences("ClickConfig", android.content.Context.MODE_PRIVATE);
            longPressDetectMs = sp.getInt("long_press_duration", 500);
            fallbackNoXY = sp.getBoolean("fallback_no_xy", true);
            int moveTolPx = sp.getInt("move_tolerance_px", 20);
            smallMoveDist2 = Math.max(0, moveTolPx) * Math.max(0, moveTolPx);
            motionThreshold = sp.getInt("motion_threshold", 9999);
        } catch (Exception ignored) {}
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        try {
            if (wm != null) {
                android.view.Display display = wm.getDefaultDisplay();
                if (display != null) displayRotation = display.getRotation();
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
                    screenW = b.width();
                    screenH = b.height();
                } else {
                    DisplayMetrics dm = new DisplayMetrics();
                    if (display != null) {
                        display.getRealMetrics(dm);
                        screenW = dm.widthPixels;
                        screenH = dm.heightPixels;
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.view.Display d = ctx.getDisplay();
                    if (d != null) displayRotation = d.getRotation();
                }
            }
        } catch (Throwable ignored) {}
        if (screenW <= 0 || screenH <= 0) {
            try {
                DisplayMetrics dm2 = ctx.getResources().getDisplayMetrics();
                screenW = dm2.widthPixels;
                screenH = dm2.heightPixels;
            } catch (Throwable ignored) {}
        }
    }

    void start() {
        running = true;
        thread = new Thread(this::runLoop);
        thread.start();
    }

    void stop() {
        running = false;
        try {
            if (process != null) process.destroy();
        } catch (Exception ignored) {}
    }

    ArrayList<Operation> getRecordedOperations() { return recorded; }
    String getDebugLog() { return debug.toString(); }

    void setExcludeRect(int left, int top, int width, int height) {
        this.exLeft = left;
        this.exTop = top;
        this.exRight = left + width;
        this.exBottom = top + height;
    }

    void suppressNextGesture() { this.suppressNextGesture = true; }

    private void runLoop() {
        try {
            String cmd = "su -c getevent -lt" + (devicePath != null && !devicePath.isEmpty() ? (" " + devicePath) : "");
            process = Runtime.getRuntime().exec(cmd);
            BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));

            int lastX = -1, lastY = -1;
            int rawLastX = -1, rawLastY = -1;
            int startX = -1, startY = -1;
            long gestureStartMs = 0;
            long lastEndMs = -1;
            boolean touching = false;
            boolean skipThisGesture = false;
            int maxDist2 = 0;

            String line;
            while (running && (line = br.readLine()) != null) {
                long ts = parseTimestampMs(line);
                // Example: [  327.549005] /dev/input/event3: EV_ABS ABS_MT_POSITION_X 0000039a
                if (line.contains("EV_ABS") && line.contains("ABS_MT_POSITION_X")) {
                    int value = parseHexOrDecValue(line);
                    rawLastX = value;
                    int[] point = mapRawPoint(rawLastX, rawLastY);
                    lastX = point[0];
                    lastY = point[1];
                    if (touching) {
                        if ((startX < 0 || startY < 0) && lastX >= 0 && lastY >= 0) {
                            startX = lastX;
                            startY = lastY;
                            if (gestureStartMs <= 0) gestureStartMs = ts > 0 ? ts : System.currentTimeMillis();
                            if (gestureStartWallMs <= 0) gestureStartWallMs = System.currentTimeMillis();
                            skipThisGesture = isInExclude(startX, startY) || skipThisGesture;
                        }
                        if (startX >= 0 && startY >= 0 && lastX >= 0 && lastY >= 0) {
                            int dx = lastX - startX;
                            int dy = lastY - startY;
                            int d2 = dx*dx + dy*dy;
                            if (d2 > maxDist2) maxDist2 = d2;
                        }
                        if (rawSegment != null) rawSegment.append(line).append('\n');
                    }
                } else if (line.contains("EV_ABS") && line.contains("ABS_MT_POSITION_Y")) {
                    int value = parseHexOrDecValue(line);
                    rawLastY = value;
                    int[] point = mapRawPoint(rawLastX, rawLastY);
                    lastX = point[0];
                    lastY = point[1];
                    if (touching) {
                        if ((startX < 0 || startY < 0) && lastX >= 0 && lastY >= 0) {
                            startX = lastX;
                            startY = lastY;
                            if (gestureStartMs <= 0) gestureStartMs = ts > 0 ? ts : System.currentTimeMillis();
                            if (gestureStartWallMs <= 0) gestureStartWallMs = System.currentTimeMillis();
                            skipThisGesture = isInExclude(startX, startY) || skipThisGesture;
                        }
                        if (startX >= 0 && startY >= 0 && lastX >= 0 && lastY >= 0) {
                            int dx = lastX - startX;
                            int dy = lastY - startY;
                            int d2 = dx*dx + dy*dy;
                            if (d2 > maxDist2) maxDist2 = d2;
                        }
                        if (rawSegment != null) rawSegment.append(line).append('\n');
                    }
                } else if (line.contains("EV_ABS") && line.contains("ABS X")) {
                    int value = parseHexOrDecValue(line);
                    rawLastX = value;
                    int[] point = mapRawPoint(rawLastX, rawLastY);
                    lastX = point[0];
                    lastY = point[1];
                    if (touching) {
                        if ((startX < 0 || startY < 0) && lastX >= 0 && lastY >= 0) {
                            startX = lastX;
                            startY = lastY;
                            if (gestureStartMs <= 0) gestureStartMs = ts > 0 ? ts : System.currentTimeMillis();
                            if (gestureStartWallMs <= 0) gestureStartWallMs = System.currentTimeMillis();
                            skipThisGesture = isInExclude(startX, startY) || skipThisGesture;
                        }
                        if (startX >= 0 && startY >= 0 && lastX >= 0 && lastY >= 0) {
                            int dx = lastX - startX;
                            int dy = lastY - startY;
                            int d2 = dx*dx + dy*dy;
                            if (d2 > maxDist2) maxDist2 = d2;
                        }
                        if (rawSegment != null) rawSegment.append(line).append('\n');
                    }
                } else if (line.contains("EV_ABS") && line.contains("ABS Y")) {
                    int value = parseHexOrDecValue(line);
                    rawLastY = value;
                    int[] point = mapRawPoint(rawLastX, rawLastY);
                    lastX = point[0];
                    lastY = point[1];
                    if (touching) {
                        if ((startX < 0 || startY < 0) && lastX >= 0 && lastY >= 0) {
                            startX = lastX;
                            startY = lastY;
                            if (gestureStartMs <= 0) gestureStartMs = ts > 0 ? ts : System.currentTimeMillis();
                            if (gestureStartWallMs <= 0) gestureStartWallMs = System.currentTimeMillis();
                            skipThisGesture = isInExclude(startX, startY) || skipThisGesture;
                        }
                        if (startX >= 0 && startY >= 0 && lastX >= 0 && lastY >= 0) {
                            int dx = lastX - startX;
                            int dy = lastY - startY;
                            int d2 = dx*dx + dy*dy;
                            if (d2 > maxDist2) maxDist2 = d2;
                        }
                        if (rawSegment != null) rawSegment.append(line).append('\n');
                    }
                } else if (line.contains("EV_KEY") && line.contains("BTN_TOUCH")) {
                    int v = parseHexOrDecValue(line);
                    boolean isDownTok = line.contains("DOWN");
                    boolean isUpTok = line.contains("UP");
                    if ((isDownTok || v == 1) && !touching) {
                        touching = true;
                        startX = -1;
                        startY = -1;
                        gestureStartMs = ts > 0 ? ts : System.currentTimeMillis();
                        gestureStartWallMs = System.currentTimeMillis();
                        maxDist2 = 0;
                        skipThisGesture = suppressNextGesture;
                        suppressNextGesture = false;
                        prevX = lastX;
                        prevY = lastY;
                        lastX = -1;
                        lastY = -1;
                        rawLastX = -1;
                        rawLastY = -1;
                        motionSamples = 0;
                        synSamples = 0;
                        debug.append("BEGIN ts=").append(gestureStartMs).append(" x=").append(startX).append(" y=").append(startY).append('\n');
                        rawSegment = new StringBuilder();
                        rawSegment.append(line).append('\n');
                    } else if ((isUpTok || v == 0) && touching) {
                        touching = false;
                        long gestureEndMs = ts > 0 ? ts : System.currentTimeMillis();
                        long gestureEndWallMs = System.currentTimeMillis();
                        int delay = (int) Math.max(0, lastEndMs >= 0 ? (gestureStartMs - lastEndMs) : 0);
                        lastEndMs = gestureEndMs;
                        long wallDur = Math.max(0, gestureEndWallMs - gestureStartWallMs);
                        if (!skipThisGesture) {
                            boolean fallbackUsed = false;
                            if ((startX < 0 || startY < 0) && fallbackNoXY && prevX >= 0 && prevY >= 0) {
                                startX = prevX;
                                startY = prevY;
                                fallbackUsed = true;
                            }
                            long duration = gestureEndMs - gestureStartMs;
                            long effDur = duration > 0 ? duration : wallDur;
                            if (fallbackUsed && startX >= 0 && startY >= 0) {
                                String label;
                                if (lastX < 0 || lastY < 0) {
                                    lastX = startX;
                                    lastY = startY;
                                }
                                if (effDur >= longPressDetectMs) {
                                    label = "LONG_PRESS";
                                    addOpDedup(new Operation(Operation.TYPE_LONG_PRESS, delay, startX, startY, 0, 0));
                                } else {
                                    label = "CLICK";
                                    addOpDedup(new Operation(Operation.TYPE_CLICK, delay, startX, startY, 0, 0));
                                }
                                debug.append("END ts=").append(gestureEndMs)
                                        .append(" dur=").append(duration).append("ms")
                                        .append(" wallDur=").append(wallDur).append("ms")
                                        .append(" dist2=").append(0)
                                        .append(" motionSamples=").append(motionSamples)
                                        .append(" synSamples=").append(synSamples)
                                        .append(" from=(").append(startX).append(',').append(startY).append(") to=(").append(lastX).append(',').append(lastY).append(") => ")
                                        .append(label).append(" lpMs=").append(longPressDetectMs)
                                        .append(" FallbackXY").append('\n');
                            } else if (startX >= 0 && startY >= 0 && lastX >= 0 && lastY >= 0) {
                                String label;
                                int dx = lastX - startX;
                                int dy = lastY - startY;
                                int dist2 = Math.max(maxDist2, dx*dx + dy*dy);
                                boolean movedByDistance = dist2 >= smallMoveDist2;
                                boolean movedBySamples = motionSamples >= motionThreshold;
                                if (!movedByDistance && effDur >= longPressDetectMs) {
                                    label = "LONG_PRESS";
                                    addOpDedup(new Operation(Operation.TYPE_LONG_PRESS, delay, startX, startY, 0, 0));
                                } else if (!(movedByDistance || movedBySamples)) {
                                    label = "CLICK";
                                    addOpDedup(new Operation(Operation.TYPE_CLICK, delay, startX, startY, 0, 0));
                                } else {
                                    label = "SWIPE";
                                    addOpDedup(new Operation(Operation.TYPE_SWIPE, delay, startX, startY, lastX, lastY));
                                }
                                debug.append("END ts=").append(gestureEndMs)
                                        .append(" dur=").append(duration).append("ms")
                                        .append(" wallDur=").append(wallDur).append("ms")
                                        .append(" dist2=").append(dist2)
                                        .append(" motionSamples=").append(motionSamples)
                                        .append(" synSamples=").append(synSamples)
                                        .append(" from=(").append(startX).append(',').append(startY).append(") to=(").append(lastX).append(',').append(lastY).append(") => ")
                                        .append(label).append(" lpMs=").append(longPressDetectMs).append('\n');
                            } else {
                                debug.append("END ts=").append(gestureEndMs)
                                        .append(" dur=").append(duration).append("ms")
                                        .append(" wallDur=").append(wallDur).append("ms NO_XY_DROPPED\n");
                            }
                        }
                        if (rawSegment != null) rawSegment.append(line).append('\n');
                        if (rawSegment != null && rawSegment.length() > 0) {
                            debug.append("RAW\n").append(rawSegment.toString()).append("ENDRAW\n");
                        }
                    }
                } else if (line.contains("EV_ABS") && line.contains("ABS_MT_TRACKING_ID")) {
                    int v = parseHexOrDecValue(line);
                    boolean isEnd = line.toLowerCase().contains("ffffffff") || v == 0xFFFFFFFF;
                    if (!isEnd && !touching) {
                        touching = true;
                        startX = -1;
                        startY = -1;
                        gestureStartMs = ts > 0 ? ts : System.currentTimeMillis();
                        gestureStartWallMs = System.currentTimeMillis();
                        maxDist2 = 0;
                        skipThisGesture = suppressNextGesture;
                        suppressNextGesture = false;
                        prevX = lastX;
                        prevY = lastY;
                        lastX = -1;
                        lastY = -1;
                        rawLastX = -1;
                        rawLastY = -1;
                        motionSamples = 0;
                        synSamples = 0;
                        debug.append("BEGIN ts=").append(gestureStartMs).append(" x=").append(startX).append(" y=").append(startY).append('\n');
                        rawSegment = new StringBuilder();
                        rawSegment.append(line).append('\n');
                    } else if (isEnd && touching) {
                        touching = false;
                        long gestureEndMs = ts > 0 ? ts : System.currentTimeMillis();
                        long gestureEndWallMs = System.currentTimeMillis();
                        int delay = (int) Math.max(0, lastEndMs >= 0 ? (gestureStartMs - lastEndMs) : 0);
                        lastEndMs = gestureEndMs;
                        long wallDur = Math.max(0, gestureEndWallMs - gestureStartWallMs);
                        if (!skipThisGesture) {
                            boolean fallbackUsed = false;
                            if ((startX < 0 || startY < 0) && fallbackNoXY && prevX >= 0 && prevY >= 0) {
                                startX = prevX;
                                startY = prevY;
                                fallbackUsed = true;
                            }
                            long duration = gestureEndMs - gestureStartMs;
                            long effDur = duration > 0 ? duration : wallDur;
                            if (fallbackUsed && startX >= 0 && startY >= 0) {
                                String label;
                                if (lastX < 0 || lastY < 0) {
                                    lastX = startX;
                                    lastY = startY;
                                }
                                if (effDur >= longPressDetectMs) {
                                    label = "LONG_PRESS";
                                    addOpDedup(new Operation(Operation.TYPE_LONG_PRESS, delay, startX, startY, 0, 0));
                                } else {
                                    label = "CLICK";
                                    addOpDedup(new Operation(Operation.TYPE_CLICK, delay, startX, startY, 0, 0));
                                }
                                debug.append("END ts=").append(gestureEndMs)
                                        .append(" dur=").append(duration).append("ms")
                                        .append(" wallDur=").append(wallDur).append("ms")
                                        .append(" dist2=").append(0)
                                        .append(" motionSamples=").append(motionSamples)
                                        .append(" synSamples=").append(synSamples)
                                        .append(" from=(").append(startX).append(',').append(startY).append(") to=(").append(lastX).append(',').append(lastY).append(") => ")
                                        .append(label).append(" lpMs=").append(longPressDetectMs)
                                        .append(" FallbackXY").append('\n');
                            } else if (startX >= 0 && startY >= 0 && lastX >= 0 && lastY >= 0) {
                                String label;
                                int dx = lastX - startX;
                                int dy = lastY - startY;
                                int dist2 = Math.max(maxDist2, dx*dx + dy*dy);
                                boolean movedByDistance = dist2 >= smallMoveDist2;
                                boolean movedBySamples = motionSamples >= motionThreshold;
                                if (!movedByDistance && effDur >= longPressDetectMs) {
                                    label = "LONG_PRESS";
                                    addOpDedup(new Operation(Operation.TYPE_LONG_PRESS, delay, startX, startY, 0, 0));
                                } else if (!(movedByDistance || movedBySamples)) {
                                    label = "CLICK";
                                    addOpDedup(new Operation(Operation.TYPE_CLICK, delay, startX, startY, 0, 0));
                                } else {
                                    label = "SWIPE";
                                    addOpDedup(new Operation(Operation.TYPE_SWIPE, delay, startX, startY, lastX, lastY));
                                }
                                debug.append("END ts=").append(gestureEndMs)
                                        .append(" dur=").append(duration).append("ms")
                                        .append(" wallDur=").append(wallDur).append("ms")
                                        .append(" dist2=").append(dist2)
                                        .append(" motionSamples=").append(motionSamples)
                                        .append(" synSamples=").append(synSamples)
                                        .append(" from=(").append(startX).append(',').append(startY).append(") to=(").append(lastX).append(',').append(lastY).append(") => ")
                                        .append(label).append(" lpMs=").append(longPressDetectMs).append('\n');
                            } else {
                                debug.append("END ts=").append(gestureEndMs)
                                        .append(" dur=").append(duration).append("ms")
                                        .append(" wallDur=").append(wallDur).append("ms NO_XY_DROPPED\n");
                            }
                        }
                        if (rawSegment != null) rawSegment.append(line).append('\n');
                        if (rawSegment != null && rawSegment.length() > 0) {
                            debug.append("RAW\n").append(rawSegment.toString()).append("ENDRAW\n");
                        }
                    }
                } else if (line.contains("EV_ABS") && (line.contains("ABS_MT_POSITION_X") || line.contains("ABS_MT_POSITION_Y") || line.contains("ABS X") || line.contains("ABS Y"))) {
                    if (touching) {
                        if ((startX < 0 || startY < 0) && lastX >= 0 && lastY >= 0) {
                            startX = lastX;
                            startY = lastY;
                            if (gestureStartMs <= 0) gestureStartMs = ts > 0 ? ts : System.currentTimeMillis();
                            if (gestureStartWallMs <= 0) gestureStartWallMs = System.currentTimeMillis();
                            skipThisGesture = isInExclude(startX, startY) || skipThisGesture;
                        }
                        if (startX >= 0 && startY >= 0 && lastX >= 0 && lastY >= 0) {
                            int dx = lastX - startX;
                            int dy = lastY - startY;
                            int d2 = dx*dx + dy*dy;
                            if (d2 > maxDist2) maxDist2 = d2;
                            // 采样计数在 SYN_REPORT 触发
                        }
                        if (rawSegment != null) rawSegment.append(line).append('\n');
                    }
                } else if (line.contains("EV_ABS") && line.contains("ABS_MT_SLOT")) {
                    currentSlot = parseHexOrDecValue(line);
                    if (touching && rawSegment != null) rawSegment.append(line).append('\n');
                } else if (line.contains("EV_SYN") && line.contains("SYN_REPORT")) {
                    if (touching && startX >= 0 && startY >= 0 && lastX >= 0 && lastY >= 0) {
                        int dx = lastX - startX;
                        int dy = lastY - startY;
                        int d2 = dx*dx + dy*dy;
                        if (d2 > maxDist2) maxDist2 = d2;
                        if (lastX != prevX || lastY != prevY) {
                            motionSamples++;
                            prevX = lastX;
                            prevY = lastY;
                        }
                        synSamples++;
                    }
                    if (touching && rawSegment != null) rawSegment.append(line).append('\n');
                } else {
                    if (touching && rawSegment != null) rawSegment.append(line).append('\n');
                }
            }
            br.close();
        } catch (Exception ignored) {}
    }

    private boolean isInExclude(int x, int y) {
        return exLeft >= 0 && x >= exLeft && x <= exRight && y >= exTop && y <= exBottom;
    }

    private void addOpDedup(Operation op) {
        if (!recorded.isEmpty()) {
            Operation last = recorded.get(recorded.size() - 1);
            if (last.type == op.type && last.x1 == op.x1 && last.y1 == op.y1 && last.x2 == op.x2 && last.y2 == op.y2) {
                return;
            }
        }
        recorded.add(op);
    }

    private int[] mapRawPoint(int rawX, int rawY) {
        if (rawX < 0 || rawY < 0) return new int[]{-1, -1};
        if (maxX <= 0 || maxY <= 0 || screenW <= 0 || screenH <= 0) {
            return new int[]{rawX, rawY};
        }

        int w = (int) screenW;
        int h = (int) screenH;
        int x;
        int y;
        int rotation = getEffectiveRotation();
        switch (rotation) {
            case android.view.Surface.ROTATION_90:
                x = scale(rawY, maxY, w);
                y = h - 1 - scale(rawX, maxX, h);
                break;
            case android.view.Surface.ROTATION_180:
                x = w - 1 - scale(rawX, maxX, w);
                y = h - 1 - scale(rawY, maxY, h);
                break;
            case android.view.Surface.ROTATION_270:
                x = w - 1 - scale(rawY, maxY, w);
                y = scale(rawX, maxX, h);
                break;
            case android.view.Surface.ROTATION_0:
            default:
                x = scale(rawX, maxX, w);
                y = scale(rawY, maxY, h);
                break;
        }
        return new int[]{clamp(x, 0, w - 1), clamp(y, 0, h - 1)};
    }

    private int getEffectiveRotation() {
        if (coordinateRotationMode == ROTATION_MODE_OFF) {
            return android.view.Surface.ROTATION_0;
        }
        if (coordinateRotationMode == ROTATION_MODE_REVERSE_LANDSCAPE) {
            if (displayRotation == android.view.Surface.ROTATION_90) {
                return android.view.Surface.ROTATION_270;
            }
            if (displayRotation == android.view.Surface.ROTATION_270) {
                return android.view.Surface.ROTATION_90;
            }
        }
        return displayRotation;
    }

    private int scale(int raw, int rawMax, int displaySize) {
        return (int) Math.round(raw * (displaySize / (float) rawMax));
    }

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private int parseHexOrDecValue(String line) {
        String[] parts = line.trim().split(" ");
        String last = parts[parts.length - 1];
        try {
            if (last.startsWith("0x")) return Integer.parseInt(last.substring(2), 16);
            if (last.matches("[0-9a-fA-F]{8}")) return Integer.parseInt(last, 16);
            if (last.trim().matches("\\d+")) return Integer.parseInt(last.trim());
            String digits = last.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) return Integer.parseInt(digits);
            return Integer.MIN_VALUE;
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    private long parseTimestampMs(String line) {
        try {
            int l = line.indexOf('[');
            int r = line.indexOf(']');
            if (l >= 0 && r > l) {
                String ts = line.substring(l + 1, r).trim();
                double seconds = Double.parseDouble(ts);
                return (long) Math.round(seconds * 1000.0);
            }
        } catch (Exception ignored) {}
        return -1;
    }
}
