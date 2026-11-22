/*  Copyright (C) 2015-2025 Andreas Shimokawa, Carsten Pfeiffer, Damien
    Gaignon, Daniele Gobbetti, José Rebelo, Petr Vaněk, vappster

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
 * Trimmed to the only checksum used by HuaweiPacket: CRC16 (both overloads).
 * Dropped on purpose: getCRC8, getCRC16ansi, getCRC32 (x2), crc16_ccitt,
 * crc16_maxim, md5 (x3), main/readAll (file/Android Context/Uri helpers). */
package io.github.bandbridge.core.proto;

public class CheckSums {

    //thanks http://stackoverflow.com/questions/13209364/convert-c-crc16-to-java-crc16
    public static int getCRC16(byte[] seq) {
        return getCRC16(seq, 0xFFFF);
    }

    public static int getCRC16(byte[] seq, int crc) {
        for (byte b : seq) {
            crc = ((crc >>> 8) | (crc << 8)) & 0xffff;
            crc ^= (b & 0xff);//byte to int, trunc sign
            crc ^= ((crc & 0xff) >> 4);
            crc ^= (crc << 12) & 0xffff;
            crc ^= ((crc & 0xFF) << 5) & 0xffff;
        }
        crc &= 0xffff;
        return crc;
    }
}
