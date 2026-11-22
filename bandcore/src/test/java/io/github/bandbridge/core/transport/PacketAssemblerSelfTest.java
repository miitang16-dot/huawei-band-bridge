package io.github.bandbridge.core.transport;

import io.github.bandbridge.core.proto.HuaweiPacket;
import io.github.bandbridge.core.proto.HuaweiTLV;
import io.github.bandbridge.core.testing.T;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-test for {@link PacketAssembler}: the BLE-side "collect slices, dispatch
 * complete packets" loop. Covers feeding frames whole, byte-by-byte, several per
 * call, slicer output, unrecognised packets and reset() after a half packet.
 */
public final class PacketAssemblerSelfTest {

    private PacketAssemblerSelfTest() {}

    private static final byte SERVICE = 0x7E;
    private static final byte COMMAND = 0x42;

    public static void run() throws Exception {
        groupWholeFrame();
        groupByteByByte();
        groupSeveralFramesPerFeed();
        groupSlicedFrames();
        groupUnclaimedPackets();
        groupResetDropsHalfPacket();
        groupEmptyFeeds();
    }

    private static void groupWholeFrame() throws Exception {
        T.group("PacketAssembler: one complete frame");

        HuaweiPacket.ParamsProvider pp = params();
        Recorder recorder = new Recorder();
        PacketAssembler assembler = new PacketAssembler(pp, recorder);

        byte[] frame = serializedFrame(pp, COMMAND, tlv(0x01, (byte) 0xAA));
        assembler.feed(frame);

        T.eq("one complete packet delivered", 1, recorder.claimed.size());
        T.eq("nothing delivered as async", 0, recorder.async.size());
        T.eq("serviceId", SERVICE, recorder.claimed.get(0).serviceId);
        T.eq("commandId", COMMAND, recorder.claimed.get(0).commandId);
    }

    private static void groupByteByByte() throws Exception {
        T.group("PacketAssembler: frame arriving one byte at a time");

        HuaweiPacket.ParamsProvider pp = params();
        Recorder recorder = new Recorder();
        PacketAssembler assembler = new PacketAssembler(pp, recorder);

        byte[] frame = serializedFrame(pp, COMMAND, tlv(0x01, (byte) 0xAA));
        for (byte b : frame) {
            assembler.feed(new byte[]{b});
        }

        T.eq("still exactly one packet", 1, recorder.claimed.size());
        T.eq("payload reassembled", (byte) 0xAA,
                recorder.claimed.get(0).getTlv().getByte(0x01));
    }

    private static void groupSeveralFramesPerFeed() throws Exception {
        T.group("PacketAssembler: several frames in one notification");

        HuaweiPacket.ParamsProvider pp = params();
        Recorder recorder = new Recorder();
        PacketAssembler assembler = new PacketAssembler(pp, recorder);

        byte[] a = serializedFrame(pp, (byte) 0x11, tlv(0x01, (byte) 0xAA));
        byte[] b = serializedFrame(pp, (byte) 0x22, tlv(0x01, (byte) 0xBB));
        byte[] c = serializedFrame(pp, (byte) 0x33, tlv(0x01, (byte) 0xCC));

        byte[] all = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        System.arraycopy(c, 0, all, a.length + b.length, c.length);
        assembler.feed(all);

        T.eq("three packets delivered from one feed", 3, recorder.claimed.size());
        T.eq("first command", (byte) 0x11, recorder.claimed.get(0).commandId);
        T.eq("second command", (byte) 0x22, recorder.claimed.get(1).commandId);
        T.eq("third command", (byte) 0x33, recorder.claimed.get(2).commandId);
    }

    private static void groupSlicedFrames() throws Exception {
        T.group("PacketAssembler: sliced packet reassembly");

        HuaweiPacket.ParamsProvider pp = params();
        pp.setSliceSize(24);
        HuaweiTLV payload = new HuaweiTLV().put(0x01, new byte[120]);

        List<byte[]> slices = serializedSlices(pp, COMMAND, payload);
        T.yes("payload really is sliced", slices.size() > 1);

        // (a) one notification per slice
        Recorder perSlice = new Recorder();
        PacketAssembler a = new PacketAssembler(pp, perSlice);
        for (byte[] slice : slices) a.feed(slice);
        T.eq("one packet from " + slices.size() + " sliced notifications", 1, perSlice.claimed.size());
        T.eq("sliced payload reassembled", payload, perSlice.claimed.get(0).getTlv());

        // (b) all slices in one notification
        byte[] all = concatenate(slices);
        Recorder perFeed = new Recorder();
        new PacketAssembler(pp, perFeed).feed(all);
        T.eq("one packet when all slices arrive together", 1, perFeed.claimed.size());
        T.eq("payload reassembled from one feed", payload, perFeed.claimed.get(0).getTlv());

        // (c) arbitrary chunk boundaries: 5 bytes per feed
        Recorder chunked = new Recorder();
        PacketAssembler c = new PacketAssembler(pp, chunked);
        for (int i = 0; i < all.length; i += 5) {
            int length = Math.min(5, all.length - i);
            byte[] chunk = new byte[length];
            System.arraycopy(all, i, chunk, 0, length);
            c.feed(chunk);
        }
        T.eq("one packet from 5-byte chunks", 1, chunked.claimed.size());
        T.eq("payload reassembled from chunks", payload, chunked.claimed.get(0).getTlv());
    }

    private static void groupUnclaimedPackets() throws Exception {
        T.group("PacketAssembler: unclaimed packet goes to onAsync()");

        HuaweiPacket.ParamsProvider pp = params();
        Recorder recorder = new Recorder();
        recorder.claimEverything = false;
        PacketAssembler assembler = new PacketAssembler(pp, recorder);

        assembler.feed(serializedFrame(pp, COMMAND, tlv(0x01, (byte) 0xAA)));

        T.eq("nothing claimed", 0, recorder.claimed.size());
        T.eq("packet delivered as async", 1, recorder.async.size());
        T.eq("async packet commandId", COMMAND, recorder.async.get(0).commandId);
    }

    private static void groupResetDropsHalfPacket() throws Exception {
        T.group("PacketAssembler: reset() drops a half packet");

        HuaweiPacket.ParamsProvider pp = params();
        Recorder recorder = new Recorder();
        PacketAssembler assembler = new PacketAssembler(pp, recorder);

        byte[] first = serializedFrame(pp, (byte) 0x11, tlv(0x01, (byte) 0xAA));
        byte[] second = serializedFrame(pp, (byte) 0x22, tlv(0x01, (byte) 0xBB));

        assembler.feed(new byte[]{first[0], first[1], first[2]});
        assembler.reset();
        assembler.feed(second);

        T.eq("only the post-reset packet is delivered", 1, recorder.claimed.size());
        T.eq("post-reset commandId", (byte) 0x22, recorder.claimed.get(0).commandId);
        T.eq("post-reset payload", (byte) 0xBB, recorder.claimed.get(0).getTlv().getByte(0x01));
    }

    private static void groupEmptyFeeds() {
        T.group("PacketAssembler: empty / null feeds are ignored");

        HuaweiPacket.ParamsProvider pp = params();
        Recorder recorder = new Recorder();
        PacketAssembler assembler = new PacketAssembler(pp, recorder);

        assembler.feed(null);
        assembler.feed(new byte[0]);
        T.eq("no packets from empty feeds", 0, recorder.claimed.size());
    }

    /* ---------- helpers ---------- */

    private static HuaweiPacket.ParamsProvider params() {
        HuaweiPacket.ParamsProvider pp = new HuaweiPacket.ParamsProvider();
        pp.setTransactionsCrypted(false);
        return pp;
    }

    private static HuaweiTLV tlv(int tag, byte value) {
        return new HuaweiTLV().put(tag, value);
    }

    private static byte[] serializedFrame(HuaweiPacket.ParamsProvider pp, byte commandId, HuaweiTLV tlv)
            throws Exception {
        HuaweiPacket packet = new HuaweiPacket(pp);
        packet.serviceId = SERVICE;
        packet.commandId = commandId;
        packet.setTlv(tlv);
        packet.setEncryption(false);
        packet.setSliced(false);
        List<byte[]> frames = packet.serialize();
        if (frames.size() != 1) {
            throw new IllegalStateException("expected one frame, got " + frames.size());
        }
        return frames.get(0);
    }

    private static List<byte[]> serializedSlices(HuaweiPacket.ParamsProvider pp, byte commandId, HuaweiTLV tlv)
            throws Exception {
        HuaweiPacket packet = new HuaweiPacket(pp);
        packet.serviceId = SERVICE;
        packet.commandId = commandId;
        packet.setTlv(tlv);
        packet.setEncryption(false);
        packet.setSliced(true);
        return packet.serialize();
    }

    private static byte[] concatenate(List<byte[]> parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    /** Sink that records what the link layer would hand to the auth/data layers. */
    private static final class Recorder implements PacketAssembler.Sink {
        final List<HuaweiPacket> claimed = new ArrayList<>();
        final List<HuaweiPacket> async = new ArrayList<>();
        boolean claimEverything = true;

        @Override
        public boolean onPacket(HuaweiPacket packet) {
            if (!claimEverything) return false;
            claimed.add(packet);
            return true;
        }

        @Override
        public void onAsync(HuaweiPacket packet) {
            async.add(packet);
        }
    }
}
