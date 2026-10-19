import http from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { ProtocolInputError } from './lib/protocol.js';
import { UpstreamError } from './lib/upstream.js';
import { TcpProtocolError } from './lib/tcp-client.js';
import {
  runAddCard,
  runCheckLocation,
  runLogin,
  runPunch,
  WorkflowError,
} from './lib/workflow.js';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_ROOT = path.join(ROOT, 'public');
const MAX_LOCAL_BODY = 20 * 1024 * 1024;
const STATIC_FILES = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/index.html', ['index.html', 'text/html; charset=utf-8']],
  ['/app.js', ['app.js', 'text/javascript; charset=utf-8']],
  ['/styles.css', ['styles.css', 'text/css; charset=utf-8']],
]);

function securityHeaders(contentType) {
  return {
    'Content-Type': contentType,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
  };
}

function sendJson(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    ...securityHeaders('application/json; charset=utf-8'),
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_LOCAL_BODY) {
      throw new WorkflowError('LOCAL_BODY_TOO_LARGE', '本地请求超过 20 MiB 限制', { status: 413 });
    }
    chunks.push(chunk);
  }

  if (size === 0) return {};
  try {
    const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      throw new Error('JSON body must be an object');
    }
    return value;
  } catch {
    throw new WorkflowError('INVALID_LOCAL_JSON', '请求体必须是 JSON 对象', { status: 400 });
  }
}

function errorResponse(error) {
  if (error instanceof WorkflowError) {
    return {
      status: error.status,
      body: {
        ok: false,
        code: error.code,
        message: error.message,
        details: error.details,
      },
    };
  }
  if (error instanceof ProtocolInputError) {
    return {
      status: 400,
      body: {
        ok: false,
        code: error.code,
        message: error.message,
        field: error.field,
      },
    };
  }
  if (error instanceof UpstreamError) {
    return {
      status: 502,
      body: {
        ok: false,
        code: error.code,
        message: error.message,
        details: error.details,
      },
    };
  }
  if (error instanceof TcpProtocolError) {
    return {
      status: error.code.startsWith('SM2_CONFIG') ? 400 : 502,
      body: {
        ok: false,
        code: error.code,
        message: error.message,
        details: error.details,
      },
    };
  }

  return {
    status: 500,
    body: {
      ok: false,
      code: 'INTERNAL_ERROR',
      message: error?.message || '本地服务发生未知错误',
    },
  };
}

async function serveStatic(req, res, pathname) {
  const selected = STATIC_FILES.get(pathname);
  if (!selected) return false;
  const [filename, contentType] = selected;
  const body = await readFile(path.join(PUBLIC_ROOT, filename));
  res.writeHead(200, {
    ...securityHeaders(contentType),
    'Content-Length': body.length,
  });
  res.end(req.method === 'HEAD' ? undefined : body);
  return true;
}

export function createClientServer({ executor, tcpExecutor } = {}) {
  return http.createServer(async (req, res) => {
    try {
      const pathname = new URL(req.url, 'http://127.0.0.1').pathname;

      if ((req.method === 'GET' || req.method === 'HEAD') && pathname === '/api/health') {
        sendJson(res, 200, { ok: true, service: 'efacego-thin-client', httpClient: 'okhttp/3.11.0' });
        return;
      }

      if (req.method === 'GET' || req.method === 'HEAD') {
        if (await serveStatic(req, res, pathname)) return;
      }

      if (req.method === 'POST' && pathname.startsWith('/api/')) {
        const input = await readJson(req);
        let result;
        switch (pathname) {
          case '/api/login':
            result = { ok: true, ...(await runLogin(input, executor)) };
            break;
          case '/api/check-location':
            result = { ok: true, ...(await runCheckLocation(input, executor)) };
            break;
          case '/api/add-card':
            result = { ok: true, ...(await runAddCard(input, executor)) };
            break;
          case '/api/punch':
            result = await runPunch(input, executor, tcpExecutor);
            break;
          default:
            sendJson(res, 404, { ok: false, code: 'NOT_FOUND', message: '接口不存在' });
            return;
        }
        sendJson(res, 200, result);
        return;
      }

      sendJson(res, 404, { ok: false, code: 'NOT_FOUND', message: '资源不存在' });
    } catch (error) {
      const { status, body } = errorResponse(error);
      sendJson(res, status, body);
    }
  });
}

function start() {
  const host = process.env.HOST || '127.0.0.1';
  const port = Number(process.env.PORT || 8787);
  if (host !== '127.0.0.1' && host !== 'localhost' && host !== '::1') {
    throw new Error('为避免暴露账号、token 和照片，本地服务只允许绑定回环地址');
  }
  const server = createClientServer();
  server.listen(port, host, () => {
    console.log(`eFaceGo thin client: http://${host}:${port}`);
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  start();
}
