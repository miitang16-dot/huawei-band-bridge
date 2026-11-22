package io.github.bandbridge.core.proto;

import io.github.bandbridge.core.proto.packets.DeviceConfig;
import io.github.bandbridge.core.testing.T;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Self-test for the parts of {@link HuaweiPacket} that do not need Bluetooth:
 * frame header / length / CRC validation, partial-packet accumulation,
 * slicing on the way out, and reassembly on the way in.
 *
 * Frames are hand-built byte by byte here (not only produced by serialize()),
 * so the parser is checked against the documented wire format instead of against
 * its own serializer.
 */
public final class HuaweiPacketFrameSelfTest {

    private HuaweiPacketFrameSelfTest() {}

    private static final byte UNKNOWN_SERVICE = 0x7E;
    private static final byte UNKNOWN_COMMAND = 0x42;

    public static void run() throws Exception {
        groupHandBuiltFrame();
        groupFrameRejectsBadInput();
        groupPartialFrame();
        groupLeftoverBytes();
        groupRawPayloadServices();
        groupDispatch();
        groupSerializeRoundTrip();
        groupSlicing();
    }

    private static void groupHandBuiltFrame() throws Exception {
        T.group("Huawei frame: header + length + CRC + TLV");

        HuaweiTLV tlv = new HuaweiTLV().put(0x01, (byte) 0xAA).put(0x02, "hi");
        byte[] frame = frame(UNKNOWN_SERVICE, UNKNOWN_COMMAND, tlv.serialize());

        HuaweiPacket.ParamsProvider pp = new HuaweiPacket.ParamsProvider();
        HuaweiPacket parsed = new HuaweiPacket(pp).parse(frame);

        T.yes("packet is complete", parsed.complete);
        T.eq("serviceId", UNKNOWN_SERVICE, parsed.serviceId);
        T.eq("commandId", UNKNOWN_COMMAND, parsed.commandId);
        T.eq("TLV payload", tlv, parsed.getTlv());
        T.eq("no bytes left over", 0, parsed.getLeft());

        // Empty TLV section: svc/cmd only, still a valid complete packet.
        HuaweiPacket emptyTlv = new HuaweiPacket(pp).parse(frame(UNKNOWN_SERVICE, (byte) 0x43, new byte[0]));
        T.yes("svc/cmd-only frame is complete", emptyTlv.complete);
        T.eq("empty TLV section parses to an empty TLV", 0, emptyTlv.getTlv().get().size());
    }

    private static void groupFrameRejectsBadInput() {
        T.group("Huawei frame: rejects corrupted input");

        HuaweiTLV tlv = new HuaweiTLV().put(0x01, (byte) 0xAA);
        HuaweiPacket.ParamsProvider pp = new HuaweiPacket.ParamsProvider();

        byte[] good = frame(UNKNOWN_SERVICE, UNKNOWN_COMMAND, tlv.serialize());

        byte[] badMagic = good.clone();
        badMagic[0] = 0x5B;
        T.thrown("magic byte other than 0x5A",
                HuaweiPacket.MagicMismatchException.class,
                () -> new HuaweiPacket(pp).parse(badMagic));

        byte[] badCrc = good.clone();
        badCrc[badCrc.length - 1] ^= 0x01;
        T.thrown("flipped CRC bit",
                HuaweiPacket.ChecksumIncorrectException.class,
                () -> new HuaweiPacket(pp).parse(badCrc));

        byte[] badPayload = good.clone();
        badPayload[6] ^= 0x01; // flip one bit inside the payload, CRC no longer matches
        T.thrown("payload bit flip",
                HuaweiPacket.ChecksumIncorrectException.class,
                () -> new HuaweiPacket(pp).parse(badPayload));

        T.thrown("negative declared length",
                HuaweiPacket.LengthMismatchException.class,
                () -> new HuaweiPacket(pp).parse(new byte[]{(byte) 0x5A, (byte) 0xFF, (byte) 0xFF}));
    }

    private static void groupPartialFrame() throws Exception {
        T.group("Huawei frame: accumulates a frame split across BLE notifications");

        HuaweiTLV tlv = new HuaweiTLV().put(0x01, (byte) 0xAA).put(0x02, (byte) 0xBB);
        byte[] frame = frame(UNKNOWN_SERVICE, UNKNOWN_COMMAND, tlv.serialize());
        HuaweiPacket.ParamsProvider pp = new HuaweiPacket.ParamsProvider();

        HuaweiPacket packet = new HuaweiPacket(pp);
        HuaweiPacket afterFirstByte = packet.parse(new byte[]{frame[0]});
        T.no("one byte is not enough", afterFirstByte.complete);

        HuaweiPacket afterTwoBytes = packet.parse(new byte[]{frame[1], frame[2]});
        T.no("three bytes are not enough", afterTwoBytes.complete);

        byte[] rest = new byte[frame.length - 3];
        System.arraycopy(frame, 3, rest, 0, rest.length);
        HuaweiPacket complete = packet.parse(rest);
        T.yes("completed once the rest arrives", complete.complete);
        T.eq("serviceId after reassembly", UNKNOWN_SERVICE, complete.serviceId);
        T.eq("TLV after reassembly", tlv, complete.getTlv());

        // A frame shorter than the minimal header is buffered, not rejected.
        HuaweiPacket shortPacket = new HuaweiPacket(pp).parse(new byte[]{(byte) 0x5A, 0x00});
        T.no("two-byte prefix is buffered", shortPacket.complete);
    }

    private static void groupLeftoverBytes() throws Exception {
        T.group("Huawei frame: reports trailing bytes via getLeft()");

        HuaweiTLV first = new HuaweiTLV().put(0x01, (byte) 0x11);
        HuaweiTLV second = new HuaweiTLV().put(0x01, (byte) 0x22);
        byte[] frameA = frame(UNKNOWN_SERVICE, (byte) 0x11, first.serialize());
        byte[] frameB = frame(UNKNOWN_SERVICE, (byte) 0x22, second.serialize());

        byte[] concatenated = new byte[frameA.length + frameB.length];
        System.arraycopy(frameA, 0, concatenated, 0, frameA.length);
        System.arraycopy(frameB, 0, concatenated, frameA.length, frameB.length);

        HuaweiPacket.ParamsProvider pp = new HuaweiPacket.ParamsProvider();
        HuaweiPacket parsedA = new HuaweiPacket(pp).parse(concatenated);
        T.eq("first frame command", (byte) 0x11, parsedA.commandId);
        T.eq("leftover length points at the second frame", frameB.length, parsedA.getLeft());

        byte[] leftover = new byte[parsedA.getLeft()];
        System.arraycopy(concatenated, concatenated.length - leftover.length, leftover, 0, leftover.length);
        HuaweiPacket parsedB = new HuaweiPacket(pp).parse(leftover);
        T.eq("second frame command", (byte) 0x22, parsedB.commandId);
        T.eq("second frame payload", second, parsedB.getTlv());
    }

    private static void groupRawPayloadServices() throws Exception {
        T.group("Huawei frame: services whose payload is not TLV");

        // (0x0A, 0x05) is in the "not TLV" list: the payload stays raw.
        byte[] payload = {0x01, 0x02, 0x03, 0x04};
        byte[] frame = frame(0x0A, 0x05, payload);

        HuaweiPacket.ParamsProvider pp = new HuaweiPacket.ParamsProvider();
        HuaweiPacket parsed = new HuaweiPacket(pp).parse(frame);
        T.yes("raw-payload frame is complete", parsed.complete);
        T.eq("no TLV is built for raw payloads", null, parsed.getTlv());
    }

    private static void groupDispatch() throws Exception {
        T.group("Huawei frame: response class dispatch by service/command");

        // 0x01/0x08 = DeviceConfig.BatteryLevel -> known response subclass.
        byte[] battery = frame(0x01, 0x08, new HuaweiTLV().put(0x01, (byte) 0x5A).serialize());
        HuaweiPacket.ParamsProvider pp = new HuaweiPacket.ParamsProvider();
        HuaweiPacket parsed = new HuaweiPacket(pp).parse(battery);
        T.yes("known service/command maps to its Response class",
                parsed instanceof DeviceConfig.BatteryLevel.Response);

        // Unknown service falls through to a plain HuaweiPacket (decrypt is a no-op
        // without a secret key, so it stays un-decrypted).
        HuaweiPacket unknown = new HuaweiPacket(pp).parse(frame(UNKNOWN_SERVICE, UNKNOWN_COMMAND, new byte[0]));
        T.eq("unknown service stays a plain packet", HuaweiPacket.class, unknown.getClass());
    }

    private static void groupSerializeRoundTrip() throws Exception {
        T.group("Huawei frame: serialize -> parse round-trip");

        HuaweiTLV tlv = new HuaweiTLV().put(0x01, (byte) 0xAA).put(0x02, 0x01020304);
        HuaweiPacket.ParamsProvider pp = new HuaweiPacket.ParamsProvider();
        pp.setTransactionsCrypted(false);

        HuaweiPacket out = new HuaweiPacket(pp);
        out.serviceId = UNKNOWN_SERVICE;
        out.commandId = UNKNOWN_COMMAND;
        out.setTlv(tlv);
        out.setEncryption(false);
        out.setSliced(false);

        List<byte[]> frames = out.serialize();
        T.eq("unsliced packet produces one frame", 1, frames.size());

        byte[] frame = frames.get(0);
        T.eq("magic", 0x5A, frame[0] & 0xFF);
        T.eq("unsliced marker", 0x00, frame[3] & 0xFF);
        T.eq("declared length matches frame size", frame.length - 5, readShort(frame, 1));
        T.eq("frame CRC is valid", crcOfBody(frame), readShort(frame, frame.length - 2));

        HuaweiPacket in = new HuaweiPacket(pp).parse(frame);
        T.yes("round-trip complete", in.complete);
        T.eq("round-trip serviceId", UNKNOWN_SERVICE, in.serviceId);
        T.eq("round-trip commandId", UNKNOWN_COMMAND, in.commandId);
        T.eq("round-trip TLV", tlv, in.getTlv());
    }

    private static void groupSlicing() throws Exception {
        T.group("Huawei frame: sliced serialization + reassembly");

        HuaweiTLV tlv = new HuaweiTLV().put(0x01, new byte[120]);
        HuaweiPacket.ParamsProvider pp = new HuaweiPacket.ParamsProvider();
        pp.setTransactionsCrypted(false);
        pp.setSliceSize(24);

        HuaweiPacket out = new HuaweiPacket(pp);
        out.serviceId = UNKNOWN_SERVICE;
        out.commandId = UNKNOWN_COMMAND;
        out.setTlv(tlv);
        out.setEncryption(false);
        out.setSliced(true);

        List<byte[]> frames = out.serialize();
        // 122-byte TLV serialized + 2-byte svc/cmd, split over sliceSize-7 body bytes.
        int tlvBytes = tlv.serialize().length;
        int expectedFrames = (int) Math.ceil((tlvBytes + 2) / (double) (24 - 5 - 2));
        T.eq("frame count follows sliceSize", expectedFrames, frames.size());
        T.yes("more than one frame was produced", frames.size() > 1);

        boolean sizesOk = true;
        boolean crcOk = true;
        boolean flagsOk = true;
        boolean slicesOk = true;
        for (int i = 0; i < frames.size(); i++) {
            byte[] f = frames.get(i);
            if (f.length > 24) sizesOk = false;
            if (readShort(f, f.length - 2) != crcOfBody(f)) crcOk = false;
            if ((f[4] & 0xFF) != i) flagsOk = false;

            int expectedSlice = i == 0 ? 0x01 : (i == frames.size() - 1 ? 0x03 : 0x02);
            if ((f[3] & 0xFF) != expectedSlice) slicesOk = false;
        }
        T.yes("every slice fits sliceSize", sizesOk);
        T.yes("every slice has a valid CRC", crcOk);
        T.yes("slice counter (flag byte) increments from 0", flagsOk);
        T.yes("slice markers are 1 / 2 / 3 (first / middle / last)", slicesOk);

        // Reassemble by feeding the slices in order, as BLE would.
        HuaweiPacket asm = new HuaweiPacket(pp);
        boolean completedTooEarly = false;
        for (int i = 0; i < frames.size(); i++) {
            asm = asm.parse(frames.get(i));
            if (i < frames.size() - 1 && asm.complete) completedTooEarly = true;
        }
        T.no("packet is not complete before the last slice", completedTooEarly);
        T.yes("packet is complete after the last slice", asm.complete);
        T.eq("reassembled serviceId", UNKNOWN_SERVICE, asm.serviceId);
        T.eq("reassembled commandId", UNKNOWN_COMMAND, asm.commandId);
        T.eq("reassembled TLV", tlv, asm.getTlv());
    }

    /* ---------- helpers ---------- */

    /** Build one unsliced frame exactly as the protocol describes it. */
    private static byte[] frame(int serviceId, int commandId, byte[] tlvBytes) {
        int bodyLength = 2 + tlvBytes.length; // svc + cmd + TLV
        byte[] head = new byte[4 + bodyLength];        // magic + len + marker + body
        ByteBuffer buffer = ByteBuffer.wrap(head);
        buffer.put((byte) 0x5A);                       // magic
        buffer.putShort((short) (bodyLength + 1));     // length (payload + unsliced marker)
        buffer.put((byte) 0x00);                       // 0 = not sliced
        buffer.put((byte) serviceId);
        buffer.put((byte) commandId);
        buffer.put(tlvBytes);

        int crc = CheckSums.getCRC16(head, 0x0000);
        ByteBuffer full = ByteBuffer.allocate(head.length + 2);
        full.put(head);
        full.putShort((short) crc);
        return full.array();
    }

    private static int crcOfBody(byte[] frame) {
        byte[] head = new byte[frame.length - 2];
        System.arraycopy(frame, 0, head, 0, head.length);
        return CheckSums.getCRC16(head, 0x0000);
    }

    private static int readShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }
}
