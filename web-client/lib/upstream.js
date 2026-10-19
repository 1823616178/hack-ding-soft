import http from 'node:http';
import https from 'node:https';
import zlib from 'node:zlib';

const agents = {
  'http:': new http.Agent({ keepAlive: true }),
  'https:': new https.Agent({ keepAlive: true }),
};

export class UpstreamError extends Error {
  constructor(message, { code = 'UPSTREAM_ERROR', details } = {}) {
    super(message);
    this.name = 'UpstreamError';
    this.code = code;
    this.details = details;
  }
}

function collectResponse(response, maxResponseBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;

    response.on('data', (chunk) => {
      size += chunk.length;
      if (size > maxResponseBytes) {
        response.destroy(new UpstreamError('上游响应超过大小限制', {
          code: 'RESPONSE_TOO_LARGE',
          details: { maxResponseBytes },
        }));
        return;
      }
      chunks.push(chunk);
    });
    response.on('end', () => resolve(Buffer.concat(chunks)));
    response.on('error', reject);
  });
}

function gunzip(buffer, maxResponseBytes) {
  return new Promise((resolve, reject) => {
    zlib.gunzip(buffer, { maxOutputLength: maxResponseBytes }, (error, output) => {
      if (error) {
        reject(new UpstreamError(`gzip 响应解码失败: ${error.message}`, {
          code: 'GZIP_DECODE_ERROR',
        }));
        return;
      }
      resolve(output);
    });
  });
}

export async function executeUpstream(request, {
  timeoutMs = 5000,
  maxResponseBytes = 4 * 1024 * 1024,
} = {}) {
  const url = new URL(request.url);
  const transport = url.protocol === 'https:' ? https : url.protocol === 'http:' ? http : null;
  if (!transport) throw new UpstreamError('不支持的上游协议', { code: 'UNSUPPORTED_PROTOCOL' });

  return new Promise((resolve, reject) => {
    let settled = false;
    const fail = (error) => {
      if (settled) return;
      settled = true;
      reject(error instanceof UpstreamError ? error : new UpstreamError(error.message, {
        code: error.code || 'UPSTREAM_ERROR',
      }));
    };

    const req = transport.request(url, {
      method: request.method,
      headers: request.headers,
      agent: agents[url.protocol],
      timeout: timeoutMs,
    }, (res) => {
      collectResponse(res, maxResponseBytes)
        .then(async (wireBody) => {
          const compressed = res.headers['content-encoding']?.toLowerCase() === 'gzip';
          const body = compressed ? await gunzip(wireBody, maxResponseBytes) : wireBody;
          return { compressed, body };
        })
        .then(({ compressed, body }) => {
          if (settled) return;
          settled = true;
          resolve({
            status: res.statusCode,
            statusMessage: res.statusMessage,
            headers: res.headers,
            body,
            text: body.toString('utf8'),
            compressed,
          });
        })
        .catch(fail);
    });

    req.on('timeout', () => {
      req.destroy(new UpstreamError(`上游请求超过 ${timeoutMs} ms`, {
        code: 'UPSTREAM_TIMEOUT',
        details: { timeoutMs },
      }));
    });
    req.on('error', fail);
    req.end(request.body);
  });
}
