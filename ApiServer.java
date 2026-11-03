import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/*
 * Web API for the UPGRADED Student Management System.
 *
 * Compile together with StudentManagementSystem.java (+ CsvStore inside it):
 *
 *     javac -cp ".:mysql-connector-j.jar" *.java
 *     java  -cp ".:mysql-connector-j.jar" ApiServer
 *
 * Then open http://localhost:8080
 *
 * Every mutation is written to MySQL first, then mirrored to data/*.csv
 * exactly like the console app.
 *
 * Auth: POST /api/login returns a random bearer token. Every other request must
 * send "Authorization: Bearer <token>". The server looks the user up on each
 * call, so role changes and deletions take effect immediately.
 */
public class ApiServer {
    static final String VERSION = "2.0.0-sms";

    static final int PORT = Integer.parseInt(env("PORT", "8080"));
    static final boolean ONLINE = System.getenv("PORT") != null;
    static final boolean LOG_REQUESTS = "true".equalsIgnoreCase(env("SMS_LOG_REQUESTS", "false"));
    static final long SESSION_MS = 2 * 60 * 60 * 1000L;
    static final SecureRandom RANDOM = new SecureRandom();
    static final Scanner IN = new Scanner(System.in);

    /** Whitelist of files that the static handler will serve. */
    static final Set<String> STATIC_EXT = Set.of("html", "js", "css", "ico", "svg", "png", "woff2");

    /** Login rate limit — 10 attempts per minute per IP. */
    static final int LOGIN_RATE_LIMIT = 10;
    static final long LOGIN_WINDOW_MS = 60_000L;
    static final Map<String, Deque<Long>> loginHits = new ConcurrentHashMap<>();

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    record Session(String userId, long expires) {}

    static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    static class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }

    static Database db;
    static CsvStore csvStore;
    static AuthService auth;
    static SchoolService school;

    // ================================================================
    //  Start-up
    // ================================================================
    public static void main(String[] args) throws Exception {
        Clock clock = Clock.systemDefaultZone();
        String dbUser = env("SMS_DB_USER", Config.DB_USER);
        String dbPassword = System.getenv("SMS_DB_PASSWORD");
        if (dbPassword == null) dbPassword = readSecret("MySQL password for user '" + dbUser + "': ");
        Path dataDir = Path.of(env("SMS_DATA_DIR", "data"));

        try {
            db = new Database(env("SMS_DB_URL", Config.DB_SERVER_URL),
                    dbUser, dbPassword, env("SMS_DB_NAME", Config.DB_NAME));
        } catch (SQLException e) {
            System.out.println("Cannot connect to MySQL: " + e.getMessage());
            System.out.println("Check MySQL is running, user/password are right, and the connector jar is on the classpath.");
            return;
        }

        csvStore = new CsvStore(dataDir);
        AuditLog audit = new AuditLog(db, clock);
        auth = new AuthService(db, clock, audit);
        school = new SchoolService(db, clock, audit);

        System.out.println("API:       " + VERSION);
        System.out.println("SQL:       " + env("SMS_DB_URL", Config.DB_SERVER_URL) + env("SMS_DB_NAME", Config.DB_NAME));
        System.out.println("CSV files: " + csvStore.directory());

        if (!auth.hasAdmin() && csvStore.hasData()) {
            if ("true".equalsIgnoreCase(System.getenv("SMS_IMPORT_CSV"))) {
                try { csvStore.importAll(db); System.out.println("Imported from CSV."); }
                catch (Exception e) { System.out.println("CSV import failed: " + e.getMessage()); }
            } else if (!ONLINE) {
                System.out.print("Detected existing CSV data. Import it? (y/n): ");
                if (IN.nextLine().trim().equalsIgnoreCase("y")) {
                    try { csvStore.importAll(db); }
                    catch (Exception e) { System.out.println("Import failed: " + e.getMessage()); }
                }
            }
        }
        if (!auth.hasAdmin()) firstRunSetup();
        syncCsv();

        InetSocketAddress address = ONLINE
                ? new InetSocketAddress(PORT)
                : new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT);
        HttpServer server = HttpServer.create(address, 0);
        server.createContext("/api/", ApiServer::handleApi);
        server.createContext("/", ApiServer::handleStatic);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            try { syncCsv(); } catch (Exception ignored) {}
            try { db.close(); } catch (Exception ignored) {}
        }));

        System.out.println("Server running -> http://localhost:" + PORT + "   (Ctrl+C to stop)");
    }

    static void firstRunSetup() {
        String envPassword = System.getenv("SMS_ADMIN_PASSWORD");
        if (envPassword != null) {
            String id = auth.createUser("setup", "ADMIN", env("SMS_ADMIN_NAME", "Administrator"), null, envPassword);
            System.out.println("Admin created. Login ID: " + id
                    + " (now remove SMS_ADMIN_PASSWORD from the host settings)");
            syncCsv();
            return;
        }
        System.out.println("=== FIRST-TIME SETUP: create the admin account ===");
        while (true) {
            System.out.print("Admin name: ");
            String name = IN.nextLine().trim();
            String password = readSecret("Admin password: ");
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

    static String readSecret(String prompt) {
        java.io.Console c = System.console();
        if (c != null) return new String(c.readPassword(prompt));
        System.out.print(prompt);
        return IN.nextLine();
    }

    // ================================================================
    //  Dual-storage helpers
    // ================================================================
    static void syncCsv() {
        if (db == null || csvStore == null) return;
        try { csvStore.exportAll(db); }
        catch (Exception e) { System.err.println("CSV sync failed: " + e.getMessage()); }
    }

    static void mutate(Runnable action) {
        action.run();
        syncCsv();
    }

    // ================================================================
    //  HTTP entry point
    // ================================================================
    static void handleApi(HttpExchange ex) throws IOException {
        long start = System.currentTimeMillis();
        int code = 200;
        try {
            cors(ex);
            String method = ex.getRequestMethod();
            if (method.equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); code = 204; return; }

            String full = ex.getRequestURI().getPath();
            if (!full.startsWith("/api")) throw new ApiException(404, "Not found.");
            String path = full.length() > 4 ? full.substring(4) : "/";
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());

            // ---- unauthenticated ----
            if (method.equals("GET") && path.equals("/health")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("status", "OK");
                m.put("version", VERSION);
                m.put("time", System.currentTimeMillis());
                m.put("csvFolder", csvStore.directory().toString());
                json(ex, 200, m); return;
            }
            if (method.equals("GET") && path.equals("/version")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("version", VERSION);
                m.put("sessions", sessions.size());
                m.put("uptimeMs", System.currentTimeMillis());
                json(ex, 200, m); return;
            }
            if (method.equals("POST") && path.equals("/login")) { login(ex); return; }

            User me = authenticate(ex);

            // ---- logout / me ----
            if (method.equals("POST") && path.equals("/logout")) {
                sessions.remove(token(ex)); ok(ex); return;
            }
            if (method.equals("GET") && path.equals("/me")) {
                json(ex, 200, userToMap(me)); return;
            }

            // ---- users ----
            if (method.equals("GET") && path.equals("/users")) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (User u : school.allUsers()) out.add(userToMap(u));
                json(ex, 200, out); return;
            }
            if (method.equals("GET") && path.startsWith("/users/") && !path.equals("/users/search")) {
                String id = path.substring("/users/".length());
                Optional<User> u = school.find(id);
                if (u.isEmpty()) throw new ApiException(404, "User not found.");
                json(ex, 200, userToMap(u.get())); return;
            }
            if (method.equals("GET") && path.equals("/users/search")) {
                String keyword = q.getOrDefault("q", "");
                boolean studentsOnly = "true".equals(q.getOrDefault("students", "false"));
                List<Map<String, Object>> out = new ArrayList<>();
                for (User u : school.search(keyword, studentsOnly)) out.add(userToMap(u));
                json(ex, 200, out); return;
            }
            if (method.equals("POST") && path.equals("/users")) {
                if (!me.is("ADMIN")) throw new ApiException(403, "Only an admin can create users.");
                Map<String, Object> b = body(ex);
                String newId = auth.createUser(me.id(), str(b, "role").toUpperCase(),
                        str(b, "name"), str(b, "subject"), str(b, "password"));
                syncCsv();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "OK");
                out.put("id", newId);
                json(ex, 201, out); return;
            }
            if (method.equals("POST") && path.equals("/users/update")) {
                Map<String, Object> b = body(ex);
                mutate(() -> school.updateUser(me, str(b, "id"), str(b, "name"), str(b, "subject")));
                ok(ex); return;
            }
            if (method.equals("POST") && path.equals("/users/delete")) {
                Map<String, Object> b = body(ex);
                mutate(() -> school.deleteUser(me, str(b, "id")));
                ok(ex); return;
            }
            if (method.equals("POST") && path.equals("/users/reset-password")) {
                Map<String, Object> b = body(ex);
                mutate(() -> auth.resetPassword(me, str(b, "id"), str(b, "password")));
                ok(ex); return;
            }
            if (method.equals("POST") && path.equals("/users/change-password")) {
                Map<String, Object> b = body(ex);
                mutate(() -> auth.changePassword(me, str(b, "oldPassword"), str(b, "newPassword")));
                ok(ex); return;
            }

            // ---- subjects ----
            if (method.equals("GET") && path.equals("/subjects")) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (String[] s : school.subjects()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", s[0]);
                    m.put("credits", Double.parseDouble(s[1]));
                    out.add(m);
                }
                json(ex, 200, out); return;
            }
            if (method.equals("POST") && path.equals("/subjects")) {
                Map<String, Object> b = body(ex);
                mutate(() -> school.setSubjectCredits(me, str(b, "name"), num(b, "credits")));
                ok(ex); return;
            }
            if (method.equals("POST") && path.equals("/subjects/delete")) {
                if (!me.is("ADMIN")) throw new ApiException(403, "Only an admin can delete subjects.");
                Map<String, Object> b = body(ex);
                String subject = str(b, "name");
                if (subject.isBlank()) throw new ApiException(400, "Subject name required.");
                mutate(() -> school.setSubjectCredits(me, subject, 1.0)); // placeholder;
                // actual deletion is a two-step: refuse if in use, then delete
                try (var ps = db.conn.prepareStatement(
                        "SELECT COUNT(*) FROM grade_components WHERE subject = ?")) {
                    ps.setString(1, subject);
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) > 0)
                            throw new ApiException(409, "Subject still has grades. Delete those first.");
                    }
                    try (var del = db.conn.prepareStatement("DELETE FROM subjects WHERE name = ?")) {
                        del.setString(1, subject);
                        del.executeUpdate();
                    }
                } catch (SQLException e) { throw Database.wrap(e); }
                syncCsv(); ok(ex); return;
            }

            // ---- grades: read ----
            if (method.equals("GET") && path.startsWith("/grades/student/")) {
                String rest = path.substring("/grades/student/".length());
                if (rest.endsWith("/components")) {
                    String id = rest.substring(0, rest.length() - "/components".length());
                    String subject = q.get("subject");
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (GradeComponent c : school.listComponents(me, id, subject)) out.add(componentToMap(c));
                    json(ex, 200, out); return;
                }
                if (rest.endsWith("/rank")) {
                    String id = rest.substring(0, rest.length() - "/rank".length());
                    Optional<StudentRank> rank = school.rankOf(me, id);
                    if (rank.isEmpty()) throw new ApiException(404, "No grades yet for this student.");
                    json(ex, 200, rankToMap(rank.get())); return;
                }
                List<Map<String, Object>> out = new ArrayList<>();
                for (SubjectGrade g : school.getAllSubjectGrades(me, rest)) out.add(subjectGradeToMap(g));
                json(ex, 200, out); return;
            }
            if (method.equals("GET") && path.equals("/components")) {
                if (!me.is("ADMIN")) throw new ApiException(403, "Admin only.");
                List<Map<String, Object>> out = new ArrayList<>();
                for (User s : school.students())
                    for (GradeComponent c : school.listComponents(me, s.id(), null))
                        out.add(componentToMap(c));
                json(ex, 200, out); return;
            }

            // ---- grades: write ----
            if (method.equals("POST") && path.equals("/grades/component/add")) {
                Map<String, Object> b = body(ex);
                Double w = numOpt(b, "weight");
                double weight = w != null ? w : 1.0;
                String comment = str(b, "comment");
                mutate(() -> school.addComponent(me, str(b, "studentId"), str(b, "subject"),
                        str(b, "componentName"), num(b, "score"), weight, comment));
                ok(ex); return;
            }
            if (method.equals("POST") && path.equals("/grades/component/update")) {
                Map<String, Object> b = body(ex);
                Double w = numOpt(b, "weight");
                String comment = str(b, "comment");
                mutate(() -> {
                    if (w != null) {
                        school.updateComponent(me, str(b, "studentId"), str(b, "subject"),
                                str(b, "componentName"), num(b, "score"), w, comment);
                    } else {
                        List<GradeComponent> existing = school.listComponents(me,
                                str(b, "studentId"), str(b, "subject"));
                        double keep = 1.0;
                        for (GradeComponent c : existing)
                            if (c.componentName().equalsIgnoreCase(str(b, "componentName")))
                                keep = c.weight();
                        school.updateComponent(me, str(b, "studentId"), str(b, "subject"),
                                str(b, "componentName"), num(b, "score"), keep, comment);
                    }
                });
                ok(ex); return;
            }
            if (method.equals("POST") && path.equals("/grades/component/delete")) {
                Map<String, Object> b = body(ex);
                mutate(() -> school.deleteComponent(me, str(b, "studentId"),
                        str(b, "subject"), str(b, "componentName")));
                ok(ex); return;
            }
            if (method.equals("POST") && path.equals("/grades/subject/delete")) {
                Map<String, Object> b = body(ex);
                mutate(() -> school.deleteSubjectGrade(me, str(b, "studentId"), str(b, "subject")));
                ok(ex); return;
            }

            // ---- reports ----
            if (method.equals("GET") && path.startsWith("/transcript/")) {
                String id = path.substring("/transcript/".length());
                String text = school.transcript(me, id);
                send(ex, 200, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (method.equals("GET") && path.equals("/leaderboard")) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (StudentRank r : school.leaderboard(me)) out.add(rankToMap(r));
                json(ex, 200, out); return;
            }
            if (method.equals("GET") && path.startsWith("/top/")) {
                int n;
                try { n = Math.max(1, Math.min(100, Integer.parseInt(path.substring("/top/".length())))); }
                catch (NumberFormatException e) { n = 10; }
                List<Map<String, Object>> out = new ArrayList<>();
                for (StudentRank r : school.topN(me, n)) out.add(rankToMap(r));
                json(ex, 200, out); return;
            }
            if (method.equals("GET") && path.equals("/honor-roll")) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (StudentRank r : school.honorRoll(me)) out.add(rankToMap(r));
                json(ex, 200, out); return;
            }
            if (method.equals("GET") && path.equals("/failing")) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (StudentRank r : school.failingStudents(me)) out.add(rankToMap(r));
                json(ex, 200, out); return;
            }
            if (method.equals("GET") && path.equals("/stats")) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (SubjectStat s : school.classStats(me)) out.add(statToMap(s));
                json(ex, 200, out); return;
            }
            if (method.equals("GET") && path.startsWith("/stats/student/")) {
                String id = path.substring("/stats/student/".length());
                List<Map<String, Object>> out = new ArrayList<>();
                for (SubjectGrade g : school.getAllSubjectGrades(me, id)) out.add(subjectGradeToMap(g));
                json(ex, 200, out); return;
            }
            if (method.equals("GET") && path.equals("/audit")) {
                int limit;
                try { limit = Math.max(1, Math.min(200, Integer.parseInt(q.getOrDefault("limit", "20")))); }
                catch (NumberFormatException e) { limit = 20; }
                json(ex, 200, school.auditTrail(me, limit)); return;
            }

            // ---- exports / dual-storage ----
            if (method.equals("GET") && path.equals("/export/csv")) {
                sendFile(ex, school.exportCsv(me), "text/csv"); return;
            }
            if (method.equals("GET") && path.equals("/export/backup")) {
                sendFile(ex, school.backup(me), "application/sql"); return;
            }
            if (method.equals("POST") && path.equals("/sync/csv")) {
                if (!me.is("ADMIN")) throw new ApiException(403, "Only an admin can sync.");
                syncCsv();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "OK");
                out.put("folder", csvStore.directory().toString());
                json(ex, 200, out); return;
            }
            if (method.equals("POST") && path.equals("/sync/import")) {
                if (!me.is("ADMIN")) throw new ApiException(403, "Only an admin can import.");
                try { csvStore.importAll(db); syncCsv(); ok(ex); }
                catch (Exception e) { throw new ApiException(500, "Import failed: " + e.getMessage()); }
                return;
            }

            throw new ApiException(404, "Not found.");
        } catch (ApiException e) {
            code = e.status;
            error(ex, e.status, e.getMessage());
        } catch (IllegalArgumentException e) {
            code = 400;
            error(ex, 400, e.getMessage());
        } catch (SecurityException e) {
            code = 403;
            error(ex, 403, e.getMessage());
        } catch (Exception e) {
            code = 500;
            e.printStackTrace();
            error(ex, 500, "Server error. Check the server console.");
        } finally {
            if (LOG_REQUESTS) {
                long ms = System.currentTimeMillis() - start;
                System.err.printf("%-6s %-40s %d  %dms%n",
                        ex.getRequestMethod(), ex.getRequestURI().getPath(), code, ms);
            }
            ex.close();
        }
    }

    // ================================================================
    //  Login + rate limit
    // ================================================================
    static void login(HttpExchange ex) throws IOException {
        String ip = ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
        if (!allowLogin(ip))
            throw new ApiException(429, "Too many attempts. Try again in a minute.");

        Map<String, Object> b = body(ex);
        String id = str(b, "id");
        String password = str(b, "password");
        if (id.isBlank() || password.isEmpty()) throw new ApiException(400, "Enter your ID and password.");
        AuthService.LoginResult r = auth.login(id, password);
        syncCsv();
        switch (r.status()) {
            case OK -> {
                byte[] raw = new byte[32];
                RANDOM.nextBytes(raw);
                String tok = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
                sessions.put(tok, new Session(r.user().id(), System.currentTimeMillis() + SESSION_MS));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "OK");
                out.put("token", tok);
                out.put("user", userToMap(r.user()));
                json(ex, 200, out);
            }
            case LOCKED -> error(ex, 423, "Account locked for " + AuthService.lockMinutes() + " minutes.");
            default -> error(ex, 401, "Wrong ID or password.");
        }
    }

    static boolean allowLogin(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> q = loginHits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > LOGIN_WINDOW_MS) q.pollFirst();
            if (q.size() >= LOGIN_RATE_LIMIT) return false;
            q.addLast(now);
            return true;
        }
    }

    // ================================================================
    //  Sessions
    // ================================================================
    static String token(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
    }

    static User authenticate(HttpExchange ex) {
        String t = token(ex);
        Session s = t == null ? null : sessions.get(t);
        if (s == null || s.expires() < System.currentTimeMillis()) {
            if (t != null) sessions.remove(t);
            throw new ApiException(401, "Please log in.");
        }
        Optional<User> u = school.find(s.userId());
        if (u.isEmpty()) {
            sessions.remove(t);
            throw new ApiException(401, "Account no longer exists.");
        }
        return u.get();
    }

    // ================================================================
    //  Record → Map
    // ================================================================
    static Map<String, Object> userToMap(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("name", u.name());
        m.put("role", u.role());
        m.put("subject", u.subject());
        return m;
    }
    static Map<String, Object> componentToMap(GradeComponent c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("studentId", c.studentId());
        m.put("subject", c.subject());
        m.put("componentName", c.componentName());
        m.put("score", c.score());
        m.put("weight", c.weight());
        m.put("gradedBy", c.gradedBy());
        m.put("updatedAt", c.updatedAt());
        m.put("comment", c.comment());
        return m;
    }
    static Map<String, Object> subjectGradeToMap(SubjectGrade g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", g.subject());
        m.put("score", g.score());
        m.put("letter", g.letter());
        m.put("result", g.result());
        m.put("credits", g.credits());
        m.put("gpaPoints", g.gpaPoints());
        m.put("componentCount", g.componentCount());
        return m;
    }
    static Map<String, Object> rankToMap(StudentRank r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank", r.rank());
        m.put("studentId", r.studentId());
        m.put("name", r.name());
        m.put("average", r.average());
        m.put("gpa", r.gpa());
        m.put("subjectCount", r.subjectCount());
        return m;
    }
    static Map<String, Object> statToMap(SubjectStat s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", s.subject());
        m.put("count", s.count());
        m.put("average", s.average());
        m.put("median", s.median());
        m.put("stdDev", s.stdDev());
        m.put("min", s.min());
        m.put("max", s.max());
        m.put("passed", s.passed());
        m.put("passRate", s.passRate());
        return m;
    }

    // ================================================================
    //  HTTP helpers
    // ================================================================
    static void cors(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Expose-Headers", "Content-Disposition");
        h.set("Cache-Control", "no-store");
    }

    static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "SAMEORIGIN");
        h.set("Referrer-Policy", "no-referrer");
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(65_537);
        if (raw.length > 65_536) throw new ApiException(413, "Request too large.");
        if (raw.length == 0) return new HashMap<>();
        return Json.parseObject(new String(raw, StandardCharsets.UTF_8));
    }

    static String str(Map<String, Object> b, String key) {
        Object v = b.get(key);
        return v == null ? "" : String.valueOf(v);
    }
    static double num(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        throw new IllegalArgumentException(key + " must be a number.");
    }
    static Double numOpt(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    static void ok(HttpExchange ex) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "OK");
        json(ex, 200, m);
    }
    static void error(HttpExchange ex, int status, String message) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ERROR");
        m.put("message", message == null ? "Error" : message);
        json(ex, status, m);
    }
    static void json(HttpExchange ex, int status, Object body) throws IOException {
        securityHeaders(ex);
        send(ex, status, "application/json; charset=utf-8",
                Json.write(body).getBytes(StandardCharsets.UTF_8));
    }
    static void send(HttpExchange ex, int status, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, data.length);
        try (var os = ex.getResponseBody()) { os.write(data); }
    }
    static void sendFile(HttpExchange ex, Path file, String type) throws IOException {
        ex.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + file.getFileName() + "\"");
        send(ex, 200, type + "; charset=utf-8", Files.readAllBytes(file));
    }

    // ================================================================
    //  Static file handler — whitelisted extensions, no traversal
    // ================================================================
    static void handleStatic(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("GET")) { notFound(ex); return; }
            String p = ex.getRequestURI().getPath();
            if (p.equals("/")) p = "/index.html";
            if (p.equals("/favicon.ico")) { ex.sendResponseHeaders(204, -1); return; }

            String name = p.startsWith("/") ? p.substring(1) : p;
            if (name.contains("..") || name.contains("\\")) { notFound(ex); return; }

            int dot = name.lastIndexOf('.');
            if (dot < 0) { notFound(ex); return; }
            String ext = name.substring(dot + 1).toLowerCase();
            if (!STATIC_EXT.contains(ext)) { notFound(ex); return; }

            Path file = Path.of(name).toAbsolutePath().normalize();
            Path base = Path.of(".").toAbsolutePath().normalize();
            if (!file.startsWith(base)) { notFound(ex); return; }
            if (!Files.isRegularFile(file)) { notFound(ex); return; }

            String mime = switch (ext) {
                case "html" -> "text/html; charset=utf-8";
                case "js"   -> "text/javascript; charset=utf-8";
                case "css"  -> "text/css; charset=utf-8";
                case "svg"  -> "image/svg+xml";
                case "png"  -> "image/png";
                case "ico"  -> "image/x-icon";
                case "woff2"-> "font/woff2";
                default     -> "application/octet-stream";
            };
            securityHeaders(ex);
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            send(ex, 200, mime, Files.readAllBytes(file));
        } finally {
            ex.close();
        }
    }

    static void notFound(HttpExchange ex) throws IOException {
        send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
    }

    // ================================================================
    //  Tiny JSON
    // ================================================================
    static final class Json {
        private final String s;
        private int i;
        private Json(String s) { this.s = s; }

        static Map<String, Object> parseObject(String text) {
            Json j = new Json(text);
            try {
                Map<String, Object> m = new HashMap<>();
                j.ws();
                j.expect('{');
                j.ws();
                if (j.s.charAt(j.i) == '}') return m;
                while (true) {
                    j.ws();
                    String k = j.string();
                    j.ws();
                    j.expect(':');
                    j.ws();
                    m.put(k, j.value());
                    j.ws();
                    char c = j.s.charAt(j.i++);
                    if (c == '}') return m;
                    if (c != ',') throw new IllegalArgumentException();
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid JSON request.");
            }
        }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private void expect(char c) { if (s.charAt(i++) != c) throw new IllegalArgumentException(); }

        private Object value() {
            if (s.charAt(i) == '"') return string();
            if (s.startsWith("true", i))  { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i))  { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(start, i));
        }
        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> sb.append(e);
                }
            }
        }
        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            put(sb, o);
            return sb.toString();
        }
        private static void put(StringBuilder sb, Object o) {
            if (o == null) sb.append("null");
            else if (o instanceof String str) quote(sb, str);
            else if (o instanceof Double d && (d.isNaN() || d.isInfinite())) sb.append("null");
            else if (o instanceof Number || o instanceof Boolean) sb.append(o);
            else if (o instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    put(sb, e.getValue());
                }
                sb.append('}');
            } else if (o instanceof Collection<?> c) {
                sb.append('[');
                boolean first = true;
                for (Object item : c) {
                    if (!first) sb.append(',');
                    first = false;
                    put(sb, item);
                }
                sb.append(']');
            } else quote(sb, o.toString());
        }
        private static void quote(StringBuilder sb, String v) {
            sb.append('"');
            for (char c : v.toCharArray()) {
                switch (c) {
                    case '"'  -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }
    }
}
