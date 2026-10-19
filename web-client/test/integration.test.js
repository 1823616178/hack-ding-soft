import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import zlib from 'node:zlib';
import { createClientServer } from '../server.js';
import { runPunch } from '../lib/workflow.js';

const jpegBase64 = Buffer.from([0xff, 0xd8, 0xff, 0x00]).toString('base64');

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
}

function close(server) {
  return new Promise((resolve) => server.close(resolve));
}

function createUpstream({ recordTcpPort = 0 } = {}) {
  const calls = [];
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      const body = Buffer.concat(chunks).toString('utf8');
      calls.push({
        method: req.method,
        url: req.url,
        headers: req.headers,
        rawHeaders: req.rawHeaders,
        body,
      });

      if (req.url === '/manage/m/login.do') {
        const json = JSON.stringify({
          success: true,
          result: {
            token: 'token-test',
            employId: 'employee-test',
            recordTcpPort,
            mapGaode: 0,
          },
        });
        const compressed = zlib.gzipSync(json);
        res.writeHead(200, {
          'Content-Type': 'application/json',
          'Content-Encoding': 'gzip',
          'Content-Length': compressed.length,
        });
        res.end(compressed);
        return;
      }
      if (req.url === '/manage/m/checkLocation.do') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, code: 0, result: { mapGaode: 0 } }));
        return;
      }
      if (req.url === '/manage/m/addCard.do') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ code: 0, result: '2024-08-16 09:08:07' }));
        return;
      }
      res.writeHead(404);
      res.end();
    });
  });
  return { server, calls };
}

async function postJson(port, path, value) {
  const response = await fetch(`http://127.0.0.1:${port}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(value),
  });
  return { status: response.status, body: await response.json() };
}

function punchInput(baseUrl, overrides = {}) {
  return {
    baseUrl,
    userName: 'demo',
    pwd: 'secret',
    timeMode: 'custom',
    customTime: '2024-08-16 09:08:07',
    gps: '北京 中关村',
    lat: '39.9042',
    lng: '116.4074',
    usedMap: '0',
    currSysVer: '28',
    photoBase64: jpegBase64,
    authorized: true,
    ...overrides,
  };
}

test('full HTTP chain matches APK URLs, fields, headers and custom time/location', async (t) => {
  const upstream = createUpstream();
  const upstreamPort = await listen(upstream.server);
  const client = createClientServer();
  const clientPort = await listen(client);
  t.after(async () => {
    await close(client);
    await close(upstream.server);
  });

  const baseUrl = `http://127.0.0.1:${upstreamPort}`;
  const result = await postJson(clientPort, '/api/punch', punchInput(baseUrl));

  assert.equal(result.status, 200);
  assert.equal(result.body.ok, true);
  assert.equal(result.body.transport, 'http');
  assert.equal(result.body.punchTime, '2024-08-16 09:08:07');
  assert.deepEqual(upstream.calls.map((call) => call.url), [
    '/manage/m/login.do',
    '/manage/m/checkLocation.do',
    '/manage/m/addCard.do',
  ]);

  const [login, checkLocation, addCard] = upstream.calls;
  assert.equal(login.body, 'userName=demo&pwd=secret');
  assert.equal(checkLocation.body,
    'gps=%E5%8C%97%E4%BA%AC+%E4%B8%AD%E5%85%B3%E6%9D%91'
    + '&lat=39.904200&lng=116.407400&token=token-test&time=2024-08-16+09%3A08%3A07');

  const addCardParams = new URLSearchParams(addCard.body);
  assert.equal(addCardParams.get('time'), '2024-08-16 09:08:07');
  assert.equal(addCardParams.get('gps'), '北京 中关村');
  assert.equal(addCardParams.get('lat'), '39.904200');
  assert.equal(addCardParams.get('lng'), '116.407400');
  assert.equal(addCardParams.get('photo'), `${jpegBase64}\n`);
  assert.equal(addCardParams.get('appVer'), '1.0.15.79.20240816');
  assert.equal(addCardParams.get('appVerCode'), '41');
  assert.equal(addCardParams.get('systemName'), 'Android');
  assert.equal(addCardParams.get('currSysVer'), '28');

  for (const call of upstream.calls) {
    assert.equal(call.headers['user-agent'], 'okhttp/3.11.0');
    assert.equal(call.headers['content-type'], 'application/x-www-form-urlencoded');
    assert.equal(call.headers['accept-encoding'], 'gzip');
    assert.equal(call.headers.connection, 'Keep-Alive');
    assert.equal(call.headers.accept, undefined);
    assert.equal(call.headers.origin, undefined);
    assert.equal(call.headers['sec-fetch-mode'], undefined);
    assert.equal(call.headers['transfer-encoding'], undefined);
    assert.equal(call.headers.expect, undefined);
  }
  assert.deepEqual(login.rawHeaders.map((_, index) => index % 2 === 0 ? login.rawHeaders[index] : null).filter(Boolean), [
    'Content-Type', 'Content-Length', 'Host', 'Connection', 'Accept-Encoding', 'User-Agent',
  ]);
  assert.deepEqual(checkLocation.rawHeaders.map((_, index) => index % 2 === 0 ? checkLocation.rawHeaders[index] : null).filter(Boolean), [
    'Cache-Control', 'Content-Type', 'Content-Length', 'Host', 'Connection', 'Accept-Encoding', 'User-Agent',
  ]);
  assert.equal(login.headers['cache-control'], undefined);
  assert.equal(checkLocation.headers['cache-control'], 'no-cache');
  assert.equal(addCard.headers['cache-control'], undefined);
  assert.equal(result.body.login.exchange.response.compressed, true);
});

test('missing SM2 config never falls through to HTTP addCard', async (t) => {
  const upstream = createUpstream({ recordTcpPort: 9177 });
  const upstreamPort = await listen(upstream.server);
  const client = createClientServer();
  const clientPort = await listen(client);
  t.after(async () => {
    await close(client);
    await close(upstream.server);
  });

  const result = await postJson(clientPort, '/api/punch', punchInput(`http://127.0.0.1:${upstreamPort}`, {
    sm2ConfigPath: '__missing_sm2_config__.json',
  }));
  assert.equal(result.status, 400);
  assert.equal(result.body.ok, false);
  assert.equal(result.body.code, 'SM2_CONFIG_REQUIRED');
  assert.deepEqual(upstream.calls.map((call) => call.url), [
    '/manage/m/login.do',
    '/manage/m/checkLocation.do',
  ]);
});

test('invalid local input is rejected before any upstream request', async () => {
  let calls = 0;
  const executor = async () => {
    calls += 1;
    throw new Error('must not be called');
  };

  await assert.rejects(
    runPunch(punchInput('http://127.0.0.1:1', { photoBase64: 'not-a-jpeg' }), executor),
    /Base64|JPEG/,
  );
  assert.equal(calls, 0);

  await assert.rejects(
    runPunch(punchInput('http://127.0.0.1:1', { lat: '' }), executor),
    /请填写纬度/,
  );
  assert.equal(calls, 0);

  await assert.rejects(
    runPunch(punchInput('http://127.0.0.1:1', { authorized: false }), executor),
    /授权测试环境/,
  );
  assert.equal(calls, 0);
});
