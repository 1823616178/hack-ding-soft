import crypto from 'node:crypto';

const SM3_SIZE = 32;
const SM4_BLOCK_SIZE = 16;

export function sm3(data) {
  return crypto.createHash('sm3').update(data).digest();
}

export function sm3Kdf(data, outputLength) {
  if (!Number.isInteger(outputLength) || outputLength < 0) {
    throw new RangeError('outputLength must be a nonnegative integer');
  }
  const input = Buffer.from(data);
  const blocks = [];
  for (let counter = 1; Buffer.concat(blocks).length < outputLength; counter += 1) {
    const counterBytes = Buffer.alloc(4);
    counterBytes.writeUInt32BE(counter);
    blocks.push(sm3(Buffer.concat([input, counterBytes])));
  }
  return Buffer.concat(blocks).subarray(0, outputLength);
}

export function pkcs7Pad(data, blockSize = SM4_BLOCK_SIZE) {
  const input = Buffer.from(data);
  const padding = blockSize - (input.length % blockSize);
  return Buffer.concat([input, Buffer.alloc(padding, padding)]);
}

export function pkcs7Unpad(data, blockSize = SM4_BLOCK_SIZE) {
  const input = Buffer.from(data);
  if (input.length === 0 || input.length % blockSize !== 0) {
    throw new Error('Invalid PKCS7 data length');
  }
  const padding = input[input.length - 1];
  if (padding < 1 || padding > blockSize) throw new Error('Invalid PKCS7 padding');
  const start = input.length - padding;
  for (let index = start; index < input.length; index += 1) {
    if (input[index] !== padding) throw new Error('Invalid PKCS7 padding');
  }
  return input.subarray(0, start);
}

export function sm4OfbEncrypt(data, key, iv, { pad = true } = {}) {
  if (key.length !== SM4_BLOCK_SIZE || iv.length !== SM4_BLOCK_SIZE) {
    throw new Error('SM4 key and IV must both be 16 bytes');
  }
  const input = pad ? pkcs7Pad(data) : Buffer.from(data);
  const cipher = crypto.createCipheriv('sm4-ofb', key, iv);
  cipher.setAutoPadding(false);
  return Buffer.concat([cipher.update(input), cipher.final()]);
}

export function sm4OfbDecrypt(data, key, iv, { unpad = true } = {}) {
  if (key.length !== SM4_BLOCK_SIZE || iv.length !== SM4_BLOCK_SIZE) {
    throw new Error('SM4 key and IV must both be 16 bytes');
  }
  if (data.length % SM4_BLOCK_SIZE !== 0) {
    throw new Error('Padded SM4/OFB ciphertext must be block-aligned');
  }
  const decipher = crypto.createDecipheriv('sm4-ofb', key, iv);
  decipher.setAutoPadding(false);
  const output = Buffer.concat([decipher.update(data), decipher.final()]);
  return unpad ? pkcs7Unpad(output) : output;
}

export function deriveSm4KeyAndIv(secretKey, mode) {
  const secret = Buffer.from(secretKey);
  const derived = mode === 0 && secret.length === 32 ? secret : sm3Kdf(secret, 32);
  return {
    key: derived.subarray(0, 16),
    iv: derived.subarray(16, 32),
    derived,
  };
}

export function encodeFrame(payload) {
  const body = Buffer.from(payload);
  const header = Buffer.alloc(4);
  header.writeUInt32BE(body.length);
  return Buffer.concat([header, body]);
}

export class FrameDecoder {
  constructor({ maxFrameBytes = 16 * 1024 * 1024 } = {}) {
    this.buffer = Buffer.alloc(0);
    this.maxFrameBytes = maxFrameBytes;
  }

  push(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    const frames = [];

    while (this.buffer.length >= 4) {
      const size = this.buffer.readUInt32BE(0);
      if (size > this.maxFrameBytes) {
        throw new Error(`Frame exceeds ${this.maxFrameBytes} bytes`);
      }
      if (size === 0) {
        this.buffer = this.buffer.subarray(4);
        continue;
      }
      if (this.buffer.length < size + 4) break;
      frames.push(this.buffer.subarray(4, size + 4));
      this.buffer = this.buffer.subarray(size + 4);
    }

    return frames;
  }
}
