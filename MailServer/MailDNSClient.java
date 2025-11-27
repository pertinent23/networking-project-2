import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class MailDNSClient {
    private static final int DNS_PORT = 53;
    private static final int TYPE_MX = 15;
    private static final int CLASS_IN = 1;

    /**
     * Resolves the MX record for a domain using raw UDP.
     * @param domain The domain to resolve (e.g., "uliege.be")
     * @return The mail server hostname with the highest priority (lowest number), or null if failed.
     */
    public static String resolveMX(String domain) {
        DatagramSocket socket = null;
        try {
            // 1. Determine which DNS server to use (from /etc/resolv.conf in Docker)
            String dnsServer = getSystemDnsServer();
            InetAddress dnsAddress = InetAddress.getByName(dnsServer);

            socket = new DatagramSocket();
            socket.setSoTimeout(5000); // 5s timeout

            // 2. Build the DNS Query Packet
            byte[] requestData = buildQuery(domain);
            DatagramPacket sendPacket = new DatagramPacket(requestData, requestData.length, dnsAddress, DNS_PORT);
            
            // 3. Send and Receive
            socket.send(sendPacket);
            
            byte[] buffer = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(receivePacket);

            // 4. Parse Response
            return parseResponse(buffer, receivePacket.getLength());

        } catch (Exception e) {
            System.err.println("Custom DNS Client Error: " + e.getMessage());
            return null;
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    /**
     * Reads /etc/resolv.conf to find the Docker container's assigned DNS server.
     */
    private static String getSystemDnsServer() {
        try (BufferedReader br = new BufferedReader(new FileReader("/etc/resolv.conf"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("nameserver")) {
                    return line.split("\\s+")[1];
                }
            }
        } catch (IOException e) {
            // Fallback for local testing (Google DNS) if not in Docker/Linux
            return "8.8.8.8"; 
        }
        return "8.8.8.8";
    }

    /**
     * Constructs a DNS Query Header + Question section
     */
    private static byte[] buildQuery(String domain) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // --- HEADER (12 bytes) ---
        dos.writeShort(0x1234); // Transaction ID (Random)
        dos.writeShort(0x0100); // Flags: Standard Query, Recursion Desired
        dos.writeShort(0x0001); // Questions: 1
        dos.writeShort(0x0000); // Answer RRs: 0
        dos.writeShort(0x0000); // Authority RRs: 0
        dos.writeShort(0x0000); // Additional RRs: 0

        // --- QUESTION SECTION ---
        // Name: "uliege.be" -> 6uliege2be0
        String[] labels = domain.split("\\.");
        for (String label : labels) {
            byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
            dos.writeByte(bytes.length);
            dos.write(bytes);
        }
        dos.writeByte(0x00); // Terminating zero

        dos.writeShort(TYPE_MX);  // Type MX
        dos.writeShort(CLASS_IN); // Class IN

        return baos.toByteArray();
    }

    /**
     * Parses the DNS Response to find the best MX record.
     */
    private static String parseResponse(byte[] data, int length) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));

        // Skip Header (12 bytes)
        dis.skipBytes(12);

        // Skip Question Section
        // We have to parse the name to know how many bytes to skip
        parseName(data, 12); // Just to find the length
        // The pointer `pos` logic is tricky with DataInputStream, so we use a manual index approach below.
        
        // --- MANUAL INDEX PARSING ---
        int idx = 12; 

        // Skip QName
        while (data[idx] != 0) {
            idx += (data[idx] & 0xFF) + 1;
        }
        idx++; // Skip the 0x00
        idx += 4; // Skip QType and QClass

        // --- ANSWER SECTION ---
        // We need to read the ANCOUNT (Answer Count) from the header (bytes 6-7)
        int answerCount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        
        String bestMx = null;
        int bestPreference = Integer.MAX_VALUE;

        for (int i = 0; i < answerCount; i++) {
            // 1. Name (usually a pointer 0xC0xx)
            int parsedNameLen = skipName(data, idx); 
            idx += parsedNameLen;

            // 2. Read Type, Class, TTL, RDLength
            int type = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
            int dataLen = ((data[idx+8] & 0xFF) << 8) | (data[idx+9] & 0xFF);
            
            idx += 10; // Skip Type(2), Class(2), TTL(4), RDLen(2)

            if (type == TYPE_MX) {
                // MX Record Data: Preference (2 bytes) + Exchange (Name)
                int preference = ((data[idx] & 0xFF) << 8) | (data[idx+1] & 0xFF);
                
                // Read the Exchange Server Name (starting at idx + 2)
                String mxHost = parseName(data, idx + 2);

                if (preference < bestPreference) {
                    bestPreference = preference;
                    bestMx = mxHost;
                }
            }
            idx += dataLen; // Move to next record
        }

        return bestMx;
    }

    /**
     * Reads a domain name from the byte array, handling DNS Compression (Pointers 0xC0).
     */
    private static String parseName(byte[] data, int index) {
        StringBuilder name = new StringBuilder();
        int idx = index;
        boolean jumped = false;

        // Max jumps to prevent infinite loops
        int jumps = 0;

        while (true) {
            if (jumps > 5) break; 
            int length = data[idx] & 0xFF;

            if (length == 0) {
                break; // End of name
            }

            // Check for Compression Pointer (starts with 11xxxxxx -> >= 192 (0xC0))
            if ((length & 0xC0) == 0xC0) {
                // It's a pointer. The next byte is the offset.
                int offset = ((length & 0x3F) << 8) | (data[idx+1] & 0xFF);
                idx = offset; // Jump to the offset
                jumped = true;
                jumps++;
                continue;
            }

            // Normal Label
            idx++;
            for (int i = 0; i < length; i++) {
                name.append((char)data[idx++]);
            }
            name.append(".");
            
            if (jumped) {
                // If we jumped, we don't naturally increment the original index
                // But since we are returning the String, we just follow the pointer chain to the end
            }
        }

        if (name.length() > 0) name.setLength(name.length() - 1); // Remove trailing dot
        return name.toString();
    }

    /**
     * Returns the number of bytes consumed by a name field (handling pointers).
     * Used only to advance the main index in the loop.
     */
    private static int skipName(byte[] data, int index) {
        int idx = index;
        while (true) {
            int length = data[idx] & 0xFF;
            if (length == 0) return (idx - index) + 1;
            
            if ((length & 0xC0) == 0xC0) {
                // Pointer takes 2 bytes total
                return (idx - index) + 2; 
            }
            idx += length + 1;
        }
    }
}