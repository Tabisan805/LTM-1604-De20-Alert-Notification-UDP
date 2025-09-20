package alert_notification;
import javax.swing.*;
import java.awt.*;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class Client extends JFrame {
    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private JTextField messageField;
    private JComboBox<String> levelBox;

    public Client() {
        setTitle("Client - Send Alert");
        setSize(400, 200);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new GridLayout(3, 2, 10, 10));

        add(new JLabel("Message:"));
        messageField = new JTextField();
        add(messageField);

        add(new JLabel("Level:"));
        levelBox = new JComboBox<>(new String[]{"Info", "Warning", "Critical"});
        add(levelBox);

        JButton sendBtn = new JButton("Send");
        sendBtn.addActionListener(e -> sendAlert());
        add(sendBtn);
    }

    private void sendAlert() {
        String message = messageField.getText().trim();
        String level = (String) levelBox.getSelectedItem();

        if (message.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Message cannot be empty!");
            return;
        }

        try {
            InetAddress group = InetAddress.getByName("230.0.0.1");
            MulticastSocket socket = new MulticastSocket();

            String json = String.format("{\"message\":\"%s\",\"level\":\"%s\"}", message, level);
            byte[] buf = json.getBytes();

            DatagramPacket packet = new DatagramPacket(buf, buf.length, group, 4446);
            socket.send(packet);
            socket.close();

            JOptionPane.showMessageDialog(this, "📤 Sent: " + json);
            messageField.setText("");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Client().setVisible(true));
    }
}
