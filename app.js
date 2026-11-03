/* ================================================================
   Student Management System — talks to ApiServer.java
   ================================================================ */
const API = '/api';
const state = { token: localStorage.getItem('sms_token'), user: null };
let currentView = 'dashboard';

const $  = s => document.querySelector(s);
const $$ = s => document.querySelectorAll(s);

function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, c =>
    ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

function toast(msg, type = 'info') {
  const box = $('#alert-box');
  if (!box) return;
  box.textContent = msg;
  box.className = 'alert ' + (type === 'error' ? 'alert-error' : 'alert-success');
  box.style.display = 'block';
  clearTimeout(box._t);
  box._t = setTimeout(() => { box.style.display = 'none'; }, 4000);
}

async function api(method, path, body) {
  const opts = { method, headers: {} };
  if (state.token) opts.headers['Authorization'] = 'Bearer ' + state.token;
  if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(API + path, opts);
  const ct  = res.headers.get('content-type') || '';
  const data = ct.includes('json') ? await res.json() : await res.text();
  if (!res.ok) {
    if (res.status === 401 && path !== '/login') logout();
    throw new Error((data && data.message) || 'Request failed');
  }
  return data;
}

function openModal(html, actions) {
  $('#modal-content').innerHTML = html + '<div class="modal-actions" id="modal-actions"></div>';
  const bar = $('#modal-actions');
  for (const a of actions) {
    const b = document.createElement('button');
    b.className = 'btn ' + (a.primary ? 'btn-primary' : a.danger ? 'btn-danger' : 'btn-secondary');
    b.textContent = a.label;
    b.onclick = a.onClick;
    bar.append(b);
  }
  const root = $('#modal-root');
  root.classList.add('show');
  root.onclick = e => { if (e.target === root) closeModal(); };
}
function closeModal() { $('#modal-root').classList.remove('show'); }

/* ---------------- login ---------------- */
$('#login-form').onsubmit = async e => {
  e.preventDefault();
  const btn = $('#login-btn');
  btn.disabled = true;
  btn.textContent = 'Signing in…';
  try {
    const r = await api('POST', '/login', {
      id: $('#login-id').value.trim(),
      password: $('#login-password').value,
    });
    console.log('[login] response:', r);
    if (!r || !r.token || !r.user) throw new Error('Malformed login response');
    state.token = r.token;
    state.user  = r.user;
    localStorage.setItem('sms_token', r.token);
    enterApp();
  } catch (err) {
    console.error('[login] failed:', err);
    toast(err.message || 'Login failed', 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = 'Sign in';
  }
};

function logout() {
  if (state.token) fetch(API + '/logout', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + state.token },
  }).catch(() => {});
  state.token = null;
  state.user  = null;
  localStorage.removeItem('sms_token');
  $('#view-dashboard').hidden = true;
  $('#view-login').hidden     = false;
  $('#login-form').reset();
  $('#content').innerHTML = '';
}
$('#logout-btn').onclick = logout;

/* ---------------- enter app ---------------- */
function enterApp() {
  $('#view-login').hidden     = true;
  $('#view-dashboard').hidden = false;
  const u = state.user || {};
  $('#nav-user-details').textContent = u.name || 'User';
  $('#user-role').textContent = u.role || '';
  $('#user-role').className = 'badge badge-' + (u.role || 'student').toLowerCase();
  $('#user-avatar').textContent = (u.name || '?').charAt(0).toUpperCase();
  renderNav();
  currentView = 'dashboard';
  render().catch(err => {
    console.error('[enterApp] render failed:', err);
    $('#content').innerHTML = '<div class="empty">⚠ ' + esc(err.message) + '</div>';
  });
}

/* ---------------- navigation ---------------- */
function navFor(role) {
  if (role === 'ADMIN') return [
    { id:'dashboard', icon:'◉', label:'Dashboard' },
    { group:'Users' },
    { id:'users', icon:'👥', label:'Users' },
    { id:'subjects', icon:'📚', label:'Subjects' },
    { group:'Grades' },
    { id:'students', icon:'🎒', label:'Students' },
    { id:'leaderboard', icon:'🏆', label:'Leaderboard' },
    { id:'honor-roll', icon:'⭐', label:'Honor Roll' },
    { id:'failing', icon:'⚠', label:'Failing' },
    { id:'stats', icon:'📊', label:'Class Stats' },
    { group:'Admin' },
    { id:'audit', icon:'📋', label:'Audit Log' },
    { id:'settings', icon:'⚙', label:'Settings' },
  ];
  if (role === 'TEACHER') return [
    { id:'dashboard', icon:'◉', label:'Dashboard' },
    { id:'students', icon:'🎒', label:'Students' },
    { id:'leaderboard', icon:'🏆', label:'Leaderboard' },
    { id:'stats', icon:'📊', label:'Class Stats' },
    { id:'settings', icon:'⚙', label:'Settings' },
  ];
  return [
    { id:'dashboard', icon:'◉', label:'Dashboard' },
    { id:'my-grades', icon:'📝', label:'My Grades' },
    { id:'top', icon:'🏆', label:'Top 5' },
    { id:'settings', icon:'⚙', label:'Settings' },
  ];
}

function renderNav() {
  const nav = $('#nav');
  nav.innerHTML = '';
  for (const item of navFor(state.user.role)) {
    if (item.group) {
      const t = document.createElement('div');
      t.className = 'nav-group-title';
      t.textContent = item.group;
      nav.append(t);
      continue;
    }
    const b = document.createElement('button');
    b.className = 'nav-item';
    b.dataset.view = item.id;
    b.innerHTML = `<span class="icon">${item.icon}</span><span>${item.label}</span>`;
    b.onclick = () => { currentView = item.id; render(); };
    nav.append(b);
  }
}

/* ---------------- render dispatcher ---------------- */
async function render() {
  const el = $('#content');
  el.innerHTML = '<div class="empty">Loading…</div>';
  const item = navFor(state.user.role).find(n => n.id === currentView) || { label: 'Dashboard' };
  $('#page-title').textContent = item.label;
  $('#brand-sub').textContent = item.label;
  $$('.nav-item[data-view]').forEach(n =>
    n.classList.toggle('active', n.dataset.view === currentView));
  try { await views[currentView](el); }
  catch (err) {
    console.error('[render] failed:', err);
    el.innerHTML = '<div class="empty">⚠ ' + esc(err.message) + '</div>';
  }
}

/* ---------------- shared helpers ---------------- */
const statCard = (label, value, hint) => `
  <div class="stat-card">
    <div class="label">${esc(label)}</div>
    <div class="value">${esc(String(value))}</div>
    ${hint ? `<div class="hint">${esc(hint)}</div>` : ''}
  </div>`;

function leaderTable(rows) {
  if (!rows || !rows.length) return '<div class="empty">No data.</div>';
  return `<table>
    <thead><tr><th>#</th><th>Student</th><th>Average</th><th>GPA</th><th>Subjects</th></tr></thead>
    <tbody>${rows.map(r => {
      const c = r.rank === 1 ? 'gold' : r.rank === 2 ? 'silver' : r.rank === 3 ? 'bronze' : '';
      return `<tr>
        <td><span class="rank-badge ${c}">${r.rank}</span></td>
        <td><strong>${esc(r.name)}</strong><br><span class="subtitle">${esc(r.studentId)}</span></td>
        <td>${r.average.toFixed(2)}</td>
        <td>${r.gpa.toFixed(2)}</td>
        <td>${r.subjectCount}</td>
      </tr>`;
    }).join('')}</tbody></table>`;
}

function subjectGradeTable(grades) {
  if (!grades || !grades.length) return '<div class="empty">No grades.</div>';
  return `<table>
    <thead><tr><th>Subject</th><th>Score</th><th>Letter</th><th>Credits</th><th>Result</th></tr></thead>
    <tbody>${grades.map(g => `
      <tr>
        <td><strong>${esc(g.subject)}</strong></td>
        <td>${g.score.toFixed(1)}</td>
        <td><span class="badge badge-${g.letter.toLowerCase()}">${g.letter}</span></td>
        <td>${g.credits}</td>
        <td><span class="badge badge-${g.result.toLowerCase()}">${g.result}</span></td>
      </tr>`).join('')}</tbody></table>`;
}

function componentTable(comps, interactive) {
  if (!comps || !comps.length) return '<div class="empty">No components.</div>';
  return `<table>
    <thead><tr><th>Subject</th><th>Component</th><th>Score</th><th>Weight</th>
      <th>By</th>${interactive ? '<th></th>' : ''}</tr></thead>
    <tbody>${comps.map(c => `
      <tr>
        <td>${esc(c.subject)}</td>
        <td>${esc(c.componentName)}</td>
        <td>${Number(c.score).toFixed(1)}</td>
        <td>${Number(c.weight).toFixed(1)}</td>
        <td>${esc(c.gradedBy || '—')}</td>
        ${interactive ? `<td><div class="row-actions">
          <button class="btn btn-secondary btn-sm" data-edit="${esc(c.id)}">Edit</button>
          <button class="btn btn-danger btn-sm" data-del="${esc(c.id)}">Del</button>
        </div></td>` : ''}
      </tr>`).join('')}</tbody></table>`;
}

function statsTable(stats) {
  if (!stats || !stats.length) return '<div class="empty">No data.</div>';
  return `<table>
    <thead><tr><th>Subject</th><th>N</th><th>Avg</th><th>Median</th>
      <th>Std</th><th>Min</th><th>Max</th><th>Pass%</th></tr></thead>
    <tbody>${stats.map(s => {
      const c = s.passRate >= 80 ? 'linear-gradient(90deg,#10b981,#34d399)'
              : s.passRate >= 50 ? 'linear-gradient(90deg,#f59e0b,#fbbf24)'
              : 'linear-gradient(90deg,#ef4444,#f87171)';
      return `<tr>
        <td><strong>${esc(s.subject)}</strong></td>
        <td>${s.count}</td>
        <td>${s.average.toFixed(2)}</td>
        <td>${s.median.toFixed(2)}</td>
        <td>${s.stdDev.toFixed(2)}</td>
        <td>${s.min.toFixed(1)}</td>
        <td>${s.max.toFixed(1)}</td>
        <td>
          <div style="display:flex;align-items:center;gap:8px">
            <span>${s.passRate.toFixed(1)}%</span>
            <div class="bar-wrap" style="flex:1;min-width:60px">
              <div class="bar" style="width:${s.passRate}%;background:${c}"></div>
            </div>
          </div>
        </td>
      </tr>`;
    }).join('')}</tbody></table>`;
}

/* ---------------- views ---------------- */
const views = {};

views.dashboard = async function(el) {
  const me = state.user;
  if (me.role === 'ADMIN') {
    const [users, subs, leader, stats] = await Promise.all([
      api('GET', '/users'),
      api('GET', '/subjects').catch(() => []),
      api('GET', '/top/5').catch(() => []),
      api('GET', '/stats').catch(() => []),
    ]);
    el.innerHTML = `
      <div class="grid grid-4">
        ${statCard('Students', users.filter(u => u.role === 'STUDENT').length, 'registered')}
        ${statCard('Teachers', users.filter(u => u.role === 'TEACHER').length, 'staff')}
        ${statCard('Subjects', subs.length, 'in catalog')}
        ${statCard('Grades', stats.reduce((a, s) => a + s.count, 0), 'entries')}
      </div>
      <div class="grid grid-2" style="margin-top:16px">
        <div class="card"><h3>🏆 Top 5 students</h3>
          <div style="margin-top:12px">${leaderTable(leader)}</div></div>
        <div class="card"><h3>📊 Class statistics</h3>
          <div style="margin-top:12px">${statsTable(stats)}</div></div>
      </div>`;
    return;
  }
  if (me.role === 'TEACHER') {
    const [users, stats] = await Promise.all([
      api('GET', '/users/search?students=true').catch(() => []),
      api('GET', '/stats').catch(() => []),
    ]);
    const mine = stats[0];
    el.innerHTML = `
      <div class="grid grid-4">
        ${statCard('My subject', me.subject || '—', 'assigned')}
        ${statCard('My students', users.length, 'in class')}
        ${statCard('Class avg', mine ? mine.average.toFixed(1) : '—', mine ? mine.count + ' grades' : '')}
        ${statCard('Pass rate', mine ? mine.passRate.toFixed(1) + '%' : '—', '')}
      </div>
      <div class="card" style="margin-top:16px">
        <h3>📊 ${esc(me.subject || '')} statistics</h3>
        <div style="margin-top:12px">${statsTable(stats)}</div>
      </div>`;
    return;
  }
  // student
  const [grades, top, rank] = await Promise.all([
    api('GET', '/grades/student/' + me.id).catch(() => []),
    api('GET', '/top/5').catch(() => []),
    api('GET', `/grades/student/${me.id}/rank`).catch(() => null),
  ]);
  const avg = grades.length ? grades.reduce((a, g) => a + g.score, 0) / grades.length : 0;
  const creditsSum = grades.reduce((a, g) => a + g.credits, 0);
  const gpa = creditsSum ? grades.reduce((a, g) => a + g.gpaPoints * g.credits, 0) / creditsSum : 0;
  el.innerHTML = `
    <div class="grid grid-4">
      ${statCard('My GPA', gpa.toFixed(2), 'credit-weighted')}
      ${statCard('Average', avg.toFixed(1), grades.length + ' subjects')}
      ${statCard('My rank', rank ? '#' + rank.rank : '—', rank ? 'of the class' : '')}
      ${statCard('Subjects', grades.length, '')}
    </div>
    <div class="grid grid-2" style="margin-top:16px">
      <div class="card"><h3>📝 My subjects</h3>
        <div style="margin-top:12px">${subjectGradeTable(grades)}</div></div>
      <div class="card"><h3>🏆 Class top 5</h3>
        <div style="margin-top:12px">${leaderTable(top)}</div></div>
    </div>`;
};

views.users = async function(el) {
  const users = await api('GET', '/users');
  el.innerHTML = `
    <div class="toolbar">
      <input class="input" id="user-search" placeholder="Search…" style="max-width:320px">
      <div class="spacer"></div>
      <button class="btn btn-primary" id="user-add">+ New user</button>
    </div>
    <div id="user-table"></div>`;
  const renderTable = list => {
    $('#user-table').innerHTML = list.length ? `
      <table>
        <thead><tr><th>ID</th><th>Name</th><th>Role</th><th>Subject</th><th></th></tr></thead>
        <tbody>${list.map(u => `
          <tr>
            <td><code>${esc(u.id)}</code></td>
            <td>${esc(u.name)}</td>
            <td><span class="badge badge-${u.role.toLowerCase()}">${u.role}</span></td>
            <td>${u.subject ? esc(u.subject) : '<span style="color:var(--muted)">—</span>'}</td>
            <td><div class="row-actions">
              <button class="btn btn-secondary btn-sm" data-edit="${esc(u.id)}">Edit</button>
              <button class="btn btn-secondary btn-sm" data-reset="${esc(u.id)}">Reset PW</button>
              <button class="btn btn-danger btn-sm" data-del="${esc(u.id)}">Delete</button>
            </div></td>
          </tr>`).join('')}</tbody>
      </table>` : '<div class="empty">No users.</div>';
    el.querySelectorAll('[data-edit]').forEach(b => b.onclick = () => editUser(b.dataset.edit));
    el.querySelectorAll('[data-del]').forEach(b => b.onclick = () => deleteUser(b.dataset.del));
    el.querySelectorAll('[data-reset]').forEach(b => b.onclick = () => resetPassword(b.dataset.reset));
  };
  renderTable(users);
  $('#user-search').oninput = async e => {
    const q = e.target.value.trim();
    if (!q) return renderTable(users);
    renderTable(await api('GET', '/users/search?q=' + encodeURIComponent(q)));
  };
  $('#user-add').onclick = addUser;
};

function addUser() {
  openModal(`
    <h2>New user</h2>
    <div class="field"><label>Role</label>
      <select class="input" id="f-role">
        <option value="STUDENT">Student</option>
        <option value="TEACHER">Teacher</option>
        <option value="ADMIN">Admin</option>
      </select></div>
    <div class="field"><label>Name</label><input class="input" id="f-name"></div>
    <div class="field" id="f-subject-wrap" hidden><label>Subject</label><input class="input" id="f-subject"></div>
    <div class="field"><label>Password</label><input class="input" type="password" id="f-password"></div>
  `, [
    { label: 'Cancel', onClick: closeModal },
    { label: 'Create', primary: true, onClick: async () => {
        try {
          const r = await api('POST', '/users', {
            role: $('#f-role').value,
            name: $('#f-name').value.trim(),
            subject: $('#f-subject').value.trim(),
            password: $('#f-password').value,
          });
          closeModal(); toast('User ' + r.id + ' created'); render();
        } catch (e) { toast(e.message, 'error'); }
      }},
  ]);
  const roleSel = $('#f-role'), wrap = $('#f-subject-wrap');
  roleSel.onchange = () => { wrap.hidden = roleSel.value !== 'TEACHER'; };
}

async function editUser(id) {
  const u = await api('GET', '/users/' + id);
  openModal(`
    <h2>Edit user</h2>
    <p class="subtitle">${esc(u.id)} · ${u.role}</p>
    <div class="field"><label>Name</label><input class="input" id="e-name" value="${esc(u.name)}"></div>
    ${u.role === 'TEACHER' ? `
      <div class="field"><label>Subject</label>
        <input class="input" id="e-subject" value="${esc(u.subject || '')}"></div>` : ''}
  `, [
    { label: 'Cancel', onClick: closeModal },
    { label: 'Save', primary: true, onClick: async () => {
        try {
          await api('POST', '/users/update', {
            id: u.id,
            name: $('#e-name').value.trim(),
            subject: u.role === 'TEACHER' ? $('#e-subject').value.trim() : '',
          });
          closeModal(); toast('Updated'); render();
        } catch (e) { toast(e.message, 'error'); }
      }},
  ]);
}

async function deleteUser(id) {
  if (!confirm('Delete ' + id + '? This cascades to their grades.')) return;
  try { await api('POST', '/users/delete', { id }); toast('Deleted'); render(); }
  catch (e) { toast(e.message, 'error'); }
}

function resetPassword(id) {
  openModal(`
    <h2>Reset password</h2>
    <p class="subtitle">${esc(id)}</p>
    <div class="field"><label>New password</label>
      <input class="input" type="password" id="r-pw"></div>
  `, [
    { label: 'Cancel', onClick: closeModal },
    { label: 'Reset', primary: true, onClick: async () => {
        try {
          await api('POST', '/users/reset-password', { id, password: $('#r-pw').value });
          closeModal(); toast('Password reset');
        } catch (e) { toast(e.message, 'error'); }
      }},
  ]);
}

views.subjects = async function(el) {
  const subs = await api('GET', '/subjects').catch(() => []);
  el.innerHTML = `
    <div class="toolbar"><div class="spacer"></div>
      <button class="btn btn-primary" id="subj-add">+ Add subject</button></div>
    <div id="subj-table"></div>`;
  const renderTable = list => {
    $('#subj-table').innerHTML = list.length ? `
      <table>
        <thead><tr><th>Subject</th><th>Credits</th><th></th></tr></thead>
        <tbody>${list.map(s => `
          <tr>
            <td><strong>${esc(s.name)}</strong></td>
            <td>${s.credits}</td>
            <td><div class="row-actions">
              <button class="btn btn-secondary btn-sm" data-edit="${esc(s.name)}">Edit</button>
              <button class="btn btn-danger btn-sm" data-del="${esc(s.name)}">Delete</button>
            </div></td>
          </tr>`).join('')}</tbody>
      </table>` : '<div class="empty">No subjects yet.</div>';
    el.querySelectorAll('[data-edit]').forEach(b => b.onclick = () => editSubject(b.dataset.edit, list));
    el.querySelectorAll('[data-del]').forEach(b => b.onclick = async () => {
      if (!confirm('Delete subject ' + b.dataset.del + '?')) return;
      try { await api('POST', '/subjects/delete', { name: b.dataset.del }); toast('Deleted'); render(); }
      catch (e) { toast(e.message, 'error'); }
    });
  };
  renderTable(subs);
  $('#subj-add').onclick = () => editSubject('', subs);

  function editSubject(name, list) {
    const cur = list.find(s => s.name === name);
    openModal(`
      <h2>${cur ? 'Edit' : 'New'} subject</h2>
      <div class="field"><label>Name</label>
        <input class="input" id="s-name" value="${esc(cur ? cur.name : '')}" ${cur ? 'readonly' : ''}></div>
      <div class="field"><label>Credits</label>
        <input class="input" type="number" step="0.5" min="0.5" max="20"
               id="s-credits" value="${cur ? cur.credits : 1}"></div>
    `, [
      { label: 'Cancel', onClick: closeModal },
      { label: 'Save', primary: true, onClick: async () => {
          try {
            await api('POST', '/subjects', {
              name: $('#s-name').value.trim(),
              credits: parseFloat($('#s-credits').value),
            });
            closeModal(); toast('Saved'); render();
          } catch (e) { toast(e.message, 'error'); }
        }},
    ]);
  }
};

views.students = async function(el) {
  const students = await api('GET', '/users/search?students=true');
  el.innerHTML = `
    <div class="toolbar">
      <input class="input" id="stud-search" placeholder="Search…" style="max-width:320px">
    </div>
    <div id="stud-grid" class="grid grid-3"></div>`;
  const renderGrid = list => {
    $('#stud-grid').innerHTML = list.length ? list.map(s => `
      <div class="card" data-card="${esc(s.id)}">
        <div style="display:flex;align-items:center;gap:12px">
          <div class="avatar" style="width:40px;height:40px;font-size:15px">
            ${esc(s.name.charAt(0).toUpperCase())}
          </div>
          <div><h3>${esc(s.name)}</h3><p class="subtitle">${esc(s.id)}</p></div>
        </div>
      </div>`).join('') : '<div class="empty">No students.</div>';
    el.querySelectorAll('[data-card]').forEach(c => c.onclick = () => openStudent(c.dataset.card));
  };
  renderGrid(students);
  $('#stud-search').oninput = async e => {
    const q = e.target.value.trim();
    if (!q) return renderGrid(students);
    renderGrid(await api('GET', '/users/search?students=true&q=' + encodeURIComponent(q)));
  };
};

async function openStudent(id) {
  const [grades, comps, rank, transcript] = await Promise.all([
    api('GET', '/grades/student/' + id).catch(() => []),
    api('GET', `/grades/student/${id}/components`).catch(() => []),
    api('GET', `/grades/student/${id}/rank`).catch(() => null),
    api('GET', '/transcript/' + id).catch(() => ''),
  ]);
  openModal(`
    <h2>${esc(id)}</h2>
    <p class="subtitle">${rank
      ? `Rank #${rank.rank} · GPA ${rank.gpa.toFixed(2)} · avg ${rank.average.toFixed(1)}`
      : 'No grades yet'}</p>
    <h3 style="font-size:14px;margin:16px 0 8px">Subject grades</h3>
    ${subjectGradeTable(grades)}
    <h3 style="font-size:14px;margin:20px 0 8px">Components</h3>
    <div id="modal-comps">${componentTable(comps, true)}</div>
    <h3 style="font-size:14px;margin:20px 0 8px">Transcript</h3>
    <pre class="transcript">${esc(transcript)}</pre>
  `, [
    { label: 'Close', onClick: closeModal },
    { label: '+ Add component', primary: true, onClick: () => addComponent(id) },
  ]);
  const mc = $('#modal-content');
  mc.querySelectorAll('[data-edit]').forEach(b => b.onclick = () => {
    const c = comps.find(x => String(x.id) === b.dataset.edit);
    if (c) editComponent(id, c);
  });
  mc.querySelectorAll('[data-del]').forEach(b => b.onclick = async () => {
    const c = comps.find(x => String(x.id) === b.dataset.del);
    if (!c || !confirm(`Delete ${c.subject}/${c.componentName}?`)) return;
    try {
      await api('POST', '/grades/component/delete', {
        studentId: id, subject: c.subject, componentName: c.componentName,
      });
      toast('Deleted'); openStudent(id);
    } catch (e) { toast(e.message, 'error'); }
  });
}

function addComponent(studentId) {
  const locked = state.user.role === 'TEACHER' ? state.user.subject : null;
  openModal(`
    <h2>Add component</h2>
    <p class="subtitle">Student ${esc(studentId)}</p>
    <div class="field"><label>Subject</label>
      ${locked
        ? `<input class="input" value="${esc(locked)}" readonly>`
        : `<input class="input" id="c-subject" placeholder="Math">`}</div>
    <div class="field"><label>Name</label>
      <input class="input" id="c-name" placeholder="Quiz 1 / Midterm / Final"></div>
    <div class="grid grid-2" style="gap:12px">
      <div class="field"><label>Score</label>
        <input class="input" type="number" min="0" max="100" step="0.1" id="c-score"></div>
      <div class="field"><label>Weight</label>
        <input class="input" type="number" min="0.1" step="0.1" id="c-weight" value="1"></div>
    </div>
    <div class="field"><label>Comment</label><input class="input" id="c-comment"></div>
  `, [
    { label: 'Cancel', onClick: closeModal },
    { label: 'Add', primary: true, onClick: async () => {
        try {
          await api('POST', '/grades/component/add', {
            studentId,
            subject: locked || $('#c-subject').value.trim(),
            componentName: $('#c-name').value.trim(),
            score: parseFloat($('#c-score').value),
            weight: parseFloat($('#c-weight').value),
            comment: $('#c-comment').value.trim(),
          });
          closeModal(); toast('Added'); openStudent(studentId);
        } catch (e) { toast(e.message, 'error'); }
      }},
  ]);
}

function editComponent(studentId, c) {
  openModal(`
    <h2>Edit component</h2>
    <p class="subtitle">${esc(c.subject)} / ${esc(c.componentName)}</p>
    <div class="grid grid-2" style="gap:12px">
      <div class="field"><label>Score</label>
        <input class="input" type="number" step="0.1" id="e-score" value="${c.score}"></div>
      <div class="field"><label>Weight</label>
        <input class="input" type="number" step="0.1" id="e-weight" value="${c.weight}"></div>
    </div>
    <div class="field"><label>Comment</label>
      <input class="input" id="e-comment" value="${esc(c.comment || '')}"></div>
  `, [
    { label: 'Cancel', onClick: closeModal },
    { label: 'Save', primary: true, onClick: async () => {
        try {
          await api('POST', '/grades/component/update', {
            studentId, subject: c.subject, componentName: c.componentName,
            score: parseFloat($('#e-score').value),
            weight: parseFloat($('#e-weight').value),
            comment: $('#e-comment').value.trim(),
          });
          closeModal(); toast('Saved'); openStudent(studentId);
        } catch (e) { toast(e.message, 'error'); }
      }},
  ]);
}

views.leaderboard = async function(el) {
  const rows = await api('GET', '/leaderboard');
  el.innerHTML = `<div class="card">
    <h3>🏆 Leaderboard</h3>
    <div style="margin-top:12px">${leaderTable(rows)}</div></div>`;
};
views['honor-roll'] = async function(el) {
  const rows = await api('GET', '/honor-roll');
  el.innerHTML = `<div class="card">
    <h3>⭐ Honor roll</h3>
    <div style="margin-top:12px">${leaderTable(rows)}</div></div>`;
};
views.failing = async function(el) {
  const rows = await api('GET', '/failing');
  el.innerHTML = `<div class="card">
    <h3>⚠ Failing students</h3>
    <div style="margin-top:12px">${leaderTable(rows)}</div></div>`;
};
views.stats = async function(el) {
  const stats = await api('GET', '/stats');
  el.innerHTML = `<div class="card">
    <h3>📊 Class statistics</h3>
    <div style="margin-top:12px">${statsTable(stats)}</div></div>`;
};
views.audit = async function(el) {
  const logs = await api('GET', '/audit?limit=100');
  el.innerHTML = `
    <div class="toolbar">
      <button class="btn btn-secondary" id="audit-refresh">↻ Refresh</button>
      <div class="spacer"></div>
      <button class="btn btn-secondary" id="btn-export">⬇ CSV report</button>
      <button class="btn btn-secondary" id="btn-backup">⬇ SQL backup</button>
      <button class="btn btn-secondary" id="btn-sync">↻ Sync CSV</button>
      <button class="btn btn-secondary" id="btn-import">⬆ Import CSV</button>
    </div>
    <div class="card">
      <h3>📋 Audit log</h3>
      <div style="margin-top:12px">${
        logs.length ? `<table>
          <thead><tr><th>Entry</th></tr></thead>
          <tbody>${logs.map(r => `<tr><td>${esc(r)}</td></tr>`).join('')}</tbody>
        </table>` : '<div class="empty">No entries.</div>'
      }</div></div>`;
  $('#audit-refresh').onclick = () => render();
  $('#btn-export').onclick = () => downloadFile('/export/csv');
  $('#btn-backup').onclick = () => downloadFile('/export/backup');
  $('#btn-sync').onclick = async () => {
    try { const r = await api('POST', '/sync/csv'); toast('Synced to ' + r.folder); }
    catch (e) { toast(e.message, 'error'); }
  };
  $('#btn-import').onclick = async () => {
    if (!confirm('Import CSVs? Matching rows will be overwritten.')) return;
    try { await api('POST', '/sync/import'); toast('Import OK'); render(); }
    catch (e) { toast(e.message, 'error'); }
  };
};
async function downloadFile(path) {
  const res = await fetch(API + path, { headers: { Authorization: 'Bearer ' + state.token } });
  if (!res.ok) return toast('Download failed', 'error');
  const blob = await res.blob();
  const cd = res.headers.get('Content-Disposition') || '';
  const m = cd.match(/filename="([^"]+)"/);
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = m ? m[1] : 'download';
  a.click();
}

views['my-grades'] = async function(el) {
  const me = state.user;
  const [grades, comps, transcript] = await Promise.all([
    api('GET', '/grades/student/' + me.id).catch(() => []),
    api('GET', `/grades/student/${me.id}/components`).catch(() => []),
    api('GET', '/transcript/' + me.id).catch(() => ''),
  ]);
  el.innerHTML = `
    <div class="grid grid-2">
      <div class="card"><h3>📝 Subject grades</h3>
        <div style="margin-top:12px">${subjectGradeTable(grades)}</div></div>
      <div class="card"><h3>📄 Transcript</h3>
        <pre class="transcript" style="margin-top:12px">${esc(transcript)}</pre></div>
    </div>
    <div class="card" style="margin-top:16px">
      <h3>🔍 Components</h3>
      <div style="margin-top:12px">${componentTable(comps, false)}</div></div>`;
};
views.top = async function(el) {
  const rows = await api('GET', '/top/5');
  el.innerHTML = `<div class="card">
    <h3>🏆 Class top 5</h3>
    <div style="margin-top:12px">${leaderTable(rows)}</div></div>`;
};
views.settings = async function(el) {
  el.innerHTML = `
    <div class="card" style="max-width:520px">
      <h3>⚙ Account</h3>
      <div style="margin-top:16px">
        <div class="field"><label>Current</label>
          <input class="input" type="password" id="pw-old"></div>
        <div class="field"><label>New</label>
          <input class="input" type="password" id="pw-new"></div>
        <div class="field"><label>Confirm</label>
          <input class="input" type="password" id="pw-confirm"></div>
        <button class="btn btn-primary btn-block" id="pw-save">Update</button>
      </div></div>`;
  $('#pw-save').onclick = async () => {
    if ($('#pw-new').value !== $('#pw-confirm').value)
      return toast('Passwords do not match', 'error');
    try {
      await api('POST', '/users/change-password', {
        oldPassword: $('#pw-old').value,
        newPassword: $('#pw-new').value,
      });
      toast('Password updated');
      ['#pw-old', '#pw-new', '#pw-confirm'].forEach(s => $(s).value = '');
    } catch (e) { toast(e.message, 'error'); }
  };
};

/* ---------------- boot ---------------- */
(async function boot() {
  if (state.token) {
    try {
      state.user = await api('GET', '/me');
      enterApp();
      return;
    } catch (err) {
      console.warn('[boot] token invalid, please log in again:', err);
      localStorage.removeItem('sms_token');
      state.token = null;
    }
  }
  $('#view-login').hidden = false;
})();
