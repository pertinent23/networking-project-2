import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

public class MailStorageManager {
    /**
     * Metadata manager for user folders
     * to store flag and UID
    */
    public static class MetaDataManager {
        private final File meta;

        private int lastUID = 0;
        private String folderUID = UUID.randomUUID().toString() + System.currentTimeMillis();
        private HashMap<Integer, String> metadata = new HashMap<>();

        public MetaDataManager(String username, String foldername) {
            Path path = Paths.get(MailSettings.STORAGE_BASE_DIR, username, foldername, MailSettings.META_DATA_FILE);

            if (!Files.exists(path)) {
                try {
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, "LAST_UID=0\nFOLDER_UID=" + folderUID + "\n");
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    this.meta = path.toFile();
                }
            } else {
                this.meta = path.toFile();
            }

            try {
                Scanner scanner = new Scanner(this.meta);
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if (line.startsWith("LAST_UID=")) {
                        this.lastUID = Integer.parseInt(line.split("=")[1]);
                    } else if (line.startsWith("FOLDER_UID=")) {
                        this.folderUID = line.split("=")[1];
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
                System.err.println("[Failed to read metadata for " + username + "/" + foldername + "]");
            } catch (NumberFormatException e) {
                // this schould not happen
                System.err.println("[Invalid metadata format for " + username + "/" + foldername + "]");
            }
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
                
                for (var entry : metadata.entrySet()) {
                    writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
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
                
                for (var entry : metadata.entrySet()) {
                    writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
                }

                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
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
        public void setFladgs(int uid, String flags) {
            metadata.put(uid, flags);
            try {
                FileWriter writer =  new FileWriter(meta.getPath(), false);
                writer.write("LAST_UID=" + lastUID + "\n");
                writer.write("FOLDER_UID=" + folderUID + "\n");
                
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
     * Authenticate a user based on username, password, and domain.
     *
     * @param user
     * @param password
     * @param domain
     * @return
     */
    public static boolean authenticate(String user, String password, String domain) {
        // Verify if a user exists in the system for the given domain
        if (!user.endsWith("@" + domain)) 
            return false;

        String username = user.split("@")[0];
        return MailSettings.USERS.containsKey(username) && MailSettings.USERS.get(username).equals(password);
    }

    
    /**
     * Store an email for a specific recipient.
     * @param recipient
     * @param content
     * @throws IOException
    */
    public static synchronized void saveEmail(String recipient, String foldername, String content) throws IOException {
        String username = recipient.split("@")[0];
        if (!MailSettings.USERS.containsKey(username)) 
            return; // User not found

        MetaDataManager meta = new MetaDataManager(username, foldername);

        // Structure: storage/user/INBOX/timestamp-uid.eml
        int lastUID = meta.getNextUID();
        Path inbox = Paths.get(MailSettings.STORAGE_BASE_DIR, username, foldername);
        Files.createDirectories(inbox);
        
        String filename = lastUID + ".eml";
        Files.writeString(inbox.resolve(filename), content);
        meta.addFlags(lastUID, "\\Recent"); // Initialize with no flags
    }

    /**
     * Get the directory for a specific user.
     * @param user
     * @return
     * @throws IOException
    */
    public static synchronized File getUserDirectory(String user) throws IOException {
        String username = user.split("@")[0];
        File userDir = Paths.get(MailSettings.STORAGE_BASE_DIR, username).toFile();
        
        if (!userDir.exists()) {
            userDir.mkdirs();
        }

        return userDir;
    }

    /**
     * Create a new folder for a specific user.
     * 
     * @param user
     * @param folderName
     * @return
    */
    public static synchronized boolean createFolder(String user, String folderName) {
        String username = user.split("@")[0];
        Path folderPath = Paths.get(MailSettings.STORAGE_BASE_DIR, username, folderName);
        
        if (Files.exists(folderPath)) {
            return false; 
        }

        // Initialize metadata for the new folder
        new MetaDataManager(username, folderName);

        try {
            Files.createDirectories(folderPath);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Delete a folder for a specific user.
     * @param user
     * @param folderName
     * @return
    */
    public static synchronized boolean deleteFolder(String user, String folderName) {
        String username = user.split("@")[0];
        Path folderPath = Paths.get(MailSettings.STORAGE_BASE_DIR, username, folderName);
        
        if (!Files.exists(folderPath) || !Files.isDirectory(folderPath)) {
            return false; 
        }

        try {
            Files.walk(folderPath)
                .map(Path::toFile)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(File::delete);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static synchronized boolean renameFolder(String user, String oldName, String newName) {
        String username = user.split("@")[0];
        Path oldPath = Paths.get(MailSettings.STORAGE_BASE_DIR, username, oldName);
        Path newPath = Paths.get(MailSettings.STORAGE_BASE_DIR, username, newName);
        
        if (!Files.exists(oldPath) || Files.exists(newPath)) {
            return false; 
        }

        try {
            Files.move(oldPath, newPath);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Check if a folder exists for a specific user.
     * @param user
     * @param folderName
     * @return
    */
    public static synchronized boolean folderExists(String user, String folderName) {
        String username = user.split("@")[0];
        Path folderPath = Paths.get(MailSettings.STORAGE_BASE_DIR, username, folderName);
        return Files.exists(folderPath) && Files.isDirectory(folderPath);
    }

    /**
     * Retrieve all messages for a specific user.
     * @param user
     * @return
     */
    public static synchronized List<File> getMessages(String user, String folderName) {
        String username = user.split("@")[0];
        Path inbox = Paths.get(MailSettings.STORAGE_BASE_DIR, username, folderName);
        if (!Files.exists(inbox)) {
            return new LinkedList<>();
        }

        File[] files = inbox.toFile().listFiles((dir, name) -> name.endsWith(".eml"));
        return files != null ? List.of(files) : new LinkedList<>();
    }

    /**
     * Delete a specific message for a user.
     * @param user
     * @param filename
     * @throws IOException
     */
    public static synchronized void deleteMessage(String user, String folderName, String filename) throws IOException {
        String username = user.split("@")[0];
        Path filePath = Paths.get(MailSettings.STORAGE_BASE_DIR, username, folderName, filename);
        Files.deleteIfExists(filePath);
        new MetaDataManager(username, folderName).updateFlag(Integer.parseInt(username.split("\\.")[0]), "\\Deleted", true);
    }

    /**
     * Delete a message file.
     * @param file
     * @throws IOException
    */
    public static synchronized void deleteMessageFile(File file) throws IOException {
        if (file != null && file.exists()) {
            Files.delete(file.toPath());
        }
    }

    /**
     * Copy a message to a target folder for a user.
     * @param user
     * @param messageFile
     * @param targetFolder
     * @param newName
     * @throws IOException
    */
    public static synchronized void copyMessage(String user, File messageFile, String targetFolder, int uid) throws IOException {
        String username = user.split("@")[0];
        Path targetDir = Paths.get(MailSettings.STORAGE_BASE_DIR, username, targetFolder);
        Files.createDirectories(targetDir);

        Path targetFile = targetDir.resolve(("" + uid).concat(".eml"));
        Files.copy(messageFile.toPath(), targetFile);

        new MetaDataManager(username, targetFolder).addFlags(uid, "\\Seen");
    }

    /**
     * Get flags for a specific message UID.
     * @param user
     * @param folderName
     * @param uid
     * @return
    */
    public static List<String> getFlags(String user, String folderName, int uid) {
        String username = user.split("@")[0];
        MetaDataManager meta = new MetaDataManager(username, folderName);
        return meta.getFlags(uid);
    }


    /**
     * Get the folder UID.
     * @param user
     * @param folderName
     * @return
    */
    public static String getFolderUID(String user, String folderName) {
        String username = user.split("@")[0];
        MetaDataManager meta = new MetaDataManager(username, folderName);
        return meta.getFolderUID();
    }

    /**
     * Get the next UID for a specific folder.
     * @param user
     * @param folderName
     * @return
    */
    public static int getNextUID(String user, String folderName) {
        String username = user.split("@")[0];
        MetaDataManager meta = new MetaDataManager(username, folderName);
        return meta.getNextUID();
    }

    /**
     * Update a specific flag for a message UID.
     * @param user
     * @param folderName
     * @param uid
     * @param flag
     * @param add
    */
    public static void updateFlag(String user, String folderName, int uid, String flag, boolean add) {
        String username = user.split("@")[0];
        MetaDataManager meta = new MetaDataManager(username, folderName);
        meta.updateFlag(uid, flag, add);
    }

    /**
     * 
     * @param user
     * @param folderName
     * @param uid
     * @param flags
    */
    public static void setFlags(String user, String folderName, int uid, String flags) {
        String username = user.split("@")[0];
        MetaDataManager meta = new MetaDataManager(username, folderName);
        meta.setFladgs(uid, flags);
    }
}
