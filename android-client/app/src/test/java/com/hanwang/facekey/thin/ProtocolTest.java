package com.hanwang.facekey.thin;

import com.hanwang.facekey.thin.protocol.Base64Codec;
import com.hanwang.facekey.thin.protocol.Protocol;
import com.hanwang.facekey.thin.tcp.PostMobileRecord;

import org.junit.Test;

import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ProtocolTest {
    private static final byte[] JPEG = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
    private static final String JPEG_BASE64 = java.util.Base64.getEncoder().encodeToString(JPEG);

    @Test
    public void androidBase64WrapsAt76AndEndsWithLf() {
        byte[] bytes = new byte[80];
        java.util.Arrays.fill(bytes, (byte) 0x5a);
        String plain = java.util.Base64.getEncoder().encodeToString(bytes);
        String encoded = Base64Codec.encodeAndroid(bytes);
        assertEquals(plain.substring(0, 76) + "\n" + plain.substring(76) + "\n", encoded);
        assertTrue(encoded.endsWith("\n"));
        assertFalse(encoded.contains("\r"));
        assertEquals("", Base64Codec.encodeAndroid(new byte[0]));
    }

    @Test
    public void base64DecodeAcceptsWrappedInputAndRejectsBadData() {
        assertArrayEquals(JPEG, Base64Codec.decode(JPEG_BASE64 + "\n"));
        assertThrows(IllegalArgumentException.class, () -> Base64Codec.decode("not base64!"));
        assertThrows(IllegalArgumentException.class, () -> Base64Codec.decode("abc"));
    }

    @Test
    public void formEncodingMatchesOkHttpAndUrlSearchParams() {
        List<Protocol.Field> login = Protocol.loginFields("demo user", "p&+=");
        assertEquals("userName=demo%20user&pwd=p%26%2B%3D", Protocol.formEncode(login));
        assertEquals("gps=%E5%8C%97%E4%BA%AC%20%E4%B8%AD%E5%85%B3%E6%9D%91"
                        + "&lat=39.904200&lng=116.407400&token=token-test&time=2024-08-16%2009%3A08%3A07",
                Protocol.formEncode(Protocol.checkLocationFields(
                        "token-test", "北京 中关村", 39.9042, "116.4074", "2024-08-16 09:08:07")));
    }

    @Test
    public void addCardKeepsApkFieldOrderAndPhotoEncoding() {
        List<Protocol.Field> fields = Protocol.addCardFields(
                "token-test", "", "北京", 39.9042, 116.4074, JPEG_BASE64, 0, 0, 28);
        assertEquals("token", fields.get(0).name);
        assertEquals("source", fields.get(1).name);
        assertEquals("1", fields.get(1).value);
        assertEquals("time", fields.get(2).name);
        assertEquals("", fields.get(2).value);
        assertEquals("appVer", fields.get(7).name);
        assertEquals("1.0.15.79.20240816", fields.get(7).value);
        assertEquals("appVerCode", fields.get(8).name);
        assertEquals("41", fields.get(8).value);
        assertEquals("systemName", fields.get(11).name);
        assertEquals("Android", fields.get(11).value);
        assertEquals("currSysVer", fields.get(12).name);
        assertEquals("28", fields.get(12).value);
        assertEquals(JPEG_BASE64 + "\n", fields.get(6).value);
        String body = Protocol.formEncode(fields);
        assertTrue(body.contains("photo=%2F9j%2FAA%3D%3D%0A"));
    }

    @Test
    public void timeModesPreserveApkDefaultAndCustomOverride() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            Date now = new Date(1723770487000L);
            Protocol.Times apk = Protocol.resolveTimes("apk", "", now);
            assertEquals("2024-08-16 09:08:07", apk.checkLocationTime);
            assertEquals("", apk.addCardTime);
            Protocol.Times custom = Protocol.resolveTimes("custom", "2025-01-02 03:04:05", now);
            assertEquals("2025-01-02 03:04:05", custom.checkLocationTime);
            assertEquals("2025-01-02 03:04:05", custom.addCardTime);
            assertThrows(IllegalArgumentException.class,
                    () -> Protocol.resolveTimes("custom", "2025-02-30 03:04:05", now));
            assertEquals("2024-08-16 09:08:07",
                    Protocol.formatLocalDateTime(now, TimeZone.getTimeZone("Asia/Shanghai")));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    public void calendarTimeValidationRejectsInvalidDates() {
        // Valid calendar times
        assertEquals("2024-08-16 09:08:07",
                Protocol.validateCalendarTime("2024-08-16 09:08:07", "time", false));
        assertEquals("", Protocol.validateCalendarTime("", "time", true));
        assertEquals("", Protocol.validateCalendarTime(null, "time", true));

        // Missing when not allowed
        assertThrows(Protocol.ProtocolException.class,
                () -> Protocol.validateCalendarTime("", "time", false));
        assertThrows(Protocol.ProtocolException.class,
                () -> Protocol.validateCalendarTime(null, "time", false));

        // Invalid calendar date (Feb 30)
        assertThrows(Protocol.ProtocolException.class,
                () -> Protocol.validateCalendarTime("2025-02-30 12:00:00", "time", false));

        // Invalid hour / minute / second
        assertThrows(Protocol.ProtocolException.class,
                () -> Protocol.validateCalendarTime("2025-02-28 25:61:61", "time", false));

        // CheckLocation rejects invalid time
        assertThrows(Protocol.ProtocolException.class,
                () -> Protocol.checkLocationFields("token", "北京", 39.9, 116.4, "2025-02-30 25:61:61"));

        // AddCard rejects invalid non-empty time
        assertThrows(Protocol.ProtocolException.class,
                () -> Protocol.addCardFields("token", "2025-02-30 25:61:61", "北京", 39.9, 116.4, JPEG_BASE64, 0, 0, 28));

        // TCP PostMobileRecord rejects invalid time
        assertThrows(Protocol.ProtocolException.class,
                () -> new PostMobileRecord(JPEG_BASE64, "emp-1", "2025-02-30 25:61:61", "北京", 39.9, 116.4, 0, 0, 28));
    }

    @Test
    public void coordinatesUseSixDecimalsAndRejectOutOfRange() {
        assertEquals("39.904200", Protocol.formatCoordinate("39.9042", "lat"));
        assertEquals("-116.407400", Protocol.formatCoordinate(-116.4074, "lng"));
        assertThrows(IllegalArgumentException.class, () -> Protocol.formatCoordinate("91", "lat"));
        assertThrows(IllegalArgumentException.class, () -> Protocol.formatCoordinate("181", "lng"));
        assertThrows(IllegalArgumentException.class, () -> Protocol.formatCoordinate("", "lat"));
    }

    @Test
    public void baseUrlValidationRejectsPathsAndCredentials() {
        assertEquals("http://example.test:8080", Protocol.normalizeBaseUrl("http://example.test:8080/"));
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.normalizeBaseUrl("http://example.test/manage"));
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.normalizeBaseUrl("ftp://example.test"));
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.normalizeBaseUrl("http://user:pass@example.test"));
    }

    @Test
    public void photoMustBeJpegAndWithinLimit() {
        assertNotNull(Protocol.decodeJpegBase64(JPEG_BASE64));
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.decodeJpegBase64(java.util.Base64.getEncoder().encodeToString(
                        new byte[]{(byte) 0x89, 'P', 'N', 'G'})));
        assertThrows(IllegalArgumentException.class, () -> Protocol.decodeJpegBase64(""));
    }

    @Test
    public void loginValidationRejectsEmptyCredentials() {
        assertThrows(IllegalArgumentException.class, () -> Protocol.loginFields("", "pwd"));
        assertThrows(IllegalArgumentException.class, () -> Protocol.loginFields("user", ""));
    }
}
