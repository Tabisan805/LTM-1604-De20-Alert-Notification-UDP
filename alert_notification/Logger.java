package alert_notification;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Logger extends JFrame {
    private static final String MULTICAST_ADDR = "230.0.0.0";
    private static final int PORT = 4446;

    private final DefaultTableModel tableModel;
    private final JLabel statusLabel = new JLabel("Listening on " + MULTICAST_ADDR + ":" + PORT);
    private final JLabel countLabel = new JLabel("Total alerts: 0");

    private MulticastSocket socket;
    private InetAddress group;
    private int alertCount = 0;
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public Logger() {
        setTitle("UDP Warning Logger");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Header
        JPanel header = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                Color c1 = new Color(19, 182, 112);
                Color c2 = new Color(26, 152, 163);
                g2.setPaint(new GradientPaint(0,0,c1, getWidth(), getHeight(), c2));
                g2.fillRect(0,0,getWidth(),getHeight());
            }
        };
        header.setPreferredSize(new Dimension(700, 80));
        header.setLayout(new BorderLayout());
        JLabel title = new JLabel("UDP Warning Logger", SwingConstants.CENTER);
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 26));
        JLabel subtitle = new JLabel("Alert Logging & History Viewer", SwingConstants.CENTER);
        subtitle.setForeground(new Color(230,230,230));
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 14));
        header.add(title, BorderLayout.CENTER);
        header.add(subtitle, BorderLayout.SOUTH);

        // Table
        String[] columns = {"Timestamp", "Level", "Message"};
        tableModel = new DefaultTableModel(columns, 0);
        JTable table = new JTable(tableModel);
        JScrollPane scroll = new JScrollPane(table);

        JPanel centerPanel = new JPanel(new BorderLayout(6,6));
        centerPanel.setBorder(new EmptyBorder(10,10,10,10));
        centerPanel.add(scroll, BorderLayout.CENTER);

        // Buttons
        JButton exportBtn = new JButton("Export CSV");
        exportBtn.addActionListener(e -> exportToCSV());
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(exportBtn);
        centerPanel.add(btnPanel, BorderLayout.SOUTH);

        // Status bar
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(new EmptyBorder(6,10,6,10));
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(countLabel, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);

        startListening();
    }

    private void startListening() {
        new Thread(() -> {
            try {
                group = InetAddress.getByName(MULTICAST_ADDR);
                socket = new MulticastSocket(PORT);
                socket.joinGroup(group);

                byte[] buf = new byte[1024];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                    SwingUtilities.invokeLater(() -> handleMessage(msg));
                }
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Socket error: " + e.getMessage()));
            }
        }, "Logger-Listener").start();
    }

    private void handleMessage(String msg) {
        alertCount++;
        countLabel.setText("Total alerts: " + alertCount);

        String timestamp = sdf.format(new Date());
        // Format message: "LEVEL: text"
        String level = "INFO";
        String content = msg;
        if (msg.contains(":")) {
            int idx = msg.indexOf(':');
            level = msg.substring(0, idx).trim();
            content = msg.substring(idx+1).trim();
        }

        tableModel.addRow(new Object[]{timestamp, level, content});
    }

    private void exportToCSV() {
        File file = new File("client_logs.csv");
        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < tableModel.getColumnCount(); j++) {
                    Object val = tableModel.getValueAt(i, j);
                    sb.append(val == null ? "" : val.toString());
                    if (j < tableModel.getColumnCount()-1) sb.append(",");
                }
                pw.println(sb);
            }
            JOptionPane.showMessageDialog(this, "Exported to " + file.getAbsolutePath());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Logger().setVisible(true));
    }
}
