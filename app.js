const SUPABASE_URL = 'https://frotwvjkuriejaluvjvp.supabase.co';
const SUPABASE_KEY = 'sb_publishable_PBkf1n7QEz-3ANuDmi5u3A_1xDjrSDJ';
const supabaseClient = supabase.createClient(SUPABASE_URL, SUPABASE_KEY);

let currentUser = null;
let alertTimer = null;

function esc(v) {
    return String(v ?? '').replace(/[&<>"']/g, c =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function showAlert(message, isError = false) {
    const box = document.getElementById('alert-box');
    if(!box) return;
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

async function handleLogin(event) {
    event.preventDefault();
    const id = document.getElementById('login-id').value;
    const password = document.getElementById('login-password').value;
    
    const { data: user, error } = await supabaseClient
        .from('users')
        .select('*')
        .eq('id', id)
        .eq('password', password)
        .single();

    if (error || !user) {
        showAlert("Invalid ID or Password!", true);
        return;
    }
    
    currentUser = user;
    
    await supabaseClient.from('audit_log').insert([{ 
        action: 'LOGIN', 
        details: `User ${currentUser.name} (${currentUser.id}) logged in` 
    }]);

    initDashboard();
}

function logout() {
    currentUser = null;
    ['#grades-table', '#stats-table', '#audit-table'].forEach(t => {
        const el = document.querySelector(t + ' tbody');
        if(el) el.innerHTML = '';
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

async function handleCreateUser(e) {
    e.preventDefault();
    const role = document.getElementById('new-user-role').value;
    const name = document.getElementById('new-user-name').value;
    const password = document.getElementById('new-user-password').value;
    
    let subject = null;
    if (role === 'TEACHER') {
        subject = document.getElementById('new-user-subject').value;
    }

    const prefix = role === 'ADMIN' ? 'A' : role === 'TEACHER' ? 'T' : 'S';
    
    try {
        const { data: existingUsers, error: fetchError } = await supabaseClient
            .from('users')
            .select('id')
            .like('id', prefix + '%');

        if (fetchError) throw fetchError;

        let maxNumber = 0;
        if (existingUsers && existingUsers.length > 0) {
            existingUsers.forEach(u => {
                const num = parseInt(u.id.substring(1), 10);
                if (!isNaN(num) && num > maxNumber) {
                    maxNumber = num;
                }
            });
        }

        const newId = prefix + String(maxNumber + 1).padStart(3, '0');

        const { error } = await supabaseClient
            .from('users')
            .insert([{ id: newId, name, role, subject, password }]);

        if (error) throw error;

        await supabaseClient.from('audit_log').insert([{ 
            action: 'CREATE_USER', 
            details: `Created ${role} ${name} with ID ${newId}` 
        }]);

        showAlert(`${role} created successfully! Their login ID is ${newId}`);
        document.getElementById('create-user-form').reset();
        toggleSubjectInput();
        
        if(typeof loadAuditLogs === 'function') loadAuditLogs();

    } catch (error) {
        showAlert("Error creating user: " + error.message, true);
    }
}

async function handleSaveGrade(e) {
    e.preventDefault();
    const studentId = document.getElementById('grade-student-id').value;
    const subject = document.getElementById('grade-subject').value;
    const score = parseFloat(document.getElementById('grade-score').value);
    
    const { error } = await supabaseClient
        .from('grades')
        .upsert({ student_id: studentId, subject: subject, score: score }, { onConflict: 'student_id,subject' });

    if (error) {
        showAlert("Error saving grade: " + error.message, true);
    } else {
        showAlert('Grade saved successfully');
        await supabaseClient.from('audit_log').insert([{ 
            action: 'SAVE_GRADE', 
            details: `Saved grade for ${studentId} in ${subject}: ${score}` 
        }]);
        
        loadStats();
        if (currentUser.role === 'ADMIN') loadAuditLogs();
    }
}

async function loadStudentGrades(targetId) {
    const studentId = (typeof targetId === 'string' && targetId)
        ? targetId
        : document.getElementById('search-student-id').value.trim();
    if (!studentId) return;

    const { data: grades, error } = await supabaseClient
        .from('grades')
        .select('*')
        .eq('student_id', studentId);

    if (error) {
        showAlert("Error fetching grades", true);
        return;
    }

    const tbody = document.querySelector('#grades-table tbody');
    if (grades.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4">No grades to show.</td></tr>';
        return;
    }

    tbody.innerHTML = grades.map(g => {
        const isPass = g.score >= 50;
        return `<tr>
            <td>${esc(g.subject)}</td>
            <td>${esc(g.score)}</td>
            <td><strong>${calculateGradeLetter(g.score)}</strong></td>
            <td><span class="badge ${isPass ? 'badge-pass' : 'badge-fail'}">${isPass ? 'Pass' : 'Fail'}</span></td>
        </tr>`;
    }).join('');
}

async function loadStats() {
    const { data: grades, error } = await supabaseClient.from('grades').select('*');
    if (error) {
        showAlert("Error loading stats", true);
        return;
    }

    const statsMap = {};
    grades.forEach(g => {
        if (!statsMap[g.subject]) {
            statsMap[g.subject] = { count: 0, totalScore: 0, passed: 0 };
        }
        statsMap[g.subject].count++;
        statsMap[g.subject].totalScore += g.score;
        if (g.score >= 50) statsMap[g.subject].passed++;
    });

    const statsArray = Object.keys(statsMap).map(subject => ({
        subject: subject,
        count: statsMap[subject].count,
        average: statsMap[subject].totalScore / statsMap[subject].count,
        passed: statsMap[subject].passed
    }));

    document.querySelector('#stats-table tbody').innerHTML = statsArray.map(s => `<tr>
        <td>${esc(s.subject)}</td>
        <td>${esc(s.count)}</td>
        <td>${Number(s.average).toFixed(2)}</td>
        <td>${esc(s.passed)}</td>
    </tr>`).join('');
}

async function loadAuditLogs() {
    const { data: logs, error } = await supabaseClient
        .from('audit_log')
        .select('*')
        .order('id', { ascending: false })
        .limit(20);

    if (error) {
        console.error("Error loading logs:", error.message);
        return;
    }

    document.querySelector('#audit-table tbody').innerHTML = logs.map(log => `<tr>
        <td>${esc(log.created_at || 'Just now')}</td>
        <td>System</td>
        <td>${esc(log.action)}</td>
        <td>${esc(log.details)}</td>
    </tr>`).join('');
}

function exportCSV() {
    showAlert("Export CSV is not available without a backend server. Using Supabase export instead.");
}
function backupDB() {
    showAlert("Backup is handled by Supabase automatically!");
}