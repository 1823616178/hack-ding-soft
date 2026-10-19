import test from 'node:test';
import assert from 'node:assert/strict';
import {
  androidBase64,
  buildAddCardRequest,
  buildCheckLocationRequest,
  buildLoginRequest,
  formatLocalDateTime,
  normalizeBaseUrl,
  requestPreview,
  resolveTimes,
} from '../lib/protocol.js';

const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0x00]);
const jpegBase64 = jpeg.toString('base64');

test('Android Base64.DEFAULT wraps at 76 chars and ends with LF', () => {
  const bytes = Buffer.alloc(80, 0x5a);
  const encoded = androidBase64(bytes);
  const plain = bytes.toString('base64');
  assert.equal(encoded, `${plain.slice(0, 76)}\n${plain.slice(76)}\n`);
  assert.ok(encoded.endsWith('\n'));
  assert.equal(encoded.includes('\r'), false);
});

test('login request preserves field order and OkHttp headers', () => {
  const request = buildLoginRequest({
    baseUrl: 'http://127.0.0.1:8080/',
    userName: 'demo user',
    pwd: 'p&+=',
  });

  assert.equal(request.url, 'http://127.0.0.1:8080/manage/m/login.do');
  assert.equal(request.body, 'userName=demo+user&pwd=p%26%2B%3D');
  assert.deepEqual(Object.keys(request.headers), [
    'Content-Type',
    'Content-Length',
    'Host',
    'Connection',
    'Accept-Encoding',
    'User-Agent',
  ]);
  assert.equal(request.headers['User-Agent'], 'okhttp/3.11.0');
  assert.equal(request.headers['Content-Type'], 'application/x-www-form-urlencoded');
  assert.equal(request.headers['Content-Length'], String(Buffer.byteLength(request.body)));
  assert.equal(request.headers.Accept, undefined);
});

test('checkLocation uses six decimals, no-cache and APK field order', () => {
  const request = buildCheckLocationRequest({
    baseUrl: 'https://example.test',
    token: 'token-test',
    gps: '北京 中关村',
    lat: 39.9042,
    lng: '116.4074',
    time: '2024-08-16 09:08:07',
  });

  assert.equal(request.body,
    'gps=%E5%8C%97%E4%BA%AC+%E4%B8%AD%E5%85%B3%E6%9D%91'
    + '&lat=39.904200&lng=116.407400&token=token-test&time=2024-08-16+09%3A08%3A07');
  assert.equal(request.headers['Cache-Control'], 'no-cache');
  assert.equal(Object.keys(request.headers)[0], 'Cache-Control');
});

test('addCard keeps empty APK time and Android photo encoding by default', () => {
  const request = buildAddCardRequest({
    baseUrl: 'http://example.test',
    token: 'token-test',
    gps: '北京',
    lat: 39.9042,
    lng: 116.4074,
    time: '',
    photoBase64: jpegBase64,
    serverSpecifiedMap: 0,
    usedMap: 0,
    currSysVer: 28,
  });
  const params = new URLSearchParams(request.body);

  assert.deepEqual([...params.keys()], [
    'token', 'source', 'time', 'gps', 'lat', 'lng', 'photo', 'appVer',
    'appVerCode', 'serverSpecifiedMap', 'usedMap', 'systemName', 'currSysVer',
  ]);
  assert.equal(params.get('time'), '');
  assert.equal(params.get('photo'), `${jpegBase64}\n`);
  assert.equal(params.get('appVer'), '1.0.15.79.20240816');
  assert.equal(params.get('appVerCode'), '41');
  assert.equal(params.get('systemName'), 'Android');
  assert.equal(params.get('currSysVer'), '28');
});

test('time modes preserve APK default and explicit custom override', () => {
  const now = new Date(2024, 7, 16, 9, 8, 7);
  assert.equal(formatLocalDateTime(now), '2024-08-16 09:08:07');
  assert.deepEqual(resolveTimes({ timeMode: 'apk', now }), {
    checkLocationTime: '2024-08-16 09:08:07',
    addCardTime: '',
  });
  assert.deepEqual(resolveTimes({ timeMode: 'custom', customTime: '2025-01-02 03:04:05', now }), {
    checkLocationTime: '2025-01-02 03:04:05',
    addCardTime: '2025-01-02 03:04:05',
  });
  assert.throws(() => resolveTimes({ timeMode: 'custom', customTime: '2025-02-30 03:04:05' }), /有效的日历时间/);
});

test('base URL and request preview are constrained and redacted', () => {
  assert.equal(normalizeBaseUrl('http://example.test/'), 'http://example.test');
  assert.throws(() => normalizeBaseUrl('http://example.test/manage'), /不要附加路径/);

  const request = buildLoginRequest({ baseUrl: 'http://example.test', userName: 'demo', pwd: 'secret' });
  const preview = requestPreview(request);
  assert.equal(preview.body.includes('secret'), false);
  assert.equal(preview.body.includes('%3Credacted%3E'), true);
});
