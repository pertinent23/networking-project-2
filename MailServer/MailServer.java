import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MailServer Entry Point
 * <p>
 * This class acts as the central hub for the mail server. It initializes the
 * multi-threaded environment and launches listener threads for each supported
 * protocol (SMTP, IMAP, POP3).
 * </p>
 *
 * <h3>Architecture Diagram:</h3>
 * <pre>
 * +------------------+
 * |   MailServer     | <--- Entry Point (main)
 * +--------+---------+
 * |
 * (Initializes Thread Pool)
 * |
 * +-----------+-----------+----------------+
 * |           |           |                |
 * v           v           v                v
 * [SMTP]      [IMAP]      [POP3]        [Shared Pool]
 * Listener    Listener    Listener      (Workers)
 * Thread      Thread      Thread             |
 * |           |           |               |
 * | accept()  | accept()  | accept()      |
 * +-----+-----+-----+-----+               |
 * |           |                     |
 * +-----> Submit Job >--------------+
 * |
 * [MailClient Handler]
 * (Runs Protocol Logic)
 * </pre>
 */
public class MailServer {

    /**
     * The domain name this server is authoritative for (e.g., "uliege.be").
     */
    private static String DOMAIN;

    /**
     * Shared Thread Pool for all protocols.
     * <p>
     * <b>Note on Concurrency:</b> We use a shared pool to limit the TOTAL resource
     * consumption of the server. Whether a client is doing SMTP or IMAP, they
     * draw from the same pool of worker threads.
     * </p>
     */
    private static ExecutorService threadPool;
    
    // Global flag to control server lifecycle
    private static volatile boolean isRunning = true;

    public static void main(String[] args) {
        // 1. Argument Validation
        if (args.length != 2) {
            System.err.println("Usage: java MailServer <domain> <maxThreads>");
            System.exit(1);
        }

        DOMAIN = args[0];
        int maxThreads = 10; // default safety

        try {
            maxThreads = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("Error: invalid integer for maxThreads. Using default: 10");
        }

        // 2. Initialize Thread Pool
        threadPool = Executors.newFixedThreadPool(maxThreads);

        System.out.println("=================================================");
        System.out.println("   MAIL SERVER STARTING");
        System.out.println("   Domain: " + DOMAIN);
        System.out.println("   Pool Size: " + maxThreads);
        System.out.println("=================================================");

        // 3. Register Shutdown Hook (Graceful Exit on Ctrl+C)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n>> Shutting down Mail Server...");
            isRunning = false;
            shutdownPool();
            System.out.println(">> Bye.");
        }));

        // 4. Start Protocol Listeners in separate threads
        // We use simple threads here because they are long-lived (Daemon-like)
        // and their job is just to accept connections and dispatch them to the pool.
        
        startListenerThread(MailProtocolType.SMTP, MailSettings.SMTP_PORT);
        startListenerThread(MailProtocolType.IMAP, MailSettings.IMAP_PORT);
        startListenerThread(MailProtocolType.POP3, MailSettings.POP3_PORT);
    }

    /**
     * Helper to start a listener thread for a specific protocol.
     * * @param type The protocol type (enum)
     * @param port The port to bind to
     */
    private static void startListenerThread(MailProtocolType type, int port) {
        new Thread(() -> {
            startService(type, port);
        }, type.getName() + "-Listener").start();
    }

    /**
     * Opens a ServerSocket and continuously accepts client connections.
     * * @param protocol The protocol type
     * @param port The port to listen on
     */
    private static void startService(MailProtocolType protocol, int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println(">> " + protocol.getName() + " Service listening on port " + port);

            while (isRunning) {
                try {
                    // Blocking call, waits for a client
                    Socket clientSocket = serverSocket.accept();
                    
                    // Log connection attempt
                    System.out.println("[" + protocol.getName() + "] New connection from " + clientSocket.getInetAddress());

                    // Hand off the heavy lifting to the ThreadPool
                    // 'MailClient' implements Runnable and handles the specific protocol logic
                    threadPool.execute(new MailClient(clientSocket, protocol, DOMAIN));

                } catch (IOException e) {
                    if (isRunning) {
                        System.err.println("[" + protocol.getName() + "] Accept failed: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[MailServer.java: !! FATAL: Could not bind " + protocol.getName() + " to port " + port + "]");
            System.err.println("[MailServer.java:   Reason: " + e.getMessage() + "]");
            // In a real server, we might want to exit if a port binds fail, 
            // but here we let other services continue.
        }
    }

    /**
     * Gracefully shuts down the executor service.
     */
    private static void shutdownPool() {
        threadPool.shutdown(); // Disable new tasks
        try {
            // Wait a while for existing tasks to terminate
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS))
                    System.err.println("Pool did not terminate");
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}