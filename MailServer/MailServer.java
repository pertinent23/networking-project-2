import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MailServer {
    /**
     * The domain name of the mail server.
    */
    private static String DOMAIN;

    /**
     * The maximum number of threads for handling client connections.
    */
    private static int MAX_THREADS;
    
    /**
     * The executor service for handling client connections.
    */
    private static ExecutorService threadPool;

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("[Usage: java MailServer <domain> <maxThreads>]");
            System.exit(1);
        }

        DOMAIN = args[0];

        try {
            MAX_THREADS = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("[Error: ivalid parameter for maxThreads]");
            System.exit(1);
        } 

        threadPool = Executors.newFixedThreadPool(MAX_THREADS);

        System.out.println(">> Mail server started for domain: " + DOMAIN + " with max threads: " + MAX_THREADS);

        /**
         * Here we could start each service (SMTP, IMAP, POP3) in its own thread
        */
        new Thread() {
            public void run() {
                startService(MailProtocolType.SMTP, MailSettings.SMTP_PORT);
                System.out.println(">> SMTP service started for: " + DOMAIN);
            }
        }.start();

        new Thread() {
            public void run() {
                startService(MailProtocolType.IMAP, MailSettings.IMAP_PORT);
                System.out.println(">> IMAP service started for: " + DOMAIN);
            }
        }.start();

        new Thread() {
            public void run() {
                startService(MailProtocolType.POP3, MailSettings.POP3_PORT);
                System.out.println(">> POP3 service started for: " + DOMAIN);
            }
        }.start();
    }

    private static void startService(MailProtocolType protocol, int port) {
        // Implementation for starting the service
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[" + protocol + " Server listening on port " + port + "]");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    threadPool.execute(new MailClient(clientSocket, protocol, DOMAIN));
                } catch (IOException e) {
                    System.err.println("[Accept error on " + protocol + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[Error starting " + protocol.getName() + " on port: " + port + "]");
        }
    }
}
