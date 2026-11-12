/* ================================================================
   Student Management System — web client (MySQL version)
   Talks to ApiServer.java through /api/...  (same address as this page)
   Open it at  http://localhost:8080  — do NOT double-click index.html.
   ================================================================ */

/* Empty = same-origin API, either served by Java or forwarded by the Vercel proxy.
   For Vercel, configure SMS_BACKEND_URL on the server, not in this public file. */
const API_BASE = String(window.SMS_API_BASE || "").trim().replace(/\/+$/, "");

/* ---------------- state ---------------- */
let token = sessionStorage.getItem("sms_token");
let currentUser = null;
let currentView = "dashboard";
const cache = { users: null, subjects: null };

const HONOR_ROLL_MIN = 85;

/* features.js adds its sidebar entries here */
const featureNav = { ADMIN: [], TEACHER: [], STUDENT: [] };
const views = {};

/* ---------------- helpers ---------------- */
const $ = (s) => document.querySelector(s);
const $$ = (s) => document.querySelectorAll(s);
const enc = encodeURIComponent;

function esc(v) {
  return String(v ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

function toast(msg, type = "info") {
  const el = document.createElement("div");
  el.className = "toast " + type;
  el.textContent = msg;
  $("#toast-root").append(el);
  setTimeout(() => {
    el.style.transition = "opacity .3s,transform .3s";
    el.style.opacity = "0";
    el.style.transform = "translateX(20px)";
    setTimeout(() => el.remove(), 300);
  }, 3500);
}
function showAlert(message, isError = false) {
  toast(message, isError ? "error" : "success");
}

function openModal(html, actions, wide = false) {
  const box = $("#modal-content");
  box.className = "modal" + (wide ? " wide" : "");
  box.innerHTML = html + '<div class="modal-actions" id="modal-actions"></div>';
  const bar = $("#modal-actions");
  for (const a of actions) {
    const b = document.createElement("button");
    b.className = "btn " + (a.primary ? "btn-primary" : a.danger ? "btn-danger" : "btn-secondary");
    b.textContent = a.label;
    b.onclick = a.onClick;
    bar.append(b);
  }
  const root = $("#modal-root");
  root.classList.add("show");
  root.onclick = (e) => {
    if (e.target === root) closeModal();
  };
}
function closeModal() {
  $("#modal-root").classList.remove("show");
}
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") closeModal();
});

function invalidate() {
  cache.users = cache.subjects = null;
}

function pwProblem(pw) {
  if (!pw || pw.length < 8) return "Password must be at least 8 characters.";
  if (!/[A-Za-z]/.test(pw) || !/[0-9]/.test(pw)) return "Password needs at least one letter and one digit.";
  return null;
}

/* ---------------- server calls ---------------- */
async function api(method, path, body) {
  const opt = { method, headers: {} };
  if (token) opt.headers.Authorization = "Bearer " + token;
  if (body !== undefined) {
    opt.headers["Content-Type"] = "application/json";
    opt.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(API_BASE + "/api" + path, opt);
  } catch (e) {
    throw new Error(
      API_BASE
        ? "Cannot reach the backend at " + API_BASE + " — check the link in config.js and that it opens in the browser."
        : "Cannot reach the server. Check that the website URL is correct and its backend is running and configured.",
    );
  }
  const isJson = (res.headers.get("Content-Type") || "").includes("json");
  const data = isJson ? await res.json() : await res.text();
  if (!res.ok) {
    if (res.status === 401 && currentUser) {
      logout();
      toast("Session expired. Please sign in again.", "error");
    }
    if (!isJson && [404, 502, 503, 504].includes(res.status))
      throw new Error(
        `The website cannot reach the backend (HTTP ${res.status}). ` +
          (API_BASE
            ? "Check the backend link in config.js and confirm that <backend-url>/api/health opens."
            : "Check the hosting platform's backend URL setting and confirm that <backend-url>/api/health opens."),
      );
    throw new Error((data && data.message) || "Error " + res.status);
  }
  return data;
}
const get = (p) => api("GET", p);
const post = (p, b) => api("POST", p, b || {});
const qs = (o) =>
  "?" +
  Object.entries(o)
    .filter(([, v]) => v !== undefined && v !== null && v !== "")
    .map(([k, v]) => enc(k) + "=" + enc(v))
    .join("&");

/* download a protected file (needs the Authorization header, so not a plain link) */
async function downloadFile(path, filename) {
  const res = await fetch(API_BASE + "/api" + path, { headers: { Authorization: "Bearer " + token } });
  if (!res.ok) {
    let msg = "Download failed";
    try {
      msg = (await res.json()).message || msg;
    } catch (_) {}
    throw new Error(msg);
  }
  const blob = await res.blob();
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

/* ---------------- small rules (display only; the server enforces everything) ---------------- */
const gpaPoints = (letter) => ({ A: 4, B: 3, C: 2, D: 1, E: 0.5, F: 0 })[letter] || 0;
function creditGpa(grades) {
  let c = 0,
    p = 0;
  for (const g of grades) {
    c += Number(g.credits);
    p += Number(g.gpaPoints) * Number(g.credits);
  }
  return c === 0 ? 0 : p / c;
}
/* can the signed-in user change grades of this subject? */
function canGrade(subject) {
  if (!currentUser) return false;
  if (currentUser.role === "ADMIN") return true;
  return (
    currentUser.role === "TEACHER" &&
    !!currentUser.subject &&
    currentUser.subject.toLowerCase() === String(subject || "").trim().toLowerCase()
  );
}

/* ---------------- cached loaders ---------------- */
async function loadUsers() {
  return (cache.users ||= await get("/users"));
}
async function loadSubjects() {
  return (cache.subjects ||= await get("/subjects"));
}

/* ---------------- login / logout ---------------- */
$("#login-form").onsubmit = async (e) => {
  e.preventDefault();
  const btn = $("#login-btn");
  btn.disabled = true;
  btn.textContent = "Signing in…";
  try {
    const res = await post("/login", {
      id: $("#login-id").value.trim(),
      password: $("#login-password").value,
    });
    token = res.token;
    sessionStorage.setItem("sms_token", token);
    currentUser = res.user;
    initDashboard();
  } catch (err) {
    toast(err.message || "Login failed", "error");
    const box = $("#login-status");
    box.style.color = "#fca5a5";
    box.textContent = "⚠ " + (err.message || "Login failed");
  } finally {
    btn.disabled = false;
    btn.textContent = "Sign in";
  }
};

function logout() {
  if (token) api("POST", "/logout", {}).catch(() => {});
  sessionStorage.removeItem("sms_token");
  token = null;
  currentUser = null;
  invalidate();
  currentView = "dashboard";
  closeModal();
  $("#app-view").hidden = true;
  $("#login-view").hidden = false;
  $("#login-form").reset();
  $("#content").innerHTML = "";
}
$("#logout-btn").onclick = logout;

function initDashboard() {
  $("#login-view").hidden = true;
  $("#app-view").hidden = false;
  $("#user-name").textContent = currentUser.name;
  $("#user-role").textContent = currentUser.role;
  $("#user-role").className = "badge badge-" + currentUser.role.toLowerCase();
  $("#user-avatar").textContent = (currentUser.name || "?").charAt(0).toUpperCase();
  currentView = "dashboard";
  renderNav();
  render();
}

/* ---------------- navigation ---------------- */
function navFor(role) {
  const extra = featureNav[role] || [];
  if (role === "ADMIN")
    return [
      { id: "dashboard", icon: "◉", label: "Dashboard" },
      { group: "Users" },
      { id: "users", icon: "👥", label: "Users" },
      { id: "subjects", icon: "📚", label: "Subjects" },
      { group: "Grades" },
      { id: "students", icon: "🎒", label: "Students" },
      { id: "leaderboard", icon: "🏆", label: "Leaderboard" },
      { id: "honor-roll", icon: "⭐", label: "Honor Roll" },
      { id: "failing", icon: "⚠", label: "Failing" },
      { id: "stats", icon: "📊", label: "Class Stats" },
      ...extra,
      { group: "Admin" },
      { id: "audit", icon: "📋", label: "Audit Log" },
      { id: "settings", icon: "⚙", label: "Settings" },
    ];
  if (role === "TEACHER")
    return [
      { id: "dashboard", icon: "◉", label: "Dashboard" },
      { id: "students", icon: "🎒", label: "Students" },
      { id: "leaderboard", icon: "🏆", label: "Leaderboard" },
      { id: "stats", icon: "📊", label: "Class Stats" },
      ...extra,
      { id: "settings", icon: "⚙", label: "Settings" },
    ];
  return [
    { id: "dashboard", icon: "◉", label: "Dashboard" },
    { id: "my-grades", icon: "📝", label: "My Grades" },
    { id: "top", icon: "🏆", label: "Top 5" },
    ...extra,
    { id: "settings", icon: "⚙", label: "Settings" },
  ];
}

function renderNav() {
  const nav = $("#nav");
  nav.innerHTML = "";
  for (const item of navFor(currentUser.role)) {
    if (item.group) {
      const t = document.createElement("div");
      t.className = "nav-group-title";
      t.textContent = item.group;
      nav.append(t);
      continue;
    }
    const b = document.createElement("button");
    b.className = "nav-item";
    b.dataset.view = item.id;
    b.innerHTML = `<span style="font-size:16px;width:18px;text-align:center">${item.icon}</span><span>${esc(item.label)}</span>`;
    b.onclick = () => {
      currentView = item.id;
      render();
    };
    nav.append(b);
  }
}

let renderToken = 0;
async function render() {
  const my = ++renderToken;
  const el = $("#content");
  el.innerHTML = '<div class="empty">Loading…</div>';
  const item = navFor(currentUser.role).find((n) => n.id === currentView) || { label: "Dashboard" };
  $("#page-title").textContent = item.label;
  $("#brand-sub").textContent = item.label;
  $$(".nav-item[data-view]").forEach((n) => n.classList.toggle("active", n.dataset.view === currentView));
  try {
    if (!views[currentView]) {
      el.innerHTML = '<div class="empty">Unknown view.</div>';
      return;
    }
    await views[currentView](el);
  } catch (err) {
    console.error(err);
    if (my === renderToken) el.innerHTML = `<div class="empty">⚠ ${esc(err.message)}</div>`;
  }
}

/* ---------------- shared table builders ---------------- */
const tableWrap = (html) => `<div class="table-wrap">${html}</div>`;

function leaderTable(rows) {
  if (!rows.length) return '<div class="empty">No data.</div>';
  return tableWrap(`<table>
    <thead><tr><th>#</th><th>Student</th><th>Average</th><th>GPA</th><th>Subjects</th></tr></thead>
    <tbody>${rows
      .map((r) => {
        const c = r.rank === 1 ? "gold" : r.rank === 2 ? "silver" : r.rank === 3 ? "bronze" : "";
        return `<tr>
        <td><span class="rank-badge ${c}">${r.rank}</span></td>
        <td><strong>${esc(r.name)}</strong><br><span class="subtitle">${esc(r.studentId)}</span></td>
        <td>${Number(r.average).toFixed(2)}</td>
        <td>${Number(r.gpa).toFixed(2)}</td>
        <td>${r.subjectCount}</td>
      </tr>`;
      })
      .join("")}</tbody>
  </table>`);
}

function subjectGradeTable(grades) {
  if (!grades.length) return '<div class="empty">No grades.</div>';
  return tableWrap(`<table>
    <thead><tr><th>Subject</th><th>Score</th><th>Letter</th><th>Credits</th><th>Result</th></tr></thead>
    <tbody>${grades
      .map(
        (g) => `<tr>
        <td><strong>${esc(g.subject)}</strong></td>
        <td>${Number(g.score).toFixed(1)}</td>
        <td><span class="badge badge-${esc(g.letter).toLowerCase()}">${esc(g.letter)}</span></td>
        <td>${g.credits}</td>
        <td><span class="badge badge-${esc(g.result).toLowerCase()}">${esc(g.result)}</span></td>
      </tr>`,
      )
      .join("")}</tbody>
  </table>`);
}

/* canEdit: false | function(component) -> boolean ; buttons carry the row index */
function componentTable(comps, canEdit) {
  if (!comps.length) return '<div class="empty">No components.</div>';
  return tableWrap(`<table>
    <thead><tr><th>Subject</th><th>Component</th><th>Score</th><th>Weight</th>
      <th>By</th>${canEdit ? "<th></th>" : ""}</tr></thead>
    <tbody>${comps
      .map(
        (c, i) => `<tr>
        <td>${esc(c.subject)}</td>
        <td>${esc(c.componentName)}${c.comment ? `<br><span class="subtitle">${esc(c.comment)}</span>` : ""}</td>
        <td>${Number(c.score).toFixed(1)}</td>
        <td>${Number(c.weight).toFixed(1)}</td>
        <td>${esc(c.gradedBy || "—")}</td>
        ${
          canEdit
            ? `<td>${
                canEdit(c)
                  ? `<div class="row-actions">
                      <button class="btn btn-secondary btn-sm" data-edit="${i}">Edit</button>
                      <button class="btn btn-danger btn-sm" data-del="${i}">Del</button>
                    </div>`
                  : ""
              }</td>`
            : ""
        }
      </tr>`,
      )
      .join("")}</tbody>
  </table>`);
}

function statCard(label, value, hint) {
  return `<div class="stat-card">
    <div class="label">${esc(label)}</div>
    <div class="value">${esc(String(value))}</div>
    ${hint ? `<div class="hint">${esc(hint)}</div>` : ""}
  </div>`;
}

function statsTable(stats) {
  if (!stats.length) return '<div class="empty">No data.</div>';
  return tableWrap(`<table>
    <thead><tr><th>Subject</th><th>N</th><th>Avg</th><th>Median</th>
      <th>Std</th><th>Min</th><th>Max</th><th>Pass%</th></tr></thead>
    <tbody>${stats
      .map((s) => {
        const c =
          s.passRate >= 80
            ? "linear-gradient(90deg,#10b981,#34d399)"
            : s.passRate >= 50
              ? "linear-gradient(90deg,#f59e0b,#fbbf24)"
              : "linear-gradient(90deg,#ef4444,#f87171)";
        return `<tr>
        <td><strong>${esc(s.subject)}</strong></td>
        <td>${s.count}</td>
        <td>${Number(s.average).toFixed(2)}</td>
        <td>${Number(s.median).toFixed(2)}</td>
        <td>${Number(s.stdDev).toFixed(2)}</td>
        <td>${Number(s.min).toFixed(1)}</td>
        <td>${Number(s.max).toFixed(1)}</td>
        <td><div style="display:flex;align-items:center;gap:8px">
            <span>${Number(s.passRate).toFixed(1)}%</span>
            <div class="bar-wrap" style="flex:1;min-width:60px">
              <div class="bar" style="width:${s.passRate}%;background:${c}"></div>
            </div></div></td>
      </tr>`;
      })
      .join("")}</tbody>
  </table>`);
}

/* ================================================================
   VIEWS
   ================================================================ */

/* ---- Dashboard ---- */
views.dashboard = async function (el) {
  const me = currentUser;

  if (me.role === "ADMIN") {
    const [sum, top, stats] = await Promise.all([get("/summary"), get("/top/5"), get("/stats")]);
    el.innerHTML = `
      <div class="grid grid-4">
        ${statCard("Students", sum.students, "registered")}
        ${statCard("Teachers", sum.teachers, "staff")}
        ${statCard("Subjects", sum.subjects, "in catalog")}
        ${statCard("Components", sum.components, "grade entries")}
      </div>
      <div class="grid grid-2" style="margin-top:16px">
        <div class="card"><h3>🏆 Top 5 students</h3><p class="subtitle">By average</p>
          <div style="margin-top:12px">${leaderTable(top)}</div></div>
        <div class="card"><h3>📊 Class statistics</h3><p class="subtitle">Per subject</p>
          <div style="margin-top:12px">${statsTable(stats)}</div></div>
      </div>`;
    return;
  }

  if (me.role === "TEACHER") {
    const [users, stats] = await Promise.all([loadUsers(), get("/stats")]);
    const mine = stats[0];
    el.innerHTML = `
      <div class="grid grid-4">
        ${statCard("My subject", me.subject || "—", "assigned")}
        ${statCard("Students", users.filter((u) => u.role === "STUDENT").length, "in school")}
        ${statCard("Class average", mine ? Number(mine.average).toFixed(1) : "—", mine ? `${mine.count} students graded` : "no data")}
        ${statCard("Pass rate", mine ? Number(mine.passRate).toFixed(1) + "%" : "—", "weighted")}
      </div>
      <div class="card" style="margin-top:16px">
        <h3>📊 ${esc(me.subject || "Subject")} statistics</h3>
        <div style="margin-top:12px">${statsTable(stats)}</div></div>`;
    return;
  }

  const [grades, top, rank] = await Promise.all([
    get("/grades/student/" + enc(me.id)),
    get("/top/5"),
    get("/grades/student/" + enc(me.id) + "/rank").catch(() => null),
  ]);
  const avg = grades.length ? grades.reduce((a, g) => a + Number(g.score), 0) / grades.length : 0;
  el.innerHTML = `
    <div class="grid grid-4">
      ${statCard("My GPA", creditGpa(grades).toFixed(2), "credit-weighted")}
      ${statCard("Average", avg.toFixed(1), `${grades.length} subjects`)}
      ${statCard("My rank", rank ? "#" + rank.rank : "—", rank ? "of the class" : "no grades yet")}
      ${statCard("Subjects", grades.length, "with grades")}
    </div>
    <div class="grid grid-2" style="margin-top:16px">
      <div class="card"><h3>📝 My subjects</h3>
        <div style="margin-top:12px">${subjectGradeTable(grades)}</div></div>
      <div class="card"><h3>🏆 Class top 5</h3>
        <div style="margin-top:12px">${leaderTable(top)}</div></div>
    </div>`;
};

/* ---- Users (admin) ---- */
views.users = async function (el) {
  const users = await loadUsers();
  el.innerHTML = `
    <div class="toolbar">
      <input class="input" id="user-search" placeholder="Search by ID or name…" style="max-width:320px">
      <div class="spacer"></div>
      <button class="btn btn-primary" id="user-add">+ New user</button>
    </div>
    <div id="user-table"></div>`;

  const renderTable = (list) => {
    el.querySelector("#user-table").innerHTML = list.length
      ? tableWrap(`<table>
        <thead><tr><th>ID</th><th>Name</th><th>Role</th><th>Subject</th><th></th></tr></thead>
        <tbody>${list
          .map(
            (u) => `<tr>
            <td><code>${esc(u.id)}</code></td>
            <td>${esc(u.name)}</td>
            <td><span class="badge badge-${esc(u.role).toLowerCase()}">${esc(u.role)}</span></td>
            <td>${u.subject ? esc(u.subject) : '<span style="color:var(--muted)">—</span>'}</td>
            <td><div class="row-actions">
              <button class="btn btn-secondary btn-sm" data-edit="${esc(u.id)}">Edit</button>
              <button class="btn btn-secondary btn-sm" data-reset="${esc(u.id)}">Reset PW</button>
              <button class="btn btn-danger btn-sm" data-del="${esc(u.id)}">Delete</button>
            </div></td>
          </tr>`,
          )
          .join("")}</tbody>
      </table>`)
      : '<div class="empty">No users.</div>';
    el.querySelectorAll("[data-edit]").forEach((b) => (b.onclick = () => editUser(b.dataset.edit)));
    el.querySelectorAll("[data-del]").forEach((b) => (b.onclick = () => deleteUser(b.dataset.del)));
    el.querySelectorAll("[data-reset]").forEach((b) => (b.onclick = () => resetPassword(b.dataset.reset)));
  };
  renderTable(users);

  el.querySelector("#user-search").oninput = (e) => {
    const q = e.target.value.toLowerCase().trim();
    renderTable(!q ? users : users.filter((u) => u.id.toLowerCase().includes(q) || u.name.toLowerCase().includes(q)));
  };
  el.querySelector("#user-add").onclick = addUser;
};

function addUser() {
  openModal(
    `<h2>New user</h2>
    <p class="subtitle">The ID is generated automatically (A001, T001, S001 …)</p>
    <div class="field"><label>Role</label>
      <select class="input" id="f-role">
        <option value="STUDENT">Student</option>
        <option value="TEACHER">Teacher</option>
        <option value="ADMIN">Admin</option>
      </select></div>
    <div class="field"><label>Name</label><input class="input" id="f-name" maxlength="100"></div>
    <div class="field" id="f-subject-wrap" hidden>
      <label>Subject taught</label><input class="input" id="f-subject" maxlength="60"></div>
    <div class="field"><label>Password (8+ chars, letter + digit)</label>
      <input class="input" type="password" id="f-password" autocomplete="new-password"></div>`,
    [
      { label: "Cancel", onClick: closeModal },
      {
        label: "Create",
        primary: true,
        onClick: async () => {
          try {
            const role = $("#f-role").value;
            const name = $("#f-name").value.trim();
            const subject = role === "TEACHER" ? $("#f-subject").value.trim() : "";
            const password = $("#f-password").value;
            if (!name) throw new Error("Name is required");
            if (role === "TEACHER" && !subject) throw new Error("Teachers must have a subject");
            const bad = pwProblem(password);
            if (bad) throw new Error(bad);
            const res = await post("/users", { role, name, subject, password });
            invalidate();
            closeModal();
            showAlert(`User ${res.id} created`);
            render();
          } catch (err) {
            showAlert(err.message, true);
          }
        },
      },
    ],
  );
  $("#f-role").onchange = (e) => {
    $("#f-subject-wrap").hidden = e.target.value !== "TEACHER";
  };
}

async function editUser(id) {
  const u = (await loadUsers()).find((x) => x.id === id);
  if (!u) return;
  openModal(
    `<h2>Edit user</h2>
    <p class="subtitle">${esc(u.id)} · ${esc(u.role)}</p>
    <div class="field"><label>Name</label>
      <input class="input" id="e-name" maxlength="100" value="${esc(u.name)}"></div>
    ${
      u.role === "TEACHER"
        ? `<div class="field"><label>Subject taught</label>
            <input class="input" id="e-subject" maxlength="60" value="${esc(u.subject || "")}"></div>`
        : ""
    }`,
    [
      { label: "Cancel", onClick: closeModal },
      {
        label: "Save",
        primary: true,
        onClick: async () => {
          try {
            const name = $("#e-name").value.trim();
            if (!name) throw new Error("Name is required");
            const subject = u.role === "TEACHER" ? $("#e-subject").value.trim() : "";
            await post("/users/update", { id: u.id, name, subject });
            invalidate();
            closeModal();
            showAlert("Updated");
            render();
          } catch (err) {
            showAlert(err.message, true);
          }
        },
      },
    ],
  );
}

async function deleteUser(id) {
  if (id === currentUser.id) return showAlert("You cannot delete your own account", true);
  if (!confirm(`Delete user ${id}? This also deletes their grades.`)) return;
  try {
    await post("/users/delete", { id });
    invalidate();
    showAlert("Deleted");
    render();
  } catch (err) {
    showAlert(err.message, true);
  }
}

function resetPassword(id) {
  openModal(
    `<h2>Reset password</h2>
    <p class="subtitle">${esc(id)} — they will be signed out</p>
    <div class="field"><label>New password</label>
      <input class="input" type="password" id="r-pw" autocomplete="new-password"></div>
    <div class="field"><label>Confirm</label>
      <input class="input" type="password" id="r-pw2" autocomplete="new-password"></div>`,
    [
      { label: "Cancel", onClick: closeModal },
      {
        label: "Reset",
        primary: true,
        onClick: async () => {
          const pw = $("#r-pw").value;
          if (pw !== $("#r-pw2").value) return showAlert("Passwords do not match", true);
          const bad = pwProblem(pw);
          if (bad) return showAlert(bad, true);
          try {
            await post("/users/reset-password", { id, password: pw });
            closeModal();
            showAlert("Password reset");
          } catch (err) {
            showAlert(err.message, true);
          }
        },
      },
    ],
  );
}

/* ---- Subjects (admin) ---- */
views.subjects = async function (el) {
  const subs = await loadSubjects();
  el.innerHTML = `
    <div class="toolbar"><div class="spacer"></div>
      <button class="btn btn-primary" id="subj-add">+ Add subject</button></div>
    <div id="subj-table"></div>`;

  el.querySelector("#subj-table").innerHTML = subs.length
    ? tableWrap(`<table>
        <thead><tr><th>Subject</th><th>Credits</th><th></th></tr></thead>
        <tbody>${subs
          .map(
            (s) => `<tr>
            <td><strong>${esc(s.name)}</strong></td>
            <td>${s.credits}</td>
            <td><div class="row-actions">
              <button class="btn btn-secondary btn-sm" data-edit="${esc(s.name)}">Edit</button>
            </div></td></tr>`,
          )
          .join("")}</tbody></table>`)
    : '<div class="empty">No subjects yet.</div>';
  el.querySelectorAll("[data-edit]").forEach((b) => (b.onclick = () => editSubject(b.dataset.edit)));
  el.querySelector("#subj-add").onclick = () => editSubject("");

  function editSubject(name) {
    const cur = subs.find((s) => s.name === name);
    openModal(
      `<h2>${cur ? "Edit" : "New"} subject</h2>
      <p class="subtitle">Credits weight the subject in GPA</p>
      <div class="field"><label>Name</label>
        <input class="input" id="s-name" maxlength="60" value="${esc(cur ? cur.name : "")}" ${cur ? "readonly" : ""}></div>
      <div class="field"><label>Credits</label>
        <input class="input" type="number" step="0.5" min="0.5" max="20" id="s-credits" value="${cur ? cur.credits : 1}"></div>`,
      [
        { label: "Cancel", onClick: closeModal },
        {
          label: "Save",
          primary: true,
          onClick: async () => {
            try {
              const subjName = $("#s-name").value.trim();
              const credits = parseFloat($("#s-credits").value);
              if (!subjName) throw new Error("Name is required");
              if (!(credits > 0 && credits <= 20)) throw new Error("Credits must be 0–20");
              await post("/subjects", { name: subjName, credits });
              invalidate();
              closeModal();
              showAlert("Saved");
              render();
            } catch (err) {
              showAlert(err.message, true);
            }
          },
        },
      ],
    );
  }
};

/* ---- Students (admin + teacher) ---- */
views.students = async function (el) {
  const users = await loadUsers();
  const students = users.filter((u) => u.role === "STUDENT");
  el.innerHTML = `
    <div class="toolbar">
      <input class="input" id="stud-search" placeholder="Search students…" style="max-width:320px">
      <div class="spacer"></div>
      ${currentUser.role === "ADMIN" ? '<button class="btn btn-secondary" id="stud-export">⬇ Export grades CSV</button>' : ""}
    </div>
    <div id="stud-grid" class="grid grid-3"></div>`;
  const grid = el.querySelector("#stud-grid");
  const renderGrid = (list) => {
    grid.innerHTML = list.length
      ? list
          .map(
            (s) => `<div class="card" data-card="${esc(s.id)}">
        <div style="display:flex;align-items:center;gap:12px">
          <div class="avatar" style="width:40px;height:40px;font-size:15px">${esc(s.name.charAt(0).toUpperCase())}</div>
          <div><h3>${esc(s.name)}</h3><p class="subtitle">${esc(s.id)}</p></div>
        </div></div>`,
          )
          .join("")
      : '<div class="empty">No students found.</div>';
    grid.querySelectorAll("[data-card]").forEach((c) => (c.onclick = () => openStudent(c.dataset.card)));
  };
  renderGrid(students);
  el.querySelector("#stud-search").oninput = (e) => {
    const q = e.target.value.toLowerCase().trim();
    renderGrid(!q ? students : students.filter((s) => s.id.toLowerCase().includes(q) || s.name.toLowerCase().includes(q)));
  };
  const exp = el.querySelector("#stud-export");
  if (exp)
    exp.onclick = () =>
      downloadFile("/export/csv", "export_grades.csv").catch((err) => showAlert(err.message, true));
};

async function openStudent(id) {
  const me = currentUser;
  try {
    const [grades, comps, rank, transcript] = await Promise.all([
      get("/grades/student/" + enc(id)),
      get("/grades/student/" + enc(id) + "/components"),
      get("/grades/student/" + enc(id) + "/rank").catch(() => null),
      get("/transcript/" + enc(id)),
    ]);
    const student = (await loadUsers()).find((u) => u.id === id);

    openModal(
      `<h2>${esc(student ? student.name : id)} <span class="subtitle">${esc(id)}</span></h2>
      <p class="subtitle">${
        rank ? `Rank #${rank.rank} · GPA ${Number(rank.gpa).toFixed(2)} · avg ${Number(rank.average).toFixed(1)}` : "No grades yet"
      }</p>
      <h3 style="font-size:14px;margin:16px 0 8px">Subject grades</h3>
      ${subjectGradeTable(grades)}
      <h3 style="font-size:14px;margin:20px 0 8px">Grade components</h3>
      ${componentTable(comps, (c) => canGrade(c.subject))}
      <h3 style="font-size:14px;margin:20px 0 8px">Transcript</h3>
      <pre class="transcript">${esc(transcript)}</pre>`,
      [
        { label: "Close", onClick: closeModal },
        ...(me.role === "ADMIN" || (me.role === "TEACHER" && me.subject)
          ? [{ label: "+ Add component", primary: true, onClick: () => addComponent(id) }]
          : []),
      ],
      true,
    );

    const mc = $("#modal-content");
    mc.querySelectorAll("[data-edit]").forEach(
      (b) => (b.onclick = () => editComponent(id, comps[Number(b.dataset.edit)])),
    );
    mc.querySelectorAll("[data-del]").forEach(
      (b) =>
        (b.onclick = async () => {
          const c = comps[Number(b.dataset.del)];
          if (!c || !confirm(`Delete ${c.subject} / ${c.componentName}?`)) return;
          try {
            await post("/grades/component/delete", {
              studentId: id,
              subject: c.subject,
              componentName: c.componentName,
            });
            showAlert("Deleted");
            openStudent(id);
          } catch (err) {
            showAlert(err.message, true);
          }
        }),
    );
  } catch (err) {
    showAlert(err.message, true);
  }
}

function addComponent(studentId) {
  const lockedSubject = currentUser.role === "TEACHER" ? currentUser.subject : null;
  openModal(
    `<h2>Add component</h2>
    <p class="subtitle">Student ${esc(studentId)}</p>
    <div class="field"><label>Subject</label>
      ${
        lockedSubject
          ? `<input class="input" value="${esc(lockedSubject)}" readonly>`
          : `<input class="input" id="c-subject" maxlength="60" placeholder="Math">`
      }</div>
    <div class="field"><label>Component name</label>
      <input class="input" id="c-name" maxlength="40" placeholder="Quiz 1 / Midterm / Final"></div>
    <div class="grid grid-2" style="gap:12px">
      <div class="field"><label>Score (0-100)</label>
        <input class="input" type="number" min="0" max="100" step="0.1" id="c-score"></div>
      <div class="field"><label>Weight</label>
        <input class="input" type="number" min="0.1" step="0.1" id="c-weight" value="1"></div>
    </div>
    <div class="field"><label>Comment (optional)</label>
      <input class="input" id="c-comment" maxlength="200"></div>`,
    [
      { label: "Cancel", onClick: closeModal },
      {
        label: "Add",
        primary: true,
        onClick: async () => {
          try {
            const subject = lockedSubject || $("#c-subject").value.trim();
            const componentName = $("#c-name").value.trim();
            const score = parseFloat($("#c-score").value);
            const weight = parseFloat($("#c-weight").value);
            if (!subject || !componentName) throw new Error("Subject and name required");
            if (!(score >= 0 && score <= 100)) throw new Error("Score must be 0–100");
            if (!(weight > 0 && weight <= 100)) throw new Error("Weight must be 0–100");
            await post("/grades/component/add", {
              studentId,
              subject,
              componentName,
              score,
              weight,
              comment: $("#c-comment").value.trim(),
            });
            invalidate();
            closeModal();
            showAlert("Component added");
            openStudent(studentId);
          } catch (err) {
            showAlert(err.message, true);
          }
        },
      },
    ],
  );
}

function editComponent(studentId, comp) {
  if (!comp) return;
  openModal(
    `<h2>Edit component</h2>
    <p class="subtitle">${esc(comp.subject)} / ${esc(comp.componentName)}</p>
    <div class="grid grid-2" style="gap:12px">
      <div class="field"><label>Score</label>
        <input class="input" type="number" min="0" max="100" step="0.1" id="e-score" value="${comp.score}"></div>
      <div class="field"><label>Weight</label>
        <input class="input" type="number" min="0.1" step="0.1" id="e-weight" value="${comp.weight}"></div>
    </div>
    <div class="field"><label>Comment</label>
      <input class="input" id="e-comment" maxlength="200" value="${esc(comp.comment || "")}"></div>`,
    [
      { label: "Cancel", onClick: closeModal },
      {
        label: "Save",
        primary: true,
        onClick: async () => {
          try {
            const score = parseFloat($("#e-score").value);
            const weight = parseFloat($("#e-weight").value);
            if (!(score >= 0 && score <= 100)) throw new Error("Score must be 0–100");
            if (!(weight > 0 && weight <= 100)) throw new Error("Weight must be 0–100");
            await post("/grades/component/update", {
              studentId,
              subject: comp.subject,
              componentName: comp.componentName,
              score,
              weight,
              comment: $("#e-comment").value.trim(),
            });
            closeModal();
            showAlert("Updated");
            openStudent(studentId);
          } catch (err) {
            showAlert(err.message, true);
          }
        },
      },
    ],
  );
}

/* ---- Leaderboard / Honor roll / Failing / Stats ---- */
views.leaderboard = async function (el) {
  const rows = await get("/leaderboard");
  el.innerHTML = `<div class="card">
    <h3>🏆 Leaderboard</h3>
    <p class="subtitle">${currentUser.role === "TEACHER" ? "Your subject only · " : ""}By average</p>
    <div style="margin-top:12px">${leaderTable(rows)}</div></div>`;
};

views["honor-roll"] = async function (el) {
  const rows = await get("/honor-roll");
  el.innerHTML = `<div class="card">
    <h3>⭐ Honor roll</h3><p class="subtitle">Average ≥ ${HONOR_ROLL_MIN} (Dean's List ≥ 90)</p>
    <div style="margin-top:12px">${leaderTable(rows)}</div></div>`;
};

views.failing = async function (el) {
  const rows = await get("/failing");
  el.innerHTML = `<div class="card">
    <h3>⚠ Failing students</h3><p class="subtitle">At least one failing subject</p>
    <div style="margin-top:12px">${leaderTable(rows)}</div></div>`;
};

views.stats = async function (el) {
  const stats = await get("/stats");
  el.innerHTML = `<div class="card">
    <h3>📊 Class statistics</h3><p class="subtitle">Per subject · median · std-dev · pass rate</p>
    <div style="margin-top:12px">${statsTable(stats)}</div></div>`;
};

/* ---- Audit log (admin) ---- */
views.audit = async function (el) {
  const lines = await get("/audit?limit=100");
  el.innerHTML = `<div class="card">
    <h3>📋 Audit log</h3><p class="subtitle">Most recent ${lines.length} entries (newest first)</p>
    <div style="margin-top:12px">${
      lines.length ? `<pre class="transcript">${esc(lines.join("\n"))}</pre>` : '<div class="empty">No entries.</div>'
    }</div></div>`;
};

/* ---- Student: my grades ---- */
views["my-grades"] = async function (el) {
  const me = currentUser;
  const [grades, comps, transcript] = await Promise.all([
    get("/grades/student/" + enc(me.id)),
    get("/grades/student/" + enc(me.id) + "/components"),
    get("/transcript/" + enc(me.id)),
  ]);
  el.innerHTML = `
    <div class="grid grid-2">
      <div class="card"><h3>📝 Subject grades</h3><p class="subtitle">Weighted averages</p>
        <div style="margin-top:12px">${subjectGradeTable(grades)}</div></div>
      <div class="card"><h3>📄 Transcript</h3><p class="subtitle">Detailed report</p>
        <pre class="transcript" style="margin-top:12px">${esc(transcript)}</pre></div>
    </div>
    <div class="card" style="margin-top:16px">
      <h3>🔍 Components</h3><p class="subtitle">All recorded scores</p>
      <div style="margin-top:12px">${componentTable(comps, false)}</div></div>`;
};

/* ---- Student: top 5 ---- */
views.top = async function (el) {
  const rows = await get("/top/5");
  el.innerHTML = `<div class="card">
    <h3>🏆 Class top 5</h3><p class="subtitle">Highest averages</p>
    <div style="margin-top:12px">${leaderTable(rows)}</div></div>`;
};

/* ---- Settings ---- */
views.settings = async function (el) {
  el.innerHTML = `
    <div class="card" style="max-width:520px">
      <h3>⚙ Account</h3>
      <p class="subtitle">Signed in as ${esc(currentUser.id)} · change your password</p>
      <div style="margin-top:16px">
        <div class="field"><label>Current password</label>
          <input class="input" type="password" id="pw-old" autocomplete="current-password"></div>
        <div class="field"><label>New password (8+ chars, letter + digit)</label>
          <input class="input" type="password" id="pw-new" autocomplete="new-password"></div>
        <div class="field"><label>Confirm new password</label>
          <input class="input" type="password" id="pw-confirm" autocomplete="new-password"></div>
        <button class="btn btn-primary btn-block" id="pw-save">Update password</button>
      </div>
    </div>
    ${
      currentUser.role === "ADMIN"
        ? `<div class="card" style="max-width:520px;margin-top:16px">
        <h3>💾 Backup</h3><p class="subtitle">Download a SQL backup (contains hashed passwords — keep it private)</p>
        <button class="btn btn-secondary" id="backup-btn" style="margin-top:12px">⬇ Download SQL backup</button></div>`
        : ""
    }`;
  el.querySelector("#pw-save").onclick = async () => {
    try {
      const nw = $("#pw-new").value;
      if (nw !== $("#pw-confirm").value) throw new Error("Passwords do not match");
      const bad = pwProblem(nw);
      if (bad) throw new Error(bad);
      await post("/users/change-password", { oldPassword: $("#pw-old").value, newPassword: nw });
      showAlert("Password updated");
      $("#pw-old").value = $("#pw-new").value = $("#pw-confirm").value = "";
    } catch (err) {
      showAlert(err.message, true);
    }
  };
  const bk = el.querySelector("#backup-btn");
  if (bk) bk.onclick = () => downloadFile("/export/backup", "backup.sql").catch((err) => showAlert(err.message, true));
};

/* ---------------- start-up: check server + restore session ---------------- */
async function startup() {
  const box = $("#login-status");
  const say = (msg, ok) => {
    box.style.color = ok ? "#6ee7b7" : "#fca5a5";
    box.textContent = msg;
  };
  if (location.protocol === "file:" && !API_BASE) {
    say("⚠ Open this app at http://localhost:8080 (start ApiServer first), not from the file.", false);
    return;
  }
  try {
    if (token) {
      try {
        currentUser = await get("/me");
        initDashboard();
        return;
      } catch (_) {
        token = null;
        currentUser = null;
        sessionStorage.removeItem("sms_token");
      }
    }
    const h = await get("/health");
    if (h.status === "OK") say("✓ Server connected", true);
    else say("⚠ " + (h.message || "Server is starting… refresh in a few seconds."), false);
  } catch (e) {
    say("⚠ " + e.message, false);
  }
}
startup();
window.__smsReady = true;
