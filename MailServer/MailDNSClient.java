import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * MailDNSClient
 * A raw UDP DNS client specifically designed to resolve MX (Mail Exchange) records.
 * * Compliant with RFC 1035 (Domain Names - Implementation and Specification).
 */
public class MailDNSClient {
    private static final int DNS_PORT = 53;
    private static final int TYPE_MX = 15;
    private static final int CLASS_IN = 1;
    private static final int MAX_PACKET_SIZE = 512; // Standard UDP DNS packet size limit

    /**
     * Resolves the MX record for a domain using raw UDP.
     * @param domain The domain to resolve (e.g., "uliege.be")
     * @return The mail server hostname with the highest priority (lowest preference number), or null if failed.
     */
    public static String resolveMX(String domain) {
        DatagramSocket socket = null;
        try {
            // 1. Determine which DNS server to use (from OS configuration or fallback)
            String dnsServer = getSystemDnsServer();
            InetAddress dnsAddress = InetAddress.getByName(dnsServer);

            socket = new DatagramSocket();
            socket.setSoTimeout(5000); // 5-second timeout to prevent hanging

            // 2. Build the DNS Query Packet
            // We generate a random Transaction ID to match the response later
            int transactionID = new Random().nextInt(65535/2);
            byte[] requestData = buildQuery(domain, transactionID);
            
            DatagramPacket sendPacket = new DatagramPacket(requestData, requestData.length, dnsAddress, DNS_PORT);
            
            // 3. Send Request
            socket.send(sendPacket);
            
            // 4. Receive Response
            byte[] buffer = new byte[MAX_PACKET_SIZE];
            DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(receivePacket);

            // 5. Parse Response to extract MX record
            return parseResponse(buffer, receivePacket.getLength(), transactionID);

        } catch (Exception e) {
            System.err.println("[MailDNSClient] Resolution Error: " + e.getMessage());
            return null;
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    /**
     * Reads /etc/resolv.conf to find the system's DNS server.
     * Parses lines like "nameserver 1.1.1.1".
     * Falls back to Google DNS (8.8.8.8) if file is missing or unreadable.
     */
    private static String getSystemDnsServer() {
        File resolvConf = new File("/etc/resolv.conf");
        if (!resolvConf.exists()) return "8.8.8.8";

        try (BufferedReader br = new BufferedReader(new FileReader(resolvConf))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                // Skip comments (#) and empty lines
                if (line.startsWith("#") || line.isEmpty()) continue;
                
                if (line.startsWith("nameserver")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return parts[1]; // Return the first IP found
                    }
                }
            }
        } catch (IOException e) {
            // Ignore error and return fallback
        }
        return "8.8.8.8";
    }

    /**
     * Constructs a DNS Query Packet.
     * * PACKET STRUCTURE:
     * +---------------------+
     * |        Header       | 12 bytes
     * +---------------------+
     * |       Question      | Variable length
     * +---------------------+
     */
    private static byte[] buildQuery(String domain, int transactionID) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        /* * --- DNS HEADER (12 Bytes) ---
         * * 0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |                      ID                       |  -> Transaction ID
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |QR|   Opcode  |AA|TC|RD|RA|   Z    |   RCODE   |  -> Flags
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |                    QDCOUNT                    |  -> # of Questions
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |                    ANCOUNT                    |  -> # of Answers
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |                    NSCOUNT                    |  -> # of Authority Records
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |                    ARCOUNT                    |  -> # of Additional Records
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         */
        
        dos.writeShort(transactionID); 
        dos.writeShort(0x0100); // Flags: QR=0 (Query), RD=1 (Recursion Desired)
        dos.writeShort(0x0001); // QDCOUNT: 1 Question
        dos.writeShort(0x0000); // ANCOUNT: 0
        dos.writeShort(0x0000); // NSCOUNT: 0
        dos.writeShort(0x0000); // ARCOUNT: 0

        /*
         * --- QUESTION SECTION ---
         * Format: [Label Length][Label Bytes]...[0x00][Type][Class]
         * Example: "google.com" -> [6]google[3]com[0]
         */
        String[] labels = domain.split("\\.");

        for (String label : labels) {
            byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
            dos.writeByte(bytes.length);
            dos.write(bytes);
        }

        dos.writeByte(0x00); // Terminating Zero Byte

        dos.writeShort(TYPE_MX);  // QTYPE: MX (15)
        dos.writeShort(CLASS_IN); // QCLASS: Internet (1)

        return baos.toByteArray();
    }

    /**
     * Parses the DNS Response to find the best MX record.
     * * RESPONSE STRUCTURE:
     * 1. Header (12 bytes) - Contains the count of answers.
     * 2. Question Section - Returns what we asked.
     * 3. Answer Section - Contains the Resource Records (RRs).
     */
    private static String parseResponse(byte[] data, int length, int transactionID) throws IOException {
        // We use a raw index pointer to traverse the byte array manually.
        // This is safer for handling DNS pointers (compression).
        int idx = 0;

        // --- 1. PARSE HEADER ---
        int resID = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
        int flags = ((data[idx+2] & 0xFF) << 8) | (data[idx+3] & 0xFF);
        int qdCount = ((data[idx+4] & 0xFF) << 8) | (data[idx+5] & 0xFF); // Questions Count
        int anCount = ((data[idx+6] & 0xFF) << 8) | (data[idx+7] & 0xFF); // Answers Count
        
        idx += 12; // Move past header

        // Validate ID
        if (resID != transactionID) {
            throw new IOException("Transaction ID mismatch");
        }

        // --- 2. SKIP QUESTION SECTION ---
        // The response echoes the question. We must skip it to get to the answers.
        // Format: [Name][Type(2)][Class(2)]
        for (int i = 0; i < qdCount; i++) {
            idx = skipName(data, idx); // Skip variable length name
            idx += 4; // Skip QType (2) and QClass (2)
        }

        // --- 3. PARSE ANSWERS SECTION ---
        /*
         * RESOURCE RECORD (RR) FORMAT:
         * +---------------------+
         * |        NAME         | Variable (Labels or Pointer)
         * +---------------------+
         * |        TYPE         | 2 bytes
         * +---------------------+
         * |        CLASS        | 2 bytes
         * +---------------------+
         * |         TTL         | 4 bytes
         * +---------------------+
         * |      RDLENGTH       | 2 bytes (Length of RDATA)
         * +---------------------+
         * |        RDATA        | Variable (MX Data for us)
         * +---------------------+
         */

        String bestMx = null;
        int bestPreference = Integer.MAX_VALUE;

        for (int i = 0; i < anCount; i++) {
            // Check if we are out of bounds
            if (idx >= length) break;

            // 1. Skip Name of the record (usually a pointer to the QName)
            idx = skipName(data, idx);

            // 2. Read Meta Data
            int type = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
            // int clazz = ((data[idx+2] & 0xFF) << 8) | (data[idx+3] & 0xFF); // Unused
            // long ttl = ... // Unused
            
            // Get Data Length
            int rdLength = ((data[idx+8] & 0xFF) << 8) | (data[idx+9] & 0xFF);
            
            // Move index to start of RDATA (Header is 10 bytes: Type(2)+Class(2)+TTL(4)+Len(2))
            idx += 10; 

            if (type == TYPE_MX) {
                /*
                 * MX RDATA FORMAT:
                 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
                 * |                  PREFERENCE                   | 2 bytes
                 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
                 * |                   EXCHANGE                    | Variable (Domain Name)
                 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
                 */
                int preference = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
                
                // Parse the Mail Exchange Server Name (starts after preference)
                String mxHost = parseName(data, idx + 2);

                // Lower preference number is higher priority
                if (preference < bestPreference) {
                    bestPreference = preference;
                    bestMx = mxHost;
                }
            }

            // Move to the next record
            // If we processed MX, idx is inside RDATA. We must jump based on rdLength relative to record start.
            // Actually, simpler logic: 'idx' was incremented by 10. The RDATA ends at (idx + rdLength - 10)? 
            // NO. The safest way is to track where RDATA started.
            // Let's reset logic:
            // The RDATA started at 'idx'.
            // For the loop to continue correctly, we must ensure 'idx' points to the start of the NEXT record.
            // The logic above advanced 'idx' by 10 to read preference. 
            // So we need to advance by (rdLength) relative to the start of RDATA.
            // Correct logic: The parsing of 'mxHost' moves 'idx' internally inside parseName? No, parseName is stateless.
            
            // Correction:
            // We were at `startOfData` (idx). 
            // We advanced 10 bytes for header.
            // Now we are at start of RDATA.
            // We must advance exactly `rdLength` bytes to reach the next record.
            // However, inside the `if (type == MX)`, we read bytes.
            // To be safe, we calculate `nextRecordIdx` before parsing RDATA.
            
            // REWIND LOGIC FOR ROBUSTNESS:
            // 1. Calculate where the next record starts
            int nextRecordIdx = idx + rdLength;
            
            // 2. Do the parsing if it's MX
            // (Code already written above is fine, it just reads from 'idx')
            
            // 3. Jump to next record
            idx = nextRecordIdx;
        }

        return bestMx;
    }

    /**
     * Reads a domain name from the byte array, handling DNS Compression.
     * * COMPRESSION SCHEME (RFC 1035):
     * If a byte starts with 11xxxxxx (>= 192 or 0xC0), it's a pointer.
     * The remaining 14 bits form an offset from the start of the packet.
     * * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
     * | 1  1 |                OFFSET                  |
     * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
     */
    private static String parseName(byte[] data, int index) {
        StringBuilder name = new StringBuilder();
        int idx = index;
        boolean jumped = false;
        int jumps = 0; // Guard against infinite loops

        while (true) {
            if (jumps > 10) break; // Safety break
            
            int len = data[idx] & 0xFF;

            // Case 1: End of Name (0x00)
            if (len == 0) {
                break;
            }

            // Case 2: Compression Pointer (starts with 0xC0)
            if ((len & 0xC0) == 0xC0) {
                // Read the offset (Current byte without top 2 bits + next byte)
                int offset = ((len & 0x3F) << 8) | (data[idx+1] & 0xFF);
                
                idx = offset; // Jump to the offset
                jumped = true;
                jumps++;
                continue;
            }

            // Case 3: Normal Label
            idx++; // Move past length byte
            for (int i = 0; i < len; i++) {
                name.append((char) data[idx++]);
            }
            name.append(".");
            
            // If we have jumped via pointer, we don't advance the original index naturally
            // because we are reading from elsewhere in the packet.
            // The calling function only cares about the String returned.
        }

        // Remove trailing dot
        if (name.length() > 0) {
            name.setLength(name.length() - 1);
        }
        return name.toString();
    }

    /**
     * Calculates how many bytes a Name field occupies in the current sequence.
     * This is necessary to advance the main pointer past a Name field to get to Type/Class.
     */
    private static int skipName(byte[] data, int index) {
        int idx = index;
        while (true) {
            int len = data[idx] & 0xFF;
            
            // Case 1: End of Name (1 byte)
            if (len == 0) {
                return idx - index + 1;
            }

            // Case 2: Compression Pointer (2 bytes total)
            if ((len & 0xC0) == 0xC0) {
                return idx - index + 2;
            }

            // Case 3: Normal Label (1 byte length + N bytes label)
            idx += len + 1;
        }
    }
}