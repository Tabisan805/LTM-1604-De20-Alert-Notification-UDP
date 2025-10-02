package alert_notification;

import java.io.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Log.java
 * Quản lý lịch sử gửi cảnh báo
 * - Ghi log ra file alerts.log
 * - Đọc lại lịch sử khi cần
 */

public class Log {

    private static final String LOG_FILE = "alerts.log";
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * Ghi 1 cảnh báo vào file log
     */
    public static synchronized void saveAlert(String level, String message) {
        String line = FORMATTER.format(Instant.now()) + " [" + level + "] " + message;
        try (FileWriter fw = new FileWriter(LOG_FILE, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            out.println(line);
        } catch (IOException e) {
            System.err.println("⚠ Không thể ghi log: " + e.getMessage());
        }
    }

    /**
     * Đọc lại toàn bộ lịch sử cảnh báo
     */
    public static synchronized List<String> getHistory() {
        List<String> history = new ArrayList<>();
        File f = new File(LOG_FILE);
        if (!f.exists()) return history;

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                history.add(line);
            }
        } catch (IOException e) {
            System.err.println("⚠ Không thể đọc log: " + e.getMessage());
        }
        return history;
    }
}
