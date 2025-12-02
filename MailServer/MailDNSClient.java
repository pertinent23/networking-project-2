import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * MailDNSClient
 * A raw UDP DNS client specifically designed to resolve MX (Mail Exchange) records.
 * Compliant with RFC 1035 (Domain Names - Implementation and Specification).
 *
 * This implementation manually constructs DNS packets and parses responses bit-by-bit
 * to function without external libraries (JNDI/DNSJava) as required by the project guidelines.
 *
 * NETWORK ARCHITECTURE CONTEXT:
 * -----------------------------
 * In the Docker environment, this client reads /etc/resolv.conf to find the
 * local DNS container IP (e.g., 10.0.1.2) instead of defaulting to public DNS.
 */
public class MailDNSClient {
    
    // --- CONSTANTS ---
    private static final int DNS_PORT = 53;
    private static final int TYPE_MX = 15;  // Mail Exchange Record Type
    private static final int TYPE_A = 1;
    private static final int CLASS_IN = 1;  // Internet Class
    
    // Standard UDP DNS Packet Limit (RFC 1035).
    // Responses larger than 512 bytes are truncated (TC flag), forcing TCP.
    // For this simulation, 512 bytes is sufficient for basic MX queries.
    private static final int MAX_PACKET_SIZE = 512; 

    /**
     * Resolves the MX | A record for a domain using raw UDP.
     * * FLOW:
     * 1. Detect DNS Server IP (from OS config).
     * 2. Construct Query Packet (Header + Question).
     * 3. Send over UDP.
     * 4. Receive Response.
     * 5. Parse Response (Header -> Skip Question -> Read Answers).
     *
     * @param domain The domain to resolve (e.g., "uliege.be")
     * @param TYPE The record type (MX or A)
     * @return The mail server hostname with the highest priority (lowest preference), or null.
     */
    private static String resolveRecord(String domain, final int TYPE) {
        DatagramSocket socket = null;
        try {
            // 1. Identify the DNS Server from /etc/resolv.conf
            // Necessary because Docker containers use internal DNS IPs (e.g., 127.0.0.11 or 10.x.x.x).
            String dnsServer = getSystemDnsServer();
            InetAddress dnsAddress = InetAddress.getByName(dnsServer);
            
            // Retry Mechanism: UDP is unreliable. We try up to 3 times before giving up.
            int maxRetries = 3;
            for (int attempt = 0; attempt < maxRetries; attempt++) {
                try {
                    socket = new DatagramSocket();
                    socket.setSoTimeout(2000); // 2 seconds timeout to avoid hanging

                    // 2. Build the DNS Query Packet
                    // We generate a random ID to match the request with the response.
                    int transactionID = new Random().nextInt(65535);
                    byte[] requestData = buildQuery(domain, transactionID, TYPE);
                    
                    DatagramPacket sendPacket = new DatagramPacket(requestData, requestData.length, dnsAddress, DNS_PORT);
                    
                    // 3. Send Request
                    socket.send(sendPacket);
                    
                    // 4. Receive Response
                    byte[] buffer = new byte[MAX_PACKET_SIZE];
                    DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(receivePacket);

                    // 5. Parse Response to extract MX hostname
                    String result = parseResponse(buffer, receivePacket.getLength(), transactionID, TYPE);
                    if (result != null) {
                        return result;
                    }
                    // If result is null (no MX found) but no error occurred, stop retrying.
                    break; 

                } catch (SocketTimeoutException e) {
                    // Packet lost or server busy; loop to retry.
                    continue;
                } finally {
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * resolve a record of type A
     * @param domain
     * @return
    */
    public static String resolveA(String domain) {
        return resolveRecord(domain, TYPE_A);
    }

    /**
     * resolve a record of type MX
     * @param domain
     * @return
    */
    public static String resolveMX(String domain) {
        return resolveRecord(domain, TYPE_MX);
    }

    /**
     * Reads /etc/resolv.conf to find the system's active nameserver.
     * * FILE STRUCTURE (/etc/resolv.conf):
     * ----------------------------------
     * # This file is managed by Docker
     * nameserver 10.0.1.2   <-- We want to extract this IP
     * options ndots:0
     * @return 
     */
    private static String getSystemDnsServer() {
        File resolvConf = new File(MailSettings.DNS_CONFIGS_FILE);
        if (!resolvConf.exists()) {
            return "8.8.8.8"; // Fallback for local testing (Google DNS)
        }

        try (BufferedReader br = new BufferedReader(new FileReader(resolvConf))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("nameserver")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return parts[1]; // Return the IP address found
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "8.8.8.8";
    }

    /**
     * Constructs the raw bytes for a DNS Query.
     *
     * PACKET VISUALIZATION (Query):
     * +---------------------+
     * |    Header (12 B)    | -> ID, Flags, Counts
     * +---------------------+
     * |   Question Section  | -> QNAME, QTYPE, QCLASS
     * +---------------------+
     * 
     * @param domain The domain to resolve (e.g., "uliege.be")
     * @param transactionID
     * @param TYPE
     * @return
     * @throws IOException
     */
    private static byte[] buildQuery(String domain, int transactionID, final int TYPE) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        /* * --- 1. DNS HEADER (12 Bytes) ---
         * * 0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |                      ID                       |  -> Transaction ID
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |QR|   Opcode  |AA|TC|RD|RA|   Z    |   RCODE   |  -> Flags
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |                    QDCOUNT                    |  -> Questions Count
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |                    ANCOUNT                    |  -> Answer RRs
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |                    NSCOUNT                    |  -> Authority RRs
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |                    ARCOUNT                    |  -> Additional RRs
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         */
        
        dos.writeShort(transactionID); 
        
        // Flags: 0x0100 
        // QR (Query/Response) = 0 (Query)
        // Opcode              = 0000 (Standard)
        // RD (Recursion Desired) = 1 (We want the server to find the answer for us)
        dos.writeShort(0x0100); 
        
        dos.writeShort(0x0001); // QDCOUNT: 1 Question
        dos.writeShort(0x0000); // ANCOUNT: 0
        dos.writeShort(0x0000); // NSCOUNT: 0
        dos.writeShort(0x0000); // ARCOUNT: 0

        /*
         * --- 2. QUESTION SECTION ---
         * Format: [Label Length] [Label Bytes] ... [0x00] [Type] [Class]
         * Example: "uliege.be" -> [6]uliege[2]be[0]
         */
        String[] labels = domain.split("\\.");
        for (String label : labels) {
            byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
            dos.writeByte(bytes.length);
            dos.write(bytes);
        }
        dos.writeByte(0x00); // Terminating Zero Byte (Root Label)

        dos.writeShort(TYPE);  // QTYPE: MX OR A
        dos.writeShort(CLASS_IN); // QCLASS: Internet (1)

        return baos.toByteArray();
    }

    /**
     * Parses the DNS Response to find the best MX record.
     * This method handles the complex pointer arithmetic required for DNS Compression.
     * @param data
     * @param length
     * @param transactionID
     * @param TYPE
     * @return
     */
    private static String parseResponse(byte[] data, int length, int transactionID, final int TYPE) throws IOException {
        // IndexPtr is a mutable wrapper for an integer, allowing us to pass the 
        // current position in the byte array by reference to helper methods.
        IndexPtr idx = new IndexPtr(0);

        // =================================================================
        // STEP 1: PARSE HEADER
        // =================================================================
        // Reading big-endian shorts manually: (HighByte << 8) | LowByte
        
        // Transaction ID
        int resID = ((data[idx.val++] & 0xFF) << 8) | (data[idx.val++] & 0xFF);
        
        //Skip flags
        @SuppressWarnings("unused") //To avoid warning about unused variable
        int flags = ((data[idx.val++] & 0xFF) << 8) | (data[idx.val++] & 0xFF);
        
        // Skip QDCOUNT (2)
        int qdCount = ((data[idx.val++] & 0xFF) << 8) | (data[idx.val++] & 0xFF); // Questions
        
        // Skip ANCOUNT (2)
        int anCount = ((data[idx.val++] & 0xFF) << 8) | (data[idx.val++] & 0xFF); // Answers
        
        idx.val += 4; // Skip NSCOUNT (2) + ARCOUNT (2) - We don't use them.

        // Security Check: Match ID to ensure this is the response to our query
        if (resID != transactionID) {
            return null;
        } 

        // =================================================================
        // STEP 2: SKIP QUESTION SECTION
        // =================================================================
        // The server echoes back the question we asked. We must jump over it 
        // to reach the Answer Section.
        for (int i = 0; i < qdCount; i++) {
            parseName(data, idx); // Parses name and advances 'idx'
            idx.val += 4; // Skip QTYPE(2) + QCLASS(2)
        }

        // =================================================================
        // STEP 3: PARSE ANSWER SECTION (Resource Records)
        // =================================================================
        /*
         * GENERIC RESOURCE RECORD (RR) STRUCTURE:
         * +---------------------+
         * |        NAME         | -> Variable length (often a pointer 0xC0..)
         * +---------------------+
         * |    TYPE (2 bytes)   | -> e.g., 15 for MX, 5 for CNAME
         * +---------------------+
         * |    CLASS (2 bytes)  |
         * +---------------------+
         * |     TTL (4 bytes)   |
         * +---------------------+
         * |  RDLENGTH (2 bytes) | -> Length of the RDATA following
         * +---------------------+
         * |        RDATA        | -> Variable (The actual answer)
         * +---------------------+
         */

        String bestMx = null;
        int bestPreference = Integer.MAX_VALUE;

        for (int i = 0; i < anCount; i++) {
            if (idx.val >= length) break; // Safety check

            // 1. Read Name (Usually a pointer to the QName in the question section)
            parseName(data, idx);

            // 2. Read Metadata
            int type = ((data[idx.val++] & 0xFF) << 8) | (data[idx.val++] & 0xFF);
            @SuppressWarnings("unused") //To avoid warning about unused variable
            int clazz = ((data[idx.val++] & 0xFF) << 8) | (data[idx.val++] & 0xFF);
            idx.val += 4; // Skip TTL (4 bytes), we don't cache in this project
            
            // 3. Read RDATA Length
            int rdLength = ((data[idx.val++] & 0xFF) << 8) | (data[idx.val++] & 0xFF);

            // 4. Process RDATA based on TYPE
            if (type == TYPE && type == TYPE_A) {
                StringBuilder ip = new StringBuilder();

                for (int j = 0; j < 4; j++) {
                    ip.append(data[idx.val++] & 0xFF);
                    if (j < 3) ip.append(".");
                }

                return ip.toString(); // Retourne "10.0.3.7"
            } else if (type == TYPE && type == TYPE_MX) {
                /*
                 * MX RDATA STRUCTURE:
                 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
                 * |                  PREFERENCE                   | 2 bytes
                 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
                 * |                   EXCHANGE                    | Variable Name
                 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
                 */
                int preference = ((data[idx.val++] & 0xFF) << 8) | (data[idx.val++] & 0xFF);
                
                // Parse the host name of the mail server
                String mxHost = parseName(data, idx);

                // Logic: Lower preference number = Higher Priority
                if (preference < bestPreference) {
                    bestPreference = preference;
                    bestMx = mxHost;
                }
            } else {
                // If it's not an MX record (e.g., CNAME, A, SOA), we MUST skip it
                // to correctly align with the start of the next record.
                // We use rdLength to jump over the RDATA block.
                idx.val += rdLength;
            }
        }

        if (TYPE == TYPE_MX) {
            return bestMx;
        }

        return null;
    }

    /**
     * Reads a domain name from the byte array, handling DNS Compression.
     * * COMPRESSION LOGIC (RFC 1035):
     * -----------------------------
     * To save space, DNS servers use pointers. 
     * - Normal Label: [Length][Bytes...] (e.g., 03 www 06 google 03 com 00)
     * - Pointer: Starts with bits 11xxxxxx (>= 0xC0).
     * Structure: [11 + Offset High Bits] [Offset Low Bits]
     * The pointer tells us to jump to a previous index in the packet to read the name.
     * @param data The full packet data
     * @param idx  The current index pointer (Mutable)
     * @return The decoded domain name string.
     */
    private static String parseName(byte[] data, IndexPtr idx) {
        StringBuilder name = new StringBuilder();
        boolean jumped = false;
        int jumps = 0; // Guard against infinite loops (malformed packets)

        // Store the original position. If we jump via pointer, we must eventually 
        // set the 'idx' to the position *after* the pointer in the original sequence.
        int currentPos = idx.val; 

        while (true) {
            if (jumps > 10) break; // Infinite loop protection

            int len = data[currentPos] & 0xFF; // Read length byte

            // Case A: End of Name (0x00)
            if (len == 0) {
                currentPos++;
                break;
            }

            // Case B: Compression Pointer (starts with 11xxxxxx binary -> 0xC0 hex)
            if ((len & 0xC0) == 0xC0) {
                // Calculate Offset: Strip the 11xxxxxx prefix, combine with next byte
                int offset = ((len & 0x3F) << 8) | (data[currentPos + 1] & 0xFF);
                
                // If this is our first jump, we need to record where we should resume 
                // parsing the main packet later (currentPos + 2 bytes for the pointer).
                if (!jumped) {
                    idx.val = currentPos + 2; 
                    jumped = true;
                }
                
                currentPos = offset; // Jump to the pointer location
                jumps++;
            } 
            // Case C: Normal Label
            else {
                currentPos++; // Move past length byte
                for (int i = 0; i < len; i++) {
                    name.append((char) data[currentPos++]);
                }
                name.append(".");
            }
        }

        // If we processed a simple name without pointers, update the main index
        // to where we finished reading.
        if (!jumped) {
            idx.val = currentPos;
        }

        // Format result: Remove trailing dot (e.g., "uliege.be." -> "uliege.be")
        if (name.length() > 0) {
            name.setLength(name.length() - 1);
        }
        return name.toString();
    }

    /**
     * Helper Class: IndexPtr
     * Acts as a pointer reference for the byte array index.
     * Allows helper methods to update the main parsing position.
     */
    private static class IndexPtr {
        int val;

        /**
         * 
         * @param val
        */
        IndexPtr(int val) { 
            this.val = val; 
        }
    }
}