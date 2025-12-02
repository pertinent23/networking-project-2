import java.util.Map;

public abstract class MailSettings {
    public static final int SMTP_PORT = 25;
    public static final int IMAP_PORT = 143;
    public static final int POP3_PORT = 110;

    public static final String STORAGE_BASE_DIR = "storage";
    public static final String META_DATA_FILE = ".metadata";

    public static final String DNS_CONFIGS_FILE = "/etc/resolv.conf";

    public static final Map<String, String> USERS = Map.of(
        "dcd", "password",
        "vj", "password"
    );
}
