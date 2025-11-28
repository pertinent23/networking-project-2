import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IMAP Protocol handler for mail server
 * Handles basic IMAP commands
*/
public class IMAPProtocol extends DomainProtocol {
    private String currentUser = null;
    private String currentMailbox = null;

    // we use this to track messages in the selected mailbox
    private List<File> currentMessages = new ArrayList<>();

    // regex to parse IMAP command lines
    private static final Pattern IMAP_CMD_PATTERN = Pattern.compile("^([^ ]+)\\s+([^ ]+)(?:\\s+(.*))?$");

    public IMAPProtocol(Socket socket, String domain) throws IOException {
        super(socket, domain);
    }

    public void handle() throws IOException {
        // Greeting with capabilities
        out.print("* OK [CAPABILITY IMAP4rev1 SASL-IR LOGIN-REFERRALS ID ENABLE IDLE LITERAL+] IMAP4rev1 Service Ready\r\n");
        out.flush();

        String line;
        while ((line = in.readLine()) != null) {
            System.out.println("[C: " + line + "]"); 

            Matcher matcher = IMAP_CMD_PATTERN.matcher(line);
            if (!matcher.find()) continue;

            String tag = matcher.group(1);
            String cmd = matcher.group(2).toUpperCase();
            String args = matcher.group(3);

            try {
                switch (cmd) {
                    case "CAPABILITY":
                        out.print("* CAPABILITY IMAP4rev1 UID LITERAL+\r\n");
                        sendOk(tag, "CAPABILITY completed");
                        break;

                    case "NOOP":
                        if (currentMailbox != null && currentUser != null) {
                            checkNewMessages();
                        }
                        sendOk(tag, "NOOP completed");
                        break;

                    case "LOGIN":
                        handleLogin(tag, args);
                        break;

                    case "LOGOUT":
                        out.print("* BYE Server logging out\r\n");
                        sendOk(tag, "LOGOUT completed");
                        return;

                    case "LIST":
                    case "LSUB":
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

                    case "UID":
                        handleUidCommand(tag, args);
                        break;

                    case "EXPUNGE":
                    case "CLOSE":
                        handleExpunge(tag);
                        if (cmd.equals("CLOSE")) currentMailbox = null;
                        sendOk(tag, cmd + " completed");
                        break;

                    default:
                        sendBad(tag, "Command not supported");
                }
                out.flush();
            } catch (Exception e) {
                e.printStackTrace();
                out.print(tag + " NO Error: " + e.getMessage() + "\r\n");
                out.flush();
            }
        }
    }

    /**
     * Handle LOGIN command
     * @param tag
     * @param args
    */
    private void handleLogin(String tag, String args) {
        if (args == null) { sendBad(tag, "Missing args"); return; }
        
        String[] parts = args.split("\\s+");
        // We have to remove the quotes around username and password
        String user = parts[0].replace("\"", "");
        String pass = parts.length > 1 ? parts[1].replace("\"", "") : "";

        if (MailStorageManager.authenticate(user, pass, serverDomain)) {
            currentUser = user;
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
        
        // always list all mailboxes
        out.print("* LIST (\\HasNoChildren) \"/\" \"INBOX\"\r\n");
        
        try {
            File userDir = MailStorageManager.getUserDirectory(currentUser);
            if (userDir != null && userDir.exists()) {
                File[] folders = userDir.listFiles(File::isDirectory);
                if (folders != null) {
                    for (File f : folders) {
                        if(!f.getName().equalsIgnoreCase("INBOX")) 
                            out.print("* LIST (\\HasNoChildren) \"/\" \"" + f.getName() + "\"\r\n");
                    }
                }
            }
            sendOk(tag, "LIST completed");
        } catch (IOException e) {
            e.printStackTrace();
            sendNo(tag, "Error listing mailboxes");
        }
    }

    /**
     * Handle CREATE command
     * @param tag
     * @param args
    */
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

        // 1. take the raw list of messages
        List<File> rawList = MailStorageManager.getMessages(currentUser, currentMailbox);
        
        // 2. make a mutable copy
        currentMessages = new ArrayList<>(rawList); 
        
        // 3. you can now sort it
        currentMessages.sort(Comparator.comparingInt(this::getUidFromFile));

        int maxUid = currentMessages.stream().mapToInt(this::getUidFromFile).max().orElse(0);
        int uidNext = maxUid + 1;

        out.print("* " + currentMessages.size() + " EXISTS\r\n");
        out.print("* 0 RECENT\r\n");
        out.print("* OK [UIDVALIDITY 1] UIDs valid\r\n");
        out.print("* OK [UIDNEXT " + uidNext + "] Predicted next UID\r\n");
        out.print("* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)\r\n");
        out.print("* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft \\*)] Limited\r\n");
        sendOk(tag, "[READ-WRITE] SELECT completed");
    }

    private void handleUidCommand(String tag, String args) throws IOException {
        if (currentMailbox == null) { 
            sendNo(tag, "Select mailbox first"); 
            return; 
        }

        String[] parts = args.split("\\s+", 2);
        String subCmd = parts[0].toUpperCase();
        String params = (parts.length > 1) ? parts[1] : "";

        if (subCmd.equals("FETCH")) {
            handleUidFetch(tag, params);
        } else if (subCmd.equals("STORE")) {
            handleUidStore(tag, params);
        } else if (subCmd.equals("COPY")) {
            handleUidCopy(tag, params);
        } else {
            sendBad(tag, "Unknown UID command");
        }
    }

    /**
     * Handle UID FETCH command
     * @param tag
     * @param params
     * @throws IOException
    */
    private void handleUidFetch(String tag, String params) throws IOException {
        // Ex: 1:* (BODY[])
        String[] paramParts = params.split("\\s+", 2);
        String range = paramParts[0];
        String dataItems = (paramParts.length > 1) ? paramParts[1].toUpperCase() : "";

        boolean fetchHeader = dataItems.contains("HEADER"); 
        boolean fetchBody = (dataItems.contains("BODY") && !fetchHeader) || (dataItems.contains("BODY\\.PEEK[]") && !fetchHeader);
        boolean fetchFlags = dataItems.contains("FLAGS");
        boolean isPeek = dataItems.contains("PEEK") && fetchHeader;

        Set<Integer> requestedUids = parseUidRange(range);

        for (int i = 0; i < currentMessages.size(); i++) {
            File msg = currentMessages.get(i);
            int uid = getUidFromFile(msg);
            int seqNum = i + 1; // Sequence number starts at 1

            if (!requestedUids.contains(uid)) 
                continue;
            
            if (!msg.exists()) 
                continue;

            StringBuilder sb = new StringBuilder();
            sb.append("* ").append(seqNum).append(" FETCH (UID ").append(uid);

            if (fetchFlags) {
                List<String> flags = MailStorageManager.getFlags(currentUser, currentMailbox, uid);
                sb.append(" FLAGS (").append(String.join(" ", flags)).append(")");
            }

            if (fetchHeader) {
                String headers = extractHeaders(msg);
                sb.append(" BODY[HEADER] {").append(headers.getBytes().length).append("}\r\n");
                sb.append(headers);
            }
            else if (fetchBody) {
                if (!isPeek) {
                    MailStorageManager.updateFlag(currentUser, currentMailbox, uid, "\\Seen", true);
                }
                sb.append(" BODY[] {").append(msg.length()).append("}\r\n");
                out.print(sb.toString());
                out.flush();
                
                Files.copy(msg.toPath(), rawOut);
                rawOut.flush();
                
                sb = new StringBuilder(); // Reset StringBuilder for closing parenthesis
            }

            sb.append(")\r\n");
            out.print(sb.toString());
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
        try {
            // Syntax: UID STORE <range> <operation> FLAGS (<flags>) [SILENT]
            String[] p = params.split("\\s+");
            String range = p[0];
            // Search for FLAGS part
            boolean isAdd = params.toUpperCase().contains("+FLAGS");
            boolean isRemove = params.toUpperCase().contains("-FLAGS");
            boolean isSet = !isAdd && !isRemove; // FLAGS tout court

            // Extraction des flags entre parenthèses
            int startParen = params.indexOf("(");
            int endParen = params.indexOf(")");
            if (startParen == -1 || endParen == -1) {
                sendBad(tag, "Invalid flags format");
                return;
            }
            String flagStr = params.substring(startParen + 1, endParen);
            List<String> newFlags = Arrays.asList(flagStr.split("\\s+"));

            Set<Integer> targetUids = parseUidRange(range);

            for (int i = 0; i < currentMessages.size(); i++) {
                File msg = currentMessages.get(i);
                int uid = getUidFromFile(msg);
                
                if (!targetUids.contains(uid)) 
                    continue;

                List<String> current = new ArrayList<>(MailStorageManager.getFlags(currentUser, currentMailbox, uid));

                for (String flag : newFlags) {
                    if (isAdd) {
                        if (!current.contains(flag)) 
                            current.add(flag);
                    } else if (isRemove) {
                        current.remove(flag);
                    } else if (isSet) {
                        /**
                         * SET mode: on remplace les flags actuels par les nouveaux
                         * 
                        */
                        if (!current.contains(flag)) 
                            current.add(flag); 
                    }
                }
                
                // Save
                MailStorageManager.setFlags(currentUser, currentMailbox, uid, String.join("|", current));
                
                // if the client did not request silent mode, send untagged response
                if (!params.toUpperCase().contains("SILENT")) {
                    out.print("* " + (i + 1) + " FETCH (UID " + uid + " FLAGS (" + String.join(" ", current) + "))\r\n");
                }
            }
            sendOk(tag, "UID STORE completed");
        } catch (Exception e) {
            e.printStackTrace();
            sendBad(tag, "Store failed");
        }
    }
    /**
     * Handle UID COPY command
     * @param tag
     * @param params
    */
    private void handleUidCopy(String tag, String params) {
        if (currentMailbox == null) { 
            sendNo(tag, "Select mailbox first"); 
            return; 
        }

        if (params == null || params.trim().isEmpty()) { 
            sendBad(tag, "Missing args"); 
            return; 
        }

        String[] parts = params.split("\\s+", 2);
        int range = Integer.parseInt(parts[0]);
        String destMailbox = (parts.length > 1) ? parts[1].trim().replace("\"", "") : "";

        if (destMailbox.isEmpty()) { 
            sendBad(tag, "Missing destination mailbox"); 
            return; 
        }

        // Destination mailbox must exist (INBOX is allowed)
        if (!destMailbox.equalsIgnoreCase("INBOX") && !MailStorageManager.folderExists(currentUser, destMailbox)) {
            sendNo(tag, "Destination mailbox does not exist");
            return;
        }

        if (currentMessages.size() <= range) { 
            sendNo(tag, "No messages match range"); 
            return; 
        }

        File msg = currentMessages.get(range);

        if (!msg.exists()) { 
            sendNo(tag, "Message file not found"); 
            return; 
        }

        try {
            // compute next UID in destination mailbox
            int nextUid = MailStorageManager.getNextUID(currentUser, destMailbox);

            StringBuilder srcList = new StringBuilder();
            StringBuilder dstList = new StringBuilder();

            MailStorageManager.copyMessage(currentUser, msg, destMailbox, nextUid);

            if (srcList.length() == 0) {
                sendNo(tag, "No messages copied");
                return;
            }

            // If copying into the currently selected mailbox, refresh currentMessages
            if (destMailbox.equalsIgnoreCase(currentMailbox)) {
                currentMessages = new ArrayList<>(MailStorageManager.getMessages(currentUser, currentMailbox));
                currentMessages.sort(Comparator.comparingInt(this::getUidFromFile));
            }

            // send tagged OK with COPYUID response as many servers do
            out.print(tag + " OK [COPYUID 1 " + srcList.toString() + " " + dstList.toString() + "] COPY completed\r\n");
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
            sendBad(tag, "COPY failed");
        }
    }

    /**
     * Handle EXPUNGE command
     * @param tag
    */
    private void handleExpunge(String tag) {
        if (currentMailbox != null) {
            Iterator<File> it = currentMessages.iterator();
            while (it.hasNext()) {
                File f = it.next();
                int uid = getUidFromFile(f);
                List<String> flags = MailStorageManager.getFlags(currentUser, currentMailbox, uid);
                if (flags.contains("\\Deleted")) {
                    f.delete();
                    it.remove();
                }
            }
        }
    }

    /**
     * Vérifie les nouveaux messages dans la boîte aux lettres sélectionnée
     * 
    */
    private void checkNewMessages() {
        List<File> freshList = MailStorageManager.getMessages(currentUser, currentMailbox);
        if (freshList.size() > currentMessages.size()) {
            out.print("* " + freshList.size() + " EXISTS\r\n");
            out.print("* " + (freshList.size() - currentMessages.size()) + " RECENT\r\n");
            // update the currentMessages list
            currentMessages = new ArrayList<>(freshList);
            currentMessages.sort(Comparator.comparingInt(this::getUidFromFile));
        }
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
     * Parses a UID range string into a set of UIDs
     *  
     * @param range
     * @return
    */
    private Set<Integer> parseUidRange(String range) {
        Set<Integer> uids = new HashSet<>();
        try {
            if (range.contains(":")) {
                String[] parts = range.split(":");
                int start = Integer.parseInt(parts[0]);
                int maxUid = currentMessages.stream().mapToInt(this::getUidFromFile).max().orElse(0);
                // if "*" is used, it means up to the max UID
                int end = parts[1].equals("*") ? maxUid : Integer.parseInt(parts[1]);
                
                // if the range is reversed
                if (end < start && parts[1].equals("*")) end = Integer.MAX_VALUE;

                for (File f : currentMessages) {
                    int uid = getUidFromFile(f);
                    if (uid >= start && uid <= end) uids.add(uid);
                }
            } else if (range.contains(",")) {
                for (String s : range.split(",")) {
                    try { uids.add(Integer.parseInt(s)); } catch (Exception ignored) {}
                }
            } else {
                uids.add(Integer.parseInt(range));
            }
        } catch (Exception e) {
            // 
        }
        return uids;
    }

    /**
     * Extracts headers from the email file
     * @param f
     * @return
     * @throws IOException
    */
    private String extractHeaders(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) 
                    break;
                sb.append(line).append("\r\n");
            }
        }

        return sb.toString();
    }

    /**
     * 
     * @param tag
     * @param msg
    */
    private void sendOk(String tag, String msg) {
        out.print(tag + " OK " + msg + "\r\n");
        out.flush();
    }

    /**
     * 
     * @param tag
     * @param msg
    */
    private void sendNo(String tag, String msg) {
        out.print(tag + " NO " + msg + "\r\n");
        out.flush();
    }

    /**
     * 
     * @param tag
     * @param msg
    */
    private void sendBad(String tag, String msg) {
        out.print(tag + " BAD " + msg + "\r\n");
        out.flush();
    }
}