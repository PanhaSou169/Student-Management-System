import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/*
 * =====================================================================
 * Classroom features on MySQL: attendance, announcements,
 * assignments and submissions.
 *
 * Tables are created automatically (CREATE TABLE IF NOT EXISTS) the first
 * time the server starts, so there is NO extra SQL to run.
 *
 * Permission rules (same as real life):
 *   ADMIN    : everything
 *   TEACHER  : attendance / assignments / grading for their OWN subject only
 *   STUDENT  : see own attendance, see assignments, submit work
 * =====================================================================
 */
final class ClassroomService {
    static final Set<String> STATUSES = Set.of("PRESENT", "LATE", "ABSENT", "EXCUSED");
    private static final String TAIL = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci";

    private final Database db;
    private final Clock clock;
    private final AuditLog audit;
    private final SchoolService school;

    ClassroomService(Database db, Clock clock, AuditLog audit, SchoolService school) throws SQLException {
        this.db = db;
        this.clock = clock;
        this.audit = audit;
        this.school = school;
        try (Statement st = db.conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS attendance ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "student_id VARCHAR(10) NOT NULL,"
                    + "subject VARCHAR(60) NOT NULL,"
                    + "att_date DATE NOT NULL,"
                    + "status ENUM('PRESENT','LATE','ABSENT','EXCUSED') NOT NULL,"
                    + "marked_by VARCHAR(10) NULL,"
                    + "UNIQUE KEY uq_att (student_id, subject, att_date),"
                    + "CONSTRAINT fk_att_student FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE"
                    + ")" + TAIL);

            st.execute("CREATE TABLE IF NOT EXISTS announcements ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "title VARCHAR(120) NOT NULL,"
                    + "body TEXT NOT NULL,"
                    + "audience ENUM('ALL','STUDENT','TEACHER') NOT NULL DEFAULT 'ALL',"
                    + "subject VARCHAR(60) NULL,"
                    + "author_id VARCHAR(10) NULL,"
                    + "author_name VARCHAR(100) NULL,"
                    + "created_at BIGINT NOT NULL"
                    + ")" + TAIL);

            st.execute("CREATE TABLE IF NOT EXISTS assignments ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "subject VARCHAR(60) NOT NULL,"
                    + "title VARCHAR(120) NOT NULL,"
                    + "description TEXT NULL,"
                    + "due_date DATE NOT NULL,"
                    + "max_score DOUBLE NOT NULL DEFAULT 100,"
                    + "created_by VARCHAR(10) NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "CONSTRAINT chk_asg_max CHECK (max_score > 0)"
                    + ")" + TAIL);

            st.execute("CREATE TABLE IF NOT EXISTS submissions ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "assignment_id BIGINT NOT NULL,"
                    + "student_id VARCHAR(10) NOT NULL,"
                    + "content TEXT NOT NULL,"
                    + "submitted_at BIGINT NOT NULL,"
                    + "score DOUBLE NULL,"
                    + "feedback VARCHAR(500) NULL,"
                    + "graded_by VARCHAR(10) NULL,"
                    + "UNIQUE KEY uq_sub (assignment_id, student_id),"
                    + "CONSTRAINT fk_sub_asg FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,"
                    + "CONSTRAINT fk_sub_student FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE"
                    + ")" + TAIL);
        }
    }

    // =================================================================
    //  Attendance
    // =================================================================
    private static void requireStaffSubject(User actor, String subject) {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("Subject cannot be empty.");
        if (actor.is("STUDENT") || !SchoolService.canGrade(actor, subject))
            throw new SecurityException("You can only manage your own subject"
                    + (actor.subject() != null ? " (" + actor.subject() + ")." : "."));
    }

    /** Who was marked on this day (teacher / admin). */
    List<Map<String, Object>> attendanceOn(User actor, String subject, LocalDate date) {
        requireStaffSubject(actor, subject);
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT student_id, status FROM attendance WHERE subject = ? AND att_date = ?")) {
            ps.setString(1, subject.trim());
            ps.setDate(2, Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("studentId", rs.getString("student_id"));
                    m.put("status", rs.getString("status"));
                    out.add(m);
                }
            }
        } catch (SQLException e) { throw Database.wrap(e); }
        return out;
    }

    /** Save the whole class for one day (insert or update). records: [{studentId,status}] */
    int markAttendance(User actor, String subject, LocalDate date, List<Map<String, Object>> records) {
        requireStaffSubject(actor, subject);
        if (date == null) throw new IllegalArgumentException("Pick a date.");
        if (date.isAfter(LocalDate.now(clock))) throw new IllegalArgumentException("You cannot mark attendance in the future.");
        if (records == null || records.isEmpty()) throw new IllegalArgumentException("No students to save.");

        Set<String> valid = new HashSet<>();
        for (User s : school.students()) valid.add(s.id().toUpperCase());

        boolean prev;
        try {
            prev = db.conn.getAutoCommit();
            db.conn.setAutoCommit(false);
        } catch (SQLException e) { throw Database.wrap(e); }
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO attendance(student_id, subject, att_date, status, marked_by) VALUES (?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE status = VALUES(status), marked_by = VALUES(marked_by)")) {
            int n = 0;
            for (Map<String, Object> r : records) {
                String sid = String.valueOf(r.get("studentId")).trim().toUpperCase();
                String status = String.valueOf(r.get("status")).trim().toUpperCase();
                if (!valid.contains(sid)) throw new IllegalArgumentException("Unknown student: " + sid);
                if (!STATUSES.contains(status)) throw new IllegalArgumentException("Bad status: " + status);
                ps.setString(1, sid);
                ps.setString(2, subject.trim());
                ps.setDate(3, Date.valueOf(date));
                ps.setString(4, status);
                ps.setString(5, actor.id());
                ps.addBatch();
                n++;
            }
            ps.executeBatch();
            db.conn.commit();
            audit.log(actor.id(), "MARK_ATTENDANCE", subject.trim() + " " + date + " (" + n + ")");
            return n;
        } catch (SQLException e) {
            rollback();
            throw Database.wrap(e);
        } catch (RuntimeException e) {
            rollback();
            throw e;
        } finally {
            try { db.conn.setAutoCommit(prev); } catch (SQLException ignored) {}
        }
    }

    private void rollback() {
        try { db.conn.rollback(); } catch (SQLException ignored) {}
    }

    /** Per-student totals for a subject (teacher / admin). */
    List<Map<String, Object>> attendanceSummary(User actor, String subject) {
        requireStaffSubject(actor, subject);
        Map<String, int[]> counts = new LinkedHashMap<>(); // present, late, absent, excused
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT student_id, status, COUNT(*) FROM attendance WHERE subject = ? GROUP BY student_id, status")) {
            ps.setString(1, subject.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int[] c = counts.computeIfAbsent(rs.getString(1), k -> new int[4]);
                    switch (rs.getString(2)) {
                        case "PRESENT" -> c[0] = rs.getInt(3);
                        case "LATE" -> c[1] = rs.getInt(3);
                        case "ABSENT" -> c[2] = rs.getInt(3);
                        default -> c[3] = rs.getInt(3);
                    }
                }
            }
        } catch (SQLException e) { throw Database.wrap(e); }

        List<Map<String, Object>> out = new ArrayList<>();
        for (User s : school.students()) {
            int[] c = counts.get(s.id());
            if (c == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", s.id());
            m.put("name", s.name());
            m.put("present", c[0]);
            m.put("late", c[1]);
            m.put("absent", c[2]);
            m.put("excused", c[3]);
            out.add(m);
        }
        return out;
    }

    /** A student's own attendance, newest first. */
    List<Map<String, Object>> myAttendance(User actor) {
        if (!actor.is("STUDENT")) throw new SecurityException("Only students have a personal attendance record.");
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT subject, att_date, status FROM attendance WHERE student_id = ? "
                        + "ORDER BY att_date DESC, id DESC LIMIT 1000")) {
            ps.setString(1, actor.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("subject", rs.getString("subject"));
                    m.put("date", rs.getDate("att_date").toLocalDate().toString());
                    m.put("status", rs.getString("status"));
                    out.add(m);
                }
            }
        } catch (SQLException e) { throw Database.wrap(e); }
        return out;
    }

    // =================================================================
    //  Announcements
    // =================================================================
    List<Map<String, Object>> announcements(User actor) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT id, title, body, audience, subject, author_id, author_name, created_at FROM announcements "
                        + "WHERE ? = 'ADMIN' OR audience = 'ALL' OR audience = ? OR author_id = ? "
                        + "ORDER BY id DESC LIMIT 100")) {
            ps.setString(1, actor.role());
            ps.setString(2, actor.role());
            ps.setString(3, actor.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("title", rs.getString("title"));
                    m.put("body", rs.getString("body"));
                    m.put("audience", rs.getString("audience"));
                    m.put("subject", rs.getString("subject"));
                    m.put("authorId", rs.getString("author_id"));
                    m.put("authorName", rs.getString("author_name"));
                    m.put("createdAt", rs.getLong("created_at"));
                    m.put("canDelete", actor.is("ADMIN") || actor.id().equals(rs.getString("author_id")));
                    out.add(m);
                }
            }
        } catch (SQLException e) { throw Database.wrap(e); }
        return out;
    }

    void addAnnouncement(User actor, String title, String body, String audience) {
        if (actor.is("STUDENT")) throw new SecurityException("Students cannot post announcements.");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Title is required.");
        if (body == null || body.isBlank()) throw new IllegalArgumentException("Message is required.");
        if (title.trim().length() > 120) throw new IllegalArgumentException("Title is too long (max 120).");
        if (body.trim().length() > 2000) throw new IllegalArgumentException("Message is too long (max 2000).");

        String aud = actor.is("ADMIN") ? String.valueOf(audience).toUpperCase() : "STUDENT";
        if (!Set.of("ALL", "STUDENT", "TEACHER").contains(aud)) aud = "ALL";
        String subject = actor.is("TEACHER") ? actor.subject() : null;

        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO announcements(title, body, audience, subject, author_id, author_name, created_at) "
                        + "VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, title.trim());
            ps.setString(2, body.trim());
            ps.setString(3, aud);
            ps.setString(4, subject);
            ps.setString(5, actor.id());
            ps.setString(6, actor.name());
            ps.setLong(7, clock.millis());
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
        audit.log(actor.id(), "ADD_ANNOUNCEMENT", aud + ": " + title.trim());
    }

    void deleteAnnouncement(User actor, long id) {
        String author = null;
        boolean found = false;
        try (PreparedStatement ps = db.conn.prepareStatement("SELECT author_id FROM announcements WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { found = true; author = rs.getString(1); }
            }
        } catch (SQLException e) { throw Database.wrap(e); }
        if (!found) throw new IllegalArgumentException("Announcement not found.");
        if (!actor.is("ADMIN") && !actor.id().equals(author))
            throw new SecurityException("You can only delete your own announcements.");
        try (PreparedStatement ps = db.conn.prepareStatement("DELETE FROM announcements WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
        audit.log(actor.id(), "DELETE_ANNOUNCEMENT", String.valueOf(id));
    }

    // =================================================================
    //  Assignments
    // =================================================================
    private record Asg(long id, String subject, String title, LocalDate due, double max) {}

    private Optional<Asg> findAssignment(long id) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT id, subject, title, due_date, max_score FROM assignments WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new Asg(rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getDate(4).toLocalDate(), rs.getDouble(5)))
                        : Optional.empty();
            }
        } catch (SQLException e) { throw Database.wrap(e); }
    }

    private long dueEndMillis(LocalDate due) {
        return due.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private Map<String, Object> submissionMap(ResultSet rs, LocalDate due) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("content", rs.getString("content"));
        long at = rs.getLong("submitted_at");
        m.put("submittedAt", at);
        double score = rs.getDouble("score");
        m.put("score", rs.wasNull() ? null : score);
        m.put("feedback", rs.getString("feedback"));
        m.put("late", at > dueEndMillis(due));
        return m;
    }

    /**
     * Students: all assignments + their own submission (or null).
     * Teachers: own subject, with submitted / graded counts.  Admin: all.
     */
    List<Map<String, Object>> assignments(User actor) {
        boolean teacher = actor.is("TEACHER");
        boolean student = actor.is("STUDENT");
        if (teacher && (actor.subject() == null || actor.subject().isBlank())) return new ArrayList<>();

        String sql = "SELECT a.id, a.subject, a.title, a.description, a.due_date, a.max_score, a.created_by, a.created_at, "
                + "(SELECT COUNT(*) FROM submissions s WHERE s.assignment_id = a.id) AS submitted, "
                + "(SELECT COUNT(*) FROM submissions s WHERE s.assignment_id = a.id AND s.score IS NOT NULL) AS graded "
                + "FROM assignments a"
                + (teacher ? " WHERE LOWER(a.subject) = LOWER(?)" : "")
                + " ORDER BY a.due_date " + (student ? "ASC" : "DESC") + ", a.id DESC LIMIT 500";

        List<Map<String, Object>> out = new ArrayList<>();
        Map<Long, LocalDate> dues = new LinkedHashMap<>();
        try (PreparedStatement ps = db.conn.prepareStatement(sql)) {
            if (teacher) ps.setString(1, actor.subject().trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate due = rs.getDate("due_date").toLocalDate();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("subject", rs.getString("subject"));
                    m.put("title", rs.getString("title"));
                    m.put("description", rs.getString("description"));
                    m.put("dueDate", due.toString());
                    m.put("maxScore", rs.getDouble("max_score"));
                    m.put("createdBy", rs.getString("created_by"));
                    m.put("createdAt", rs.getLong("created_at"));
                    if (!student) {
                        m.put("submitted", rs.getInt("submitted"));
                        m.put("graded", rs.getInt("graded"));
                    }
                    dues.put(rs.getLong("id"), due);
                    out.add(m);
                }
            }
        } catch (SQLException e) { throw Database.wrap(e); }

        if (student) {
            Map<Long, Map<String, Object>> mine = new LinkedHashMap<>();
            try (PreparedStatement ps = db.conn.prepareStatement(
                    "SELECT id, assignment_id, content, submitted_at, score, feedback FROM submissions WHERE student_id = ?")) {
                ps.setString(1, actor.id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long aid = rs.getLong("assignment_id");
                        LocalDate due = dues.get(aid);
                        if (due != null) mine.put(aid, submissionMap(rs, due));
                    }
                }
            } catch (SQLException e) { throw Database.wrap(e); }
            for (Map<String, Object> m : out) m.put("submission", mine.get((Long) m.get("id")));
        }
        return out;
    }

    long createAssignment(User actor, String subject, String title, String description,
                          LocalDate due, double maxScore) {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("Subject is required.");
        if (actor.is("STUDENT") || !SchoolService.canGrade(actor, subject))
            throw new SecurityException("You can only create assignments for your own subject.");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("Title is required.");
        if (title.trim().length() > 120) throw new IllegalArgumentException("Title is too long (max 120).");
        if (description != null && description.trim().length() > 2000)
            throw new IllegalArgumentException("Instructions are too long (max 2000).");
        if (due == null) throw new IllegalArgumentException("Due date is required.");
        if (Double.isNaN(maxScore) || maxScore <= 0 || maxScore > 1000)
            throw new IllegalArgumentException("Max score must be between 0 and 1000.");

        school.ensureSubject(subject);
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO assignments(subject, title, description, due_date, max_score, created_by, created_at) "
                        + "VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, subject.trim());
            ps.setString(2, title.trim());
            ps.setString(3, description == null || description.isBlank() ? null : description.trim());
            ps.setDate(4, Date.valueOf(due));
            ps.setDouble(5, maxScore);
            ps.setString(6, actor.id());
            ps.setLong(7, clock.millis());
            ps.executeUpdate();
            long id = 0;
            try (ResultSet keys = ps.getGeneratedKeys()) { if (keys.next()) id = keys.getLong(1); }
            audit.log(actor.id(), "ADD_ASSIGNMENT", subject.trim() + ": " + title.trim());
            return id;
        } catch (SQLException e) { throw Database.wrap(e); }
    }

    void deleteAssignment(User actor, long id) {
        Asg a = findAssignment(id).orElseThrow(() -> new IllegalArgumentException("Assignment not found."));
        if (actor.is("STUDENT") || !SchoolService.canGrade(actor, a.subject()))
            throw new SecurityException("You can only delete assignments of your own subject.");
        try (PreparedStatement ps = db.conn.prepareStatement("DELETE FROM assignments WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
        audit.log(actor.id(), "DELETE_ASSIGNMENT", a.subject() + ": " + a.title());
    }

    /** Teacher / admin: every student with their submission (or null). */
    List<Map<String, Object>> submissionsOf(User actor, long assignmentId) {
        Asg a = findAssignment(assignmentId).orElseThrow(() -> new IllegalArgumentException("Assignment not found."));
        if (actor.is("STUDENT") || !SchoolService.canGrade(actor, a.subject()))
            throw new SecurityException("You can only see submissions of your own subject.");

        Map<String, Map<String, Object>> bySt = new LinkedHashMap<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT id, student_id, content, submitted_at, score, feedback FROM submissions WHERE assignment_id = ?")) {
            ps.setLong(1, assignmentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) bySt.put(rs.getString("student_id"), submissionMap(rs, a.due()));
            }
        } catch (SQLException e) { throw Database.wrap(e); }

        List<Map<String, Object>> out = new ArrayList<>();
        for (User s : school.students()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", s.id());
            m.put("name", s.name());
            m.put("submission", bySt.get(s.id()));
            out.add(m);
        }
        return out;
    }

    // =================================================================
    //  Submissions
    // =================================================================
    void submit(User actor, long assignmentId, String content) {
        if (!actor.is("STUDENT")) throw new SecurityException("Only students can submit work.");
        Asg a = findAssignment(assignmentId).orElseThrow(() -> new IllegalArgumentException("Assignment not found."));
        if (content == null || content.isBlank()) throw new IllegalArgumentException("Write your answer first.");
        if (content.trim().length() > 10_000) throw new IllegalArgumentException("Answer is too long (max 10000).");

        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT score FROM submissions WHERE assignment_id = ? AND student_id = ?")) {
            ps.setLong(1, assignmentId);
            ps.setString(2, actor.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    rs.getDouble(1);
                    if (!rs.wasNull()) throw new IllegalArgumentException("This work is already graded. Ask your teacher.");
                }
            }
        } catch (SQLException e) { throw Database.wrap(e); }

        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO submissions(assignment_id, student_id, content, submitted_at) VALUES (?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE content = VALUES(content), submitted_at = VALUES(submitted_at)")) {
            ps.setLong(1, assignmentId);
            ps.setString(2, actor.id());
            ps.setString(3, content.trim());
            ps.setLong(4, clock.millis());
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
        audit.log(actor.id(), "SUBMIT_ASSIGNMENT", a.subject() + "/" + a.title());
    }

    /** Grade one submission. If syncGradebook, also save it as a grade component (0-100 scale). */
    void grade(User actor, long submissionId, double score, String feedback, boolean syncGradebook) {
        String studentId = null;
        long assignmentId = 0;
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT student_id, assignment_id FROM submissions WHERE id = ?")) {
            ps.setLong(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { studentId = rs.getString(1); assignmentId = rs.getLong(2); }
            }
        } catch (SQLException e) { throw Database.wrap(e); }
        if (studentId == null) throw new IllegalArgumentException("Submission not found.");

        Asg a = findAssignment(assignmentId).orElseThrow(() -> new IllegalArgumentException("Assignment not found."));
        if (actor.is("STUDENT") || !SchoolService.canGrade(actor, a.subject()))
            throw new SecurityException("You can only grade your own subject.");
        if (Double.isNaN(score) || score < 0 || score > a.max())
            throw new IllegalArgumentException("Score must be between 0 and " + trim(a.max()) + ".");
        if (feedback != null && feedback.trim().length() > 500)
            throw new IllegalArgumentException("Feedback is too long (max 500).");

        try (PreparedStatement ps = db.conn.prepareStatement(
                "UPDATE submissions SET score = ?, feedback = ?, graded_by = ? WHERE id = ?")) {
            ps.setDouble(1, score);
            ps.setString(2, feedback == null || feedback.isBlank() ? null : feedback.trim());
            ps.setString(3, actor.id());
            ps.setLong(4, submissionId);
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
        audit.log(actor.id(), "GRADE_SUBMISSION", studentId + " " + a.subject() + "/" + a.title() + " = " + score);

        if (syncGradebook) {
            String name = a.title().length() > 40 ? a.title().substring(0, 40) : a.title();
            double pct = Math.round(score / a.max() * 1000.0) / 10.0;
            GradeComponent existing = null;
            for (GradeComponent c : school.listComponents(actor, studentId, a.subject()))
                if (c.componentName().equalsIgnoreCase(name)) { existing = c; break; }
            if (existing == null)
                school.addComponent(actor, studentId, a.subject(), name, pct, 1.0, "From assignment");
            else
                school.updateComponent(actor, studentId, a.subject(), existing.componentName(),
                        pct, existing.weight(), existing.comment());
        }
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
