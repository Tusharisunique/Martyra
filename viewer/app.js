/* Martyra Admin Viewer — app.js */
'use strict';
const SUPABASE_URL = 'https://msqfpovrcnkebuzawfhf.supabase.co';
const SUPABASE_KEY = 'sb_publishable_0d3lPoCN9lSX1LTEQJpmXA_gdVjLT2c';
const sb = supabase.createClient(SUPABASE_URL, SUPABASE_KEY);

const el = (t, a = {}, ...k) => {
  const e = document.createElement(t);
  for (const [p, v] of Object.entries(a)) (p in e || p.startsWith('on')) ? e[p] = v : e.setAttribute(p, v);
  for (const x of k) e.append(typeof x === 'string' ? document.createTextNode(x) : x);
  return e;
};
const clear = () => { const a = document.getElementById('app'); while (a.firstChild) a.removeChild(a.firstChild); return a; };
const signout = document.getElementById('btn-signout');
signout.onclick = async () => { await sb.auth.signOut(); signout.hidden = true; route(); };

const getSession = async () => (await sb.auth.getSession()).data.session;

const route = async () => {
  try {
    const s = await getSession();
    signout.hidden = !s;
    if (!s) return renderLogin();
    const h = location.hash;
    if (h.startsWith('#/s/')) return renderSession(...h.split('/').slice(2));
    if (h.startsWith('#/u/')) return renderUser(h.split('/')[2]);
    renderUsers();
  } catch (e) { clear().append(el('div', { className: 'error' }, e.message)); }
};
window.onhashchange = route; window.onload = route;

function renderLogin() {
  const a = clear(), form = el('div', { id: 'login' }),
    emailIn = el('input', { type: 'email', placeholder: 'Admin email', id: 'inp-email' }),
    passIn  = el('input', { type: 'password', placeholder: 'Password', id: 'inp-pass' }),
    btn = el('button', { id: 'btn-login' }, 'Sign in'),
    err = el('div', { className: 'error', id: 'login-err', style: 'display:none' });
  btn.onclick = async () => {
    err.style.display = 'none';
    const { error } = await sb.auth.signInWithPassword({ email: emailIn.value, password: passIn.value });
    error ? (err.textContent = error.message, err.style.display = '') : route();
  };
  form.append(emailIn, passIn, btn, err); a.append(form);
}

async function renderUsers() {
  const a = clear(), { data, error } = await sb.from('profiles').select('*').order('created_at');
  if (error) return a.append(el('div', { className: 'error' }, error.message));
  a.append(el('h2', {}, 'Users'));
  const tbl = el('table', {}), thead = el('tr', {}), tbody = el('tbody', {});
  for (const h of ['Name', 'ID', 'Joined', 'Status']) thead.append(el('th', {}, h));
  tbl.append(el('thead', {}, thead));
  for (const u of data || []) {
    const tr = el('tr', {}), link = el('a', { id: `user-${u.id}` }, u.name);
    link.onclick = () => { location.hash = `#/u/${u.id}`; };
    tr.append(el('td', {}, link), el('td', {}, u.id.slice(0, 8) + '…'),
      el('td', {}, new Date(u.created_at).toLocaleDateString()), el('td', {}, u.disabled ? 'Disabled' : 'Active'));
    tbody.append(tr);
  }
  tbl.append(tbody); a.append(tbl);
}

async function renderUser(uid) {
  const a = clear(), back = el('a', { className: 'back', id: 'back-users' }, '← All users');
  back.onclick = () => { location.hash = '#/'; };
  a.append(back);
  const { data, error } = await sb.from('evidence_sessions').select('*').eq('user_id', uid).order('started_at');
  if (error) return a.append(el('div', { className: 'error' }, error.message));
  a.append(el('h2', {}, 'Sessions'));
  const tbl = el('table', {}), thead = el('tr', {}), tbody = el('tbody', {});
  for (const h of ['Started', 'Segments', 'Size', 'Hashes OK', 'Status']) thead.append(el('th', {}, h));
  tbl.append(el('thead', {}, thead));
  for (const s of data || []) {
    const tr = el('tr', {}), link = el('a', { id: `sess-${s.session}` }, new Date(s.started_at).toLocaleString());
    link.onclick = () => { location.hash = `#/s/${uid}/${s.session}`; };
    const sz = s.bytes ? (s.bytes / 1024 / 1024).toFixed(2) + ' MB' : '–';
    tr.append(el('td', {}, link), el('td', {}, String(s.segments)), el('td', {}, sz),
      el('td', {}, el('span', { className: s.hashes_ok ? 'tag ok' : 'tag fail' }, s.hashes_ok ? '✓' : '✗')),
      el('td', {}, String(s.status)));
    tbody.append(tr);
  }
  tbl.append(tbody); a.append(tbl);
}

async function renderSession(uid, sid) {
  const a = clear(), back = el('a', { className: 'back', id: 'back-user' }, '← Sessions');
  back.onclick = () => { location.hash = `#/u/${uid}`; };
  a.append(back);
  const { data, error } = await sb.from('evidence').select('*').eq('user_id', uid).eq('session', sid).order('seq');
  if (error) return a.append(el('div', { className: 'error' }, error.message));
  if (!data?.length) return a.append(el('div', {}, 'Nothing here.'));
  a.append(el('h2', {}, `Session ${sid}`));
  const isVid = data[0].kind === 'video', urlCache = new Map();
  const getUrl = async (i) => {
    if (i < 0 || i >= data.length) return null;
    if (urlCache.has(i)) return urlCache.get(i);
    const { data: u } = await sb.storage.from('evidence').createSignedUrl(data[i].path, 300);
    if (u?.signedUrl) urlCache.set(i, u.signedUrl);
    return u?.signedUrl || null;
  };
  let cur = 0, deck = 0;
  const p0 = el(isVid ? 'video' : 'img', isVid ? { controls: '', playsinline: '', className: 'v-player' } : { id: 'photo' });
  const p1 = isVid ? el('video', { controls: '', playsinline: '', className: 'v-player', style: 'display:none' }) : null;
  const decks = [p0, p1];
  a.append(p0); if (p1) a.append(p1);
  const rowEls = [], fileCells = [];
  const playSeg = async (i) => {
    if (i < 0 || i >= data.length) return;
    cur = i; rowEls.forEach((r, idx) => r.classList.toggle('active', idx === cur));
    const url = await getUrl(i), curr = decks[deck];
    if (url && curr.src !== url) curr.src = url;
    if (isVid) curr.play().catch(() => {});
    if (isVid && i + 1 < data.length) {
      getUrl(i + 1).then(nxt => { if (nxt) { decks[1 - deck].src = nxt; decks[1 - deck].preload = 'auto'; } });
    }
  };
  if (isVid) p0.onended = p1.onended = () => {
    if (cur + 1 < data.length) {
      decks[deck].style.display = 'none'; deck = 1 - deck; decks[deck].style.display = 'block'; playSeg(cur + 1);
    }
  };
  const vBtn = el('button', { id: 'btn-verify' }, 'Verify hashes'), vSum = el('span', { id: 'verify-summary' });
  a.append(el('div', { className: 'vbar' }, vBtn, vSum));
  const tbl = el('table', {}), thead = el('tr', {}), tbody = el('tbody', {});
  for (const h of ['#', 'Captured', 'Location', 'Device', 'Claim', 'SHA-256', 'File']) thead.append(el('th', {}, h));
  tbl.append(el('thead', {}, thead));
  data.forEach((r, i) => {
    const tr = el('tr', { id: `row-${r.seq}`, style: 'cursor:pointer' });
    tr.onclick = () => {
      if (isVid && deck !== 0) { decks[1].style.display = 'none'; decks[0].style.display = 'block'; deck = 0; }
      playSeg(i);
    };
    const loc = (r.lat != null && r.lon != null) ? `${Number(r.lat).toFixed(4)},${Number(r.lon).toFixed(4)}` : '–';
    const tag = el('span', { className: r.hash_ok ? 'tag ok' : 'flagged' }, r.hash_ok ? 'ok' : 'FLAGGED');
    const fCell = el('td', {}, '–');
    fileCells.push(fCell);
    tr.append(el('td', {}, String(r.seq)), el('td', {}, new Date(r.captured_at).toLocaleTimeString()),
      el('td', {}, loc), el('td', {}, r.device), el('td', {}, tag),
      el('td', { title: r.sha256 }, r.sha256.slice(0, 10) + '…'), fCell);
    tbody.append(tr); rowEls.push(tr);
  });
  tbl.append(tbody); a.append(el('div', { className: 'wrap-tbl' }, tbl));
  vBtn.onclick = async () => {
    vBtn.disabled = true; vSum.textContent = 'Verifying…'; let okCount = 0;
    for (let i = 0; i < data.length; i++) {
      try {
        const u = await getUrl(i), res = await fetch(u);
        const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await res.arrayBuffer())))
          .map(b => b.toString(16).padStart(2, '0')).join('');
        const match = hash === data[i].sha256;
        if (match) okCount++;
        fileCells[i].textContent = '';
        fileCells[i].append(el('span', { className: match ? 'tag ok' : 'flagged' }, match ? 'match' : 'MISMATCH'));
      } catch { fileCells[i].textContent = 'error'; }
    }
    vSum.textContent = `${okCount} of ${data.length} match`; vBtn.disabled = false;
  };
  playSeg(0);
}
