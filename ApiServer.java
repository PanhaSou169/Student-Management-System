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
 * Web API for the School Management System.
 * Keep it in the SAME folder as StudentManagementSystem.java (same default package),
 * compile everything together, and run THIS class instead of the console one:
 *
 *     javac -cp ".;mysql-connector-j.jar" StudentManagementSystem.java ApiServer.java
 *     java  -cp ".;mysql-connector-j.jar" ApiServer
 *
 * Then open http://localhost:8080
 */
public class ApiServer {
    static final int DEFAULT_PORT = 8080;
    static final long SESSION_MS = 2 * 60 * 60 * 1000L; // 2 hours

    // FIX #1 — extras.js must be served too, otherwise all enhancements die
    static final Set<String> STATIC_FILES = Set.of("index.html", "app.js", "extras.js");

    static final SecureRandom RANDOM = new SecureRandom();
    static final Scanner IN = new Scanner(System.in);

    record Session(String userId, long expires) {}

    static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    static class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }

    static AuthService auth;
    static SchoolService school;

    // ------------------------------------------------------------------ start-up

    public static void main(String[] args) throws Exception {
        final int port;
        try { port = configuredPort(args); }
        catch (IllegalArgumentException e) { System.err.println(e.getMessage()); return; }

        Clock clock = Clock.systemDefaultZone();
        String dbUser = System.getenv("SMS_DB_USER") != null ? System.getenv("SMS_DB_USER") : Config.DB_USER;
        String dbPassword = System.getenv("SMS_DB_PASSWORD");
        if (dbPassword == null) dbPassword = readSecret("MySQL password for user '" + dbUser + "': ");

        final Database db;
        try {
            db = new Database(Config.DB_SERVER_URL, dbUser, dbPassword, Config.DB_NAME);
        } catch (SQLException e) {
            System.out.println("Cannot connect to MySQL: " + e.getMessage());
            System.out.println("Check MySQL is running, user/password are right, and the connector jar is on the classpath.");
            return;
        }
        AuditLog audit = new AuditLog(db, clock);
        auth = new AuthService(db, clock, audit);
        school = new SchoolService(db, clock, audit);
        if (!auth.hasAdmin()) firstRunSetup();

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/api/", ApiServer::handleApi);
        server.createContext("/", ApiServer::handleStatic);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            try { db.close(); } catch (Exception ignored) { }
        }));
        System.out.println("Server running -> http://localhost:" + server.getAddress().getPort() + "   (Ctrl+C to stop)");
    }

    static int configuredPort(String[] args) {
        if (args.length > 1) throw new IllegalArgumentException("Usage: java ApiServer [port]");
        String value = args.length == 1 ? args[0] : System.getenv("SMS_PORT");
        if (value == null || value.isBlank()) return DEFAULT_PORT;
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Port must be a number from 1 to 65535.");
        }
    }

    static void firstRunSetup() {
        System.out.println("=== FIRST-TIME SETUP: create the admin account ===");
        while (true) {
            System.out.print("Admin name: ");
            String name = IN.nextLine().trim();
            String password = readSecret("Admin password: ");
            try {
                System.out.println("Admin created. Your login ID is " + auth.createUser("setup", "ADMIN", name, null, password));
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

    // ------------------------------------------------------------------ routing

    static void handleApi(HttpExchange ex) throws IOException {
        try {
            cors(ex);
            String method = ex.getRequestMethod();
            if (method.equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); return; }

            String path = ex.getRequestURI().getPath().substring(4); // strip "/api"
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String route = method + " " + path;

            if (route.equals("POST /login")) { login(ex); return; }
            User me = authenticate(ex);

            switch (route) {
                case "POST /logout" -> {
                    sessions.remove(token(ex));
                    ok(ex);
                }

                // FIX #2 — GET /users for the KPI cards
                case "GET /users" -> {
                    if (!me.is("ADMIN")) throw new ApiException(403, "Only an admin can list users.");
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (User u : school.allUsers()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", u.id());
                        m.put("name", u.name());
                        m.put("role", u.role());
                        m.put("subject", u.subject());
                        out.add(m);
                    }
                    json(ex, 200, out);
                }

                case "POST /users" -> {
                    if (!me.is("ADMIN")) throw new ApiException(403, "Only an admin can create users.");
                    Map<String, Object> b = body(ex);
                    String id = auth.createUser(me.id(), str(b, "role").toUpperCase(), str(b, "name"),
                            str(b, "subject"), str(b, "password"));
                    json(ex, 201, Map.of("status", "OK", "id", id));
                }

                // FIX #3 — change own password
                case "POST /users/change-password" -> {
                    Map<String, Object> b = body(ex);
                    auth.changePassword(me, str(b, "currentPassword"), str(b, "newPassword"));
                    ok(ex);
                }

                case "POST /grades/add" -> {
                    Map<String, Object> b = body(ex);
                    school.addGrade(me, str(b, "studentId"), str(b, "subject"), num(b, "score"));
                    ok(ex);
                }
                case "POST /grades/update" -> {
                    Map<String, Object> b = body(ex);
                    school.updateGrade(me, str(b, "studentId"), str(b, "subject"), num(b, "score"));
                    ok(ex);
                }

                // FIX #4 — delete grade
                case "POST /grades/delete" -> {
                    Map<String, Object> b = body(ex);
                    school.deleteGrade(me, str(b, "studentId"), str(b, "subject"));
                    ok(ex);
                }

                case "GET /stats" -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (SubjectStat s : school.classStats(me)) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("subject", s.subject());
                        m.put("count", s.count());
                        m.put("average", s.average());
                        m.put("passed", s.passed());
                        out.add(m);
                    }
                    json(ex, 200, out);
                }
                case "GET /audit" -> {
                    int limit;
                    try {
                        limit = Math.max(1, Math.min(200, Integer.parseInt(q.getOrDefault("limit", "20"))));
                    } catch (NumberFormatException e) { limit = 20; }
                    json(ex, 200, school.auditTrail(me, limit));
                }
                case "GET /export/csv" -> sendFile(ex, school.exportCsv(me), "text/csv");
                case "GET /export/backup" -> sendFile(ex, school.backup(me), "application/sql");
                default -> {
                    String prefix = "GET /grades/student/";
                    if (!route.startsWith(prefix)) throw new ApiException(404, "Not found.");
                    String id = route.substring(prefix.length()).trim();
                    if (id.isEmpty()) throw new IllegalArgumentException("Student ID is required.");
                    json(ex, 200, school.gradesVisibleTo(me, id));
                }
            }
        } catch (ApiException e) {
            error(ex, e.status, e.getMessage());
        } catch (IllegalArgumentException e) {
            error(ex, 400, e.getMessage());
        } catch (SecurityException e) {
            error(ex, 403, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            error(ex, 500, "Server error. Check the server console.");
        } finally {
            ex.close();
        }
    }

    static void login(HttpExchange ex) throws IOException {
        Map<String, Object> b = body(ex);
        String id = str(b, "id");
        String password = str(b, "password");
        if (id.isBlank() || password.isEmpty()) throw new ApiException(400, "Enter your ID and password.");
        AuthService.LoginResult r = auth.login(id, password);
        switch (r.status()) {
            case OK -> {
                byte[] raw = new byte[32];
                RANDOM.nextBytes(raw);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
                sessions.put(token, new Session(r.user().id(), System.currentTimeMillis() + SESSION_MS));
                Map<String, Object> user = new LinkedHashMap<>();
                user.put("id", r.user().id());
                user.put("name", r.user().name());
                user.put("role", r.user().role());
                user.put("subject", r.user().subject());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "OK");
                out.put("token", token);
                out.put("user", user);
                json(ex, 200, out);
            }
            case LOCKED -> error(ex, 423, "Account locked for " + AuthService.lockMinutes() + " minutes.");
            default -> error(ex, 401, "Wrong ID or password.");
        }
    }

    // ------------------------------------------------------------------ sessions

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
        if (u.isEmpty()) { sessions.remove(t); throw new ApiException(401, "Account no longer exists."); }
        return u.get();
    }

    // ------------------------------------------------------------------ helpers

    static void cors(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.set("Access-Control-Expose-Headers", "Content-Disposition");
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
        return Json.parseObject(new String(raw, StandardCharsets.UTF_8));
    }

    static String str(Map<String, Object> b, String key) {
        Object v = b.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    static double num(Map<String, Object> b, String key) {
        if (b.get(key) instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("Score must be a number.");
    }

    static void ok(HttpExchange ex) throws IOException {
        json(ex, 200, Map.of("status", "OK"));
    }

    static void error(HttpExchange ex, int status, String message) throws IOException {
        json(ex, status, Map.of("status", "ERROR", "message", message == null ? "Error" : message));
    }

    static void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, data.length);
        try (var os = ex.getResponseBody()) { os.write(data); }
    }

    static void sendFile(HttpExchange ex, Path file, String type) throws IOException {
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"");
        send(ex, 200, type + "; charset=utf-8", Files.readAllBytes(file));
    }

    static void handleStatic(HttpExchange ex) throws IOException {
        try {
            String p = ex.getRequestURI().getPath();
            String name = p.equals("/") ? "index.html" : p.substring(1);
            Path file = Path.of(name);
            if (!ex.getRequestMethod().equals("GET") || !STATIC_FILES.contains(name) || !Files.isRegularFile(file)) {
                send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] content = Files.readAllBytes(file);
            if (name.equals("index.html")) {
                String html = new String(content, StandardCharsets.UTF_8);
                html = html.replace("</head>",
                        "<script>window.SMS_API_BASE_URL='/api';</script>\n</head>");
                content = html.getBytes(StandardCharsets.UTF_8);
            }
            send(ex, 200, (name.endsWith(".js") ? "text/javascript" : "text/html") + "; charset=utf-8", content);
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------ tiny JSON (no libraries needed)

    static final class Json {
        private final String s;
        private int i;
        private Json(String s) { this.s = s; }

        static Map<String, Object> parseObject(String text) {
            Json j = new Json(text);
            try {
                Map<String, Object> m = new HashMap<>();
                j.ws(); j.expect('{'); j.ws();
                if (j.s.charAt(j.i) == '}') return m;
                while (true) {
                    j.ws(); String k = j.string(); j.ws(); j.expect(':'); j.ws();
                    m.put(k, j.value()); j.ws();
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
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
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
                sb.append('{'); boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(','); first = false;
                    quote(sb, String.valueOf(e.getKey())); sb.append(':'); put(sb, e.getValue());
                }
                sb.append('}');
            } else if (o instanceof Collection<?> c) {
                sb.append('['); boolean first = true;
                for (Object item : c) {
                    if (!first) sb.append(','); first = false;
                    put(sb, item);
                }
                sb.append(']');
            } else quote(sb, o.toString());
        }

        private static void quote(StringBuilder sb, String v) {
            sb.append('"');
            for (char c : v.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
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
