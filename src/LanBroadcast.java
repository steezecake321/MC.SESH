import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Makes running servers show up in Minecraft's Multiplayer screen under "LAN worlds". Only a singleplayer world opened to LAN announces itself normally, so the
 * manager sends the same small multicast message ("[MOTD]name[/MOTD][AD]port[/AD]" to 224.0.2.60:4445, about every 1.5 seconds) for every running server.
 * Java Edition clients on the same network pick it up; the join address is the PC's own address, taken from the packet. Bedrock does not use this.
 */
final class LanBroadcast {
    private LanBroadcast() { }

    private static final InetAddress GROUP = group();
    private static final int PORT = 4445;
    private static final Pattern VIRTUAL = Pattern.compile("(?i)vethernet|vmware|virtualbox|hyper-v|loopback|wsl|docker|tap-|tailscale|zerotier|bluetooth|vpn|npcap|pseudo");
    private static MulticastSocket socket;
    private static boolean warned;

    private static InetAddress group() {
        try { return InetAddress.getByName("224.0.2.60"); } catch (UnknownHostException e) { throw new IllegalStateException(e); }
    }

    /** On unless switched off for the server. */
    static boolean enabled(Srv s) { return !Boolean.FALSE.equals(s.cfg.get("lanBroadcast")); }

    /** Network adapters that are up, carry a normal IPv4 address and are not obviously virtual. */
    static List<NetworkInterface> adapters() {
        List<NetworkInterface> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || !ni.supportsMulticast()) continue;
                if (VIRTUAL.matcher(ni.getDisplayName() + " " + ni.getName()).find()) continue;
                boolean v4 = false;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) if (a instanceof Inet4Address && !a.isLinkLocalAddress()) v4 = true;
                if (v4) out.add(ni);
            }
        } catch (SocketException ignored) { }
        return out;
    }

    static byte[] packet(Srv s) {
        String name = s.name.replace('[', '(').replace(']', ')');
        if (name.length() > 60) name = name.substring(0, 60);
        return ("[MOTD]" + name + "[/MOTD][AD]" + s.gamePort() + "[/AD]").getBytes(StandardCharsets.UTF_8);
    }

    /** Names of the servers being announced right now (for the settings page and tests). */
    static List<String> announced() {
        List<String> l = new ArrayList<>();
        for (Srv s : Main.servers) if (enabled(s) && s.running(false)) l.add(s.name);
        return l;
    }

    static synchronized void tick() {
        List<byte[]> packets = new ArrayList<>();
        for (Srv s : Main.servers) if (enabled(s) && s.running(false)) packets.add(packet(s));
        if (packets.isEmpty()) return;
        try {
            if (socket == null) {
                socket = new MulticastSocket();
                socket.setTimeToLive(1);
            }
            List<NetworkInterface> nics = adapters();
            for (NetworkInterface ni : nics) {
                socket.setNetworkInterface(ni);
                for (byte[] b : packets) socket.send(new DatagramPacket(b, b.length, GROUP, PORT));
            }
            warned = false;
        } catch (IOException | RuntimeException e) {
            if (!warned) { Main.log("LAN broadcast: " + e); warned = true; }
            try { if (socket != null) socket.close(); } catch (RuntimeException ignored) { }
            socket = null;
        }
    }
}
