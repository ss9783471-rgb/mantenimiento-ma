import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Servidor local de Mantenimiento M.A: sirve la web y guarda opiniones en JSON. */
public class NexoServer {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path PUBLIC = ROOT.resolve("public").normalize();
    private static final Path DATA = ROOT.resolve("data").normalize();
    private static final Path COMMENTS = DATA.resolve("comments.json");
    private static final Path COMMENT_RECORDS = DATA.resolve("comments");
    private static final Path REQUESTS = DATA.resolve("requests.json");
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_IMAGE_REQUEST_BYTES = 28 * 1024 * 1024;
    private static final Object FILE_LOCK = new Object();

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DATA);
        Files.createDirectories(COMMENT_RECORDS);
        if (!Files.exists(COMMENTS)) Files.writeString(COMMENTS, "[]\n", StandardCharsets.UTF_8);
        if (!Files.exists(REQUESTS)) Files.writeString(REQUESTS, "[]\n", StandardCharsets.UTF_8);
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "3000"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/comments", NexoServer::comments);
        server.createContext("/api/issues", NexoServer::issues);
        server.createContext("/api/gallery", NexoServer::galleryEditor);
        server.createContext("/", NexoServer::staticFiles);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("Mantenimiento M.A listo en http://localhost:" + port);
    }

    private static void comments(HttpExchange ex) throws IOException {
        if ("GET".equals(ex.getRequestMethod())) {
            List<Map<String, Object>> rows = readComments();
            rows.sort((a, b) -> String.valueOf(b.get("createdAt")).compareTo(String.valueOf(a.get("createdAt"))));
            List<Map<String, Object>> visible = rows.stream().map(NexoServer::publicComment).toList();
            respond(ex, 200, toJson(visible), "application/json; charset=utf-8");
            return;
        }
        if ("DELETE".equals(ex.getRequestMethod())) {
            deleteComment(ex);
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Método no permitido.\"}", "application/json; charset=utf-8");
            return;
        }
        try {
            byte[] body = ex.getRequestBody().readNBytes(5001);
            if (body.length > 5000) {
                respond(ex, 413, "{\"error\":\"El comentario es demasiado largo.\"}", "application/json; charset=utf-8");
                return;
            }
            Object parsed = new JsonParser(new String(body, StandardCharsets.UTF_8)).parse();
            if (!(parsed instanceof Map<?, ?> input)) throw new IllegalArgumentException();
            String name = clean(input.get("name"), 48);
            String email = clean(input.get("email"), 254).toLowerCase();
            String text = clean(input.get("text"), 500);
            String service = clean(input.get("service"), 40);
            int rating = parseRating(input.get("rating"));
            if (name.length() < 2 || !validEmail(email) || text.length() < 8 || rating < 1 || rating > 5) {
                respond(ex, 400, "{\"error\":\"Revisa tu nombre, correo, comentario y calificación.\"}", "application/json; charset=utf-8");
                return;
            }
            byte[] tokenBytes = new byte[32];
            new SecureRandom().nextBytes(tokenBytes);
            String deleteToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("name", name);
            entry.put("email", email);
            entry.put("showEmail", Boolean.TRUE.equals(input.get("showEmail")));
            entry.put("text", text);
            entry.put("service", service);
            entry.put("rating", rating);
            entry.put("createdAt", Instant.now().toString());
            entry.put("deleteTokenHash", hashToken(deleteToken));
            synchronized (FILE_LOCK) {
                saveCommentRecord(entry);
            }
            Map<String, Object> result = publicComment(entry);
            result.put("deleteToken", deleteToken);
            respond(ex, 201, toJson(result), "application/json; charset=utf-8");
        } catch (Exception e) {
            System.err.println("No se pudo procesar el comentario: " + e);
            respond(ex, 500, "{\"error\":\"No se pudo guardar la opinión. Inténtalo de nuevo.\"}", "application/json; charset=utf-8");
        }
    }

    private static boolean validEmail(String email) {
        return email.length() <= 254 && email.matches("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    private static String hashToken(String token) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private static Map<String, Object> publicComment(Map<String, Object> entry) {
        Map<String, Object> visible = new LinkedHashMap<>();
        for (String key : List.of("id", "name", "text", "service", "rating", "createdAt")) {
            if (entry.containsKey(key)) visible.put(key, entry.get(key));
        }
        if (Boolean.TRUE.equals(entry.get("showEmail")) && entry.get("email") instanceof String email) {
            visible.put("email", email);
        }
        return visible;
    }

    private static void deleteComment(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String id = path.substring("/api/comments/".length());
            if (!id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException();
            byte[] body = ex.getRequestBody().readNBytes(2000);
            Object parsed = new JsonParser(new String(body, StandardCharsets.UTF_8)).parse();
            if (!(parsed instanceof Map<?, ?> input)) throw new IllegalArgumentException();
            String token = String.valueOf(input.get("deleteToken"));
            Path file = COMMENT_RECORDS.resolve(id + ".json").normalize();
            synchronized (FILE_LOCK) {
                if (!file.startsWith(COMMENT_RECORDS) || !Files.isRegularFile(file)) {
                    respond(ex, 404, "{\"error\":\"No se encontró la opinión.\"}", "application/json; charset=utf-8");
                    return;
                }
                Object stored = new JsonParser(Files.readString(file, StandardCharsets.UTF_8)).parse();
                if (!(stored instanceof Map<?, ?> entry) || !(entry.get("deleteTokenHash") instanceof String tokenHash)) throw new IllegalArgumentException();
                byte[] expected = tokenHash.getBytes(StandardCharsets.US_ASCII);
                byte[] actual = hashToken(token).getBytes(StandardCharsets.US_ASCII);
                if (!MessageDigest.isEqual(expected, actual)) {
                    respond(ex, 403, "{\"error\":\"No se pudo verificar que esta opinión te pertenece.\"}", "application/json; charset=utf-8");
                    return;
                }
                Files.delete(file);
            }
            respond(ex, 200, "{\"deleted\":true}", "application/json; charset=utf-8");
        } catch (Exception e) {
            respond(ex, 400, "{\"error\":\"No se pudo eliminar la opinión.\"}", "application/json; charset=utf-8");
        }
    }

    private static void issues(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if ("POST".equals(ex.getRequestMethod()) && "/api/issues".equals(path)) {
            createIssue(ex);
            return;
        }
        if (!authorized(ex)) return;
        if ("GET".equals(ex.getRequestMethod()) && "/api/issues".equals(path)) {
            respond(ex, 200, toJson(readIssues()), "application/json; charset=utf-8");
            return;
        }
        if ("PATCH".equals(ex.getRequestMethod()) && path.startsWith("/api/issues/")) {
            updateIssue(ex, path.substring("/api/issues/".length()));
            return;
        }
        respond(ex, 405, "{\"error\":\"Método o ruta no permitidos.\"}", "application/json; charset=utf-8");
    }

    private static boolean authorized(HttpExchange ex) throws IOException {
        String provided = ex.getRequestHeaders().getFirst("Authorization");
        String configuredPassword = System.getenv("TECH_PASSWORD");
        try {
            if (provided != null && provided.startsWith("Basic ")) {
                String credential = new String(Base64.getDecoder().decode(provided.substring(6)), StandardCharsets.UTF_8);
                int separator = credential.indexOf(':');
                if (separator > 0) {
                    String username = credential.substring(0, separator);
                    String password = credential.substring(separator + 1);
                    boolean validUser = username.equals("admin") || username.equals("tecnicos");
                    if (validUser && configuredPassword != null && !configuredPassword.isBlank()
                            && MessageDigest.isEqual(configuredPassword.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8))) return true;
                    if (validUser && (configuredPassword == null || configuredPassword.isBlank())) {
                        Object value = new JsonParser(Files.readString(DATA.resolve("admin.json"), StandardCharsets.UTF_8)).parse();
                        if (value instanceof Map<?, ?> admin && admin.get("passwordHash") instanceof String passwordHash
                                && MessageDigest.isEqual(passwordHash.getBytes(StandardCharsets.US_ASCII), hashToken(password).getBytes(StandardCharsets.US_ASCII))) return true;
                    }
                }
            }
        } catch (Exception ignored) { }
        respond(ex, 401, "{\"error\":\"Clave incorrecta.\"}", "application/json; charset=utf-8");
        return false;
    }

    private static void createIssue(HttpExchange ex) throws IOException {
        try {
            byte[] body = ex.getRequestBody().readNBytes(5001);
            if (body.length > 5000) {
                respond(ex, 413, "{\"error\":\"La solicitud es demasiado larga.\"}", "application/json; charset=utf-8");
                return;
            }
            Object parsed = new JsonParser(new String(body, StandardCharsets.UTF_8)).parse();
            if (!(parsed instanceof Map<?, ?> input)) throw new IllegalArgumentException();
            String equipment = clean(input.get("equipment"), 80);
            String description = clean(input.get("description"), 1000);
            String name = clean(input.get("name"), 80);
            String phone = clean(input.get("phone"), 32);
            if (equipment.length() < 2 || description.length() < 8 || name.length() < 2 || phone.replaceAll("[^0-9]", "").length() < 7) {
                respond(ex, 400, "{\"error\":\"Completa el equipo, el problema, tu nombre y un teléfono válido.\"}", "application/json; charset=utf-8");
                return;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("equipment", equipment);
            entry.put("description", description);
            entry.put("name", name);
            entry.put("phone", phone);
            entry.put("status", "NUEVA");
            entry.put("notification", "NUEVA_SOLICITUD");
            entry.put("createdAt", Instant.now().toString());
            synchronized (FILE_LOCK) {
                List<Map<String, Object>> rows = readIssues();
                rows.add(entry);
                if (rows.size() > 1000) rows = new ArrayList<>(rows.subList(rows.size() - 1000, rows.size()));
                saveIssues(rows);
            }
            respond(ex, 201, toJson(entry), "application/json; charset=utf-8");
        } catch (Exception e) {
            respond(ex, 400, "{\"error\":\"No se pudo guardar la solicitud.\"}", "application/json; charset=utf-8");
        }
    }

    private static void updateIssue(HttpExchange ex, String id) throws IOException {
        try {
            if (id.length() > 80 || id.contains("/")) throw new IllegalArgumentException();
            byte[] body = ex.getRequestBody().readNBytes(1000);
            Object parsed = new JsonParser(new String(body, StandardCharsets.UTF_8)).parse();
            if (!(parsed instanceof Map<?, ?> input)) throw new IllegalArgumentException();
            String status = clean(input.get("status"), 24);
            if (!List.of("NUEVA", "EN PROCESO", "RESUELTA").contains(status)) throw new IllegalArgumentException();
            synchronized (FILE_LOCK) {
                List<Map<String, Object>> rows = readIssues();
                Map<String, Object> target = rows.stream().filter(row -> id.equals(row.get("id"))).findFirst().orElse(null);
                if (target == null) {
                    respond(ex, 404, "{\"error\":\"No se encontró la solicitud.\"}", "application/json; charset=utf-8");
                    return;
                }
                target.put("status", status);
                saveIssues(rows);
                respond(ex, 200, toJson(target), "application/json; charset=utf-8");
            }
        } catch (Exception e) {
            respond(ex, 400, "{\"error\":\"No se pudo actualizar el estado.\"}", "application/json; charset=utf-8");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readIssues() {
        synchronized (FILE_LOCK) {
            try {
                Object value = new JsonParser(Files.readString(REQUESTS, StandardCharsets.UTF_8)).parse();
                if (value instanceof List<?> list) return (List<Map<String, Object>>) list;
            } catch (Exception ignored) { }
            return new ArrayList<>();
        }
    }

    private static void saveIssues(List<Map<String, Object>> rows) throws IOException {
        Path temp = DATA.resolve("requests.tmp");
        Files.writeString(temp, toJson(rows) + "\n", StandardCharsets.UTF_8);
        Files.move(temp, REQUESTS, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @SuppressWarnings("unchecked")
    private static void galleryEditor(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!authorized(ex)) return;
        if ("GET".equals(ex.getRequestMethod()) && "/api/gallery".equals(path)) {
            respond(ex, 200, "{\"enabled\":true}", "application/json; charset=utf-8");
            return;
        }
        if (!"POST".equals(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Método no permitido.\"}", "application/json; charset=utf-8");
            return;
        }
        try {
            String[] parts = path.substring("/api/gallery/".length()).split("/");
            String collectionName;
            String fieldName;
            String imageLabel;
            int index;
            if (parts.length == 2 && parts[0].matches("\\d+") && (parts[1].equals("before") || parts[1].equals("after"))) {
                collectionName = "gallery";
                fieldName = parts[1];
                imageLabel = "gallery";
                index = Integer.parseInt(parts[0]);
            } else if (parts.length == 3 && parts[0].equals("team") && parts[1].matches("\\d+") && parts[2].equals("photo")) {
                collectionName = "team";
                fieldName = "photo";
                imageLabel = "technician";
                index = Integer.parseInt(parts[1]);
            } else {
                throw new IllegalArgumentException();
            }
            byte[] body = ex.getRequestBody().readNBytes(MAX_IMAGE_REQUEST_BYTES + 1);
            if (body.length > MAX_IMAGE_REQUEST_BYTES) {
                respond(ex, 413, "{\"error\":\"La imagen supera el límite de 20 MB.\"}", "application/json; charset=utf-8");
                return;
            }
            Object parsed = new JsonParser(new String(body, StandardCharsets.UTF_8)).parse();
            if (!(parsed instanceof Map<?, ?> input)) throw new IllegalArgumentException();
            String mime = String.valueOf(input.get("type"));
            String extension = switch (mime) {
                case "image/jpeg" -> "jpg";
                case "image/png" -> "png";
                case "image/webp" -> "webp";
                default -> throw new IllegalArgumentException("Formato no admitido. Usa JPG, PNG o WebP.");
            };
            byte[] image = Base64.getDecoder().decode(String.valueOf(input.get("data")));
            if (image.length == 0 || image.length > MAX_IMAGE_BYTES) {
                respond(ex, 413, "{\"error\":\"La imagen debe pesar hasta 20 MB.\"}", "application/json; charset=utf-8");
                return;
            }
            String publicPath;
            synchronized (FILE_LOCK) {
                Path contentFile = PUBLIC.resolve("site-content.json");
                Object rootValue = new JsonParser(Files.readString(contentFile, StandardCharsets.UTF_8)).parse();
                if (!(rootValue instanceof Map<?, ?> rootRaw)) throw new IllegalStateException("No se pudo leer la configuración de galería.");
                Map<String, Object> root = (Map<String, Object>) rootRaw;
                if (!(root.get(collectionName) instanceof List<?> entries) || index < 0 || index >= entries.size()) throw new IllegalArgumentException();
                if (!(entries.get(index) instanceof Map<?, ?> itemRaw)) throw new IllegalStateException("El registro no existe.");
                Map<String, Object> item = (Map<String, Object>) itemRaw;
                String filename = imageLabel.equals("gallery")
                        ? String.format("gallery-%02d-%s.%s", index + 1, fieldName, extension)
                        : String.format("technician-%02d.%s", index + 1, extension);
                Path imagePath = PUBLIC.resolve("images").resolve(filename).normalize();
                Files.createDirectories(imagePath.getParent());
                Path tempImage = imagePath.resolveSibling(filename + ".tmp");
                Files.write(tempImage, image);
                moveReplacing(tempImage, imagePath);
                publicPath = "/images/" + filename;
                item.put(fieldName, publicPath);
                Path tempContent = contentFile.resolveSibling("site-content.json.tmp");
                Files.writeString(tempContent, toJson(root) + "\n", StandardCharsets.UTF_8);
                moveReplacing(tempContent, contentFile);
            }
            respond(ex, 200, toJson(Map.of("saved", true, "path", publicPath)), "application/json; charset=utf-8");
        } catch (IllegalArgumentException e) {
            String message = e.getMessage() == null ? "No se pudo procesar la imagen." : e.getMessage();
            respond(ex, 400, toJson(Map.of("error", message)), "application/json; charset=utf-8");
        } catch (Exception e) {
            System.err.println("No se pudo guardar la imagen de galería: " + e);
            respond(ex, 500, "{\"error\":\"No se pudo guardar la imagen. Inténtalo de nuevo.\"}", "application/json; charset=utf-8");
        }
    }

    private static void moveReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String clean(Object value, int limit) {
        String text = value == null ? "" : String.valueOf(value).trim().replace("<", "").replace(">", "");
        return text.substring(0, Math.min(text.length(), limit));
    }

    private static int parseRating(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (numeric != Math.rint(numeric) || numeric < 1 || numeric > 5) throw new IllegalArgumentException();
            return (int) numeric;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readComments() {
        synchronized (FILE_LOCK) {
            List<Map<String, Object>> result = new ArrayList<>();
            // Keep older comments readable while all new opinions use individual records.
            try {
                Object value = new JsonParser(Files.readString(COMMENTS, StandardCharsets.UTF_8)).parse();
                if (value instanceof List<?> list) {
                    for (Object row : list) if (row instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
                }
            } catch (Exception ignored) { }
            try (var files = Files.list(COMMENT_RECORDS)) {
                files.filter(file -> file.getFileName().toString().endsWith(".json")).forEach(file -> {
                    try {
                        Object row = new JsonParser(Files.readString(file, StandardCharsets.UTF_8)).parse();
                        if (row instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
                    } catch (Exception ignored) { }
                });
            } catch (IOException ignored) { }
            return result;
        }
    }

    private static void saveCommentRecord(Map<String, Object> entry) throws IOException {
        Files.createDirectories(COMMENT_RECORDS);
        String id = String.valueOf(entry.get("id"));
        Path temp = COMMENT_RECORDS.resolve(id + ".tmp");
        Path destination = COMMENT_RECORDS.resolve(id + ".json");
        Files.writeString(temp, toJson(entry) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, destination);
        }
    }

    private static void staticFiles(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
            respond(ex, 405, "Método no permitido", "text/plain; charset=utf-8");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = PUBLIC.resolve(path.substring(1)).normalize();
        if (!file.startsWith(PUBLIC) || !Files.isRegularFile(file)) {
            respond(ex, 404, "No encontrado", "text/plain; charset=utf-8");
            return;
        }
        String type = switch (file.getFileName().toString().contains(".") ? file.getFileName().toString().substring(file.getFileName().toString().lastIndexOf('.')) : "") {
            case ".html" -> "text/html; charset=utf-8";
            case ".css" -> "text/css; charset=utf-8";
            case ".js" -> "text/javascript; charset=utf-8";
            case ".svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, "HEAD".equals(ex.getRequestMethod()) ? -1 : bytes.length);
        if (!"HEAD".equals(ex.getRequestMethod())) ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static void respond(HttpExchange ex, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> pairs = new ArrayList<>();
            map.forEach((k, v) -> pairs.add(toJson(String.valueOf(k)) + ":" + toJson(v)));
            return "{" + String.join(",", pairs) + "}";
        }
        if (value instanceof Iterable<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) items.add(toJson(item));
            return "[" + String.join(",", items) + "]";
        }
        return toJson(String.valueOf(value));
    }

    /** Parser JSON pequeño para evitar dependencias externas. */
    private static final class JsonParser {
        private final String source;
        private int pos;
        JsonParser(String source) { this.source = source; }
        Object parse() { Object value = value(); whitespace(); if (pos != source.length()) throw new IllegalArgumentException(); return value; }
        private Object value() {
            whitespace(); if (pos >= source.length()) throw new IllegalArgumentException();
            char c = source.charAt(pos);
            if (c == '"') return string();
            if (c == '{') return object();
            if (c == '[') return array();
            if (source.startsWith("true", pos)) { pos += 4; return true; }
            if (source.startsWith("false", pos)) { pos += 5; return false; }
            if (source.startsWith("null", pos)) { pos += 4; return null; }
            int start = pos; if (c == '-') pos++;
            while (pos < source.length() && (Character.isDigit(source.charAt(pos)) || ".eE+-".indexOf(source.charAt(pos)) >= 0)) pos++;
            if (start == pos) throw new IllegalArgumentException();
            String number = source.substring(start, pos);
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                return Double.valueOf(number);
            }
            return Long.valueOf(number);
        }
        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>(); pos++; whitespace(); if (take('}')) return map;
            do { whitespace(); String key = string(); whitespace(); require(':'); map.put(key, value()); whitespace(); if (take('}')) return map; require(','); } while (true);
        }
        private List<Object> array() {
            List<Object> list = new ArrayList<>(); pos++; whitespace(); if (take(']')) return list;
            do { list.add(value()); whitespace(); if (take(']')) return list; require(','); } while (true);
        }
        private String string() {
            require('"'); StringBuilder out = new StringBuilder();
            while (pos < source.length()) {
                char c = source.charAt(pos++); if (c == '"') return out.toString();
                if (c == '\\') {
                    if (pos >= source.length()) throw new IllegalArgumentException();
                    char e = source.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> out.append(e);
                        case 'b' -> out.append('\b'); case 'f' -> out.append('\f'); case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                        case 'u' -> { if (pos + 4 > source.length()) throw new IllegalArgumentException(); out.append((char) Integer.parseInt(source.substring(pos, pos + 4), 16)); pos += 4; }
                        default -> throw new IllegalArgumentException();
                    }
                } else { if (c < 0x20) throw new IllegalArgumentException(); out.append(c); }
            }
            throw new IllegalArgumentException();
        }
        private void whitespace() { while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++; }
        private boolean take(char c) { if (pos < source.length() && source.charAt(pos) == c) { pos++; return true; } return false; }
        private void require(char c) { if (!take(c)) throw new IllegalArgumentException(); }
    }
}
