/* ================================================================
   Student Management System — client-side logic
   Talks directly to Supabase (users, subjects, grade_components,
   audit_log). Renders everything into #content.
   Works with the fixed index.html (ids: login-view, app-view, ...).
   ================================================================ */

const SUPABASE_URL = 'https://frotwvjkuriejaluvjvp.supabase.co';
const SUPABASE_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImZyb3R3dmprdXJpZWphbHV2anZwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTM2MzI3NjgsImV4cCI6MjEwOTIwODc2OH0.VBADQtaxalwttX3i6noXxh9l0_v1g2yvYZ_pEp_T208';
const supabaseClient = supabase.createClient(SUPABASE_URL, SUPABASE_KEY);

/* ---------------- state ---------------- */
let currentUser = null;
let currentView = 'dashboard';
const cache = { users: null, subjects: null, components: null };

const PASS_MARK = 50;
const HONOR_ROLL_MIN = 85;

/* ---------------- helpers ---------------- */
const $ = s => document.querySelector(s);
const $$ = s => document.querySelectorAll(s);

function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, c =>
    ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));
}

function toast(msg, type = 'info') {
  const el = document.createElement('div');
  el.className = 'toast ' + type;
  el.textContent = msg;
  $('#toast-root').append(el);
  setTimeout(() => {
    el.style.transition = 'opacity .3s,transform .3s';
    el.style.opacity = '0';
    el.style.transform = 'translateX(20px)';
    setTimeout(() => el.remove(), 300);
  }, 3200);
}
function showAlert(message, isError = false) { toast(message, isError ? 'error' : 'success'); }

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

function invalidate() { cache.users = cache.subjects = cache.components = null; }

/* ---------------- grade rules ---------------- */
function calculateGradeLetter(score) {
  if (score >= 90) return 'A';
  if (score >= 80) return 'B';
  if (score >= 70) return 'C';
  if (score >= 60) return 'D';
  if (score >= PASS_MARK) return 'E';
  return 'F';
}
function isPass(score) { return score >= PASS_MARK; }
function gpaPoints(letter) {
  return ({ A:4, B:3, C:2, D:1, E:0.5, F:0 })[letter] || 0;
}
function weightedAvg(components) {
  let sum = 0, w = 0;
  for (const c of components) {
    sum += Number(c.score) * Number(c.weight || 1);
    w   += Number(c.weight || 1);
  }
  return w === 0 ? 0 : sum / w;
}
function creditGpa(grades) {
  let c = 0, p = 0;
  for (const g of grades) { c += Number(g.credits); p += g.gpaPoints * Number(g.credits); }
  return c === 0 ? 0 : p / c;
}

/* ---------------- data loaders (with cache) ---------------- */
async function loadUsers() {
  if (cache.users) return cache.users;
  const { data, error } = await supabaseClient.from('users').select('*').order('id');
  if (error) throw error;
  return (cache.users = data);
}
async function loadSubjects() {
  if (cache.subjects) return cache.subjects;
  const { data, error } = await supabaseClient.from('subjects').select('*').order('name');
  if (error) throw error;
  return (cache.subjects = data);
}
async function loadAllComponents() {
  if (cache.components) return cache.components;
  const { data, error } = await supabaseClient.from('grade_components')
    .select('*')
    .order('student_id')
    .order('subject')
    .order('component_name');
  if (error) throw error;
  return (cache.components = data);
}
async function loadComponentsFor(studentId) {
  const { data, error } = await supabaseClient.from('grade_components')
    .select('*')
    .eq('student_id', studentId)
    .order('subject')
    .order('component_name');
  if (error) throw error;
  return data;
}

/* ---------------- aggregations ---------------- */
async function computeSubjectGrades(studentId) {
  const [comps, subjects] = await Promise.all([loadComponentsFor(studentId), loadSubjects()]);
  const bySubject = {};
  for (const c of comps) (bySubject[c.subject] ||= []).push(c);

  const creditsOf = name => {
    const s = subjects.find(x => x.name === name);
    return s ? Number(s.credits) : 1;
  };

  return Object.entries(bySubject).map(([subject, list]) => {
    const score = weightedAvg(list);
    const letter = calculateGradeLetter(score);
    return {
      subject, score, letter,
      result: isPass(score) ? 'Pass' : 'Fail',
      credits: creditsOf(subject),
      gpaPoints: gpaPoints(letter),
      componentCount: list.length,
      components: list,
    };
  }).sort((a, b) => a.subject.localeCompare(b.subject));
}

async function computeLeaderboard(viewer) {
  const users = await loadUsers();
  const students = users.filter(u => u.role === 'STUDENT');
  const rows = [];
  for (const s of students) {
    const grades = await computeSubjectGrades(s.id);
    const visible = viewer && viewer.role === 'TEACHER'
      ? grades.filter(g => g.subject.toLowerCase() === (viewer.subject || '').toLowerCase())
      : grades;
    if (visible.length === 0) continue;
    const average = visible.reduce((a, g) => a + g.score, 0) / visible.length;
    const gpa = creditGpa(visible);
    rows.push({
      studentId: s.id, name: s.name,
      average, gpa, subjectCount: visible.length,
    });
  }
  rows.sort((a, b) => b.average - a.average || a.name.localeCompare(b.name));
  let rank = 0, prevAvg = NaN;
  return rows.map((r, i) => {
    if (i === 0 || r.average !== prevAvg) rank = i + 1;
    prevAvg = r.average;
    return { rank, ...r };
  });
}

async function computeStats(viewer) {
  const users = await loadUsers();
  const students = users.filter(u => u.role === 'STUDENT');
  const bySubject = {};
  for (const s of students) {
    const grades = await computeSubjectGrades(s.id);
    for (const g of grades) {
      if (viewer && viewer.role === 'TEACHER'
          && g.subject.toLowerCase() !== (viewer.subject || '').toLowerCase()) continue;
      (bySubject[g.subject] ||= []).push(g.score);
    }
  }
  const out = [];
  for (const [subject, scores] of Object.entries(bySubject)) {
    scores.sort((a, b) => a - b);
    const n = scores.length;
    const average = scores.reduce((a, b) => a + b, 0) / n;
    const median = n % 2 === 0
      ? (scores[n / 2 - 1] + scores[n / 2]) / 2
      : scores[Math.floor(n / 2)];
    const variance = scores.reduce((a, s) => a + (s - average) ** 2, 0) / n;
    const stdDev = Math.sqrt(variance);
    const min = scores[0], max = scores[n - 1];
    const passed = scores.filter(isPass).length;
    const passRate = (passed / n) * 100;
    out.push({ subject, count: n, average, median, stdDev, min, max, passed, passRate });
  }
  out.sort((a, b) => a.subject.localeCompare(b.subject));
  return out;
}

/* ---------------- audit logging ---------------- */
async function logAction(action, details) {
  try {
    await supabaseClient.from('audit_log').insert([{
      actor: currentUser ? currentUser.id : '-',
      action,
      details,
    }]);
  } catch (e) {
    console.warn('Audit log failed:', e);
  }
}

/* ---------------- login / logout ---------------- */
$('#login-form').onsubmit = async e => {
  e.preventDefault();
  const btn = $('#login-btn');
  btn.disabled = true;
  btn.textContent = 'Signing in…';
  try {
    const id = $('#login-id').value.trim().toUpperCase();
    const password = $('#login-password').value;
    const { data, error } = await supabaseClient.from('users').select('*')
      .eq('id', id).eq('password', password).maybeSingle();
    if (error) { console.error(error); throw new Error(error.message || 'Login failed'); }
    if (!data) throw new Error('Invalid ID or password');
    currentUser = data;
    initDashboard();
  } catch (err) {
    toast(err.message || 'Login failed', 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = 'Sign in';
  }
};

function logout() {
  currentUser = null;
  invalidate();
  currentView = 'dashboard';
  $('#app-view').hidden = true;
  $('#login-view').hidden = false;
  $('#login-form').reset();
  $('#content').innerHTML = '';
}
$('#logout-btn').onclick = logout;

/* ---------------- dashboard init ---------------- */
function initDashboard() {
  $('#login-view').hidden = true;
  $('#app-view').hidden = false;
  $('#user-name').textContent = currentUser.name;
  $('#user-role').textContent = currentUser.role;
  $('#user-role').className = 'badge badge-' + currentUser.role.toLowerCase();
  $('#user-avatar').textContent = (currentUser.name || '?').charAt(0).toUpperCase();
  currentView = 'dashboard';
  renderNav();
  render();
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
  for (const item of navFor(currentUser.role)) {
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
    b.innerHTML = `<span style="font-size:16px;width:18px;text-align:center">${item.icon}</span><span>${item.label}</span>`;
    b.onclick = () => { currentView = item.id; render(); };
    nav.append(b);
  }
}

/* ---------------- render dispatcher ---------------- */
async function render() {
  const el = $('#content');
  el.innerHTML = '<div class="empty">Loading…</div>';
  const item = navFor(currentUser.role).find(n => n.id === currentView) || { label:'Dashboard' };
  $('#page-title').textContent = item.label;
  $('#brand-sub').textContent = item.label;
  $$('.nav-item[data-view]').forEach(n =>
    n.classList.toggle('active', n.dataset.view === currentView));
  try {
    if (views[currentView]) await views[currentView](el);
    else el.innerHTML = '<div class="empty">Unknown view.</div>';
  } catch (err) {
    console.error(err);
    el.innerHTML = `<div class="empty">⚠ ${esc(err.message)}</div>`;
  }
}

/* ---------------- shared table builders ---------------- */
function leaderTable(rows) {
  if (!rows.length) return '<div class="empty">No data.</div>';
  return `<table>
    <thead><tr><th>#</th><th>Student</th><th>Average</th><th>GPA</th><th>Subjects</th></tr></thead>
    <tbody>${rows.map(r => {
      const c = r.rank === 1 ? 'gold' : r.rank === 2 ? 'silver' : r.rank === 3 ? 'bronze' : '';
      return `<tr>
        <td><span class="rank-badge ${c}">${r.rank}</span></td>
        <td><strong>${esc(r.name)}</strong><br>
            <span class="subtitle">${esc(r.studentId)}</span></td>
        <td>${r.average.toFixed(2)}</td>
        <td>${r.gpa.toFixed(2)}</td>
        <td>${r.subjectCount}</td>
      </tr>`;
    }).join('')}</tbody>
  </table>`;
}

function subjectGradeTable(grades) {
  if (!grades.length) return '<div class="empty">No grades.</div>';
  return `<table>
    <thead><tr><th>Subject</th><th>Score</th><th>Letter</th><th>Credits</th><th>Result</th></tr></thead>
    <tbody>${grades.map(g => `
      <tr>
        <td><strong>${esc(g.subject)}</strong></td>
        <td>${g.score.toFixed(1)}</td>
        <td><span class="badge badge-${g.letter.toLowerCase()}">${g.letter}</span></td>
        <td>${g.credits}</td>
        <td><span class="badge badge-${g.result.toLowerCase()}">${g.result}</span></td>
      </tr>`).join('')}</tbody>
  </table>`;
}

function componentTable(comps, interactive) {
  if (!comps.length) return '<div class="empty">No components.</div>';
  return `<table>
    <thead><tr><th>Subject</th><th>Component</th><th>Score</th><th>Weight</th>
      <th>By</th>${interactive ? '<th></th>' : ''}</tr></thead>
    <tbody>${comps.map(c => `
      <tr>
        <td>${esc(c.subject)}</td>
        <td>${esc(c.component_name)}</td>
        <td>${Number(c.score).toFixed(1)}</td>
        <td>${Number(c.weight).toFixed(1)}</td>
        <td>${esc(c.graded_by || '—')}</td>
        ${interactive ? `<td><div class="row-actions">
          <button class="btn btn-secondary btn-sm" data-edit="${esc(c.id)}">Edit</button>
          <button class="btn btn-danger btn-sm" data-del="${esc(c.id)}">Del</button>
        </div></td>` : ''}
      </tr>`).join('')}</tbody>
  </table>`;
}

function statCard(label, value, hint) {
  return `<div class="stat-card">
    <div class="label">${esc(label)}</div>
    <div class="value">${esc(String(value))}</div>
    ${hint ? `<div class="hint">${esc(hint)}</div>` : ''}
  </div>`;
}

function statsTable(stats) {
  if (!stats.length) return '<div class="empty">No data.</div>';
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
    }).join('')}</tbody>
  </table>`;
}

/* ---------------- views ---------------- */
const views = {};

/* ---- Dashboard ---- */
views.dashboard = async function(el) {
  const me = currentUser;

  if (me.role === 'ADMIN') {
    const [users, subjects, leader] = await Promise.all([
      loadUsers(), loadSubjects(), computeLeaderboard(),
    ]);
    const comps = await loadAllComponents();
    const stats = await computeStats(null);
    el.innerHTML = `
      <div class="grid grid-4">
        ${statCard('Students', users.filter(u => u.role === 'STUDENT').length, 'registered')}
        ${statCard('Teachers', users.filter(u => u.role === 'TEACHER').length, 'staff')}
        ${statCard('Subjects', subjects.length, 'in catalog')}
        ${statCard('Components', comps.length, 'grade entries')}
      </div>
      <div class="grid grid-2" style="margin-top:16px">
        <div class="card">
          <h3>🏆 Top 5 students</h3>
          <p class="subtitle">By weighted average</p>
          <div style="margin-top:12px">${leaderTable(leader.slice(0, 5))}</div>
        </div>
        <div class="card">
          <h3>📊 Class statistics</h3>
          <p class="subtitle">Per subject</p>
          <div style="margin-top:12px">${statsTable(stats)}</div>
        </div>
      </div>`;
    return;
  }

  if (me.role === 'TEACHER') {
    const [users, stats] = await Promise.all([loadUsers(), computeStats(me)]);
    const mine = stats[0];
    el.innerHTML = `
      <div class="grid grid-4">
        ${statCard('My subject', me.subject || '—', 'assigned')}
        ${statCard('My students', users.filter(u => u.role === 'STUDENT').length, 'in class')}
        ${statCard('Class average', mine ? mine.average.toFixed(1) : '—',
                   mine ? `${mine.count} grades` : 'no data')}
        ${statCard('Pass rate', mine ? mine.passRate.toFixed(1) + '%' : '—', 'weighted')}
      </div>
      <div class="card" style="margin-top:16px">
        <h3>📊 ${esc(me.subject || 'Subject')} statistics</h3>
        <div style="margin-top:12px">${statsTable(stats)}</div>
      </div>`;
    return;
  }

  // student
  const [grades, leader] = await Promise.all([
    computeSubjectGrades(me.id), computeLeaderboard(),
  ]);
  const myRank = leader.find(r => r.studentId === me.id);
  const avg = grades.length ? grades.reduce((a, g) => a + g.score, 0) / grades.length : 0;
  const gpa = creditGpa(grades);
  el.innerHTML = `
    <div class="grid grid-4">
      ${statCard('My GPA', gpa.toFixed(2), 'credit-weighted')}
      ${statCard('Average', avg.toFixed(1), `${grades.length} subjects`)}
      ${statCard('My rank', myRank ? '#' + myRank.rank : '—',
                 myRank ? 'of the class' : 'no grades yet')}
      ${statCard('Subjects', grades.length, 'enrolled')}
    </div>
    <div class="grid grid-2" style="margin-top:16px">
      <div class="card">
        <h3>📝 My subjects</h3>
        <div style="margin-top:12px">${subjectGradeTable(grades)}</div>
      </div>
      <div class="card">
        <h3>🏆 Class top 5</h3>
        <div style="margin-top:12px">${leaderTable(leader.slice(0, 5))}</div>
      </div>
    </div>`;
};

/* ---- Users ---- */
views.users = async function(el) {
  const users = await loadUsers();
  el.innerHTML = `
    <div class="toolbar">
      <input class="input" id="user-search" placeholder="Search by ID or name…" style="max-width:320px">
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

  $('#user-search').oninput = e => {
    const q = e.target.value.toLowerCase().trim();
    renderTable(!q ? users : users.filter(u =>
      u.id.toLowerCase().includes(q) || u.name.toLowerCase().includes(q)));
  };
  $('#user-add').onclick = addUser;
};

async function nextUserId(prefix) {
  const { data } = await supabaseClient.from('users').select('id')
    .like('id', prefix + '%').order('id', { ascending: false }).limit(1);
  if (!data || data.length === 0) return prefix + '001';
  return prefix + String(parseInt(data[0].id.slice(1), 10) + 1).padStart(3, '0');
}

function addUser() {
  openModal(`
    <h2>New user</h2>
    <p class="subtitle">Create an account</p>
    <div class="field"><label>Role</label>
      <select class="input" id="f-role">
        <option value="STUDENT">Student</option>
        <option value="TEACHER">Teacher</option>
        <option value="ADMIN">Admin</option>
      </select>
    </div>
    <div class="field"><label>Name</label><input class="input" id="f-name"></div>
    <div class="field" id="f-subject-wrap" hidden>
      <label>Subject taught</label><input class="input" id="f-subject">
    </div>
    <div class="field"><label>Password</label>
      <input class="input" type="password" id="f-password"></div>
  `, [
    { label: 'Cancel', onClick: closeModal },
    { label: 'Create', primary: true, onClick: async () => {
        try {
          const role = $('#f-role').value;
          const name = $('#f-name').value.trim();
          const subject = role === 'TEACHER' ? $('#f-subject').value.trim() : null;
          const password = $('#f-password').value;
          if (!name || !password) throw new Error('Name and password are required');
          if (role === 'TEACHER' && !subject) throw new Error('Teachers must have a subject');
          const id = await nextUserId(role[0]);
          const { error } = await supabaseClient.from('users')
            .insert([{ id, name, role, subject, password }]);
          if (error) throw error;
          await logAction('ADD_USER', `${id} ${role} ${name}`);
          invalidate();
          closeModal();
          showAlert(`User ${id} created`);
          render();
        } catch (err) { showAlert(err.message, true); }
      }},
  ]);
  const roleSel = $('#f-role'), wrap = $('#f-subject-wrap');
  roleSel.onchange = () => { wrap.hidden = roleSel.value !== 'TEACHER'; };
}

async function editUser(id) {
  const users = await loadUsers();
  const u = users.find(x => x.id === id);
  if (!u) return;
  openModal(`
    <h2>Edit user</h2>
    <p class="subtitle">${esc(u.id)} · ${u.role}</p>
    <div class="field"><label>Name</label>
      <input class="input" id="e-name" value="${esc(u.name)}"></div>
    ${u.role === 'TEACHER' ? `
      <div class="field"><label>Subject taught</label>
        <input class="input" id="e-subject" value="${esc(u.subject || '')}"></div>` : ''}
  `, [
    { label: 'Cancel', onClick: closeModal },
    { label: 'Save', primary: true, onClick: async () => {
        try {
          const patch = { name: $('#e-name').value.trim() };
          if (u.role === 'TEACHER') patch.subject = $('#e-subject').value.trim();
          const { error } = await supabaseClient.from('users').update(patch).eq('id', u.id);
          if (error) throw error;
          await logAction('UPDATE_USER', `${u.id} ${patch.name}`);
          invalidate();
          closeModal();
          showAlert('Updated');
          render();
        } catch (err) { showAlert(err.message, true); }
      }},
  ]);
}

async function deleteUser(id) {
  if (id === currentUser.id) return showAlert('You cannot delete your own account', true);
  if (!confirm(`Delete user ${id}? This cascades to their grades.`)) return;
  try {
    const { error } = await supabaseClient.from('users').delete().eq('id', id);
    if (error) throw error;
    await logAction('DELETE_USER', id);
    invalidate();
    showAlert('Deleted');
    render();
  } catch (err) { showAlert(err.message, true); }
}

function resetPassword(id) {
  openModal(`
    <h2>Reset password</h2>
    <p class="subtitle">${esc(id)}</p>
    <div class="field"><label>New password</label>
      <input class="input" type="password" id="r-pw"></div>
    <div class="field"><label>Confirm</label>
      <input class="input" type="password" id="r-pw2"></div>
  `, [
    { label: 'Cancel', onClick: closeModal },
    { label: 'Reset', primary: true, onClick: async () => {
        if ($('#r-pw').value !== $('#r-pw2').value) return showAlert('Passwords do not match', true);
        if ($('#r-pw').value.length < 6) return showAlert('Password must be at least 6 characters', true);
        try {
          const { error } = await supabaseClient.from('users')
            .update({ password: $('#r-pw').value }).eq('id', id);
          if (error) throw error;
          await logAction('RESET_PASSWORD', id);
          closeModal();
          showAlert('Password reset');
        } catch (err) { showAlert(err.message, true); }
      }},
  ]);
}

/* ---- Subjects ---- */
views.subjects = async function(el) {
  const subs = await loadSubjects();
  el.innerHTML = `
    <div class="toolbar">
      <div class="spacer"></div>
      <button class="btn btn-primary" id="subj-add">+ Add subject</button>
    </div>
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
            </div></td>
          </tr>`).join('')}</tbody>
      </table>` : '<div class="empty">No subjects yet.</div>';
    el.querySelectorAll('[data-edit]').forEach(b => b.onclick = () => editSubject(b.dataset.edit));
  };
  renderTable(subs);
  $('#subj-add').onclick = () => editSubject('');

  function editSubject(name) {
    const cur = subs.find(s => s.name === name);
    openModal(`
      <h2>${cur ? 'Edit' : 'New'} subject</h2>
      <p class="subtitle">Credits weight the subject in GPA</p>
      <div class="field"><label>Name</label>
        <input class="input" id="s-name" value="${esc(cur ? cur.name : '')}" ${cur ? 'readonly' : ''}></div>
      <div class="field"><label>Credits</label>
        <input class="input" type="number" step="0.5" min="0.5" max="20"
               id="s-credits" value="${cur ? cur.credits : 1}"></div>
    `, [
      { label: 'Cancel', onClick: closeModal },
      { label: 'Save', primary: true, onClick: async () => {
          try {
            const subjName = $('#s-name').value.trim();
            const credits = parseFloat($('#s-credits').value);
            if (!subjName) throw new Error('Name is required');
            if (!(credits > 0 && credits <= 20)) throw new Error('Credits must be 0–20');
            const { error } = await supabaseClient.from('subjects')
              .upsert([{ name: subjName, credits }]);
            if (error) throw error;
            await logAction('SET_CREDITS', `${subjName} = ${credits}`);
            invalidate();
            closeModal();
            showAlert('Saved');
            render();
          } catch (err) { showAlert(err.message, true); }
        }},
    ]);
  }
};

/* ---- Students (admin + teacher) ---- */
views.students = async function(el) {
  const users = await loadUsers();
  const students = users.filter(u => u.role === 'STUDENT');
  el.innerHTML = `
    <div class="toolbar">
      <input class="input" id="stud-search" placeholder="Search students…" style="max-width:320px">
    </div>
    <div id="stud-grid" class="grid grid-3"></div>`;
  const renderGrid = list => {
    $('#stud-grid').innerHTML = list.length ? list.map(s => `
      <div class="card" data-card="${esc(s.id)}">
        <div style="display:flex;align-items:center;gap:12px">
          <div class="avatar" style="width:40px;height:40px;font-size:15px">
            ${esc(s.name.charAt(0).toUpperCase())}
          </div>
          <div>
            <h3>${esc(s.name)}</h3>
            <p class="subtitle">${esc(s.id)}</p>
          </div>
        </div>
      </div>`).join('') : '<div class="empty">No students found.</div>';
    el.querySelectorAll('[data-card]').forEach(c => c.onclick = () => openStudent(c.dataset.card));
  };
  renderGrid(students);
  $('#stud-search').oninput = e => {
    const q = e.target.value.toLowerCase().trim();
    renderGrid(!q ? students : students.filter(s =>
      s.id.toLowerCase().includes(q) || s.name.toLowerCase().includes(q)));
  };
};

async function openStudent(id) {
  const me = currentUser;
  const [grades, comps, leader] = await Promise.all([
    computeSubjectGrades(id),
    loadComponentsFor(id),
    computeLeaderboard(me.role === 'TEACHER' ? me : null),
  ]);
  const rank = leader.find(r => r.studentId === id);

  const visibleComps = me.role === 'TEACHER'
    ? comps.filter(c => c.subject.toLowerCase() === (me.subject || '').toLowerCase())
    : comps;
  const visibleGrades = me.role === 'TEACHER'
    ? grades.filter(g => g.subject.toLowerCase() === (me.subject || '').toLowerCase())
    : grades;

  openModal(`
    <h2>${esc(id)}</h2>
    <p class="subtitle">${rank
      ? `Rank #${rank.rank} · GPA ${rank.gpa.toFixed(2)} · avg ${rank.average.toFixed(1)}`
      : 'No grades yet'}</p>
    <h3 style="font-size:14px;margin:16px 0 8px">Subject grades</h3>
    ${subjectGradeTable(visibleGrades)}
    <h3 style="font-size:14px;margin:20px 0 8px">Grade components</h3>
    <div id="modal-comps">${componentTable(visibleComps, true)}</div>
    <h3 style="font-size:14px;margin:20px 0 8px">Transcript</h3>
    <pre class="transcript">${esc(buildTranscript(id, visibleGrades))}</pre>
  `, [
    { label: 'Close', onClick: closeModal },
    { label: '+ Add component', primary: true, onClick: () => addComponent(id) },
  ]);

  const mc = $('#modal-content');
  mc.querySelectorAll('[data-edit]').forEach(b => b.onclick = () => {
    const comp = visibleComps.find(c => String(c.id) === b.dataset.edit);
    if (comp) editComponent(id, comp);
  });
  mc.querySelectorAll('[data-del]').forEach(b => b.onclick = async () => {
    const comp = visibleComps.find(c => String(c.id) === b.dataset.del);
    if (!comp) return;
    if (!confirm(`Delete ${comp.subject} / ${comp.component_name}?`)) return;
    try {
      const { error } = await supabaseClient.from('grade_components')
        .delete().eq('id', comp.id);
      if (error) throw error;
      await logAction('DELETE_COMPONENT', `${id} ${comp.subject}/${comp.component_name}`);
      invalidate();
      showAlert('Deleted');
      openStudent(id);
    } catch (err) { showAlert(err.message, true); }
  });
}

function pad(s, n) { s = String(s); return s.length >= n ? s : s + ' '.repeat(n - s.length); }

function buildTranscript(studentId, grades) {
  const lines = ['=============================================',
                 ` TRANSCRIPT — ${studentId}`,
                 '============================================='];
  if (!grades.length) { lines.push(' No grades on record.'); return lines.join('\n'); }
  lines.push(pad('Subject', 20) + pad('Score', 7) + pad('Ltr', 5) + pad('Cr', 5) + 'GPA');
  let creditsSum = 0, pointsSum = 0, scoreSum = 0;
  for (const g of grades) {
    lines.push(pad(g.subject, 20) + pad(g.score.toFixed(1), 7) + pad(g.letter, 5)
             + pad(String(g.credits), 5) + g.gpaPoints.toFixed(2));
    creditsSum += Number(g.credits);
    pointsSum  += g.gpaPoints * Number(g.credits);
    scoreSum   += g.score;
  }
  const avg = scoreSum / grades.length;
  const gpa = creditsSum === 0 ? 0 : pointsSum / creditsSum;
  lines.push('---------------------------------------------');
  lines.push(` Average: ${avg.toFixed(2)}    GPA: ${gpa.toFixed(2)}    Credits: ${creditsSum.toFixed(1)}`);
  return lines.join('\n');
}

function addComponent(studentId) {
  const lockedSubject = currentUser.role === 'TEACHER' ? currentUser.subject : null;
  openModal(`
    <h2>Add component</h2>
    <p class="subtitle">Student ${esc(studentId)}</p>
    <div class="field"><label>Subject</label>
      ${lockedSubject
        ? `<input class="input" value="${esc(lockedSubject)}" readonly>`
        : `<input class="input" id="c-subject" placeholder="Math">`}
    </div>
    <div class="field"><label>Component name</label>
      <input class="input" id="c-name" placeholder="Quiz 1 / Midterm / Final"></div>
    <div class="grid grid-2" style="gap:12px">
      <div class="field"><label>Score (0-100)</label>
        <input class="input" type="number" min="0" max="100" step="0.1" id="c-score"></div>
      <div class="field"><label>Weight</label>
        <input class="input" type="number" min="0.1" step="0.1" id="c-weight" value="1"></div>
    </div>
    <div class="field"><label>Comment (optional)</label>
      <input class="input" id="c-comment"></div>
  `, [
    { label: 'Cancel', onClick: closeModal },
    { label: 'Add', primary: true, onClick: async () => {
        try {
          const subject = lockedSubject || $('#c-subject').value.trim();
          const component_name = $('#c-name').value.trim();
          const score  = parseFloat($('#c-score').value);
          const weight = parseFloat($('#c-weight').value);
          const comment = $('#c-comment').value.trim() || null;
          if (!subject || !component_name) throw new Error('Subject and name required');
          if (!(score >= 0 && score <= 100)) throw new Error('Score must be 0–100');
          if (!(weight > 0)) throw new Error('Weight must be > 0');

          const subs = await loadSubjects();
          if (!subs.find(s => s.name.toLowerCase() === subject.toLowerCase())) {
            await supabaseClient.from('subjects').insert([{ name: subject, credits: 1.0 }]);
          }
          const { error } = await supabaseClient.from('grade_components').insert([{
            student_id: studentId, subject, component_name, score, weight,
            graded_by: currentUser.id, comment,
            updated_at: Date.now(),
          }]);
          if (error) throw error;
          await logAction('ADD_COMPONENT', `${studentId} ${subject}/${component_name} = ${score}`);
          invalidate();
          closeModal();
          showAlert('Component added');
          openStudent(studentId);
        } catch (err) { showAlert(err.message, true); }
      }},
  ]);
}

function editComponent(studentId, comp) {
  openModal(`
    <h2>Edit component</h2>
    <p class="subtitle">${esc(comp.subject)} / ${esc(comp.component_name)}</p>
    <div class="grid grid-2" style="gap:12px">
      <div class="field"><label>Score</label>
        <input class="input" type="number" step="0.1" id="e-score" value="${comp.score}"></div>
      <div class="field"><label>Weight</label>
        <input class="input" type="number" step="0.1" id="e-weight" value="${comp.weight}"></div>
    </div>
    <div class="field"><label>Comment</label>
      <input class="input" id="e-comment" value="${esc(comp.comment || '')}"></div>
  `, [
    { label: 'Cancel', onClick: closeModal },
    { label: 'Save', primary: true, onClick: async () => {
        try {
          const score  = parseFloat($('#e-score').value);
          const weight = parseFloat($('#e-weight').value);
          const comment = $('#e-comment').value.trim() || null;
          if (!(score >= 0 && score <= 100)) throw new Error('Score must be 0–100');
          if (!(weight > 0)) throw new Error('Weight must be > 0');
          const { error } = await supabaseClient.from('grade_components').update({
            score, weight, comment,
            graded_by: currentUser.id,
            updated_at: Date.now(),
          }).eq('id', comp.id);
          if (error) throw error;
          await logAction('UPDATE_COMPONENT',
            `${studentId} ${comp.subject}/${comp.component_name} = ${score}`);
          invalidate();
          closeModal();
          showAlert('Updated');
          openStudent(studentId);
        } catch (err) { showAlert(err.message, true); }
      }},
  ]);
}

/* ---- Leaderboard / Honor roll / Failing ---- */
views.leaderboard = async function(el) {
  const rows = await computeLeaderboard(currentUser);
  el.innerHTML = `<div class="card">
    <h3>🏆 Leaderboard</h3>
    <p class="subtitle">By weighted average</p>
    <div style="margin-top:12px">${leaderTable(rows)}</div>
  </div>`;
};

views['honor-roll'] = async function(el) {
  const rows = (await computeLeaderboard()).filter(r => r.average >= HONOR_ROLL_MIN);
  el.innerHTML = `<div class="card">
    <h3>⭐ Honor roll</h3>
    <p class="subtitle">Average ≥ ${HONOR_ROLL_MIN}</p>
    <div style="margin-top:12px">${leaderTable(rows)}</div>
  </div>`;
};

views.failing = async function(el) {
  const leader = await computeLeaderboard();
  const failing = [];
  for (const r of leader) {
    const grades = await computeSubjectGrades(r.studentId);
    if (grades.some(g => !isPass(g.score))) failing.push(r);
  }
  el.innerHTML = `<div class="card">
    <h3>⚠ Failing students</h3>
    <p class="subtitle">At least one failing subject</p>
    <div style="margin-top:12px">${leaderTable(failing)}</div>
  </div>`;
};

/* ---- Class statistics ---- */
views.stats = async function(el) {
  const stats = await computeStats(currentUser);
  el.innerHTML = `<div class="card">
    <h3>📊 Class statistics</h3>
    <p class="subtitle">Per subject · median · std-dev · pass rate</p>
    <div style="margin-top:12px">${statsTable(stats)}</div>
  </div>`;
};

/* ---- Audit log ---- */
views.audit = async function(el) {
  const { data: logs, error } = await supabaseClient.from('audit_log')
    .select('*').order('created_at', { ascending: false }).limit(100);
  if (error) throw error;
  el.innerHTML = `<div class="card">
    <h3>📋 Audit log</h3>
    <p class="subtitle">Most recent 100 entries</p>
    <div style="margin-top:12px">${
      logs.length
        ? `<table>
             <thead><tr><th>When</th><th>Actor</th><th>Action</th><th>Details</th></tr></thead>
             <tbody>${logs.map(l => `
               <tr>
                 <td style="font-family:monospace;font-size:11px">
                   ${l.created_at ? new Date(l.created_at).toLocaleString() : '—'}
                 </td>
                 <td>${esc(l.actor || '—')}</td>
                 <td><code>${esc(l.action)}</code></td>
                 <td>${esc(l.details || '')}</td>
               </tr>`).join('')}</tbody>
           </table>`
        : '<div class="empty">No entries.</div>'
    }</div>
  </div>`;
};

/* ---- Student: my grades ---- */
views['my-grades'] = async function(el) {
  const me = currentUser;
  const [grades, comps] = await Promise.all([
    computeSubjectGrades(me.id),
    loadComponentsFor(me.id),
  ]);
  el.innerHTML = `
    <div class="grid grid-2">
      <div class="card">
        <h3>📝 Subject grades</h3>
        <p class="subtitle">Weighted averages</p>
        <div style="margin-top:12px">${subjectGradeTable(grades)}</div>
      </div>
      <div class="card">
        <h3>📄 Transcript</h3>
        <p class="subtitle">Detailed report</p>
        <pre class="transcript" style="margin-top:12px">${esc(buildTranscript(me.id, grades))}</pre>
      </div>
    </div>
    <div class="card" style="margin-top:16px">
      <h3>🔍 Components</h3>
      <p class="subtitle">All recorded scores</p>
      <div style="margin-top:12px">${componentTable(comps, false)}</div>
    </div>`;
};

/* ---- Student: top 5 ---- */
views.top = async function(el) {
  const rows = (await computeLeaderboard()).slice(0, 5);
  el.innerHTML = `<div class="card">
    <h3>🏆 Class top 5</h3>
    <p class="subtitle">Highest weighted averages</p>
    <div style="margin-top:12px">${leaderTable(rows)}</div>
  </div>`;
};

/* ---- Settings ---- */
views.settings = async function(el) {
  el.innerHTML = `
    <div class="card" style="max-width:520px">
      <h3>⚙ Account</h3>
      <p class="subtitle">Update your password</p>
      <div style="margin-top:16px">
        <div class="field"><label>Current password</label>
          <input class="input" type="password" id="pw-old"></div>
        <div class="field"><label>New password</label>
          <input class="input" type="password" id="pw-new"></div>
        <div class="field"><label>Confirm new password</label>
          <input class="input" type="password" id="pw-confirm"></div>
        <button class="btn btn-primary btn-block" id="pw-save">Update password</button>
      </div>
    </div>`;
  $('#pw-save').onclick = async () => {
    try {
      const old = $('#pw-old').value;
      const nw = $('#pw-new').value;
      if (old !== currentUser.password) throw new Error('Current password is wrong');
      if (nw.length < 6) throw new Error('New password must be at least 6 characters');
      if (nw !== $('#pw-confirm').value) throw new Error('Passwords do not match');
      const { error } = await supabaseClient.from('users')
        .update({ password: nw }).eq('id', currentUser.id);
      if (error) throw error;
      currentUser.password = nw;
      await logAction('CHANGE_PASSWORD', 'own password');
      showAlert('Password updated');
      $('#pw-old').value = $('#pw-new').value = $('#pw-confirm').value = '';
    } catch (err) { showAlert(err.message, true); }
  };
};
