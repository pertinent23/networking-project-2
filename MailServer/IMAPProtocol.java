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
public class IMAPProtocol extends MailProtocol {
    private String currentUser = null;
    private String currentMailbox = null;
    private int msn = 1;

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
            System.out.println("[IMAPProtocol.java: C: " + line + "]"); 

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
                        handleList(tag, args, cmd.equals("LSUB"));
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
                        handleSubscribe(tag, args, cmd.equals("SUBSCRIBE"));
                        break;

                    case "UID":
                        handleUidCommand(tag, args);
                        break;

                    case "EXPUNGE":
                    case "CLOSE":
                        handleExpunge(tag);
                        sendOk(tag, cmd + " completed");
                        
                        if (cmd.equals("CLOSE")) {
                            currentMailbox = null;
                            close();
                        }

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
     * Respects RFC 3501: LIST reference mailbox-name
     * @param tag
     * @param args
    */
    private void handleList(String tag, String args, boolean isLsub) {
        if (currentUser == null) { 
            sendNo(tag, "Login first"); 
            return; 
        }

        // Parse arguments (Reference and Pattern) handling quotes
        String[] parts = parseListArgs(args);
        // reference is usually empty or implies context, simplified here
        String pattern = parts[1];

        // Special case RFC 3501: LIST "" "" returns the hierarchy delimiter
        if (pattern.isEmpty()) {
            out.print("* LIST (\\Noselect) \"/\" \"\"\r\n");
            sendOk(tag, "LIST completed");
            return;
        }

        try {
            File userDir = MailStorageManager.getUserDirectory(currentUser);
            if (userDir != null && userDir.exists()) {
                
                // Map to store folder name -> File object (for attribute checking)
                // We use a TreeMap to ensure alphabetical order in response
                Map<String, File> allFolders = new TreeMap<>();
                
                // INBOX is always present and virtual (case-insensitive)
                allFolders.put("INBOX", null);

                // Recursively find all folders
                listFoldersRecursively(userDir, "", allFolders);

                // Filter and send response using forEach
                allFolders.forEach((name, file) -> {
                    if (isLsub && !MailStorageManager.isSubscribed(currentUser, name)) {
                        return;
                    }

                    if (matchesListPattern(name, pattern)) {
                        List<String> attributes = new ArrayList<>();
                        
                        if (name.equalsIgnoreCase("INBOX")) {
                            /**
                             * INBOX cannot have subfolders in this simple implementation
                             * but we can update this logic if needed.
                            */
                            attributes.add("\\HasNoChildren"); 
                        } else {
                            // Check if this folder has sub-directories physically
                            boolean hasChildren = file != null && hasSubFolders(file);
                            if (hasChildren) {
                                attributes.add("\\HasChildren");
                            } else {
                                attributes.add("\\HasNoChildren");
                            }
                        }

                        // Escape quotes in folder name for safety
                        String safeName = name.replace("\"", "\\\"");
                        String attrStr = String.join(" ", attributes);
                        
                        out.print("* LIST (" + attrStr + ") \"/\" \"" + safeName + "\"\r\n");
                    }
                });
            }
            sendOk(tag, "LIST completed");

        } catch (Exception e) {
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

    private void handleSubscribe(String tag, String args, boolean subscribe) {
        // For simplicity, we accept the command but do not store subscriptions
        if (currentUser == null) { 
            sendNo(tag, "Login first"); 
            return; 
        }

        if (args == null || args.isEmpty()) {
            sendBad(tag, "Missing mailbox name");
            return;
        }

        String folder = args.replace("\"", "").trim();

        if (!MailStorageManager.folderExists(currentUser, folder)) {
            sendNo(tag, "Mailbox does not exist");
            return;
        }

        MailStorageManager.setSubscribed(currentUser, folder, subscribe);

        sendOk(tag, (subscribe ? "" : "UN").concat("SUBSCRIBE completed"));
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

        if (currentUser == null) { 
            sendNo(tag, "Login first"); 
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
     *
     * @param tag The command tag
     * @param params Command parameters (e.g., "1:* (FLAGS BODY.PEEK[HEADER] UID ENVELOPE)")
     */
    private void handleUidFetch(String tag, String params) throws IOException {
        // Split into range and data items (max 2 parts)
        String[] paramParts = params.split("\\s+", 2);
        String range = paramParts[0];
        String dataItemsRaw = (paramParts.length > 1) ? paramParts[1] : "";

        // Clean up parentheses to simplify parsing logic for macros
        String dataItemsUpper = dataItemsRaw.toUpperCase().replace("(", " ").replace(")", " ");

        // --- Handle IMAP Macros (RFC 3501) ---
        boolean fetchAll = dataItemsUpper.contains("ALL");
        boolean fetchFast = dataItemsUpper.contains("FAST");
        boolean fetchFull = dataItemsUpper.contains("FULL");

        // --- Determine Atomic Data Items to Fetch ---
        boolean fetchFlags = fetchAll || fetchFast || fetchFull || dataItemsUpper.contains("FLAGS");
        boolean fetchInternalDate = fetchAll || fetchFast || fetchFull || dataItemsUpper.contains("INTERNALDATE");
        boolean fetchSize = fetchAll || fetchFull || dataItemsUpper.contains("RFC822.SIZE");
        boolean fetchEnvelope = fetchAll || fetchFull || dataItemsUpper.contains("ENVELOPE");
        boolean fetchBodyStructure = dataItemsUpper.contains("BODYSTRUCTURE");

        // Regex to capture complex body requests: BODY[], BODY[HEADER], BODY.PEEK[TEXT], etc.
        // Group 1: .PEEK (optional)
        // Group 2: Section inside brackets (e.g., HEADER, TEXT, or empty for full body)
        Pattern bodyPattern = Pattern.compile("BODY(\\.PEEK)?\\[(.*?)\\]", Pattern.CASE_INSENSITIVE);

        // Parse the requested UID set
        Set<Integer> requestedUids = parseUidRange(range);

        resetMSN();

        currentMessages.forEach(msg -> {
            int uid = getUidFromFile(msg);

            if (!requestedUids.contains(uid)) {
                return;
            }

            if (!msg.exists()) {
                return;
            }

            try {
                StringBuilder sb = new StringBuilder();

                sb.append("* ")
                .append(getMSN())
                .append(" FETCH (");

                List<String> responseParts = new ArrayList<>();

                // add UID part
                responseParts.add("UID " + uid);

                if (fetchFlags) {
                    List<String> flags = MailStorageManager.getFlags(currentUser, currentMailbox, uid);
                    responseParts.add("FLAGS (" + String.join(" ", flags) + ")");
                }

                if (fetchInternalDate) {
                    responseParts.add("INTERNALDATE \"" + Calendar.getInstance() + "\"");
                }

                if (fetchSize) {
                    responseParts.add("RFC822.SIZE " + msg.length());
                }

                if (fetchEnvelope) {
                    responseParts.add("ENVELOPE " + getEnvelope(msg));
                }

                if (fetchBodyStructure) {
                    responseParts.add("BODYSTRUCTURE " + getBodyStructure(msg));
                }

                // --- Handle Specific BODY[...] Sections ---
                Matcher matcher = bodyPattern.matcher(dataItemsRaw);
                while (matcher.find()) {
                    boolean isPeek = (matcher.group(1) != null); // Checks if .PEEK is present
                    String section = matcher.group(2).toUpperCase(); // content inside brackets

                    // Logic to update \Seen flag (only if NOT PEEK)
                    if (!isPeek) {
                        List<String> currentFlags = MailStorageManager.getFlags(currentUser, currentMailbox, uid);
                        if (!currentFlags.contains("\\Seen")) {
                            MailStorageManager.updateFlag(currentUser, currentMailbox, uid, "\\Seen", true);
                        }
                    }

                    // Case: BODY[] -> Full message content
                    if (section.equals("")) {
                        responseParts.add("BODY[] {" + msg.length() + "}");
                        
                        // Send existing parts + literal header
                        sb.append(String.join(" ", responseParts));
                        out.print(sb.toString().concat("\r\n"));
                        out.flush();

                        // Stream raw file content to output
                        Files.copy(msg.toPath(), rawOut);
                        rawOut.flush();

                        // Reset buffers for potential subsequent parts (closing parenthesis)
                        sb = new StringBuilder();
                        responseParts.clear();
                    } 
                    else {
                        /**
                         * We extract the requested section and include it in the response
                         * for BODY[HEADER] or BODY[TEXT]
                        */
                        String content = extractBodySection(msg, section);
                        responseParts.add("BODY[" + section + "] {" + content.getBytes().length + "}\r\n" + content);
                    }
                }

                // Append any remaining response parts
                if (!responseParts.isEmpty()) {
                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '(') {
                        sb.append(" ");
                    }
                    sb.append(String.join(" ", responseParts));
                }

                sb.append(")\r\n");
                out.print(sb.toString());
                out.flush();

            } catch (IOException e) {
                // Inside forEach, we must handle checked exceptions. 
                // We log it to server console but avoid crashing the whole loop.
                System.err.println("[IMAPProtocol.java: Error fetching message " + uid + ": " + e.getMessage() + "]");
            }
        });

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

            resetMSN();

            currentMessages.forEach((filename) -> {
                int uid = getUidFromFile(filename);
                if(targetUids.contains(uid)) {
                    List<String> current = new ArrayList<>(MailStorageManager.getFlags(currentUser, currentMailbox, uid));

                    if (isSet) {
                        current.clear();
                    }

                    newFlags.forEach((flag) -> {
                        if (isAdd) {
                            if (!current.contains(flag)) 
                                current.add(flag);
                        } else if (isRemove) {
                            current.remove(flag);
                        } else if (isSet) {
                            /**
                             * We replace the entire flag set with the new flags
                             * 
                            */
                            if (!current.contains(flag)) 
                                current.add(flag); 
                        }
                    });

                    MailStorageManager.setFlags(currentUser, currentMailbox, uid, String.join("|", current));
                
                    // if the client did not request silent mode, send untagged response
                    if (!params.toUpperCase().contains("SILENT")) {
                        out.print("* " + getMSN() + " FETCH (UID " + uid + " FLAGS (" + String.join(" ", current) + "))\r\n");
                    }
                }
            });

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
        String[] parts = params.split("\\s+", 2);
        String range = parts[0];
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

        Set<Integer> targetUids = parseUidRange(range);

        
        currentMessages.forEach((filename) -> {
            int uid = getUidFromFile(filename);
            if(targetUids.contains(uid)) {
                File file = MailStorageManager.getMessageFile(currentUser, currentMailbox, uid);
                if (file != null && file.exists()) {
                    try {
                        int nextUid = MailStorageManager.getNextUID(currentUser, destMailbox);
                        MailStorageManager.copyMessage(currentUser, file, destMailbox, nextUid);
                    } catch (IOException e) {
                        e.printStackTrace();
                        System.err.println("[IMAPProtocol.java: failed to copy message UID " + uid + " to mailbox " + destMailbox + "]");
                    }
                } else {
                    System.err.println("[IMAPProtocol.java: message file not found for UID " + uid + "]");
                    sendNo(tag, "Message file not found for UID " + uid);
                }
            } else {
                sendNo(tag, "Message file not found for UID " + uid);
            }
        });

        // If copying into the currently selected mailbox, refresh currentMessages
        if (destMailbox.equalsIgnoreCase(currentMailbox)) {
            currentMessages = new ArrayList<>(MailStorageManager.getMessages(currentUser, currentMailbox));
            currentMessages.sort(Comparator.comparingInt(this::getUidFromFile));
        }

        // send tagged OK with COPYUID response as many servers do
        out.print(tag + " OK [COPYUID " + targetUids.size() + " " + currentMailbox.toString() + " " + destMailbox.toString() + "] COPY completed\r\n");
        out.flush();
    }

    /**
     * Handle EXPUNGE command
     * @param tag
    */
    private void handleExpunge(String tag) {
        if (currentMailbox == null) {
            sendNo(tag, "You must select a mailbox first");
            return;
        }
        
        Iterator<File> it = currentMessages.iterator();

        resetMSN();

        while (it.hasNext()) {
            File file = it.next();
            int uid = getUidFromFile(file);
            List<String> flags = MailStorageManager.getFlags(currentUser, currentMailbox, uid);
            
            if (flags.contains("\\Deleted")) {
                if (file.exists()) {
                    file.delete();
                }

                it.remove();
                out.print("* " + getMSN() + " EXPUNGE\r\n");
            }
        }
        
        out.flush();
        sendOk(tag, "EXPUNGE completed");
    }

    /**
     * Check for new messages in the selected mailbox
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
                if (end < start && parts[1].equals("*")) {
                    end = Integer.MAX_VALUE;
                }

                for (File f : currentMessages) {
                    int uid = getUidFromFile(f);
                    if (uid >= start && uid <= end) uids.add(uid);
                }
            } else if (range.contains(",")) {
                for (String s : range.split(",")) {
                    try { 
                        uids.add(Integer.parseInt(s)); 
                    } catch (Exception ignored) {
                        // ignore invalid numbers
                    }
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
     * Parse headers to build the IMAP ENVELOPE structure.
     * Returns a string like: (date subject from sender reply-to to cc bcc in-reply-to message-id)
     * @param f
     * @return
    */
    private String getEnvelope(File f) {
        Map<String, String> headers = parseHeaders(f);

        StringBuilder env = new StringBuilder("(");
        env.append(quote(headers.getOrDefault("Date", "NIL"))).append(" ");
        env.append(quote(headers.getOrDefault("Subject", "NIL"))).append(" ");

        // Addresses (From, Sender, Reply-To, To, Cc, Bcc)
        // Note: Sender is usually same as From if not specified
        env.append(parseAddress(headers.get("From"))).append(" ");
        env.append(parseAddress(headers.get("From"))).append(" "); 
        env.append(parseAddress(headers.get("Reply-To"))).append(" ");
        env.append(parseAddress(headers.get("To"))).append(" ");
        env.append(parseAddress(headers.get("Cc"))).append(" ");
        env.append(parseAddress(headers.get("Bcc"))).append(" ");

        env.append(quote(headers.getOrDefault("In-Reply-To", "NIL"))).append(" ");
        env.append(quote(headers.getOrDefault("Message-ID", "NIL")));
        env.append(")");

        return env.toString();
    }

    /**
     * Parse headers to build basic BODYSTRUCTURE.
     * Detects Content-Type and Charset dynamically.
     * @param f
     * @return
    */
    private String getBodyStructure(File f) {
        Map<String, String> headers = parseHeaders(f);
        String contentType = headers.getOrDefault("Content-Type", "text/plain");
        
        // Split "text/plain; charset=utf-8"
        String[] ctParts = contentType.split(";");
        String fullType = ctParts[0].trim();
        
        String typeMain = "TEXT";
        String typeSub = "PLAIN";
        
        if (fullType.contains("/")) {
            String[] split = fullType.split("/");
            typeMain = split[0].replace("\"", "").trim().toUpperCase();
            typeSub = (split.length > 1) ? split[1].replace("\"", "").trim().toUpperCase() : "PLAIN";
        }

        // Extract charset parameter
        String charset = "NIL";
        if (contentType.toLowerCase().contains("charset=")) {
            String[] paramSplit = contentType.split("charset=");
            if (paramSplit.length > 1) {
                String charsetVal = paramSplit[1].split(";")[0].replace("\"", "").trim();
                charset = "(\"CHARSET\" \"" + charsetVal + "\")";
            }
        }

        // Format: (type subtype (params) id description encoding size lines)
        return String.format("(\"%s\" \"%s\" %s NIL NIL \"7BIT\" %d %d)", typeMain, typeSub, charset, f.length(), 10); 
    }

    /**
     * Extract specific body section (HEADER, TEXT) from email file
     * if section is HEADER, returns headers
     * if section is TEXT, returns body
     * @param f
     * @param section
     * @return
     * @throws IOException
    */
    private String extractBodySection(File f, String section) throws IOException {
        StringBuilder headerSb = new StringBuilder();
        StringBuilder bodySb = new StringBuilder();
        boolean inHeader = true;

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (inHeader && line.isEmpty()) {
                    inHeader = false;
                    continue; // The empty line separates header and body
                }
                if (inHeader) {
                    headerSb.append(line).append("\r\n");
                } else {
                    bodySb.append(line).append("\r\n");
                }
            }
        }

        if (section.contains("HEADER")) return headerSb.toString();
        if (section.contains("TEXT")) return bodySb.toString();
        
        // Fallback for unknown sections
        return "";
    }

    /**
     * Parse email headers from file
     * @param f
     * @return
    */
    private Map<String, String> parseHeaders(File f) {
        Map<String, String> headers = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null && !line.isEmpty()) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    // Headers are case-insensitive, but we store as is. 
                    // Real implementation should handle multi-line headers.
                    headers.put(parts[0].trim(), parts[1].trim());
                }
            }
        } catch (IOException e) {
            System.err.println("[IMAPProtocol.java: Error parsing headers: " + e.getMessage() + "]");
        }
        return headers;
    }

    /**
     * Helper to format email address for IMAP ENVELOPE.
     * Returns ((name route mailbox host)) or NIL.
     */
    private String parseAddress(String raw) {
        if (raw == null || raw.isEmpty()) return "NIL";
        
        String name = "NIL";
        String email = raw;

        // Parse format: "Name <user@domain>"
        if (raw.contains("<") && raw.contains(">")) {
            name = "\"" + raw.substring(0, raw.indexOf("<")).trim().replace("\"", "") + "\"";
            email = raw.substring(raw.indexOf("<") + 1, raw.indexOf(">"));
        }
        
        String[] parts = email.split("@");
        String user = (parts.length > 0) ? "\"" + parts[0].trim() + "\"" : "NIL";
        String domain = (parts.length > 1) ? "\"" + parts[1].trim() + "\"" : "NIL";
        
        return "((" + name + " NIL " + user + " " + domain + "))";
    }

    /**
     * Helper to quote strings for IMAP response, returning NIL for nulls.
     * @param s
     * @return
    */
    private String quote(String s) {
        if (s == null || s.equals("NIL")) return "NIL";
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }

    /**
     * Helper to parse LIST arguments which might be quoted.
     * Handles: "" "*", "REF" "PAT", etc.
     * @param args
     * @return array of [reference, pattern]
     */
    private String[] parseListArgs(String args) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        
        for (char c : args.toCharArray()) {
            if (c == '"') {
                inQuote = !inQuote; // Toggle state
                continue;
            }
            
            // Split on space only if not inside quotes
            if (c == ' ' && !inQuote) {
                if (current.length() > 0 || result.isEmpty()) { 
                    result.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());

        // Pad with empty strings if args are missing
        while (result.size() < 2) 
            result.add("");
        
        return result.toArray(new String[0]);
    }

    /**
     * Checks if a folder name matches the IMAP pattern (* and %)
     * @param name
     * @param pattern
     * @return true if matches, false otherwise
    */
    private boolean matchesListPattern(String name, String pattern) {
        if (pattern.equals("*") || pattern.equals("%")) return true;
        
        // Exact match (INBOX is case-insensitive)
        if (name.equalsIgnoreCase("INBOX") && pattern.equalsIgnoreCase("INBOX")) {
            return true;
        }

        if (name.equals(pattern)) {
            return true;
        }
        
        // Convert IMAP wildcards to Regex
        // * -> .* (match anything)
        // % -> [^/]* (match anything except hierarchy separator)
        String regex = "^" + pattern.replace("*", ".*").replace("%", "[^/]*") + "$";
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(name).matches();
    }

    /**
     * Helper to recursively find folders.
     * @param dir Current directory to scan
     * @param prefix Relative path prefix (e.g. "Archive/")
     * @param result Map to collect results
     * @return 
     */
    private void listFoldersRecursively(File dir, String prefix, Map<String, File> result) {
        File[] files = dir.listFiles(File::isDirectory);
        if (files == null) return;

        for (File f : files) {
            if (f.getName().equalsIgnoreCase("INBOX")) 
                continue;

            String imapName = prefix + f.getName();
            result.put(imapName, f);

            listFoldersRecursively(f, imapName + "/", result);
        }
    }

    /**
     * Helper to check if a directory has sub-directories.
     * Used to determine \HasChildren vs \HasNoChildren.
     * @param dir
     * @return true if has sub-folders, false otherwise
    */
    private boolean hasSubFolders(File dir) {
        File[] subs = dir.listFiles(File::isDirectory);
        return subs != null && subs.length > 0;
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

    /**
     * 
     * @return
    */
    private int getMSN() {
        return msn++;
    }

    /**
     * Reset MSN counter
    */
    private void resetMSN() {
        msn = 1;
    }
}