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
  // Sort by seq so order is always correct regardless of upload order
  data.sort((a, b) => a.seq - b.seq);
  a.append(el('h2', {}, `Session ${sid}`));
  // Show a warning if any seq values are missing (gaps between 0 and max)
  if (data.length > 0) {
    const maxSeq = data[data.length - 1].seq;
    const missing = maxSeq + 1 - data.length;
    if (missing > 0) {
      const seqSet = new Set(data.map(r => r.seq));
      const gaps = [];
      for (let i = 0; i <= maxSeq; i++) { if (!seqSet.has(i)) gaps.push(i); }
      a.append(el('div', { className: 'error', id: 'gap-warning' },
        `${missing} segment${missing > 1 ? 's' : ''} missing (seq ${gaps.join(', ')})`));
    }
  }
  const isVid = data[0].kind === 'video', urlCache = new Map();
  const getUrl = async (i) => {
    if (i < 0 || i >= data.length) return null;
    if (urlCache.has(i)) return urlCache.get(i);
    const { data: u } = await sb.storage.from('evidence').createSignedUrl(data[i].path, 600);
    if (u?.signedUrl) urlCache.set(i, u.signedUrl);
    return u?.signedUrl || null;
  };

  // Pre-fetch all signed URLs immediately so the buffer is ready before playback starts
  const prefetchAll = () => { for (let i = 0; i < data.length; i++) getUrl(i); };
  prefetchAll();

  let cur = 0;
  const rowEls = [], fileCells = [];

  // --- Double-buffer player ---
  // Two hidden <video> elements. While A plays, B pre-loads the next segment.
  // On 'ended', swap: make B visible and start playing, reload A with the one after.
  // Result: zero-gap, visually seamless segment transitions.
  const mkVid = () => {
    const v = el('video', { playsinline: '', preload: 'auto', controls: '', className: 'v-player' });
    v.style.display = 'none';
    return v;
  };
  const vA = mkVid(), vB = mkVid();
  // Only the active element shows controls; we overlay a single controls bar below
  const wrap = el('div', { className: 'v-wrap' });
  wrap.append(vA, vB);
  a.append(wrap);

  let active = vA, standby = vB;
  let activeIdx = 0;

  const updateHighlight = (i) => {
    cur = i;
    rowEls.forEach((r, idx) => r.classList.toggle('active', idx === cur));
  };

  // Load a URL into an element and return a Promise that resolves when enough is buffered
  const loadInto = (vid, url) => {
    if (!url) return Promise.resolve();
    vid.src = url;
    vid.load();
    return new Promise(res => {
      const done = () => { vid.removeEventListener('canplay', done); res(); };
      vid.addEventListener('canplay', done);
      // Resolve anyway after 3 s so a slow network doesn't block the swap
      setTimeout(res, 3000);
    });
  };

  const playSeg = async (i) => {
    if (i < 0 || i >= data.length) return;
    updateHighlight(i);
    activeIdx = i;
    const url = await getUrl(i);
    if (!url) return;
    if (isVid) {
      await loadInto(active, url);
      active.style.display = 'block';
      standby.style.display = 'none';
      active.play().catch(() => {});
      // Pre-load next segment into standby immediately
      if (i + 1 < data.length) {
        const nextUrl = await getUrl(i + 1);
        if (nextUrl) loadInto(standby, nextUrl);
      }
    } else {
      // Photo: just show in active slot
      active.src = url;
      active.style.display = 'block';
      standby.style.display = 'none';
    }
  };

  if (isVid) {
    const onSegEnd = async () => {
      const next = activeIdx + 1;
      if (next >= data.length) return;
      updateHighlight(next);
      activeIdx = next;
      // Swap buffers: standby is already loaded → show it and play instantly
      [active, standby] = [standby, active];
      standby.style.display = 'none';
      active.style.display = 'block';
      active.play().catch(() => {});
      // Pre-load next-next into the now-idle standby
      if (next + 1 < data.length) {
        const url = await getUrl(next + 1);
        if (url) loadInto(standby, url);
      }
    };
    vA.addEventListener('ended', onSegEnd);
    vB.addEventListener('ended', onSegEnd);
  }
  const vBtn = el('button', { id: 'btn-verify' }, 'Verify hashes'), vSum = el('span', { id: 'verify-summary' });
  a.append(el('div', { className: 'vbar' }, vBtn, vSum));
  const tbl = el('table', {}), thead = el('tr', {}), tbody = el('tbody', {});
  for (const h of ['#', 'Captured', 'Location', 'Device', 'Claim', 'SHA-256', 'File']) thead.append(el('th', {}, h));
  tbl.append(el('thead', {}, thead));
  data.forEach((r, i) => {
    const tr = el('tr', { id: `row-${r.seq}`, style: 'cursor:pointer' });
    tr.onclick = () => { playSeg(i); };
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
