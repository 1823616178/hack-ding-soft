export const APK_IDENTITY = Object.freeze({
  appVer: '1.0.15.79.20240816',
  appVerCode: '41',
  systemName: 'Android',
  userAgent: 'okhttp/3.11.0',
  source: '1',
});

const FORM_CONTENT_TYPE = 'application/x-www-form-urlencoded';
const TIME_PATTERN = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/;

export class ProtocolInputError extends Error {
  constructor(message, field) {
    super(message);
    this.name = 'ProtocolInputError';
    this.code = 'INVALID_INPUT';
    this.field = field;
  }
}

export function normalizeBaseUrl(value) {
  const raw = String(value ?? '').trim();
  if (!raw) throw new ProtocolInputError('请填写服务器地址', 'baseUrl');

  let url;
  try {
    url = new URL(raw);
  } catch {
    throw new ProtocolInputError('服务器地址格式不正确', 'baseUrl');
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new ProtocolInputError('服务器地址仅支持 http 或 https', 'baseUrl');
  }
  if (url.username || url.password || url.search || url.hash) {
    throw new ProtocolInputError('服务器地址不能包含账号、查询参数或锚点', 'baseUrl');
  }
  if (url.pathname !== '/' && url.pathname !== '') {
    throw new ProtocolInputError('请只填写协议、主机和端口，不要附加路径', 'baseUrl');
  }

  return url.origin;
}

export function manageUrl(baseUrl, endpoint) {
  return `${normalizeBaseUrl(baseUrl)}/manage${endpoint}`;
}

export function formEncode(fields) {
  const params = new URLSearchParams();
  for (const [name, value] of fields) {
    params.append(name, String(value ?? ''));
  }
  return params.toString();
}

export function formatCoordinate(value, kind) {
  const isLat = kind === 'lat';
  if (kind !== 'lat' && kind !== 'lng') {
    throw new TypeError(`Unknown coordinate kind: ${kind}`);
  }

  const text = String(value ?? '').trim();
  if (!text) {
    throw new ProtocolInputError(isLat ? '请填写纬度' : '请填写经度', kind);
  }
  const number = typeof value === 'number' ? value : Number(text);
  if (!Number.isFinite(number)) {
    throw new ProtocolInputError(isLat ? '纬度必须是数字' : '经度必须是数字', kind);
  }
  if (isLat && (number < -90 || number > 90)) {
    throw new ProtocolInputError('纬度必须在 -90 到 90 之间', kind);
  }
  if (!isLat && (number < -180 || number > 180)) {
    throw new ProtocolInputError('经度必须在 -180 到 180 之间', kind);
  }

  return number.toFixed(6);
}

function pad2(value) {
  return String(value).padStart(2, '0');
}

export function formatLocalDateTime(date = new Date()) {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} `
    + `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
}

export function validateCustomTime(value) {
  const text = String(value ?? '').trim();
  const match = TIME_PATTERN.exec(text);
  if (!match) {
    throw new ProtocolInputError('自定义时间格式必须为 yyyy-MM-dd HH:mm:ss', 'customTime');
  }

  const [year, month, day, hour, minute, second] = match.slice(1).map(Number);
  const date = new Date(year, month - 1, day, hour, minute, second);
  if (
    date.getFullYear() !== year
    || date.getMonth() !== month - 1
    || date.getDate() !== day
    || date.getHours() !== hour
    || date.getMinutes() !== minute
    || date.getSeconds() !== second
  ) {
    throw new ProtocolInputError('自定义时间不是有效的日历时间', 'customTime');
  }

  return text;
}

export function resolveTimes({ timeMode = 'apk', customTime = '', now = new Date() } = {}) {
  if (timeMode === 'apk') {
    return {
      checkLocationTime: formatLocalDateTime(now),
      addCardTime: '',
    };
  }
  if (timeMode === 'custom') {
    const time = validateCustomTime(customTime);
    return {
      checkLocationTime: time,
      addCardTime: time,
    };
  }
  throw new ProtocolInputError('未知的时间模式', 'timeMode');
}

export function decodePhoto(photoBase64) {
  const compact = String(photoBase64 ?? '').replace(/\s+/g, '');
  if (!compact) throw new ProtocolInputError('请选择 JPEG 照片', 'photo');
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(compact) || compact.length % 4 !== 0) {
    throw new ProtocolInputError('照片不是有效的 Base64 数据', 'photo');
  }

  const bytes = Buffer.from(compact, 'base64');
  if (bytes.length < 3 || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[2] !== 0xff) {
    throw new ProtocolInputError('源 APK 上传的是 JPEG，请选择 JPEG 照片', 'photo');
  }
  if (bytes.length > 12 * 1024 * 1024) {
    throw new ProtocolInputError('JPEG 照片不能超过 12 MiB', 'photo');
  }

  return bytes;
}

// android.util.Base64.encodeToString(bytes, Base64.DEFAULT):
// RFC 2045 alphabet, 76-character lines, LF separators and a trailing LF.
export function androidBase64(bytes) {
  const encoded = Buffer.from(bytes).toString('base64');
  if (!encoded) return '';
  return `${encoded.match(/.{1,76}/g).join('\n')}\n`;
}

export function encodePhotoForApk(photoBase64) {
  return androidBase64(decodePhoto(photoBase64));
}

function buildHeaders(url, body, { forceNetwork = false } = {}) {
  const headers = {};
  if (forceNetwork) headers['Cache-Control'] = 'no-cache';
  headers['Content-Type'] = FORM_CONTENT_TYPE;
  headers['Content-Length'] = String(Buffer.byteLength(body));
  headers.Host = new URL(url).host;
  headers.Connection = 'Keep-Alive';
  headers['Accept-Encoding'] = 'gzip';
  headers['User-Agent'] = APK_IDENTITY.userAgent;
  return headers;
}

function postRequest(url, fields, options) {
  const body = formEncode(fields);
  return {
    method: 'POST',
    url,
    headers: buildHeaders(url, body, options),
    body,
  };
}

export function buildLoginRequest({ baseUrl, userName, pwd }) {
  if (!String(userName ?? '')) throw new ProtocolInputError('请填写用户名', 'userName');
  if (!String(pwd ?? '')) throw new ProtocolInputError('请填写密码', 'pwd');
  return postRequest(manageUrl(baseUrl, '/m/login.do'), [
    ['userName', userName],
    ['pwd', pwd],
  ]);
}

export function buildCheckLocationRequest({ baseUrl, token, gps, lat, lng, time }) {
  if (!String(token ?? '')) throw new ProtocolInputError('缺少登录 token', 'token');
  return postRequest(manageUrl(baseUrl, '/m/checkLocation.do'), [
    ['gps', gps],
    ['lat', formatCoordinate(lat, 'lat')],
    ['lng', formatCoordinate(lng, 'lng')],
    ['token', token],
    ['time', time],
  ], { forceNetwork: true });
}

export function buildAddCardRequest({
  baseUrl,
  token,
  gps,
  lat,
  lng,
  time = '',
  photoBase64,
  serverSpecifiedMap = 0,
  usedMap = 0,
  currSysVer,
}) {
  if (!String(token ?? '')) throw new ProtocolInputError('缺少登录 token', 'token');
  if (!/^\d+$/.test(String(currSysVer ?? ''))) {
    throw new ProtocolInputError('currSysVer 必须是 Android SDK 整数版本', 'currSysVer');
  }
  if (usedMap !== 0 && usedMap !== 1 && usedMap !== '0' && usedMap !== '1') {
    throw new ProtocolInputError('usedMap 只能是 0 或 1', 'usedMap');
  }

  return postRequest(manageUrl(baseUrl, '/m/addCard.do'), [
    ['token', token],
    ['source', APK_IDENTITY.source],
    ['time', time],
    ['gps', gps],
    ['lat', formatCoordinate(lat, 'lat')],
    ['lng', formatCoordinate(lng, 'lng')],
    ['photo', encodePhotoForApk(photoBase64)],
    ['appVer', APK_IDENTITY.appVer],
    ['appVerCode', APK_IDENTITY.appVerCode],
    ['serverSpecifiedMap', String(serverSpecifiedMap)],
    ['usedMap', String(usedMap)],
    ['systemName', APK_IDENTITY.systemName],
    ['currSysVer', String(currSysVer)],
  ]);
}

export function requestPreview(request, { redactPhoto = true } = {}) {
  const params = new URLSearchParams(request.body);
  if (redactPhoto && params.has('photo')) {
    const photo = params.get('photo');
    params.set('photo', `<JPEG Base64: ${photo.length} chars>`);
  }
  if (params.has('pwd')) params.set('pwd', '<redacted>');
  if (params.has('token')) params.set('token', '<redacted>');

  return {
    method: request.method,
    url: request.url,
    headers: { ...request.headers },
    body: params.toString(),
  };
}
