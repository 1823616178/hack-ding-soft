package com.hanwang.facekey.thin.protocol;

import com.hanwang.facekey.thin.config.ApkIdentity;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pure-Java construction and validation of the APK's HTTP forms.
 *
 * <p>No Android dependencies, so the exact bytes can be asserted in JVM tests.</p>
 */
public final class Protocol {
    public static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private Protocol() {
    }

    public static final class Field {
        public final String name;
        public final String value;

        public Field(String name, String value) {
            this.name = name;
            this.value = value == null ? "" : value;
        }
    }

    public static final class Times {
        public final String checkLocationTime;
        public final String addCardTime;

        Times(String checkLocationTime, String addCardTime) {
            this.checkLocationTime = checkLocationTime;
            this.addCardTime = addCardTime;
        }
    }

    public static final class ProtocolException extends IllegalArgumentException {
        public final String field;

        public ProtocolException(String message, String field) {
            super(message);
            this.field = field;
        }
    }

    public static String normalizeBaseUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) throw new ProtocolException("请填写服务器地址", "baseUrl");
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new ProtocolException("服务器地址格式不正确", "baseUrl");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new ProtocolException("服务器地址仅支持 http 或 https", "baseUrl");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new ProtocolException("服务器地址不能包含账号、查询参数或锚点", "baseUrl");
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new ProtocolException("请只填写协议、主机和端口，不要附加路径", "baseUrl");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new ProtocolException("服务器地址格式不正确", "baseUrl");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        String authority = host.contains(":") ? "[" + host + "]" : host;
        int port = uri.getPort();
        return scheme.toLowerCase(Locale.US) + "://" + authority + (port >= 0 ? ":" + port : "");
    }

    public static String manageUrl(String baseUrl, String endpoint) {
        return normalizeBaseUrl(baseUrl) + "/manage" + endpoint;
    }

    public static String formEncode(List<Field> fields) {
        StringBuilder out = new StringBuilder();
        for (Field field : fields) {
            if (out.length() > 0) out.append('&');
            out.append(percentEncode(field.name)).append('=').append(percentEncode(field.value));
        }
        return out.toString();
    }

    /** application/x-www-form-urlencoded, UTF-8, %20 for space (OkHttp 3.11 FormBody). */
    public static String percentEncode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (byte raw : bytes) {
            int b = raw & 0xff;
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '*') {
                out.append((char) b);
            } else if (b == ' ') {
                out.append("%20");
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((b >>> 4) & 0xf, 16)));
                out.append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
            }
        }
        return out.toString();
    }

    public static String formatCoordinate(Object value, String kind) {
        boolean latitude = "lat".equals(kind);
        if (!latitude && !"lng".equals(kind)) throw new IllegalArgumentException("Unknown coordinate kind");
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isEmpty()) throw new ProtocolException(latitude ? "请填写纬度" : "请填写经度", kind);
        double number;
        try {
            number = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new ProtocolException(latitude ? "纬度必须是数字" : "经度必须是数字", kind);
        }
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            throw new ProtocolException(latitude ? "纬度必须是数字" : "经度必须是数字", kind);
        }
        if (latitude && (number < -90 || number > 90)) {
            throw new ProtocolException("纬度必须在 -90 到 90 之间", kind);
        }
        if (!latitude && (number < -180 || number > 180)) {
            throw new ProtocolException("经度必须在 -180 到 180 之间", kind);
        }
        return String.format(Locale.US, "%.6f", number);
    }

    public static String formatLocalDateTime(Date date) {
        return formatLocalDateTime(date, TimeZone.getDefault());
    }

    public static String formatLocalDateTime(Date date, TimeZone timeZone) {
        SimpleDateFormat format = new SimpleDateFormat(TIME_PATTERN, Locale.US);
        format.setTimeZone(timeZone);
        return format.format(date);
    }

    public static String validateCalendarTime(String value, String fieldName, boolean allowEmpty) {
        if (value == null || value.trim().isEmpty()) {
            if (allowEmpty) return "";
            throw new ProtocolException("缺少时间", fieldName);
        }
        String text = value.trim();
        if (!text.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$")) {
            throw new ProtocolException(fieldName + " 格式必须为 yyyy-MM-dd HH:mm:ss", fieldName);
        }
        SimpleDateFormat format = new SimpleDateFormat(TIME_PATTERN, Locale.US);
        format.setLenient(false);
        try {
            Date parsed = format.parse(text);
            if (!text.equals(format.format(parsed))) {
                throw new ProtocolException(fieldName + " 不是有效的日历时间", fieldName);
            }
        } catch (ParseException e) {
            throw new ProtocolException(fieldName + " 不是有效的日历时间", fieldName);
        }
        return text;
    }

    public static String validateCustomTime(String value) {
        return validateCalendarTime(value, "customTime", false);
    }

    public static Times resolveTimes(String mode, String customTime, Date now) {
        if ("apk".equals(mode)) {
            return new Times(formatLocalDateTime(now), "");
        }
        if ("custom".equals(mode)) {
            String value = validateCustomTime(customTime);
            return new Times(value, value);
        }
        throw new ProtocolException("未知的时间模式", "timeMode");
    }

    public static byte[] decodeJpegBase64(String base64) {
        String compact = base64 == null ? "" : base64.replaceAll("\\s+", "");
        if (compact.isEmpty()) throw new ProtocolException("请选择 JPEG 照片", "photo");
        byte[] bytes;
        try {
            bytes = Base64Codec.decode(compact);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("照片不是有效的 Base64 数据", "photo");
        }
        if (bytes.length < 3 || (bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8
                || (bytes[2] & 0xff) != 0xff) {
            throw new ProtocolException("源 APK 上传的是 JPEG，请选择 JPEG 照片", "photo");
        }
        if (bytes.length > 12 * 1024 * 1024) {
            throw new ProtocolException("JPEG 照片不能超过 12 MiB", "photo");
        }
        return bytes;
    }

    public static String encodePhotoForApk(String photoBase64) {
        return Base64Codec.encodeAndroid(decodeJpegBase64(photoBase64));
    }

    public static List<Field> loginFields(String userName, String pwd) {
        if (userName == null || userName.isEmpty()) throw new ProtocolException("请填写用户名", "userName");
        if (pwd == null || pwd.isEmpty()) throw new ProtocolException("请填写密码", "pwd");
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("userName", userName));
        fields.add(new Field("pwd", pwd));
        return fields;
    }

    public static List<Field> checkLocationFields(
            String token, String gps, Object lat, Object lng, String time) {
        requireToken(token);
        String validTime = validateCalendarTime(time, "time", false);
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("gps", gps));
        fields.add(new Field("lat", formatCoordinate(lat, "lat")));
        fields.add(new Field("lng", formatCoordinate(lng, "lng")));
        fields.add(new Field("token", token));
        fields.add(new Field("time", validTime));
        return fields;
    }

    public static List<Field> addCardFields(
            String token,
            String time,
            String gps,
            Object lat,
            Object lng,
            String photoBase64,
            int serverSpecifiedMap,
            int usedMap,
            int currSysVer) {
        requireToken(token);
        String validTime = validateCalendarTime(time, "time", true);
        if (currSysVer < 0) {
            throw new ProtocolException("currSysVer 必须是 Android SDK 整数版本", "currSysVer");
        }
        if (usedMap != 0 && usedMap != 1) {
            throw new ProtocolException("usedMap 只能是 0 或 1", "usedMap");
        }
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("token", token));
        fields.add(new Field("source", ApkIdentity.SOURCE));
        fields.add(new Field("time", validTime));
        fields.add(new Field("gps", gps));
        fields.add(new Field("lat", formatCoordinate(lat, "lat")));
        fields.add(new Field("lng", formatCoordinate(lng, "lng")));
        fields.add(new Field("photo", encodePhotoForApk(photoBase64)));
        fields.add(new Field("appVer", ApkIdentity.APP_VER));
        fields.add(new Field("appVerCode", ApkIdentity.APP_VER_CODE));
        fields.add(new Field("serverSpecifiedMap", String.valueOf(serverSpecifiedMap)));
        fields.add(new Field("usedMap", String.valueOf(usedMap)));
        fields.add(new Field("systemName", ApkIdentity.SYSTEM_NAME));
        fields.add(new Field("currSysVer", String.valueOf(currSysVer)));
        return fields;
    }

    public static void requireToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new ProtocolException("缺少 token，请先登录", "token");
        }
    }

    public static String responseMessage(String reason, String message, String msg, String fallback) {
        if (reason != null && !reason.trim().isEmpty()) return reason.trim();
        if (message != null && !message.trim().isEmpty()) return message.trim();
        if (msg != null && !msg.trim().isEmpty()) return msg.trim();
        return fallback;
    }
}
