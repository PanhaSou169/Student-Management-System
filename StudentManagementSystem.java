import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.TreeMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/*
 * =====================================================================
 * Student Management System (console version, MySQL + CSV dual-storage)
 *
 * Layers:
 *   Config, GradeCalculator, PasswordHasher   - pure rules
 *   Database, AuditLog                        - storage (MySQL)
 *   CsvStore                                  - storage (CSV mirror + import)
 *   AuthService, SchoolService                - business logic and permissions
 *   StudentManagementSystem                   - console UI only
 *
 * Features:
 *   • Grade components (Quiz/Midterm/Final...) with weights and comments
 *   • Weighted subject grades -> letter -> GPA points
 *   • Credit-weighted GPA and printable transcript
 *   • Top-of-class leaderboard, honor roll, failing-students report
 *   • Subject catalog with credits
 *   • Median / std-dev / pass-rate class statistics
 *   • Every mutation is mirrored to CSV under data/
 *   • Bootstrap: import CSVs back into an empty DB
 *
 * Data files (in data/, or $SMS_DATA_DIR):
 *   users.csv, subjects.csv, grade_components.csv, audit_log.csv
 * =====================================================================
 */

// =====================================================================
//  Settings
// =====================================================================
final class Config {
    static final String DB_SERVER_URL = "jdbc:mysql://localhost:3306/";
    static final String DB_NAME = "student_db";
    static final String DB_USER = "root";
    static final double PASS_MARK = 50.0;
    static final int MIN_PASSWORD_LENGTH = 8;
    static final int MAX_FAILED_LOGINS = 5;
    static final long LOCK_MINUTES = 5;
    static final double HONOR_ROLL_MIN = 85.0;
    static final double DEAN_LIST_MIN = 90.0;
    private Config() {}
}

// =====================================================================
//  Domain records
// =====================================================================
record User(String id, String name, String role, String subject) {
    boolean is(String r) { return role.equals(r); }

    @Override
    public String toString() {
        return String.format("%-6s %-20s %-8s%s", id, name, role,
                subject == null || subject.isBlank() ? "" : " (" + subject + ")");
    }
}

/** One grade component (Quiz 1, Midterm, Final...). */
record GradeComponent(long id, String studentId, String subject, String componentName,
                      double score, double weight, String gradedBy, long updatedAt, String comment) {
    @Override
    public String toString() {
        return String.format("%-15s %6.1f  w=%.1f%s", componentName, score, weight,
                comment == null || comment.isBlank() ? "" : "   // " + comment);
    }
}

/** Aggregated grade for one subject. */
record SubjectGrade(String subject, double score, String letter, String result,
                    double credits, double gpaPoints, int componentCount) {}

/** One leaderboard row. */
record StudentRank(int rank, String studentId, String name, double average, double gpa, int subjectCount) {
    @Override
    public String toString() {
        return String.format("#%-3d %-6s %-20s  avg=%.2f  gpa=%.2f  (%d subjects)",
                rank, studentId, name, average, gpa, subjectCount);
    }
}

/** Enriched per-subject statistics. */
record SubjectStat(String subject, int count, double average, double median, double stdDev,
                   double min, double max, int passed, double passRate) {}

// =====================================================================
//  Grade rules
// =====================================================================
final class GradeCalculator {
    private GradeCalculator() {}

    static boolean isValidScore(double s) { return !Double.isNaN(s) && s >= 0 && s <= 100; }

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

    static boolean isPass(double score) { return score >= Config.PASS_MARK; }

    static double gpaPoints(String letter) {
        return switch (letter) {
            case "A" -> 4.0;
            case "B" -> 3.0;
            case "C" -> 2.0;
            case "D" -> 1.0;
            case "E" -> 0.5;
            default -> 0.0;
        };
    }

    static double weightedAverage(List<GradeComponent> components) {
        double wSum = 0, wWeighted = 0;
        for (GradeComponent c : components) {
            wSum += c.score() * c.weight();
            wWeighted += c.weight();
        }
        return wWeighted == 0 ? 0 : wSum / wWeighted;
    }
}

// =====================================================================
//  Password hashing (PBKDF2 + random salt)
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
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("Password hashing failed", e);
        }
    }
}

// =====================================================================
//  Database (schema + legacy migration)
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
            st.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "id VARCHAR(10) NOT NULL PRIMARY KEY,"
                    + "name VARCHAR(100) NOT NULL,"
                    + "role ENUM('ADMIN','TEACHER','STUDENT') NOT NULL,"
                    + "subject VARCHAR(60) NULL,"
                    + "password_hash VARCHAR(255) NOT NULL,"
                    + "failed_attempts INT NOT NULL DEFAULT 0,"
                    + "locked_until BIGINT NOT NULL DEFAULT 0"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");

            st.execute("CREATE TABLE IF NOT EXISTS subjects ("
                    + "name VARCHAR(60) NOT NULL PRIMARY KEY,"
                    + "credits DOUBLE NOT NULL DEFAULT 1.0,"
                    + "description VARCHAR(200) NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");

            st.execute("CREATE TABLE IF NOT EXISTS grade_components ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "student_id VARCHAR(10) NOT NULL,"
                    + "subject VARCHAR(60) NOT NULL,"
                    + "component_name VARCHAR(40) NOT NULL,"
                    + "score DOUBLE NOT NULL,"
                    + "weight DOUBLE NOT NULL DEFAULT 1.0,"
                    + "graded_by VARCHAR(10) NULL,"
                    + "updated_at BIGINT NOT NULL,"
                    + "comment VARCHAR(300) NULL,"
                    + "UNIQUE KEY uq_component (student_id, subject, component_name),"
                    + "CONSTRAINT fk_gc_student FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE,"
                    + "CONSTRAINT chk_gc_score CHECK (score >= 0 AND score <= 100),"
                    + "CONSTRAINT chk_gc_weight CHECK (weight > 0)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");

            st.execute("CREATE TABLE IF NOT EXISTS audit_log ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "ts BIGINT NOT NULL,"
                    + "actor VARCHAR(10) NULL,"
                    + "action VARCHAR(30) NOT NULL,"
                    + "details VARCHAR(500) NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }

        // One-time migration from the old single-score "grades" table
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW TABLES LIKE 'grades'")) {
            if (rs.next()) {
                try (Statement st2 = conn.createStatement()) {
                    st2.execute("INSERT IGNORE INTO grade_components "
                            + "(student_id, subject, component_name, score, weight, graded_by, updated_at) "
                            + "SELECT student_id, subject, 'Final', score, 1.0, updated_by, updated_at FROM grades");
                    st2.execute("DROP TABLE grades");
                }
            }
        }
    }

    @Override
    public void close() throws SQLException { conn.close(); }

    static RuntimeException wrap(SQLException e) {
        return new IllegalStateException("Database error: " + e.getMessage(), e);
    }

    static User mapUser(ResultSet rs) throws SQLException {
        return new User(rs.getString("id"), rs.getString("name"), rs.getString("role"), rs.getString("subject"));
    }
}

// =====================================================================
//  Audit log
// =====================================================================
final class AuditLog {
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Database db;
    private final Clock clock;

    AuditLog(Database db, Clock clock) { this.db = db; this.clock = clock; }

    void log(String actor, String action, String details) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO audit_log(ts, actor, action, details) VALUES (?,?,?,?)")) {
            ps.setLong(1, clock.millis());
            ps.setString(2, actor);
            ps.setString(3, action);
            ps.setString(4, details);
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
    }

    List<String> recent(int limit) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT ts, actor, action, details FROM audit_log ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(String.format("%s  %-6s %-18s %s",
                            FORMAT.format(java.time.Instant.ofEpochMilli(rs.getLong("ts"))),
                            rs.getString("actor"), rs.getString("action"),
                            rs.getString("details") == null ? "" : rs.getString("details")));
                }
            }
        } catch (SQLException e) { throw Database.wrap(e); }
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
        this.db = db; this.clock = clock; this.audit = audit;
    }

    boolean hasAdmin() {
        try (Statement st = db.conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) { throw Database.wrap(e); }
    }

    String createUser(String actor, String role, String name, String subject, String password) {
        if (!role.equals("ADMIN") && !role.equals("TEACHER") && !role.equals("STUDENT"))
            throw new IllegalArgumentException("Unknown role.");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name cannot be empty.");
        if (name.trim().length() > 100) throw new IllegalArgumentException("Name is too long (max 100).");
        if (role.equals("TEACHER") && (subject == null || subject.isBlank()))
            throw new IllegalArgumentException("A teacher needs a subject.");
        if (subject != null && subject.trim().length() > 60)
            throw new IllegalArgumentException("Subject is too long (max 60).");
        String weak = PasswordHasher.checkStrength(password);
        if (weak != null) throw new IllegalArgumentException(weak);

        String prefix = role.substring(0, 1);
        try {
            int next;
            try (PreparedStatement ps = db.conn.prepareStatement(
                    "SELECT COALESCE(MAX(CAST(SUBSTR(id, 2) AS UNSIGNED)), 0) FROM users WHERE role = ?")) {
                ps.setString(1, role);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); next = rs.getInt(1) + 1; }
            }
            String id = String.format("%s%03d", prefix, next);
            try (PreparedStatement ps = db.conn.prepareStatement(
                    "INSERT INTO users(id, name, role, subject, password_hash) VALUES (?,?,?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, name.trim());
                ps.setString(3, role);
                ps.setString(4, role.equals("TEACHER") && subject != null ? subject.trim() : null);
                ps.setString(5, PasswordHasher.hash(password));
                ps.executeUpdate();
            }
            audit.log(actor, "ADD_USER", id + " " + role + " " + name.trim());
            return id;
        } catch (SQLException e) { throw Database.wrap(e); }
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
        } catch (SQLException e) { throw Database.wrap(e); }

        if (user == null) {
            PasswordHasher.verify(password, DUMMY_HASH);
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
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) { throw Database.wrap(e); }
    }

    private void storeHash(String id, String password) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "UPDATE users SET password_hash = ? WHERE UPPER(id) = UPPER(?)")) {
            ps.setString(1, PasswordHasher.hash(password));
            ps.setString(2, id.trim());
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
    }

    private void setAttempts(String id, int failed, long lockedUntil) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "UPDATE users SET failed_attempts = ?, locked_until = ? WHERE id = ?")) {
            ps.setInt(1, failed);
            ps.setLong(2, lockedUntil);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
    }

    private static String abbreviate(String s) {
        String t = s.replaceAll("[\\r\\n]", " ").trim();
        return t.length() > 30 ? t.substring(0, 30) + "..." : t;
    }
}

// =====================================================================
//  Business logic: users, grades, rankings, permissions
// =====================================================================
final class SchoolService {
    private final Database db;
    private final Clock clock;
    private final AuditLog audit;

    SchoolService(Database db, Clock clock, AuditLog audit) {
        this.db = db; this.clock = clock; this.audit = audit;
    }

    // ----- users -----

    Optional<User> find(String id) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT id, name, role, subject FROM users WHERE UPPER(id) = UPPER(?)")) {
            ps.setString(1, id.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(Database.mapUser(rs)) : Optional.empty();
            }
        } catch (SQLException e) { throw Database.wrap(e); }
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
        } catch (SQLException e) { throw Database.wrap(e); }
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
        } catch (SQLException e) { throw Database.wrap(e); }
        audit.log(actor.id(), "UPDATE_USER", target.id());
    }

    void deleteUser(User actor, String id) {
        requireAdmin(actor);
        User target = find(id).orElseThrow(() -> new IllegalArgumentException("User not found."));
        if (target.id().equals(actor.id())) throw new IllegalArgumentException("You cannot delete your own account.");
        if (target.is("ADMIN") && countAdmins() <= 1) throw new IllegalArgumentException("You cannot delete the last admin.");
        try (PreparedStatement ps = db.conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setString(1, target.id());
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
        audit.log(actor.id(), "DELETE_USER", target.id() + " " + target.name());
    }

    private int countAdmins() {
        try (Statement st = db.conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) { throw Database.wrap(e); }
    }

    // ----- subjects -----

    void ensureSubject(String subject) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT IGNORE INTO subjects(name, credits) VALUES (?, 1.0)")) {
            ps.setString(1, subject.trim());
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
    }

    double creditsOf(String subject) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT credits FROM subjects WHERE name = ?")) {
            ps.setString(1, subject);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getDouble(1) : 1.0; }
        } catch (SQLException e) { throw Database.wrap(e); }
    }

    void setSubjectCredits(User actor, String subject, double credits) {
        requireAdmin(actor);
        if (credits <= 0 || credits > 20) throw new IllegalArgumentException("Credits must be between 0 and 20.");
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO subjects(name, credits) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE credits = VALUES(credits)")) {
            ps.setString(1, subject.trim());
            ps.setDouble(2, credits);
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
        audit.log(actor.id(), "SET_CREDITS", subject.trim() + " = " + credits);
    }

    List<String[]> subjects() {
        List<String[]> out = new ArrayList<>();
        try (Statement st = db.conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, credits FROM subjects ORDER BY name")) {
            while (rs.next()) out.add(new String[] {rs.getString(1), String.valueOf(rs.getDouble(2))});
        } catch (SQLException e) { throw Database.wrap(e); }
        return out;
    }

    // ----- grade components -----

    static boolean canGrade(User actor, String subject) {
        if (actor.is("ADMIN")) return true;
        return actor.is("TEACHER") && actor.subject() != null
                && actor.subject().equalsIgnoreCase(subject.trim());
    }

    List<GradeComponent> listComponents(User viewer, String studentId, String subject) {
        checkCanView(viewer, studentId);
        List<GradeComponent> out = new ArrayList<>();
        String sql = "SELECT id, student_id, subject, component_name, score, weight, graded_by, updated_at, comment "
                + "FROM grade_components WHERE UPPER(student_id) = UPPER(?)";
        String normalizedSubject = subject == null ? null : subject.trim();
        boolean withSubject = normalizedSubject != null && !normalizedSubject.isBlank();
        if (withSubject) sql += " AND subject = ?";
        sql += " ORDER BY subject, component_name";
        try (PreparedStatement ps = db.conn.prepareStatement(sql)) {
            ps.setString(1, studentId.trim());
            if (withSubject) ps.setString(2, normalizedSubject);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GradeComponent c = new GradeComponent(
                            rs.getLong("id"), rs.getString("student_id"), rs.getString("subject"),
                            rs.getString("component_name"), rs.getDouble("score"), rs.getDouble("weight"),
                            rs.getString("graded_by"), rs.getLong("updated_at"), rs.getString("comment"));
                        if (viewer.is("TEACHER") && (c.subject() == null || viewer.subject() == null
                            || !c.subject().equalsIgnoreCase(viewer.subject()))) continue;
                    out.add(c);
                }
            }
        } catch (SQLException e) { throw Database.wrap(e); }
        return out;
    }

    void addComponent(User actor, String studentId, String subject, String componentName,
                      double score, double weight, String comment) {
        checkGradeRequest(actor, studentId, subject, score, weight, componentName);
        ensureSubject(subject);
        if (existsComponent(studentId, subject, componentName))
            throw new IllegalArgumentException("Component '" + componentName + "' already exists. Use update.");
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO grade_components(student_id, subject, component_name, score, weight, "
                        + "graded_by, updated_at, comment) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, studentId.toUpperCase());
            ps.setString(2, subject.trim());
            ps.setString(3, componentName.trim());
            ps.setDouble(4, score);
            ps.setDouble(5, weight);
            ps.setString(6, actor.id());
            ps.setLong(7, clock.millis());
            ps.setString(8, comment == null || comment.isBlank() ? null : comment.trim());
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
        audit.log(actor.id(), "ADD_COMPONENT",
                studentId.toUpperCase() + " " + subject.trim() + "/" + componentName + " = " + score);
    }

    void updateComponent(User actor, String studentId, String subject, String componentName,
                         double score, double weight, String comment) {
        checkGradeRequest(actor, studentId, subject, score, weight, componentName);
        if (!existsComponent(studentId, subject, componentName))
            throw new IllegalArgumentException("Component not found.");
        try (PreparedStatement ps = db.conn.prepareStatement(
                "UPDATE grade_components SET score = ?, weight = ?, graded_by = ?, updated_at = ?, comment = ? "
                        + "WHERE UPPER(student_id) = UPPER(?) AND subject = ? AND component_name = ?")) {
            ps.setDouble(1, score);
            ps.setDouble(2, weight);
            ps.setString(3, actor.id());
            ps.setLong(4, clock.millis());
            ps.setString(5, comment == null || comment.isBlank() ? null : comment.trim());
            ps.setString(6, studentId.trim());
            ps.setString(7, subject.trim());
            ps.setString(8, componentName.trim());
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
        audit.log(actor.id(), "UPDATE_COMPONENT",
                studentId.toUpperCase() + " " + subject.trim() + "/" + componentName + " = " + score);
    }

    void deleteComponent(User actor, String studentId, String subject, String componentName) {
        checkGradeRequest(actor, studentId, subject, 0, 1, componentName);
        if (!existsComponent(studentId, subject, componentName))
            throw new IllegalArgumentException("Component not found.");
        try (PreparedStatement ps = db.conn.prepareStatement(
                "DELETE FROM grade_components WHERE UPPER(student_id) = UPPER(?) AND subject = ? AND component_name = ?")) {
            ps.setString(1, studentId.trim());
            ps.setString(2, subject.trim());
            ps.setString(3, componentName.trim());
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
        audit.log(actor.id(), "DELETE_COMPONENT",
                studentId.toUpperCase() + " " + subject.trim() + "/" + componentName);
    }

    void deleteSubjectGrade(User actor, String studentId, String subject) {
        checkGradeRequest(actor, studentId, subject, 0, 1, "any");
        try (PreparedStatement ps = db.conn.prepareStatement(
                "DELETE FROM grade_components WHERE UPPER(student_id) = UPPER(?) AND subject = ?")) {
            ps.setString(1, studentId.trim());
            ps.setString(2, subject.trim());
            ps.executeUpdate();
        } catch (SQLException e) { throw Database.wrap(e); }
        audit.log(actor.id(), "DELETE_SUBJECT_GRADE", studentId.toUpperCase() + " " + subject.trim());
    }

    private boolean existsComponent(String studentId, String subject, String componentName) {
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT 1 FROM grade_components WHERE UPPER(student_id) = UPPER(?) AND subject = ? AND component_name = ?")) {
            ps.setString(1, studentId.trim());
            ps.setString(2, subject.trim());
            ps.setString(3, componentName.trim());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { throw Database.wrap(e); }
    }

    private void checkGradeRequest(User actor, String studentId, String subject,
                                   double score, double weight, String componentName) {
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("Subject cannot be empty.");
        if (subject.trim().length() > 60) throw new IllegalArgumentException("Subject is too long.");
        if (componentName == null || componentName.isBlank())
            throw new IllegalArgumentException("Component name cannot be empty.");
        if (componentName.trim().length() > 40) throw new IllegalArgumentException("Component name is too long.");
        if (!canGrade(actor, subject)) {
            audit.log(actor.id(), "DENIED", "grade change in " + subject.trim());
            throw new SecurityException(actor.is("TEACHER")
                    ? "You can only change grades for your own subject (" + actor.subject() + ")."
                    : "You are not allowed to change grades.");
        }
        if (!GradeCalculator.isValidScore(score))
            throw new IllegalArgumentException("Score must be 0 to 100.");
        if (Double.isNaN(weight) || weight <= 0 || weight > 100)
            throw new IllegalArgumentException("Weight must be between 0 and 100.");
        Optional<User> student = find(studentId);
        if (student.isEmpty() || !student.get().is("STUDENT"))
            throw new IllegalArgumentException("Student not found.");
    }

    // ----- aggregation -----

    SubjectGrade getSubjectGrade(User viewer, String studentId, String subject) {
        List<GradeComponent> components = listComponents(viewer, studentId, subject);
        if (components.isEmpty()) return null;
        double finalScore = GradeCalculator.weightedAverage(components);
        String letter = GradeCalculator.letter(finalScore);
        String result = GradeCalculator.isPass(finalScore) ? "Pass" : "Fail";
        double credits = creditsOf(subject);
        return new SubjectGrade(subject, finalScore, letter, result,
                credits, GradeCalculator.gpaPoints(letter), components.size());
    }

    List<SubjectGrade> getAllSubjectGrades(User viewer, String studentId) {
        List<String> subjects = new ArrayList<>();
        try (PreparedStatement ps = db.conn.prepareStatement(
                "SELECT DISTINCT subject FROM grade_components WHERE UPPER(student_id) = UPPER(?) ORDER BY subject")) {
            ps.setString(1, studentId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String s = rs.getString(1);
                    if (viewer.is("TEACHER") && !s.equalsIgnoreCase(viewer.subject())) continue;
                    subjects.add(s);
                }
            }
        } catch (SQLException e) { throw Database.wrap(e); }

        List<SubjectGrade> out = new ArrayList<>();
        for (String s : subjects) {
            SubjectGrade g = getSubjectGrade(viewer, studentId, s);
            if (g != null) out.add(g);
        }
        return out;
    }

    double gpaOf(List<SubjectGrade> grades) {
        double creditsSum = 0, pointsSum = 0;
        for (SubjectGrade g : grades) {
            creditsSum += g.credits();
            pointsSum += g.gpaPoints() * g.credits();
        }
        return creditsSum == 0 ? 0 : pointsSum / creditsSum;
    }

    // ----- rankings -----

    List<StudentRank> leaderboard(User viewer) {
        if (viewer.is("STUDENT")) throw new SecurityException("Students can only see the top 5.");
        return buildLeaderboard(viewer, Integer.MAX_VALUE);
    }

    List<StudentRank> topN(User viewer, int n) {
        return buildLeaderboard(viewer, n);
    }

    private List<StudentRank> buildLeaderboard(User viewer, int limit) {
        record Row(String id, String name, double avg, double gpa, int count) {}
        List<Row> rows = new ArrayList<>();
        for (User s : students()) {
            List<SubjectGrade> grades = getAllSubjectGrades(viewer, s.id());
            if (grades.isEmpty()) continue;
            double avg = grades.stream().mapToDouble(SubjectGrade::score).average().orElse(0);
            double gpa = gpaOf(grades);
            rows.add(new Row(s.id(), s.name(), avg, gpa, grades.size()));
        }
        rows.sort(Comparator.comparingDouble(Row::avg).reversed()
                .thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));

        List<StudentRank> result = new ArrayList<>();
        int currentRank = 0;
        double prevAvg = Double.NaN;
        int count = 0;
        for (int i = 0; i < rows.size() && count < limit; i++, count++) {
            Row r = rows.get(i);
            if (i == 0 || r.avg() != prevAvg) currentRank = i + 1;
            prevAvg = r.avg();
            result.add(new StudentRank(currentRank, r.id(), r.name(), r.avg(), r.gpa(), r.count()));
        }
        return result;
    }

    Optional<StudentRank> rankOf(User viewer, String studentId) {
        for (StudentRank r : buildLeaderboard(viewer, Integer.MAX_VALUE)) {
            if (r.studentId().equalsIgnoreCase(studentId)) return Optional.of(r);
        }
        return Optional.empty();
    }

    List<StudentRank> honorRoll(User viewer) {
        List<StudentRank> out = new ArrayList<>();
        for (StudentRank r : leaderboard(viewer))
            if (r.average() >= Config.HONOR_ROLL_MIN) out.add(r);
        return out;
    }

    List<StudentRank> failingStudents(User viewer) {
        if (viewer.is("STUDENT")) throw new SecurityException("Not allowed.");
        List<StudentRank> out = new ArrayList<>();
        for (StudentRank r : buildLeaderboard(viewer, Integer.MAX_VALUE)) {
            for (SubjectGrade g : getAllSubjectGrades(viewer, r.studentId())) {
                if (!GradeCalculator.isPass(g.score())) { out.add(r); break; }
            }
        }
        return out;
    }

    // ----- stats -----

    List<SubjectStat> classStats(User viewer) {
        if (viewer.is("STUDENT")) throw new SecurityException("Not allowed.");
        Map<String, List<Double>> bySubject = new TreeMap<>();
        for (User s : students()) {
            for (SubjectGrade g : getAllSubjectGrades(viewer, s.id())) {
                if (viewer.is("TEACHER") && !g.subject().equalsIgnoreCase(viewer.subject())) continue;
                bySubject.computeIfAbsent(g.subject(), k -> new ArrayList<>()).add(g.score());
            }
        }
        List<SubjectStat> out = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : bySubject.entrySet()) {
            List<Double> scores = e.getValue();
            Collections.sort(scores);
            int n = scores.size();
            double avg = GradeCalculator.average(scores);
            double median;
            if (n % 2 == 0) {
                median = (scores.get(n / 2 - 1) + scores.get(n / 2)) / 2.0;
            } else {
                Double middleScore = scores.get(n / 2);
                if (middleScore == null) throw new IllegalStateException("Score cannot be null.");
                median = middleScore;
            }
            double variance = 0;
            for (double s : scores) variance += (s - avg) * (s - avg);
            variance /= n;
            double stdDev = Math.sqrt(variance);
            double min = scores.get(0);
            double max = scores.get(n - 1);
            int passed = 0;
            for (double s : scores) if (GradeCalculator.isPass(s)) passed++;
            double passRate = 100.0 * passed / n;
            out.add(new SubjectStat(e.getKey(), n, avg, median, stdDev, min, max, passed, passRate));
        }
        return out;
    }

    // ----- transcript -----

    String transcript(User viewer, String studentId) {
        checkCanView(viewer, studentId);
        User s = find(studentId).orElseThrow(() -> new IllegalArgumentException("Student not found."));
        List<SubjectGrade> grades = getAllSubjectGrades(viewer, studentId);
        StringBuilder sb = new StringBuilder();
        sb.append("=============================================\n");
        sb.append(" TRANSCRIPT\n");
        sb.append(" Student: ").append(s.name()).append(" (").append(s.id()).append(")\n");
        sb.append("=============================================\n");
        if (grades.isEmpty()) {
            sb.append(" No grades on record.\n");
        } else {
            sb.append(String.format("%-20s %6s %5s %5s %6s%n", "Subject", "Score", "Ltr", "Cr", "GPA"));
            double creditsSum = 0, pointsSum = 0;
            for (SubjectGrade g : grades) {
                sb.append(String.format("%-20s %6.1f %5s %5.1f %6.2f%n",
                        g.subject(), g.score(), g.letter(), g.credits(), g.gpaPoints()));
                creditsSum += g.credits();
                pointsSum += g.gpaPoints() * g.credits();
            }
            double avg = grades.stream().mapToDouble(SubjectGrade::score).average().orElse(0);
            double gpa = creditsSum == 0 ? 0 : pointsSum / creditsSum;
            sb.append("---------------------------------------------\n");
            sb.append(String.format(" Average: %.2f    GPA: %.2f    Credits: %.1f%n", avg, gpa, creditsSum));
        }
        return sb.toString();
    }

    // ----- admin tools -----

    List<String> auditTrail(User actor, int limit) {
        requireAdmin(actor);
        return audit.recent(limit);
    }

    Path backup(User actor) {
        requireAdmin(actor);
        try {
            Files.createDirectories(Path.of("backup"));
            Path target = Path.of("backup", "school_"
                    + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(clock.instant())
                    + ".sql");
            StringBuilder sb = new StringBuilder("-- backup (contains hashed passwords: keep it private)\n");
            dumpTable(sb, "users", "id, name, role, subject, password_hash, failed_attempts, locked_until", "id");
            dumpTable(sb, "subjects", "name, credits, description", "name");
            dumpTable(sb, "grade_components",
                    "student_id, subject, component_name, score, weight, graded_by, updated_at, comment",
                    "student_id, subject, component_name");
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
        StringBuilder sb = new StringBuilder(
                "student_id,student_name,subject,component,score,weight,graded_by,comment\n");
        for (User s : students()) {
            for (GradeComponent c : listComponents(actor, s.id(), null)) {
                sb.append(csv(s.id())).append(',').append(csv(s.name())).append(',').append(csv(c.subject())).append(',')
                        .append(csv(c.componentName())).append(',').append(c.score()).append(',').append(c.weight()).append(',')
                        .append(csv(c.gradedBy() == null ? "" : c.gradedBy())).append(',')
                        .append(csv(c.comment() == null ? "" : c.comment())).append('\n');
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

    private static void requireAdmin(User actor) {
        if (!actor.is("ADMIN")) throw new SecurityException("Only an admin can do this.");
    }

    private static void checkCanView(User viewer, String studentId) {
        if (viewer.is("STUDENT") && !viewer.id().equalsIgnoreCase(studentId))
            throw new SecurityException("Students can only see their own grades.");
    }

    private static String csv(String v) {
        String s = v.replace("\"", "\"\"");
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) s = "'" + s;
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s + "\"" : s;
    }
}

// =====================================================================
//  CSV mirror + import
// =====================================================================
final class CsvStore {
    private final Path dir;

    CsvStore(Path dir) throws IOException {
        this.dir = dir.toAbsolutePath();
        Files.createDirectories(dir);
    }

    Path directory() { return dir; }

    boolean hasData() {
        return Files.exists(dir.resolve("users.csv"))
            && fileHasDataRows(dir.resolve("users.csv"));
    }

    // ----- export -----

    void exportAll(Database db) throws SQLException, IOException {
        exportUsers(db);
        exportSubjects(db);
        exportGrades(db);
        exportAudit(db);
    }

    void exportUsers(Database db) throws SQLException, IOException {
        List<String> lines = new ArrayList<>();
        lines.add("id,name,role,subject,password_hash,failed_attempts,locked_until");
        try (Statement st = db.conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT id, name, role, subject, password_hash, failed_attempts, locked_until " +
                 "FROM users ORDER BY id")) {
            while (rs.next()) {
                lines.add(String.join(",",
                        csv(rs.getString("id")),
                        csv(rs.getString("name")),
                        csv(rs.getString("role")),
                        csv(rs.getString("subject")),
                        csv(rs.getString("password_hash")),
                        Integer.toString(rs.getInt("failed_attempts")),
                        Long.toString(rs.getLong("locked_until"))));
            }
        }
        Files.write(dir.resolve("users.csv"), lines);
    }

    void exportSubjects(Database db) throws SQLException, IOException {
        List<String> lines = new ArrayList<>();
        lines.add("name,credits,description");
        try (Statement st = db.conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, credits, description FROM subjects ORDER BY name")) {
            while (rs.next()) {
                lines.add(String.join(",",
                        csv(rs.getString("name")),
                        Double.toString(rs.getDouble("credits")),
                        csv(rs.getString("description"))));
            }
        }
        Files.write(dir.resolve("subjects.csv"), lines);
    }

    void exportGrades(Database db) throws SQLException, IOException {
        List<String> lines = new ArrayList<>();
        lines.add("student_id,subject,component_name,score,weight,graded_by,updated_at,comment");
        try (Statement st = db.conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT student_id, subject, component_name, score, weight, graded_by, updated_at, comment " +
                 "FROM grade_components ORDER BY student_id, subject, component_name")) {
            while (rs.next()) {
                lines.add(String.join(",",
                        csv(rs.getString("student_id")),
                        csv(rs.getString("subject")),
                        csv(rs.getString("component_name")),
                        Double.toString(rs.getDouble("score")),
                        Double.toString(rs.getDouble("weight")),
                        csv(rs.getString("graded_by")),
                        Long.toString(rs.getLong("updated_at")),
                        csv(rs.getString("comment"))));
            }
        }
        Files.write(dir.resolve("grade_components.csv"), lines);
    }

    void exportAudit(Database db) throws SQLException, IOException {
        List<String> lines = new ArrayList<>();
        lines.add("ts,actor,action,details");
        try (Statement st = db.conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT ts, actor, action, details FROM audit_log ORDER BY id")) {
            while (rs.next()) {
                lines.add(String.join(",",
                        Long.toString(rs.getLong("ts")),
                        csv(rs.getString("actor")),
                        csv(rs.getString("action")),
                        csv(rs.getString("details"))));
            }
        }
        Files.write(dir.resolve("audit_log.csv"), lines);
    }

    // ----- import (idempotent) -----

    void importAll(Database db) throws SQLException, IOException {
        boolean prevAutoCommit = db.conn.getAutoCommit();
        db.conn.setAutoCommit(false);
        try {
            int u = importUsers(db);
            int s = importSubjects(db);
            int g = importGrades(db);
            int a = importAudit(db);
            db.conn.commit();
            System.out.printf(
                "Imported from CSV: %d users, %d subjects, %d grade components, %d audit rows.%n",
                u, s, g, a);
        } catch (SQLException | IOException | RuntimeException e) {
            db.conn.rollback();
            throw e;
        } finally {
            db.conn.setAutoCommit(prevAutoCommit);
        }
    }

    private int importUsers(Database db) throws SQLException, IOException {
        List<String[]> rows = readCsv(dir.resolve("users.csv"));
        if (rows.isEmpty()) return 0;
        rows.remove(0);
        int count = 0;
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO users(id, name, role, subject, password_hash, failed_attempts, locked_until) " +
                "VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE " +
                "name=VALUES(name), role=VALUES(role), subject=VALUES(subject), " +
                "password_hash=VALUES(password_hash), failed_attempts=VALUES(failed_attempts), " +
                "locked_until=VALUES(locked_until)")) {
            for (String[] r : rows) {
                if (r.length < 7) continue;
                ps.setString(1, r[0]);
                ps.setString(2, r[1]);
                ps.setString(3, r[2]);
                ps.setString(4, blankToNull(r[3]));
                ps.setString(5, r[4]);
                ps.setInt(6, parseInt(r[5]));
                ps.setLong(7, parseLong(r[6]));
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    private int importSubjects(Database db) throws SQLException, IOException {
        List<String[]> rows = readCsv(dir.resolve("subjects.csv"));
        if (rows.isEmpty()) return 0;
        rows.remove(0);
        int count = 0;
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO subjects(name, credits, description) VALUES (?,?,?) " +
                "ON DUPLICATE KEY UPDATE credits=VALUES(credits), description=VALUES(description)")) {
            for (String[] r : rows) {
                if (r.length < 3) continue;
                ps.setString(1, r[0]);
                ps.setDouble(2, r[1].isEmpty() ? 1.0 : Double.parseDouble(r[1]));
                ps.setString(3, blankToNull(r[2]));
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    private int importGrades(Database db) throws SQLException, IOException {
        List<String[]> rows = readCsv(dir.resolve("grade_components.csv"));
        if (rows.isEmpty()) return 0;
        rows.remove(0);
        int count = 0;
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO grade_components " +
                "(student_id, subject, component_name, score, weight, graded_by, updated_at, comment) " +
                "VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE " +
                "score=VALUES(score), weight=VALUES(weight), graded_by=VALUES(graded_by), " +
                "updated_at=VALUES(updated_at), comment=VALUES(comment)")) {
            for (String[] r : rows) {
                if (r.length < 8) continue;
                ps.setString(1, r[0]);
                ps.setString(2, r[1]);
                ps.setString(3, r[2]);
                ps.setDouble(4, Double.parseDouble(r[3]));
                ps.setDouble(5, r[4].isEmpty() ? 1.0 : Double.parseDouble(r[4]));
                ps.setString(6, blankToNull(r[5]));
                ps.setLong(7, parseLong(r[6]));
                ps.setString(8, blankToNull(r[7]));
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    private int importAudit(Database db) throws SQLException, IOException {
        List<String[]> rows = readCsv(dir.resolve("audit_log.csv"));
        if (rows.isEmpty()) return 0;
        rows.remove(0);
        int count = 0;
        try (PreparedStatement ps = db.conn.prepareStatement(
                "INSERT INTO audit_log(ts, actor, action, details) " +
                "SELECT ?,?,?,? FROM DUAL WHERE NOT EXISTS (" +
                "  SELECT 1 FROM audit_log WHERE ts=? AND actor<=>? AND action=? AND details<=>?" +
                ")")) {
            for (String[] r : rows) {
                if (r.length < 4) continue;
                long ts = parseLong(r[0]);
                String actor = blankToNull(r[1]);
                String action = r[2];
                String details = blankToNull(r[3]);
                ps.setLong(1, ts);
                ps.setString(2, actor);
                ps.setString(3, action);
                ps.setString(4, details);
                ps.setLong(5, ts);
                ps.setString(6, actor);
                ps.setString(7, action);
                ps.setString(8, details);
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    // ----- helpers -----

    private static List<String[]> readCsv(Path file) throws IOException {
        if (!Files.exists(file)) return new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        List<String[]> out = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(parseCsvLine(line));
        }
        return out;
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQ) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQ = false;
                } else cur.append(c);
            } else {
                switch (c) {
                    case '"' -> inQ = true;
                    case ',' -> { fields.add(cur.toString()); cur.setLength(0); }
                    default -> cur.append(c);
                }
            }
        }
        fields.add(cur.toString());
        return fields.toArray(String[]::new);
    }

    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r"))
            return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    private static boolean fileHasDataRows(Path p) {
        try {
            return Files.readAllLines(p).stream()
                    .skip(1)
                    .anyMatch(l -> !l.isBlank() && !l.startsWith("#"));
        } catch (IOException e) { return false; }
    }

    private static String blankToNull(String s) { return s == null || s.isEmpty() ? null : s; }
    private static int parseInt(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }
    private static long parseLong(String s) { try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; } }
}

// =====================================================================
//  Console UI
// =====================================================================
public class StudentManagementSystem {
    private static final Scanner in = new Scanner(System.in);
    private static AuthService auth;
    private static SchoolService school;
    private static CsvStore csvStore;
    private static Database db;

    public static void main(String[] args) throws Exception {
        Clock clock = Clock.systemDefaultZone();
        String dbServerUrl = env("SMS_DB_URL", Config.DB_SERVER_URL);
        String dbName = env("SMS_DB_NAME", Config.DB_NAME);
        String dbUser = env("SMS_DB_USER", Config.DB_USER);
        String dbPassword = System.getenv("SMS_DB_PASSWORD");
        if (dbPassword == null) dbPassword = readPassword("MySQL password for user '" + dbUser + "': ");
        Path dataDir = Path.of(System.getenv().getOrDefault("SMS_DATA_DIR", "data"));

        try {
            db = new Database(dbServerUrl, dbUser, dbPassword, dbName);
        } catch (SQLException e) {
            System.out.println("Cannot connect to MySQL: " + e.getMessage());
            return;
        }

        try (Database database = db) {
            csvStore = new CsvStore(dataDir);
            AuditLog audit = new AuditLog(database, clock);
            auth = new AuthService(db, clock, audit);
            school = new SchoolService(db, clock, audit);

            System.out.println("SQL:       " + dbServerUrl + dbName);
            System.out.println("CSV files: " + csvStore.directory());

            if (!auth.hasAdmin() && csvStore.hasData()) {
                System.out.println("Detected existing CSV data in " + csvStore.directory() + ".");
                if (readText("Import it into the database? (y/n): ").equalsIgnoreCase("y")) {
                    try { csvStore.importAll(db); }
                    catch (SQLException | IOException | RuntimeException e) {
                        System.out.println("Import failed: " + e.getMessage());
                    }
                } else {
                    System.out.println("Skipped import. Existing CSVs will be OVERWRITTEN on next save.");
                }
            }
            if (!auth.hasAdmin()) firstRunSetup();
            syncCsv();

            try {
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
            } finally {
                syncCsv();
            }
        }
        System.out.println("Goodbye!");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    // ---------- dual-storage helpers ----------

    private static void syncCsv() {
        try {
            csvStore.exportAll(db);
        } catch (SQLException | IOException e) {
            System.out.println("WARNING: CSV sync failed: " + e.getMessage());
        }
    }

    private static void mutate(Runnable action) {
        run(() -> {
            action.run();
            syncCsv();
        });
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
                syncCsv();
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
            System.out.println(" 1. Add user            11. Add grade component");
            System.out.println(" 2. Delete user         12. Update grade component");
            System.out.println(" 3. Update user         13. Delete grade component");
            System.out.println(" 4. View all users      14. View audit log");
            System.out.println(" 5. Search user         15. Export report CSV");
            System.out.println(" 6. Reset password      16. Backup SQL dump");
            System.out.println(" 7. Share report        17. Leaderboard (top of class)");
            System.out.println(" 8. Manage subjects     18. Honor roll");
            System.out.println(" 9. View student grades 19. Failing students");
            System.out.println("10. Change my password  20. Transcript");
            System.out.println("21. Sync all tables to CSV files");
            System.out.println("22. Import CSV files into database");
            System.out.println("23. Show CSV folder");
            System.out.println(" 0. Logout");
            int choice = readInt("Choose: ");
            switch (choice) {
                case 1  -> mutate(() -> addUser(me));
                case 2  -> mutate(() -> {
                    String id = readText("User ID to delete: ");
                    if (readText("Delete " + id + "? (y/n): ").equalsIgnoreCase("y")) {
                        school.deleteUser(me, id);
                        System.out.println("User deleted.");
                    } else System.out.println("Cancelled.");
                });
                case 3  -> mutate(() -> {
                    String id = readText("User ID to update: ");
                    school.updateUser(me, id,
                            readText("New name (blank to keep): "),
                            readText("New subject (teachers only, blank to keep): "));
                    System.out.println("User updated.");
                });
                case 4  -> run(() -> printUsers(school.allUsers()));
                case 5  -> run(() -> printUsers(school.search(readText("Search (ID or name): "), false)));
                case 6  -> mutate(() -> {
                    String id = readText("User ID: ");
                    auth.resetPassword(me, id, readPassword("New password: "));
                    System.out.println("Password reset.");
                });
                case 7  -> run(() -> {
                    StringBuilder sb = new StringBuilder("=== ALL USERS ===\n");
                    for (User u : school.allUsers()) sb.append(u).append('\n');
                    sb.append("\n=== TRANSCRIPTS ===\n");
                    for (User s : school.students()) sb.append(school.transcript(me, s.id())).append('\n');
                    share(me, "all_users_report.txt", sb.toString());
                });
                case 8  -> mutate(() -> manageSubjects(me));
                case 9  -> run(() -> System.out.print(school.transcript(me, readText("Student ID: "))));
                case 10 -> mutate(() -> changeOwnPassword(me));
                case 11 -> mutate(() -> addGradeComponent(me));
                case 12 -> mutate(() -> updateGradeComponent(me));
                case 13 -> mutate(() -> deleteGradeComponent(me));
                case 14 -> run(() -> school.auditTrail(me, 50).forEach(System.out::println));
                case 15 -> run(() -> System.out.println("Exported to " + school.exportCsv(me)));
                case 16 -> mutate(() -> System.out.println("Backup saved to " + school.backup(me)));
                case 17 -> run(() -> school.leaderboard(me).forEach(System.out::println));
                case 18 -> run(() -> {
                    var hr = school.honorRoll(me);
                    if (hr.isEmpty()) System.out.println("Nobody on the honor roll yet (avg >= " + Config.HONOR_ROLL_MIN + ").");
                    else hr.forEach(r -> System.out.println(
                            (r.average() >= Config.DEAN_LIST_MIN ? "Dean's List: " : "") + r));
                });
                case 19 -> run(() -> {
                    var fs = school.failingStudents(me);
                    if (fs.isEmpty()) System.out.println("No failing students.");
                    else fs.forEach(System.out::println);
                });
                case 20 -> run(() -> System.out.print(school.transcript(me, readText("Student ID: "))));
                case 21 -> run(() -> { syncCsv(); System.out.println("CSV files written to " + csvStore.directory()); });
                case 22 -> run(() -> {
                    if (!readText("Import will overwrite matching rows. Continue? (y/n): ").equalsIgnoreCase("y")) {
                        System.out.println("Cancelled."); return;
                    }
                    try {
                        csvStore.importAll(db);
                        syncCsv();
                    } catch (SQLException | IOException e) {
                        System.out.println("Import failed: " + e.getMessage());
                    }
                });
                case 23 -> System.out.println("CSV files live in: " + csvStore.directory());
                case 0  -> active = false;
                default -> System.out.println("Invalid option.");
            }
        }
    }

    private static void addUser(User me) {
        System.out.println("1. Student  2. Teacher  3. Admin");
        int type = readInt("Type: ");
        String role = type == 1 ? "STUDENT" : type == 2 ? "TEACHER" : type == 3 ? "ADMIN" : null;
        if (role == null) { System.out.println("Invalid type."); return; }
        String name = readText("Name: ");
        String subject = role.equals("TEACHER") ? readText("Subject taught: ") : null;
        String password = readPassword("Initial password: ");
        String id = auth.createUser(me.id(), role, name, subject, password);
        System.out.println(role + " created with ID " + id);
    }

    private static void printUsers(List<User> users) {
        if (users.isEmpty()) System.out.println("No users found.");
        for (User u : users) System.out.println(u);
    }

    private static void manageSubjects(User me) {
        System.out.println("Current subjects and credits:");
        List<String[]> subs = school.subjects();
        if (subs.isEmpty()) System.out.println("  (none yet)");
        for (String[] s : subs) System.out.printf("  %-20s credits=%.1f%n", s[0], Double.parseDouble(s[1]));
        String subject = readText("Subject name to add/update credits: ");
        System.out.print("Credits (e.g. 1, 2, 3): ");
        double credits;
        try { credits = Double.parseDouble(in.nextLine().trim()); }
        catch (NumberFormatException e) { System.out.println("Invalid credits."); return; }
        school.setSubjectCredits(me, subject, credits);
        System.out.println("Subject '" + subject + "' set to " + credits + " credits.");
    }

    // ---------- Teacher ----------

    private static void teacherMenu(User me) {
        boolean active = true;
        while (active) {
            System.out.println("\n--- TEACHER MENU (subject: " + me.subject() + ") ---");
            System.out.println("1. Add grade component");
            System.out.println("2. Update grade component");
            System.out.println("3. Delete grade component");
            System.out.println("4. View students & grades");
            System.out.println("5. Search student");
            System.out.println("6. Share grades");
            System.out.println("7. Class statistics");
            System.out.println("8. Leaderboard (top of class)");
            System.out.println("9. Change my password");
            System.out.println("0. Logout");
            switch (readInt("Choose: ")) {
                case 1 -> mutate(() -> addGradeComponent(me));
                case 2 -> mutate(() -> updateGradeComponent(me));
                case 3 -> mutate(() -> deleteGradeComponent(me));
                case 4 -> run(() -> {
                    for (User s : school.students()) System.out.print(school.transcript(me, s.id()));
                });
                case 5 -> run(() -> {
                    for (User s : school.search(readText("Search: "), true))
                        System.out.print(school.transcript(me, s.id()));
                });
                case 6 -> run(() -> {
                    StringBuilder sb = new StringBuilder();
                    for (User s : school.students()) sb.append(school.transcript(me, s.id())).append('\n');
                    share(me, "grades_" + me.subject().replaceAll("\\W", "_") + ".txt", sb.toString());
                });
                case 7 -> run(() -> printStats(school.classStats(me)));
                case 8 -> run(() -> school.leaderboard(me).forEach(System.out::println));
                case 9 -> mutate(() -> changeOwnPassword(me));
                case 0 -> active = false;
                default -> System.out.println("Invalid option.");
            }
        }
    }

    private static void printStats(List<SubjectStat> stats) {
        if (stats.isEmpty()) { System.out.println("No grades yet."); return; }
        System.out.printf("%-15s %5s %7s %7s %6s %6s %6s %7s%n",
                "Subject", "N", "Avg", "Median", "Std", "Min", "Max", "Pass%");
        for (SubjectStat s : stats)
            System.out.printf("%-15s %5d %7.2f %7.2f %6.2f %6.1f %6.1f %6.1f%%%n",
                    s.subject(), s.count(), s.average(), s.median(),
                    s.stdDev(), s.min(), s.max(), s.passRate());
    }

    // ---------- Student ----------

    private static void studentMenu(User me) {
        boolean active = true;
        while (active) {
            System.out.println("\n--- STUDENT MENU (" + me.name() + ") ---");
            System.out.println("1. View profile");
            System.out.println("2. View my grades / transcript");
            System.out.println("3. My rank");
            System.out.println("4. Top 5 of the class");
            System.out.println("5. Share my transcript");
            System.out.println("6. Change my password");
            System.out.println("0. Logout");
            switch (readInt("Choose: ")) {
                case 1 -> System.out.println(me);
                case 2 -> run(() -> System.out.print(school.transcript(me, me.id())));
                case 3 -> run(() -> {
                    var rank = school.rankOf(me, me.id());
                    if (rank.isEmpty()) System.out.println("You have no grades yet.");
                    else System.out.println(rank.get());
                });
                case 4 -> run(() -> {
                    var top = school.topN(me, 5);
                    if (top.isEmpty()) System.out.println("No grades in the class yet.");
                    else top.forEach(System.out::println);
                });
                case 5 -> run(() -> share(me, "transcript_" + me.id() + ".txt", school.transcript(me, me.id())));
                case 6 -> mutate(() -> changeOwnPassword(me));
                case 0 -> active = false;
                default -> System.out.println("Invalid option.");
            }
        }
    }

    // ---------- Shared actions ----------

    private static void addGradeComponent(User me) {
        String studentId = readText("Student ID: ");
        String subject = gradeSubject(me);
        String component = readText("Component name (blank = Final): ");
        if (component.isBlank()) component = "Final";
        double score = readScore();
        double weight = readWeight();
        String comment = readText("Comment (optional): ", 200);
        school.addComponent(me, studentId, subject, component, score, weight, comment);
        System.out.println("Grade component added.");
    }

    private static void updateGradeComponent(User me) {
        String studentId = readText("Student ID: ");
        String subject = gradeSubject(me);
        List<GradeComponent> existing = school.listComponents(me, studentId, subject);
        if (existing.isEmpty()) { System.out.println("No components exist."); return; }
        System.out.println("Existing components:");
        for (GradeComponent c : existing) System.out.println("  " + c);
        String compName = readText("Component to update: ");
        GradeComponent target = null;
        for (GradeComponent c : existing)
            if (c.componentName().equalsIgnoreCase(compName)) { target = c; break; }
        if (target == null) { System.out.println("Component not found."); return; }
        double score = readScore();
        double weight = readWeightOrDefault(target.weight());
        String comment = readText("Comment (blank = keep current): ", 200);
        if (comment.isBlank()) comment = target.comment();
        school.updateComponent(me, studentId, subject, target.componentName(), score, weight, comment);
        System.out.println("Updated.");
    }

    private static void deleteGradeComponent(User me) {
        String studentId = readText("Student ID: ");
        String subject = gradeSubject(me);
        List<GradeComponent> existing = school.listComponents(me, studentId, subject);
        if (existing.isEmpty()) { System.out.println("No components exist."); return; }
        System.out.println("Existing components:");
        for (GradeComponent c : existing) System.out.println("  " + c);
        String compName = readText("Component to delete (blank = delete whole subject grade): ");
        if (compName.isBlank()) {
            if (readText("Delete ALL components for this subject? (y/n): ").equalsIgnoreCase("y")) {
                school.deleteSubjectGrade(me, studentId, subject);
                System.out.println("Whole subject grade deleted.");
            } else System.out.println("Cancelled.");
        } else {
            school.deleteComponent(me, studentId, subject, compName);
            System.out.println("Component deleted.");
        }
    }

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
            try { return Integer.parseInt(in.nextLine().trim()); }
            catch (NumberFormatException e) { System.out.println("Please enter a number."); }
        }
    }

    private static double readScore() {
        while (true) {
            System.out.print("Score (0-100): ");
            try {
                double v = Double.parseDouble(in.nextLine().trim());
                if (GradeCalculator.isValidScore(v)) return v;
            } catch (NumberFormatException ignored) {}
            System.out.println("Enter a number from 0 to 100.");
        }
    }

    private static double readWeight() {
        while (true) {
            System.out.print("Weight (blank = 1.0): ");
            String s = in.nextLine().trim();
            if (s.isEmpty()) return 1.0;
            try {
                double v = Double.parseDouble(s);
                if (v > 0 && v <= 100) return v;
            } catch (NumberFormatException ignored) {}
            System.out.println("Enter a positive number (max 100).");
        }
    }

    private static double readWeightOrDefault(double current) {
        while (true) {
            System.out.print("Weight (blank = " + current + "): ");
            String s = in.nextLine().trim();
            if (s.isEmpty()) return current;
            try {
                double v = Double.parseDouble(s);
                if (v > 0 && v <= 100) return v;
            } catch (NumberFormatException ignored) {}
            System.out.println("Enter a positive number (max 100).");
        }
    }

    private static String readText(String prompt) { return readText(prompt, 60); }

    private static String readText(String prompt, int maxLen) {
        System.out.print(prompt);
        String s = in.nextLine().trim();
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private static String readPassword(String prompt) {
        java.io.Console console = System.console();
        if (console != null) return new String(console.readPassword(prompt));
        System.out.print(prompt);
        return in.nextLine();
    }
}
