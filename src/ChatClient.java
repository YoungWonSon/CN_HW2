import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Properties;
import java.util.Scanner;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * ChatClient
 *
 * A Swing-based client for the multi-threaded chat server.
 *
 * Features:
 * 1. Reads server host/port from a configuration file (serverinfo.dat).
 * 2. Provides a login/register dialog (GUI-based).
 * 3. Allows broadcast messages to all users.
 * 4. Supports whisper (private) messages using a combo box to select the target user.
 * 5. Shows the list of online users via USERLIST protocol messages.
 * 6. Displays server system messages, normal messages, and whisper messages.
 *
 * Protocol (Server -> Client) handled by this client:
 *  - SUBMITNAME
 *  - NAMEACCEPTED userId
 *  - REGISTERED OK
 *  - REGISTERFAIL error-message
 *  - IDOK
 *  - IDTAKEN
 *  - MESSAGE text...
 *  - WHISPERFROM senderId: text...
 *  - SYSTEM text...
 *  - USERLIST id1,id2,id3,...
 *
 * Protocol (Client -> Server) sent by this client:
 *  - REGISTER userId password name email
 *  - LOGIN userId password
 *  - MSG text...
 *  - WHISPER targetId text...
 *  - LOGOUT
 */
public class ChatClient {

    /** Server host address (e.g., 127.0.0.1) */
    private String serverAddress;

    /** Server port (e.g., 59001) */
    private int serverPort;

    /** Input stream from the server */
    private Scanner in;

    /** Output stream to the server */
    private PrintWriter out;

    /** Main application window */
    JFrame frame = new JFrame("Chat & Whisper Client");

    /** Text field used to type outgoing messages */
    JTextField textField = new JTextField(40);

    /** Text area that displays all received messages */
    JTextArea messageArea = new JTextArea(18, 50);

    /** Combo box that lists online users for whispering */
    JComboBox<String> userComboBox = new JComboBox<>();

    /** Button used to send whisper messages */
    JButton whisperButton = new JButton("Whisper");

    /** Button used to send a normal broadcast message */
    JButton sendButton = new JButton("Send");

    /**
     * Constructs the client and builds the GUI layout.
     * At this point, the connection to the server is NOT yet established.
     */
    public ChatClient(String serverAddress, int serverPort) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;

        // The user should not be able to type messages before successful login.
        textField.setEditable(false);
        messageArea.setEditable(false);

        // ------------------ Top panel: Whisper controls ------------------
        JPanel topPanel = new JPanel();
        topPanel.add(new JLabel("Whisper to:"));

        // Set a prototype display value so the combo box has a reasonable width.
        userComboBox.setPrototypeDisplayValue("long_user_name_here");
        userComboBox.addItem("(All)"); // Default "no specific user" option
        topPanel.add(userComboBox);

        topPanel.add(whisperButton);

        // ------------------ Center panel: Message display ------------------
        JScrollPane scrollPane = new JScrollPane(messageArea);

        // ------------------ Bottom panel: Input and send button ------------------
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(textField, BorderLayout.CENTER);
        bottomPanel.add(sendButton, BorderLayout.EAST);

        // Add all panels to the main frame.
        frame.getContentPane().add(topPanel, BorderLayout.NORTH);
        frame.getContentPane().add(scrollPane, BorderLayout.CENTER);
        frame.getContentPane().add(bottomPanel, BorderLayout.SOUTH);

        frame.pack();

        // ------------------ Event handlers ------------------

        // When the user presses Enter in the text field, send a normal message.
        textField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendNormalMessage();
            }
        });

        // When the "Send" button is clicked, send a normal message.
        sendButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendNormalMessage();
            }
        });

        // When the "Whisper" button is clicked, send a whisper message.
        whisperButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendWhisper();
            }
        });
    }

    /**
     * Sends a normal public message to the server.
     * The message will be broadcast to all connected clients by the server.
     */
    private void sendNormalMessage() {
        if (out == null) {
            return; // No connection to the server yet
        }

        String text = textField.getText().trim();
        if (text.isEmpty()) {
            return; // Do not send empty messages
        }

        // Support "/quit" as a shortcut for logging out.
        if (text.equalsIgnoreCase("/quit")) {
            out.println("LOGOUT");
            frame.setVisible(false);
            frame.dispose();
            return;
        }

        // Send MSG command to the server.
        out.println("MSG " + text);
        textField.setText("");
    }

    /**
     * Sends a whisper (private) message to the target user currently selected
     * in the combo box.
     */
    private void sendWhisper() {
        if (out == null) {
            return; // No connection yet
        }

        String text = textField.getText().trim();
        if (text.isEmpty()) {
            return; // Do not send empty messages
        }

        String target = (String) userComboBox.getSelectedItem();
        if (target == null || target.equals("(All)")) {
            // If no specific target user is selected, warn the user.
            JOptionPane.showMessageDialog(
                    frame,
                    "Please select a user to whisper to.",
                    "Whisper",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        // Send WHISPER command to the server in the format:
        // WHISPER targetId message...
        out.println("WHISPER " + target + " " + text);
        textField.setText("");
    }

    /**
     * Presents a dialog for authentication.
     * The user may choose to:
     *  - Login (LOGIN userId password)
     *  - Register (REGISTER userId password name email)
     *  - Exit (cancel)
     *
     * This method only constructs and returns the command string that should be
     * sent to the server. It does NOT communicate with the server by itself.
     *
     * @return A string such as "LOGIN id pw" or "REGISTER id pw name email",
     *         or null if the user chooses to exit.
     */
    private String getAuthCommand() {
        String[] options = {"Login", "Register", "Exit"};

        while (true) {
            int choice = JOptionPane.showOptionDialog(
                    frame,
                    "Please select Login or Register.",
                    "Authentication",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    options,
                    options[0]
            );

            // ------------------ User selected "Login" ------------------
            if (choice == 0) {
                JTextField idField = new JTextField();
                JPasswordField pwField = new JPasswordField();

                JPanel panel = new JPanel(new GridLayout(0, 1));
                panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
                panel.add(new JLabel("User ID (no spaces):"));
                panel.add(idField);
                panel.add(new JLabel("Password:"));
                panel.add(pwField);

                int result = JOptionPane.showConfirmDialog(
                        frame,
                        panel,
                        "Login",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE
                );

                if (result == JOptionPane.OK_OPTION) {
                    String id = idField.getText().trim();
                    String pw = new String(pwField.getPassword());

                    if (id.isEmpty() || pw.isEmpty()) {
                        JOptionPane.showMessageDialog(
                                frame,
                                "Both ID and password must be provided.",
                                "Login",
                                JOptionPane.WARNING_MESSAGE
                        );
                        // Loop again to show the login/register dialog.
                        continue;
                    }

                    return "LOGIN " + id + " " + pw;
                } else {
                    // User canceled the login dialog, go back to the main choice.
                    continue;
                }

                // ------------------ User selected "Register" ------------------
            } else if (choice == 1) {
                // Registration requires more fields (ID, password, name, email).
                JTextField idField = new JTextField();
                JPasswordField pwField = new JPasswordField();
                JTextField nameField = new JTextField();
                JTextField emailField = new JTextField();

                JPanel panel = new JPanel(new GridLayout(0, 1));
                panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
                panel.add(new JLabel("User ID (no spaces):"));
                panel.add(idField);
                panel.add(new JLabel("Password:"));
                panel.add(pwField);
                panel.add(new JLabel("Name (no spaces):"));
                panel.add(nameField);
                panel.add(new JLabel("Email (no spaces):"));
                panel.add(emailField);

                int result = JOptionPane.showConfirmDialog(
                        frame,
                        panel,
                        "Register",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE
                );

                if (result == JOptionPane.OK_OPTION) {
                    String id = idField.getText().trim();
                    String pw = new String(pwField.getPassword());
                    String name = nameField.getText().trim();
                    String email = emailField.getText().trim();

                    if (id.isEmpty() || pw.isEmpty() || name.isEmpty() || email.isEmpty()) {
                        JOptionPane.showMessageDialog(
                                frame,
                                "All fields must be filled.",
                                "Register",
                                JOptionPane.WARNING_MESSAGE
                        );
                        // Show the main choice dialog again
                        continue;
                    }

                    if (id.contains(" ") || pw.contains(" ")
                            || name.contains(" ") || email.contains(" ")) {
                        JOptionPane.showMessageDialog(
                                frame,
                                "ID, password, name, and email cannot contain spaces.",
                                "Register",
                                JOptionPane.WARNING_MESSAGE
                        );
                        continue;
                    }

                    return "REGISTER " + id + " " + pw + " " + name + " " + email;
                } else {
                    // User canceled the registration dialog, go back to main choice.
                    continue;
                }

                // ------------------ User selected "Exit" or closed the dialog ------------------
            } else {
                return null;
            }
        }
    }

    /**
     * Updates the online user list combo box when a USERLIST message is received
     * from the server. The format is a comma-separated string of user IDs.
     *
     * @param csv A comma-separated list of online user IDs, e.g., "alice,bob,charlie"
     */
    private void updateUserList(String csv) {
        userComboBox.removeAllItems();
        userComboBox.addItem("(All)");

        if (csv == null || csv.isEmpty()) {
            return;
        }

        String[] ids = csv.split(",");
        for (String id : ids) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                userComboBox.addItem(trimmed);
            }
        }
    }

    /**
     * Main communication loop with the server.
     * This method:
     *  1. Connects to the server.
     *  2. Creates input/output streams.
     *  3. Repeatedly reads protocol messages from the server and reacts accordingly.
     */
    private void run() throws IOException {
        Socket socket = null;
        try {
            // Establish a TCP connection to the server.
            socket = new Socket(serverAddress, serverPort);

            in = new Scanner(socket.getInputStream());
            out = new PrintWriter(socket.getOutputStream(), true);

            // Main loop for processing incoming server messages.
            while (in.hasNextLine()) {
                String line = in.nextLine();

                // ------------------ SUBMITNAME ------------------
                // The server asks the client to send authentication info.
                if (line.startsWith("SUBMITNAME")) {
                    String cmd = getAuthCommand();
                    if (cmd == null) {
                        // The user decided to exit instead of logging in.
                        out.println("/quit");
                        break;
                    }
                    out.println(cmd);

                    // ------------------ NAMEACCEPTED ------------------
                    // Login was successful and the server tells us our final user ID.
                } else if (line.startsWith("NAMEACCEPTED")) {
                    String userId = line.substring("NAMEACCEPTED".length()).trim();
                    this.frame.setTitle("Chat & Whisper Client - " + userId);
                    messageArea.append("[SYSTEM] Login successful: " + userId + "\n");
                    // Now the user can type messages.
                    textField.setEditable(true);

                    // ------------------ REGISTERED ------------------
                    // Registration was successful. The user still needs to log in.
                } else if (line.startsWith("REGISTERED")) {
                    messageArea.append("[SYSTEM] Registration successful. Please log in again.\n");

                    // ------------------ REGISTERFAIL ------------------
                    // Registration failed; show the reason.
                } else if (line.startsWith("REGISTERFAIL")) {
                    String reason = line.substring("REGISTERFAIL".length()).trim();
                    JOptionPane.showMessageDialog(
                            frame,
                            reason,
                            "Registration Failed",
                            JOptionPane.ERROR_MESSAGE
                    );

                    // ------------------ IDOK / IDTAKEN ------------------
                    // These responses would be used if we implemented explicit ID-check UI.
                } else if (line.startsWith("IDOK")) {
                    messageArea.append("[SYSTEM] The requested ID is available.\n");
                } else if (line.startsWith("IDTAKEN")) {
                    messageArea.append("[SYSTEM] The requested ID is already taken.\n");

                    // ------------------ MESSAGE ------------------
                    // Normal broadcast messages from the server.
                } else if (line.startsWith("MESSAGE")) {
                    String msg = line.substring("MESSAGE".length()).trim();
                    messageArea.append(msg + "\n");

                    // ------------------ WHISPERFROM ------------------
                    // Whisper messages directed to this client.
                } else if (line.startsWith("WHISPERFROM")) {
                    String msg = line.substring("WHISPERFROM".length()).trim();
                    messageArea.append("[Whisper] " + msg + "\n");

                    // ------------------ SYSTEM ------------------
                    // System-level notifications (join, leave, errors, etc.).
                } else if (line.startsWith("SYSTEM")) {
                    String msg = line.substring("SYSTEM".length()).trim();
                    messageArea.append("[SYSTEM] " + msg + "\n");

                    // ------------------ USERLIST ------------------
                    // Updates the list of online users in the combo box.
                } else if (line.startsWith("USERLIST")) {
                    String list = line.substring("USERLIST".length()).trim();
                    updateUserList(list);
                }
            }
        } finally {
            // Clean up UI and socket when the connection ends.
            frame.setVisible(false);
            frame.dispose();
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore close exception
                }
            }
        }
    }

    /**
     * Reads server connection information from serverinfo.dat.
     * The file should be placed in the same directory as this client program,
     * and have the following format:
     *
     * host=127.0.0.1
     * port=59001
     *
     * If the file is missing or invalid, default values (127.0.0.1:59001) are used.
     */
    private static String[] loadServerInfo() {
        String host = "127.0.0.1";
        int port = 59001;

        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream("serverinfo.dat")) {
            p.load(fis);
            String h = p.getProperty("host");
            String po = p.getProperty("port");

            if (h != null && !h.trim().isEmpty()) {
                host = h.trim();
            }
            if (po != null && !po.trim().isEmpty()) {
                try {
                    port = Integer.parseInt(po.trim());
                } catch (NumberFormatException ignored) {
                    // If parsing fails, simply keep the default port.
                }
            }
        } catch (IOException e) {
            System.out.println("serverinfo.dat not found. Using default 127.0.0.1:59001.");
        }

        return new String[]{host, String.valueOf(port)};
    }

    /**
     * Application entry point.
     * 1. Loads server information from serverinfo.dat.
     * 2. Creates the ChatClient instance.
     * 3. Shows the GUI.
     * 4. Starts the main run() loop that talks to the server.
     */
    public static void main(String[] args) throws Exception {
        String[] info = loadServerInfo();
        String host = info[0];
        int port = Integer.parseInt(info[1]);

        ChatClient client = new ChatClient(host, port);
        client.frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        client.frame.setVisible(true);
        client.run();
    }
}