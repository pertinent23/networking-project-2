import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * POP3 Protocol handler for mail server
 * Handles basic POP3 commands specified in the assignment
 *  
*/
public class POP3Protocol extends MailProtocol {
    private String currentUser = null;
    private List<File> messages = new ArrayList<>();
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
            System.out.println("[POP3Protocol.java: C: " + line + "]");

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
                        break;
                    }

                    refreshMessageList();
                    long totalSize = 0;
                    for (File msg : getVisibleMessages()) {
                        totalSize += msg.length();
                    }
                    sendOk(getVisibleMessages().size() + " " + totalSize);
                    break;

                case "LIST":
                case "UIDL":
                     if (!isAuthenticated()) {
                        break;
                    }
                    refreshMessageList();
                    handleList(cmd.equals("UIDL"));
                    break;

                case "RETR":
                    if (!isAuthenticated()) {
                        break;
                    }
                    if (parts.length < 2) {
                        sendErr("Missing argument");
                        break;
                    }
                    refreshMessageList();
                    handleRetr(parts[1]);
                    break;

                case "DELE":
                    if (!isAuthenticated()) {
                        break;
                    }
                    if (parts.length < 2) {
                        sendErr("Missing argument");
                        break;
                    }
                    handleDele(parts[1]);
                    refreshMessageList();
                    break;
                
                case "RSET":
                    if (!isAuthenticated()) {
                        break;
                    }

                    handleReset();
                    break;
                
                case "NOOP":
                    if (!isAuthenticated()) {
                        break;
                    }

                    sendOk("Noop");
                    break;

                case "QUIT":
                    processDeletions();
                    sendOk("Bye");
                    close();
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
     * Refreshes the local message list from the storage.
     */
    private void refreshMessageList() {
        if (currentUser != null) {
            messages = MailStorageManager.getMessages(currentUser, currentFolder);
        }
    }

    /**
     * Returns a list of messages not marked for deletion.
     * @return A list of visible messages.
     */
    private List<File> getVisibleMessages() {
        List<File> visible = new ArrayList<>();
        for (File msg : messages) {
            if (!isMarkedForDeletion(msg)) {
                visible.add(msg);
            }
        }
        return visible;
    }

    /**
     * use for list message in the current folder [INBOX]
     * @param isUID equal true if the command UIDL is used
    */
    private void handleList(boolean isUID) {
        List<File> visibleMessages = getVisibleMessages();
        StringBuilder response = new StringBuilder();
                    
        for (int i = 0; i < visibleMessages.size(); i++) {
            File msg = visibleMessages.get(i);
            if (!isUID) {
                response.append((i + 1) + " " + msg.length() + "\r\n");
            } else {
                response.append((i + 1) + " " + getUidFromFile(msg) + "\r\n");
            }
        }

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
            int msgIndex = Integer.parseInt(arg) - 1;  // Message index is 1-based
            List<File> visibleMessages = getVisibleMessages();
            File message = visibleMessages.get(msgIndex);

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

            out.flush();
            
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
            int msgIndex = Integer.parseInt(arg) - 1;  // Message index is 1-based
            List<File> visibleMessages = getVisibleMessages();
            File message = visibleMessages.get(msgIndex);

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
        for (File message : messages) {
            if (isMarkedForDeletion(message)) {
                MailStorageManager.updateFlag(currentUser, currentFolder, getUidFromFile(message), "\\Deleted", false);
            }
        }
        sendOk("maildrop has " + messages.size() + " messages");
    }

    /**
     * Process deletions at QUIT command
     * 
    */
    private void processDeletions() {
        if (currentUser == null) return;
        
        // Use an iterator to safely remove while iterating if needed, though here we just delete files.
        Iterator<File> it = messages.iterator();
        while (it.hasNext()) {
            File message = it.next();
            if (isMarkedForDeletion(message)) {
                try {
                    MailStorageManager.deleteMessageFile(message);
                } catch (IOException e) {
                    System.err.println("[POP3Protocol.java: Failed to delete message: " + message.getName() + "]");
                }
            }
        }
    }

    private boolean isMarkedForDeletion(File message) {
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