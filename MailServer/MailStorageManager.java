import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

public class MailStorageManager {

    private final String username;

    public MailStorageManager(String user) {
        this.username = user.split("@")[0];
    }

    /**
     * Metadata manager for user folders
     * to store flag and UID information
    */
    public static class MetaDataManager {
        //the meta data file for the folder
        private final File meta;

        //the last UID used
        private int lastUID = 0;

        //equal to true if the folder is subscribed
        private boolean isSubscribed = false;

        //the folder UID
        private String folderUID = UUID.randomUUID().toString() + System.currentTimeMillis();

        //the list of metadata flags for each UID
        private HashMap<Integer, String> metadata = new HashMap<>();

        /**
         * Constructor
         * @param username
         * @param foldername
        */
        public MetaDataManager(String username, String foldername) {
            File path = new File(
                MailSettings.STORAGE_BASE_DIR
                    .concat(File.separator)
                    .concat(username)
                    .concat(File.separator)
                    .concat(foldername),
            MailSettings.META_DATA_FILE);

            if (!path.exists()) {
                try {
                    path.getParentFile().mkdirs();
                    try (FileWriter writer = new FileWriter(path)) {
                        writer.write("LAST_UID=0\nFOLDER_UID=".concat(folderUID).concat("\n"));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    this.meta = path;
                }
            } else {
                this.meta = path;
            }

            try {
                Scanner scanner = new Scanner(this.meta);
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if (line.startsWith("LAST_UID=")) {
                        this.lastUID = Integer.parseInt(line.split("=")[1]);
                    } else if (line.startsWith("FOLDER_UID=")) {
                        this.folderUID = line.split("=")[1];
                    } else if (line.startsWith("SUBSCRIBED")) {
                        this.isSubscribed = true;
                    } else {
                        String[] parts = line.split("=", 2);
                        if (parts.length == 2) {
                            int uid = Integer.parseInt(parts[0]);
                            String flags = parts[1];
                            metadata.put(uid, flags);
                        }
                    }
                }

                scanner.close();
            } catch (IOException e) {
                // this schould not happen
                System.err.println("[MailStorageManager.java: Failed to read metadata for ".concat(username).concat("/").concat(foldername).concat("]"));
            } catch (NumberFormatException e) {
                // this schould not happen
                System.err.println("[MailStorageManager.java: Invalid metadata format for ".concat(username).concat("/").concat(foldername).concat("]"));
            }
        }

        /**
         * Check if the folder is subscribed.
         * @return
        */
        public boolean isSubscribed() {
            return isSubscribed;
        }

        /**
         * Get the next unique identifier (UID) for a new message.
         * @return
        */
        public synchronized int getNextUID() {
            lastUID += 1;
            try {
                FileWriter writer =  new FileWriter(meta.getPath(), false);
                writer.write("LAST_UID=" + lastUID + "\n");
                writer.write("FOLDER_UID=" + folderUID + "\n");

                if (isSubscribed) {
                    writer.write("SUBSCRIBED\n");
                }
                
                for (var entry : metadata.entrySet()) {
                    writer.write(entry.getKey().toString().concat("=").concat(entry.getValue()).concat("\n"));
                }

                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            return lastUID;
        }

        /**
         * Get the folder UID.
         * @return
        */
        public String getFolderUID() {
            return folderUID;
        }

        /**
         * Set flags for a specific UID.
         * @param uid
         * @return
        */
        public synchronized List<String> getFlags(int uid) {
            return List.of(metadata.getOrDefault(uid, "").split("\\|"));
        }

        /**
         * Set flags for a specific UID.
         * @param uid
         * @param flags
        */
        public synchronized void addFlags(int uid, String flags) {
            metadata.put(uid, flags);
            try {
                FileWriter writer =  new FileWriter(meta.getPath(), true);
                writer.write(uid + "=" +  flags + "\n");
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        /**
         * Remove a specific UID from metadata.
         * @param uid
        */
        public synchronized void removeUID(int uid) {
            metadata.remove(uid);
            try {
                FileWriter writer =  new FileWriter(meta.getPath(), false);
                writer.write("LAST_UID=" + lastUID + "\n");
                writer.write("FOLDER_UID=" + folderUID + "\n");
                
                if (isSubscribed) {
                    writer.write("SUBSCRIBED\n");
                }
                
                for (var entry : metadata.entrySet()) {
                    writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
                }

                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * Set subscription status for a folder.
         * @param subscribed
        */
        public synchronized void setSubscribed(boolean subscribed) {
            this.isSubscribed = subscribed;
            try {
                FileWriter writer =  new FileWriter(meta.getPath(), false);
                writer.write("LAST_UID=" + lastUID + "\n");
                writer.write("FOLDER_UID=" + folderUID + "\n");
                
                if (subscribed) {
                    writer.write("SUBSCRIBED\n");
                }

                for (var entry : metadata.entrySet()) {
                    writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
                }

                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
                System.err.println("[MailStorageManager.java: Failed to update subscription status in metadata.]");
            }
        }

        /**
         * Update a specific flag for a UID.
         * @param uid
         * @param flag
         * @param add
        */
        public synchronized void updateFlag(int uid, String flag, boolean add) {
            String flags = metadata.getOrDefault(uid, "");
            List<String> flagList = new LinkedList<>(List.of(flags.split("\\|")));

            if (add) {
                if (!flagList.contains(flag)) {
                    flagList.add(flag);
                }
            } else {
                flagList.remove(flag);
            }

            String updatedFlags = String.join("|", flagList);
            metadata.put(uid, updatedFlags);

            try {
                FileWriter writer =  new FileWriter(meta.getPath(), false);
                writer.write("LAST_UID=" + lastUID + "\n");
                writer.write("FOLDER_UID=" + folderUID + "\n");

                if (isSubscribed) {
                    writer.write("SUBSCRIBED\n");
                }
                
                for (var entry : metadata.entrySet()) {
                    writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
                }

                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * Set flags for a specific UID.
         * @param uid
         * @param flags
        */
        public void setFlags(int uid, String flags) {
            metadata.put(uid, flags);
            try {
                FileWriter writer =  new FileWriter(meta.getPath(), false);
                writer.write("LAST_UID=" + lastUID + "\n");
                writer.write("FOLDER_UID=" + folderUID + "\n");

                if (isSubscribed) {
                    writer.write("SUBSCRIBED\n");
                }
                
                for (var entry : metadata.entrySet()) {
                    writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
                }

                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Store an email for a specific recipient.
     * @param foldername
     * @param content
     * @throws IOException
     */
    public void saveEmail(String foldername, String content) throws IOException {
        MailboxLockManager.lockWrite(username);
        try {
            if (!MailSettings.USERS.containsKey(username))
                return; // User not found

            MetaDataManager meta = new MetaDataManager(username, foldername);

            int lastUID = meta.getNextUID();
            File inboxDir = new File(
                MailSettings.STORAGE_BASE_DIR, username
                    .concat(File.separator)
                    .concat(foldername)
            );

            if (!inboxDir.exists()) {
                inboxDir.mkdirs();
            }

            String filename = lastUID + ".eml";
            try (FileWriter writer = new FileWriter(new File(inboxDir, filename))) {
                writer.write(content);
            }
            meta.addFlags(lastUID, "\\Recent"); // Initialize with no flags
        } finally {
            MailboxLockManager.unlockWrite(username);
        }
    }

    /**
     * Get the directory for a specific user.
     * @return
     * @throws IOException
     */
    public File getUserDirectory() throws IOException {
        MailboxLockManager.lockRead(username);
        try {
            File userDir = new File(MailSettings.STORAGE_BASE_DIR, username);

            if (!userDir.exists()) {
                userDir.mkdirs();
            }

            return userDir;
        } finally {
            MailboxLockManager.unlockRead(username);
        }
    }

    /**
     * Create a new folder for a specific user.
     *
     * @param folderName
     * @return
     */
    public boolean createFolder(String folderName) {
        MailboxLockManager.lockWrite(username);
        try {
            File folder = new File(
                MailSettings.STORAGE_BASE_DIR
                            .concat(File.separator)
                            .concat(username),
            folderName);

            if (folder.exists()) {
                return false;
            }

            // Initialize metadata for the new folder
            new MetaDataManager(username, folderName);

            if (folder.mkdirs() || folder.exists()) {
                return true;
            } else {
                return false;
            }
        } finally {
            MailboxLockManager.unlockWrite(username);
        }
    }

    /**
     * Delete a folder for a specific user.
     * @param folderName
     * @return
     */
    public boolean deleteFolder(String folderName) {
        MailboxLockManager.lockWrite(username);
        try {
            File folder = new File(
                MailSettings.STORAGE_BASE_DIR
                    .concat(File.separator)
                    .concat(username),
            folderName);

            if (!folder.exists() || !folder.isDirectory()) {
                return false;
            }

            return deleteDirectory(folder);
        } finally {
            MailboxLockManager.unlockWrite(username);
        }
    }

    /**
     * Delete a directory recursively.
     * @param directoryToBeDeleted
     * @return
     */
    private boolean deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return directoryToBeDeleted.delete();
    }

    /**
     * Rename a folder for a specific user.
     *
     * @param oldName
     * @param newName
     * @return
     */
    public boolean renameFolder(String oldName, String newName) {
        MailboxLockManager.lockWrite(username);
        try {
            File oldDir = new File(MailSettings.STORAGE_BASE_DIR.concat(File.separator).concat(username), oldName);
            File newDir = new File(MailSettings.STORAGE_BASE_DIR.concat(File.separator).concat(username), newName);

            if (!oldDir.exists() || newDir.exists()) {
                return false;
            }

            return oldDir.renameTo(newDir);
        } finally {
            MailboxLockManager.unlockWrite(username);
        }
    }

    /**
     * Check if a folder exists for a specific user.
     * @param folderName
     * @return
     */
    public boolean folderExists(String folderName) {
        MailboxLockManager.lockRead(username);
        try {
            File folder = new File(
                MailSettings.STORAGE_BASE_DIR
                    .concat(File.separator)
                    .concat(username),
            folderName);
            return folder.exists() && folder.isDirectory();
        } finally {
            MailboxLockManager.unlockRead(username);
        }
    }

    /**
     * Retrieve all messages for a specific user.
     * @param folderName
     * @return
     */
    public List<File> getMessages(String folderName) {
        MailboxLockManager.lockRead(username);
        try {
            File inboxDir = new File(
                MailSettings.STORAGE_BASE_DIR
                    .concat(File.separator)
                    .concat(username),
            folderName);

            if (!inboxDir.exists()) {
                return new LinkedList<>();
            }

            File[] files = inboxDir.listFiles((dir, name) -> name.endsWith(".eml"));
            return files != null ? List.of(files) : new LinkedList<>();
        } finally {
            MailboxLockManager.unlockRead(username);
        }
    }

    /**
     * Retrieve a specific message file for a user.
     * @param folderName
     * @param uid
     * @return
     */
    public File getMessageFile(String folderName, int uid) {
        MailboxLockManager.lockRead(username);
        try {
            File file = new File(
                MailSettings.STORAGE_BASE_DIR
                    .concat(File.separator)
                    .concat(username)
                    .concat(File.separator)
                    .concat(folderName),
            uid + ".eml");

            if (file.exists()) {
                return file;
            }

            return null;
        } finally {
            MailboxLockManager.unlockRead(username);
        }
    }

    /**
     * Get the message count for a specific user.
     * @param folderName
     * @return
     */
    public int getMessageCount(String folderName) {
        MailboxLockManager.lockRead(username);
        try {
            File inboxDir = new File(
                MailSettings.STORAGE_BASE_DIR
                    .concat(File.separator)
                    .concat(username),
            folderName);

            if (!inboxDir.exists()) {
                return 0;
            }

            File[] files = inboxDir.listFiles((dir, name) -> name.endsWith(".eml"));
            return files != null ? files.length : 0;
        } finally {
            MailboxLockManager.unlockRead(username);
        }
    }

    /**
     * Delete a specific message for a user.
     * @param folderName
     * @param filename
     * @throws IOException
     */
    public void deleteMessage(String folderName, String filename) throws IOException {
        MailboxLockManager.lockWrite(username);
        try {
            File file = new File(
                MailSettings.STORAGE_BASE_DIR
                    .concat(File.separator)
                    .concat(username)
                    .concat(File.separator)
                    .concat(folderName),
            filename);

            if (file.exists()) {
                file.delete();
            }

            new MetaDataManager(username, folderName).updateFlag(Integer.parseInt(filename.split("\\.")[0]), "\\Deleted", true);
        } finally {
            MailboxLockManager.unlockWrite(username);
        }
    }

    /**
     * Delete a message file.
     * @param file
     * @throws IOException
     */
    public void deleteMessageFile(File file) throws IOException {
        MailboxLockManager.lockWrite(username);
        try {
            if (file != null && file.exists()) {
                file.delete();
            }
        } finally {
            MailboxLockManager.unlockWrite(username);
        }
    }

    /**
     * Copy a message to a target folder for a user.
     * @param messageFile
     * @param targetFolder
     * @param uid
     * @throws IOException
     */
    public void copyMessage(File messageFile, String targetFolder, int uid) throws IOException {
        MailboxLockManager.lockWrite(username);
        try {
            File targetDir = new File(
                MailSettings.STORAGE_BASE_DIR
                            .concat(File.separator)
                            .concat(username),
                targetFolder);

            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }

            File targetFile = new File(targetDir, ("" + uid).concat(".eml"));

            try (java.io.InputStream in = new java.io.FileInputStream(messageFile);
                 java.io.OutputStream out = new java.io.FileOutputStream(targetFile)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
            }

            new MetaDataManager(username, targetFolder).addFlags(uid, "\\Seen");
        } finally {
            MailboxLockManager.unlockWrite(username);
        }
    }

    /**
     * Get flags for a specific message UID.
     * @param folderName
     * @param uid
     * @return
     */
    public List<String> getFlags(String folderName, int uid) {
        MailboxLockManager.lockRead(username);
        try {
            MetaDataManager meta = new MetaDataManager(username, folderName);
            return meta.getFlags(uid);
        } finally {
            MailboxLockManager.unlockRead(username);
        }
    }

    /**
     * Get the folder UID.
     * @param folderName
     * @return
     */
    public String getFolderUID(String folderName) {
        MailboxLockManager.lockRead(username);
        try {
            MetaDataManager meta = new MetaDataManager(username, folderName);
            return meta.getFolderUID();
        } finally {
            MailboxLockManager.unlockRead(username);
        }
    }

    /**
     * Get the next UID for a specific folder.
     * @param folderName
     * @return
     */
    public int getNextUID(String folderName) {
        MailboxLockManager.lockWrite(username);
        try {
            MetaDataManager meta = new MetaDataManager(username, folderName);
            return meta.getNextUID();
        } finally {
            MailboxLockManager.unlockWrite(username);
        }
    }

    /**
     * Update a specific flag for a message UID.
     * @param folderName
     * @param uid
     * @param flag
     * @param add
     */
    public void updateFlag(String folderName, int uid, String flag, boolean add) {
        MailboxLockManager.lockWrite(username);
        try {
            MetaDataManager meta = new MetaDataManager(username, folderName);
            meta.updateFlag(uid, flag, add);
        } finally {
            MailboxLockManager.unlockWrite(username);
        }
    }

    /**
     *
     * @param folderName
     * @param uid
     * @param flags
     */
    public void setFlags(String folderName, int uid, String flags) {
        MailboxLockManager.lockWrite(username);
        try {
            MetaDataManager meta = new MetaDataManager(username, folderName);
            meta.setFlags(uid, flags);
        } finally {
            MailboxLockManager.unlockWrite(username);
        }
    }

    /**
     * Set subscription status for a folder.
     * @param folderName
     * @param subscribed
     */
    public void setSubscribed(String folderName, boolean subscribed) {
        MailboxLockManager.lockWrite(username);
        try {
            MetaDataManager meta = new MetaDataManager(username, folderName);
            meta.setSubscribed(subscribed);
        } finally {
            MailboxLockManager.unlockWrite(username);
        }
    }

    /**
     * Check if a folder is subscribed.
     * @param folderName
     * @return
     */
    public boolean isSubscribed(String folderName) {
        MailboxLockManager.lockRead(username);
        try {
            MetaDataManager meta = new MetaDataManager(username, folderName);
            return meta.isSubscribed();
        } finally {
            MailboxLockManager.unlockRead(username);
        }
    }
}
