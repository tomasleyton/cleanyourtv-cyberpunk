package com.gathin.tvhome;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** The miIO wire format, without a device: key schedule, packet layout, hello parsing, JSON. */
public class MiioClientTest {

    private static final byte[] TOKEN = MiioClient.hexToken("00112233445566778899aabbccddeeff");

    @Test
    public void keyScheduleFollowsTheProtocol() {
        byte[] key = MiioClient.md5(TOKEN);
        byte[] iv = MiioClient.md5(key, TOKEN);
        assertEquals(16, key.length);
        assertEquals(16, iv.length);
        assertArrayEquals(key, MiioClient.md5(TOKEN));
        if (Arrays.equals(key, iv)) fail("iv must differ from key");
    }

    @Test
    public void encryptThenDecryptRoundTrips() throws Exception {
        byte[] key = MiioClient.md5(TOKEN);
        byte[] iv = MiioClient.md5(key, TOKEN);
        byte[] plain = "{\"id\":7,\"method\":\"get_prop\",\"params\":[\"power\"]}".getBytes(StandardCharsets.UTF_8);
        byte[] enc = MiioClient.encrypt(key, iv, plain);
        assertEquals(0, enc.length % 16);
        if (enc.length <= plain.length) fail("PKCS7 padding adds at least one byte");
        assertArrayEquals(plain, MiioClient.decrypt(key, iv, enc));
    }

    @Test
    public void packetHasHeaderChecksumAndPayload() {
        byte[] enc = new byte[48];
        Arrays.fill(enc, (byte) 0x5a);
        byte[] pkt = MiioClient.packet(TOKEN, 123456789, 123456, enc);
        assertEquals(32 + 48, pkt.length);
        ByteBuffer b = ByteBuffer.wrap(pkt);
        assertEquals((short) 0x2131, b.getShort(0));
        assertEquals((short) 80, b.getShort(2));
        assertEquals(0, b.getInt(4));
        assertEquals(123456789, b.getInt(8));
        assertEquals(123456, b.getInt(12));
        byte[] head = Arrays.copyOf(pkt, 16);
        assertArrayEquals(MiioClient.md5(head, TOKEN, enc), Arrays.copyOfRange(pkt, 16, 32));
        assertArrayEquals(enc, Arrays.copyOfRange(pkt, 32, pkt.length));
    }

    @Test
    public void helloIsMagicLengthAndFf() {
        byte[] h = MiioClient.hello();
        assertEquals(32, h.length);
        assertArrayEquals(new byte[] {0x21, 0x31, 0, 0x20}, Arrays.copyOf(h, 4));
        for (int i = 4; i < 32; i++) assertEquals((byte) 0xff, h[i]);
    }

    @Test
    public void helloReplyGivesDeviceIdAndStamp() throws Exception {
        byte[] reply = ByteBuffer.allocate(32).putShort((short) 0x2131).putShort((short) 32).putInt(0)
                .putInt(123456789).putInt(99).array();
        assertArrayEquals(new int[] {123456789, 99}, MiioClient.parseHello(reply));
    }

    @Test(expected = IOException.class)
    public void shortHelloReplyIsRejected() throws Exception {
        MiioClient.parseHello(new byte[] {0x21, 0x31, 0, 0x20});
    }

    @Test
    public void jsonKeepsNumbersBareAndQuotesStrings() {
        assertEquals("{\"id\":3,\"method\":\"set_power\",\"params\":[\"on\",\"smooth\",300]}",
                MiioClient.json(3, "set_power", "on", "smooth", 300));
        assertEquals("{\"id\":4,\"method\":\"get_prop\",\"params\":[\"power\",\"bright\"]}",
                MiioClient.json(4, "get_prop", (Object[]) new String[] {"power", "bright"}));
        assertEquals("{\"id\":5,\"method\":\"miIO.info\",\"params\":[]}", MiioClient.json(5, "miIO.info"));
    }

    @Test
    public void tokenParsing() {
        assertArrayEquals(new byte[] {0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                (byte) 0x88, (byte) 0x99, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff}, TOKEN);
        for (String bad : new String[] {null, "", "abc", "zz112233445566778899aabbccddeeff"}) {
            try {
                MiioClient.hexToken(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // fine
            }
        }
    }

    @Test
    public void colourMatching() {
        // rgb mode: the exact colour; a white or an unknown colour: none.
        assertEquals(0, HeliosPresets.colourIndex(1, 0xFF0000));
        assertEquals(3, HeliosPresets.colourIndex(1, 0x00FF00));
        assertEquals(-1, HeliosPresets.colourIndex(1, 0x00F0FF));
        assertEquals(-1, HeliosPresets.colourIndex(2, 0xFF0000));
    }

    @Test
    public void whiteMatchingAndToggle() {
        assertEquals(R.string.helios_white_warm, HeliosPresets.whiteName(2, 3040));
        assertEquals(R.string.helios_white_neutral, HeliosPresets.whiteName(2, 4039));
        assertEquals(R.string.helios_white_cool, HeliosPresets.whiteName(2, 6000));
        assertEquals(0, HeliosPresets.whiteName(2, 5000));
        assertEquals(0, HeliosPresets.whiteName(1, 3000));
        // Hold OK: a colour goes to warm, warm to cold, cold (or a neutral) to warm.
        assertEquals(3000, HeliosPresets.nextWhite(1, 4039));   // a colour: start warm
        assertEquals(4100, HeliosPresets.nextWhite(2, 3000));
        assertEquals(6000, HeliosPresets.nextWhite(2, 4100));
        assertEquals(3000, HeliosPresets.nextWhite(2, 6000));
        assertEquals(6000, HeliosPresets.nextWhite(2, 4039));   // counts as neutral: on to cold
        assertEquals(6000, HeliosPresets.nextWhite(2, 5000));   // in between: the next white above
    }

    @Test
    public void brightnessSteps() {
        assertEquals(75, HeliosPresets.nextBright(57));
        assertEquals(50, HeliosPresets.nextBright(25));
        assertEquals(100, HeliosPresets.nextBright(75));
        assertEquals(25, HeliosPresets.nextBright(100));
        assertEquals(25, HeliosPresets.nextBright(0));
    }
}
