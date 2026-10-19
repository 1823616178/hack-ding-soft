import test from 'node:test';
import assert from 'node:assert/strict';
import {
  completeAkeInitiation,
  computeZ,
  createAkeInitiation,
  CURVES,
  isOnCurve,
  keyAgreement,
  pointFromHex,
  pointMultiply,
  pointToHex,
} from '../lib/sm2.js';

function party(curve, ownerId, privateValue, ephemeralValue) {
  const publicKey = pointMultiply({ x: curve.gx, y: curve.gy }, privateValue, curve);
  const R = pointMultiply({ x: curve.gx, y: curve.gy }, ephemeralValue, curve);
  return {
    ownerId,
    privateKey: privateValue,
    publicKey,
    r: ephemeralValue,
    R,
    Z: computeZ(ownerId, publicKey, curve),
  };
}

for (const [curveName, curve] of Object.entries(CURVES)) {
  test(`SM2 key agreement is symmetric on ${curveName}`, () => {
    const client = party(curve, '汉王人脸仪客户端', 2n, 3n);
    const server = party(curve, 'test-server', 5n, 7n);

    const initiator = keyAgreement({
      ...client,
      partnerPublicKey: server.publicKey,
      partnerR: server.R,
      partnerZ: server.Z,
      isInitiator: true,
      curve,
    });
    const responder = keyAgreement({
      ...server,
      partnerPublicKey: client.publicKey,
      partnerR: client.R,
      partnerZ: client.Z,
      isInitiator: false,
      curve,
    });

    assert.deepEqual(initiator.sharedKey, responder.sharedKey);
    assert.equal(initiator.sharedKey.length, 32);
    assert.notEqual(initiator.sharedKey.toString('hex'), '00'.repeat(32));

    const initiation = createAkeInitiation({
      ownerId: client.ownerId,
      privateKey: client.privateKey,
      publicKey: client.publicKey,
      curve,
      ephemeralScalar: client.r,
    });
    assert.equal(initiation.message.COMMAND, 'AKE');
    assert.equal(initiation.message.PARAM.sn, client.ownerId);
    assert.equal(initiation.message.PARAM.key.length, 2);
    assert.deepEqual(pointFromHex(initiation.message.PARAM.key[0], curve), client.publicKey);
    assert.deepEqual(pointFromHex(initiation.message.PARAM.key[1], curve), client.R);

    const completed = completeAkeInitiation({
      RETURN: 'AKE',
      PARAM: {
        result: 'success',
        sn: server.ownerId,
        key: [pointToHex(server.publicKey), pointToHex(server.R)],
      },
    }, initiation.state);
    assert.deepEqual(completed.sharedKey, initiator.sharedKey);
    assert.equal(completed.key.length, 16);
    assert.equal(completed.iv.length, 16);
  });
}

test('point encoding rejects values from another curve', () => {
  const point = pointMultiply({ x: CURVES.nistP256.gx, y: CURVES.nistP256.gy }, 2n, CURVES.nistP256);
  const encoded = pointToHex(point, { uppercase: true });
  assert.equal(encoded.length, 128);
  assert.equal(encoded, encoded.toUpperCase());
  assert.equal(isOnCurve(point, CURVES.nistP256), true);
  assert.throws(() => pointFromHex(encoded, CURVES.sm2p256v1), /not on the selected curve/);
});
