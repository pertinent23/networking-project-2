import java.util.Map;

public abstract class MailSettings {
    // the smtp server port
    public static final int SMTP_PORT = 25;

    //the imap server port
    public static final int IMAP_PORT = 143;

    //the pop3 server port
    public static final int POP3_PORT = 110;

    //the folder we use to store emails
    public static final String STORAGE_BASE_DIR = "storage";

    //the default file for meta data
    public static final String META_DATA_FILE = ".metadata";

    //the dns config file
    public static final String DNS_CONFIGS_FILE = "/etc/resolv.conf";

    //the time to wait for socket read operation
    public static final int SOCKET_READ_TIMEOUT_MS = 5 * 1000;

    //the time to wait for inactivity when we are using imap
    public static final long IMAP_TIMEOUT_MS = 30 * 60 * 1000;

    //the time to wait for inactivity when we are using pop3
    public static final long POP3_TIMEOUT_MS = 10 * 60 * 1000;

    //the time to wait for inactivity when we are using smtp
    public static final long SMTP_TIMEOUT_MS = 5 * 60 * 1000;

    //the list of users and their passwords
    public static final Map<String, String> USERS = Map.of(
        "dcd", "password",
        "vj", "password"
    );

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
}
