package io.github.bandbridge.core.proto;

import io.github.bandbridge.core.testing.T;

import java.nio.BufferUnderflowException;
import java.nio.charset.StandardCharsets;

/**
 * Self-test for {@link HuaweiTLV} and the package-private {@code VarInt} helper:
 * wire format, round-trips, and the edge cases the BLE parser has to survive
 * (empty input, truncated input, oversized length prefix, trailing null byte).
 */
public final class TlvSelfTest {

    private TlvSelfTest() {}

    public static void run() throws Exception {
        groupRoundTrip();
        groupWireFormat();
        groupEdgeCases();
        groupAccessors();
        groupVarInt();
    }

    private static void groupRoundTrip() throws Exception {
        T.group("TLV round-trip (all put() overloads)");

        HuaweiTLV original = new HuaweiTLV()
                .put(0x01, (byte) 0x42)
                .put(0x02, true)
                .put(0x03, (short) 0x1234)
                .put(0x04, 0x01020304)
                .put(0x05, 1700000000000L)
                .put(0x06, 1.5d)
                .put(0x07, "hello 手环")
                .put(0x08, new byte[]{0x00, 0x01, 0x02, (byte) 0xFF})
                .put(0x09, false);

        byte[] wire = original.serialize();
        HuaweiTLV parsed = new HuaweiTLV().parse(wire);

        T.eq("parsed equals original", original, parsed);
        T.eq("getByte", (byte) 0x42, parsed.getByte(0x01));
        T.eq("getBoolean true", Boolean.TRUE, parsed.getBoolean(0x02));
        T.eq("getShort", (short) 0x1234, parsed.getShort(0x03));
        T.eq("getInteger", 0x01020304, parsed.getInteger(0x04));
        T.eq("getLong", 1700000000000L, parsed.getLong(0x05));
        T.eq("getString (utf-8)", "hello 手环", parsed.getString(0x07));
        T.bytes("bytes value", new byte[]{0x00, 0x01, 0x02, (byte) 0xFF}, parsed.getBytes(0x08));
        T.eq("getBoolean false", Boolean.FALSE, parsed.getBoolean(0x09));

        HuaweiTLV inner = new HuaweiTLV().put(0x11, (byte) 0x01).put(0x12, "x");
        HuaweiTLV nested = new HuaweiTLV().put(0x0A, inner);
        HuaweiTLV nestedParsed = new HuaweiTLV().parse(nested.serialize());
        T.eq("nested TLV getObject", inner, nestedParsed.getObject(0x0A));
    }

    private static void groupWireFormat() throws Exception {
        T.group("TLV wire format (tag + VarInt length + value)");

        T.bytes("single byte element",
                new byte[]{0x01, 0x01, 0x7F},
                new HuaweiTLV().put(0x01, (byte) 0x7F).serialize());

        T.bytes("empty value element (put(tag))",
                new byte[]{0x2A, 0x00},
                new HuaweiTLV().put(0x2A).serialize());

        T.bytes("two elements keep insertion order",
                new byte[]{0x01, 0x01, 0x11, 0x02, 0x01, 0x22},
                new HuaweiTLV().put(0x01, (byte) 0x11).put(0x02, (byte) 0x22).serialize());

        // 200-byte value -> VarInt length is 0x81 0x48 (two bytes, 7 bits each).
        byte[] longValue = new byte[200];
        for (int i = 0; i < longValue.length; i++) longValue[i] = (byte) i;
        byte[] wire = new HuaweiTLV().put(0x05, longValue).serialize();
        T.eq("long value wire length", 1 + 2 + 200, wire.length);
        T.hex("long value length prefix", "05 81 48", new byte[]{wire[0], wire[1], wire[2]});
        T.eq("long value round-trips",
                (byte) 199, new HuaweiTLV().parse(wire).getBytes(0x05)[199]);

        T.eq("length() matches serialized size",
                wire.length, new HuaweiTLV().put(0x05, longValue).length());
    }

    private static void groupEdgeCases() throws Exception {
        T.group("TLV edge cases");

        T.eq("empty TLV serializes to zero bytes", 0, new HuaweiTLV().serialize().length);
        T.eq("length() of empty TLV", 0, new HuaweiTLV().length());
        T.eq("toString of empty TLV", "Empty", new HuaweiTLV().toString());

        HuaweiTLV emptyParsed = new HuaweiTLV().parse(new byte[0]);
        T.eq("parsing empty buffer yields an empty TLV", 0, emptyParsed.get().size());
        T.eq("parse(null) returns null", null, new HuaweiTLV().parse(null));

        // A trailing 0x00 tag byte after a complete element is padding and is ignored.
        HuaweiTLV padded = new HuaweiTLV().parse(new byte[]{0x01, 0x01, 0x7F, 0x00});
        T.eq("trailing 0x00 tag is ignored", 1, padded.get().size());
        T.eq("trailing 0x00 tag keeps value", (byte) 0x7F, padded.getByte(0x01));

        // Declared element size larger than the remaining buffer: rejected, not truncated.
        T.thrown("element size larger than remaining buffer",
                ArrayIndexOutOfBoundsException.class,
                () -> new HuaweiTLV().parse(new byte[]{0x01, 0x05, 0x7F}));

        // Tag byte with no length byte at all: the parser reads past the buffer.
        T.thrown("truncated element (tag only)",
                ArrayIndexOutOfBoundsException.class,
                () -> new HuaweiTLV().parse(new byte[]{0x01}));

        // put(tag, null) is a no-op, not an element with an empty value.
        HuaweiTLV nullPut = new HuaweiTLV().put(0x01, (byte[]) null).put(0x02, (String) null);
        T.no("put(tag, null byte[]) is ignored", nullPut.contains(0x01));
        T.no("put(tag, null String) is ignored", nullPut.contains(0x02));

        HuaweiTLV removed = new HuaweiTLV().put(0x01, (byte) 0x11).put(0x01, (byte) 0x22);
        T.bytes("remove() returns the last element with that tag",
                new byte[]{0x22}, removed.remove(0x01));
        T.eq("remove() drops only the returned element", 1, removed.get().size());
        T.bytes("the earlier element with the same tag survives",
                new byte[]{0x11}, removed.getBytes(0x01));
        T.bytes("remove() the remaining element", new byte[]{0x11}, removed.remove(0x01));
        T.no("remove() empties the tag", removed.contains(0x01));
        T.eq("remove() of an absent tag returns null", null, removed.remove(0x63));
    }

    private static void groupAccessors() throws Exception {
        T.group("TLV accessors");

        HuaweiTLV tlv = new HuaweiTLV()
                .put(0x10, new byte[]{(byte) 0xFF})            // 1 byte, unsigned on purpose
                .put(0x11, new byte[]{0x01, 0x02})             // 2 bytes
                .put(0x12, new byte[]{0x01, 0x02, 0x03})       // 3 bytes
                .put(0x13, new byte[]{0x00, 0x00, 0x01, 0x00}) // 4 bytes
                .put(0x14, new byte[0])                        // empty value
                .put(0x15, "手环".getBytes(StandardCharsets.UTF_8));

        T.eq("getAsInteger 1 byte is unsigned", 255, tlv.getAsInteger(0x10));
        T.eq("getAsInteger 2 bytes", 258, tlv.getAsInteger(0x11));
        // Known quirk of the ported code: the no-default getAsInteger() falls through to
        // ByteBuffer.getInt() for anything that is not 1 or 2 bytes, so a 3-byte value
        // underflows. Pinned here so the behaviour is documented, not "fixed" by a test.
        T.thrown("getAsInteger of a 3-byte value underflows (no-default overload)",
                BufferUnderflowException.class, () -> tlv.getAsInteger(0x12));
        T.eq("getAsInteger(tag, def) handles a 3-byte value", 0x010203, tlv.getAsInteger(0x12, -1));
        T.eq("getAsInteger 4 bytes", 256, tlv.getAsInteger(0x13));

        T.eq("getAsInteger(tag, def) for empty value uses default",
                -1, tlv.getAsInteger(0x14, -1));
        T.eq("getAsInteger(tag, def) for absent tag uses default",
                -1, tlv.getAsInteger(0x63, -1));
        T.eq("getAsInteger(tag, def) for 5-byte value uses default",
                -1, new HuaweiTLV().put(0x20, new byte[5]).getAsInteger(0x20, -1));

        T.eq("getBytes(tag, def) for absent tag uses default", null, tlv.getBytes(0x63, null));
        T.eq("getByte(tag, def)", (byte) 0x7E, tlv.getByte(0x63, (byte) 0x7E));
        T.eq("getShort(tag, def)", (short) 0x7E, tlv.getShort(0x63, (short) 0x7E));
        T.eq("getInteger(tag, def)", 0x7E, tlv.getInteger(0x63, 0x7E));
        T.eq("getBoolean(tag, def)", Boolean.TRUE, tlv.getBoolean(0x63, Boolean.TRUE));

        T.thrown("getBytes of missing tag",
                HuaweiPacket.MissingTagException.class, () -> tlv.getBytes(0x63));
        T.thrown("getString of missing tag",
                HuaweiPacket.MissingTagException.class, () -> tlv.getString(0x63));

        T.eq("getString decodes UTF-8", "手环", tlv.getString(0x15));

        // getObjects: every element with the same tag, each parsed as its own TLV.
        HuaweiTLV multi = new HuaweiTLV()
                .put(0x21, new HuaweiTLV().put(0x01, (byte) 0x01))
                .put(0x21, new HuaweiTLV().put(0x01, (byte) 0x02));
        T.eq("getObjects count", 2, multi.getObjects(0x21).size());
        T.eq("getObjects first", (byte) 0x01, multi.getObjects(0x21).get(0).getByte(0x01));
        T.eq("getObjects second", (byte) 0x02, multi.getObjects(0x21).get(1).getByte(0x01));

        // getAllContainerObjects: tags with the high bit set are containers.
        HuaweiTLV containers = new HuaweiTLV()
                .put(0x81, new HuaweiTLV().put(0x01, (byte) 0x0A))
                .put(0x82, new HuaweiTLV().put(0x01, (byte) 0x0B))
                .put(0x01, (byte) 0x0C); // not a container
        T.eq("getAllContainerObjects count", 2, containers.getAllContainerObjects().size());
        T.eq("getAllContainerObjects value",
                (byte) 0x0A, containers.getAllContainerObjects().get(0).getByte(0x01));
        T.eq("contains()", Boolean.TRUE, containers.contains(0x81));
        T.no("contains() for absent tag", containers.contains(0x83));
    }

    private static void groupVarInt() {
        T.group("VarInt (7 bits per byte, continuation bit 0x80)");

        int[] values = {0, 1, 0x7F, 0x80, 0xC8, 0x3FFF, 0x4000, 0x1FFFFF, 0x200000, Integer.MAX_VALUE};
        boolean sizesOk = true;
        boolean roundTripOk = true;
        for (int value : values) {
            byte[] encoded = VarInt.putVarIntValue(value);
            if (VarInt.getVarIntSize(value) != encoded.length) sizesOk = false;
            if (VarInt.getVarIntValue(encoded, 0) != value) roundTripOk = false;
        }
        T.yes("getVarIntSize agrees with putVarIntValue for " + values.length + " values", sizesOk);
        T.yes("encode -> decode round-trip for " + values.length + " values", roundTripOk);

        T.hex("0 encodes to one byte", "00", VarInt.putVarIntValue(0));
        T.hex("127 encodes to one byte", "7F", VarInt.putVarIntValue(0x7F));
        T.hex("128 needs two bytes", "81 00", VarInt.putVarIntValue(0x80));
        T.hex("200 needs two bytes", "81 48", VarInt.putVarIntValue(200));
        T.hex("16383 needs two bytes", "FF 7F", VarInt.putVarIntValue(0x3FFF));
        T.hex("16384 needs three bytes", "81 80 00", VarInt.putVarIntValue(0x4000));

        T.eq("getVarIntSize(0)", 1, VarInt.getVarIntSize(0));
        T.eq("getVarIntSize(127)", 1, VarInt.getVarIntSize(127));
        T.eq("getVarIntSize(128)", 2, VarInt.getVarIntSize(128));
        T.eq("getVarIntSize(16383)", 2, VarInt.getVarIntSize(16383));
        T.eq("getVarIntSize(16384)", 3, VarInt.getVarIntSize(16384));
        T.eq("getVarIntSize(2097151)", 3, VarInt.getVarIntSize(2097151));
        T.eq("getVarIntSize(2097152)", 4, VarInt.getVarIntSize(2097152));
    }
}
