import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LightsOutGame {
    public static final int GRID_SIZE = 5;
    private static final int MIN_INDEX = 0;
    private static final int MAX_INDEX = GRID_SIZE - 1;
    private static final int LIGHT_OFF = 0;
    private static final int LIGHT_ON = 1;
    private static final int TOP_ROW_COMBINATIONS = 1 << GRID_SIZE; // 32 possibilities
    private static final int DEFAULT_PORT = 8080;

    private static final int[][] ORTHOGONAL_OFFSETS = {
        {0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}
    };

    private static final Random RANDOM = new Random();

    private static boolean isValidCoord(int value) {
        return value >= MIN_INDEX && value <= MAX_INDEX;
    }

    private static void validateCoords(int row, int col) {
        if (!isValidCoord(row) || !isValidCoord(col)) {
            throw new IllegalArgumentException(
                "Row and col must be between " + MIN_INDEX + " and " + MAX_INDEX + "."
            );
        }
    }

    public static int[][] copyBoard(int[][] board) {
        int[][] copy = new int[GRID_SIZE][GRID_SIZE];
        for (int row = 0; row < GRID_SIZE; row++) {
            System.arraycopy(board[row], 0, copy[row], 0, GRID_SIZE);
        }
        return copy;
    }

    /**
     * Returns a new 5x5 board resulting from clicking (row, col).
     * Does not modify the original board.
     */
    public static int[][] toggle(int[][] board, int row, int col) {
        validateCoords(row, col);
        int[][] nextBoard = copyBoard(board);

        for (int[] offset : ORTHOGONAL_OFFSETS) {
            int targetRow = row + offset[0];
            int targetCol = col + offset[1];
            if (isValidCoord(targetRow) && isValidCoord(targetCol)) {
                nextBoard[targetRow][targetCol] =
                    (nextBoard[targetRow][targetCol] == LIGHT_ON) ? LIGHT_OFF : LIGHT_ON;
            }
        }
        return nextBoard;
    }

    /**
     * Returns true if every light on the board is off (0), false otherwise.
     */
    public static boolean isSolved(int[][] board) {
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                if (board[row][col] != LIGHT_OFF) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Generates a random 5x5 board guaranteed to be solvable and not already solved.
     */
    public static int[][] generateBoard() {
        int[][] candidate = new int[GRID_SIZE][GRID_SIZE];

        do {
            candidate = new int[GRID_SIZE][GRID_SIZE];
            for (int row = 0; row < GRID_SIZE; row++) {
                for (int col = 0; col < GRID_SIZE; col++) {
                    if (RANDOM.nextBoolean()) {
                        candidate = toggle(candidate, row, col);
                    }
                }
            }
        } while (isSolved(candidate));

        return candidate;
    }

    /**
     * Computes the next {row, col} move (0-4) to solve the board.
     * Does not modify the input board. Returns null if already solved.
     */
    public static int[] hint(int[][] board) {
        if (isSolved(board)) {
            return null;
        }

        // Try all 32 top-row click patterns and chase lights down rows 1..4.
        // Because toggling twice cancels out, net presses mod 2 give a valid move set.
        for (int mask = 0; mask < TOP_ROW_COMBINATIONS; mask++) {
            int[][] simBoard = copyBoard(board);
            int[][] pressCount = new int[GRID_SIZE][GRID_SIZE];

            for (int col = 0; col < GRID_SIZE; col++) {
                if (((mask >> col) & 1) == 1) {
                    simBoard = toggle(simBoard, 0, col);
                    pressCount[0][col] ^= 1;
                }
            }

            for (int row = 1; row < GRID_SIZE; row++) {
                for (int col = 0; col < GRID_SIZE; col++) {
                    if (simBoard[row - 1][col] == LIGHT_ON) {
                        simBoard = toggle(simBoard, row, col);
                        pressCount[row][col] ^= 1;
                    }
                }
            }

            if (isSolved(simBoard)) {
                for (int row = 0; row < GRID_SIZE; row++) {
                    for (int col = 0; col < GRID_SIZE; col++) {
                        if (pressCount[row][col] == 1) {
                            return new int[] { row, col };
                        }
                    }
                }
            }
        }

        throw new IllegalArgumentException("Provided board is not solvable.");
    }

    // ---------- Lightweight HTTP API for JS GUI ----------

    public static void startServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/new-board", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            int[][] newBoard = generateBoard();
            sendJson(exchange, 200, "{\"board\":" + boardToJson(newBoard) + "}");
        });

        server.createContext("/api/toggle", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int[][] board = parseBoard(extractJsonArray(body, "board"));
                int row = extractJsonInt(body, "row");
                int col = extractJsonInt(body, "col");

                int[][] updated = toggle(board, row, col);
                boolean solved = isSolved(updated);
                sendJson(
                    exchange,
                    200,
                    "{\"board\":" + boardToJson(updated) + ",\"solved\":" + solved + "}"
                );
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/hint", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int[][] board = parseBoard(extractJsonArray(body, "board"));
                boolean solved = isSolved(board);
                if (solved) {
                    sendJson(exchange, 200, "{\"solved\":true,\"hint\":null}");
                    return;
                }
                int[] move = hint(board);
                sendJson(
                    exchange,
                    200,
                    "{\"solved\":false,\"hint\":[" + move[0] + "," + move[1] + "]}"
                );
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println("LightsOutGame API running at http://localhost:" + port);
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String extractJsonArray(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\[\\[.*?\\]\\])", Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (!m.find()) {
            throw new IllegalArgumentException("Missing or invalid '" + key + "' array.");
        }
        return m.group(1);
    }

    private static int extractJsonInt(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)");
        Matcher m = p.matcher(json);
        if (!m.find()) {
            throw new IllegalArgumentException("Missing or invalid '" + key + "' integer.");
        }
        return Integer.parseInt(m.group(1));
    }

    private static int[][] parseBoard(String arrayJson) {
        String cleaned = arrayJson.trim();
        if (!cleaned.startsWith("[[") || !cleaned.endsWith("]]")) {
            throw new IllegalArgumentException("Board must be a 5x5 matrix.");
        }
        String inner = cleaned.substring(2, cleaned.length() - 2);
        String[] rowStrings = inner.split("\\]\\s*,\\s*\\[");
        if (rowStrings.length != GRID_SIZE) {
            throw new IllegalArgumentException("Board must have " + GRID_SIZE + " rows.");
        }

        int[][] parsed = new int[GRID_SIZE][GRID_SIZE];
        for (int r = 0; r < GRID_SIZE; r++) {
            String[] cols = rowStrings[r].split("\\s*,\\s*");
            if (cols.length != GRID_SIZE) {
                throw new IllegalArgumentException("Each row must have " + GRID_SIZE + " columns.");
            }
            for (int c = 0; c < GRID_SIZE; c++) {
                parsed[r][c] = Integer.parseInt(cols[c].trim());
            }
        }
        return parsed;
    }

    private static String boardToJson(int[][] board) {
        StringBuilder sb = new StringBuilder("[");
        for (int r = 0; r < GRID_SIZE; r++) {
            if (r > 0) sb.append(",");
            sb.append(Arrays.toString(board[r]).replace(" ", ""));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static void main(String[] args) throws IOException {
        startServer(DEFAULT_PORT);
    }
}