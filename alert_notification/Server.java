package alert_notification;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;

/**
 * Server.java
 * Máy chủ cảnh báo UDP Multicast
 * - Gửi cảnh báo đến multicast group
 * - Nhận heartbeat / QUIT từ client
 * - Quản lý danh sách client (không trùng ID)
 * - Log auto xuống dòng
 * - Lịch sử lưu file alerts.log
 * - Có chế độ auto send (gửi cảnh báo ngẫu nhiên định kỳ)
 */
public class Server extends JFrame {

    private static final String MULTICAST_GROUP = "230.0.0.1";
    private static final int PORT = 5000;
    private static final int HEARTBEAT_PORT = 5001;
    private static final int CLIENT_TIMEOUT_SECONDS = 20;

    private final JComboBox<String> levelCombo;
    private final JTextField messageField;
    private final JTextArea logArea;
    private final ClientsTableModel clientsModel;
    private final DefaultListModel<String> historyModel;

    private final ConcurrentHashMap<String, ClientInfo> clients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

    private ScheduledFuture<?> autoSendTask;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public Server() {
        super("Máy chủ cảnh báo");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(950, 600);
        setLocationRelativeTo(null);

        // ===== Title =====
        JLabel titleLabel = new JLabel("Máy chủ cảnh báo", SwingConstants.CENTER);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 28));
        titleLabel.setBorder(new EmptyBorder(12, 12, 8, 12));
        add(titleLabel, BorderLayout.NORTH);

        // ===== Form nhập cảnh báo =====
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0;
        form.add(new JLabel("Alert Level:"), gbc);

        levelCombo = new JComboBox<>(new String[]{"INFO", "WARNING", "ERROR"});
        gbc.gridx = 1; gbc.weightx = 1.0;
        form.add(levelCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        form.add(new JLabel("Message:"), gbc);

        messageField = new JTextField();
        gbc.gridx = 1; gbc.weightx = 1.0;
        form.add(messageField, gbc);

        // Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
        JButton sendBtn = new JButton("Send Alert");
        JButton autoBtn = new JButton("Auto Send");
        JButton checkLogBtn = new JButton("Check Log");
        btnPanel.add(sendBtn);
        btnPanel.add(autoBtn);
        btnPanel.add(checkLogBtn);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        northPanel.add(form, BorderLayout.CENTER);
        northPanel.add(btnPanel, BorderLayout.SOUTH);

        add(northPanel, BorderLayout.CENTER);

        // ===== Split log & tabs =====
        JSplitPane split = new JSplitPane();
        split.setResizeWeight(0.6);
        split.setPreferredSize(new Dimension(950, 360));
        add(split, BorderLayout.SOUTH);

        // Log
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBorder(BorderFactory.createTitledBorder("Log"));
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        leftPanel.add(logScroll, BorderLayout.CENTER);

        // Tabs
        JTabbedPane tabbed = new JTabbedPane();
        clientsModel = new ClientsTableModel();
        JTable clientsTable = new JTable(clientsModel);
        JScrollPane clientsScroll = new JScrollPane(clientsTable);
        tabbed.addTab("Client", clientsScroll);

        historyModel = new DefaultListModel<>();
        JList<String> historyList = new JList<>(historyModel);
        JScrollPane historyScroll = new JScrollPane(historyList);
        tabbed.addTab("Lịch sử", historyScroll);

        split.setLeftComponent(leftPanel);
        split.setRightComponent(tabbed);

        // ===== Button actions =====
        sendBtn.addActionListener(this::onSend);
        autoBtn.addActionListener(e -> toggleAutoSend(autoBtn));
        checkLogBtn.addActionListener(e -> showLogDialog());

        // Load history
        for (String entry : Log.getHistory()) {
            historyModel.addElement(entry);
        }

        // Start background tasks
        startHeartbeatListener();
        startClientReaper();

        appendLog("Server đã khởi động. Multicast group: " + MULTICAST_GROUP + ":" + PORT, "INFO");
    }

    // ===== Send alert thủ công =====
    private void onSend(ActionEvent e) {
        String level = (String) levelCombo.getSelectedItem();
        String msg = messageField.getText().trim();
        if (msg.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a message.", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        sendAlert(level, msg, "SEND");
        messageField.setText("");
    }

    // ===== Gửi alert (chung cho auto + manual) =====
    private void sendAlert(String level, String msg, String logType) {
        String payload = "[" + level + "] " + msg;
        try {
            sendMulticast(payload);
            appendLog("Sent alert: " + payload, logType);
            Log.saveAlert(level, msg);
            List<String> h = Log.getHistory();
            if (!h.isEmpty()) historyModel.addElement(h.get(h.size() - 1));
        } catch (IOException ex) {
            appendLog("Error sending alert: " + ex.getMessage(), "ERROR");
        }
    }

    // ===== Auto send toggle =====
    private void toggleAutoSend(JButton btn) {
        if (autoSendTask == null || autoSendTask.isCancelled()) {
            autoSendTask = scheduler.scheduleAtFixedRate(() -> {
                String[] levels = {"INFO", "WARNING", "ERROR"};
                String level = levels[new Random().nextInt(levels.length)];
                String msg = "Auto generated alert at " + TIME_FMT.format(Instant.now());
                sendAlert(level, msg, "AUTO");
            }, 0, 15, TimeUnit.SECONDS);

            appendLog("Auto send started (every 15s).", "AUTO");
            btn.setText("Stop Auto");
        } else {
            autoSendTask.cancel(true);
            appendLog("Auto send stopped.", "AUTO");
            btn.setText("Auto Send");
        }
    }

    private void showLogDialog() {
        JTextArea ta = new JTextArea(logArea.getText());
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(700, 400));
        JOptionPane.showMessageDialog(this, sp, "Server Log", JOptionPane.INFORMATION_MESSAGE);
    }

    // ===== Log =====
    private void appendLog(String text, String type) {
        String line = TIME_FMT.format(Instant.now()) + " [" + type + "] " + text + "\n";
        SwingUtilities.invokeLater(() -> {
            logArea.append(line);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ===== Multicast send =====
    private void sendMulticast(String message) throws IOException {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
        DatagramSocket socket = new DatagramSocket();
        DatagramPacket packet = new DatagramPacket(data, data.length, group, PORT);
        socket.send(packet);
        socket.close();
    }

    // ===== Heartbeat listener & QUIT =====
    private void startHeartbeatListener() {
        scheduler.execute(() -> {
            try (DatagramSocket socket = new DatagramSocket(HEARTBEAT_PORT)) {
                appendLog("Heartbeat listener started on port " + HEARTBEAT_PORT, "INFO");
                byte[] buf = new byte[1024];
                while (!Thread.currentThread().isInterrupted()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    String recv = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    InetAddress addr = packet.getAddress();
                    int port = packet.getPort();

                    if (recv.startsWith("QUIT:")) {
                        String clientId = recv.substring("QUIT:".length()).trim();
                        if (clientId.isEmpty()) clientId = addr.getHostAddress();
                        clients.remove(clientId);
                        appendLog("Client " + clientId + " đã thoát (QUIT)", "QUIT");
                        SwingUtilities.invokeLater(() -> clientsModel.setClients(new ArrayList<>(clients.values())));
                        continue;
                    }

                    String clientId;
                    if (recv.startsWith("HEARTBEAT:")) {
                        clientId = recv.substring("HEARTBEAT:".length()).trim();
                        if (clientId.isEmpty()) clientId = addr.getHostAddress();
                    } else {
                        clientId = addr.getHostAddress();
                    }

                    long now = System.currentTimeMillis();
                    clients.compute(clientId, (k, old) -> {
                        if (old == null) {
                            appendLog("New client joined: " + k + " (" + addr.getHostAddress() + ")", "JOIN");
                            return new ClientInfo(k, addr.getHostAddress(), port, now);
                        } else {
                            old.ip = addr.getHostAddress();
                            old.port = port;
                            old.lastSeen = now;
                            return old;
                        }
                    });
                    SwingUtilities.invokeLater(() -> clientsModel.setClients(new ArrayList<>(clients.values())));
                }
            } catch (IOException ex) {
                appendLog("Heartbeat listener error: " + ex.getMessage(), "ERROR");
            }
        });
    }

    // ===== Client timeout =====
    private void startClientReaper() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            List<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, ClientInfo> en : clients.entrySet()) {
                if ((now - en.getValue().lastSeen) > CLIENT_TIMEOUT_SECONDS * 1000L) {
                    toRemove.add(en.getKey());
                }
            }
            for (String k : toRemove) {
                clients.remove(k);
                appendLog("Removed client due to timeout: " + k, "TIMEOUT");
            }
            if (!toRemove.isEmpty()) {
                SwingUtilities.invokeLater(() -> clientsModel.setClients(new ArrayList<>(clients.values())));
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void shutdown() {
        appendLog("Shutting down server...", "INFO");
        scheduler.shutdownNow();
    }

    // ===== ClientInfo & Table =====
    private static class ClientInfo {
        final String id;
        String ip;
        int port;
        volatile long lastSeen;

        ClientInfo(String id, String ip, int port, long lastSeen) {
            this.id = id; this.ip = ip; this.port = port; this.lastSeen = lastSeen;
        }

        String lastSeenStr() {
            return TIME_FMT.format(Instant.ofEpochMilli(lastSeen));
        }
    }

    private class ClientsTableModel extends AbstractTableModel {
        private final String[] cols = {"Client ID", "IP", "Port", "Last Seen"};
        private List<ClientInfo> list = new ArrayList<>();

        public void setClients(List<ClientInfo> newList) {
            newList.sort(Comparator.comparing(c -> c.id));
            this.list = newList;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return list.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int column) { return cols[column]; }
        @Override public Object getValueAt(int row, int col) {
            ClientInfo c = list.get(row);
            switch (col) {
                case 0: return c.id;
                case 1: return c.ip;
                case 2: return c.port;
                case 3: return c.lastSeenStr();
                default: return "";
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Server s = new Server();
            s.setVisible(true);
            Runtime.getRuntime().addShutdownHook(new Thread(s::shutdown));
        });
    }
}
