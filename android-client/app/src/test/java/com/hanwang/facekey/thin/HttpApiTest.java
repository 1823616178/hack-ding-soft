package com.hanwang.facekey.thin;

import com.hanwang.facekey.thin.net.AddCardResult;
import com.hanwang.facekey.thin.net.ApiException;
import com.hanwang.facekey.thin.net.CheckLocationResult;
import com.hanwang.facekey.thin.net.FeatureResult;
import com.hanwang.facekey.thin.net.HttpApi;
import com.hanwang.facekey.thin.net.LoginResult;
import com.hanwang.facekey.thin.protocol.Protocol;

import okhttp3.Call;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HttpApiTest {
    private static final String JPEG_BASE64 = java.util.Base64.getEncoder()
            .encodeToString(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00});

    private MockWebServer server;
    private String baseUrl;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        baseUrl = "http://" + server.getHostName() + ":" + server.getPort();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.shutdown();
    }

    @Test
    public void loginPreservesBodyHeadersAndDecompressesGzip() throws Exception {
        byte[] json = ("{\"success\":true,\"result\":{\"token\":\"token-test\","
                + "\"employId\":\"employee-test\",\"recordTcpPort\":0,\"mapGaode\":0}}")
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream gzip = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzip)) {
            out.write(json);
        }
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("Content-Encoding", "gzip")
                .setBody(new Buffer().write(gzip.toByteArray())));

        LoginResult result = new HttpApi().login(baseUrl, "demo user", "p&+=");
        assertEquals("token-test", result.token);
        assertEquals("employee-test", result.employId);
        assertEquals(-1, result.recordTcpPort);
        assertEquals("http", result.transport);

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/manage/m/login.do", request.getPath());
        String requestBody = request.getBody().readUtf8();
        assertEquals("userName=demo%20user&pwd=p%26%2B%3D", requestBody);
        assertEquals("okhttp/3.11.0", request.getHeader("User-Agent"));
        assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"));
        assertEquals(String.valueOf(requestBody.getBytes(StandardCharsets.UTF_8).length),
                request.getHeader("Content-Length"));
        assertTrue(request.getHeader("Accept-Encoding").contains("gzip"));
        assertEquals("Keep-Alive", request.getHeader("Connection"));
        assertNotNull(request.getHeader("Host"));
        assertNull(request.getHeader("Cache-Control"));
        assertNull(request.getHeader("Authorization"));
    }

    @Test
    public void checkLocationUsesNoCacheCoordinatesAndFieldOrder() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"code\":0,\"result\":{\"mapGaode\":2}}"));

        CheckLocationResult result = new HttpApi().checkLocation(
                baseUrl, "token-test", "北京 中关村", 39.9042, "116.4074", "2024-08-16 09:08:07");
        assertEquals(2, result.mapGaode);
        RecordedRequest request = server.takeRequest();
        assertEquals("gps=%E5%8C%97%E4%BA%AC%20%E4%B8%AD%E5%85%B3%E6%9D%91"
                        + "&lat=39.904200&lng=116.407400&token=token-test&time=2024-08-16%2009%3A08%3A07",
                request.getBody().readUtf8());
        assertEquals("no-cache", request.getHeader("Cache-Control"));
        assertEquals("okhttp/3.11.0", request.getHeader("User-Agent"));
    }

    @Test
    public void addCardPreservesApkFieldOrderAndAndroidBase64() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"result\":\"2024-08-16 09:08:07\"}"));

        AddCardResult result = new HttpApi().addCard(baseUrl, "token-test", "", "北京", 39.9042,
                116.4074, JPEG_BASE64, 2, 0, 28);
        assertEquals("2024-08-16 09:08:07", result.punchTime);
        RecordedRequest request = server.takeRequest();
        String body = request.getBody().readUtf8();
        String[] pairs = body.split("&");
        assertEquals(13, pairs.length);
        assertEquals("token=token-test", pairs[0]);
        assertEquals("source=1", pairs[1]);
        assertEquals("time=", pairs[2]);
        assertEquals("gps=%E5%8C%97%E4%BA%AC", pairs[3]);
        assertEquals("lat=39.904200", pairs[4]);
        assertEquals("lng=116.407400", pairs[5]);
        assertTrue(pairs[6].startsWith("photo="));
        assertTrue(pairs[6].endsWith("%0A"));
        assertEquals("appVer=1.0.15.79.20240816", pairs[7]);
        assertEquals("appVerCode=41", pairs[8]);
        assertEquals("serverSpecifiedMap=2", pairs[9]);
        assertEquals("usedMap=0", pairs[10]);
        assertEquals("systemName=Android", pairs[11]);
        assertEquals("currSysVer=28", pairs[12]);
        assertNull(request.getHeader("Cache-Control"));
        assertEquals("okhttp/3.11.0", request.getHeader("User-Agent"));
    }

    @Test
    public void featureDownloadSendsTokenAndPicType() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"result\":{\"picStr\":\"" + JPEG_BASE64
                        + "\",\"picMaskStr\":\"\"}}"));
        FeatureResult result = new HttpApi().downloadFeature(baseUrl, "token-test");
        assertEquals(JPEG_BASE64, result.picStr);
        RecordedRequest request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertNotNull(request.getPath());
        assertTrue(request.getPath().contains("token=token-test"));
        assertTrue(request.getPath().contains("picType=1"));
        assertEquals("no-cache", request.getHeader("Cache-Control"));
    }

    @Test
    public void invalidCalendarTimeIsRejectedBeforeNetworkRequest() throws Exception {
        HttpApi api = new HttpApi();
        int initialRequests = server.getRequestCount();

        // checkLocation with invalid calendar time
        assertThrows(Protocol.ProtocolException.class,
                () -> api.checkLocation(baseUrl, "token-test", "北京", 39.9042, 116.4074, "2025-02-30 25:61:61"));
        assertEquals(initialRequests, server.getRequestCount());

        // addCard with invalid calendar time
        assertThrows(Protocol.ProtocolException.class,
                () -> api.addCard(baseUrl, "token-test", "2025-02-30 25:61:61", "北京", 39.9042, 116.4074,
                        JPEG_BASE64, 0, 0, 28));
        assertEquals(initialRequests, server.getRequestCount());
    }

    @Test
    public void callCancellationAbortsInFlightRequest() throws Exception {
        server.enqueue(new MockResponse()
                .setHeadersDelay(1, TimeUnit.SECONDS)
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"result\":\"ok\"}"));

        HttpApi api = new HttpApi();
        AtomicReference<Call> callRef = new AtomicReference<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> future = pool.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                api.addCard(baseUrl, "token-test", "", "北京", 39.9042, 116.4074, JPEG_BASE64, 0, 0, 28, callRef);
                return null;
            }
        });

        long start = System.currentTimeMillis();
        while (callRef.get() == null && System.currentTimeMillis() - start < 2000L) {
            Thread.sleep(10);
        }
        Call call = callRef.get();
        assertNotNull(call);
        call.cancel();

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        Throwable cause = ex.getCause();
        assertTrue(cause instanceof ApiException);
        assertEquals("REQUEST_CANCELED", ((ApiException) cause).code);
        pool.shutdownNow();
    }
}
