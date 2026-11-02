/* extras.js — v3 (bulletproof)
 * Wraps only loadStats / handleSaveGrade / handleCreateUser.
 * NEVER wraps initDashboard / handleLogin / logout.
 * Login can't be blocked by this file.
 */

/* ---------------- 1. KPI loader ---------------- */
async function loadKPIs() {
    var row = document.getElementById('kpi-row');
    if (!row) return;

    if (!window.currentUser || window.currentUser.role === 'STUDENT') {
        row.classList.add('hidden');
        return;
    }
    row.classList.remove('hidden');

    function set(id, v, suffix) {
        var el = document.getElementById(id);
        if (!el) return;
        el.innerHTML = String(v) + (suffix ? ' <small>' + suffix + '</small>' : '');
    }

    try {
        var stats = await api('/stats');
        var totalGrades = stats.reduce(function (s, x) { return s + Number(x.count || 0); }, 0);
        var totalScore  = stats.reduce(function (s, x) { return s + Number(x.average || 0) * Number(x.count || 0); }, 0);
        var totalPassed = stats.reduce(function (s, x) { return s + Number(x.passed  || 0); }, 0);
        var avg = totalGrades ? totalScore / totalGrades : 0;
        var passRate = totalGrades ? Math.round((totalPassed / totalGrades) * 100) : 0;
        set('kpi-average',  avg.toFixed(1), '/100');
        set('kpi-passrate', passRate,       '%');
    } catch (e) {
        set('kpi-average', '—');
        set('kpi-passrate', '—');
    }

    try {
        var users = await api('/users');
        if (Array.isArray(users)) {
            set('kpi-students', users.filter(function (u) { return u.role === 'STUDENT'; }).length);
            set('kpi-teachers', users.filter(function (u) { return u.role === 'TEACHER'; }).length);
        } else {
            set('kpi-students', '—');
            set('kpi-teachers', '—');
        }
    } catch (e) {
        set('kpi-students', '—');
        set('kpi-teachers', '—');
    }
}

/* ---------------- 2. Wrap loadStats only ---------------- */
if (typeof window.loadStats === 'function') {
    var __origLoadStats = window.loadStats;
    window.loadStats = async function () {
        try { await __origLoadStats(); } catch (e) { console.error('loadStats failed', e); }
        loadKPIs();
    };
}

/* ---------------- 3. Add DELETE to handleSaveGrade ---------------- */
if (typeof window.handleSaveGrade === 'function') {
    var __origSaveGrade = window.handleSaveGrade;
    window.handleSaveGrade = async function (e) {
        var sel = document.getElementById('grade-action');
        if (!sel || sel.value !== 'DELETE') {
            return __origSaveGrade.call(this, e);
        }
        e.preventDefault();
        var sid = document.getElementById('grade-student-id').value.trim();
        var sub = document.getElementById('grade-subject').value.trim();
        if (!sid || !sub) { showAlert('Student ID and Subject are required.', true); return; }
        if (!confirm('Delete the grade for ' + sid + ' in ' + sub + '?\nThis cannot be undone.')) return;
        try {
            await api('/grades/delete', { method: 'POST', body: { studentId: sid, subject: sub } });
            showAlert('Grade deleted.');
            document.getElementById('grade-form').reset();
            if (window.currentUser && window.currentUser.role === 'TEACHER') {
                document.getElementById('grade-subject').value = window.currentUser.subject || '';
            }
            loadStats();
            if (window.currentUser && window.currentUser.role === 'ADMIN') loadAuditLogs();
        } catch (err) {
            showAlert(err.message, true);
        }
    };
}

/* ---------------- 4. Refresh KPIs after user creation ---------------- */
if (typeof window.handleCreateUser === 'function') {
    var __origHandleCreate = window.handleCreateUser;
    window.handleCreateUser = async function (e) {
        await __origHandleCreate.call(this, e);
        loadKPIs();
    };
}

/* ---------------- 5. Change-password modal ---------------- */
function openPasswordModal() {
    var modal = document.getElementById('password-modal');
    if (!modal) return;
    document.getElementById('password-form').reset();
    modal.classList.remove('hidden');
    document.body.classList.add('modal-open');
    setTimeout(function () { document.getElementById('pwd-current').focus(); }, 60);
}

function closePasswordModal() {
    var modal = document.getElementById('password-modal');
    if (modal) modal.classList.add('hidden');
    document.body.classList.remove('modal-open');
}

async function handleChangePassword(e) {
    e.preventDefault();
    var current = document.getElementById('pwd-current').value;
    var next    = document.getElementById('pwd-new').value;
    var confirm = document.getElementById('pwd-confirm').value;

    if (next !== confirm)  return showAlert('New passwords do not match.', true);
    if (next.length < 8)   return showAlert('Password must be at least 8 characters with a letter and a digit.', true);
    if (next === current)  return showAlert('New password must differ from current password.', true);

    var btn = e.target.querySelector('button[type="submit"]');
    var orig = btn.textContent;
    btn.disabled = true;
    btn.textContent = 'Updating…';
    try {
        await api('/users/change-password', {
            method: 'POST',
            body: { currentPassword: current, newPassword: next },
        });
        closePasswordModal();
        showAlert('Password updated successfully.');
    } catch (err) {
        showAlert(err.message, true);
    } finally {
        btn.disabled = false;
        btn.textContent = orig;
    }
}

/* ---------------- 6. DOM boot hooks ---------------- */
document.addEventListener('DOMContentLoaded', function () {
    var sel = document.getElementById('grade-action');
    var score = document.getElementById('grade-score');
    if (sel && score) {
        sel.addEventListener('change', function () {
            var del = sel.value === 'DELETE';
            score.required = !del;
            score.disabled = del;
            if (del) score.value = '';
            score.placeholder = del ? 'Not required for delete' : '';
        });
    }
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') closePasswordModal();
    });
});

console.log('[extras.js] v3 loaded');
