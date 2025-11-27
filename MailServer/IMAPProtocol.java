import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IMAP Protocol handler for mail server
 * Compatible with Mozilla Thunderbird
*/
public class IMAPProtocol extends DomainProtocol {
    private String currentUser = null;
    private String currentMailbox = null;
    private List<File> currentMessages = new LinkedList<>();
    private Map<Integer, Set<String>> sessionFlags = new HashMap<>();

    // Regex pour parser proprement: TAG COMMAND ARGS
    private static final Pattern IMAP_CMD_PATTERN = Pattern.compile("^([^ ]+) ([^ ]+)(?: (.*))?$");

    public IMAPProtocol(Socket socket, String domain) throws IOException {
        super(socket, domain);
    }

    public void handle() throws IOException {
        // Greeting initial
        out.print("* OK IMAP4rev1 Service Ready\r\n");
        out.flush();

        String line;
        while ((line = in.readLine()) != null) {
            Matcher matcher = IMAP_CMD_PATTERN.matcher(line);
            if (!matcher.find()) 
                continue;

            String tag = matcher.group(1);     // Ex: A1
            String cmd = matcher.group(2).toUpperCase(); // Ex: LOGIN
            String args = matcher.group(3);    // Ex: "user" "pass"

            try {
                switch (cmd) {
                    case "CAPABILITY": 
                        out.print("* CAPABILITY IMAP4rev1 UID\r\n");
                        sendOk(tag, "CAPABILITY completed");
                        break;

                    case "NOOP": 
                        sendOk(tag, "NOOP completed");
                        break;

                    case "LOGIN": 
                        handleLogin(tag, args);
                        break;

                    case "LOGOUT":
                        out.print("* BYE Server logging out\r\n");
                        sendOk(tag, "LOGOUT completed");
                        return;

                    /** 
                     * Folder Management Commands
                    */
                    case "LIST":
                        handleList(tag, args);
                        break;
                    
                    case "LSUB": 
                        // Same as LIST for our simple implementation
                        handleList(tag, args); 
                        break;

                    case "SELECT":
                        handleSelect(tag, args);
                        break;

                    case "CREATE":
                        handleCreate(tag, args);
                        break;

                    case "DELETE":
                        handleDelete(tag, args);
                        break;

                    case "RENAME":
                        handleRename(tag, args);
                        break;

                    case "SUBSCRIBE":
                    case "UNSUBSCRIBE":
                        sendOk(tag, cmd + " completed");
                        break;

                    /**
                     * Message Management (UID commands)
                    */

                    case "UID":
                        handleUidCommand(tag, args);
                        break;

                    case "EXPUNGE":
                        handleExpunge(tag);
                        break;

                    case "CLOSE":
                        handleExpunge(tag);
                        currentMailbox = null;
                        sendOk(tag, "CLOSE completed");
                        break;

                    default:
                        sendBad(tag, "Command not supported");
                }

                out.flush(); // always flush after each command response

            } catch (Exception e) {
                e.printStackTrace();
                out.print(tag + " NO Error processing command\r\n");
                out.flush();
            }
        }
    }

    /**
     * Handle LOGIN command
     * @param tag //ex A1
     * @param args
    */
    private void handleLogin(String tag, String args) {
        if (args == null) { 
            sendBad(tag, "Missing args"); 
            return; 
        }

        String[] parts = args.replace("\"", "").split(" ");
        
        if (parts.length < 2) { 
            sendBad(tag, "Invalid args"); return; 
        }

        if (MailStorageManager.authenticate(parts[0], parts[1], serverDomain)) {
            currentUser = parts[0];
            sendOk(tag, "LOGIN completed");
        } else {
            sendNo(tag, "Login failed");
        }
    }

    /**
     * Handle LIST command
     * @param tag
     * @param args
    */
    private void handleList(String tag, String args) {
        if (currentUser == null) { 
            sendNo(tag, "Login first"); 
            return; 
        }
        
        // we have to list all folders for the user
        // including INBOX and any created folders  
        // attempted format : * LIST (\HasNoChildren) "/" "FolderName"
        
        try {
            File userDir = MailStorageManager.getUserDirectory(currentUser);

            if (userDir != null && userDir.exists()) {
                File[] folders = userDir.listFiles(File::isDirectory);
                if (folders != null) {
                    for (File f : folders) {
                        out.print("* LIST (\\HasNoChildren) \"/\" \"" + f.getName() + "\"\r\n");
                    }
                }
            } else {
                out.print("* LIST (\\HasNoChildren) \"/\" \"INBOX\"\r\n");
            }
        } catch (IOException e) {
            out.print("* LIST (\\HasNoChildren) \"/\" \"INBOX\"\r\n");
        } finally {
            sendOk(tag, "LIST completed");
        }
    }

    /**
     * Handle SELECT command
     * @param tag
     * @param args
    */
    private void handleSelect(String tag, String args) {
        if (currentUser == null) { 
            sendNo(tag, "Login first"); 
            return; 
        }
        
        String mailboxName = args.replace("\"", "").trim();

        if (!mailboxName.equalsIgnoreCase("INBOX") && !MailStorageManager.folderExists(currentUser, mailboxName)) {
            sendNo(tag, "Mailbox does not exist");
            return;
        }

        currentMailbox = mailboxName;
        currentMessages = MailStorageManager.getMessages(currentUser, currentMailbox);
        sessionFlags.clear();

        for(int i=0; i<currentMessages.size(); i++) {
            sessionFlags.put(i+1, new HashSet<>()); 
        }

        System.err.println("[User " + currentUser + " selected mailbox " + currentMailbox + "]");

        out.print("* " + currentMessages.size() + " EXISTS\r\n");
        out.print("* 0 RECENT\r\n");
        out.print("* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)\r\n");
        out.print("* OK [UIDVALIDITY 1] UIDs valid\r\n");
        sendOk(tag, "[READ-WRITE] SELECT completed");
    }

    private void handleCreate(String tag, String args) {
        if (currentUser == null) { 
            sendNo(tag, "Login first"); 
            return; 
        }

        String folder = args.replace("\"", "").trim();
        
        if (MailStorageManager.createFolder(currentUser, folder)) {
            sendOk(tag, "CREATE completed");
        } else {
            sendNo(tag, "Create failed");
        }
    }

    /**
     * Handle DELETE command
     * @param tag
     * @param args
    */
    private void handleDelete(String tag, String args) {
        if (currentUser == null) { 
            sendNo(tag, "Login first"); 
            return; 
        }

        String folder = args.replace("\"", "").trim();
        
        if (folder.equalsIgnoreCase("INBOX")) {
            sendNo(tag, "Cannot delete INBOX");
            return;
        }
        if (MailStorageManager.deleteFolder(currentUser, folder)) {
            sendOk(tag, "DELETE completed");
        } else {
            sendNo(tag, "Delete failed");
        }
    }

    /**
     * Handle RENAME command
     * @param tag
     * @param args
     */
    private void handleRename(String tag, String args) {
        if (currentUser == null) { 
            sendNo(tag, "Login first"); 
            return; 
        }

        String[] parts = args.split(" ");
        
        if (parts.length < 2) { 
            sendBad(tag, "Missing args"); 
            return; 
        }
        
        String oldName = parts[0].replace("\"", "");
        String newName = parts[1].replace("\"", "");

        if (MailStorageManager.renameFolder(currentUser, oldName, newName)) {
            sendOk(tag, "RENAME completed");
        } else {
            sendNo(tag, "Rename failed");
        }
    }

    // --- UID COMMANDS (FETCH, STORE, COPY) ---

    /**
     * Handle UID command
     * @param tag
     * @param args
     * @throws IOException
    */
    private void handleUidCommand(String tag, String args) throws IOException {
        if (currentMailbox == null) { 
            sendNo(tag, "Select mailbox first"); 
            return; 
        }

        String[] parts = args.split(" ", 2);
        String subCmd = parts[0].toUpperCase();
        String params = (parts.length > 1) ? parts[1] : "";

        if (subCmd.equals("FETCH")) { 
            handleUidFetch(tag, params);
        } 
        else if (subCmd.equals("STORE")) {
            // Ex: UID STORE 1 +FLAGS (\Seen)
            handleUidStore(tag, params);
        }
        else if (subCmd.equals("COPY")) {
            // Ex: UID COPY 1 "Trash"
            handleUidCopy(tag, params);
        }
        else {
            sendBad(tag, "Unknown UID command");
        }
    }

    /**
     *  Handle UID FETCH command
     * @param tag
     * @param params
     * @throws IOException
    */
    private void handleUidFetch(String tag, String params) throws IOException {
        // params ex: 1:* (FLAGS BODY[])
        boolean requestingBody = params.contains("BODY");
        boolean requestingFlags = params.contains("FLAGS");
        boolean requestingSize = params.contains("RFC822.SIZE");

        for (int i = 0; i < currentMessages.size(); i++) {
            int uid = i + 1;
            File msg = currentMessages.get(i);
            if (!msg.exists()) 
                continue; // Perhaps deleted in the meantime

            StringBuilder sb = new StringBuilder();
            sb.append("* ")
                .append(uid)
                .append(" FETCH (UID ")
                .append(uid);

            if (requestingFlags) {
                sb.append(" FLAGS (");
                Set<String> flags = sessionFlags.getOrDefault(uid, new HashSet<>());
                sb.append(String.join(" ", flags));
                sb.append(") ");
            }

            if (requestingSize) {
                sb.append(" RFC822.SIZE ").append(msg.length()).append(" ");
            }

            if (requestingBody) {
                sb.append(" BODY[] {").append(msg.length()).append("}\r\n");

                out.print(sb.toString());
                out.flush();
                Files.copy(msg.toPath(), rawOut);
                out.print(")");
            } else {
                sb.append(")");
                out.print(sb.toString());
            }
            out.print("\r\n");
            out.flush();
        }
        sendOk(tag, "UID FETCH completed");
    }

    /**
     * Handle UID STORE command
     * @param tag
     * @param params
    */
    private void handleUidStore(String tag, String params) {
        String[] p = params.split(" ", 3);

        if(p.length < 3) { 
            sendBad(tag, "Invalid STORE args"); 
            return; 
        }
        
        try {
            int uid = Integer.parseInt(p[0]);
            String mode = p[1]; // +FLAGS, -FLAGS, FLAGS
            String flagStr = p[2].replace("(", "").replace(")", ""); // \Seen \Deleted

            Set<String> current = sessionFlags.getOrDefault(uid, new HashSet<>());
            List<String> newFlags = Arrays.asList(flagStr.split(" "));

            if (mode.equalsIgnoreCase("+FLAGS")) {
                current.addAll(newFlags);
            } else if (mode.equalsIgnoreCase("-FLAGS")) {
                current.removeAll(newFlags);
            } else {
                current.clear();
                current.addAll(newFlags);
            }
            sessionFlags.put(uid, current);
            
            out.print("* " + uid + " FETCH (FLAGS (" + String.join(" ", current) + "))\r\n");
            sendOk(tag, "UID STORE completed");

        } catch (NumberFormatException e) {
            sendBad(tag, "Invalid UID");
        }
    }

    /**
     * Handle UID COPY command
     * @param tag
     * @param params
     */
    private void handleUidCopy(String tag, String params) {
        String[] p = params.split(" ", 2);
        
        try {
            int uid = Integer.parseInt(p[0]);
            String targetFolder = p[1].replace("\"", "");

            File msg = currentMessages.get(uid - 1);
            if (msg.exists()) {
                MailStorageManager.copyMessage(currentUser, msg, targetFolder);
                sendOk(tag, "UID COPY completed");
            } else {
                sendNo(tag, "Message not found");
            }
        } catch (Exception e) {
            sendNo(tag, "Copy failed");
        }
    }

    /**
     * Handle EXPUNGE command
     * @param tag
    */
    private void handleExpunge(String tag) {
        if (currentMailbox == null) { 
            sendNo(tag, "Select first"); 
            return; 
        }
        
        // Delete messages marked with \Deleted flag
        Iterator<Map.Entry<Integer, Set<String>>> it = sessionFlags.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Set<String>> entry = it.next();
            if (entry.getValue().contains("\\Deleted")) {
                int uid = entry.getKey();

                if (uid - 1 < currentMessages.size()) {
                    File f = currentMessages.get(uid - 1);
                    if (f.delete()) {
                        out.print("* " + uid + " EXPUNGE\r\n");
                    }
                }
            }
        }

        // load updated message list
        currentMessages = MailStorageManager.getMessages(currentUser, currentMailbox);
        sendOk(tag, "EXPUNGE completed");
    }

    // --- Helpers ---
    private void sendOk(String tag, String msg) { 
        out.print(tag + " OK " + msg + "\r\n"); 
        out.flush(); 
    }

    private void sendNo(String tag, String msg) { 
        out.print(tag + " NO " + msg + "\r\n"); 
        out.flush(); 
    }

    private void sendBad(String tag, String msg) {
        out.print(tag + " BAD " + msg + "\r\n"); 
        out.flush(); 
    }
}