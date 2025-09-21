package alert_notification;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Set;
import java.util.concurrent.*;

public class Server extends JFrame {
    // Networking
    private static final String MULTICAST_ADDR = "230.0.0.1";
    private static final int PORT = 5000;
    private MulticastSocket multicastSocket;
    private InetAddress group;

    // UI
    private final JComboBox<String> levelCombo = new JComboBox<>(new String[]{"INFO","WARNING","DANGER","CRITICAL"});
    private final JTextField messageField = new JTextField("");
    private final JTextArea logArea = new JTextArea(12, 60);
    private final JLabel clientsLabel = new JLabel("Clients connected: 0");
    private final JLabel serverLabel = new JLabel("Server: " + MULTICAST_ADDR + ":" + PORT);
    private final JLabel entriesLabel = new JLabel("0 entries");

    // Logging
    private static final File CSV = new File("alert_logs.csv");
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // Client tracking
    private final ConcurrentMap<String, Long> clients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // For counting logs
    private int logCount = 0;

    public Server() {
        setTitle("Warning Server");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        initUI();
        pack();
        setLocationRelativeTo(null);
        startNetwork();
        startClientPurgeTask();
    }

    private void initUI() {
        // Header (simple gradient-like panel)
        JPanel header = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                Color c1 = new Color(19, 182, 112);
                Color c2 = new Color(26, 152, 163);
                g2.setPaint(new java.awt.GradientPaint(0,0,c1, getWidth(), getHeight(), c2));
                g2.fillRect(0,0,getWidth(),getHeight());
            }
        };
        header.setPreferredSize(new Dimension(800, 90));
        header.setLayout(new BorderLayout());
        JLabel title = new JLabel("Máy chủ cảnh báo", SwingConstants.CENTER);
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 28));
        JLabel subtitle = new JLabel("", SwingConstants.CENTER);
        subtitle.setForeground(new Color(230,230,230));
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 14));
        header.add(title, BorderLayout.CENTER);
        header.add(subtitle, BorderLayout.SOUTH);

        // Alert panel
        JPanel alertPanel = new JPanel(new GridBagLayout());
        alertPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(12,12,12,12),
                BorderFactory.createLineBorder(new Color(220,220,220))
        ));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(8,8,8,8);
        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST;
        alertPanel.add(new JLabel("<html><b>Alert Level:</b></html>"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0;
        alertPanel.add(levelCombo, c);
        c.gridx = 0; c.gridy = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        alertPanel.add(new JLabel("<html><b>Message:</b></html>"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0;
        alertPanel.add(messageField, c);

        // Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 6));
        JButton sendBtn = new JButton("Send Alert");
        sendBtn.setBackground(new Color(26, 181, 123));
        sendBtn.setForeground(Color.WHITE);
        sendBtn.addActionListener(this::onSend);
        JButton clearBtn = new JButton("Clear Log");
        clearBtn.addActionListener(e -> {
            logArea.setText("");
            logCount = 0;
            entriesLabel.setText(logCount + " entries");
        });
        JButton checkBtn = new JButton("Check Log");
        checkBtn.addActionListener(e -> loadCsvLogToTextArea());
        btnPanel.add(sendBtn);
        btnPanel.add(clearBtn);
        btnPanel.add(checkBtn);

        // Log panel
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Server Activity Log"));
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(logArea);
        logPanel.add(scroll, BorderLayout.CENTER);
        logPanel.add(entriesLabel, BorderLayout.NORTH);

        // Status bar
        JPanel statusBar = new JPanel(new BorderLayout());
        clientsLabel.setForeground(new Color(20,160,90));
        statusBar.setBorder(new EmptyBorder(6,10,6,10));
        statusBar.add(clientsLabel, BorderLayout.WEST);
        statusBar.add(serverLabel, BorderLayout.EAST);

        // Layout main
        JPanel center = new JPanel(new BorderLayout(8,8));
        center.setBorder(new EmptyBorder(12,12,12,12));
        center.add(alertPanel, BorderLayout.NORTH);
        center.add(btnPanel, BorderLayout.CENTER);
        center.add(logPanel, BorderLayout.SOUTH);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(header, BorderLayout.NORTH);
        getContentPane().add(center, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);
    }

    private void startNetwork() {
        try {
            group = InetAddress.getByName(MULTICAST_ADDR);
            multicastSocket = new MulticastSocket(PORT);
            multicastSocket.setReuseAddress(true);
            multicastSocket.joinGroup(group);

            appendLog("Server ready. Lắng nghe trên " + MULTICAST_ADDR + ":" + PORT);
            writeCsvHeaderIfNeeded();

            // Start receiver thread
            Thread receiver = new Thread(this::receiveLoop, "UDP-Receiver");
            receiver.setDaemon(true);
            receiver.start();

        } catch (IOException e) {
            showError("Không thể khởi tạo multicast socket: " + e.getMessage());
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[1024];
        while (!multicastSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                multicastSocket.receive(packet);
                String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                String sender = packet.getAddress().getHostAddress() + ":" + packet.getPort();
                handleIncomingMessage(msg, sender);
            } catch (IOException e) {
                appendLog("Receiver error: " + e.getMessage());
            }
        }
    }

    private void handleIncomingMessage(String msg, String sender) {
        // Expected messages from clients: REGISTER:<id> or HEARTBEAT:<id> or UNREGISTER:<id>
        if (msg.startsWith("REGISTER:") || msg.startsWith("HEARTBEAT:")) {
            String id = msg.substring(msg.indexOf(':') + 1).trim();
            clients.put(id, System.currentTimeMillis());
            updateClientsLabel();
            appendLog(sdf.format(System.currentTimeMillis()) + " - " + id + " registered/heartbeat from " + sender);
        } else if (msg.startsWith("UNREGISTER:")) {
            String id = msg.substring(msg.indexOf(':') + 1).trim();
            clients.remove(id);
            updateClientsLabel();
            appendLog(sdf.format(System.currentTimeMillis()) + " - " + id + " unregistered");
        } else {
            appendLog(sdf.format(System.currentTimeMillis()) + " - Received from " + sender + ": " + msg);
        }
        
    }

    private void updateClientsLabel() {
        SwingUtilities.invokeLater(() -> clientsLabel.setText("Clients connected: " + clients.size()));
    }

    private void onSend(ActionEvent ae) {
        String level = (String) levelCombo.getSelectedItem();
        String msg = messageField.getText().trim();
        if (msg.isEmpty()) {
            showError("Message cannot be empty");
            return;
        }
        String composed = level + ": " + msg;
        sendMulticast(composed);
        String logMsg = sdf.format(System.currentTimeMillis()) + " - Alert sent: " + composed;
        appendLog(logMsg);
        appendCsvLine(sdf.format(System.currentTimeMillis()) + "," + escapeCsv(level) + "," + escapeCsv(msg) + "," + clients.size());
    }

    private void sendMulticast(String message) {
        scheduler.execute(() -> {
            try {
                byte[] data = message.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(data, data.length, group, PORT);
                MulticastSocket sendSocket = new MulticastSocket(); // separate socket for send
                sendSocket.send(packet);
                sendSocket.close();
            } catch (IOException e) {
                appendLog("Send error: " + e.getMessage());
            }
        });
    }

    private void appendLog(String text) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(text + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
            logCount++;
            entriesLabel.setText(logCount + " entries");
        });
    }

    private void showError(String text) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, text, "Error", JOptionPane.ERROR_MESSAGE));
    }

    // CSV functions
    private void writeCsvHeaderIfNeeded() {
        try {
            if (!CSV.exists()) {
                try (PrintWriter pw = new PrintWriter(new FileWriter(CSV, true))) {
                    pw.println("Timestamp,Level,Message,ClientsConnected");
                }
            }
        } catch (IOException e) {
            appendLog("Cannot create CSV: " + e.getMessage());
        }
    }

    private synchronized void appendCsvLine(String line) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(CSV, true))) {
            pw.println(line);
        } catch (IOException e) {
            appendLog("Cannot write CSV: " + e.getMessage());
        }
    }

    private void loadCsvLogToTextArea() {
        scheduler.execute(() -> {
            if (!CSV.exists()) {
                appendLog("CSV file not found.");
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(CSV))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                SwingUtilities.invokeLater(() -> {
                    logArea.setText(sb.toString());
                    entriesLabel.setText((sb.length() == 0 ? 0 : countCsvLines(CSV)) + " entries");
                });
            } catch (IOException e) {
                appendLog("Error reading CSV: " + e.getMessage());
            }
        });
    }

    private int countCsvLines(File f) {
        int c = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            while (br.readLine() != null) c++;
        } catch (IOException ignored) {}
        return c;
    }

    // remove stale clients (no heartbeat for 30 seconds)
    private void startClientPurgeTask() {
        final long STALE_MS = 30_000L;
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (String id : clients.keySet()) {
                Long last = clients.get(id);
                if (last == null || (now - last) > STALE_MS) {
                    clients.remove(id);
                    changed = true;
                    appendLog(sdf.format(now) + " - Client " + id + " timed out and removed.");
                }
            }
            if (changed) updateClientsLabel();
        }, 5, 5, TimeUnit.SECONDS);
    }

    private String escapeCsv(String s) {
        String out = s.replace("\"", "\"\"");
        if (out.contains(",") || out.contains("\"") || out.contains("\n")) {
            return "\"" + out + "\"";
        }
        return out;
    }

    private void shutdown() {
        try {
            if (multicastSocket != null) {
                multicastSocket.leaveGroup(group);
                multicastSocket.close();
            }
        } catch (IOException ignored) {}
        scheduler.shutdownNow();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Server app = new Server();
            app.setVisible(true);

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));
        });
    }
}
