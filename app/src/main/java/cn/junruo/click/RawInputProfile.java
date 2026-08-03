package cn.junruo.click;

import android.view.Surface;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RawInputProfile {
    private static final Pattern DEVICE_PATTERN = Pattern.compile("(/dev/input/event\\d+)");
    private static final Pattern RANGE_PATTERN = Pattern.compile("min\\s+(-?\\d+).*max\\s+(-?\\d+)");

    final String devicePath;
    final int minX;
    final int maxX;
    final int minY;
    final int maxY;
    final boolean hasSlot;
    final int slot;
    final boolean hasBtnTouch;
    final boolean hasBtnToolFinger;
    final boolean hasPressure;
    final int pressureValue;
    final boolean hasTouchMajor;
    final int touchMajorValue;
    final boolean hasWidthMajor;
    final int widthMajorValue;
    final int maxTrackingId;

    private RawInputProfile(Builder b) {
        devicePath = b.devicePath;
        minX = b.minX;
        maxX = b.maxX;
        minY = b.minY;
        maxY = b.maxY;
        hasSlot = b.hasSlot;
        slot = b.slotMin;
        hasBtnTouch = b.hasBtnTouch;
        hasBtnToolFinger = b.hasBtnToolFinger;
        hasPressure = b.hasPressure;
        pressureValue = activeValue(b.pressureMin, b.pressureMax);
        hasTouchMajor = b.hasTouchMajor;
        touchMajorValue = activeValue(b.touchMajorMin, b.touchMajorMax);
        hasWidthMajor = b.hasWidthMajor;
        widthMajorValue = activeValue(b.widthMajorMin, b.widthMajorMax);
        maxTrackingId = b.trackingMax > 0 ? b.trackingMax : 65535;
    }

    static RawInputProfile detect(String preferredDevice) throws IOException {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "getevent -pl")
                    .redirectErrorStream(true)
                    .start();
            Process runningProcess = process;
            FutureTask<String> readTask = new FutureTask<>(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(runningProcess.getInputStream()))) {
                    StringBuilder output = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                    return output.toString();
                }
            });
            Thread readThread = new Thread(readTask, "raw_input_device_scan");
            readThread.start();
            String output;
            try {
                output = readTask.get(8, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                runningProcess.destroy();
                readThread.interrupt();
                throw new IOException("Touch device detection timed out", e);
            } catch (ExecutionException e) {
                throw new IOException("Touch device detection failed", e.getCause());
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("getevent exited with " + exitCode);
            }
            return parseCapabilities(output, preferredDevice);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Touch device detection interrupted", e);
        } finally {
            try { if (process != null) process.destroy(); } catch (Exception ignored) {}
        }
    }

    static RawInputProfile parseCapabilities(String output, String preferredDevice) throws IOException {
        List<RawInputProfile> profiles = new ArrayList<>();
        Builder current = null;
        String[] lines = output == null ? new String[0] : output.split("\\r?\\n");
        for (String line : lines) {
            Matcher deviceMatcher = DEVICE_PATTERN.matcher(line);
            if (line.contains("add device") && deviceMatcher.find()) {
                addIfSupported(profiles, current);
                current = new Builder(deviceMatcher.group(1));
                continue;
            }
            if (current == null) continue;
            current.readCapability(line);
        }
        addIfSupported(profiles, current);

        if (preferredDevice != null && DEVICE_PATTERN.matcher(preferredDevice.trim()).matches()) {
            String preferred = preferredDevice.trim();
            for (RawInputProfile profile : profiles) {
                if (profile.devicePath.equals(preferred)) return profile;
            }
        }
        if (!profiles.isEmpty()) return profiles.get(0);
        throw new IOException("No supported Type-B touch device found");
    }

    int[] mapScreenPoint(int screenX, int screenY, int screenWidth, int screenHeight,
                         int coordinateRotationMode, int displayRotation) {
        int width = Math.max(1, screenWidth);
        int height = Math.max(1, screenHeight);
        int x = clamp(screenX, 0, width - 1);
        int y = clamp(screenY, 0, height - 1);
        int rotation = effectiveRotation(coordinateRotationMode, displayRotation);
        int rawX;
        int rawY;
        switch (rotation) {
            case Surface.ROTATION_90:
                rawX = scaleScreen(height - 1 - y, height, minX, maxX);
                rawY = scaleScreen(x, width, minY, maxY);
                break;
            case Surface.ROTATION_180:
                rawX = scaleScreen(width - 1 - x, width, minX, maxX);
                rawY = scaleScreen(height - 1 - y, height, minY, maxY);
                break;
            case Surface.ROTATION_270:
                rawX = scaleScreen(y, height, minX, maxX);
                rawY = scaleScreen(width - 1 - x, width, minY, maxY);
                break;
            case Surface.ROTATION_0:
            default:
                rawX = scaleScreen(x, width, minX, maxX);
                rawY = scaleScreen(y, height, minY, maxY);
                break;
        }
        return new int[]{clamp(rawX, minX, maxX), clamp(rawY, minY, maxY)};
    }

    String daemonArguments() {
        return devicePath + " " + bool(hasSlot) + " " + slot + " "
                + bool(hasBtnTouch) + " " + bool(hasBtnToolFinger) + " "
                + bool(hasPressure) + " " + pressureValue + " "
                + bool(hasTouchMajor) + " " + touchMajorValue + " "
                + bool(hasWidthMajor) + " " + widthMajorValue + " "
                + minX + " " + maxX + " " + minY + " " + maxY + " " + maxTrackingId;
    }

    String description() {
        int slash = devicePath.lastIndexOf('/');
        String name = slash >= 0 ? devicePath.substring(slash + 1) : devicePath;
        return name + " / Type-B / X " + minX + "-" + maxX + " / Y " + minY + "-" + maxY;
    }

    private static int effectiveRotation(int mode, int displayRotation) {
        if (mode == 1) return Surface.ROTATION_0;
        if (mode == 2) {
            if (displayRotation == Surface.ROTATION_90) return Surface.ROTATION_270;
            if (displayRotation == Surface.ROTATION_270) return Surface.ROTATION_90;
        }
        return displayRotation;
    }

    private static int scaleScreen(int value, int displaySize, int rawMin, int rawMax) {
        int range = Math.max(0, rawMax - rawMin);
        return rawMin + Math.round(value * (range / (float) Math.max(1, displaySize)));
    }

    private static int activeValue(int min, int max) {
        if (max <= min) return Math.max(1, max);
        return clamp(min + Math.max(1, (max - min) / 2), min, max);
    }

    private static void addIfSupported(List<RawInputProfile> profiles, Builder builder) {
        if (builder != null && builder.isSupported()) profiles.add(new RawInputProfile(builder));
    }

    private static int bool(boolean value) { return value ? 1 : 0; }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static final class Builder {
        final String devicePath;
        boolean hasPositionX;
        boolean hasPositionY;
        int minX;
        int maxX;
        int minY;
        int maxY;
        boolean hasTrackingId;
        int trackingMax;
        boolean hasSlot;
        int slotMin;
        boolean hasBtnTouch;
        boolean hasBtnToolFinger;
        boolean hasPressure;
        int pressureMin;
        int pressureMax;
        boolean hasTouchMajor;
        int touchMajorMin;
        int touchMajorMax;
        boolean hasWidthMajor;
        int widthMajorMin;
        int widthMajorMax;

        Builder(String devicePath) {
            this.devicePath = devicePath;
        }

        void readCapability(String line) {
            if (line.contains("ABS_MT_POSITION_X")) {
                int[] range = parseRange(line);
                if (range != null) {
                    hasPositionX = true;
                    minX = range[0];
                    maxX = range[1];
                }
            } else if (line.contains("ABS_MT_POSITION_Y")) {
                int[] range = parseRange(line);
                if (range != null) {
                    hasPositionY = true;
                    minY = range[0];
                    maxY = range[1];
                }
            } else if (line.contains("ABS_MT_TRACKING_ID")) {
                hasTrackingId = true;
                int[] range = parseRange(line);
                if (range != null) trackingMax = range[1];
            } else if (line.contains("ABS_MT_SLOT")) {
                hasSlot = true;
                int[] range = parseRange(line);
                if (range != null) slotMin = range[0];
            } else if (line.contains("ABS_MT_PRESSURE")) {
                int[] range = parseRange(line);
                if (range != null) {
                    hasPressure = true;
                    pressureMin = range[0];
                    pressureMax = range[1];
                }
            } else if (line.contains("ABS_MT_TOUCH_MAJOR")) {
                int[] range = parseRange(line);
                if (range != null) {
                    hasTouchMajor = true;
                    touchMajorMin = range[0];
                    touchMajorMax = range[1];
                }
            } else if (line.contains("ABS_MT_WIDTH_MAJOR")) {
                int[] range = parseRange(line);
                if (range != null) {
                    hasWidthMajor = true;
                    widthMajorMin = range[0];
                    widthMajorMax = range[1];
                }
            }
            if (line.contains("BTN_TOUCH")) hasBtnTouch = true;
            if (line.contains("BTN_TOOL_FINGER")) hasBtnToolFinger = true;
        }

        boolean isSupported() {
            return devicePath != null && DEVICE_PATTERN.matcher(devicePath).matches()
                    && hasPositionX && hasPositionY && hasTrackingId && hasSlot && slotMin == 0
                    && maxX > minX && maxY > minY;
        }

        private static int[] parseRange(String line) {
            Matcher matcher = RANGE_PATTERN.matcher(line);
            if (!matcher.find()) return null;
            try {
                return new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
