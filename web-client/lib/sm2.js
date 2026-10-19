import crypto from 'node:crypto';
import { deriveSm4KeyAndIv, sm3, sm3Kdf } from './crypto-sm.js';

function hex(value) {
  return BigInt(`0x${value}`);
}

export const CURVES = Object.freeze({
  sm2p256v1: Object.freeze({
    p: hex('FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFF'),
    a: hex('FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFC'),
    b: hex('28E9FA9E9D9F5E344D5A9E4BCF6509A7F39789F515AB8F92DDBCBD414D940E93'),
    n: hex('FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFF7203DF6B21C6052B53BBF40939D54123'),
    gx: hex('32C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7'),
    gy: hex('BC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0'),
  }),
  nistP256: Object.freeze({
    p: hex('FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF'),
    a: hex('FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC'),
    b: hex('5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B'),
    n: hex('FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551'),
    gx: hex('6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296'),
    gy: hex('4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5'),
  }),
});

function mod(value, modulus) {
  const result = value % modulus;
  return result < 0n ? result + modulus : result;
}

function modPow(base, exponent, modulus) {
  let result = 1n;
  let factor = mod(base, modulus);
  let power = exponent;
  while (power > 0n) {
    if (power & 1n) result = mod(result * factor, modulus);
    factor = mod(factor * factor, modulus);
    power >>= 1n;
  }
  return result;
}

function modInverse(value, modulus) {
  if (mod(value, modulus) === 0n) throw new Error('No modular inverse');
  return modPow(value, modulus - 2n, modulus);
}

export function pointAdd(left, right, curve) {
  if (left === null) return right;
  if (right === null) return left;
  const { p, a } = curve;

  let lambda;
  if (left.x === right.x) {
    if (mod(left.y + right.y, p) === 0n) return null;
    const numerator = mod(3n * left.x * left.x + a, p);
    const denominator = modInverse(2n * left.y, p);
    lambda = mod(numerator * denominator, p);
  } else {
    const numerator = mod(right.y - left.y, p);
    const denominator = modInverse(right.x - left.x, p);
    lambda = mod(numerator * denominator, p);
  }

  const x = mod(lambda * lambda - left.x - right.x, p);
  const y = mod(lambda * (left.x - x) - left.y, p);
  return { x, y };
}

export function pointMultiply(point, scalar, curve) {
  if (scalar < 0n) throw new RangeError('Scalar must be nonnegative');
  let result = null;
  let addend = point;
  let value = scalar;
  while (value > 0n) {
    if (value & 1n) result = pointAdd(result, addend, curve);
    addend = pointAdd(addend, addend, curve);
    value >>= 1n;
  }
  return result;
}

export function isOnCurve(point, curve) {
  if (point === null) return true;
  const { p, a, b } = curve;
  if (point.x < 0n || point.x >= p || point.y < 0n || point.y >= p) return false;
  const left = mod(point.y * point.y, p);
  const right = mod(point.x * point.x * point.x + a * point.x + b, p);
  return left === right;
}

export function bufferToBigInt(buffer) {
  const text = Buffer.from(buffer).toString('hex');
  return text ? BigInt(`0x${text}`) : 0n;
}

export function bigIntToBuffer(value, length = 32) {
  const text = value.toString(16).padStart(length * 2, '0');
  if (text.length > length * 2) throw new RangeError('Integer does not fit target length');
  return Buffer.from(text, 'hex');
}

export function pointToHex(point, { uppercase = true } = {}) {
  if (!point) throw new Error('Cannot encode point at infinity');
  const text = `${bigIntToBuffer(point.x).toString('hex')}${bigIntToBuffer(point.y).toString('hex')}`;
  return uppercase ? text.toUpperCase() : text;
}

export function pointFromHex(text, curve) {
  if (!/^[0-9a-fA-F]{128}$/.test(text || '')) throw new Error('Expected a 128-character EC point');
  const point = {
    x: BigInt(`0x${text.slice(0, 64)}`),
    y: BigInt(`0x${text.slice(64)}`),
  };
  if (!isOnCurve(point, curve)) throw new Error('EC point is not on the selected curve');
  return point;
}

function generator(curve) {
  return { x: curve.gx, y: curve.gy };
}

export function computeZ(ownerId, publicKey, curve) {
  const userId = Buffer.from(ownerId, 'utf8');
  const bitLength = userId.length * 8;
  if (bitLength > 0xffff) throw new RangeError('SM2 owner ID is too long');
  const prefix = Buffer.alloc(2);
  prefix.writeUInt16BE(bitLength);
  return sm3(Buffer.concat([
    prefix,
    userId,
    bigIntToBuffer(curve.a),
    bigIntToBuffer(curve.b),
    bigIntToBuffer(curve.gx),
    bigIntToBuffer(curve.gy),
    bigIntToBuffer(publicKey.x),
    bigIntToBuffer(publicKey.y),
  ]));
}

function randomScalar(curve) {
  for (;;) {
    const candidate = bufferToBigInt(crypto.randomBytes(32));
    if (candidate > 0n && candidate < curve.n) return candidate;
  }
}

function bitLength(value) {
  return value.toString(2).length;
}

export function keyAgreement({
  privateKey,
  publicKey,
  r,
  R,
  Z,
  partnerPublicKey,
  partnerR,
  partnerZ,
  isInitiator,
  curve,
}) {
  for (const point of [publicKey, R, partnerPublicKey, partnerR]) {
    if (!isOnCurve(point, curve)) throw new Error('Invalid EC point');
  }
  if (!Z || Z.length !== 32 || !partnerZ || partnerZ.length !== 32) {
    throw new Error('SM2 Z values must be 32 bytes');
  }

  const w1 = 1n << BigInt((bitLength(curve.n) >> 1) - 1);
  const w2 = w1 - 1n;
  const x = w1 + (R.x & w2);
  const t = mod(privateKey + r * x, curve.n);
  const partnerX = w1 + (partnerR.x & w2);
  const partnerPoint = pointAdd(partnerPublicKey, pointMultiply(partnerR, partnerX, curve), curve);
  const V = pointMultiply(partnerPoint, t, curve);
  if (V === null) throw new Error('SM2 key agreement produced point at infinity');

  const zBytes = Buffer.concat([
    bigIntToBuffer(V.x),
    bigIntToBuffer(V.y),
    isInitiator ? Z : partnerZ,
    isInitiator ? partnerZ : Z,
  ]);
  return {
    sharedKey: sm3Kdf(zBytes, 32),
    V,
  };
}

export function createAkeInitiation({
  ownerId,
  privateKey,
  publicKey,
  curve,
  ephemeralScalar,
  uppercase = true,
}) {
  if (privateKey <= 0n || privateKey >= curve.n) throw new Error('Invalid SM2 private key');
  if (!isOnCurve(publicKey, curve)) throw new Error('Invalid SM2 public key');
  const r = ephemeralScalar ?? randomScalar(curve);
  if (r <= 0n || r >= curve.n) throw new Error('Invalid SM2 ephemeral scalar');
  const R = pointMultiply(generator(curve), r, curve);
  const Z = computeZ(ownerId, publicKey, curve);

  return {
    message: {
      COMMAND: 'AKE',
      PARAM: {
        sn: ownerId,
        key: [pointToHex(publicKey, { uppercase }), pointToHex(R, { uppercase })],
      },
    },
    state: { ownerId, privateKey, publicKey, r, R, Z, curve, uppercase },
  };
}

export function completeAkeInitiation(response, state) {
  if (response?.RETURN !== 'AKE' || response?.PARAM?.result !== 'success') {
    throw new Error('AKE response was not successful');
  }
  const keys = response.PARAM.key;
  if (!Array.isArray(keys) || keys.length < 2 || !keys[0] || !keys[1]) {
    throw new Error('SM2 AKE response does not contain both EC points');
  }

  const curve = state.curve;
  const partnerPublicKey = pointFromHex(keys[0], curve);
  const partnerR = pointFromHex(keys[1], curve);
  const partnerZ = computeZ(response.PARAM.sn, partnerPublicKey, curve);
  const { sharedKey } = keyAgreement({
    ...state,
    partnerPublicKey,
    partnerR,
    partnerZ,
    isInitiator: true,
    curve,
  });
  return {
    ...deriveSm4KeyAndIv(sharedKey, 0),
    sharedKey,
    partnerPublicKey,
    partnerR,
    partnerZ,
  };
}
