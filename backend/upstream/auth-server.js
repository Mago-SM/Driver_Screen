import { randomBytes, scrypt as scryptCallback, timingSafeEqual, createHash } from 'node:crypto';
import { promisify } from 'node:util';
import db from './db.js';

const scrypt = promisify(scryptCallback);
const SESSION_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const send = (res, code, data) => {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(data));
};
const error = (res, code, message) => send(res, code, { error: { message } });

async function bodyJson(req) {
  let text = '';
  for await (const chunk of req) {
    text += chunk.toString();
    if (Buffer.byteLength(text, 'utf8') > 16000) throw Object.assign(new Error('요청이 너무 큽니다.'), { status: 413 });
  }
  try { return JSON.parse(text); }
  catch { throw Object.assign(new Error('잘못된 JSON 요청입니다.'), { status: 400 }); }
}

async function hashPassword(password) {
  const salt = randomBytes(16).toString('hex');
  const derived = await scrypt(password, salt, 64);
  return `scrypt:${salt}:${derived.toString('hex')}`;
}
async function verifyPassword(password, encoded) {
  if (typeof encoded !== 'string') return false;
  const [algo, salt, hash] = encoded.split(':');
  if (algo !== 'scrypt' || !/^[a-f0-9]{32}$/i.test(salt ?? '') || !/^[a-f0-9]{128}$/i.test(hash ?? '')) return false;
  const actual = await scrypt(password, salt, 64);
  return timingSafeEqual(actual, Buffer.from(hash, 'hex'));
}

const cookieName = 'bus_session';
function readCookie(req) {
  const items = (req.headers.cookie || '').split(';');
  const item = items.find(x => x.trim().startsWith(cookieName + '='));
  return item ? item.trim().slice(cookieName.length + 1) : null;
}
const sha256 = value => createHash('sha256').update(value).digest('hex');
function setCookie(res, token) {
  const secure = process.env.COOKIE_SECURE === 'true' ? '; Secure' : '';
  res.setHeader('Set-Cookie', `${cookieName}=${token}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${SESSION_AGE_MS / 1000}${secure}`);
}
function clearCookie(res) {
  const secure = process.env.COOKIE_SECURE === 'true' ? '; Secure' : '';
  res.setHeader('Set-Cookie', `${cookieName}=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0${secure}`);
}
async function createSession(res, userId) {
  const token = randomBytes(32).toString('hex');
  await db.execute(
    'INSERT INTO user_sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)',
    [sha256(token), userId, new Date(Date.now() + SESSION_AGE_MS)]
  );
  setCookie(res, token);
}
export async function currentUser(req) {
  const token = readCookie(req);
  if (!token || !/^[a-f0-9]{64}$/.test(token)) return null;
  const [rows] = await db.execute(
    `SELECT u.id, u.login_id, u.name, u.role FROM user_sessions s
     JOIN users u ON u.id = s.user_id
     WHERE s.token_hash = ? AND s.expires_at > NOW() LIMIT 1`,
    [sha256(token)]
  );
  return rows[0] || null;
}
export async function requireUser(req, res) {
  const user = await currentUser(req);
  if (!user) error(res, 401, '로그인이 필요합니다.');
  return user;
}
const validBirth = value => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const d = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(d.valueOf()) && d.toISOString().slice(0, 10) === value && value <= new Date().toISOString().slice(0,10);
};

export async function authHandler(req, res, pathname) {
  try {
    if (pathname === '/api/auth/me' && req.method === 'GET') {
      const user = await currentUser(req);
      return user ? send(res, 200, { user }) : error(res, 401, '로그인하지 않았습니다.');
    }
    if (pathname === '/api/auth/register' && req.method === 'POST') {
      const x = await bodyJson(req);
      const name = typeof x.name === 'string' ? x.name.trim() : '';
      const loginId = typeof x.loginId === 'string' ? x.loginId.trim() : '';
      const email = typeof x.email === 'string' ? x.email.trim() : '';
      const phone = typeof x.phone === 'string' ? x.phone.trim() : '';
      const role = x.role ?? 'PASSENGER';
      if (!['PASSENGER', 'DRIVER'].includes(role)) {
        return error(res, 400, '올바르지 않은 회원 유형입니다.');
      }
      if (!name || name.length > 50 || !/^[a-zA-Z0-9_]{4,50}$/.test(loginId) ||
          typeof x.password !== 'string' || x.password.length < 8 || x.password.length > 128 ||
          !validBirth(x.birthDate) || email.length > 255 || phone.length > 20 ||
          (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))) {
        return error(res, 400, '회원가입 정보를 확인해 주세요. 아이디는 영문·숫자·_ 4자 이상, 비밀번호는 8자 이상이어야 합니다.');
      }
      const passwordHash = await hashPassword(x.password);

      const [result] = await db.execute(
        `INSERT INTO users
        (name, email, phone, login_id, password_hash, birth_date, role)
        VALUES (?, ?, ?, ?, ?, ?, ?)`,
        [
          name,
          email || null,
          phone || null,
          loginId,
          passwordHash,
          x.birthDate,
          role
        ]
      );

      await createSession(res, result.insertId);

      return send(res, 201, {
        user: {
          id: result.insertId,
          login_id: loginId,
          name,
          role
        }
      });
    }
    if (pathname === '/api/auth/login' && req.method === 'POST') {
      const x = await bodyJson(req);
      if (typeof x.loginId !== 'string' || typeof x.password !== 'string') return error(res, 400, '아이디와 비밀번호를 입력해 주세요.');
      const [users] = await db.execute(
        'SELECT id, name, login_id, password_hash, role FROM users WHERE login_id = ? LIMIT 1',
        [x.loginId]
      );
      const user = users[0];
      if (!user || !(await verifyPassword(x.password, user.password_hash))) return error(res, 401, '아이디 또는 비밀번호가 올바르지 않습니다.');
      await createSession(res, user.id);
      return send(res, 200, {
        user: {
          id: user.id,
          name: user.name,
          login_id: user.login_id,
          role: user.role
        }
      });
    }
    if (pathname === '/api/auth/logout' && req.method === 'POST') {
      const token = readCookie(req);
      if (token) await db.execute('DELETE FROM user_sessions WHERE token_hash = ?', [sha256(token)]);
      clearCookie(res);
      return send(res, 200, { success: true });
    }
    return error(res, 405, '지원하지 않는 인증 요청입니다.');
  } catch (e) {
    if (e.code === 'ER_DUP_ENTRY') return error(res, 409, '이미 사용 중인 아이디 또는 이메일입니다.');
    console.error('인증 오류:', e);
    return error(res, e.status || 500, e.status ? e.message : '인증 서버 오류가 발생했습니다.');
  }
}

