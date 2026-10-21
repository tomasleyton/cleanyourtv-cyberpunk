package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A tiny client for one Xiaomi miIO device on the LAN (encrypted JSON over UDP, port 54321), with the
 * classic Yeelight light commands on top. Nothing here is asynchronous: every method blocks for up to
 * two attempts of {@link #TIMEOUT_MS} (the device drops a packet now and then), so callers run it off
 * the main thread (HeliosPanel owns a single worker thread for it).
 *
 * The wire format: a hello packet (magic 0x2131, length 32, the rest 0xff) is answered with the
 * device id and its uptime stamp. Requests are then AES-128-CBC with key = md5(token) and
 * iv = md5(key + token) of {@code {"id":n,"method":...,"params":[...]}}, behind a 32-byte header
 * (magic, total length, 0, device id, stamp + seconds since hello, md5 of header + token + payload).
 * Replies decrypt the same way. The pure parts (key schedule, packet layout, JSON) are static so a
 * plain JVM test can check them without a device.
 */
final class MiioClient {

    static final int PORT = 54321;
    static final int TIMEOUT_MS = 3000;
    /** Hello again after this long: the stamp must stay close to the device's clock. */
    private static final long HELLO_MAX_AGE_NS = 120_000_000_000L;
    private static final int SMOOTH_MS = 300;

    private final InetAddress address;
    private final byte[] token;
    private final byte[] key;
    private final byte[] iv;
    private boolean discovered;
    private int deviceId;
    private int stamp;
    private long helloNanos;
    private int nextId;

    /** @param token the device's 16-byte token (32 hex digits in the Mi Home backup) */
    MiioClient(String host, byte[] token) throws IOException {
        if (token.length != 16) throw new IllegalArgumentException("token must be 16 bytes");
        this.address = InetAddress.getByName(host);
        this.token = token.clone();
        this.key = md5(token);
        this.iv = md5(key, token);
        // Not from 1: the device may still remember the ids of the last session.
        this.nextId = (int) ((System.currentTimeMillis() / 1000) % 100_000);
    }

    // ---- Yeelight commands ---------------------------------------------------------------------

    /** Values of the named properties, as the device sends them (strings, in order), e.g. power → "on". */
    String[] getProp(String... names) throws IOException {
        String reply = call("get_prop", (Object[]) names);
        try {
            JSONArray r = new JSONObject(reply).getJSONArray("result");
            String[] out = new String[r.length()];
            for (int i = 0; i < out.length; i++) out[i] = r.getString(i);
            return out;
        } catch (JSONException e) {
            throw new IOException("unexpected reply: " + reply, e);
        }
    }

    void setPower(boolean on) throws IOException {
        command("set_power", on ? "on" : "off", "smooth", SMOOTH_MS);
    }

    /** @param percent 1..100 */
    void setBright(int percent) throws IOException {
        command("set_bright", clamp(percent, 1, 100), "smooth", SMOOTH_MS);
    }

    /** @param kelvin 1700..6500 */
    void setCt(int kelvin) throws IOException {
        command("set_ct_abx", clamp(kelvin, 1700, 6500), "smooth", SMOOTH_MS);
    }

    /** @param rgb 0xRRGGBB */
    void setRgb(int rgb) throws IOException {
        command("set_rgb", rgb & 0xFFFFFF, "smooth", SMOOTH_MS);
    }

    private void command(String method, Object... params) throws IOException {
        String reply = call(method, params);
        if (!reply.contains("\"ok\"")) throw new IOException(method + " refused: " + reply);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- transport ---------------------------------------------------------------------------

    /** One request; the decrypted reply JSON. Hello first when needed, one retry on a timeout. */
    synchronized String call(String method, Object... params) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            if (!discovered || System.nanoTime() - helloNanos > HELLO_MAX_AGE_NS) hello(socket);
            int id = ++nextId;
            byte[] body = json(id, method, params).getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = encrypt(key, iv, body);
            int elapsed = (int) ((System.nanoTime() - helloNanos) / 1_000_000_000L);
            byte[] packet = packet(token, deviceId, stamp + elapsed, encrypted);
            try {
                return exchange(socket, packet, id);
            } catch (IOException e) {
                discovered = false;   // next call says hello again
                throw e;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("crypto failure", e);
        }
    }

    private void hello(DatagramSocket socket) throws IOException {
        byte[] hello = hello();
        byte[] buf = new byte[64];
        for (int attempt = 0; ; attempt++) {
            socket.send(new DatagramPacket(hello, hello.length, address, PORT));
            socket.setSoTimeout(TIMEOUT_MS);
            try {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                socket.receive(in);
                int[] h = parseHello(Arrays.copyOf(buf, in.getLength()));
                deviceId = h[0];
                stamp = h[1];
                helloNanos = System.nanoTime();
                discovered = true;
                return;
            } catch (SocketTimeoutException e) {
                if (attempt == 1) throw e;
            }
        }
    }

    /** Sends the packet (twice at most) and waits for the reply carrying this request's id. */
    private String exchange(DatagramSocket socket, byte[] packet, int id)
            throws IOException, GeneralSecurityException {
        byte[] buf = new byte[4096];
        String idTag = "\"id\":" + id;
        for (int attempt = 0; ; attempt++) {
            socket.send(new DatagramPacket(packet, packet.length, address, PORT));
            long deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L;
            try {
                while (true) {
                    long left = (deadline - System.nanoTime()) / 1_000_000L;
                    if (left <= 0) throw new SocketTimeoutException();
                    socket.setSoTimeout((int) left);
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    socket.receive(in);
                    if (in.getLength() <= 32) continue;   // a stray hello or an empty error frame
                    byte[] plain = decrypt(key, iv, Arrays.copyOfRange(buf, 32, in.getLength()));
                    String text = new String(plain, StandardCharsets.UTF_8).trim();   // drops trailing NULs
                    if (text.replace(" ", "").contains(idTag)) return text;
                }
            } catch (SocketTimeoutException e) {
                if (attempt == 1) throw e;
            }
        }
    }

    // ---- pure helpers (unit tested) ----------------------------------------------------------

    static byte[] hello() {
        byte[] p = new byte[32];
        Arrays.fill(p, (byte) 0xff);
        p[0] = 0x21;
        p[1] = 0x31;
        p[2] = 0;
        p[3] = 0x20;
        return p;
    }

    /** {device id, stamp} of a hello reply. */
    static int[] parseHello(byte[] reply) throws IOException {
        if (reply.length < 32 || reply[0] != 0x21 || reply[1] != 0x31) throw new IOException("bad hello reply");
        ByteBuffer b = ByteBuffer.wrap(reply);
        return new int[] {b.getInt(8), b.getInt(12)};
    }

    /** Header (magic, length, 0, device id, stamp), md5(header + token + payload), payload. */
    static byte[] packet(byte[] token, int deviceId, int stamp, byte[] encrypted) {
        ByteBuffer head = ByteBuffer.allocate(16);
        head.putShort((short) 0x2131).putShort((short) (32 + encrypted.length)).putInt(0).putInt(deviceId).putInt(stamp);
        byte[] h = head.array();
        byte[] sum = md5(h, token, encrypted);
        return ByteBuffer.allocate(32 + encrypted.length).put(h).put(sum).put(encrypted).array();
    }

    static byte[] encrypt(byte[] key, byte[] iv, byte[] plain) throws GeneralSecurityException {
        return cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plain);
    }

    static byte[] decrypt(byte[] key, byte[] iv, byte[] encrypted) throws GeneralSecurityException {
        return cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(encrypted);
    }

    private static Cipher cipher(int mode, byte[] key, byte[] iv) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c;
    }

    static byte[] md5(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);   // MD5 is in every Java runtime
        }
    }

    /** {@code {"id":n,"method":m,"params":[...]}}: numbers bare, everything else a string. */
    static String json(int id, String method, Object... params) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("{\"id\":").append(id).append(",\"method\":\"").append(method).append("\",\"params\":[");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            Object p = params[i];
            if (p instanceof Number || p instanceof Boolean) sb.append(p);
            else sb.append('"').append(String.valueOf(p).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append("]}").toString();
    }

    /** 32 hex digits to 16 bytes; IllegalArgumentException for anything else. */
    static byte[] hexToken(String hex) {
        if (hex == null || hex.length() != 32) throw new IllegalArgumentException("token must be 32 hex digits");
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16), lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("token must be 32 hex digits");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
