import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CURVES, pointFromHex, pointMultiply } from './sm2.js';
import { TcpProtocolError } from './tcp-client.js';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_CONFIG_PATH = path.join(ROOT, '..', 'sm2.config.local.json');

function parseHex64(value, field) {
  const text = String(value ?? '').trim().replace(/^0x/i, '');
  if (!/^[0-9a-fA-F]{64}$/.test(text)) {
    throw new TcpProtocolError('SM2_CONFIG_INVALID', `${field} 必须是 64 个十六进制字符`);
  }
  return text;
}

export async function resolveSm2Config(input = {}) {
  if (input.sm2?.curve && input.sm2?.privateKey && input.sm2?.publicKey) {
    return input.sm2;
  }

  let source = input.sm2;
  if (!source) {
    const configPath = input.sm2ConfigPath || DEFAULT_CONFIG_PATH;
    try {
      source = JSON.parse(await readFile(configPath, 'utf8'));
    } catch (error) {
      throw new TcpProtocolError('SM2_CONFIG_REQUIRED',
        `TCP 环境需要 SM2 配置。请创建 sm2.config.local.json（参考 sm2.config.example.json）：${error.message}`);
    }
  }

  const curveName = source.curve || 'sm2p256v1';
  const curve = CURVES[curveName];
  if (!curve) throw new TcpProtocolError('SM2_CONFIG_INVALID', `未知 SM2 曲线: ${curveName}`);

  const privateKeyHex = parseHex64(source.privateKey, 'privateKey');
  const publicKeyX = parseHex64(source.publicKeyX, 'publicKeyX');
  const publicKeyY = parseHex64(source.publicKeyY, 'publicKeyY');
  const privateKey = BigInt(`0x${privateKeyHex}`);
  if (privateKey <= 0n || privateKey >= curve.n) {
    throw new TcpProtocolError('SM2_CONFIG_INVALID', 'SM2 私钥不在有效范围内');
  }
  const publicKey = pointFromHex(`${publicKeyX}${publicKeyY}`, curve);
  const derivedPublicKey = pointMultiply({ x: curve.gx, y: curve.gy }, privateKey, curve);
  if (derivedPublicKey.x !== publicKey.x || derivedPublicKey.y !== publicKey.y) {
    throw new TcpProtocolError('SM2_CONFIG_INVALID', 'SM2 私钥与公钥坐标不匹配');
  }

  return {
    ownerId: String(source.ownerId || '汉王人脸仪客户端'),
    privateKey,
    publicKey,
    curve,
    uppercase: source.uppercase !== false,
  };
}
