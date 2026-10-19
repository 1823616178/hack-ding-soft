#!/usr/bin/env node
/**
 * Local mock server for the Android thin client.
 *
 * HTTP:
 *   POST /manage/m/login.do
 *   POST /manage/m/checkLocation.do
 *   POST /manage/m/addCard.do
 *   GET  /manage/mobiSetting/getEmployeeFeaturePicToMobi.do
 *
 * TCP (only with --tcp):
 *   SM2 AKE + SM4/OFB/PKCS7 encrypted PostMobileRecord
 *
 * This server is intentionally unauthenticated and for a trusted LAN only.
 * It never prints tokens, photos, user credentials, or key material.
 */
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { FrameDecoder, deriveSm4KeyAndIv, encodeFrame, sm4OfbDecrypt, sm4OfbEncrypt } from '../../web-client/lib/crypto-sm.js';
import {
  CURVES,
  computeZ,
  keyAgreement,
  pointFromHex,
  pointMultiply,
  pointToHex,
} from '../../web-client/lib/sm2.js';

const CURVE = CURVES.sm2p256v1;
const ROOT = path.dirname(fileURLToPath(import.meta.url));
const FALLBACK_JPEG = Buffer.from(
  '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAF//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABBQJ//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAwEBPwF//8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAgBAgEBPwF//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQAGPwJ//8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPyF//9oADAMBAAIAAwAAABD/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAEDAQE/EH//xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/EH//xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAE/EH//2Q==',
  'base64',
);

function parseArgs(argv) {
  const options = {
    host: '127.0.0.1',
    httpPort: 8787,
    tcpPort: 9177,
    tcp: false,
    feature: null,
    maskFeature: null,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--host') options.host = argv[++i];
    else if (arg === '--http-port') options.httpPort = Number(argv[++i]);
    else if (arg === '--tcp') options.tcp = true;
    else if (arg === '--tcp-port') options.tcpPort = Number(argv[++i]);
    else if (arg === '--feature') options.feature = argv[++i];
    else if (arg === '--mask-feature') options.maskFeature = argv[++i];
    else if (arg === '--help' || arg === '-h') {
      console.log('Usage: node tools/mock-server.mjs [--host 0.0.0.0] [--http-port 8787] [--tcp] [--tcp-port 9177] [--feature face.jpg] [--mask-feature mask.jpg]');
      process.exit(0);
    }
  }
  return options;
}

function loadFeature(file) {
  if (!file) return FALLBACK_JPEG;
  const bytes = fs.readFileSync(file);
  if (bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[2] !== 0xff) {
    throw new Error(`Feature file is not a JPEG: ${file}`);
  }
  return bytes;
}

function json(res, status, value) {
  const body = Buffer.from(JSON.stringify(value), 'utf8');
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': body.length,
  });
  res.end(body);
}

function redactForm(form) {
  const copy = {};
  for (const [key, value] of form.entries()) {
    copy[key] = (key === 'photo' || key === 'pwd' || key === 'token' || key === 'userName' || key === 'id')
      ? `<redacted:${value.length}>`
      : value;
  }
  return copy;
}

function nowText() {
  const date = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())} `
    + `${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}`;
}

function createTcpServer(options) {
  const serverParty = {
    ownerId: 'mock-tcp-server',
    privateKey: 5n,
    r: 7n,
  };
  serverParty.publicKey = pointMultiply({ x: CURVE.gx, y: CURVE.gy }, serverParty.privateKey, CURVE);
  serverParty.R = pointMultiply({ x: CURVE.gx, y: CURVE.gy }, serverParty.r, CURVE);
  serverParty.Z = computeZ(serverParty.ownerId, serverParty.publicKey, CURVE);

  const server = net.createServer((socket) => {
    const decoder = new FrameDecoder();
    let session = null;
    socket.on('data', (chunk) => {
      try {
        for (const frame of decoder.push(chunk)) {
          if (!session) {
            const command = JSON.parse(frame.toString('utf8'));
            if (command.COMMAND !== 'AKE') throw new Error('expected AKE');
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
            const response = JSON.stringify({
              RETURN: 'AKE',
              PARAM: {
                result: 'success',
                sn: serverParty.ownerId,
                key: [pointToHex(serverParty.publicKey), pointToHex(serverParty.R)],
              },
            });
            socket.write(encodeFrame(Buffer.from(response, 'utf8')));
            continue;
          }

          const record = JSON.parse(sm4OfbDecrypt(frame, session.key, session.iv).toString('utf8'));
          if (record.COMMAND !== 'PostMobileRecord') throw new Error('expected PostMobileRecord');
          const param = record.PARAM || {};
          console.log('[tcp] PostMobileRecord', {
            id: `<redacted:${typeof param.id === 'string' ? param.id.length : 0}>`,
            time: param.time,
            gps: param.gps,
            lat: param.lat,
            lng: param.lng,
            photoChars: typeof param.capturejpg === 'string' ? param.capturejpg.length : 0,
            appVer: param.appVer,
            appVerCode: param.appVerCode,
            serverSpecifiedMap: param.serverSpecifiedMap,
            usedMap: param.usedMap,
            systemName: param.systemName,
            currSysVer: param.currSysVer,
          });
          const response = JSON.stringify({
            RETURN: 'PostMobileRecord',
            PARAM: { result: 'success', punchTime: nowText() },
          });
          socket.write(encodeFrame(sm4OfbEncrypt(Buffer.from(response, 'utf8'), session.key, session.iv)));
          socket.end();
        }
      } catch (error) {
        console.error('[tcp] error:', error.message);
        socket.destroy();
      }
    });
    socket.on('error', () => {});
  });
  server.listen(options.tcpPort, options.host, () => {
    console.log(`[tcp] listening on ${options.host}:${options.tcpPort}`);
  });
  return server;
}

const options = parseArgs(process.argv.slice(2));
const feature = loadFeature(options.feature);
const mask = options.maskFeature ? loadFeature(options.maskFeature) : null;
const featureBase64 = feature.toString('base64');
const maskBase64 = mask ? mask.toString('base64') : '';

let tcpServer = null;
if (options.tcp) tcpServer = createTcpServer(options);

const httpServer = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', (chunk) => chunks.push(chunk));
  req.on('end', () => {
    const raw = Buffer.concat(chunks).toString('utf8');
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const form = new URLSearchParams(raw);

    if (url.pathname === '/manage/m/login.do') {
      console.log('[http] login', redactForm(form));
      json(res, 200, {
        success: true,
        result: {
          token: 'mock-token',
          employId: 'mock-employee',
          mapGaode: 0,
          recordTcpPort: options.tcp ? options.tcpPort : 0,
        },
      });
      return;
    }
    if (url.pathname === '/manage/m/checkLocation.do') {
      console.log('[http] checkLocation', redactForm(form));
      json(res, 200, { success: true, code: 0, result: { mapGaode: 0 } });
      return;
    }
    if (url.pathname === '/manage/m/addCard.do') {
      console.log('[http] addCard', redactForm(form));
      json(res, 200, { code: 0, result: nowText() });
      return;
    }
    if (url.pathname === '/manage/mobiSetting/getEmployeeFeaturePicToMobi.do') {
      console.log('[http] feature download', {
        picType: url.searchParams.get('picType'),
        token: `<redacted:${(url.searchParams.get('token') || '').length}>`,
      });
      json(res, 200, {
        code: 0,
        result: { picStr: featureBase64, picMaskStr: maskBase64 },
      });
      return;
    }
    json(res, 404, { code: -1, message: 'not found' });
  });
});

httpServer.listen(options.httpPort, options.host, () => {
  if (options.host !== '127.0.0.1') {
    console.warn('WARNING: mock server is reachable from the network; use only on a trusted test LAN.');
  }
  console.log(`[http] listening on http://${options.host}:${options.httpPort}`);
  if (!options.feature) {
    console.warn('No --feature supplied: the mock returns a 1x1 JPEG. Native face matching will fail.');
  }
  console.log(`Open the Android app and use http://<this-host>:${options.httpPort}`);
});

function shutdown() {
  httpServer.close(() => {});
  if (tcpServer) tcpServer.close(() => {});
  setTimeout(() => process.exit(0), 200);
}
process.on('SIGINT', shutdown);
