import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { createClientServer } from '../server.js';
import {
  deriveSm4KeyAndIv,
  encodeFrame,
  FrameDecoder,
  sm4OfbDecrypt,
  sm4OfbEncrypt,
} from '../lib/crypto-sm.js';
import {
  computeZ,
  CURVES,
  keyAgreement,
  pointFromHex,
  pointMultiply,
  pointToHex,
} from '../lib/sm2.js';
import { resolveSm2Config } from '../lib/sm2-config.js';
import { buildPostMobileRecord } from '../lib/tcp-protocol.js';
import { postMobileRecord } from '../lib/tcp-client.js';

const JPEG_BASE64 = Buffer.from([0xff, 0xd8, 0xff, 0x00]).toString('base64');
const OWNER_ID = '汉王人脸仪客户端';
const CURVE = CURVES.sm2p256v1;

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
}

function close(server) {
  return new Promise((resolve) => server.close(resolve));
}

function pointFromScalar(scalar) {
  return pointMultiply({ x: CURVE.gx, y: CURVE.gy }, scalar, CURVE);
}

function testParty(ownerId, privateKey, ephemeralScalar) {
  const publicKey = pointFromScalar(privateKey);
  const R = pointFromScalar(ephemeralScalar);
  return {
    ownerId,
    privateKey,
    publicKey,
    r: ephemeralScalar,
    R,
    Z: computeZ(ownerId, publicKey, CURVE),
  };
}

function createUpstream(recordTcpPort) {
  const calls = [];
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      calls.push({ url: req.url, body: Buffer.concat(chunks).toString('utf8') });
      if (req.url === '/manage/m/login.do') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          success: true,
          result: { token: 'token-tcp', employId: 'employee-tcp', recordTcpPort, mapGaode: 0 },
        }));
        return;
      }
      if (req.url === '/manage/m/checkLocation.do') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true, code: 0, result: { mapGaode: 0 } }));
        return;
      }
      res.writeHead(404);
      res.end();
    });
  });
  return { server, calls };
}

function createMockTcpServer() {
  const akeCommands = [];
  const records = [];
  const serverParty = testParty('tcp-test-server', 5n, 7n);
  const server = net.createServer((socket) => {
    const decoder = new FrameDecoder();
    let session = null;
    socket.on('data', (chunk) => {
      try {
        for (const frame of decoder.push(chunk)) {
          if (!session) {
            const command = JSON.parse(frame.toString('utf8'));
            akeCommands.push(command);
            const partnerPublicKey = pointFromHex(command.PARAM.key[0], CURVE);
            const partnerR = pointFromHex(command.PARAM.key[1], CURVE);
            const partnerZ = computeZ(command.PARAM.sn, partnerPublicKey, CURVE);
            const { sharedKey } = keyAgreement({
              ...serverParty,
              partnerPublicKey,
              partnerR,
              partnerZ,
              isInitiator: false,
              curve: CURVE,
            });
            session = deriveSm4KeyAndIv(sharedKey, 0);
            const reply = Buffer.from(JSON.stringify({
              RETURN: 'AKE',
              PARAM: {
                result: 'success',
                sn: serverParty.ownerId,
                key: [pointToHex(serverParty.publicKey), pointToHex(serverParty.R)],
              },
            }), 'utf8');
            const replyFrame = encodeFrame(reply);
            socket.write(replyFrame.subarray(0, 3));
            setImmediate(() => socket.write(replyFrame.subarray(3)));
            continue;
          }

          const record = JSON.parse(sm4OfbDecrypt(frame, session.key, session.iv).toString('utf8'));
          records.push(record);
          const response = sm4OfbEncrypt(Buffer.from(JSON.stringify({
            RETURN: 'PostMobileRecord',
            PARAM: { result: 'success' },
          }), 'utf8'), session.key, session.iv);
          const responseFrame = encodeFrame(response);
          socket.write(responseFrame.subarray(0, 2), () => {
            socket.end(responseFrame.subarray(2));
          });
        }
      } catch (error) {
        socket.destroy(error);
      }
    });
    socket.on('error', () => {});
  });
  return { server, akeCommands, records };
}

async function postJson(port, path, value) {
  const response = await fetch(`http://127.0.0.1:${port}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(value),
  });
  return { status: response.status, body: await response.json() };
}

test('TCP path completes SM2 AKE and encrypted PostMobileRecord without HTTP addCard', async (t) => {
  const tcp = createMockTcpServer();
  const tcpPort = await listen(tcp.server);
  const upstream = createUpstream(tcpPort);
  const upstreamPort = await listen(upstream.server);
  const client = createClientServer();
  const clientPort = await listen(client);
  t.after(async () => {
    await close(client);
    await close(upstream.server);
    await close(tcp.server);
  });

  const clientParty = testParty(OWNER_ID, 2n, 3n);
  const input = {
    baseUrl: `http://127.0.0.1:${upstreamPort}`,
    userName: 'demo',
    pwd: 'secret',
    timeMode: 'custom',
    customTime: '2024-08-16 09:08:07',
    gps: '北京 中关村',
    lat: '39.9042',
    lng: '116.4074',
    usedMap: '0',
    currSysVer: '28',
    photoBase64: JPEG_BASE64,
    authorized: true,
    sm2: {
      ownerId: clientParty.ownerId,
      curve: 'sm2p256v1',
      privateKey: clientParty.privateKey.toString(16).padStart(64, '0'),
      publicKeyX: pointToHex(clientParty.publicKey).slice(0, 64),
      publicKeyY: pointToHex(clientParty.publicKey).slice(64),
      uppercase: true,
    },
  };

  const result = await postJson(clientPort, '/api/punch', input);
  assert.equal(result.status, 200);
  assert.equal(result.body.ok, true);
  assert.equal(result.body.transport, 'tcp');
  assert.match(result.body.punchTime, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  assert.equal(result.body.tcp.response.json.PARAM.result, 'success');
  assert.deepEqual(upstream.calls.map((call) => call.url), [
    '/manage/m/login.do',
    '/manage/m/checkLocation.do',
  ]);
  assert.equal(upstream.calls.some((call) => call.url === '/manage/m/addCard.do'), false);

  assert.equal(tcp.akeCommands.length, 1);
  const ake = tcp.akeCommands[0];
  assert.equal(ake.COMMAND, 'AKE');
  assert.equal(ake.PARAM.sn, OWNER_ID);
  assert.equal(ake.PARAM.key[0], pointToHex(clientParty.publicKey));
  assert.match(ake.PARAM.key[0], /^[0-9A-F]{128}$/);
  assert.match(ake.PARAM.key[1], /^[0-9A-F]{128}$/);
  assert.doesNotThrow(() => pointFromHex(ake.PARAM.key[1], CURVE));

  assert.equal(tcp.records.length, 1);
  const record = tcp.records[0];
  assert.equal(record.COMMAND, 'PostMobileRecord');
  assert.equal(record.PARAM.id, 'employee-tcp');
  assert.equal(record.PARAM.time, '2024-08-16 09:08:07');
  assert.equal(record.PARAM.gps, '北京 中关村');
  assert.equal(record.PARAM.lat, '39.904200');
  assert.equal(record.PARAM.lng, '116.407400');
  assert.equal(record.PARAM.capturejpg, `${JPEG_BASE64}\n`);
  assert.equal(record.PARAM.recogSource, '1');
  assert.equal(record.PARAM.appVer, '1.0.15.79.20240816');
  assert.equal(record.PARAM.appVerCode, '41');
  assert.equal(record.PARAM.systemName, 'Android');
  assert.equal(record.PARAM.currSysVer, '28');
  assert.equal(JSON.stringify(result.body).includes(JPEG_BASE64), false);
});

const LOCAL_SM2_CONFIG = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  'sm2.config.local.json',
);

test('configured APK SM2 key pair completes local AKE without printing secrets', { skip: !existsSync(LOCAL_SM2_CONFIG) }, async (t) => {
  const tcp = createMockTcpServer();
  const tcpPort = await listen(tcp.server);
  t.after(async () => close(tcp.server));

  const sm2 = await resolveSm2Config({});
  const record = buildPostMobileRecord({
    employId: 'employee-config-test',
    photoBase64: JPEG_BASE64,
    time: '2024-08-16 09:08:07',
    gps: '本地测试',
    lat: '39.9042',
    lng: '116.4074',
    serverSpecifiedMap: 0,
    usedMap: 0,
    currSysVer: 28,
  });
  const result = await postMobileRecord({
    host: '127.0.0.1',
    port: tcpPort,
    record,
    sm2,
  });

  assert.equal(result.response.PARAM.result, 'success');
  assert.equal(tcp.akeCommands.length, 1);
  assert.equal(tcp.akeCommands[0].PARAM.sn, sm2.ownerId);
  assert.equal(tcp.akeCommands[0].PARAM.key[0] === pointToHex(sm2.publicKey), true);
  assert.equal(JSON.stringify(result).includes(sm2.privateKey.toString(16)), false);
});
