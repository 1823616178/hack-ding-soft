import test from 'node:test';
import assert from 'node:assert/strict';
import {
  deriveSm4KeyAndIv,
  encodeFrame,
  FrameDecoder,
  pkcs7Pad,
  pkcs7Unpad,
  sm3,
  sm3Kdf,
  sm4OfbDecrypt,
  sm4OfbEncrypt,
} from '../lib/crypto-sm.js';

test('SM3 matches the standard abc digest', () => {
  assert.equal(sm3('abc').toString('hex'),
    '66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0');
});

test('SM3 KDF is counter-based and deterministic', () => {
  const first = sm3Kdf(Buffer.from('shared secret'), 48);
  const second = sm3Kdf(Buffer.from('shared secret'), 48);
  assert.equal(first.length, 48);
  assert.deepEqual(first, second);
  assert.notDeepEqual(first.subarray(0, 32), first.subarray(32));
});

test('SM4/OFB/PKCS7 encrypts and decrypts, including full-block input', () => {
  const key = Buffer.alloc(16, 0x11);
  const iv = Buffer.alloc(16, 0x22);
  for (const value of [Buffer.from('hello'), Buffer.alloc(16, 0x33), Buffer.alloc(32, 0x44)]) {
    const encrypted = sm4OfbEncrypt(value, key, iv);
    assert.equal(encrypted.length % 16, 0);
    assert.notDeepEqual(encrypted, value);
    assert.deepEqual(sm4OfbDecrypt(encrypted, key, iv), value);
  }
});

test('PKCS7 validates padding and key derivation follows mode rules', () => {
  assert.equal(pkcs7Pad(Buffer.alloc(16)).length, 32);
  assert.deepEqual(pkcs7Unpad(pkcs7Pad(Buffer.from('x'))), Buffer.from('x'));
  assert.throws(() => pkcs7Unpad(Buffer.alloc(16)), /padding/);

  const secret = Buffer.alloc(32, 0x55);
  assert.deepEqual(deriveSm4KeyAndIv(secret, 0).derived, secret);
  assert.notDeepEqual(deriveSm4KeyAndIv(secret, 1).derived, secret);
  assert.equal(deriveSm4KeyAndIv(secret, 1).key.length, 16);
  assert.equal(deriveSm4KeyAndIv(secret, 1).iv.length, 16);
});

test('frame codec handles fragmentation, coalescing and zero-length frames', () => {
  const first = encodeFrame(Buffer.from('first'));
  const second = encodeFrame(Buffer.from('second'));
  const combined = Buffer.concat([first, Buffer.alloc(4), second]);
  const decoder = new FrameDecoder();
  const output = [];
  for (const byte of combined) output.push(...decoder.push(Buffer.from([byte])));
  assert.deepEqual(output.map((frame) => frame.toString()), ['first', 'second']);
});
