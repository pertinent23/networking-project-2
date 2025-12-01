import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.Socket;
import java.util.List;

/**
 * POP3 Protocol handler for mail server
 * Handles basic POP3 commands specified in the assignment
 *  
*/
public class POP3Protocol extends MailProtocol {
    private String currentUser = null;
    private List<File> messages = List.of();
    private final String currentFolder = "INBOX";

    public POP3Protocol(Socket socket, String domain) throws IOException {
        super(socket, domain);
    }

    @Override
    public void handle() throws IOException {
        sendOk("POP3 server ready");

        String line;
        while ((line = in.readLine()) != null) {
            String[] parts = line.split(" ", 2);
            String cmd = parts[0].toUpperCase();

            switch (cmd) {
                case "USER":
                    if (parts.length < 2) {
                        sendErr("User required");
                    } else {
                        currentUser = parts[1];
                        sendOk("User accepted");
                    }

                    break;

                case "PASS":
                    if (parts.length < 2) {
                        sendErr("Password required");
                    } else if (MailStorageManager.authenticate(currentUser, parts[1], serverDomain)) {
                        sendOk("Logged in");
                        messages = MailStorageManager.getMessages(currentUser, currentFolder);
                    } else {
                        sendErr("Auth failed");
                        currentUser = null;
                    }
                    break;

                case "STAT":
                    if (!isAuthenticated()) {
                        continue;
                    }

                    long totalSize = 0;
                    int count = 0;
                    
                    for (int i = 0; i < messages.size(); i++) {
                        if (!isMakedForDeletion(messages.get(i))) {
                            totalSize += messages.get(i).length();
                            count++;
                        }
                    }
                    sendOk(count + " " + totalSize);
                    break;

                case "LIST":
                case "UIDL":
                     if (!isAuthenticated()) {
                        continue;
                    }
                    
                    handleList(cmd.equals("UIDL"));
                    break;

                case "RETR":
                    if (!isAuthenticated()) {
                        continue;
                    }

                    if (parts.length < 2) {
                        sendErr("Missing argument");
                        break;
                    }

                    handleRetr(parts[1]);
                    break;

                case "DELE":
                    if (!isAuthenticated()) {
                        continue;
                    }

                    if (parts.length < 2) {
                        sendErr("Missing argument");
                        break;
                    }

                    handleDele(parts[1]);
                    break;
                
                case "RSET":
                    if (!isAuthenticated()) {
                        continue;
                    }

                    handleReset();
                    break;
                
                case "NOOP":
                    if (!isAuthenticated()) {
                        continue;
                    }

                    sendOk("Noop");
                    break;

                case "QUIT":
                    processDeletions();
                    sendOk("Bye");
                    return;

                default:
                    sendErr("Unknown command");
                    break;
            }
        }
    }

    /**
     * Check if user is authenticated
     * @return
    */
    private boolean isAuthenticated() {
        if (currentUser == null) {
            sendErr("Authenticate first");
            return false;
        }
        return true;
    }

    /**
     * use for list message in the current folder [INBOX]
     * @param isUID equal true if the command UIDL is used
    */
    private void handleList(boolean isUID) {
        int counter = 0;
        StringBuilder response = new StringBuilder();
                    
        for (int i = 0; i < messages.size(); i++) {
            if (!isMakedForDeletion(messages.get(i))) {
                if (!isUID) {
                    response.append((i+1) + " " + messages.get(i).length() + "\r\n");
                } else {
                    response.append((i+1) + " " + getUidFromFile(messages.get(i)) + "\r\n");
                }
            } else {
                counter++;
            }
        }

        sendOk((messages.size() - counter) + " messages");
        out.print(response.toString());
        out.print(".\r\n");
        out.flush();
    }

    /**
     * Handle RETR command
     * @param arg
    */
    private void handleRetr(String arg) {
        try {
            int uid = Integer.parseInt(arg) - 1;  // Message index
            File message = messages.get(uid);
           
            if (isMakedForDeletion(message)) {
                sendErr("Message not found or deleted");
                return;
            }
            
            sendOk(message.length() + " octets");

            try (BufferedReader fileReader = new BufferedReader(new FileReader(message))) {
                String line;
                while ((line = fileReader.readLine()) != null) {
                    if (line.startsWith(".")) {
                        out.print(".");
                    }
                    out.print(line.concat("\r\n"));
                }
            } catch (IOException e) {
                System.err.println("[POP3Protocol.java: Error reading file: " + message.getName() + "]");
            } finally {
                out.print(".\r\n");
            }
            
        } catch (NumberFormatException e) {
            sendErr("Invalid message number");
        } catch (IndexOutOfBoundsException e) {
            sendErr("Message not found or deleted");
        }
    }

    /**
     * Handle DELE command
     * @param arg
    */
    private void handleDele(String arg) {
        try {
            int uid = Integer.parseInt(arg) - 1;  // Message index
            File message = messages.get(uid);
           
            if (isMakedForDeletion(message)) {
                sendErr("Message not found or deleted");
                return;
            }

            List<String> flags = MailStorageManager.getFlags(currentUser, currentFolder, getUidFromFile(message));

            if (flags.contains("\\Deleted")) {
                sendErr("Message already deleted or invalid");
            } else {
                MailStorageManager.updateFlag(currentUser, currentFolder, getUidFromFile(message), "\\Deleted", true);
                sendOk("Message marked for deletion");
            }
        } catch (NumberFormatException e) {
            sendErr("Invalid message number");
        } catch (IndexOutOfBoundsException e) {
            sendErr("Message not found or deleted");
        }
    }

    /**
     * If the user want to reset the deletion
     * before quit we can reset
     * 
    */
    private void handleReset() {
        messages.forEach(message -> {
            if (message.exists()) {
                List<String> flags = MailStorageManager.getFlags(currentUser, currentFolder, getUidFromFile(message));

                if (flags.contains("\\Deleted")) {
                    MailStorageManager.updateFlag(currentUser, currentFolder, getUidFromFile(message), "\\Deleted", false);
                }
            }
        });

        sendOk("Messages has been reseted");
    }

    /**
     * Process deletions at QUIT command
     * 
    */
    private void processDeletions() {
        messages.forEach(message -> {
            if (message.exists()) {
                List<String> flags = MailStorageManager.getFlags(currentUser, currentFolder, getUidFromFile(message));

                if (flags.contains("\\Deleted")) {
                    try {
                        MailStorageManager.deleteMessageFile(message);
                    } catch (IOException e) {
                        System.err.println("[POP3Protocol.java: Failed to delete message: " + message.getName() + "]");
                    }
                }
            }
        });
    }

    private boolean isMakedForDeletion(File message) {
        int uid = getUidFromFile(message);
        List<String> flags = MailStorageManager.getFlags(currentUser, currentFolder, uid);
        return flags.contains("\\Deleted");
    }

    /**
     * Extracts UID from filename
     * @param f
     * @return
    */
    private int getUidFromFile(File f) {
        try {
            // we assume filename is like "123.eml"
            return Integer.parseInt(f.getName().split("[^0-9]")[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 
     * @param tag
     * @param msg
    */
    private void sendErr(String msg) {
        out.print("-ERR " + msg + "\r\n");
        out.flush();
    }

    /**
     * 
     * @param tag
     * @param msg
    */
    private void sendOk(String msg) {
        out.print("+OK " + msg + "\r\n");
        out.flush();
    }
}