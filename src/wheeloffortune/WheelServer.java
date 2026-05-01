package wheeloffortune;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class WheelServer {
    private static final String TASKS_FILE = "tasks_data.json";
    private static final int PORT = 8080;

    // Глобальный лок для файловых операций — защита от гонки данных
    private static final ReentrantLock FILE_LOCK = new ReentrantLock();

    private static final String[] ALL_COLORS = {
            "#f472b6", "#a78bfa", "#818cf8", "#60a5fa", "#34d399",
            "#6ee7b7", "#facc15", "#fb923c", "#f87171", "#e879f9",
            "#38bdf8", "#4ade80", "#c084fc", "#fb7185", "#fcd34d",
            "#22d3ee", "#a855f7", "#f43f5e", "#2dd4bf", "#fbbf24"
    };

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : PORT;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/tasks", new TasksHandler());
        server.createContext("/api/spin", new SpinHandler());
        server.createContext("/api/tasks/add", new AddTaskHandler());
        server.createContext("/api/tasks/delete", new DeleteTaskHandler());
        server.createContext("/api/center", new CenterImageHandler());
        server.createContext("/", new StaticHandler());
        server.createContext("/api/tasks/clear", new ClearTasksHandler());
        server.createContext("/api/random-gif", new RandomGifHandler());

        server.setExecutor(null);
        server.start();

        System.out.println("Колесо задач: http://localhost:" + port);
    }

    // ── Утилита: добавить CORS-заголовки ─────────────────────────────────────
    static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    // ── Утилита: проверка HTTP-метода ─────────────────────────────────────────
    /**
     * Возвращает true и отвечает 405, если метод не совпадает.
     * Для OPTIONS сразу отвечает 204 (preflight).
     */
    static boolean rejectWrongMethod(HttpExchange exchange, String expected) throws IOException {
        String method = exchange.getRequestMethod();
        if (method.equalsIgnoreCase("OPTIONS")) {
            addCorsHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        if (!method.equalsIgnoreCase(expected)) {
            byte[] body = ("Method Not Allowed, expected: " + expected).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(405, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            return true;
        }
        return false;
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    static class TasksHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (rejectWrongMethod(exchange, "GET")) return;
            List<Task> tasks = loadTasks();
            sendJson(exchange, toJson(tasks));
        }
    }

    static class SpinHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (rejectWrongMethod(exchange, "GET")) return;

            List<Task> tasks = loadTasks();
            if (tasks.size() < 2) {
                sendJson(exchange, "{\"success\":false,\"error\":\"min 2 tasks\"}");
                return;
            }

            int winnerIndex = new Random().nextInt(tasks.size());
            Task winner = tasks.get(winnerIndex);

            // Парсим ?time= с явной валидацией типа
            int spinTime = 5;
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("time=")) {
                        String value = param.substring(5);
                        if (value.matches("\\d+")) {
                            spinTime = Math.min(10, Math.max(2, Integer.parseInt(value)));
                        }
                        // иначе молча оставляем дефолт 5
                    }
                }
            }

            int count = tasks.size();
            double segmentAngle = 2 * Math.PI / count;
            int rotations = 5 + new Random().nextInt(4);
            double targetAngle = rotations * 2 * Math.PI
                    + 3 * Math.PI / 2
                    - (winnerIndex + 0.5) * segmentAngle;

            StringBuilder sb = new StringBuilder();
            sb.append("{\"success\":true,\"data\":{");
            sb.append("\"winner\":{");
            sb.append("\"id\":\"").append(winner.id).append("\",");
            sb.append("\"text\":\"").append(escapeJson(winner.text)).append("\",");
            sb.append("\"color\":\"").append(winner.color).append("\"");
            sb.append("},");
            sb.append("\"winnerIndex\":").append(winnerIndex).append(",");
            sb.append("\"targetAngle\":").append(String.format(Locale.US, "%.6f", targetAngle)).append(",");
            sb.append("\"spinTime\":").append(spinTime);
            sb.append("}}");
            sendJson(exchange, sb.toString());
        }
    }

    static class AddTaskHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (rejectWrongMethod(exchange, "POST")) return;

            String body = readBody(exchange);
            String text = extractJsonString(body, "text");
            if (text == null || text.trim().isEmpty()) {
                sendJson(exchange, "{\"success\":false,\"error\":\"empty text\"}");
                return;
            }

            FILE_LOCK.lock();
            try {
                List<Task> tasks = loadTasks();

                Task task = new Task();
                task.id = UUID.randomUUID().toString();
                task.text = text.trim();
                task.color = getUniqueColor(tasks);
                tasks.add(task);
                saveTasks(tasks);

                StringBuilder sb = new StringBuilder();
                sb.append("{\"success\":true,\"data\":{");
                sb.append("\"id\":\"").append(task.id).append("\",");
                sb.append("\"text\":\"").append(escapeJson(task.text)).append("\",");
                sb.append("\"color\":\"").append(task.color).append("\"");
                sb.append("}}");
                sendJson(exchange, sb.toString());
            } finally {
                FILE_LOCK.unlock();
            }
        }
    }

    static class DeleteTaskHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (rejectWrongMethod(exchange, "POST")) return;

            String body = readBody(exchange);
            String id = extractJsonString(body, "id");
            if (id == null || id.isEmpty()) {
                sendJson(exchange, "{\"success\":false,\"error\":\"no id\"}");
                return;
            }

            String forceStr = extractJsonString(body, "force");
            boolean force = "true".equalsIgnoreCase(forceStr);

            FILE_LOCK.lock();
            try {
                List<Task> tasks = loadTasks();

                tasks.removeIf(t -> t.id.equals(id));
                saveTasks(tasks);
                sendJson(exchange, "{\"success\":true,\"data\":null}");
            } finally {
                FILE_LOCK.unlock();
            }
        }
    }

    static class ClearTasksHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (rejectWrongMethod(exchange, "POST")) return;
            FILE_LOCK.lock();
            try {
                saveTasks(new ArrayList<>());
                sendJson(exchange, "{\"success\":true,\"data\":null}");
            } finally {
                FILE_LOCK.unlock();
            }
        }
    }

    static class RandomGifHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (rejectWrongMethod(exchange, "GET")) return;

            File dir = new File("gifs");
            File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".gif"));

            if (files == null || files.length == 0) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            File gif = files[new Random().nextInt(files.length)];
            addCorsHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", "image/gif");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, gif.length());
            try (OutputStream os = exchange.getResponseBody()) {
                Files.copy(gif.toPath(), os);
            }
        }
    }

    static class CenterImageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (rejectWrongMethod(exchange, "GET")) return;

            File dir = new File(".");
            File[] files = dir.listFiles((d, name) ->
                    name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"));

            if (files != null && files.length > 0) {
                // Стабильный порядок: сортируем по имени, берём первый
                Arrays.sort(files, Comparator.comparing(File::getName));
                File imgFile = files[0];
                String contentType = imgFile.getName().endsWith(".png") ? "image/png" : "image/jpeg";

                addCorsHeaders(exchange);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                long len = imgFile.length();
                exchange.sendResponseHeaders(200, len);
                try (OutputStream os = exchange.getResponseBody()) {
                    Files.copy(imgFile.toPath(), os);
                }
            } else {
                byte[] notFound = "no image".getBytes();
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(notFound);
                }
            }
        }
    }

    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            File file = new File("." + path);
            if (file.exists() && !file.isDirectory()) {
                String contentType = "text/html; charset=utf-8";
                if (path.endsWith(".css")) contentType = "text/css";
                else if (path.endsWith(".js")) contentType = "application/javascript";

                addCorsHeaders(exchange);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, file.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    Files.copy(file.toPath(), os);
                }
            } else {
                byte[] notFound = "404".getBytes();
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(notFound);
                }
            }
        }
    }

    // ── Утилиты ───────────────────────────────────────────────────────────────

    /**
     * Выбирает цвет, которого ещё нет среди задач.
     * Если палитра исчерпана — перебирает цвета циклически по индексу,
     * чтобы избежать случайного дубля.
     */
    static String getUniqueColor(List<Task> existingTasks) {
        Set<String> usedColors = new HashSet<>();
        for (Task t : existingTasks) {
            usedColors.add(t.color.toLowerCase());
        }

        for (String c : ALL_COLORS) {
            if (!usedColors.contains(c.toLowerCase())) {
                return c;
            }
        }
        // Палитра исчерпана — берём по индексу (детерминировано)
        return ALL_COLORS[existingTasks.size() % ALL_COLORS.length];
    }

    static void sendJson(HttpExchange exchange, String json) throws IOException {
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    /**
     * Простой парсер JSON-строк. Работает корректно для экранированных символов
     * (\", \\, \n, \r, \t) внутри значений.
     */
    static String extractJsonString(String json, String key) {
        if (json == null) return null;
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) return null;
        int colon = json.indexOf(":", keyIndex + key.length() + 2);
        if (colon < 0) return null;

        // Пропускаем пробелы после ':'
        int pos = colon + 1;
        while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) pos++;
        if (pos >= json.length() || json.charAt(pos) != '"') return null;
        pos++; // пропускаем открывающую кавычку

        StringBuilder sb = new StringBuilder();
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '\\' && pos + 1 < json.length()) {
                char next = json.charAt(pos + 1);
                switch (next) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    default:   sb.append(next); break;
                }
                pos += 2;
            } else if (c == '"') {
                break; // конец строки
            } else {
                sb.append(c);
                pos++;
            }
        }
        return sb.toString();
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    static String toJson(List<Task> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"success\":true,\"data\":[");
        for (int i = 0; i < tasks.size(); i++) {
            if (i > 0) sb.append(",");
            Task t = tasks.get(i);
            sb.append("{\"id\":\"").append(t.id).append("\",");
            sb.append("\"text\":\"").append(escapeJson(t.text)).append("\",");
            sb.append("\"color\":\"").append(t.color).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    static List<Task> loadTasks() {
        File file = new File(TASKS_FILE);
        if (!file.exists()) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(Path.of(TASKS_FILE));
            return parseTasks(json);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    static List<Task> parseTasks(String json) {
        List<Task> tasks = new ArrayList<>();
        int i = 0;
        while (i < json.length()) {
            int idKey = json.indexOf("\"id\"", i);
            if (idKey < 0) break;
            int idColon = json.indexOf(":", idKey);
            int idQ1 = json.indexOf("\"", idColon);
            int idQ2 = json.indexOf("\"", idQ1 + 1);
            String id = (idQ1 >= 0 && idQ2 > idQ1) ? json.substring(idQ1 + 1, idQ2) : "";

            int textKey = json.indexOf("\"text\"", idQ2);
            if (textKey < 0) break;
            // Используем улучшенный extractJsonString для корректной обработки спецсимволов
            // Вырезаем подстроку начиная с "text" для локального парсинга
            String textValue = extractJsonString(json.substring(textKey), "text");
            if (textValue == null) textValue = "";

            int colorKey = json.indexOf("\"color\"", textKey);
            String colorValue = extractJsonString(json.substring(colorKey), "color");
            if (colorValue == null || colorValue.isEmpty()) colorValue = "#888888";

            Task t = new Task();
            t.id = id;
            t.text = textValue;
            t.color = colorValue;
            tasks.add(t);

            // Двигаемся дальше — находим конец блока color
            int colorColon = json.indexOf(":", colorKey);
            int colorQ1 = json.indexOf("\"", colorColon);
            int colorQ2 = json.indexOf("\"", colorQ1 + 1);
            i = colorQ2 + 1;
        }
        return tasks;
    }

    static void saveTasks(List<Task> tasks) {
        try {
            Files.writeString(Path.of(TASKS_FILE), toJson(tasks));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class Task {
        String id;
        String text;
        String color;
    }
}