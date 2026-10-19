import {
  APK_IDENTITY,
  encodePhotoForApk,
  formatCoordinate,
  ProtocolInputError,
} from './protocol.js';

export function buildPostMobileRecord({
  employId,
  photoBase64,
  time,
  gps,
  lat,
  lng,
  serverSpecifiedMap = 0,
  usedMap = 0,
  currSysVer,
}) {
  if (!String(employId ?? '')) throw new ProtocolInputError('TCP 打卡缺少 employId', 'employId');
  if (!String(time ?? '')) throw new ProtocolInputError('TCP 打卡缺少时间', 'time');
  if (!/^\d+$/.test(String(currSysVer ?? ''))) {
    throw new ProtocolInputError('currSysVer 必须是 Android SDK 整数版本', 'currSysVer');
  }
  if (usedMap !== 0 && usedMap !== 1 && usedMap !== '0' && usedMap !== '1') {
    throw new ProtocolInputError('usedMap 只能是 0 或 1', 'usedMap');
  }

  return {
    capturejpg: encodePhotoForApk(photoBase64),
    id: String(employId),
    time: String(time),
    gps: String(gps ?? ''),
    lat: formatCoordinate(lat, 'lat'),
    lng: formatCoordinate(lng, 'lng'),
    recogSource: APK_IDENTITY.source,
    appVer: APK_IDENTITY.appVer,
    appVerCode: APK_IDENTITY.appVerCode,
    serverSpecifiedMap: String(serverSpecifiedMap),
    usedMap: String(usedMap),
    systemName: APK_IDENTITY.systemName,
    currSysVer: String(currSysVer),
  };
}

export function buildPostMobileRecordMessage(record) {
  return {
    COMMAND: 'PostMobileRecord',
    PARAM: record,
  };
}

export function tcpRequestPreview(host, port, record) {
  return {
    method: 'TCP',
    url: `tcp://${host}:${port}`,
    headers: {
      Protocol: 'MINA frame: 4-byte big-endian length + payload',
      Encryption: 'SM2 AKE + SM3 KDF + SM4/OFB/PKCS7',
    },
    body: JSON.stringify({
      COMMAND: 'PostMobileRecord',
      PARAM: {
        ...record,
        capturejpg: `<JPEG Base64: ${record.capturejpg.length} chars>`,
      },
    }),
  };
}
