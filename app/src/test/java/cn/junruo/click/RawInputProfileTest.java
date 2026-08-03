package cn.junruo.click;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Surface;

import org.junit.Test;

public class RawInputProfileTest {
    private static final String CAPABILITIES =
            "add device 1: /dev/input/event2\n"
                    + "  ABS_MT_POSITION_X : value 0, min 0, max 1000, fuzz 0\n"
                    + "  ABS_MT_POSITION_Y : value 0, min 0, max 2000, fuzz 0\n"
                    + "  ABS_MT_TRACKING_ID : value 0, min 0, max 65535, fuzz 0\n"
                    + "add device 2: /dev/input/event4\n"
                    + "  ABS_MT_SLOT : value 0, min 0, max 9, fuzz 0\n"
                    + "  ABS_MT_TOUCH_MAJOR : value 0, min 0, max 255, fuzz 0\n"
                    + "  ABS_MT_PRESSURE : value 0, min 0, max 127, fuzz 0\n"
                    + "  ABS_MT_POSITION_X : value 0, min 0, max 1080, fuzz 0\n"
                    + "  ABS_MT_POSITION_Y : value 0, min 0, max 2400, fuzz 0\n"
                    + "  ABS_MT_TRACKING_ID : value 0, min 0, max 65535, fuzz 0\n"
                    + "  KEY (0001): BTN_TOUCH BTN_TOOL_FINGER\n";

    @Test
    public void preferredSupportedDeviceIsSelected() throws Exception {
        RawInputProfile profile = RawInputProfile.parseCapabilities(
                CAPABILITIES, "/dev/input/event4");

        assertEquals("/dev/input/event4", profile.devicePath);
        assertEquals(1080, profile.maxX);
        assertEquals(2400, profile.maxY);
        assertTrue(profile.hasSlot);
        assertTrue(profile.hasBtnTouch);
        assertTrue(profile.hasBtnToolFinger);
        assertTrue(profile.hasPressure);
        assertFalse(profile.hasWidthMajor);
    }

    @Test
    public void screenCoordinatesAreMappedBackToRawCoordinates() throws Exception {
        RawInputProfile profile = RawInputProfile.parseCapabilities(
                CAPABILITIES, "/dev/input/event4");

        int[] portrait = profile.mapScreenPoint(
                540, 1200, 1080, 2400, 0, Surface.ROTATION_0);
        assertEquals(540, portrait[0]);
        assertEquals(1200, portrait[1]);

        int[] landscape = profile.mapScreenPoint(
                1200, 540, 2400, 1080, 0, Surface.ROTATION_90);
        assertEquals(539, landscape[0]);
        assertEquals(1200, landscape[1]);
    }

    @Test(expected = java.io.IOException.class)
    public void unsupportedDeviceIsRejected() throws Exception {
        RawInputProfile.parseCapabilities(
                "add device 1: /dev/input/event1\n"
                        + "  ABS_MT_POSITION_X : value 0, min 0, max 1000\n"
                        + "  ABS_MT_POSITION_Y : value 0, min 0, max 2000\n",
                "/dev/input/event1");
    }
}
