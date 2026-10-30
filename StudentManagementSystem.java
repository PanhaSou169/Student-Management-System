import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/*
 * Student Management System (console version, MySQL + hashed passwords)
 *
 * Layers (so a web UI such as Spring Boot can reuse everything above Main):
 *   Config, GradeCalculator, PasswordHasher  - rules, no database needed
 *   Database, AuditLog                       - storage
 *   AuthService, SchoolService               - business logic and permissions
 *   Main                                     - console user interface only
 */

// =====================================================================
//  Settings: change the grading scale, pass mark and login rules here
// =====================================================================
final class Config {
    // --- MySQL connection (the password is NOT stored here: it is asked at start-up) ---
    static final String DB_SERVER_URL = "jdbc:mysql://localhost:3306/";
    static final String DB_NAME = "student_db";
    static final String DB_USER = "root";
    static final double PASS_MARK = 50.0;
    static final int MIN_PASSWORD_LENGTH = 8;
    static final int MAX_FAILED_LOGINS = 3;
    static final long LOCK_MINUTES = 5;
    private Config() {}
}

record User(String id, String name, String role, String subject) {
    boolean is(String r) { return role.equals(r); }

    @Override
    public String toString() {
        return String.format("%-6s %-20s %-8s%s", id, name, role,
                subject == null || subject.isBlank() ? "" : " (" + subject + ")");
    }
}

record SubjectStat(String subject, int count, double average, int passed) {}

// =====================================================================
//  Grade rules
// =====================================================================
final class GradeCalculator {
    private GradeCalculator() {}

    static boolean isValidScore(double s) {
        return !Double.isNaN(s) && s >= 0 && s <= 100;
    }

    static double total(Collection<Double> scores) {
        double sum = 0;
        for (double s : scores) sum += s;
        return sum;
    }

    static double average(Collection<Double> scores) {
        return scores.isEmpty() ? 0 : total(scores) / scores.size();
    }

    static String letter(double score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        if (score >= Config.PASS_MARK) return "E";
        return "F";
    }

    static boolean isPass(double score) {
        return score >= Config.PASS_MARK;
    }
}

// =====================================================================
//  Password hashing: PBKDF2 (built into the JDK) with a random salt.
//  Stored format:  pbkdf2$iterations$salt$hash
//  Plain passwords are never stored. BCrypt can replace this class later.
// =====================================================================
final class PasswordHasher {
    private static final int ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] h = pbkdf2(password.toCharArray(), salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(h);
    }

    static boolean verify(String password, String stored) {
        try {
            String[] p = stored.split("\\$");
            if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
            byte[] expected = Base64.getDecoder().decode(p[3]);
            byte[] actual = pbkdf2(password.toCharArray(), Base64.getDecoder().decode(p[2]),
                    Integer.parseInt(p[1]));
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Returns null when the password is acceptable, otherwise the reason it is not. */
    static String checkStrength(String pw) {
        if (pw == null || pw.length() < Config.MIN_PASSWORD_LENGTH)
            return "Password must be at least " + Config.MIN_PASSWORD_LENGTH + " characters.";
        boolean letter = false, digit = false;
        for (char c : pw.toCharArray()) {
            if (Character.isLetter(c)) letter = true;
            if (Character.isDigit(c)) digit = true;
        }
        if (!letter || !digit) return "Password must contain at least one letter and one digit.";
        return null;
    }

    private static byte[] pbkdf2(char[] pw, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw, salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing failed", e);
        }
    }
}

// =====================================================================
//  Database (MySQL through JDBC)
// =====================================================================
final class Database implements AutoCloseable {
    final Connection conn;

    Database(String serverUrl, String user, String password, String dbName) throws SQLException {
        if (!dbName.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Bad database name.");
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        try (Connection serverConnection = DriverManager.getConnection(serverUrl + options, user, password);
                Statement st = serverConnection.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `" + dbName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }
        conn = DriverManager.getConnection(serverUrl + dbName + options, user, password);
        try (Statement st = conn.createStatement()) {
            // utf8mb4_general_ci is case-insensitive, so "Math" and "math" are the same subject
            st.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "id VARCHAR(10) NOT NULL PRIMARY KEY,"
                    + "name VARCHAR(100) NOT NULL,"
                    + "role ENUM('ADMIN','TEACHER','STUDENT') NOT NULL,"
                    + "subject VARCHAR(60) NULL,"
                    + "password_hash VARCHAR(255) NOT NULL,"
                    + "failed_attempts INT NOT NULL DEFAULT 0,"
                    + "locked_until BIGINT NOT NULL DEFAULT 0"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            st.execute("CREATE TABLE IF NOT EXISTS grades ("
                    + "student_id VARCHAR(10) NOT NULL,"
                    + "subject VARCHAR(60) NOT NULL,"
                    + "score DOUBLE NOT NULL,"
                    + "updated_by VARCHAR(10) NULL,"
                    + "updated_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (student_id, subject),"
                    + "CONSTRAINT fk_grades_student FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE,"
                    + "CONSTRAINT chk_grades_score CHECK (score >= 0 AND score <= 100)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            st.execute("CREATE TABLE IF NOT EXISTS audit_log ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "ts BIGINT NOT NULL,"
                    + "actor VARCHAR(10) NULL,"
                    + "action VARCHAR(30) NOT NULL,"
                    + "details VARCHAR(500) NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }

    static RuntimeException wrap(SQLException e) {
        return new IllegalStateException("Database error: " + e.getMessage(), e);
    }

    static User mapUser(ResultSet rs) throws SQLException {
        return new User(rs.getString("id"), rs.getString("name"), rs.getString("role"), rs.getString("subject"));
    }
}

// =====================================================================
//  Audit log: who did what and when
// =====================================================================
final class AuditLog {
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Database db;
    private final Clock clock;

    AuditLog(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    void log(String actor, String action, String details) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO audit_log(ts, actor, action, details) VALUES (?,?,?,?)")) {
            ps.setLong(1, clock.millis());
            ps.setString(2, actor);
            ps.setString(3, action);
            ps.setString(4, details);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
    }

    List<String> recent(int limit) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT ts, actor, action, details FROM audit_log ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(String.format("%s  %-6s %-15s %s",
                            FORMAT.format(java.time.Instant.ofEpochMilli(rs.getLong("ts"))),
                            rs.getString("actor"), rs.getString("action"),
                            rs.getString("details") == null ? "" : rs.getString("details")));
                }
            }
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
        return out;
    }
}

// =====================================================================
//  Accounts and login rules
// =====================================================================
final class AuthService {
    enum Status { OK, BAD_CREDENTIALS, LOCKED }

    record LoginResult(Status status, User user) {}

    private static final String DUMMY_HASH = PasswordHasher.hash("dummy-password-1");

    private final Database db;
    private final Clock clock;
    private final AuditLog audit;

    AuthService(Database db, Clock clock, AuditLog audit) {
        this.db = db;
        this.clock = clock;
        this.audit = audit;
    }

    boolean hasAdmin() {
        try (Statement st = db.conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
    }

    /** Creates an account and returns its new ID (A001, T001, S001 ...). */
    String createUser(String actor, String role, String name, String subject, String password) {
        if (!role.equals("ADMIN") && !role.equals("TEACHER") && !role.equals("STUDENT"))
            throw new IllegalArgumentException("Unknown role.");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name cannot be empty.");
        if (name.trim().length() > 100) throw new IllegalArgumentException("Name is too long (max 100).");
        if (role.equals("TEACHER") && (subject == null || subject.isBlank()))
            throw new IllegalArgumentException("A teacher needs a subject.");
        if (subject != null && subject.trim().length() > 60) throw new IllegalArgumentException("Subject is too long (max 60).");
        String weak = PasswordHasher.checkStrength(password);
        if (weak != null) throw new IllegalArgumentException(weak);

        String prefix = role.substring(0, 1);
        try {
            int next;
            try (PreparedStatement ps = db.conn.prepareStatement(
                    "SELECT COALESCE(MAX(CAST(SUBSTR(id, 2) AS UNSIGNED)), 0) FROM users WHERE role = ?")) {
                ps.setString(1, role);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    next = rs.getInt(1) + 1;
                }
            }
            String id = String.format("%s%03d", prefix, next);
            try (PreparedStatement ps = db.conn.prepareStatement(
                    "INSERT INTO users(id, name, role, subject, password_hash) VALUES (?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, name.trim());
                ps.setString(3, role);
                ps.setString(4, role.equals("TEACHER") ? subject.trim() : null);
                ps.setString(5, PasswordHasher.hash(password));
                ps.executeUpdate();
            }
            audit.log(actor, "ADD_USER", id + " " + role + " " + name.trim());
            return id;
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
    }

    LoginResult login(String id, String password) {
        long now = clock.millis();
        User user = null;
        String hash = null;
        int failed = 0;
        long lockedUntil = 0;
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT id, name, role, subject, password_hash, failed_attempts, locked_until "
                        + "FROM users WHERE UPPER(id) = UPPER(?)")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    user = Database.mapUser(rs);
                    hash = rs.getString("password_hash");
                    failed = rs.getInt("failed_attempts");
                    lockedUntil = rs.getLong("locked_until");
                }
            }
        } catch (SQLException e) {
            throw Database.wrap(e);
        }

        if (user == null) {
            PasswordHasher.verify(password, DUMMY_HASH); // same work as a real login
            audit.log("-", "LOGIN_FAIL", "unknown id " + abbreviate(id));
            return new LoginResult(Status.BAD_CREDENTIALS, null);
        }
        if (lockedUntil > now) {
            audit.log(user.id(), "LOGIN_BLOCKED", "account is locked");
            return new LoginResult(Status.LOCKED, null);
        }
        if (PasswordHasher.verify(password, hash)) {
            setAttempts(user.id(), 0, 0);
            audit.log(user.id(), "LOGIN_OK", user.role());
            return new LoginResult(Status.OK, user);
        }
        failed++;
        if (failed >= Config.MAX_FAILED_LOGINS) {
            setAttempts(user.id(), 0, now + Config.LOCK_MINUTES * 60_000L);
            audit.log(user.id(), "ACCOUNT_LOCKED", "too many wrong passwords");
            return new LoginResult(Status.LOCKED, null);
        }
        setAttempts(user.id(), failed, 0);
        audit.log(user.id(), "LOGIN_FAIL", "wrong password (" + failed + "/" + Config.MAX_FAILED_LOGINS + ")");
        return new LoginResult(Status.BAD_CREDENTIALS, null);
    }

    void changePassword(User actor, String oldPassword, String newPassword) {
        String hash = hashOf(actor.id());
        if (hash == null || !PasswordHasher.verify(oldPassword, hash))
            throw new IllegalArgumentException("Current password is wrong.");
        String weak = PasswordHasher.checkStrength(newPassword);
        if (weak != null) throw new IllegalArgumentException(weak);
        storeHash(actor.id(), newPassword);
        audit.log(actor.id(), "CHANGE_PASSWORD", "own password");
    }

    void resetPassword(User actor, String targetId, String newPassword) {
        if (!actor.is("ADMIN")) throw new SecurityException("Only an admin can reset passwords.");
        if (hashOf(targetId) == null) throw new IllegalArgumentException("User not found.");
        String weak = PasswordHasher.checkStrength(newPassword);
        if (weak != null) throw new IllegalArgumentException(weak);
        storeHash(targetId, newPassword);
        setAttempts(targetId, 0, 0);
        audit.log(actor.id(), "RESET_PASSWORD", targetId);
    }

    static long lockMinutes() { return Config.LOCK_MINUTES; }

    private String hashOf(String id) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT password_hash FROM users WHERE UPPER(id) = UPPER(?)")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
    }

    private void storeHash(String id, String password) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "UPDATE users SET password_hash = ? WHERE UPPER(id) = UPPER(?)")) {
            ps.setString(1, PasswordHasher.hash(password));
            ps.setString(2, id.trim());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
    }

    private void setAttempts(String id, int failed, long lockedUntil) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "UPDATE users SET failed_attempts = ?, locked_until = ? WHERE id = ?")) {
            ps.setInt(1, failed);
            ps.setLong(2, lockedUntil);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
    }

    private static String abbreviate(String s) {
        String t = s.replaceAll("[\\r\\n]", " ").trim();
        return t.length() > 30 ? t.substring(0, 30) + "..." : t;
    }
}

// =====================================================================
//  Users, grades and permissions
//    ADMIN   - everything
//    TEACHER - grades for their OWN subject only
//    STUDENT - read their own grades only
// =====================================================================
final class SchoolService {
    private final Database db;
    private final Clock clock;
    private final AuditLog audit;

    SchoolService(Database db, Clock clock, AuditLog audit) {
        this.db = db;
        this.clock = clock;
        this.audit = audit;
    }

    // ----- users -----

    Optional<User> find(String id) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT id, name, role, subject FROM users WHERE UPPER(id) = UPPER(?)")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(Database.mapUser(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
    }

    List<User> allUsers() {
        return query("SELECT id, name, role, subject FROM users ORDER BY role, id", null);
    }

    List<User> students() {
        return query("SELECT id, name, role, subject FROM users WHERE role = 'STUDENT' ORDER BY id", null);
    }

    List<User> search(String keyword, boolean studentsOnly) {
        String sql = "SELECT id, name, role, subject FROM users WHERE (LOWER(id) LIKE ? OR LOWER(name) LIKE ?)"
                + (studentsOnly ? " AND role = 'STUDENT'" : "") + " ORDER BY role, id";
        String k = "%" + keyword.toLowerCase() + "%";
        return query(sql, new String[] {k, k});
    }

    private List<User> query(String sql, String[] params) {
        List<User> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(sql)) {
            if (params != null) for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(Database.mapUser(rs));
            }
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
        return out;
    }

    void updateUser(User actor, String id, String newName, String newSubject) {
        requireAdmin(actor);
        User target = find(id).orElseThrow(() -> new IllegalArgumentException("User not found."));
        String name = newName == null || newName.isBlank() ? target.name() : newName.trim();
        String subject = target.subject();
        if (target.is("TEACHER") && newSubject != null && !newSubject.isBlank()) subject = newSubject.trim();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "UPDATE users SET name = ?, subject = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setString(2, subject);
            ps.setString(3, target.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
        audit.log(actor.id(), "UPDATE_USER", target.id());
    }

    void deleteUser(User actor, String id) {
        requireAdmin(actor);
        User target = find(id).orElseThrow(() -> new IllegalArgumentException("User not found."));
        if (target.id().equals(actor.id())) throw new IllegalArgumentException("You cannot delete your own account.");
        if (target.is("ADMIN") && countAdmins() <= 1) throw new IllegalArgumentException("You cannot delete the last admin.");
        try (PreparedStatement ps = db.conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setString(1, target.id());
            ps.executeUpdate(); // grades are removed too (ON DELETE CASCADE)
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
        audit.log(actor.id(), "DELETE_USER", target.id() + " " + target.name());
    }

    private int countAdmins() {
        try (Statement st = db.conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
    }

    // ----- grades -----

    static boolean canGrade(User actor, String subject) {
        if (actor.is("ADMIN")) return true;
        return actor.is("TEACHER") && actor.subject() != null && actor.subject().equalsIgnoreCase(subject.trim());
    }

    void addGrade(User actor, String studentId, String subject, double score) {
        checkGradeRequest(actor, studentId, subject, score);
        if (hasGrade(studentId, subject))
            throw new IllegalArgumentException("That subject already has a grade. Use update.");
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO grades(student_id, subject, score, updated_by, updated_at) VALUES (?,?,?,?,?)")) {
            ps.setString(1, studentId.toUpperCase());
            ps.setString(2, subject.trim());
            ps.setDouble(3, score);
            ps.setString(4, actor.id());
            ps.setLong(5, clock.millis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
        audit.log(actor.id(), "ADD_GRADE", studentId.toUpperCase() + " " + subject.trim() + " = " + score);
    }

    void updateGrade(User actor, String studentId, String subject, double score) {
        checkGradeRequest(actor, studentId, subject, score);
        if (!hasGrade(studentId, subject))
            throw new IllegalArgumentException("No grade for that subject yet. Use add.");
        try (PreparedStatement ps = db.conn.prepareStatement(
                "UPDATE grades SET score = ?, updated_by = ?, updated_at = ? WHERE student_id = ? AND subject = ?")) {
            ps.setDouble(1, score);
            ps.setString(2, actor.id());
            ps.setLong(3, clock.millis());
            ps.setString(4, studentId.toUpperCase());
            ps.setString(5, subject.trim());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
        audit.log(actor.id(), "UPDATE_GRADE", studentId.toUpperCase() + " " + subject.trim() + " = " + score);
    }

    void deleteGrade(User actor, String studentId, String subject) {
        checkGradeRequest(actor, studentId, subject, 0);
        if (!hasGrade(studentId, subject)) throw new IllegalArgumentException("No grade for that subject.");
        try (PreparedStatement ps = db.conn.prepareStatement(
                "DELETE FROM grades WHERE student_id = ? AND subject = ?")) {
            ps.setString(1, studentId.toUpperCase());
            ps.setString(2, subject.trim());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
        audit.log(actor.id(), "DELETE_GRADE", studentId.toUpperCase() + " " + subject.trim());
    }

    private void checkGradeRequest(User actor, String studentId, String subject, double score) {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("Subject cannot be empty.");
        if (subject.trim().length() > 60) throw new IllegalArgumentException("Subject is too long (max 60).");
        if (!canGrade(actor, subject)) {
            audit.log(actor.id(), "DENIED", "grade change in " + subject.trim());
            throw new SecurityException(actor.is("TEACHER")
                    ? "You can only change grades for your own subject (" + actor.subject() + ")."
                    : "You are not allowed to change grades.");
        }
        if (!GradeCalculator.isValidScore(score)) throw new IllegalArgumentException("Score must be 0 to 100.");
        Optional<User> student = find(studentId);
        if (student.isEmpty() || !student.get().is("STUDENT")) throw new IllegalArgumentException("Student not found.");
    }

    private boolean hasGrade(String studentId, String subject) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT 1 FROM grades WHERE student_id = ? AND subject = ?")) {
            ps.setString(1, studentId.toUpperCase());
            ps.setString(2, subject.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
    }

    /** Grades the viewer is allowed to see for one student. */
    Map<String, Double> gradesVisibleTo(User viewer, String studentId) {
        if (viewer.is("STUDENT") && !viewer.id().equalsIgnoreCase(studentId))
            throw new SecurityException("Students can only see their own grades.");
        Map<String, Double> out = new LinkedHashMap<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT subject, score FROM grades WHERE student_id = ? ORDER BY subject")) {
            ps.setString(1, studentId.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String subject = rs.getString(1);
                    if (viewer.is("TEACHER") && !subject.equalsIgnoreCase(viewer.subject())) continue;
                    out.put(subject, rs.getDouble(2));
                }
            }
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
        return out;
    }

    /** Average and pass count per subject (a teacher only gets their own subject). */
    List<SubjectStat> classStats(User viewer) {
        if (viewer.is("STUDENT")) throw new SecurityException("Not allowed.");
        List<SubjectStat> out = new ArrayList<>();
        String sql = "SELECT subject, COUNT(*), AVG(score), SUM(CASE WHEN score >= ? THEN 1 ELSE 0 END) "
                + "FROM grades " + (viewer.is("TEACHER") ? "WHERE subject = ? " : "") + "GROUP BY subject ORDER BY subject";
        try (PreparedStatement ps = db.conn.prepareStatement(sql)) {
            ps.setDouble(1, Config.PASS_MARK);
            if (viewer.is("TEACHER")) ps.setString(2, viewer.subject());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new SubjectStat(rs.getString(1), rs.getInt(2), rs.getDouble(3), rs.getInt(4)));
            }
        } catch (SQLException e) {
            throw Database.wrap(e);
        }
        return out;
    }

    // ----- admin tools -----

    List<String> auditTrail(User actor, int limit) {
        requireAdmin(actor);
        return audit.recent(limit);
    }

    /** Writes a .sql file with INSERT statements for every table. Restore it by running it in an empty database. */
    Path backup(User actor) {
        requireAdmin(actor);
        try {
            Files.createDirectories(Path.of("backup"));
            Path target = Path.of("backup", "school_"
                    + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(clock.instant())
                    + ".sql");
            StringBuilder sb = new StringBuilder("-- Student management backup (contains password hashes: keep it private)\n");
            dumpTable(sb, "users", "id, name, role, subject, password_hash, failed_attempts, locked_until", "id");
            dumpTable(sb, "grades", "student_id, subject, score, updated_by, updated_at", "student_id, subject");
            dumpTable(sb, "audit_log", "ts, actor, action, details", "id");
            Files.writeString(target, sb.toString());
            audit.log(actor.id(), "BACKUP", target.toString());
            return target;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Backup failed: " + e.getMessage(), e);
        }
    }

    private void dumpTable(StringBuilder sb, String table, String columns, String orderBy) throws SQLException {
        try (Statement st = db.conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + columns + " FROM " + table + " ORDER BY " + orderBy)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                sb.append("INSERT INTO ").append(table).append(" (").append(columns).append(") VALUES (");
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    if (i > 1) sb.append(", ");
                    Object v = rs.getObject(i);
                    if (v == null) sb.append("NULL");
                    else if (v instanceof Number) sb.append(v);
                    else sb.append('\'').append(v.toString().replace("\\", "\\\\").replace("'", "''")).append('\'');
                }
                sb.append(");\n");
            }
        }
    }

    Path exportCsv(User actor) {
        requireAdmin(actor);
        StringBuilder sb = new StringBuilder("student_id,student_name,subject,score,letter,result\n");
        for (User s : students()) {
            for (Map.Entry<String, Double> g : gradesVisibleTo(actor, s.id()).entrySet()) {
                sb.append(csv(s.id())).append(',').append(csv(s.name())).append(',').append(csv(g.getKey())).append(',')
                        .append(g.getValue()).append(',').append(GradeCalculator.letter(g.getValue())).append(',')
                        .append(GradeCalculator.isPass(g.getValue()) ? "Pass" : "Fail").append('\n');
            }
        }
        try {
            Path file = Path.of("export_grades.csv");
            Files.writeString(file, sb.toString());
            audit.log(actor.id(), "EXPORT_CSV", file.toString());
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Export failed: " + e.getMessage(), e);
        }
    }

    void logShare(User actor, String what) {
        audit.log(actor.id(), "SHARE_REPORT", what);
    }

    private static String csv(String v) {
        String s = v.replace("\"", "\"\"");
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) s = "'" + s; // spreadsheet formula safety
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s + "\"" : s;
    }

    private static void requireAdmin(User actor) {
        if (!actor.is("ADMIN")) throw new SecurityException("Only an admin can do this.");
    }
}

// =====================================================================
//  Console user interface
// =====================================================================
public class StudentManagementSystem {
    private static final Scanner in = new Scanner(System.in);
    private static AuthService auth;
    private static SchoolService school;

    public static void main(String[] args) throws Exception {
        Clock clock = Clock.systemDefaultZone();
        String dbUser = System.getenv("SMS_DB_USER") != null ? System.getenv("SMS_DB_USER") : Config.DB_USER;
        String dbPassword = System.getenv("SMS_DB_PASSWORD");
        if (dbPassword == null) dbPassword = readPassword("MySQL password for user '" + dbUser + "': ");
        final Database db;
        try {
            db = new Database(Config.DB_SERVER_URL, dbUser, dbPassword, Config.DB_NAME);
        } catch (SQLException e) {
            System.out.println("Cannot connect to MySQL: " + e.getMessage());
            System.out.println("Check that MySQL is running, the user and password are right,");
            System.out.println("and that the mysql-connector-j jar is added to the project.");
            return;
        }
        try (db) {
            AuditLog audit = new AuditLog(db, clock);
            auth = new AuthService(db, clock, audit);
            school = new SchoolService(db, clock, audit);

            if (!auth.hasAdmin()) firstRunSetup();

            boolean running = true;
            while (running) {
                System.out.println("\n=== STUDENT MANAGEMENT SYSTEM ===");
                System.out.println("1. Login");
                System.out.println("0. Exit");
                switch (readInt("Choose: ")) {
                    case 1 -> login();
                    case 0 -> running = false;
                    default -> System.out.println("Invalid option.");
                }
            }
        }
        System.out.println("Goodbye!");
    }

    // ---------- Setup and login ----------

    private static void firstRunSetup() {
        System.out.println("=== FIRST-TIME SETUP: create the admin account ===");
        while (true) {
            String name = readText("Admin name: ");
            String password = readPassword("Admin password: ");
            try {
                String id = auth.createUser("setup", "ADMIN", name, null, password);
                System.out.println("Admin created. Your login ID is " + id);
                return;
            } catch (IllegalArgumentException e) {
                System.out.println(e.getMessage());
            }
        }
    }

    private static void login() {
        String id = readText("User ID: ");
        for (int attempt = 1; attempt <= Config.MAX_FAILED_LOGINS; attempt++) {
            AuthService.LoginResult r = auth.login(id, readPassword("Password: "));
            switch (r.status()) {
                case OK -> {
                    User me = r.user();
                    System.out.println("Welcome, " + me.name() + " (" + me.role() + ")");
                    switch (me.role()) {
                        case "ADMIN" -> adminMenu(me);
                        case "TEACHER" -> teacherMenu(me);
                        default -> studentMenu(me);
                    }
                    return;
                }
                case LOCKED -> {
                    System.out.println("This account is locked for " + AuthService.lockMinutes() + " minutes.");
                    return;
                }
                default -> System.out.println("Wrong ID or password.");
            }
        }
    }

    // ---------- Admin ----------

    private static void adminMenu(User me) {
        boolean active = true;
        while (active) {
            System.out.println("\n--- ADMIN MENU ---");
            System.out.println(" 1. Add user            8. Backup database");
            System.out.println(" 2. Delete user         9. Add grade");
            System.out.println(" 3. Update user        10. Update grade");
            System.out.println(" 4. View all users     11. Delete grade");
            System.out.println(" 5. Search user        12. View audit log");
            System.out.println(" 6. Reset password     13. Export grades to CSV");
            System.out.println(" 7. Share report       14. Change my password");
            System.out.println(" 0. Logout");
            int choice = readInt("Choose: ");
            switch (choice) {
                case 1 -> run(() -> addUser(me));
                case 2 -> run(() -> {
                    String id = readText("User ID to delete: ");
                    System.out.print("Delete " + id + "? (y/n): ");
                    if (in.nextLine().trim().equalsIgnoreCase("y")) {
                        school.deleteUser(me, id);
                        System.out.println("User deleted.");
                    } else {
                        System.out.println("Cancelled.");
                    }
                });
                case 3 -> run(() -> {
                    String id = readText("User ID to update: ");
                    String name = readText("New name (blank to keep): ");
                    String subject = readText("New subject, teachers only (blank to keep): ");
                    school.updateUser(me, id, name, subject);
                    System.out.println("User updated.");
                });
                case 4 -> run(() -> printUsers(school.allUsers()));
                case 5 -> run(() -> printUsers(school.search(readText("Search (ID or name): "), false)));
                case 6 -> run(() -> {
                    String id = readText("User ID: ");
                    auth.resetPassword(me, id, readPassword("New password: "));
                    System.out.println("Password reset.");
                });
                case 7 -> run(() -> {
                    StringBuilder sb = new StringBuilder("=== ALL USERS ===\n");
                    for (User u : school.allUsers()) sb.append(u).append('\n');
                    sb.append("\n=== STUDENT GRADES ===\n");
                    for (User s : school.students()) sb.append(studentReport(me, s)).append('\n');
                    share(me, "all_users_report.txt", sb.toString());
                });
                case 8 -> run(() -> System.out.println("Backup saved to " + school.backup(me)));
                case 9 -> run(() -> addGrade(me));
                case 10 -> run(() -> updateGrade(me));
                case 11 -> run(() -> deleteGrade(me));
                case 12 -> run(() -> school.auditTrail(me, 50).forEach(System.out::println));
                case 13 -> run(() -> System.out.println("Exported to " + school.exportCsv(me)));
                case 14 -> run(() -> changeOwnPassword(me));
                case 0 -> active = false;
                default -> System.out.println("Invalid option.");
            }
        }
    }

    private static void addUser(User me) {
        System.out.println("1. Student  2. Teacher  3. Admin");
        int type = readInt("Type: ");
        String role = type == 1 ? "STUDENT" : type == 2 ? "TEACHER" : type == 3 ? "ADMIN" : null;
        if (role == null) {
            System.out.println("Invalid type.");
            return;
        }
        String name = readText("Name: ");
        String subject = role.equals("TEACHER") ? readText("Subject taught: ") : null;
        String password = readPassword("Initial password: ");
        String id = auth.createUser(me.id(), role, name, subject, password);
        System.out.println(role + " created with ID " + id + ". Ask them to change the password after login.");
    }

    private static void printUsers(List<User> users) {
        if (users.isEmpty()) System.out.println("No users found.");
        for (User u : users) System.out.println(u);
    }

    // ---------- Teacher ----------

    private static void teacherMenu(User me) {
        boolean active = true;
        while (active) {
            System.out.println("\n--- TEACHER MENU (subject: " + me.subject() + ") ---");
            System.out.println("1. Add grade");
            System.out.println("2. Update grade");
            System.out.println("3. Delete grade");
            System.out.println("4. View students");
            System.out.println("5. Search student");
            System.out.println("6. Share grades");
            System.out.println("7. Class average");
            System.out.println("8. Change my password");
            System.out.println("0. Logout");
            switch (readInt("Choose: ")) {
                case 1 -> run(() -> addGrade(me));
                case 2 -> run(() -> updateGrade(me));
                case 3 -> run(() -> deleteGrade(me));
                case 4 -> run(() -> {
                    List<User> list = school.students();
                    if (list.isEmpty()) System.out.println("No students yet.");
                    for (User s : list) System.out.print(studentReport(me, s));
                });
                case 5 -> run(() -> {
                    List<User> list = school.search(readText("Search (ID or name): "), true);
                    if (list.isEmpty()) System.out.println("No students found.");
                    for (User s : list) System.out.print(studentReport(me, s));
                });
                case 6 -> run(() -> {
                    StringBuilder sb = new StringBuilder("=== " + me.subject().toUpperCase() + " GRADES ===\n");
                    for (User s : school.students()) sb.append(studentReport(me, s)).append('\n');
                    share(me, "grades_" + me.subject().replaceAll("\\W", "_") + ".txt", sb.toString());
                });
                case 7 -> run(() -> printStats(school.classStats(me)));
                case 8 -> run(() -> changeOwnPassword(me));
                case 0 -> active = false;
                default -> System.out.println("Invalid option.");
            }
        }
    }

    private static void printStats(List<SubjectStat> stats) {
        if (stats.isEmpty()) {
            System.out.println("No grades yet.");
            return;
        }
        System.out.printf("%-15s %8s %9s %8s%n", "Subject", "Students", "Average", "Passed");
        for (SubjectStat s : stats)
            System.out.printf("%-15s %8d %9.1f %8d%n", s.subject(), s.count(), s.average(), s.passed());
    }

    // ---------- Student ----------

    private static void studentMenu(User me) {
        boolean active = true;
        while (active) {
            System.out.println("\n--- STUDENT MENU (" + me.name() + ") ---");
            System.out.println("1. View profile");
            System.out.println("2. View grades");
            System.out.println("3. Search subject");
            System.out.println("4. Share report");
            System.out.println("5. Change my password");
            System.out.println("0. Logout");
            switch (readInt("Choose: ")) {
                case 1 -> System.out.println(me);
                case 2 -> run(() -> System.out.print(studentReport(me, me)));
                case 3 -> run(() -> {
                    String key = readText("Subject: ").toLowerCase();
                    boolean any = false;
                    for (var e : school.gradesVisibleTo(me, me.id()).entrySet()) {
                        if (e.getKey().toLowerCase().contains(key)) {
                            System.out.printf("%-15s %6.1f  %s%n", e.getKey(), e.getValue(), GradeCalculator.letter(e.getValue()));
                            any = true;
                        }
                    }
                    if (!any) System.out.println("No matching subject.");
                });
                case 4 -> run(() -> share(me, "report_" + me.id() + ".txt", studentReport(me, me)));
                case 5 -> run(() -> changeOwnPassword(me));
                case 0 -> active = false;
                default -> System.out.println("Invalid option.");
            }
        }
    }

    // ---------- Shared actions ----------

    private static void addGrade(User me) {
        String studentId = readText("Student ID: ");
        String subject = gradeSubject(me);
        school.addGrade(me, studentId, subject, readScore());
        System.out.println("Grade added.");
    }

    private static void updateGrade(User me) {
        String studentId = readText("Student ID: ");
        String subject = gradeSubject(me);
        school.updateGrade(me, studentId, subject, readScore());
        System.out.println("Grade updated.");
    }

    private static void deleteGrade(User me) {
        String studentId = readText("Student ID: ");
        school.deleteGrade(me, studentId, gradeSubject(me));
        System.out.println("Grade deleted.");
    }

    /** Teachers always grade their own subject; admins type the subject. */
    private static String gradeSubject(User me) {
        if (me.is("TEACHER")) {
            System.out.println("Subject: " + me.subject());
            return me.subject();
        }
        return readText("Subject: ");
    }

    private static void changeOwnPassword(User me) {
        String oldPw = readPassword("Current password: ");
        auth.changePassword(me, oldPw, readPassword("New password: "));
        System.out.println("Password changed.");
    }

    private static String studentReport(User viewer, User student) {
        Map<String, Double> grades = school.gradesVisibleTo(viewer, student.id());
        StringBuilder sb = new StringBuilder("Student: " + student.name() + " (" + student.id() + ")\n");
        if (grades.isEmpty()) return sb.append("  No grades yet.\n").toString();
        for (Map.Entry<String, Double> e : grades.entrySet()) {
            sb.append(String.format("  %-15s %6.1f  %s  %s%n", e.getKey(), e.getValue(),
                    GradeCalculator.letter(e.getValue()), GradeCalculator.isPass(e.getValue()) ? "Pass" : "Fail"));
        }
        if (!viewer.is("TEACHER")) { // teachers only see their own subject, so no overall figures
            double avg = GradeCalculator.average(grades.values());
            sb.append(String.format("  %-15s %6.1f%n", "Total", GradeCalculator.total(grades.values())));
            sb.append(String.format("  %-15s %6.1f  %s%n", "Average", avg, GradeCalculator.letter(avg)));
        }
        return sb.toString();
    }

    private static void share(User me, String fileName, String content) {
        try {
            Files.createDirectories(Path.of("reports"));
            Path file = Path.of("reports", fileName);
            Files.writeString(file, content);
            school.logShare(me, file.toString());
            System.out.println("Report saved to " + file);
        } catch (IOException e) {
            System.out.println("Could not write file: " + e.getMessage());
        }
    }

    // ---------- Input helpers ----------

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | SecurityException e) {
            System.out.println(e.getMessage());
        } catch (RuntimeException e) {
            System.out.println("Something went wrong: " + e.getMessage());
        }
    }

    private static int readInt(String prompt) {
        while (true) {
            System.out.print(prompt);
            try {
                return Integer.parseInt(in.nextLine().trim());
            } catch (NumberFormatException e) {
                System.out.println("Please enter a number.");
            }
        }
    }

    private static double readScore() {
        while (true) {
            System.out.print("Score (0-100): ");
            try {
                double v = Double.parseDouble(in.nextLine().trim());
                if (GradeCalculator.isValidScore(v)) return v;
            } catch (NumberFormatException ignored) {
            }
            System.out.println("Enter a number from 0 to 100.");
        }
    }

    private static String readText(String prompt) {
        System.out.print(prompt);
        String s = in.nextLine().trim();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    /** Hides typing when run in a real terminal. Some IDE consoles cannot hide it. */
    private static String readPassword(String prompt) {
        java.io.Console console = System.console();
        if (console != null) return new String(console.readPassword(prompt));
        System.out.print(prompt);
        return in.nextLine();
    }
}