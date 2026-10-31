import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Minimal persistent RCON client. One connection is kept open so the server log is not spammed by reconnects. */
final class Rcon {
    static final class AuthException extends IOException {
        AuthException(String m) { super(m); }
    }

    private Socket sock;
    private InputStream in;
    private OutputStream out;
    private String key = "";

    synchronized String exec(int port, String password, String cmd) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                ensure(port, password);
                return run(cmd);
            } catch (AuthException e) {
                close();
                throw e;
            } catch (IOException e) {
                close();
                if (attempt >= 1) throw e;
            }
        }
    }

    /** Sends a command that makes the server drop the connection (stop); does not wait for a full reply. */
    synchronized void execAndForget(int port, String password, String cmd) throws IOException {
        try {
            ensure(port, password);
            send(2, cmd);
            sock.setSoTimeout(1500);
            try { readPacket(); } catch (IOException ignored) { }
        } finally {
            close();
        }
    }

    synchronized void close() {
        try { if (sock != null) sock.close(); } catch (IOException ignored) { }
        sock = null;
        in = null;
        out = null;
        key = "";
    }

    private void ensure(int port, String password) throws IOException {
        String k = port + ":" + password;
        if (sock != null && !sock.isClosed() && k.equals(key)) return;
        close();
        if (password == null || password.isEmpty()) throw new AuthException("RCON password is empty");
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            s.setSoTimeout(8000);
            s.setTcpNoDelay(true);
            sock = s;
            in = new BufferedInputStream(s.getInputStream());
            out = s.getOutputStream();
            send(1, password, 3);
            int[] id = new int[1];
            readPacket(id);
            if (id[0] == -1) throw new AuthException("RCON password rejected");
            key = k;
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            sock = null;
            throw e;
        }
    }

    private String run(String cmd) throws IOException {
        sock.setSoTimeout(10000);
        send(2, cmd);
        StringBuilder sb = new StringBuilder();
        int[] id = new int[1];
        String first = readPacket(id);
        sb.append(first);
        int chunk = first.getBytes(StandardCharsets.UTF_8).length;
        // The server splits replies longer than 4096 bytes into several packets; read follow-ups while chunks are full.
        while (chunk >= 4000) {
            sock.setSoTimeout(400);
            try {
                String more = readPacket(id);
                sb.append(more);
                chunk = more.getBytes(StandardCharsets.UTF_8).length;
            } catch (SocketTimeoutException e) {
                break;
            }
        }
        return sb.toString();
    }

    private void send(int id, String body) throws IOException { send(id, body, 2); }

    private void send(int id, String body, int type) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(14 + b.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(10 + b.length).putInt(id).putInt(type).put(b).put((byte) 0).put((byte) 0);
        out.write(bb.array());
        out.flush();
    }

    private String readPacket() throws IOException { return readPacket(new int[1]); }

    private String readPacket(int[] idOut) throws IOException {
        byte[] h = in.readNBytes(4);
        if (h.length < 4) throw new EOFException("RCON connection closed");
        int len = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (len < 10 || len > 8192) throw new IOException("Bad RCON packet length " + len);
        byte[] p = in.readNBytes(len);
        if (p.length < len) throw new EOFException("RCON connection closed");
        ByteBuffer bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        idOut[0] = bb.getInt();
        bb.getInt();
        return new String(p, 8, len - 10, StandardCharsets.UTF_8);
    }
}
