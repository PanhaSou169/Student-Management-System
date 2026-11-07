import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/*
 * HTTP routes for the classroom features. ApiServer calls handle() for every
 * authenticated request; it returns true when it answered the request.
 *
 *   GET  /api/attendance?subject=&date=          (teacher/admin) who is marked that day
 *   POST /api/attendance/mark                    {subject,date,records:[{studentId,status}]}
 *   GET  /api/attendance/summary?subject=        (teacher/admin) totals per student
 *   GET  /api/attendance/mine                    (student) own attendance
 *
 *   GET  /api/announcements
 *   POST /api/announcements                      {title,body,audience}
 *   POST /api/announcements/delete               {id}
 *
 *   GET  /api/assignments
 *   POST /api/assignments                        {subject,title,description,dueDate,maxScore}
 *   POST /api/assignments/delete                 {id}
 *   GET  /api/assignments/{id}/submissions       (teacher/admin)
 *   POST /api/submissions/submit                 {assignmentId,content}
 *   POST /api/submissions/grade                  {submissionId,score,feedback,syncGradebook}
 */
final class ClassroomApi {
    private ClassroomApi() {}

    static boolean handle(HttpExchange ex, String method, String path, Map<String, String> q, User me)
            throws IOException {
        ClassroomService c = ApiServer.classroom;
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");

        // ---------------- attendance ----------------
        if (get && path.equals("/attendance")) {
            ApiServer.json(ex, 200, c.attendanceOn(me, q.getOrDefault("subject", ""), date(q.get("date"))));
            return true;
        }
        if (get && path.equals("/attendance/summary")) {
            ApiServer.json(ex, 200, c.attendanceSummary(me, q.getOrDefault("subject", "")));
            return true;
        }
        if (get && path.equals("/attendance/mine")) {
            ApiServer.json(ex, 200, c.myAttendance(me));
            return true;
        }
        if (post && path.equals("/attendance/mark")) {
            Map<String, Object> b = ApiServer.body(ex);
            List<Map<String, Object>> records = new ArrayList<>();
            if (b.get("records") instanceof List<?> list)
                for (Object o : list)
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> r = new java.util.HashMap<>();
                        for (Map.Entry<?, ?> e : m.entrySet()) r.put(String.valueOf(e.getKey()), e.getValue());
                        records.add(r);
                    }
            int n = c.markAttendance(me, ApiServer.str(b, "subject"), date(ApiServer.str(b, "date")), records);
            ApiServer.syncCsv();
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("status", "OK");
            out.put("saved", n);
            ApiServer.json(ex, 200, out);
            return true;
        }

        // ---------------- announcements ----------------
        if (get && path.equals("/announcements")) {
            ApiServer.json(ex, 200, c.announcements(me));
            return true;
        }
        if (post && path.equals("/announcements")) {
            Map<String, Object> b = ApiServer.body(ex);
            c.addAnnouncement(me, ApiServer.str(b, "title"), ApiServer.str(b, "body"), ApiServer.str(b, "audience"));
            ApiServer.ok(ex);
            return true;
        }
        if (post && path.equals("/announcements/delete")) {
            c.deleteAnnouncement(me, (long) ApiServer.num(ApiServer.body(ex), "id"));
            ApiServer.ok(ex);
            return true;
        }

        // ---------------- assignments ----------------
        if (get && path.equals("/assignments")) {
            ApiServer.json(ex, 200, c.assignments(me));
            return true;
        }
        if (post && path.equals("/assignments")) {
            Map<String, Object> b = ApiServer.body(ex);
            Double max = ApiServer.numOpt(b, "maxScore");
            long id = c.createAssignment(me, ApiServer.str(b, "subject"), ApiServer.str(b, "title"),
                    ApiServer.str(b, "description"), date(ApiServer.str(b, "dueDate")),
                    max == null ? 100.0 : max);
            ApiServer.syncCsv();
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("status", "OK");
            out.put("id", id);
            ApiServer.json(ex, 201, out);
            return true;
        }
        if (post && path.equals("/assignments/delete")) {
            c.deleteAssignment(me, (long) ApiServer.num(ApiServer.body(ex), "id"));
            ApiServer.syncCsv();
            ApiServer.ok(ex);
            return true;
        }
        if (get && path.startsWith("/assignments/") && path.endsWith("/submissions")) {
            String mid = path.substring("/assignments/".length(), path.length() - "/submissions".length());
            long id;
            try { id = Long.parseLong(mid); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Bad assignment id."); }
            ApiServer.json(ex, 200, c.submissionsOf(me, id));
            return true;
        }

        // ---------------- submissions ----------------
        if (post && path.equals("/submissions/submit")) {
            Map<String, Object> b = ApiServer.body(ex);
            c.submit(me, (long) ApiServer.num(b, "assignmentId"), ApiServer.str(b, "content"));
            ApiServer.ok(ex);
            return true;
        }
        if (post && path.equals("/submissions/grade")) {
            Map<String, Object> b = ApiServer.body(ex);
            boolean sync = Boolean.TRUE.equals(b.get("syncGradebook"));
            c.grade(me, (long) ApiServer.num(b, "submissionId"), ApiServer.num(b, "score"),
                    ApiServer.str(b, "feedback"), sync);
            if (sync) ApiServer.syncCsv();
            ApiServer.ok(ex);
            return true;
        }
        return false;
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("Date is required (yyyy-MM-dd).");
        try { return LocalDate.parse(s.trim()); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("Bad date: " + s); }
    }
}
