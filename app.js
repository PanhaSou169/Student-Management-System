// Java-served pages provide a same-origin API URL. For a separate dev server,
// use ?apiPort=9090 or ?apiUrl=http://localhost:9090/api to select the API.
const searchParams = new URLSearchParams(location.search);
const apiPort = searchParams.get('apiPort') || '8080';
const apiOrigin = location.protocol === 'https:' ? 'https:' : 'http:';
const API_BASE_URL = window.SMS_API_BASE_URL || searchParams.get('apiUrl')
    || (location.port === apiPort ? '/api'
        : `${apiOrigin}//${location.hostname || 'localhost'}:${apiPort}/api`);

let currentUser = null;
let token = null;
let alertTimer = null;

// --- Utility Functions ---
function esc(v) {
    return String(v ?? '').replace(/[&<>"']/g, c =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function showAlert(message, isError = false) {
    const box = document.getElementById('alert-box');
    box.textContent = message;
    box.className = `alert ${isError ? 'alert-error' : 'alert-success'}`;
    box.style.display = 'block';
    clearTimeout(alertTimer);
    alertTimer = setTimeout(() => { box.style.display = 'none'; }, 5000);
}

function calculateGradeLetter(score) {
    if (score >= 90) return 'A';
    if (score >= 80) return 'B';
    if (score >= 70) return 'C';
    if (score >= 60) return 'D';
    if (score >= 50) return 'E';
    return 'F';
}

function toggleSubjectInput() {
    const role = document.getElementById('new-user-role').value;
    document.getElementById('subject-field').classList.toggle('hidden', role !== 'TEACHER');
}

// One place for every server call: adds the token, turns errors into readable messages.
async function api(path, { method = 'GET', body, raw = false } = {}) {
    const headers = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (token) headers['Authorization'] = 'Bearer ' + token;

    let res;
    try {
        res = await fetch(API_BASE_URL + path, {
            method,
            headers,
            body: body !== undefined ? JSON.stringify(body) : undefined
        });
    } catch (e) {
        throw new Error('Cannot reach the server. Is the Java server running on port 8080?');
    }

    if (res.status === 401 && token) {
        logout();
        throw new Error('Session expired. Please log in again.');
    }
    if (!res.ok) {
        let msg = `Request failed (${res.status})`;
        try { msg = (await res.json()).message || msg; } catch (e) { /* not JSON */ }
        throw new Error(msg);
    }
    return raw ? res : res.json();
}

// --- Authentication & Session Routing ---
async function handleLogin(event) {
    event.preventDefault();
    const id = document.getElementById('login-id').value;
    const password = document.getElementById('login-password').value;
    try {
        const data = await api('/login', { method: 'POST', body: { id, password } });
        token = data.token;
        currentUser = data.user;
        initDashboard();
    } catch (err) {
        showAlert(err.message, true);
    }
}

function logout() {
    if (token) {
        fetch(API_BASE_URL + '/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + token } }).catch(() => {});
    }
    token = null;
    currentUser = null;
    ['#grades-table', '#stats-table', '#audit-table'].forEach(t => {
        document.querySelector(t + ' tbody').innerHTML = '';
    });
    document.getElementById('view-dashboard').classList.add('hidden');
    document.getElementById('user-nav').classList.add('hidden');
    document.getElementById('view-login').classList.remove('hidden');
    document.getElementById('login-form').reset();
}

function initDashboard() {
    document.getElementById('view-login').classList.add('hidden');
    document.getElementById('view-dashboard').classList.remove('hidden');
    document.getElementById('nav-user-details').textContent = `${currentUser.name} (${currentUser.role})`;
    document.getElementById('user-nav').classList.remove('hidden');

    ['admin-panel', 'teacher-panel', 'student-panel'].forEach(id =>
        document.getElementById(id).classList.add('hidden'));
    document.getElementById('student-search-bar').classList.add('hidden');

    if (currentUser.role === 'ADMIN') {
        ['admin-panel', 'teacher-panel', 'student-panel', 'student-search-bar'].forEach(id =>
            document.getElementById(id).classList.remove('hidden'));
        loadAuditLogs();
        loadStats();
    } else if (currentUser.role === 'TEACHER') {
        ['teacher-panel', 'student-panel', 'student-search-bar'].forEach(id =>
            document.getElementById(id).classList.remove('hidden'));
        document.getElementById('grade-subject').value = currentUser.subject || '';
        loadStats();
    } else {
        document.getElementById('student-panel').classList.remove('hidden');
        loadStudentGrades(currentUser.id);
    }
}

// --- Data Fetching & Operations ---
async function handleCreateUser(e) {
    e.preventDefault();
    const role = document.getElementById('new-user-role').value;
    const name = document.getElementById('new-user-name').value;
    const subject = document.getElementById('new-user-subject').value;
    const password = document.getElementById('new-user-password').value;
    try {
        const data = await api('/users', { method: 'POST', body: { role, name, subject, password } });
        showAlert(`${role} created. Their login ID is ${data.id}`);
        document.getElementById('create-user-form').reset();
        toggleSubjectInput();
        loadAuditLogs();
    } catch (err) {
        showAlert(err.message, true);
    }
}

async function handleSaveGrade(e) {
    e.preventDefault();
    const studentId = document.getElementById('grade-student-id').value;
    const subject = document.getElementById('grade-subject').value;
    const score = parseFloat(document.getElementById('grade-score').value);
    const action = document.getElementById('grade-action').value;
    try {
        await api(action === 'ADD' ? '/grades/add' : '/grades/update',
            { method: 'POST', body: { studentId, subject, score } });
        showAlert(action === 'ADD' ? 'Grade added successfully' : 'Grade updated successfully');
        loadStats();
        if (currentUser.role === 'ADMIN') loadAuditLogs();
    } catch (err) {
        showAlert(err.message, true);
    }
}

async function loadStudentGrades(targetId) {
    const studentId = (typeof targetId === 'string' && targetId)
        ? targetId
        : document.getElementById('search-student-id').value.trim();
    if (!studentId) return;
    try {
        const grades = await api(`/grades/student/${encodeURIComponent(studentId)}`);
        const entries = Object.entries(grades);
        const tbody = document.querySelector('#grades-table tbody');
        if (entries.length === 0) {
            tbody.innerHTML = '<tr><td colspan="4">No grades to show.</td></tr>';
            return;
        }
        tbody.innerHTML = entries.map(([subject, score]) => {
            const isPass = score >= 50;
            return `<tr>
                <td>${esc(subject)}</td>
                <td>${esc(score)}</td>
                <td><strong>${calculateGradeLetter(score)}</strong></td>
                <td><span class="badge ${isPass ? 'badge-pass' : 'badge-fail'}">${isPass ? 'Pass' : 'Fail'}</span></td>
            </tr>`;
        }).join('');
    } catch (err) {
        showAlert(err.message, true);
    }
}

async function loadStats() {
    try {
        const stats = await api('/stats');
        document.querySelector('#stats-table tbody').innerHTML = stats.map(s => `<tr>
            <td>${esc(s.subject)}</td>
            <td>${esc(s.count)}</td>
            <td>${Number(s.average).toFixed(2)}</td>
            <td>${esc(s.passed)}</td>
        </tr>`).join('');
    } catch (err) {
        showAlert(err.message, true);
    }
}

async function loadAuditLogs() {
    try {
        const logs = await api('/audit?limit=20');
        document.querySelector('#audit-table tbody').innerHTML = logs.map(log => {
            const parts = log.split(/\s+/);
            return `<tr>
                <td>${esc(parts[0] + ' ' + parts[1])}</td>
                <td>${esc(parts[2] || '')}</td>
                <td>${esc(parts[3] || '')}</td>
                <td>${esc(parts.slice(4).join(' '))}</td>
            </tr>`;
        }).join('');
    } catch (err) {
        showAlert(err.message, true);
    }
}

// Downloads need the token header, so a plain link/redirect won't work: fetch then save the blob.
async function download(path, fallbackName) {
    try {
        const res = await api(path, { raw: true });
        const blob = await res.blob();
        const match = /filename="?([^";]+)"?/.exec(res.headers.get('Content-Disposition') || '');
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = match ? match[1] : fallbackName;
        document.body.appendChild(a);
        a.click();
        a.remove();
        URL.revokeObjectURL(a.href);
        loadAuditLogs();
    } catch (err) {
        showAlert(err.message, true);
    }
}

function exportCSV() { download('/export/csv', 'export_grades.csv'); }
function backupDB() { download('/export/backup', 'backup.sql'); }