package alert_notification;

import javax.swing.*;
import java.awt.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Center extends JFrame {
    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private DB db;
    private DefaultListModel<String> listModel;
    private JList<String> alertList;
    private static Center instance;

    private Center(DB db) {
        this.db = db;
        setTitle("Center - Alert Monitor");
        setSize(600, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        listModel = new DefaultListModel<>();
        alertList = new JList<>(listModel);

        add(new JScrollPane(alertList), BorderLayout.CENTER);

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> loadAlerts());
        add(refreshBtn, BorderLayout.SOUTH);
    }

    private void loadAlerts() {
        try {
            listModel.clear();
            List<String[]> alerts = db.getAllAlerts();
            for (String[] a : alerts) {
                listModel.addElement(String.format("[%s] %s - %s (%s)", a[0], a[1], a[2], a[3]));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("deprecation")
	private static void startUdpReceiver(DB db) {
        try (MulticastSocket socket = new MulticastSocket(4446)) {
            InetAddress group = InetAddress.getByName("230.0.0.1");
            socket.joinGroup(group);

            byte[] buf = new byte[512];
            System.out.println("📡 Center listening UDP on 230.0.0.1:4446 ...");

            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                String received = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                System.out.println("📩 Nhận: " + received);

                String message = received.replaceAll(".*\"message\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                String level = received.replaceAll(".*\"level\"\\s*:\\s*\"([^\"]+)\".*", "$1");

                db.insertAlert(message, level);

                // Nếu UI chưa mở, thì mở lên
                if (instance == null) {
                    SwingUtilities.invokeLater(() -> {
                        instance = new Center(db);
                        instance.setVisible(true);
                        instance.loadAlerts();
                    });
                } else {
                    SwingUtilities.invokeLater(() -> instance.loadAlerts());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        DB db = new DB("alerts.csv");
        startUdpReceiver(db); // chỉ chạy UDP, UI mở khi có tin đến
    }
}
