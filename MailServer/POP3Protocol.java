import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * POP3 Protocol handler for mail server
 * Handles basic POP3 commands specified in the assignment
*/
public class POP3Protocol extends DomainProtocol {
    private String currentUser = null;
    private List<File> messages;
    // We keep track of deleted messages by index to handle the UPDATE state
    private List<Integer> markedForDeletion = new ArrayList<>();

    public POP3Protocol(Socket socket, String domain) throws IOException {
        super(socket, domain);
    }

    @Override
    public void handle() throws IOException {
        out.println("+OK POP3 server ready");

        String line;
        while ((line = in.readLine()) != null) {
            String[] parts = line.split(" ");
            String cmd = parts[0].toUpperCase();

            // Using switch as requested
            switch (cmd) {
                case "USER":
                    if (parts.length < 2) {
                        out.println("-ERR User required");
                    } else {
                        currentUser = parts[1];
                        out.println("+OK User accepted");
                    }
                    break;

                case "PASS":
                    if (parts.length < 2) {
                        out.println("-ERR Password required");
                    } else if (MailStorageManager.authenticate(currentUser, parts[1], serverDomain)) {
                        out.println("+OK Logged in");
                        messages = MailStorageManager.getMessages(currentUser, "INBOX");
                        markedForDeletion.clear(); // Reset deletion list on new login
                    } else {
                        out.println("-ERR Auth failed");
                        currentUser = null;
                    }
                    break;

                case "STAT":
                    // Show count and total size
                    if (!isAuthenticated()) continue;
                    long totalSize = 0;
                    int count = 0;
                    
                    for (int i = 0; i < messages.size(); i++) {
                        if (!markedForDeletion.contains(i)) {
                            totalSize += messages.get(i).length();
                            count++;
                        }
                    }
                    out.println("+OK " + count + " " + totalSize);
                    break;

                case "LIST":
                    // List messages
                    if (!isAuthenticated()) 
                        continue;
                    
                    // We must calculate valid count first
                    long validCount = messages.size() - markedForDeletion.size();
                    out.println("+OK " + validCount + " messages");
                    
                    for (int i = 0; i < messages.size(); i++) {
                        if (!markedForDeletion.contains(i)) {
                            // POP3 indexes start at 1
                            out.println((i + 1) + " " + messages.get(i).length());
                        }
                    }
                    out.println(".");
                    break;

                case "RETR":
                    // Retrieve message
                    if (!isAuthenticated()) 
                        continue;

                    if (parts.length < 2) {
                        out.println("-ERR Missing argument");
                        break;
                    }
                    handleRetr(parts[1]);
                    break;

                case "DELE":
                    //  Mark message for deletion
                    if (!isAuthenticated()) 
                        continue;

                    if (parts.length < 2) {
                        out.println("-ERR Missing argument");
                        break;
                    }
                    handleDele(parts[1]);
                    break;

                case "QUIT":
                    // Update state: remove deleted messages
                    processDeletions();
                    out.println("+OK Bye");
                    return; // Break the loop and close connection

                default:
                    out.println("-ERR Unknown command");
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
            out.println("-ERR Authenticate first");
            return false;
        }
        return true;
    }

    /**
     * Handle RETR command
     * @param arg
    */
    private void handleRetr(String arg) {
        try {
            int msgIndex = Integer.parseInt(arg) - 1; // POP3 is 1-based

            if (isInvalidIndex(msgIndex)) {
                out.println("-ERR Message not found or deleted");
                return;
            }

            File f = messages.get(msgIndex);
            
            out.println("+OK " + f.length() + " octets");

            try (BufferedReader fileReader = new BufferedReader(new FileReader(f))) {
                String fileLine;
                while ((fileLine = fileReader.readLine()) != null) {
                    // Byte Stuffing [Standard POP3 requirement]
                    if (fileLine.startsWith(".")) {
                        out.print(".");
                    }
                    out.println(fileLine);
                }
            } catch (IOException e) {
                System.err.println("Error reading file: " + f.getName());
            }
            out.println(".");
            
        } catch (NumberFormatException e) {
            out.println("-ERR Invalid message number");
        }
    }

    /**
     * Handle DELE command
     * @param arg
    */
    private void handleDele(String arg) {
        try {
            int msgIndex = Integer.parseInt(arg) - 1;

            if (isInvalidIndex(msgIndex)) {
                out.println("-ERR Message already deleted or invalid");
            } else {
                markedForDeletion.add(msgIndex);
                out.println("+OK Message marked for deletion");
            }
        } catch (NumberFormatException e) {
            out.println("-ERR Invalid message number");
        }
    }

    /**
     * Process deletions at QUIT command
     *
    */
    private void processDeletions() {
        if (currentUser != null && !markedForDeletion.isEmpty()) {
            for (int index : markedForDeletion) {
                File f = messages.get(index);
                if (f.exists()) {
                    // Assuming StorageManager has a delete method
                    try {
                        MailStorageManager.deleteMessageFile(f);
                    } catch (IOException e) {
                        System.err.println("[Failed to delete message: " + f.getName() + "]");
                    }
                }
            }
        }
    }

    /**
     * Check if the given message index is invalid or marked for deletion
     * @param index
     * @return
    */
    private boolean isInvalidIndex(int index) {
        return index < 0 || index >= messages.size() || markedForDeletion.contains(index);
    }
}