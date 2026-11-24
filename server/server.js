import Fastify from 'fastify';
import { createClient } from '@supabase/supabase-js';
import { createHash, createPublicKey, verify as cryptoVerify } from 'node:crypto';
import { startAnchor } from './anchor.js';

if (!process.env.SUPABASE_URL || !process.env.SUPABASE_SECRET_KEY) {
  console.error(['SUPABASE_URL', 'SUPABASE_SECRET_KEY'].filter(k => !process.env[k]).join(' '));
  process.exit(1);
}

const supabase = createClient(process.env.SUPABASE_URL, process.env.SUPABASE_SECRET_KEY, {
  auth: { persistSession: false, autoRefreshToken: false }
});

const app = Fastify({ logger: false });
app.addContentTypeParser('application/octet-stream', { parseAs: 'buffer', bodyLimit: 52428800 }, (_, b, done) => done(null, b));

app.addHook('onRequest', async (req) => { console.log(`[REQ] ${req.method} ${req.url.slice(0, 80)}`); });

app.get('/health', async () => ({ ok: true }));

const schema = {
  params: { type: 'object', required: ['session', 'seq'], properties: {
    session: { type: 'string', pattern: '^\\d{1,15}$' }, seq: { type: 'string', pattern: '^\\d{1,9}$' }
  }},
  querystring: { type: 'object', required: ['kind', 'ts', 'lat', 'lon', 'sha256', 'device', 'name', 'sig', 'pk'], properties: {
    kind: { type: 'string', enum: ['video', 'photo'] },
    ts: { type: 'string', pattern: '^\\d{10,16}$' },
    lat: { type: 'string', pattern: '^(na|-?\\d{1,3}\\.\\d{5})$' },
    lon: { type: 'string', pattern: '^(na|-?\\d{1,3}\\.\\d{5})$' },
    sha256: { type: 'string', pattern: '^[0-9a-f]{64}$' },
    device: { type: 'string', pattern: '^[A-Za-z0-9 ._-]{1,64}$' },
    name: { type: 'string', minLength: 1, maxLength: 40 },
    sig: { type: 'string', minLength: 1 },
    pk: { type: 'string', minLength: 1 }
  }}
};

const authHook = async (req, reply) => {
  const m = (req.headers.authorization || '').match(/^Bearer (.+)$/);
  if (!m) return reply.status(401).send({ error: 'unauthorized' });
  const { data: { user } = {}, error } = await supabase.auth.getUser(m[1]);
  if (error) {
    if (error.status && error.status < 500) return reply.status(401).send({ error: 'unauthorized' });
    throw error;
  }
  if (!user) return reply.status(401).send({ error: 'unauthorized' });
  req.user = user;
};

function b64urlDecode(s) {
  return Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/'), 'base64');
}

app.put('/v1/evidence/:session/:seq', { schema, onRequest: authHook }, async (req, reply) => {
  const { session, seq } = req.params;
  const { kind, ts, lat, lon, sha256: claimed, device, sig: sigB64, pk: pkB64 } = req.query;
  const name = req.query.name.trim();
  if ((lat !== 'na' && Math.abs(Number(lat)) > 90) || (lon !== 'na' && Math.abs(Number(lon)) > 180)) {
    return reply.status(400).send({ error: 'bad_coordinates' });
  }
  if (!req.body || req.body.length === 0) return reply.status(400).send({ error: 'empty_body' });

  let pubKeyObj;
  try {
    const pkDer = b64urlDecode(pkB64);
    pubKeyObj = createPublicKey({ key: pkDer, format: 'der', type: 'spki' });
    if (pubKeyObj.asymmetricKeyType !== 'ec') throw new Error('not ec');
  } catch { return reply.status(400).send({ error: 'invalid_pk' }); }

  const pkDer = b64urlDecode(pkB64);
  const fp = createHash('sha256').update(pkDer).digest('hex');
  const record = `martyra1|${fp}|${claimed}|${session}|${seq}|${ts}|${lat}|${lon}|${kind}|${device}`;
  const sigDer = b64urlDecode(sigB64);
  const valid = cryptoVerify('SHA256', Buffer.from(record, 'utf8'), { key: pubKeyObj, dsaEncoding: 'der' }, sigDer);
  if (!valid) return reply.status(403).send({ error: 'bad_signature' });

  const uid = req.user.id;
  const { data: prof, error: pErr } = await supabase.from('profiles').select('disabled, pubkey').eq('id', uid).maybeSingle();
  if (pErr) throw pErr;
  if (prof) {
    if (prof.disabled) return reply.status(403).send({ error: 'disabled' });
    if (prof.pubkey !== pkB64) return reply.status(403).send({ error: 'key_mismatch' });
  } else {
    const { error: upErr } = await supabase.from('profiles').insert({ id: uid, name, pubkey: pkB64 });
    if (upErr) throw upErr;
  }

  const { data: existing, error: exErr } = await supabase.from('evidence')
    .select('claimed_sha256').eq('user_id', uid).eq('session', session).eq('seq', seq).maybeSingle();
  if (exErr) throw exErr;
  if (existing) {
    if (existing.claimed_sha256 === claimed) return reply.send({ duplicate: true });
    return reply.status(409).send({ error: 'conflict' });
  }

  const ext = kind === 'photo' ? 'jpg' : 'mp4';
  const mime = kind === 'photo' ? 'image/jpeg' : 'video/mp4';
  const storagePath = `${uid}/${session}/${String(seq).padStart(4, '0')}.${ext}`;
  const { error: stErr } = await supabase.storage.from('evidence').upload(storagePath, req.body, { contentType: mime, upsert: true });
  if (stErr) throw stErr;

  const sha = createHash('sha256').update(req.body).digest('hex');
  const hashOk = sha === claimed;
  const { error: inErr } = await supabase.from('evidence').insert({
    user_id: uid, session, seq, kind, path: storagePath,
    captured_at: new Date(Number(ts)).toISOString(),
    lat: lat === 'na' ? null : Number(lat), lon: lon === 'na' ? null : Number(lon),
    device, sha256: sha, claimed_sha256: claimed, size_bytes: req.body.length,
    record, sig: sigB64
  });
  if (inErr) {
    if (inErr.code === '23505') return reply.send({ duplicate: true });
    throw inErr;
  }
  return reply.send({ hash_ok: hashOk });
});

app.setErrorHandler((err, _req, reply) => {
  if (err.code && err.code.startsWith('FST_')) return reply.send(err);
  console.error(err.message);
  reply.status(500).send({ error: 'server_error' });
});

const port = parseInt(process.env.PORT || '3000', 10);
const host = process.env.HOST || '127.0.0.1';
app.listen({ port, host }, (err) => {
  if (err) { console.error(err.message); process.exit(1); }
  console.log(port);
});

const { CHAIN_RPC_URL, CHAIN_PRIVATE_KEY, CONTRACT_ADDRESS } = process.env;
if (CHAIN_RPC_URL && CHAIN_PRIVATE_KEY && CONTRACT_ADDRESS) {
  startAnchor(supabase);
} else {
  console.log('anchoring off (CHAIN_RPC_URL/CHAIN_PRIVATE_KEY/CONTRACT_ADDRESS not set)');
}
