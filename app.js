/* ============================================================
   extras.js — loaded AFTER app.js.
   Wraps existing globals so app.js stays untouched.
   Works entirely against your Java REST API.
   ============================================================ */

/* ------------------------------------------------------------------
   0. Session persistence
   ------------------------------------------------------------------ */
const SESSION_KEY = 'sms_session_v1';

/* ------------------------------------------------------------------
   1. KPI cards
   ------------------------------------------------------------------ */
let __userCountsCache = null;
let __userCountsTime  = 0;
const USER_COUNTS_TTL = 30000; // 30s

async function fetchUserCounts() {
    const now = Date.now();
    if (__userCountsCache && now - __userCountsTime < USER_COUNTS_TTL) {
        return __userCountsCache;
    }
    try {
        // Standard REST convention — if your backend doesn't expose
        // GET /users, this returns null and the KPI shows "—".
        const users = await api('/users');
        if (!Array.isArray(users)) throw new Error('non-array');
        const counts = {
            students: users.filter(u => u.role === 'STUDENT').length,
            teachers: users.filter(u => u.role === 'TEACHER').length,
        };
        __userCountsCache = counts;
        __userCountsTime  = now;
        return counts;
    } catch {
        return null;
    }
}

function setKpi(id, value, suffix) {
    const el = document.getElementById(id);
    if (!el) return;
    el.innerHTML = esc(String(value)) + (suffix ? ` <small>${esc(suffix)}</small>` : '');
}

async function loadKPIs() {
    const row = document.getElementById('kpi-row');
    if (!row) return;

    // Students never see KPIs
    if (!currentUser || currentUser.role === 'STUDENT') {
        row.classList.add('hidden');
        return;
    }
    row.classList.remove('hidden');

    // Grade stats
    try {
        const stats = await api('/stats');
        const totalGrades  = stats.reduce((s, x) => s + Number(x.count || 0), 0);
        const totalScore   = stats.reduce((s, x) => s + Number(x.average || 0) * Number(x.count || 0), 0);
        const totalPassed  = stats.reduce((s, x) => s + Number(x.passed  || 0), 0);
        const avg = totalGrades ? totalScore / totalGrades : 0;
        const passRate = totalGrades ? Math.round((totalPassed / totalGrades) * 100) : 0;

        setKpi('kpi-average', avg.toFixed(1), '/100');
        setKpi('kpi-passrate', passRate, '%');
    } catch {
        setKpi('kpi-average', '—');
        setKpi('kpi-passrate', '—');
    }

    // User counts (best-effort)
    const counts = await fetchUserCounts();
    if (counts) {
        setKpi('kpi-students', counts.students);
        setKpi('kpi-teachers', counts.teachers);
    } else {
        setKpi('kpi-students', '—');
        setKpi('kpi-teachers', '—');
    }
}

/* ------------------------------------------------------------------
   2. Wrap app.js globals
   ------------------------------------------------------------------ */
const __origInitDashboard   = window.initDashboard;
const __origLoadStats       = window.loadStats;
const __origHandleCreate    = window.handleCreateUser;
const __origLogout          = window.logout;

window.initDashboard = function () {
    __origInitDashboard();
    // Persist session after login succeeds (initDashboard is only
    // called with a live token + user)
    if (token && currentUser) {
        try {
            localStorage.setItem(SESSION_KEY, JSON.stringify({
                token, user: currentUser,
            }));
        } catch { /* private mode / quota */ }
    }
    loadKPIs();
};

window.loadStats = async function () {
    await __origLoadStats();
    loadKPIs();
};

window.handleCreateUser = async function (e) {
    await __origHandleCreate.call(this, e);
    __userCountsCache = null; // invalidate cache after a new user
    loadKPIs();
};

window.logout = function () {
    try { localStorage.removeItem(SESSION_KEY); } catch { /* ignore */ }
    __userCountsCache = null;
    document.getElementById('kpi-row')?.classList.add('hidden');
    closePasswordModal();
    __origLogout();
};

/* ------------------------------------------------------------------
   3. Restore session on page load
   ------------------------------------------------------------------ */
async function restoreSession() {
    let saved;
    try { saved = JSON.parse(localStorage.getItem(SESSION_KEY) || 'null'); }
    catch { saved = null; }
    if (!saved || !saved.token || !saved.user) return;

    // Set globals (they are `let` in app.js — same global lexical scope,
    // so assigning here updates what app.js reads).
    token = saved.token;
    currentUser = saved.user;

    // Validate the token against a cheap endpoint
    try {
        await api('/stats');
        initDashboard();
    } catch {
        token = null;
        currentUser = null;
        try { localStorage.removeItem(SESSION_KEY); } catch { /* ignore */ }
    }
}

/* ------------------------------------------------------------------
   4. Delete-grade support (extends handleSaveGrade)
   ------------------------------------------------------------------ */
const __origSaveGrade = window.handleSaveGrade;

window.handleSaveGrade = async function (e) {
    const action = document.getElementById('grade-action').value;
    if (action !== 'DELETE') {
        return __origSaveGrade.call(this, e);
    }

    e.preventDefault();
    const studentId = document.getElementById('grade-student-id').value.trim();
    const subject   = document.getElementById('grade-subject').value.trim();

    if (!studentId || !subject) {
        return showAlert('Student ID and Subject are required.', true);
    }
    if (!confirm(`Delete the grade for ${studentId} in ${subject}?\nThis cannot be undone.`)) return;

    try {
        // Requires:  POST /api/grades/delete   body: { studentId, subject }
        // Add this endpoint on the Java side if it does not yet exist.
        await api('/grades/delete', { method: 'POST', body: { studentId, subject } });
        showAlert('Grade deleted.');
        document.getElementById('grade-form').reset();
        if (currentUser.role === 'TEACHER') {
            document.getElementById('grade-subject').value = currentUser.subject || '';
        }
        loadStats(); // triggers wrapped version → KPIs refresh
        if (currentUser.role === 'ADMIN') loadAuditLogs();
    } catch (err) {
        showAlert(err.message, true);
    }
};

/* ------------------------------------------------------------------
   5. Change-password modal
   ------------------------------------------------------------------ */
function openPasswordModal() {
    const modal = document.getElementById('password-modal');
    if (!modal) return;
    document.getElementById('password-form').reset();
    modal.classList.remove('hidden');
    document.body.classList.add('modal-open');
    setTimeout(() => document.getElementById('pwd-current')?.focus(), 60);
}

function closePasswordModal() {
    document.getElementById('password-modal')?.classList.add('hidden');
    document.body.classList.remove('modal-open');
}

async function handleChangePassword(e) {
    e.preventDefault();
    const current = document.getElementById('pwd-current').value;
    const next    = document.getElementById('pwd-new').value;
    const confirm = document.getElementById('pwd-confirm').value;

    if (next !== confirm)  return showAlert('New passwords do not match.', true);
    if (next.length < 4)   return showAlert('Password must be at least 4 characters.', true);
    if (next === current)  return showAlert('New password must differ from current password.', true);

    const submitBtn = e.target.querySelector('button[type="submit"]');
    const orig = submitBtn.textContent;
    submitBtn.disabled = true;
    submitBtn.textContent = 'Updating…';

    try {
        // Requires:  POST /api/users/change-password
        // body: { currentPassword, newPassword }
        await api('/users/change-password', {
            method: 'POST',
            body: { currentPassword: current, newPassword: next },
        });
        closePasswordModal();
        showAlert('Password updated successfully.');
    } catch (err) {
        showAlert(err.message, true);
    } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = orig;
    }
}

/* ------------------------------------------------------------------
   6. Auto-refresh (60s) — data only, never disturbs forms
   ------------------------------------------------------------------ */
setInterval(() => {
    if (!currentUser) return;
    if (currentUser.role === 'ADMIN') {
        loadStats();
        loadAuditLogs();
    } else if (currentUser.role === 'TEACHER') {
        loadStats();
    }
}, 60000);

/* ------------------------------------------------------------------
   7. Boot
   ------------------------------------------------------------------ */
document.addEventListener('DOMContentLoaded', () => {
    // Toggle score input when DELETE is selected
    const actionSel  = document.getElementById('grade-action');
    const scoreInput = document.getElementById('grade-score');
    if (actionSel && scoreInput) {
        actionSel.addEventListener('change', () => {
            const isDelete = actionSel.value === 'DELETE';
            scoreInput.required = !isDelete;
            scoreInput.disabled = isDelete;
            if (isDelete) scoreInput.value = '';
            scoreInput.placeholder = isDelete ? 'Not required for delete' : '';
        });
    }

    // Escape closes modal
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closePasswordModal();
    });

    // Restore a saved session (if any) after app.js is ready
    restoreSession();
});
