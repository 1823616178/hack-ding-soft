package com.hanwang.facekey.thin;

import com.hanwang.facekey.thin.net.AddCardResult;
import com.hanwang.facekey.thin.net.CheckLocationResult;
import com.hanwang.facekey.thin.net.HttpApi;
import com.hanwang.facekey.thin.net.LoginResult;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WorkflowTest {
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
    public void httpChainLoginCheckLocationAddCard() throws Exception {
        server.enqueue(json("{\"success\":true,\"result\":{\"token\":\"token-chain\","
                + "\"employId\":\"employee-chain\",\"recordTcpPort\":0,\"mapGaode\":0}}"));
        server.enqueue(json("{\"success\":true,\"code\":0,\"result\":{\"mapGaode\":3}}"));
        server.enqueue(json("{\"code\":0,\"result\":\"2024-08-16 09:08:07\"}"));

        HttpApi api = new HttpApi();
        LoginResult login = api.login(baseUrl, "demo", "secret");
        assertEquals("http", login.transport);
        CheckLocationResult location = api.checkLocation(
                baseUrl, login.token, "北京", 39.9042, 116.4074, "2024-08-16 09:08:07");
        assertEquals(3, location.mapGaode);
        AddCardResult card = api.addCard(baseUrl, login.token, "", "北京",
                39.9042, 116.4074,
                java.util.Base64.getEncoder().encodeToString(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0}),
                3, 0, 28);
        assertEquals("2024-08-16 09:08:07", card.punchTime);
        assertEquals(3, server.getRequestCount());

        RecordedRequest loginRequest = server.takeRequest();
        RecordedRequest locationRequest = server.takeRequest();
        RecordedRequest cardRequest = server.takeRequest();
        assertEquals("/manage/m/login.do", loginRequest.getPath());
        assertEquals("/manage/m/checkLocation.do", locationRequest.getPath());
        assertEquals("no-cache", locationRequest.getHeader("Cache-Control"));
        assertEquals("/manage/m/addCard.do", cardRequest.getPath());
        String body = cardRequest.getBody().readUtf8();
        assertTrue(body.startsWith("token=token-chain&source=1&time=&gps="));
        assertTrue(body.contains("&serverSpecifiedMap=3&usedMap=0&systemName=Android&currSysVer=28"));
    }

    @Test
    public void tcpLoginDoesNotCallHttpAddCardForSelection() throws Exception {
        server.enqueue(json("{\"success\":true,\"result\":{\"token\":\"token-tcp\","
                + "\"employId\":\"employee-tcp\",\"recordTcpPort\":9177,\"mapGaode\":0}}"));
        LoginResult login = new HttpApi().login(baseUrl, "demo", "secret");
        assertEquals("tcp", login.transport);
        assertEquals(9177, login.recordTcpPort);
        assertEquals(1, server.getRequestCount());
        assertEquals("/manage/m/login.do", server.takeRequest().getPath());
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
