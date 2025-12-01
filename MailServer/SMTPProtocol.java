import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * SMTP Protocol Handler
 * <p>
 * This class implements the server-side SMTP logic (RFC 5321).
 * It handles incoming email transmission requests and acts as an
 * MTA (Mail Transfer Agent) to either deliver locally or relay remotely.
 * +--------+      SMTP       +--------------+
 * | Client | --------------> | SMTPProtocol |
 * +--------+                 +------+-------+
 * |
 * +----------------+----------------+
 * |                                 |
 * [Domain Match?]                     [External Domain]
 * |                                 |
 * +--------v---------+             +---------v---------+
 * | Local Delivery   |             |    Relaying       |
 * | (Save to Disk)   |             | (MailDNSClient)   |
 * +------------------+             +---------+---------+
 * |
 * +--------v---------+
 * | Remote SMTP Srv  |
 * +------------------+
*/
public class SMTPProtocol extends MailProtocol {
    // Current transaction state
    private String sender = "";
    private String recipient = "";
    
    // Buffer to accumulate message data (Body + Headers) during DATA phase
    private StringBuilder dataBuffer = new StringBuilder();

    public SMTPProtocol(Socket socket, String domain) throws IOException {
        super(socket, domain);
    }

    /**
     * Main Protocol Loop
     * Implements the SMTP State Machine.
     * State Machine Diagram:
     * (Connection Est.)
     * |
     * v
     * +------+------+
     * | COMMAND MODE| <----qt-----+
     * +------+------+             |
     * | DATA Command              |
     * v                           |
     * +------+------+             |
     * |  DATA MODE  |             |
     * | (Read Body) |             |
     * +------+------+             |
     * | <CRLF>.<CRLF>             |
     * v                           |
     * [Process Email] ------------+
     * </pre>
     */
    @Override
    public void handle() throws IOException {
        // Step 1: Send initial greeting (Service Ready)
        sendResponse("220 " + serverDomain + " Simple Mail Transfer Service Ready");
        
        String line;
        boolean dataMode = false;
        
        while ((line = in.readLine()) != null) {
            
            // =================================================================================
            // PHASE 1: DATA MODE (Reading Email Content)
            // =================================================================================
            if (dataMode) {
                // End of data sequence: <CRLF>.<CRLF>
                if (line.equals(".")) {
                    dataMode = false;
                    processEmail();
                } else {
                    /*
                     * --- TRANSPARENCY / DOT-STUFFING ---
                     * If a line starts with a period, the client adds an extra period.
                     * We must strip it here, unless we want to store it "stuffed".
                     * Here, we keep it simple and store as received (or strip if needed).
                     */
                    if (line.startsWith(".")) {
                        // In a full implementation, line = line.substring(1);
                        dataBuffer.append(line).append("\r\n");
                    } else {
                        dataBuffer.append(line).append("\r\n");
                    }
                }
                continue; // Skip command parsing while in data mode
            }

            // =================================================================================
            // PHASE 2: COMMAND MODE (Parsing SMTP Verbs)
            // =================================================================================
            String upperLine = line.trim().toUpperCase();
            String command = "";

            // Identify the verb
            if (upperLine.startsWith("MAIL FROM:")) command = "MAIL";
            else if (upperLine.startsWith("RCPT TO:")) command = "RCPT";
            else if (upperLine.startsWith("HELO") || upperLine.startsWith("EHLO")) command = "HELO";
            else command = upperLine; 

            switch (command) {
                case "HELO":
                    sendResponse("250 " + serverDomain);
                    break;

                case "MAIL":
                    try {
                        int colonIndex = line.indexOf(':');
                        if (colonIndex > -1) {
                            sender = extractEmail(line.substring(colonIndex + 1));
                            sendResponse("250 OK");
                        } else {
                            sendResponse("501 Syntax error in parameters");
                        }
                    } catch (Exception e) {
                        sendResponse("501 Syntax error");
                    }
                    break;

                case "RCPT":
                    // Define Recipient (Can be called multiple times in standard SMTP, 
                    // but here we simplify to single recipient logic)
                    try {
                        int colonIndex = line.indexOf(':');
                        if (colonIndex > -1) {
                            recipient = extractEmail(line.substring(colonIndex + 1));
                            sendResponse("250 OK");
                        } else {
                            sendResponse("501 Syntax error");
                        }
                    } catch (Exception e) {
                        sendResponse("501 Syntax error");
                    }
                    break;

                case "DATA":
                    // Trigger transition to DATA MODE
                    // 354 is the code to tell client "Go ahead, end with ."
                    sendResponse("354 End data with <CRLF>.<CRLF>");
                    dataMode = true;
                    dataBuffer.setLength(0); 
                    break;

                case "QUIT":
                    // Close connection
                    sendResponse("221 Bye");
                    return; 

                case "RSET":
                    // Reset transaction state without dropping connection
                    sender = "";
                    recipient = "";
                    dataBuffer.setLength(0);
                    sendResponse("250 OK");
                    break;

                default:
                    sendResponse("500 Unrecognized command");
                    break;
            }
        }
    }

    /**
     * Routing Logic: Local Delivery vs. Relaying
     * Analyzes the recipient domain to decide the path.
    */
    private void processEmail() {
        if (recipient == null || !recipient.contains("@")) {
            sendResponse("550 Invalid recipient");
            return;
        }

        String targetDomain = recipient.substring(recipient.indexOf('@') + 1);
        
        // Check if the email is for this server
        if (targetDomain.equalsIgnoreCase(serverDomain) || targetDomain.equals("localhost")) {
            // --- LOCAL DELIVERY STRATEGY ---
            try {
                // Construct final email content with envelope headers
                StringBuilder finalContent = new StringBuilder();
                finalContent.append("Return-Path: <").append(sender).append(">\r\n");
                finalContent.append("Delivered-To: ").append(recipient).append("\r\n");
                finalContent.append(dataBuffer.toString());

                // Save to local disk via Manager
                MailStorageManager.saveEmail(recipient, "INBOX", finalContent.toString());
                sendResponse("250 OK Message accepted for delivery");
            } catch (IOException e) {
                e.printStackTrace();
                sendResponse("451 Requested action aborted: local error");
            }
        } else {
            // --- RELAYING STRATEGY ---
            // Security Note: In a real world scenario, you must check if 'sender' is authenticated
            // or if 'sender' belongs to this domain. Otherwise, you are an "Open Relay".
            
            System.out.println("[SMTPProtocol.java: Relaying email to remote domain: " + targetDomain + "]");
            try {
                // 1. DNS Resolution (Find where to send)
                String mxHost = MailDNSClient.resolveMX(targetDomain);
                
                // Fallback : If no MX record found, treat domain as A record (Host)
                if (mxHost == null) {
                    System.out.println("[SMTPProtocol.java: No MX record found, falling back to A record: " + targetDomain + "]");
                    mxHost = targetDomain;
                }

                // 2. SMTP Forwarding
                boolean success = sendToRemoteServer(mxHost, sender, recipient, dataBuffer.toString());

                if (success) {
                    sendResponse("250 OK Message relayed to " + mxHost);
                } else {
                    sendResponse("451 Failed to relay message to remote server");
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendResponse("451 Error during forwarding");
            }
        }
    }

    /**
     * Acts as an SMTP Client to forward the email to another server.
     * SMTP Relay Sequence:
     * 
     * Our Server            Remote Server
     * |                       |
     * |---(TCP Connect)------>|
     * |<-------- 220 ---------|
     * |--- EHLO mydomain ---->|
     * |<-------- 250 ---------|
     * |--- MAIL FROM: ... --->|
     * |<-------- 250 ---------|
     * |---- RCPT TO: ... ---->|
     * |<-------- 250 ---------|
     * |------- DATA --------->|
     * |<-------- 354 ---------|
     * |---- (Send Body) ----->|
     * |-------- . ----------->|
     * |<-------- 250 ---------|
     * |------- QUIT --------->|
     * 
     * @param mxHost The hostname of the remote mail server (from DNS).
     * @param sender The envelope sender.
     * @param recipient The envelope recipient.
     * @param data The content of the email.
     * @return true if relayed successfully.
     */
    private boolean sendToRemoteServer(String mxHost, String sender, String recipient, String data) {
        // Standard SMTP port is 25. 
        // Note: Many ISPs block outgoing port 25 to prevent spam.
        try (Socket socket = new Socket(mxHost, 25);
            BufferedReader remoteIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter remoteOut = new PrintWriter(socket.getOutputStream(), true)) {

            // 1. Wait for Greeting
            if (!expect(remoteIn, "220")) {
                return false;
            }

            // 2. Handshake (EHLO preferred, fallback to HELO)
            sendCmd(remoteOut, "EHLO " + serverDomain);
            if (!expect(remoteIn, "250")) {
                sendCmd(remoteOut, "HELO " + serverDomain);
                if (!expect(remoteIn, "250")) {
                    return false;
                }
            }

            // 3. Envelope Sender
            sendCmd(remoteOut, "MAIL FROM:<" + sender + ">");
            if (!expect(remoteIn, "250")) {
                return false;
            }

            // 4. Envelope Recipient
            sendCmd(remoteOut, "RCPT TO:<" + recipient + ">");
            // Remote server might return 251 (User not local; will forward), which is also valid success.
            String resp = remoteIn.readLine();
            if (resp == null || (!resp.startsWith("250") && !resp.startsWith("251"))) {
                return false;
            }

            // 5. Data Transmission
            sendCmd(remoteOut, "DATA");
            if (!expect(remoteIn, "354")) {
                return false;
            }

            // Important: We must ensure headers are present.
            // If the original data doesn't have headers, we add basic ones.
            if (!data.toLowerCase().startsWith("from:")) {
                remoteOut.print("From: " + sender + "\r\n");
                remoteOut.print("To: " + recipient + "\r\n");
            }
            
            // Send body content
            // NOTE: Technically, we should "dot-stuff" here (replace "\n." with "\n..") 
            // to prevent premature ending.
            remoteOut.print(data);
            
            // End of Data Indicator (<CRLF>.<CRLF>)
            sendCmd(remoteOut, "\r\n."); 
            
            if (!expect(remoteIn, "250")) return false;

            // 6. Termination
            sendCmd(remoteOut, "QUIT");
            return true;
        } catch (IOException e) {
            System.err.println("Failed to connect to relay host " + mxHost + ": " + e.getMessage());
            return false;
        }
    }

    // ==========================================================
    //                   HELPER METHODS
    // ==========================================================

    /**
     * Sends a raw response to the connected client.
     * Enforces CRLF (\r\n) line endings as per RFC 5321.
    */
    private void sendResponse(String msg) {
        out.print(msg + "\r\n");
        out.flush();
    }

    /**
     * Sends a command to a remote server.
     * Trims input and appends CRLF.
     */
    private void sendCmd(PrintWriter out, String cmd) {
        out.print(cmd.trim() + "\r\n");
        out.flush();
    }

    /**
     * Reads a line from the remote server and checks if it starts with the expected code.
     */
    private boolean expect(BufferedReader in, String code) throws IOException {
        String line = in.readLine();
        // System.out.println("Debug Remote Response: " + line); 
        return line != null && line.startsWith(code);
    }

    /**
     * Utility to clean email addresses (remove < and >).
     */
    private String extractEmail(String raw) {
        return raw.trim().replace("<", "").replace(">", "");
    }
}