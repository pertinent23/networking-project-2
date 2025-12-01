import java.net.Socket;

public class MailClient implements Runnable {
    protected Socket clientSocket;
    protected MailProtocolType protocol;
    protected String domain;

    public MailClient(Socket clientSocket, MailProtocolType protocol, String domain) {
        this.clientSocket = clientSocket;
        this.protocol = protocol;
        this.domain = domain;
    }

    @Override
    public void run() {
        // Handle client connection based on the protocol
        try {
            System.out.println("[New connection for " + protocol + " from " + clientSocket.getInetAddress() + "]");
            switch (protocol) {
                case SMTP:
                    new SMTPProtocol(clientSocket, domain).handle();
                    break;
                case IMAP:
                    new IMAPProtocol(clientSocket, domain).handle();
                    break;
                case POP3:
                    new POP3Protocol(clientSocket, domain).handle();
                    break;
            }
        } catch (Exception e) {
            System.err.println("[Error handling client: " + e.getMessage() + "]");
        } finally {
            try {
                clientSocket.close();
            } catch (Exception e) {
                // Ignore because the connection is being closed
            }
        }
    }
}
