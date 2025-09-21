package alert_notification;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

public class Client extends JFrame {
    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private static final String MULTICAST_ADDR = "230.0.0.0";
    private static final int PORT = 4446;

    private final JTextArea alertArea = new JTextArea(12, 50);
    private final JLabel entriesLabel = new JLabel("0 alerts");
    private final JLabel statusLabel = new JLabel("Listening on " + MULTICAST_ADDR + ":" + PORT);
    private final JLabel updateLabel = new JLabel("Last update: none");

    private int alertCount = 0;
    private MulticastSocket socket;
    private InetAddress group;
    private String clientId = "client-" + (int)(Math.random()*1000);

    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public Client() {
        setTitle("UDP Warning Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        initUI();
        pack();
        setLocationRelativeTo(null);

        startListening();
        startHeartbeat();
    }

    private void initUI() {
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
        JLabel title = new JLabel("Hệ thống cảnh báo UDP", SwingConstants.CENTER);
        title.setForeground(Color.WHITE);
        title.setFont(new Font("SansSerif", Font.BOLD, 26));
        JLabel subtitle = new JLabel("Alert Receiver & Notification System", SwingConstants.CENTER);
        subtitle.setForeground(new Color(230,230,230));
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 14));
        header.add(title, BorderLayout.CENTER);
        header.add(subtitle, BorderLayout.SOUTH);

        // Alert panel
        JPanel alertPanel = new JPanel(new BorderLayout(6,6));
        alertPanel.setBorder(new EmptyBorder(10,10,10,10));
        alertArea.setEditable(false);
        alertArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane scroll = new JScrollPane(alertArea);
        alertPanel.add(entriesLabel, BorderLayout.NORTH);
        alertPanel.add(scroll, BorderLayout.CENTER);

        // Status bar
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(new EmptyBorder(6,10,6,10));
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(updateLabel, BorderLayout.EAST);

        // Layout
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(header, BorderLayout.NORTH);
        getContentPane().add(alertPanel, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);
    }

    private void startListening() {
        new Thread(() -> {
            try {
                group = InetAddress.getByName(MULTICAST_ADDR);
                socket = new MulticastSocket(PORT);
                socket.joinGroup(group);

                sendRegister();

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
        }, "Client-Listener").start();
    }

    private void handleMessage(String msg) {
        alertCount++;
        String timestamp = sdf.format(new Date());
        alertArea.append("[" + timestamp + "] " + msg + "\n");
        alertArea.setCaretPosition(alertArea.getDocument().getLength());
        entriesLabel.setText(alertCount + " alerts");
        updateLabel.setText("Last update: " + timestamp);
    }

    private void sendRegister() {
        sendMessage("REGISTER:" + clientId);
    }

    private void sendHeartbeat() {
        sendMessage("HEARTBEAT:" + clientId);
    }

    private void sendMessage(String text) {
        try {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, group, PORT);
            MulticastSocket sendSock = new MulticastSocket();
            sendSock.send(packet);
            sendSock.close();
        } catch (IOException ignored) {}
    }

    private void startHeartbeat() {
        Timer timer = new Timer(10_000, e -> sendHeartbeat()); // mỗi 10s gửi heartbeat
        timer.start();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Client().setVisible(true));
    }
}
