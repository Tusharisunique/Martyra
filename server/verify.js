import { createHash, createPublicKey, verify as cryptoVerify } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { ethers } from 'ethers';
import { leaf, root } from './merkle.js';

const ABI = ['function anchoredAt(bytes32) view returns (uint64)'];
const [,, userId, session, seq, localFile] = process.argv;
if (!userId || !session || !seq) { console.error('usage: npm run verify -- <user_id> <session> <seq> [file]'); process.exit(1); }

const b64url = s => Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/'), 'base64');
let failed = false;
function pass(m) { console.log('PASS:', m); }
function fail(m) { console.log('FAIL:', m); failed = true; }

async function sfetch(path, tok) {
  const r = await fetch(`${process.env.SUPABASE_URL}${path}`, {
    headers: { apikey: process.env.SUPABASE_KEY, Authorization: `Bearer ${tok}`, Accept: 'application/json' }
  });
  if (!r.ok) throw new Error(`${path} ${r.status}`);
  return r.json();
}

async function main() {
  const ar = await fetch(`${process.env.SUPABASE_URL}/auth/v1/token?grant_type=password`, {
    method: 'POST', headers: { apikey: process.env.SUPABASE_KEY, 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: process.env.ADMIN_EMAIL, password: process.env.ADMIN_PASSWORD })
  });
  if (!ar.ok) { console.error('admin sign-in failed'); process.exit(1); }
  const tok = (await ar.json()).access_token;

  const rows = await sfetch(`/rest/v1/evidence?user_id=eq.${userId}&session=eq.${session}&seq=eq.${seq}&select=*`, tok);
  if (!rows.length || !rows[0].batch_id) { console.log('NOT YET ANCHORED'); process.exit(2); }
  const row = rows[0];

  const [[prof], [batch]] = await Promise.all([
    sfetch(`/rest/v1/profiles?id=eq.${userId}&select=pubkey`, tok),
    sfetch(`/rest/v1/anchor_batches?id=eq.${row.batch_id}&select=*`, tok)
  ]);
  if (!batch) { console.log('NOT YET ANCHORED'); process.exit(2); }

  let fileBytes;
  if (localFile) {
    fileBytes = readFileSync(localFile);
  } else {
    const sr = await fetch(`${process.env.SUPABASE_URL}/storage/v1/object/sign/evidence/${row.path}`, {
      method: 'POST', headers: { apikey: process.env.SUPABASE_KEY, Authorization: `Bearer ${tok}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ expiresIn: 300 })
    });
    const { signedURL } = await sr.json();
    fileBytes = Buffer.from(await (await fetch(`${process.env.SUPABASE_URL}/storage/v1${signedURL}`)).arrayBuffer());
  }

  const record = row.record;
  const parts = record.split('|');
  parts[3] === session && parts[4] === seq ? pass('record session/seq match') : fail('record session/seq mismatch');

  const fileHash = createHash('sha256').update(fileBytes).digest('hex');
  fileHash === parts[2] ? pass('file hash matches record') : fail(`file hash mismatch`);

  try {
    const pkDer = b64url(prof.pubkey);
    const fp = createHash('sha256').update(pkDer).digest('hex');
    const pub = createPublicKey({ key: pkDer, format: 'der', type: 'spki' });
    const ok = cryptoVerify('SHA256', Buffer.from(record, 'utf8'), { key: pub, dsaEncoding: 'der' }, b64url(row.sig));
    fp === parts[1] && ok ? pass('signature valid') : fail('signature invalid or fp mismatch');
  } catch (e) { fail('signature check: ' + e.message); }

  const lf = leaf(record, row.sig).toString('hex');
  batch.leaves.includes(lf) ? pass('leaf is in batch') : fail('leaf not in batch');

  const cr = root(batch.leaves.map(l => Buffer.from(l, 'hex'))).toString('hex');
  cr === batch.root ? pass('Merkle root matches') : fail(`Merkle root mismatch`);

  if (!process.env.CHAIN_RPC_URL || !process.env.CONTRACT_ADDRESS) { fail('chain env not set'); }
  else {
    const c = new ethers.Contract(process.env.CONTRACT_ADDRESS, ABI, new ethers.JsonRpcProvider(process.env.CHAIN_RPC_URL));
    const ts = await c.anchoredAt('0x' + batch.root);
    ts === 0n ? fail('root not anchored on chain') : pass(`anchored at ${new Date(Number(ts) * 1000).toISOString()}`);
  }

  if (failed) { console.log('\nTAMPERED'); process.exit(1); }
  console.log('\nVERIFIED'); process.exit(0);
}

main().catch(e => { console.error(e.message); process.exit(1); });
