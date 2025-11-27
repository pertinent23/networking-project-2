import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * SMTP Protocol handler for mail server
 * Handles basic SMTP commands
 */
public class SMTPProtocol extends DomainProtocol {
    // state variables to track SMTP session
    private String sender = "";
    private String recipient = "";
    private StringBuilder dataBuffer = new StringBuilder();

    public SMTPProtocol(Socket socket, String domain) throws IOException {
        super(socket, domain);
    }

    /**
     * Handles the SMTP session with the connected client
    */
    @Override
    public void handle() throws IOException {
        out.println("220 " + serverDomain + " Simple Mail Transfer Service Ready");
        
        String line;
        boolean dataMode = false;
        
        while ((line = in.readLine()) != null) {
            // 1. DATA MODE HANDLER
            if (dataMode) {
                if (line.equals(".")) {
                    dataMode = false;
                    processEmail();
                } else {
                    // Start with . handling (Transparency)
                    if (line.startsWith(".")) dataBuffer.append("."); 
                    dataBuffer.append(line).append("\r\n");
                }
                continue;
            }

            // 2. COMMAND PARSING
            // We need to extract the "Verb" for the switch statement
            String upperLine = line.trim().toUpperCase();
            String command = "";

            if (upperLine.startsWith("MAIL FROM:")) command = "MAIL";
            else if (upperLine.startsWith("RCPT TO:")) command = "RCPT";
            else if (upperLine.startsWith("HELO")) command = "HELO";
            else command = upperLine; // For DATA, QUIT

            // 3. COMMAND SWITCH
            switch (command) {
                case "HELO":
                    // Format: HELO client.domain.com
                    out.println("250 " + serverDomain);
                    break;

                case "MAIL":
                    // Format: MAIL FROM: <address>
                    try {
                        // Robust parsing: find the index of ':' to support spaces or no spaces
                        int colonIndex = line.indexOf(':');
                        if (colonIndex > -1) {
                            sender = line.substring(colonIndex + 1).trim().replace("<", "").replace(">", "");
                            out.println("250 OK");
                        } else {
                            out.println("501 Syntax error in parameters or arguments");
                        }
                    } catch (Exception e) {
                        out.println("501 Syntax error");
                    }
                    break;

                case "RCPT":
                    // Format: RCPT TO: <address>
                    try {
                        int colonIndex = line.indexOf(':');
                        if (colonIndex > -1) {
                            recipient = line.substring(colonIndex + 1).trim().replace("<", "").replace(">", "");
                            out.println("250 OK");
                        } else {
                            out.println("501 Syntax error");
                        }
                    } catch (Exception e) {
                        out.println("501 Syntax error");
                    }
                    break;

                case "DATA":
                    out.println("354 End data with <CRLF>.<CRLF>");
                    dataMode = true;
                    dataBuffer.setLength(0); // Reset buffer
                    break;

                case "QUIT":
                    out.println("221 Bye");
                    return; // Break loop and close connection

                default:
                    out.println("500 Unrecognized command");
                    break;
            }
        }
    }

    /**
     * Process the received email data 
    */
    private void processEmail() {
        String targetDomain = recipient.substring(recipient.indexOf('@') + 1);
        
        if (targetDomain.equalsIgnoreCase(serverDomain)) {
            // --- LOCAL DELIVERY ---
            try {
                // Prepend headers for local storage so POP3/IMAP clients can display them
                StringBuilder finalContent = new StringBuilder("From: ")
                        .append(sender).append("\r\nTo: ")
                        .append(recipient).append("\r\n")
                        .append(dataBuffer.toString());

                MailStorageManager.saveEmail(recipient, "INBOX", finalContent.toString());
                out.println("250 OK Message accepted for delivery");
            } catch (IOException e) {
                e.printStackTrace();
                out.println("451 Requested action aborted: local error");
            }
        } else {
            // --- RELAYING (FORWARDING) ---
            System.out.println("Relaying email to remote domain: " + targetDomain);
            try {
                String mxHost = resolveMX(targetDomain);
                
                if (mxHost == null) {
                    out.println("550 No mail server found for domain " + targetDomain);
                    return;
                }

                boolean success = sendToRemoteServer(mxHost, sender, recipient, dataBuffer.toString());

                if (success) {
                    out.println("250 OK Message relayed to " + mxHost);
                } else {
                    out.println("451 Failed to relay message to remote server");
                }

            } catch (Exception e) {
                e.printStackTrace();
                out.println("451 Error during forwarding");
            }
        }
    }

    /**
     * Resolves the MX record for the given domain using 'dig' command
    */
    private String resolveMX(String domain) {
        try {
            ProcessBuilder pb = new ProcessBuilder("dig", "+short", "MX", domain);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            String bestMx = null;
            int bestPriority = Integer.MAX_VALUE;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        int priority = Integer.parseInt(parts[0]);
                        String host = parts[1];
                        if (priority < bestPriority) {
                            bestPriority = priority;
                            bestMx = host;
                        }
                    } catch (NumberFormatException e) {
                        bestMx = parts[0]; 
                    }
                }
            }
            
            if (bestMx != null && bestMx.endsWith(".")) {
                bestMx = bestMx.substring(0, bestMx.length() - 1);
            }
            
            // Fallback to A record if no MX
            if (bestMx == null) {
                return domain; 
            }

            return bestMx;

        } catch (IOException e) {
            System.err.println("DNS Lookup failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Sends the email to the remote SMTP server
     */
    private boolean sendToRemoteServer(String mxHost, String sender, String recipient, String data) {
        try (Socket socket = new Socket(mxHost, 25);
            BufferedReader remoteIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter remoteOut = new PrintWriter(socket.getOutputStream(), true)) {

            if (!checkResponse(remoteIn, "220")) 
                return false;

            remoteOut.println("HELO " + serverDomain);
            if (!checkResponse(remoteIn, "250")) 
                return false;

            remoteOut.println("MAIL FROM: <" + sender + ">");
            if (!checkResponse(remoteIn, "250")) 
                return false;

            remoteOut.println("RCPT TO: <" + recipient + ">");
            
            // 250 OK or 251 User not local
            String rcptResp = remoteIn.readLine();
            if (rcptResp == null || (!rcptResp.startsWith("250") && !rcptResp.startsWith("251"))) 
                return false;

            remoteOut.println("DATA");
            if (!checkResponse(remoteIn, "354")) 
                return false;

            // Send Headers
            remoteOut.println("From: " + sender);
            remoteOut.println("To: " + recipient);
            
            // Ensure blank line between headers and body
            if(!data.startsWith("\r\n") && !data.startsWith("\n")) {
                remoteOut.println(); 
            }

            remoteOut.print(data);
            remoteOut.println("\r\n."); 
            
            if (!checkResponse(remoteIn, "250")) 
                return false;

            remoteOut.println("QUIT");
            return true;
        } catch (IOException e) {
            System.err.println("Failed to connect to remote server " + mxHost + ": " + e.getMessage());
            return false;
        }
    }

    // Helper to reduce duplicated code in sendToRemoteServer
    private boolean checkResponse(BufferedReader in, String expectedCode) throws IOException {
        String line = in.readLine();
        return line != null && line.startsWith(expectedCode);
    }
}