
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MailboxLockManager {

    private static final ConcurrentHashMap<String, ReadWriteLock> userLocks = new ConcurrentHashMap<>();

    /**
     * Acquires a read lock for a given user.
     * This allows multiple threads to read from the same user's mailbox concurrently.
     *
     * @param username The user to lock.
     */
    public static void lockRead(String username) {
        getLock(username).readLock().lock();
    }

    /**
     * Releases a read lock for a given user.
     *
     * @param username The user to unlock.
     */
    public static void unlockRead(String username) {
        getLock(username).readLock().unlock();
    }

    /**
     * Acquires a write lock for a given user.
     * This is an exclusive lock; no other threads can read or write.
     *
     * @param username The user to lock.
     */
    public static void lockWrite(String username) {
        getLock(username).writeLock().lock();
    }

    /**
     * Releases a write lock for a given user.
     *
     * @param username The user to unlock.
     */
    public static void unlockWrite(String username) {
        getLock(username).writeLock().unlock();
    }

    /**
     * Retrieves the ReadWriteLock for a user, creating it if it doesn't exist.
     *
     * @param username The user.
     * @return The ReadWriteLock for that user.
     */
    private static ReadWriteLock getLock(String username) {
        // computeIfAbsent ensures that the lock is created and put in the map atomically
        return userLocks.computeIfAbsent(username, k -> new ReentrantReadWriteLock());
    }
}
