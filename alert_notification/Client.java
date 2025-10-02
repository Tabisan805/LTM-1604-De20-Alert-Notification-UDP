package alert_notification;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Client.java
 * - Gửi heartbeat đều đặn tới server
 * - Nhận cảnh báo từ multicast group
 * - Hiển thị log + popup
 * - Gửi QUIT khi thoát
 */
public class Client extends JFrame {

    private static final String MULTICAST_GROUP = "230.0.0.1";
    private static final int SERVER_PORT = 5000;     // multicast port (must match server)
    private static final int HEARTBEAT_PORT = 5001;  // server heartbeat listener port
    private static final int HEARTBEAT_INTERVAL = 5; // seconds

    private final String clientId;
    private final JTextArea logArea;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private MulticastSocket multicastSocket;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public Client(String clientId) {
        super("Client - " + clientId);
        this.clientId = clientId;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(600, 400);
        setLocationRelativeTo(null);

        // ====== UI ======
        JLabel title = new JLabel("Client ID: " + clientId, SwingConstants.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 24));
        title.setBorder(new EmptyBorder(10, 10, 10, 10));
        add(title, BorderLayout.NORTH);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder("Received Alerts"));
        add(scroll, BorderLayout.CENTER);

        JButton exitBtn = new JButton("Thoát");
        exitBtn.addActionListener(e -> {
            shutdown();
            System.exit(0);
        });
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(exitBtn);
        add(bottom, BorderLayout.SOUTH);

        // Start tasks
        startHeartbeat();
        startReceiver();
    }

    // ====== Heartbeat ======
    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                String msg = "HEARTBEAT:" + clientId;
                byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                InetAddress serverAddr = InetAddress.getByName("127.0.0.1"); // chỉnh nếu server remote
                DatagramPacket packet = new DatagramPacket(data, data.length, serverAddr, HEARTBEAT_PORT);
                socket.send(packet);
            } catch (IOException e) {
                appendLog("⚠ Heartbeat error: " + e.getMessage(), "ERROR");
            }
        }, 0, HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
    }

    // ====== Nhận multicast ======
    private void startReceiver() {
        scheduler.execute(() -> {
            try {
                multicastSocket = new MulticastSocket(SERVER_PORT);
                InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
                multicastSocket.joinGroup(group);

                appendLog("✅ Joined multicast group " + MULTICAST_GROUP + ":" + SERVER_PORT, "INFO");

                byte[] buf = new byte[1024];
                while (!Thread.currentThread().isInterrupted()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    multicastSocket.receive(packet);
                    String recv = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();

                    appendLog("📩 Received alert: " + recv, "ALERT");

                    // Xử lý popup vượt cấp
                    SwingUtilities.invokeLater(() -> showAlertPopup(recv));
                }

            } catch (IOException e) {
                appendLog("Receiver stopped: " + e.getMessage(), "ERROR");
            }
        });
    }

    // ====== Hiển thị popup theo cấp độ ======
    private void showAlertPopup(String message) {
        if (message.startsWith("[ERROR]")) {
            JOptionPane.showMessageDialog(this, message, "🚨 ERROR Alert", JOptionPane.ERROR_MESSAGE);
        } else if (message.startsWith("[WARNING]")) {
            JOptionPane.showMessageDialog(this, message, "⚠ WARNING Alert", JOptionPane.WARNING_MESSAGE);
        } else {
            // INFO hoặc các loại khác
            JOptionPane.showMessageDialog(this, message, "ℹ Info Alert", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // ====== Log ======
    private void appendLog(String msg, String type) {
        String line = TIME_FMT.format(Instant.now()) + " [" + type + "] " + msg + "\n";
        SwingUtilities.invokeLater(() -> {
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ====== Shutdown ======
    private void shutdown() {
        appendLog("🔻 Client shutting down...", "INFO");
        // Gửi QUIT
        try (DatagramSocket socket = new DatagramSocket()) {
            String msg = "QUIT:" + clientId;
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            InetAddress serverAddr = InetAddress.getByName("127.0.0.1");
            DatagramPacket packet = new DatagramPacket(data, data.length, serverAddr, HEARTBEAT_PORT);
            socket.send(packet);
            appendLog("Đã gửi QUIT tới server.", "QUIT");
        } catch (IOException e) {
            appendLog("⚠ Lỗi khi gửi QUIT: " + e.getMessage(), "ERROR");
        }

        scheduler.shutdownNow();
        try {
            if (multicastSocket != null) {
                multicastSocket.leaveGroup(InetAddress.getByName(MULTICAST_GROUP));
                multicastSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    // ====== Main ======
    public static void main(String[] args) {
        String id;
        if (args.length > 0) {
            id = args[0];
        } else {
            id = JOptionPane.showInputDialog(null, "Nhập Client ID:", "Client", JOptionPane.QUESTION_MESSAGE);
            if (id == null || id.trim().isEmpty()) {
                JOptionPane.showMessageDialog(null, "Client ID không được để trống!", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        String finalId = id.trim();
        SwingUtilities.invokeLater(() -> {
            Client c = new Client(finalId);
            c.setVisible(true);
            Runtime.getRuntime().addShutdownHook(new Thread(c::shutdown));
        });
    }
}
