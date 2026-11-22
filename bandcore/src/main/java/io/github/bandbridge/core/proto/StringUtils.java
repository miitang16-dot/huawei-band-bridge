/*  Copyright (C) 2017-2024 Andreas Shimokawa, Arjan Schrijver, Carsten
    Pfeiffer, Daniel Dakhno, Daniele Gobbetti, João Paulo Barraca, José Rebelo,
    Nephiel, Roi Greenberg, Taavi Eomäe, Zhong Jianxin

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */

/*
 * Trimmed to only the helpers referenced by the ported Huawei protocol code:
 *   - bytesToHex  (was GB.hexdump, needed by HuaweiTLV#toString/VarInt#toString)
 *   - hexToBytes  (was GB.hexStringToByteArray, needed by packets.DeviceConfig HiChain JSON)
 * Everything else from GB's StringUtils was dropped on purpose. */
package io.github.bandbridge.core.proto;

public class StringUtils {

    public static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    /** Ported from GB.hexdump(byte[]) - uppercase hex, no separators, "(null)" for null. */
    public static String bytesToHex(byte[] buffer) {
        if (buffer == null) {
            return "(null)";
        }
        char[] hexChars = new char[buffer.length * 2];
        for (int i = 0; i < buffer.length; i++) {
            int v = buffer[i] & 0xFF;
            hexChars[i * 2] = HEX_CHARS[v >>> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hexChars);
    }

    /** Ported from GB.hexStringToByteArray(CharSequence). */
    public static byte[] hexToBytes(CharSequence s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
}
