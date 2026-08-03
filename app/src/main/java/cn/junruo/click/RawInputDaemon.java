package cn.junruo.click;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class RawInputDaemon {
    private static final int EV_SYN = 0;
    private static final int EV_KEY = 1;
    private static final int EV_ABS = 3;
    private static final int SYN_REPORT = 0;
    private static final int BTN_TOOL_FINGER = 0x145;
    private static final int BTN_TOUCH = 0x14a;
    private static final int ABS_MT_SLOT = 0x2f;
    private static final int ABS_MT_TOUCH_MAJOR = 0x30;
    private static final int ABS_MT_WIDTH_MAJOR = 0x32;
    private static final int ABS_MT_POSITION_X = 0x35;
    private static final int ABS_MT_POSITION_Y = 0x36;
    private static final int ABS_MT_TRACKING_ID = 0x39;
    private static final int ABS_MT_PRESSURE = 0x3a;

    private final RandomAccessFile device;
    private final PrintWriter output;
    private final boolean is64Bit;
    private final boolean hasSlot;
    private final int slot;
    private final boolean hasBtnTouch;
    private final boolean hasBtnToolFinger;
    private final boolean hasPressure;
    private final int pressureValue;
    private final boolean hasTouchMajor;
    private final int touchMajorValue;
    private final boolean hasWidthMajor;
    private final int widthMajorValue;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int maxTrackingId;
    private int trackingId = 1;
    private boolean fingerDown;

    private RawInputDaemon(String[] args, PrintWriter output) throws Exception {
        if (args.length != 16 || !args[0].matches("/dev/input/event\\d+")) {
            throw new IllegalArgumentException("Invalid daemon arguments");
        }
        this.output = output;
        hasSlot = flag(args[1]);
        slot = integer(args[2]);
        hasBtnTouch = flag(args[3]);
        hasBtnToolFinger = flag(args[4]);
        hasPressure = flag(args[5]);
        pressureValue = integer(args[6]);
        hasTouchMajor = flag(args[7]);
        touchMajorValue = integer(args[8]);
        hasWidthMajor = flag(args[9]);
        widthMajorValue = integer(args[10]);
        minX = integer(args[11]);
        maxX = integer(args[12]);
        minY = integer(args[13]);
        maxY = integer(args[14]);
        maxTrackingId = Math.max(1, integer(args[15]));
        String dataModel = System.getProperty("sun.arch.data.model", "");
        String architecture = System.getProperty("os.arch", "");
        is64Bit = "64".equals(dataModel) || architecture.contains("64");
        device = new RandomAccessFile(args[0], "rw");
    }

    public static void main(String[] args) {
        PrintWriter output = new PrintWriter(System.out, true);
        RawInputDaemon daemon = null;
        try {
            daemon = new RawInputDaemon(args, output);
            RawInputDaemon finalDaemon = daemon;
            Runtime.getRuntime().addShutdownHook(new Thread(finalDaemon::safeRelease, "raw-input-release"));
            output.println("READY");
            daemon.runLoop();
        } catch (Throwable t) {
            output.println("FATAL " + safeMessage(t));
        } finally {
            if (daemon != null) daemon.shutdown();
        }
    }

    private void runLoop() throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = input.readLine()) != null) {
            String command = line.trim();
            if (command.isEmpty()) continue;
            if ("EXIT".equals(command)) break;
            try {
                if ("SELFTEST".equals(command) || "RELEASE".equals(command)) {
                    release(true);
                } else if (command.startsWith("TAP ")) {
                    String[] parts = command.split("\\s+");
                    if (parts.length != 3) throw new IllegalArgumentException("Invalid TAP command");
                    tap(integer(parts[1]), integer(parts[2]));
                } else {
                    throw new IllegalArgumentException("Unknown command");
                }
                output.println("OK");
            } catch (Throwable t) {
                safeRelease();
                output.println("ERR " + safeMessage(t));
            }
        }
    }

    private synchronized void tap(int x, int y) throws Exception {
        press(clamp(x, minX, maxX), clamp(y, minY, maxY));
        try {
            Thread.sleep(1);
        } finally {
            release(false);
        }
    }

    private void press(int x, int y) throws Exception {
        if (fingerDown) release(true);
        List<Event> events = new ArrayList<>();
        if (hasSlot) events.add(new Event(EV_ABS, ABS_MT_SLOT, slot));
        events.add(new Event(EV_ABS, ABS_MT_TRACKING_ID, nextTrackingId()));
        events.add(new Event(EV_ABS, ABS_MT_POSITION_X, x));
        events.add(new Event(EV_ABS, ABS_MT_POSITION_Y, y));
        if (hasTouchMajor) events.add(new Event(EV_ABS, ABS_MT_TOUCH_MAJOR, touchMajorValue));
        if (hasWidthMajor) events.add(new Event(EV_ABS, ABS_MT_WIDTH_MAJOR, widthMajorValue));
        if (hasPressure) events.add(new Event(EV_ABS, ABS_MT_PRESSURE, pressureValue));
        if (hasBtnTouch) events.add(new Event(EV_KEY, BTN_TOUCH, 1));
        if (hasBtnToolFinger) events.add(new Event(EV_KEY, BTN_TOOL_FINGER, 1));
        events.add(new Event(EV_SYN, SYN_REPORT, 0));
        writeEvents(events);
        fingerDown = true;
    }

    private synchronized void release(boolean force) throws Exception {
        if (!force && !fingerDown) return;
        List<Event> events = new ArrayList<>();
        if (hasSlot) events.add(new Event(EV_ABS, ABS_MT_SLOT, slot));
        if (hasPressure) events.add(new Event(EV_ABS, ABS_MT_PRESSURE, 0));
        if (hasTouchMajor) events.add(new Event(EV_ABS, ABS_MT_TOUCH_MAJOR, 0));
        if (hasWidthMajor) events.add(new Event(EV_ABS, ABS_MT_WIDTH_MAJOR, 0));
        events.add(new Event(EV_ABS, ABS_MT_TRACKING_ID, -1));
        if (hasBtnTouch) events.add(new Event(EV_KEY, BTN_TOUCH, 0));
        if (hasBtnToolFinger) events.add(new Event(EV_KEY, BTN_TOOL_FINGER, 0));
        events.add(new Event(EV_SYN, SYN_REPORT, 0));
        writeEvents(events);
        fingerDown = false;
    }

    private void writeEvents(List<Event> events) throws Exception {
        int eventSize = is64Bit ? 24 : 16;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(eventSize * events.size());
        for (Event event : events) {
            ByteBuffer buffer = ByteBuffer.allocate(eventSize).order(ByteOrder.nativeOrder());
            if (is64Bit) {
                buffer.putLong(0L);
                buffer.putLong(0L);
            } else {
                buffer.putInt(0);
                buffer.putInt(0);
            }
            buffer.putShort((short) event.type);
            buffer.putShort((short) event.code);
            buffer.putInt(event.value);
            bytes.write(buffer.array());
        }
        device.write(bytes.toByteArray());
    }

    private int nextTrackingId() {
        int next = trackingId++;
        if (trackingId <= 0 || trackingId > maxTrackingId) trackingId = 1;
        return next;
    }

    private void safeRelease() {
        try { release(true); } catch (Exception ignored) {}
    }

    private void shutdown() {
        safeRelease();
        try { device.close(); } catch (Exception ignored) {}
    }

    private static boolean flag(String value) { return integer(value) != 0; }
    private static int integer(String value) { return Integer.parseInt(value); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) message = t.getClass().getSimpleName();
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class Event {
        final int type;
        final int code;
        final int value;

        Event(int type, int code, int value) {
            this.type = type;
            this.code = code;
            this.value = value;
        }
    }
}
