package com.hanwang.facekey.thin.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanwang.facekey.thin.config.ApkIdentity;
import com.hanwang.facekey.thin.protocol.Protocol;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * OkHttp 3.11.0 client for the three APK HTTP endpoints and the template download.
 * Field order is preserved by FormBody; no Authorization header is added.
 */
public final class HttpApi {
    private final OkHttpClient client;
    private final String userAgent;

    public HttpApi() {
        this(ApkIdentity.USER_AGENT);
    }

    public HttpApi(String userAgent) {
        this.userAgent = userAgent;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    public LoginResult login(String baseUrl, String userName, String pwd) throws ApiException {
        return login(baseUrl, userName, pwd, null);
    }

    public LoginResult login(
            String baseUrl, String userName, String pwd, AtomicReference<Call> callRef) throws ApiException {
        List<Protocol.Field> fields = Protocol.loginFields(userName, pwd);
        String url = Protocol.manageUrl(baseUrl, "/m/login.do");
        JsonObject json = postJson(url, fields, false, "login.do", callRef);
        if (!isTrue(json.get("success"))) {
            throw new ApiException("LOGIN_FAILED",
                    Protocol.responseMessage(string(json, "reason"), string(json, "message"),
                            string(json, "msg"), "登录失败"), 200);
        }
        JsonObject result = object(json, "result");
        String token = string(result, "token");
        if (token == null || token.isEmpty()) {
            throw new ApiException("LOGIN_RESULT_INVALID", "登录成功响应中缺少 result.token", 502);
        }
        int rawPort = integer(result, "recordTcpPort", -1);
        int recordTcpPort = rawPort > 0 ? rawPort : -1;
        int mapGaode = integer(result, "mapGaode", 0);
        String employId = string(result, "employId");
        return new LoginResult(token, employId == null ? "" : employId, mapGaode, recordTcpPort);
    }

    public CheckLocationResult checkLocation(
            String baseUrl, String token, String gps, Object lat, Object lng, String time) throws ApiException {
        return checkLocation(baseUrl, token, gps, lat, lng, time, null);
    }

    public CheckLocationResult checkLocation(
            String baseUrl, String token, String gps, Object lat, Object lng, String time,
            AtomicReference<Call> callRef) throws ApiException {
        List<Protocol.Field> fields = Protocol.checkLocationFields(token, gps, lat, lng, time);
        String url = Protocol.manageUrl(baseUrl, "/m/checkLocation.do");
        JsonObject json = postJson(url, fields, true, "checkLocation.do", callRef);
        boolean success = isTrue(json.get("success"));
        int code = integer(json, "code", -1);
        if (!success || code != 0) {
            throw new ApiException("LOCATION_REJECTED",
                    Protocol.responseMessage(string(json, "reason"), string(json, "message"),
                            string(json, "msg"), "服务端未通过定位校验"), 200);
        }
        int mapGaode = integer(json, "mapGaode", Integer.MIN_VALUE);
        boolean mapGaodePresent = mapGaode != Integer.MIN_VALUE;
        JsonObject result = objectOrNull(json, "result");
        if (result != null && result.has("mapGaode")) {
            mapGaode = integer(result, "mapGaode", mapGaode);
            mapGaodePresent = true;
        }
        if (mapGaode == Integer.MIN_VALUE) mapGaode = 0;
        return new CheckLocationResult(true, code, mapGaode, mapGaodePresent);
    }

    public AddCardResult addCard(
            String baseUrl,
            String token,
            String time,
            String gps,
            Object lat,
            Object lng,
            String photoBase64,
            int serverSpecifiedMap,
            int usedMap,
            int currSysVer) throws ApiException {
        return addCard(baseUrl, token, time, gps, lat, lng, photoBase64, serverSpecifiedMap, usedMap, currSysVer, null);
    }

    public AddCardResult addCard(
            String baseUrl,
            String token,
            String time,
            String gps,
            Object lat,
            Object lng,
            String photoBase64,
            int serverSpecifiedMap,
            int usedMap,
            int currSysVer,
            AtomicReference<Call> callRef) throws ApiException {
        List<Protocol.Field> fields = Protocol.addCardFields(token, time, gps, lat, lng,
                photoBase64, serverSpecifiedMap, usedMap, currSysVer);
        String url = Protocol.manageUrl(baseUrl, "/m/addCard.do");
        JsonObject json = postJson(url, fields, false, "addCard.do", callRef);
        int code = integer(json, "code", -1);
        if (code != 0) {
            throw new ApiException("ADD_CARD_REJECTED",
                    Protocol.responseMessage(string(json, "reason"), string(json, "message"),
                            string(json, "msg"), "上传考勤信息失败"), 200);
        }
        return new AddCardResult(string(json, "result"));
    }

    public FeatureResult downloadFeature(String baseUrl, String token) throws ApiException {
        return downloadFeature(baseUrl, token, null);
    }

    public FeatureResult downloadFeature(
            String baseUrl, String token, AtomicReference<Call> callRef) throws ApiException {
        Protocol.requireToken(token);
        String endpoint = Protocol.normalizeBaseUrl(baseUrl)
                + "/manage/mobiSetting/getEmployeeFeaturePicToMobi.do";
        HttpUrl parsed = HttpUrl.parse(endpoint);
        if (parsed == null) throw new ApiException("INVALID_BASE_URL", "服务器地址格式不正确", 400);
        HttpUrl url = parsed.newBuilder()
                .addQueryParameter("token", token)
                .addQueryParameter("picType", "1")
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .get()
                .build();
        JsonObject json = executeJson(request, "getEmployeeFeaturePicToMobi.do", callRef);
        int code = integer(json, "code", -1);
        if (code != 0) {
            throw new ApiException("FEATURE_REQUEST_FAILED",
                    Protocol.responseMessage(string(json, "reason"), string(json, "message"),
                            string(json, "msg"), "下载人脸模板失败"), 200);
        }
        JsonObject result = objectOrNull(json, "result");
        if (result == null) throw new ApiException("FEATURE_RESULT_INVALID", "模板响应缺少 result", 502);
        String picStr = string(result, "picStr");
        if (picStr == null || picStr.isEmpty()) {
            throw new ApiException("FEATURE_MISSING", "未注册人脸模板，请先在管理端注册", 200);
        }
        return new FeatureResult(picStr, string(result, "picMaskStr"));
    }

    private JsonObject postJson(
            String url, List<Protocol.Field> fields, boolean noCache, String endpoint,
            AtomicReference<Call> callRef) throws ApiException {
        FormBody.Builder form = new FormBody.Builder();
        for (Protocol.Field field : fields) form.add(field.name, field.value);
        RequestBody body = form.build();
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .post(body);
        if (noCache) builder.cacheControl(CacheControl.FORCE_NETWORK);
        return executeJson(builder.build(), endpoint, callRef);
    }

    private JsonObject executeJson(
            Request request, String endpoint, AtomicReference<Call> callRef) throws ApiException {
        Call call = client.newCall(request);
        if (callRef != null) callRef.set(call);
        try (Response response = call.execute()) {
            int status = response.code();
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (status < 200 || status >= 300) {
                String reason = tryReason(text);
                throw new ApiException("UPSTREAM_HTTP_STATUS",
                        endpoint + " 返回 HTTP " + status
                                + (reason == null || reason.isEmpty() ? "" : "：" + reason), status);
            }
            if (text == null || text.trim().isEmpty()) {
                throw new ApiException("UPSTREAM_EMPTY", endpoint + " 返回空响应", 502);
            }
            JsonElement element;
            try {
                element = JsonParser.parseString(text);
            } catch (RuntimeException e) {
                throw new ApiException("UPSTREAM_INVALID_JSON", endpoint + " 返回的不是有效 JSON", 502, e);
            }
            if (!element.isJsonObject()) {
                throw new ApiException("UPSTREAM_INVALID_JSON", endpoint + " 返回的不是 JSON 对象", 502);
            }
            return element.getAsJsonObject();
        } catch (IOException e) {
            if (call.isCanceled()) {
                throw new ApiException("REQUEST_CANCELED", endpoint + " 请求已取消", 0, e);
            }
            throw new ApiException("NETWORK_ERROR", endpoint + " 网络请求失败: " + safeMessage(e), 0, e);
        } finally {
            if (callRef != null) callRef.compareAndSet(call, null);
        }
    }

    private static String tryReason(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            String reason = string(json, "reason");
            if (reason == null) reason = string(json, "message");
            if (reason == null) reason = string(json, "msg");
            return reason;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static JsonObject object(JsonObject json, String name) throws ApiException {
        JsonObject result = objectOrNull(json, name);
        if (result == null) {
            throw new ApiException("UPSTREAM_FIELD_MISSING", "响应缺少对象字段 " + name, 502);
        }
        return result;
    }

    private static JsonObject objectOrNull(JsonObject json, String name) {
        if (json == null || !json.has(name)) return null;
        JsonElement element = json.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject json, String name) {
        if (json == null || !json.has(name)) return null;
        JsonElement element = json.get(name);
        if (element == null || element.isJsonNull()) return null;
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int integer(JsonObject json, String name, int fallback) {
        if (json == null || !json.has(name)) return fallback;
        JsonElement element = json.get(name);
        if (element == null || element.isJsonNull()) return fallback;
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean isTrue(JsonElement element) {
        if (element == null || element.isJsonNull()) return false;
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            String text = element.getAsString();
            return "true".equalsIgnoreCase(text) || "1".equals(text);
        }
    }
}
