package alert_notification;

import java.io.*;
import java.util.*;

public class DB {
    private File file;

    public DB(String filename) {
        this.file = new File(filename);
        if (!file.exists()) {
            try (PrintWriter pw = new PrintWriter(new FileWriter(file, true))) {
                pw.println("id,message,level,created_at");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public synchronized void insertAlert(String message, String level) throws IOException {
        int id = getNextId();
        String createdAt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        try (PrintWriter pw = new PrintWriter(new FileWriter(file, true))) {
            pw.printf("%d,%s,%s,%s%n", id, escape(message), escape(level), createdAt);
        }
    }

    public synchronized List<String[]> getAllAlerts() throws IOException {
        List<String[]> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] parts = line.split(",", 4);
                if (parts.length == 4) list.add(parts);
            }
        }
        return list;
    }

    private int getNextId() throws IOException {
        int maxId = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] parts = line.split(",", 2);
                if (parts.length > 0) {
                    int id = Integer.parseInt(parts[0]);
                    if (id > maxId) maxId = id;
                }
            }
        }
        return maxId + 1;
    }

    private String escape(String value) {
        return value.replace(",", " "); // tránh lỗi khi có dấu ,
    }
}
