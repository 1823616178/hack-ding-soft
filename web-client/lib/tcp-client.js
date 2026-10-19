import net from 'node:net';
import {
  completeAkeInitiation,
  createAkeInitiation,
} from './sm2.js';
import {
  encodeFrame,
  FrameDecoder,
  sm4OfbDecrypt,
  sm4OfbEncrypt,
} from './crypto-sm.js';
import { buildPostMobileRecordMessage } from './tcp-protocol.js';

export class TcpProtocolError extends Error {
  constructor(code, message, details) {
    super(message);
    this.name = 'TcpProtocolError';
    this.code = code;
    this.details = details;
  }
}

class FrameQueue {
  constructor(socket, decoder) {
    this.socket = socket;
    this.decoder = decoder;
    this.frames = [];
    this.waiters = [];
    this.closed = false;

    socket.on('data', (chunk) => {
      try {
        for (const frame of decoder.push(chunk)) {
          const waiter = this.waiters.shift();
          if (waiter) waiter.resolve(frame);
          else this.frames.push(frame);
        }
      } catch (error) {
        this.fail(error);
      }
    });
    socket.on('error', (error) => this.fail(error));
    socket.on('close', () => this.fail(new TcpProtocolError('TCP_CLOSED', 'TCP 连接已关闭')));
  }

  fail(error) {
    if (this.closed) return;
    this.closed = true;
    for (const waiter of this.waiters.splice(0)) waiter.reject(error);
  }

  next(timeoutMs, code) {
    if (this.frames.length) return Promise.resolve(this.frames.shift());
    if (this.closed) return Promise.reject(new TcpProtocolError('TCP_CLOSED', 'TCP 连接已关闭'));

    return new Promise((resolve, reject) => {
      const waiter = { resolve, reject };
      this.waiters.push(waiter);
      const timer = setTimeout(() => {
        const index = this.waiters.indexOf(waiter);
        if (index >= 0) this.waiters.splice(index, 1);
        reject(new TcpProtocolError(code, `等待 TCP 帧超过 ${timeoutMs} ms`));
        this.socket.destroy();
      }, timeoutMs);
      const clear = () => clearTimeout(timer);
      waiter.resolve = (frame) => {
        clear();
        resolve(frame);
      };
      waiter.reject = (error) => {
        clear();
        reject(error);
      };
    });
  }

  close() {
    this.closed = true;
    for (const waiter of this.waiters.splice(0)) {
      waiter.reject(new TcpProtocolError('TCP_CLOSED', 'TCP 连接已关闭'));
    }
  }
}

function connect(host, port, timeoutMs) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port });
    const timer = setTimeout(() => {
      socket.destroy(new TcpProtocolError('TCP_CONNECT_TIMEOUT', `连接 ${host}:${port} 超时`));
    }, timeoutMs);
    socket.once('connect', () => {
      clearTimeout(timer);
      socket.setNoDelay(true);
      resolve(socket);
    });
    socket.once('error', (error) => {
      clearTimeout(timer);
      reject(new TcpProtocolError('TCP_CONNECT_ERROR', `连接 ${host}:${port} 失败: ${error.message}`));
    });
  });
}

function write(socket, data) {
  return new Promise((resolve, reject) => {
    socket.write(data, (error) => {
      if (error) reject(new TcpProtocolError('TCP_WRITE_ERROR', error.message));
      else resolve();
    });
  });
}

export async function postMobileRecord({
  host,
  port,
  record,
  sm2,
  connectTimeoutMs = 10000,
  akeTimeoutMs = 3000,
  responseTimeoutMs = 10000,
}) {
  if (!sm2?.curve || !sm2?.ownerId || !sm2?.privateKey || !sm2?.publicKey) {
    throw new TcpProtocolError('SM2_CONFIG_REQUIRED', '缺少 SM2 曲线、OwnerId、私钥或公钥配置');
  }

  const initiation = createAkeInitiation({
    ownerId: sm2.ownerId,
    privateKey: sm2.privateKey,
    publicKey: sm2.publicKey,
    curve: sm2.curve,
    ephemeralScalar: sm2.ephemeralScalar,
    uppercase: sm2.uppercase,
  });

  const socket = await connect(host, Number(port), connectTimeoutMs);
  const queue = new FrameQueue(socket, new FrameDecoder());
  try {
    await write(socket, encodeFrame(Buffer.from(JSON.stringify(initiation.message), 'utf8')));
    const akeFrame = await queue.next(akeTimeoutMs, 'AKE_TIMEOUT');
    let akeResponse;
    try {
      akeResponse = JSON.parse(akeFrame.toString('utf8'));
    } catch {
      throw new TcpProtocolError('AKE_INVALID_JSON', 'AKE 响应不是有效 JSON');
    }
    let session;
    try {
      session = completeAkeInitiation(akeResponse, initiation.state);
    } catch (error) {
      throw new TcpProtocolError('AKE_FAILED', `SM2 密钥协商失败: ${error.message}`);
    }

    const plaintext = Buffer.from(JSON.stringify(buildPostMobileRecordMessage(record)), 'utf8');
    const encrypted = sm4OfbEncrypt(plaintext, session.key, session.iv);
    await write(socket, encodeFrame(encrypted));

    const responseFrame = await queue.next(responseTimeoutMs, 'TCP_RESPONSE_TIMEOUT');
    let response;
    try {
      response = JSON.parse(sm4OfbDecrypt(responseFrame, session.key, session.iv).toString('utf8'));
    } catch (error) {
      throw new TcpProtocolError('TCP_RESPONSE_INVALID', `打卡响应解码失败: ${error.message}`);
    }

    if (String(response?.RETURN ?? '').toLowerCase() !== 'postmobilerecord') {
      throw new TcpProtocolError('TCP_RESPONSE_INVALID', 'TCP 响应 RETURN 不是 PostMobileRecord', response);
    }
    if (String(response?.PARAM?.result ?? '').toLowerCase() !== 'success') {
      throw new TcpProtocolError('TCP_REJECTED', response?.PARAM?.reason || 'TCP 上传记录失败', response);
    }

    return { response };
  } finally {
    queue.close();
    socket.destroy();
  }
}
