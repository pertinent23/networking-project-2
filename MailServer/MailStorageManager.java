import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

public class MailStorageManager {
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

        // Structure: storage/user/INBOX/timestamp-uid.eml
        Path inbox = Paths.get(MailSettings.STORAGE_BASE_DIR, username, foldername);
        Files.createDirectories(inbox);
        
        String filename = System.currentTimeMillis() + "-" + UUID.randomUUID().toString() + ".eml";
        Files.writeString(inbox.resolve(filename), content);
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
     * @throws IOException
    */
    public static synchronized void copyMessage(String user, File messageFile, String targetFolder) throws IOException {
        String username = user.split("@")[0];
        Path targetDir = Paths.get(MailSettings.STORAGE_BASE_DIR, username, targetFolder);
        Files.createDirectories(targetDir);

        Path targetFile = targetDir.resolve(messageFile.getName());
        Files.copy(messageFile.toPath(), targetFile);
    }
}
