/* ============================================================
   extras.js — loaded AFTER app.js.
   Wraps only `loadStats` (called by initDashboard for ADMIN/TEACHER),
   `handleSaveGrade` (to add DELETE), `handleCreateUser` (to refresh KPIs).
   Never wraps initDashboard / handleLogin / logout → login can't break.
   ============================================================ */

/* ---------------------------------------------------------------
   1. KPI loader
   --------------------------------------------------------------- */
async function loadKPIs() {
    const row = document.getElementById('kpi-row');
    if (!row) return;

    // Students never see KPIs
    if (!currentUser || currentUser.role === 'STUDENT') {
        row.classList.add('hidden');
        return;
    }
    row.classList.remove('hidden');

    // Grade aggregates — reuse the /stats endpoint
    try {
        const stats = await api('/stats');
        const totalGrades = stats.reduce((s, x) => s + Number(x.count || 0), 0);
        const totalScore  = stats.reduce((s, x) => s + Number(x.average || 0) * Number(x.count || 0), 0);
        const totalPassed = stats.reduce((s, x) => s + Number(x.passed  || 0), 0);
        const avg         = totalGrades ? totalScore / totalGrades : 0;
        const passRate    = totalGrades ? Math.round((totalPassed / totalGrades) * 100) : 0;

        setKpi('kpi-average',  avg.toFixed(1), '/100');
        setKpi('kpi-passrate', passRate,       '%');
    } catch {
        setKpi('kpi-average',  '—');
        setKpi('kpi-passrate', '—');
    }

    // User counts — best-effort (requires GET /api/users)
    try {
        const users = await api('/users');
        if (Array.isArray(users)) {
            setKpi('kpi-students', users.filter(u => u.role === 'STUDENT').length);
            setKpi('kpi-teachers', users.filter(u => u.role === 'TEACHER').length);
        } else {
            setKpi('kpi-students', '—');
            setKpi('kpi-teachers', '—');
        }
    } catch {
        setKpi('kpi-students', '—');
        setKpi('kpi-teachers', '—');
    }
}

function setKpi(id, value, suffix) {
    const el = document.getElementById(id);
    if (!el) return;
    el.innerHTML = esc(String(value)) + (suffix ? ` <small>${esc(suffix)}</small>` : '');
}

/* ---------------------------------------------------------------
   2. Wrap loadStats → KPI refresh happens after stats are shown
   --------------------------------------------------------------- */
if (typeof window.loadStats === 'function') {
    const __origLoadStats = window.loadStats;
    window.loadStats = async function () {
        await __origLoadStats();
        loadKPIs();
    };
}

/* ---------------------------------------------------------------
   3. Wrap handleSaveGrade → add DELETE support
   --------------------------------------------------------------- */
if (typeof window.handleSaveGrade === 'function') {
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
            showAlert('Student ID and Subject are required.', true);
            return;
        }
        if (!confirm(`Delete the grade for ${studentId} in ${subject}?\nThis cannot be undone.`)) {
            return;
        }

        try {
            // Requires backend:  POST /api/grades/delete
            // body: { studentId, subject }
            await api('/grades/delete', {
                method: 'POST',
                body: { studentId, subject },
            });
            showAlert('Grade deleted.');
            document.getElementById('grade-form').reset();
            if (currentUser.role === 'TEACHER') {
                document.getElementById('grade-subject').value = currentUser.subject || '';
            }
            loadStats();                                // triggers KPI refresh via wrapper
            if (currentUser.role === 'ADMIN') loadAuditLogs();
        } catch (err) {
            showAlert(err.message, true);
        }
    };
}

/* ---------------------------------------------------------------
   4. Wrap handleCreateUser → refresh KPI counts
   --------------------------------------------------------------- */
if (typeof window.handleCreateUser === 'function') {
    const __origHandleCreate = window.handleCreateUser;
    window.handleCreateUser = async function (e) {
        await __origHandleCreate.call(this, e);
        loadKPIs();
    };
}

/* ---------------------------------------------------------------
   5. Change-password modal
   --------------------------------------------------------------- */
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

    if (next !== confirm) return showAlert('New passwords do not match.', true);
    if (next.length < 4)  return showAlert('Password must be at least 4 characters.', true);
    if (next === current) return showAlert('New password must differ from current password.', true);

    const submitBtn = e.target.querySelector('button[type="submit"]');
    const orig = submitBtn.textContent;
    submitBtn.disabled = true;
    submitBtn.textContent = 'Updating…';

    try {
        // Requires backend:  POST /api/users/change-password
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

/* ---------------------------------------------------------------
   6. Auto-refresh every 60s (never disturbs forms)
   --------------------------------------------------------------- */
setInterval(() => {
    if (!currentUser) return;
    if (currentUser.role === 'ADMIN') {
        loadStats();
        loadAuditLogs();
    } else if (currentUser.role === 'TEACHER') {
        loadStats();
    }
}, 60000);

/* ---------------------------------------------------------------
   7. Boot hooks
   --------------------------------------------------------------- */
document.addEventListener('DOMContentLoaded', () => {
    // Toggle score input when DELETE is chosen
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
});
