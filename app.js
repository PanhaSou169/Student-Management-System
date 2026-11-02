/* ============================================================
   extras.js — loaded AFTER app.js.
   Wraps existing globals (initDashboard, loadStats, handleSaveGrade,
   handleCreateUser, exportCSV) so app.js stays untouched.
   ============================================================ */

/* ---------------------------------------------------------
   1. LIVE KPI CARDS
   --------------------------------------------------------- */
async function loadKPIs() {
    const row = document.getElementById('kpi-row');
    if (!row) return;

    // Students never see KPIs
    if (!currentUser || currentUser.role === 'STUDENT') {
        row.classList.add('hidden');
        return;
    }
    row.classList.remove('hidden');

    try {
        const [usersRes, gradesRes] = await Promise.all([
            supabaseClient.from('users').select('id, role'),
            supabaseClient.from('grades').select('score'),
        ]);
        if (usersRes.error) throw usersRes.error;
        if (gradesRes.error) throw gradesRes.error;

        const users  = usersRes.data || [];
        const grades = gradesRes.data || [];

        const students = users.filter(u => u.role === 'STUDENT').length;
        const teachers = users.filter(u => u.role === 'TEACHER').length;
        const total    = grades.length;
        const avg      = total
            ? grades.reduce((sum, g) => sum + Number(g.score), 0) / total
            : 0;
        const passed    = grades.filter(g => Number(g.score) >= 50).length;
        const passRate  = total ? Math.round((passed / total) * 100) : 0;

        setKpi('kpi-students', students);
        setKpi('kpi-teachers', teachers);
        setKpi('kpi-average',  avg.toFixed(1), '/100');
        setKpi('kpi-passrate', passRate, '%');
    } catch (err) {
        console.error('KPI load failed:', err);
        ['kpi-students', 'kpi-teachers', 'kpi-average', 'kpi-passrate']
            .forEach(id => setKpi(id, '—'));
    }
}

function setKpi(id, value, suffix) {
    const el = document.getElementById(id);
    if (!el) return;
    el.innerHTML = esc(value) + (suffix ? ` <small>${esc(suffix)}</small>` : '');
}

/* ---- Wrap app.js globals so KPIs stay fresh ---- */
const __origInitDashboard  = window.initDashboard;
const __origLoadStats      = window.loadStats;
const __origHandleCreate   = window.handleCreateUser;
const __origLogout         = window.logout;

window.initDashboard = function () {
    __origInitDashboard();
    loadKPIs();
};

window.loadStats = async function () {
    await __origLoadStats();
    loadKPIs();
};

window.handleCreateUser = async function (e) {
    await __origHandleCreate(e);
    loadKPIs();
};

window.logout = function () {
    closePasswordModal();
    document.getElementById('kpi-row')?.classList.add('hidden');
    __origLogout();
};

/* ---------------------------------------------------------
   2. REAL CSV EXPORT (replaces the stub in app.js)
   --------------------------------------------------------- */
function csvEscape(v) {
    const s = String(v ?? '');
    return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

window.exportCSV = async function () {
    try {
        const { data: grades, error: gErr } = await supabaseClient
            .from('grades').select('*');
        if (gErr) throw gErr;
        if (!grades || grades.length === 0) {
            showAlert('No grade records to export.', true);
            return;
        }

        const ids = [...new Set(grades.map(g => g.student_id))];
        const { data: students, error: sErr } = await supabaseClient
            .from('users').select('id, name').in('id', ids);
        if (sErr) throw sErr;

        const nameById = Object.fromEntries((students || []).map(s => [s.id, s.name]));

        const rows = [['Student ID', 'Student Name', 'Subject', 'Score', 'Grade', 'Status']];
        grades.forEach(g => rows.push([
            g.student_id,
            nameById[g.student_id] || '—',
            g.subject,
            g.score,
            calculateGradeLetter(Number(g.score)),
            Number(g.score) >= 50 ? 'Pass' : 'Fail',
        ]));

        const csv  = rows.map(r => r.map(csvEscape).join(',')).join('\r\n');
        const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' });
        const url  = URL.createObjectURL(blob);

        const a = document.createElement('a');
        a.href = url;
        a.download = `grades-${new Date().toISOString().slice(0, 10)}.csv`;
        document.body.appendChild(a);
        a.click();
        a.remove();
        URL.revokeObjectURL(url);

        await supabaseClient.from('audit_log').insert([{
            action: 'EXPORT_CSV',
            details: `Exported ${grades.length} grade records to CSV`,
        }]);

        showAlert(`Exported ${grades.length} grade records.`);
    } catch (err) {
        showAlert('Export failed: ' + err.message, true);
    }
};

/* ---------------------------------------------------------
   3. CHANGE PASSWORD MODAL
   --------------------------------------------------------- */
function openPasswordModal() {
    const modal = document.getElementById('password-modal');
    if (!modal) return;
    modal.classList.remove('hidden');
    document.getElementById('password-form').reset();
    setTimeout(() => document.getElementById('pwd-current')?.focus(), 60);
}

function closePasswordModal() {
    document.getElementById('password-modal')?.classList.add('hidden');
}

async function handleChangePassword(e) {
    e.preventDefault();
    if (!currentUser) return;

    const current = document.getElementById('pwd-current').value;
    const next    = document.getElementById('pwd-new').value;
    const confirm = document.getElementById('pwd-confirm').value;

    if (next !== confirm)      return showAlert('New passwords do not match.', true);
    if (next.length < 4)       return showAlert('New password must be at least 4 characters.', true);
    if (next === current)      return showAlert('New password must differ from current password.', true);

    // Verify current password (same plain-text scheme app.js uses for login)
    const { data: rows, error: verifyErr } = await supabaseClient
        .from('users')
        .select('id')
        .eq('id', currentUser.id)
        .eq('password', current)
        .limit(1);

    if (verifyErr) return showAlert('Verification failed: ' + verifyErr.message, true);
    if (!rows || rows.length === 0) return showAlert('Current password is incorrect.', true);

    const { error } = await supabaseClient
        .from('users')
        .update({ password: next })
        .eq('id', currentUser.id);

    if (error) return showAlert('Update failed: ' + error.message, true);

    await supabaseClient.from('audit_log').insert([{
        action: 'CHANGE_PASSWORD',
        details: `User ${currentUser.name} (${currentUser.id}) changed password`,
    }]);

    closePasswordModal();
    showAlert('Password updated successfully.');
}

/* Close modal with Escape */
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closePasswordModal();
});

/* ---------------------------------------------------------
   4. DELETE GRADE (extends handleSaveGrade)
   --------------------------------------------------------- */
window.handleSaveGrade = async function (e) {
    e.preventDefault();
    const studentId = document.getElementById('grade-student-id').value.trim();
    const subject   = document.getElementById('grade-subject').value.trim();
    const action    = document.getElementById('grade-action').value;
    const scoreRaw  = document.getElementById('grade-score').value;

    if (action === 'DELETE') {
        if (!confirm(`Delete the grade for ${studentId} in ${subject}? This cannot be undone.`)) return;

        const { error } = await supabaseClient
            .from('grades')
            .delete()
            .eq('student_id', studentId)
            .eq('subject', subject);
        if (error) return showAlert('Delete failed: ' + error.message, true);

        await supabaseClient.from('audit_log').insert([{
            action: 'DELETE_GRADE',
            details: `Deleted grade for ${studentId} in ${subject}`,
        }]);

        showAlert('Grade deleted.');
        document.getElementById('grade-form').reset();
        if (currentUser.role === 'TEACHER') {
            document.getElementById('grade-subject').value = currentUser.subject || '';
        }
        loadStats();            // triggers wrapped version → KPIs refresh
        if (currentUser.role === 'ADMIN') loadAuditLogs();
        return;
    }

    // ADD / UPDATE path (same as original)
    const score = parseFloat(scoreRaw);
    const { error } = await supabaseClient
        .from('grades')
        .upsert({ student_id: studentId, subject, score }, { onConflict: 'student_id,subject' });

    if (error) return showAlert('Error saving grade: ' + error.message, true);

    showAlert('Grade saved successfully.');
    await supabaseClient.from('audit_log').insert([{
        action: 'SAVE_GRADE',
        details: `Saved grade for ${studentId} in ${subject}: ${score}`,
    }]);

    loadStats();
    if (currentUser.role === 'ADMIN') loadAuditLogs();
};

/* ---------------------------------------------------------
   5. Toggle score input when DELETE is selected
   --------------------------------------------------------- */
document.addEventListener('DOMContentLoaded', () => {
    const actionSel = document.getElementById('grade-action');
    const scoreInput = document.getElementById('grade-score');
    if (!actionSel || !scoreInput) return;

    actionSel.addEventListener('change', () => {
        const isDelete = actionSel.value === 'DELETE';
        scoreInput.required = !isDelete;
        scoreInput.disabled = isDelete;
        if (isDelete) scoreInput.value = '';
        scoreInput.placeholder = isDelete ? 'Not required for delete' : '';
    });
});
