package com.tshidiso.ppssppmodtoolkit;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class Iso9660ReaderTest {
    private static final int SECTOR = 2048;

    @Test
    public void parsesNestedIsoAndStripsVersionSuffixes() throws Exception {
        byte[] iso = createTestIso();
        Iso9660Reader.Volume volume = Iso9660Reader.read(new ByteArraySource(iso));

        assertEquals("FIFA14_TEST", volume.getVolumeId());
        assertEquals(SECTOR, volume.getLogicalBlockSize());
        assertFalse(volume.isJoliet());
        assertEquals(4, volume.getFileCount());
        assertEquals(2, volume.getDirectoryCount());
        assertEquals(16L, volume.getFileBytes());

        Map<String, Iso9660Reader.Entry> entries = new HashMap<>();
        for (Iso9660Reader.Entry entry : volume.getEntries()) {
            entries.put(entry.getPath(), entry);
        }

        assertTrue(entries.get("PSP_GAME").isDirectory());
        assertTrue(entries.get("PSP_GAME/USRDIR").isDirectory());
        assertEquals(3L, entries.get("PSP_GAME/USRDIR/fifa.db").getSize());
        assertEquals(4L, entries.get("PSP_GAME/USRDIR/data.big").getSize());
        assertEquals(32L, entries.get("PSP_GAME/USRDIR/fifa.db").getExtentBlock());
    }


    @Test
    public void prefersJolietForLongUnicodeNames() throws Exception {
        byte[] iso = createJolietIso();
        Iso9660Reader.Volume volume = Iso9660Reader.read(new ByteArraySource(iso));

        assertTrue(volume.isJoliet());
        assertEquals("JOLIET_TEST", volume.getVolumeId());
        assertEquals(1, volume.getFileCount());
        assertEquals("Long File Name.big", volume.getEntries().get(0).getPath());
    }

    @Test
    public void rejectsImageWithoutCd001Descriptor() throws Exception {
        byte[] invalid = new byte[40 * SECTOR];
        try {
            Iso9660Reader.read(new ByteArraySource(invalid));
            fail("Expected invalid ISO to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("CD001"));
        }
    }

    @Test
    public void randomAccessReadFullyRejectsUnexpectedEnd() throws Exception {
        byte[] data = new byte[]{1, 2, 3};
        byte[] target = new byte[4];
        try {
            Iso9660Reader.readFully(new ByteArraySource(data), 0L, target, 0, 4);
            fail("Expected unexpected end");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unexpected end"));
        }
    }


    private static byte[] createJolietIso() {
        byte[] iso = createTestIso();

        int svd = 17 * SECTOR;
        iso[svd] = 2;
        putAscii(iso, svd + 1, "CD001", 5);
        iso[svd + 6] = 1;
        iso[svd + 88] = '%';
        iso[svd + 89] = '/';
        iso[svd + 90] = 'E';
        putUtf16(iso, svd + 40, "JOLIET_TEST", 32);
        putBothEndianInt(iso, svd + 80, 40);
        putBothEndianShort(iso, svd + 128, SECTOR);
        byte[] jolietRoot = directoryRecord(24, SECTOR, true, new byte[]{0});
        System.arraycopy(jolietRoot, 0, iso, svd + 156, jolietRoot.length);

        int terminator = 18 * SECTOR;
        iso[terminator] = (byte) 255;
        putAscii(iso, terminator + 1, "CD001", 5);
        iso[terminator + 6] = 1;

        int root = 24 * SECTOR;
        int offset = root;
        offset = appendRecord(iso, offset, directoryRecord(24, SECTOR, true, new byte[]{0}));
        offset = appendRecord(iso, offset, directoryRecord(24, SECTOR, true, new byte[]{1}));
        byte[] longName = "Long File Name.big;1".getBytes(StandardCharsets.UTF_16BE);
        appendRecord(iso, offset, directoryRecord(34, 6, false, longName));
        putAscii(iso, 34 * SECTOR, "JOLIET", 6);
        return iso;
    }

    private static byte[] createTestIso() {
        byte[] iso = new byte[40 * SECTOR];

        int pvd = 16 * SECTOR;
        iso[pvd] = 1;
        putAscii(iso, pvd + 1, "CD001", 5);
        iso[pvd + 6] = 1;
        putAscii(iso, pvd + 40, "FIFA14_TEST", 32);
        putBothEndianInt(iso, pvd + 80, 40);
        putBothEndianShort(iso, pvd + 128, SECTOR);
        byte[] rootRecord = directoryRecord(20, SECTOR, true, new byte[]{0});
        System.arraycopy(rootRecord, 0, iso, pvd + 156, rootRecord.length);

        int terminator = 17 * SECTOR;
        iso[terminator] = (byte) 255;
        putAscii(iso, terminator + 1, "CD001", 5);
        iso[terminator + 6] = 1;

        int root = 20 * SECTOR;
        int offset = root;
        offset = appendRecord(iso, offset, directoryRecord(20, SECTOR, true, new byte[]{0}));
        offset = appendRecord(iso, offset, directoryRecord(20, SECTOR, true, new byte[]{1}));
        offset = appendRecord(iso, offset, directoryRecord(
                21,
                SECTOR,
                true,
                "PSP_GAME".getBytes(StandardCharsets.ISO_8859_1)
        ));
        appendRecord(iso, offset, directoryRecord(
                30,
                4,
                false,
                "EBOOT.BIN;1".getBytes(StandardCharsets.ISO_8859_1)
        ));

        int pspGame = 21 * SECTOR;
        offset = pspGame;
        offset = appendRecord(iso, offset, directoryRecord(21, SECTOR, true, new byte[]{0}));
        offset = appendRecord(iso, offset, directoryRecord(20, SECTOR, true, new byte[]{1}));
        offset = appendRecord(iso, offset, directoryRecord(
                22,
                SECTOR,
                true,
                "USRDIR".getBytes(StandardCharsets.ISO_8859_1)
        ));
        appendRecord(iso, offset, directoryRecord(
                31,
                5,
                false,
                "PARAM.SFO;1".getBytes(StandardCharsets.ISO_8859_1)
        ));

        int usrdir = 22 * SECTOR;
        offset = usrdir;
        offset = appendRecord(iso, offset, directoryRecord(22, SECTOR, true, new byte[]{0}));
        offset = appendRecord(iso, offset, directoryRecord(21, SECTOR, true, new byte[]{1}));
        offset = appendRecord(iso, offset, directoryRecord(
                32,
                3,
                false,
                "fifa.db;1".getBytes(StandardCharsets.ISO_8859_1)
        ));
        appendRecord(iso, offset, directoryRecord(
                33,
                4,
                false,
                "data.big;1".getBytes(StandardCharsets.ISO_8859_1)
        ));

        putAscii(iso, 30 * SECTOR, "BOOT", 4);
        putAscii(iso, 31 * SECTOR, "PARAM", 5);
        putAscii(iso, 32 * SECTOR, "DB!", 3);
        putAscii(iso, 33 * SECTOR, "BIG!", 4);
        return iso;
    }

    private static byte[] directoryRecord(
            int extent,
            int size,
            boolean directory,
            byte[] identifier
    ) {
        int length = 33 + identifier.length;
        if ((length & 1) != 0) {
            length++;
        }
        byte[] record = new byte[length];
        record[0] = (byte) length;
        putBothEndianInt(record, 2, extent);
        putBothEndianInt(record, 10, size);
        record[25] = directory ? (byte) 0x02 : 0;
        putBothEndianShort(record, 28, 1);
        record[32] = (byte) identifier.length;
        System.arraycopy(identifier, 0, record, 33, identifier.length);
        return record;
    }

    private static int appendRecord(byte[] target, int offset, byte[] record) {
        System.arraycopy(record, 0, target, offset, record.length);
        return offset + record.length;
    }


    private static void putUtf16(byte[] target, int offset, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_16BE);
        int copied = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, target, offset, copied);
        for (int index = copied; index < length; index += 2) {
            target[offset + index] = 0;
            if (index + 1 < length) {
                target[offset + index + 1] = ' ';
            }
        }
    }

    private static void putAscii(byte[] target, int offset, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        int copied = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, target, offset, copied);
        for (int index = copied; index < length; index++) {
            target[offset + index] = ' ';
        }
    }

    private static void putBothEndianShort(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static void putBothEndianInt(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
        target[offset + 4] = (byte) (value >>> 24);
        target[offset + 5] = (byte) (value >>> 16);
        target[offset + 6] = (byte) (value >>> 8);
        target[offset + 7] = (byte) value;
    }

    private static final class ByteArraySource implements Iso9660Reader.RandomAccessSource {
        private final byte[] data;

        private ByteArraySource(byte[] data) {
            this.data = data;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            int count = (int) Math.min(length, data.length - position);
            System.arraycopy(data, (int) position, buffer, offset, count);
            return count;
        }

        @Override
        public void close() {
            // Nothing to close.
        }
    }
}
