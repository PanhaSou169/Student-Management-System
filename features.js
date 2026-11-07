/* ================================================================
   features.js — real-life classroom features (MySQL version)
   Attendance · Announcements · Assignments & Submissions
   Loaded AFTER app.js (uses its helpers: $, esc, get, post, openModal ...)
   The tables are created automatically by ApiServer — nothing to install.
   ================================================================ */
(() => {
  const today = () => {
    const d = new Date();
    d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
    return d.toISOString().slice(0, 10);
  };
  const fmt = (ms) => (ms ? new Date(Number(ms)).toLocaleString() : "—");
  const fmtDay = (s) =>
    s ? new Date(s + "T00:00:00").toLocaleDateString() : "—";
  const wrap = (html) => `<div class="table-wrap">${html}</div>`;
  const clean = (n) =>
    Number.isInteger(Number(n)) ? String(Number(n)) : Number(n).toFixed(1);
  const rate = (p, l, a) => (p + l + a ? ((p + l) / (p + l + a)) * 100 : 100);

  const STATUS = {
    PRESENT: "badge-a",
    LATE: "badge-c",
    ABSENT: "badge-f",
    EXCUSED: "badge-b",
  };
  const badge = (st) =>
    `<span class="badge ${STATUS[st] || "badge-muted"}">${esc(st)}</span>`;

  const nav = [
    { group: "Classroom" },
    { id: "attendance", icon: "🗓", label: "Attendance" },
    { id: "assignments", icon: "📌", label: "Assignments" },
    { id: "announcements", icon: "📢", label: "Announcements" },
  ];
  featureNav.ADMIN.push(...nav);
  featureNav.TEACHER.push(...nav);
  featureNav.STUDENT.push(...nav);

  /* ================================================================
     ATTENDANCE
     ================================================================ */
  views.attendance = async function (el) {
    return currentUser.role === "STUDENT"
      ? studentAttendance(el)
      : staffAttendance(el);
  };

  async function staffAttendance(el) {
    const me = currentUser;
    const subjects =
      me.role === "TEACHER"
        ? [me.subject].filter(Boolean)
        : (await loadSubjects()).map((s) => s.name);
    if (!subjects.length) {
      el.innerHTML =
        '<div class="empty">No subject available yet. Add a subject first.</div>';
      return;
    }
    const students = (await loadUsers()).filter((u) => u.role === "STUDENT");

    el.innerHTML = `
      <div class="toolbar">
        <select class="input" id="att-subject" style="max-width:220px">
          ${subjects.map((s) => `<option>${esc(s)}</option>`).join("")}
        </select>
        <input class="input" type="date" id="att-date" value="${today()}" max="${today()}" style="max-width:180px">
        <div class="spacer"></div>
        <button class="btn btn-secondary" id="att-all">Mark all present</button>
        <button class="btn btn-primary" id="att-save">Save attendance</button>
      </div>
      <div class="card"><h3>Roll call</h3>
        <p class="subtitle" id="att-hint"></p>
        <div id="att-body" style="margin-top:12px"></div></div>
      <div class="card" style="margin-top:16px"><h3>Summary for this subject</h3>
        <p class="subtitle">Attendance rate = (present + late) ÷ (present + late + absent). Excused days don't count.</p>
        <div id="att-summary" style="margin-top:12px"></div></div>`;

    const subjSel = el.querySelector("#att-subject");
    const dateInp = el.querySelector("#att-date");

    async function load() {
      const subject = subjSel.value;
      const date = dateInp.value;
      if (!date) return;
      try {
        const saved = Object.fromEntries(
          (await get("/attendance" + qs({ subject, date }))).map((r) => [
            r.studentId,
            r.status,
          ]),
        );
        const n = Object.keys(saved).length;
        el.querySelector("#att-hint").textContent = n
          ? `${n} of ${students.length} students already marked for this day.`
          : "Not marked yet for this day.";
        el.querySelector("#att-body").innerHTML = students.length
          ? wrap(`<table><thead><tr><th>ID</th><th>Student</th><th>Status</th></tr></thead>
            <tbody>${students
              .map(
                (
                  s,
                ) => `<tr><td><code>${esc(s.id)}</code></td><td>${esc(s.name)}</td>
              <td><select class="input" data-sid="${esc(s.id)}" style="max-width:160px">
                ${Object.keys(STATUS)
                  .map(
                    (st) =>
                      `<option ${st === (saved[s.id] || "PRESENT") ? "selected" : ""}>${st}</option>`,
                  )
                  .join("")}
              </select></td></tr>`,
              )
              .join("")}</tbody></table>`)
          : '<div class="empty">No students yet.</div>';
        loadSummary(subject);
      } catch (err) {
        showAlert(err.message, true);
      }
    }

    async function loadSummary(subject) {
      const box = el.querySelector("#att-summary");
      try {
        const list = await get("/attendance/summary" + qs({ subject }));
        box.innerHTML = list.length
          ? wrap(`<table><thead><tr><th>Student</th><th>Present</th><th>Late</th><th>Absent</th><th>Excused</th><th>Rate</th></tr></thead>
            <tbody>${list
              .map((r) => {
                const pc = rate(r.present, r.late, r.absent);
                return `<tr><td><strong>${esc(r.name)}</strong><br><span class="subtitle">${esc(r.studentId)}</span></td>
                  <td>${r.present}</td><td>${r.late}</td><td>${r.absent}</td><td>${r.excused}</td>
                  <td><span class="badge ${pc >= 80 ? "badge-a" : pc >= 60 ? "badge-c" : "badge-f"}">${pc.toFixed(0)}%</span></td></tr>`;
              })
              .join("")}</tbody></table>`)
          : '<div class="empty">No attendance recorded yet.</div>';
      } catch (err) {
        box.innerHTML = `<div class="empty">⚠ ${esc(err.message)}</div>`;
      }
    }

    subjSel.onchange = load;
    dateInp.onchange = load;
    el.querySelector("#att-all").onclick = () =>
      el
        .querySelectorAll("select[data-sid]")
        .forEach((s) => (s.value = "PRESENT"));
    el.querySelector("#att-save").onclick = async () => {
      const records = [...el.querySelectorAll("select[data-sid]")].map((s) => ({
        studentId: s.dataset.sid,
        status: s.value,
      }));
      if (!records.length) return;
      const btn = el.querySelector("#att-save");
      btn.disabled = true;
      try {
        await post("/attendance/mark", {
          subject: subjSel.value,
          date: dateInp.value,
          records,
        });
        showAlert("Attendance saved");
        load();
      } catch (err) {
        showAlert(err.message, true);
      } finally {
        btn.disabled = false;
      }
    };
    load();
  }

  async function studentAttendance(el) {
    const rows = await get("/attendance/mine");
    const by = {};
    for (const r of rows) {
      const o = (by[r.subject] ||= {
        PRESENT: 0,
        LATE: 0,
        ABSENT: 0,
        EXCUSED: 0,
      });
      o[r.status]++;
    }
    const cnt = (st) => rows.filter((r) => r.status === st).length;
    const overall = rate(cnt("PRESENT"), cnt("LATE"), cnt("ABSENT"));

    el.innerHTML = `
      <div class="grid grid-4">
        ${statCard("Attendance rate", overall.toFixed(0) + "%", "present + late")}
        ${statCard("Days recorded", rows.length, "all subjects")}
        ${statCard("Absent", cnt("ABSENT"), "unexcused")}
        ${statCard("Late", cnt("LATE"), "arrivals")}
      </div>
      <div class="grid grid-2" style="margin-top:16px">
        <div class="card"><h3>By subject</h3><div style="margin-top:12px">${
          Object.keys(by).length
            ? wrap(`<table><thead><tr><th>Subject</th><th>P</th><th>L</th><th>A</th><th>E</th><th>Rate</th></tr></thead>
              <tbody>${Object.entries(by)
                .map(
                  ([
                    s,
                    o,
                  ]) => `<tr><td><strong>${esc(s)}</strong></td><td>${o.PRESENT}</td><td>${o.LATE}</td>
                    <td>${o.ABSENT}</td><td>${o.EXCUSED}</td><td>${rate(o.PRESENT, o.LATE, o.ABSENT).toFixed(0)}%</td></tr>`,
                )
                .join("")}</tbody></table>`)
            : '<div class="empty">No attendance recorded yet.</div>'
        }</div></div>
        <div class="card"><h3>Recent days</h3><div style="margin-top:12px">${
          rows.length
            ? wrap(`<table><thead><tr><th>Date</th><th>Subject</th><th>Status</th></tr></thead>
              <tbody>${rows
                .slice(0, 20)
                .map(
                  (r) =>
                    `<tr><td>${fmtDay(r.date)}</td><td>${esc(r.subject)}</td><td>${badge(r.status)}</td></tr>`,
                )
                .join("")}</tbody></table>`)
            : '<div class="empty">Nothing yet.</div>'
        }</div></div>
      </div>`;
  }

  /* ================================================================
     ANNOUNCEMENTS
     ================================================================ */
  views.announcements = async function (el) {
    const me = currentUser;
    const list = await get("/announcements");
    const canPost = me.role === "ADMIN" || me.role === "TEACHER";

    el.innerHTML = `
      ${
        canPost
          ? `<div class="toolbar"><div class="spacer"></div>
        <button class="btn btn-primary" id="ann-new">+ New announcement</button></div>`
          : ""
      }
      ${
        list.length
          ? list
              .map(
                (a) => `<div class="card post">
          <div style="display:flex;justify-content:space-between;gap:12px;align-items:flex-start">
            <h3>${esc(a.title)}</h3>
            <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap;justify-content:flex-end">
              <span class="badge badge-muted">${esc(a.audience)}</span>
              ${a.subject ? `<span class="badge badge-b">${esc(a.subject)}</span>` : ""}
              ${a.canDelete ? `<button class="btn btn-danger btn-sm" data-del="${esc(a.id)}">Delete</button>` : ""}
            </div>
          </div>
          <p>${esc(a.body)}</p>
          <p class="subtitle" style="margin-top:10px">${esc(a.authorName || a.authorId || "—")} · ${fmt(a.createdAt)}</p>
        </div>`,
              )
              .join("")
          : '<div class="empty">No announcements.</div>'
      }`;

    el.querySelectorAll("[data-del]").forEach(
      (b) =>
        (b.onclick = async () => {
          if (!confirm("Delete this announcement?")) return;
          try {
            await post("/announcements/delete", { id: Number(b.dataset.del) });
            showAlert("Deleted");
            render();
          } catch (err) {
            showAlert(err.message, true);
          }
        }),
    );

    const btn = el.querySelector("#ann-new");
    if (btn)
      btn.onclick = () =>
        openModal(
          `<h2>New announcement</h2>
          <p class="subtitle">${me.role === "TEACHER" ? `Visible to students · tagged ${esc(me.subject || "")}` : "Choose who should see it"}</p>
          <div class="field"><label>Title</label><input class="input" id="an-title" maxlength="120"></div>
          <div class="field"><label>Message</label><textarea class="input" id="an-body" maxlength="2000"></textarea></div>
          ${
            me.role === "ADMIN"
              ? `<div class="field"><label>Audience</label>
                  <select class="input" id="an-aud">
                    <option value="ALL">Everyone</option><option value="STUDENT">Students</option>
                    <option value="TEACHER">Teachers</option></select></div>`
              : ""
          }`,
          [
            { label: "Cancel", onClick: closeModal },
            {
              label: "Post",
              primary: true,
              onClick: async () => {
                try {
                  const title = $("#an-title").value.trim();
                  const body = $("#an-body").value.trim();
                  if (!title || !body)
                    throw new Error("Title and message are required");
                  await post("/announcements", {
                    title,
                    body,
                    audience:
                      me.role === "ADMIN" ? $("#an-aud").value : "STUDENT",
                  });
                  closeModal();
                  showAlert("Posted");
                  render();
                } catch (e) {
                  showAlert(e.message, true);
                }
              },
            },
          ],
        );
  };

  /* ================================================================
     ASSIGNMENTS & SUBMISSIONS
     ================================================================ */
  views.assignments = async function (el) {
    return currentUser.role === "STUDENT"
      ? studentAssignments(el)
      : staffAssignments(el);
  };

  /* ---------- staff (teacher: own subject, admin: all) ---------- */
  async function staffAssignments(el) {
    const list = await get("/assignments");
    el.innerHTML = `
      <div class="toolbar"><div class="spacer"></div>
        <button class="btn btn-primary" id="asg-new">+ New assignment</button></div>
      ${
        list.length
          ? wrap(`<table><thead><tr><th>Title</th><th>Subject</th><th>Due</th><th>Max</th><th>Submitted</th><th></th></tr></thead>
          <tbody>${list
            .map(
              (a) => `<tr><td><strong>${esc(a.title)}</strong>${
                a.description
                  ? `<br><span class="subtitle">${esc(a.description.slice(0, 90))}</span>`
                  : ""
              }</td><td>${esc(a.subject)}</td><td>${fmtDay(a.dueDate)}</td><td>${clean(a.maxScore)}</td>
              <td>${a.submitted} <span class="subtitle">(${a.graded} graded)</span></td>
              <td><div class="row-actions">
                <button class="btn btn-secondary btn-sm" data-sub="${esc(a.id)}">Submissions</button>
                <button class="btn btn-danger btn-sm" data-del="${esc(a.id)}">Delete</button>
              </div></td></tr>`,
            )
            .join("")}</tbody></table>`)
          : '<div class="empty">No assignments yet.</div>'
      }`;

    el.querySelector("#asg-new").onclick = () => newAssignment();
    el.querySelectorAll("[data-sub]").forEach(
      (b) =>
        (b.onclick = () =>
          openSubmissions(list.find((a) => String(a.id) === b.dataset.sub))),
    );
    el.querySelectorAll("[data-del]").forEach(
      (b) =>
        (b.onclick = async () => {
          if (!confirm("Delete this assignment and all its submissions?"))
            return;
          try {
            await post("/assignments/delete", { id: Number(b.dataset.del) });
            showAlert("Deleted");
            render();
          } catch (err) {
            showAlert(err.message, true);
          }
        }),
    );
  }

  async function newAssignment() {
    const me = currentUser;
    const subjects =
      me.role === "TEACHER"
        ? [me.subject].filter(Boolean)
        : (await loadSubjects()).map((s) => s.name);
    if (!subjects.length) return showAlert("No subject available", true);
    openModal(
      `<h2>New assignment</h2><p class="subtitle">Students will see it right away</p>
      <div class="field"><label>Subject</label>
        <select class="input" id="as-subject">${subjects.map((s) => `<option>${esc(s)}</option>`).join("")}</select></div>
      <div class="field"><label>Title</label><input class="input" id="as-title" maxlength="120"></div>
      <div class="field"><label>Instructions (optional)</label><textarea class="input" id="as-desc" maxlength="2000"></textarea></div>
      <div class="grid grid-2" style="gap:12px">
        <div class="field"><label>Due date</label><input class="input" type="date" id="as-due" min="${today()}"></div>
        <div class="field"><label>Max score</label><input class="input" type="number" id="as-max" min="1" step="1" value="100"></div>
      </div>`,
      [
        { label: "Cancel", onClick: closeModal },
        {
          label: "Create",
          primary: true,
          onClick: async () => {
            try {
              const title = $("#as-title").value.trim();
              const dueDate = $("#as-due").value;
              const maxScore = parseFloat($("#as-max").value);
              if (!title) throw new Error("Title is required");
              if (!dueDate) throw new Error("Due date is required");
              if (!(maxScore > 0))
                throw new Error("Max score must be greater than 0");
              await post("/assignments", {
                subject: $("#as-subject").value,
                title,
                description: $("#as-desc").value.trim(),
                dueDate,
                maxScore,
              });
              invalidate();
              closeModal();
              showAlert("Assignment created");
              render();
            } catch (e) {
              showAlert(e.message, true);
            }
          },
        },
      ],
    );
  }

  async function openSubmissions(a) {
    if (!a) return;
    try {
      const rows = await get("/assignments/" + enc(a.id) + "/submissions");
      openModal(
        `<h2>${esc(a.title)}</h2>
        <p class="subtitle">${esc(a.subject)} · due ${fmtDay(a.dueDate)} · max ${clean(a.maxScore)}</p>
        ${wrap(`<table><thead><tr><th>Student</th><th>Status</th><th>Answer</th><th>Score</th><th>Feedback</th></tr></thead>
        <tbody>${rows
          .map((r) => {
            const s = r.submission;
            return `<tr>
              <td><strong>${esc(r.name)}</strong><br><span class="subtitle">${esc(r.studentId)}</span></td>
              <td>${
                s
                  ? `<span class="badge ${s.late ? "badge-c" : "badge-a"}">${s.late ? "LATE" : "ON TIME"}</span><br>
                     <span class="subtitle">${fmt(s.submittedAt)}</span>`
                  : '<span class="badge badge-muted">MISSING</span>'
              }</td>
              <td>${s ? `<details><summary>View</summary><pre>${esc(s.content)}</pre></details>` : "—"}</td>
              <td>${
                s
                  ? `<input class="input" type="number" min="0" max="${a.maxScore}" step="0.1"
                       data-score="${esc(s.id)}" data-student="${esc(r.studentId)}" style="width:90px"
                       value="${s.score ?? ""}">`
                  : "—"
              }</td>
              <td>${s ? `<input class="input" data-fb="${esc(s.id)}" maxlength="500" value="${esc(s.feedback || "")}">` : "—"}</td></tr>`;
          })
          .join("")}</tbody></table>`)}
        <label class="check"><input type="checkbox" id="sync-gb"> Also save scores in the gradebook
          (as a component named "${esc(a.title.slice(0, 40))}", converted to 0–100)</label>`,
        [
          { label: "Close", onClick: closeModal },
          {
            label: "Save grades",
            primary: true,
            onClick: async () => {
              try {
                const sync = $("#sync-gb").checked;
                let saved = 0;
                for (const inp of $$("#modal-content [data-score]")) {
                  const raw = inp.value.trim();
                  if (raw === "") continue;
                  const score = parseFloat(raw);
                  if (!(score >= 0 && score <= Number(a.maxScore)))
                    throw new Error(
                      `Score must be between 0 and ${clean(a.maxScore)}`,
                    );
                  const fb = $(
                    `#modal-content [data-fb="${CSS.escape(inp.dataset.score)}"]`,
                  ).value.trim();
                  await post("/submissions/grade", {
                    submissionId: Number(inp.dataset.score),
                    score,
                    feedback: fb,
                    syncGradebook: sync,
                  });
                  saved++;
                }
                closeModal();
                showAlert(
                  saved ? `${saved} grade(s) saved` : "Nothing to save",
                );
                render();
              } catch (e) {
                showAlert(e.message, true);
              }
            },
          },
        ],
        true,
      );
    } catch (err) {
      showAlert(err.message, true);
    }
  }

  /* ---------- student ---------- */
  async function studentAssignments(el) {
    const list = await get("/assignments");
    const now = new Date();
    const statusOf = (a) => {
      const s = a.submission;
      const overdue = now > new Date(a.dueDate + "T23:59:59");
      if (s && s.score !== null) return ["GRADED", "badge-a"];
      if (s) return ["SUBMITTED", "badge-b"];
      return overdue ? ["MISSING", "badge-f"] : ["TO DO", "badge-c"];
    };

    el.innerHTML = list.length
      ? wrap(`<table><thead><tr><th>Assignment</th><th>Subject</th><th>Due</th><th>Status</th><th>Score</th><th></th></tr></thead>
        <tbody>${list
          .map((a) => {
            const s = a.submission;
            const [label, cls] = statusOf(a);
            return `<tr><td><strong>${esc(a.title)}</strong>${
              a.description
                ? `<br><span class="subtitle">${esc(a.description.slice(0, 100))}</span>`
                : ""
            }${s && s.feedback ? `<br><span class="subtitle">💬 ${esc(s.feedback)}</span>` : ""}</td>
              <td>${esc(a.subject)}</td><td>${fmtDay(a.dueDate)}</td>
              <td><span class="badge ${cls}">${label}</span></td>
              <td>${s && s.score !== null ? `${clean(s.score)} / ${clean(a.maxScore)}` : "—"}</td>
              <td><div class="row-actions"><button class="btn ${s ? "btn-secondary" : "btn-primary"} btn-sm"
                data-open="${esc(a.id)}">${s ? (s.score !== null ? "View" : "Edit") : "Submit"}</button></div></td></tr>`;
          })
          .join("")}</tbody></table>`)
      : '<div class="empty">No assignments yet. 🎉</div>';

    el.querySelectorAll("[data-open]").forEach(
      (b) =>
        (b.onclick = () =>
          submitModal(list.find((x) => String(x.id) === b.dataset.open))),
    );
  }

  function submitModal(a) {
    const sub = a.submission;
    const graded = sub && sub.score !== null;
    const late = new Date() > new Date(a.dueDate + "T23:59:59");
    openModal(
      `<h2>${esc(a.title)}</h2>
      <p class="subtitle">${esc(a.subject)} · due ${fmtDay(a.dueDate)} · max ${clean(a.maxScore)}${late && !sub ? " · <b style='color:#fcd34d'>past due — will be marked late</b>" : ""}</p>
      ${a.description ? `<p style="white-space:pre-wrap;margin-bottom:16px">${esc(a.description)}</p>` : ""}
      ${
        graded
          ? `<p><span class="badge badge-a">Graded: ${clean(sub.score)} / ${clean(a.maxScore)}</span></p>
        ${sub.feedback ? `<p class="subtitle" style="margin-top:8px">Feedback: ${esc(sub.feedback)}</p>` : ""}`
          : ""
      }
      <div class="field" style="margin-top:12px"><label>Your answer</label>
        <textarea class="input" id="sb-content" maxlength="10000" style="min-height:160px" ${graded ? "readonly" : ""}>${esc(sub ? sub.content : "")}</textarea></div>`,
      [
        { label: "Close", onClick: closeModal },
        ...(graded
          ? []
          : [
              {
                label: sub ? "Update submission" : "Submit",
                primary: true,
                onClick: async () => {
                  try {
                    const content = $("#sb-content").value.trim();
                    if (!content) throw new Error("Write your answer first");
                    await post("/submissions/submit", {
                      assignmentId: a.id,
                      content,
                    });
                    closeModal();
                    showAlert("Submitted");
                    render();
                  } catch (e) {
                    showAlert(e.message, true);
                  }
                },
              },
            ]),
      ],
    );
  }
})();
