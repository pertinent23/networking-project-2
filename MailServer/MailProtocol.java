import java.io.*;
import java.net.Socket;

public abstract class MailProtocol {
    // the client socket
    protected Socket socket;

    // flux to read from and write to the client
    protected BufferedReader in;
    protected PrintWriter out;

    // flux to write raw bytes if needed
    protected OutputStream rawOut;

    // the domain of thie server
    protected String serverDomain;

    public MailProtocol(Socket socket, String domain) throws IOException {
        this.socket = socket;
        this.serverDomain = domain;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.rawOut = socket.getOutputStream();
        this.out = new PrintWriter(rawOut, true);
    }

    public abstract void handle() throws IOException;

    /**
     * Close the socket connexion 
    */
    protected void close() {
        try{
            socket.close();
        } catch(IOException e) {
            // the socket is probably already closed
        }
    }
}
