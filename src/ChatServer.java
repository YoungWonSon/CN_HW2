import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChatServer (Multi-threaded Chat Application Server)
 *
 * Features:
 * 1. User registration with ID, password, name, email
 * 2. Login authentication (Salt + SHA-256 hashed password)
 * 3. User account DB stored in a file (users.db)
 * 4. Whisper messaging (private one-to-one messaging)
 * 5. Broadcast messaging (public chat)
 * 6. USERLIST broadcasting (client UI updates the online user list)
 *
 * Protocol Examples (Client -> Server):
 *  - REGISTER userId password name email
 *  - LOGIN userId password
 *  - CHECK_ID userId
 *  - MSG text...
 *  - WHISPER targetId text...
 *  - LOGOUT
 *
 * Server -> Client:
 *  - SUBMITNAME
 *  - NAMEACCEPTED userId
 *  - REGISTERED OK
 *  - REGISTERFAIL error-message
 *  - IDOK
 *  - IDTAKEN
 *  - MESSAGE text
 *  - WHISPERFROM senderId: text
 *  - SYSTEM text
 *  - USERLIST id1,id2,id3,...
 */
public class ChatServer {

    /** User account storage file */
    private static final String USER_DB_FILE = "users.db";

    /** userId -> User object */
    private static final Map<String, User> userDB = new HashMap<>();

    /** Set of online user IDs */
    private static final Set<String> onlineUsers = new HashSet<>();

    /** All client PrintWriters (used for broadcasting) */
    private static final Set<PrintWriter> writers = new HashSet<>();

    /** userId -> PrintWriter (used for whisper messaging) */
    private static final Map<String, PrintWriter> userWriters = new HashMap<>();

    /** Lock object for synchronization */
    private static final Object lock = new Object();

    /**
     * User class for storing account information.
     * Stored fields: userId, name, email, saltHex, hashedPasswordHex
     */
    private static class User {
        String userId;
        String name;
        String email;
        String saltHex;
        String passwordHashHex;

        User(String userId, String name, String email, String saltHex, String passwordHashHex) {
            this.userId = userId;
            this.name = name;
            this.email = email;
            this.saltHex = saltHex;
            this.passwordHashHex = passwordHashHex;
        }
    }

    // ============================ Password Utilities ============================

    /**
     * Generates a random 16-byte salt for password hashing.
     */
    private static byte[] generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /**
     * Computes SHA-256 hash of (salt + password).
     */
    private static String hashPassword(String password, byte[] salt) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(salt);                  // Include salt first
        md.update(password.getBytes("UTF-8")); // Then add the password
        byte[] digest = md.digest();
        return bytesToHex(digest);
    }

    /** Utility for converting bytes to hex string */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Utility for converting hex string to byte[] */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    // ============================ User DB I/O ============================

    /**
     * Loads user accounts from users.db.
     * Each line format: userId\tname\temail\tsaltHex\thashHex
     */
    private static void loadUsersFromFile() {
        File f = new File(USER_DB_FILE);
        if (!f.exists()) {
            System.out.println("No existing user DB found. Starting fresh.");
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            synchronized (lock) {
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split("\t");
                    if (parts.length == 5) {
                        User u = new User(parts[0], parts[1], parts[2], parts[3], parts[4]);
                        userDB.put(u.userId, u);
                    }
                }
            }
            System.out.println("Loaded users from DB: " + userDB.size());
        } catch (IOException e) {
            System.out.println("Failed to load user DB: " + e.getMessage());
        }
    }

    /**
     * Saves user accounts back to users.db.
     */
    private static void saveUsersToFile() {
        synchronized (lock) {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(USER_DB_FILE))) {
                for (User u : userDB.values()) {
                    bw.write(u.userId + "\t" + u.name + "\t" + u.email + "\t" + u.saltHex + "\t" + u.passwordHashHex);
                    bw.newLine();
                }
            } catch (IOException e) {
                System.out.println("Failed to save user DB: " + e.getMessage());
            }
        }
    }

    /** Returns true if ID is not taken */
    private static boolean isIdAvailable(String userId) {
        synchronized (lock) {
            return !userDB.containsKey(userId);
        }
    }

    /**
     * Handles user registration. Returns null if successful; otherwise returns error message.
     */
    private static String registerUser(String userId, String password, String name, String email) {
        synchronized (lock) {
            if (userDB.containsKey(userId)) return "This ID is already in use.";

            try {
                byte[] salt = generateSalt();
                String saltHex = bytesToHex(salt);
                String hash = hashPassword(password, salt);
                User u = new User(userId, name, email, saltHex, hash);

                userDB.put(userId, u);
                saveUsersToFile();
                return null;
            } catch (Exception e) {
                return "Internal server error (Hashing failed).";
            }
        }
    }

    /**
     * Authenticates user credentials during login.
     * Returns User object if login is successful, otherwise null.
     */
    private static User authenticate(String userId, String password) {
        synchronized (lock) {
            User u = userDB.get(userId);
            if (u == null) return null;

            try {
                byte[] salt = hexToBytes(u.saltHex);
                String hash = hashPassword(password, salt);
                if (hash.equals(u.passwordHashHex)) return u;
            } catch (Exception ignored) {}

            return null;
        }
    }

    // ============================ Broadcasting ============================

    /** Broadcasts public chat messages to all connected clients */
    private static void broadcastMessage(String msg) {
        synchronized (lock) {
            for (PrintWriter w : writers) {
                w.println("MESSAGE " + msg);
            }
        }
    }

    /** Sends system messages (visible to all clients) */
    private static void broadcastSystem(String msg) {
        synchronized (lock) {
            for (PrintWriter w : writers) {
                w.println("SYSTEM " + msg);
            }
        }
    }

    /**
     * Broadcasts current online user list to all clients.
     * Format: USERLIST id1,id2,id3
     */
    private static void broadcastUserList() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (String name : onlineUsers) {
                if (!first) sb.append(",");
                sb.append(name);
                first = false;
            }
            String csv = sb.toString();

            for (PrintWriter w : writers) {
                w.println("USERLIST " + csv);
            }
        }
    }

    // ============================ Server Main ============================

    public static void main(String[] args) throws Exception {
        System.out.println("Chat Server is running...");
        loadUsersFromFile();

        ExecutorService pool = Executors.newFixedThreadPool(500);
        try (ServerSocket listener = new ServerSocket(59001)) {
            while (true) {
                pool.execute(new Handler(listener.accept()));
            }
        }
    }

    // ============================ Handler Thread ============================

    /**
     * Each client is handled by one Handler instance.
     * This class manages:
     *  - User registration/login
     *  - Joining/leaving broadcast
     *  - Message and whisper processing
     */
    private static class Handler implements Runnable {
        private String userId;
        private Socket socket;
        private Scanner in;
        private PrintWriter out;

        public Handler(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try {
                in = new Scanner(socket.getInputStream());
                out = new PrintWriter(socket.getOutputStream(), true);

                // ---------------- Authentication Loop ----------------
                while (true) {
                    out.println("SUBMITNAME");  // Request authentication command

                    if (!in.hasNextLine()) return;
                    String cmd = in.nextLine();
                    if (cmd == null) return;

                    // ========== Registration ==========
                    if (cmd.startsWith("REGISTER ")) {
                        String[] p = cmd.split(" ", 5);
                        if (p.length < 5) {
                            out.println("REGISTERFAIL Format: REGISTER id pw name email");
                            continue;
                        }
                        String err = registerUser(p[1], p[2], p[3], p[4]);
                        if (err == null) out.println("REGISTERED OK");
                        else out.println("REGISTERFAIL " + err);

                        // ========== Login ==========
                    } else if (cmd.startsWith("LOGIN ")) {
                        String[] p = cmd.split(" ", 3);
                        if (p.length < 3) {
                            out.println("SYSTEM Format: LOGIN id pw");
                            continue;
                        }
                        String id = p[1];
                        String pw = p[2];

                        User u = authenticate(id, pw);
                        if (u == null) {
                            out.println("SYSTEM Login failed: Invalid ID or password.");
                            continue;
                        }

                        synchronized (lock) {
                            if (onlineUsers.contains(id)) {
                                out.println("SYSTEM This account is already logged in.");
                                continue;
                            }
                            this.userId = id;
                            onlineUsers.add(id);
                            writers.add(out);
                            userWriters.put(id, out);
                        }

                        out.println("NAMEACCEPTED " + id);
                        broadcastSystem(id + " has joined the chat.");
                        broadcastUserList();
                        break;

                        // ========== ID check ==========
                    } else if (cmd.startsWith("CHECK_ID ")) {
                        String id = cmd.substring(9).trim();
                        if (isIdAvailable(id)) out.println("IDOK");
                        else out.println("IDTAKEN");

                    } else {
                        out.println("SYSTEM Unknown command. Use REGISTER / LOGIN / CHECK_ID.");
                    }
                }

                // ---------------- Main Chat Loop ----------------
                while (true) {
                    if (!in.hasNextLine()) break;
                    String input = in.nextLine();
                    if (input == null) break;

                    if (input.equalsIgnoreCase("LOGOUT") || input.equalsIgnoreCase("/quit")) {
                        break;
                    }

                    // Public message
                    if (input.startsWith("MSG ")) {
                        String msg = input.substring(4).trim();
                        if (!msg.isEmpty()) {
                            broadcastMessage(userId + ": " + msg);
                        }

                        // Whisper
                    } else if (input.startsWith("WHISPER ")) {
                        String[] p = input.split(" ", 3);
                        if (p.length < 3) {
                            out.println("SYSTEM Format: WHISPER targetId message...");
                            continue;
                        }
                        String target = p[1];
                        String msg = p[2];

                        PrintWriter targetWriter;
                        synchronized (lock) {
                            targetWriter = userWriters.get(target);
                        }

                        if (targetWriter != null) {
                            targetWriter.println("WHISPERFROM " + userId + ": " + msg);
                            out.println("SYSTEM [Whisper to " + target + "] " + msg);
                        } else {
                            out.println("SYSTEM The target user is not online.");
                        }

                    } else {
                        // If it doesn't match any command, treat as a public message
                        broadcastMessage(userId + ": " + input);
                    }
                }

            } catch (Exception e) {
                System.out.println("Handler error: " + e.getMessage());

            } finally {
                // ---------------- Cleanup ----------------
                if (userId != null) {
                    synchronized (lock) {
                        onlineUsers.remove(userId);
                        userWriters.remove(userId);
                        writers.remove(out);
                    }
                    broadcastSystem(userId + " has left the chat.");
                    broadcastUserList();
                }
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
