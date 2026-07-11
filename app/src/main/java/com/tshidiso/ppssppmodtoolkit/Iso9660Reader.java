package com.tshidiso.ppssppmodtoolkit;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Small read-only ISO-9660/Joliet parser used by Phase 1C.
 *
 * <p>The parser only reads directory metadata. File payloads remain in the source image and are
 * streamed by {@link ExtractionEngine}. It deliberately rejects unsafe names, impossible extents,
 * cyclic directory records, and multi-extent files instead of guessing.</p>
 */
public final class Iso9660Reader {
    static final int VOLUME_DESCRIPTOR_SECTOR_SIZE = 2048;
    private static final int FIRST_VOLUME_DESCRIPTOR_SECTOR = 16;
    private static final int MAX_VOLUME_DESCRIPTOR_SECTORS = 64;
    private static final int MAX_ENTRIES = 50_000;
    private static final int MAX_DEPTH = 48;
    private static final long MAX_DIRECTORY_BYTES = 64L * 1024L * 1024L;

    public interface RandomAccessSource extends Closeable {
        long size() throws IOException;

        int read(long position, byte[] buffer, int offset, int length) throws IOException;
    }

    public static final class Entry {
        private final String path;
        private final String name;
        private final long extentBlock;
        private final long size;
        private final boolean directory;

        private Entry(
                String path,
                String name,
                long extentBlock,
                long size,
                boolean directory
        ) {
            this.path = path;
            this.name = name;
            this.extentBlock = extentBlock;
            this.size = size;
            this.directory = directory;
        }

        public String getPath() {
            return path;
        }

        public String getName() {
            return name;
        }

        public long getExtentBlock() {
            return extentBlock;
        }

        public long getSize() {
            return size;
        }

        public boolean isDirectory() {
            return directory;
        }
    }

    public static final class Volume {
        private final String volumeId;
        private final int logicalBlockSize;
        private final boolean joliet;
        private final List<Entry> entries;
        private final long fileBytes;
        private final int fileCount;
        private final int directoryCount;

        private Volume(
                String volumeId,
                int logicalBlockSize,
                boolean joliet,
                List<Entry> entries,
                long fileBytes,
                int fileCount,
                int directoryCount
        ) {
            this.volumeId = volumeId;
            this.logicalBlockSize = logicalBlockSize;
            this.joliet = joliet;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.fileBytes = fileBytes;
            this.fileCount = fileCount;
            this.directoryCount = directoryCount;
        }

        public String getVolumeId() {
            return volumeId;
        }

        public int getLogicalBlockSize() {
            return logicalBlockSize;
        }

        public boolean isJoliet() {
            return joliet;
        }

        public List<Entry> getEntries() {
            return entries;
        }

        public long getFileBytes() {
            return fileBytes;
        }

        public int getFileCount() {
            return fileCount;
        }

        public int getDirectoryCount() {
            return directoryCount;
        }
    }

    private Iso9660Reader() {
    }

    public static Volume read(RandomAccessSource source) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("source == null");
        }

        long sourceSize = source.size();
        long minimumSize = (FIRST_VOLUME_DESCRIPTOR_SECTOR + 1L)
                * VOLUME_DESCRIPTOR_SECTOR_SIZE;
        if (sourceSize < minimumSize) {
            throw new IOException("The selected file is too small to contain an ISO-9660 volume");
        }

        Descriptor primary = null;
        Descriptor joliet = null;
        byte[] sector = new byte[VOLUME_DESCRIPTOR_SECTOR_SIZE];

        for (int index = 0; index < MAX_VOLUME_DESCRIPTOR_SECTORS; index++) {
            long position = (FIRST_VOLUME_DESCRIPTOR_SECTOR + (long) index)
                    * VOLUME_DESCRIPTOR_SECTOR_SIZE;
            if (position + sector.length > sourceSize) {
                break;
            }
            readFully(source, position, sector, 0, sector.length);

            int type = unsignedByte(sector[0]);
            if (!hasStandardIdentifier(sector)) {
                if (index == 0) {
                    throw new IOException("ISO-9660 volume descriptor signature CD001 was not found");
                }
                continue;
            }
            if (unsignedByte(sector[6]) != 1) {
                throw new IOException("Unsupported ISO-9660 volume descriptor version");
            }

            if (type == 1 && primary == null) {
                primary = parseDescriptor(sector, false);
            } else if (type == 2 && isJolietDescriptor(sector)) {
                Descriptor candidate = parseDescriptor(sector, true);
                if (joliet == null || candidate.jolietLevel > joliet.jolietLevel) {
                    joliet = candidate;
                }
            } else if (type == 255) {
                break;
            }
        }

        Descriptor selected = joliet != null ? joliet : primary;
        if (selected == null) {
            throw new IOException("No usable primary or Joliet volume descriptor was found");
        }

        validateExtent(sourceSize, selected.logicalBlockSize,
                selected.root.extentBlock, selected.root.size, "root directory");

        List<Entry> entries = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        Set<String> visitedDirectories = new HashSet<>();
        ArrayDeque<DirectoryNode> queue = new ArrayDeque<>();
        queue.add(new DirectoryNode("", selected.root.extentBlock, selected.root.size, 0));

        long totalFileBytes = 0L;
        int fileCount = 0;
        int directoryCount = 0;

        while (!queue.isEmpty()) {
            DirectoryNode directory = queue.removeFirst();
            if (directory.depth > MAX_DEPTH) {
                throw new IOException("ISO directory nesting exceeds the safe depth limit");
            }

            String directoryKey = directory.extentBlock + ":" + directory.size;
            if (!visitedDirectories.add(directoryKey)) {
                continue;
            }
            if (directory.size < 0L || directory.size > MAX_DIRECTORY_BYTES) {
                throw new IOException("An ISO directory record is larger than the safe limit");
            }
            validateExtent(sourceSize, selected.logicalBlockSize,
                    directory.extentBlock, directory.size, "directory " + directory.path);

            byte[] bytes = new byte[(int) directory.size];
            long directoryOffset = safeMultiply(directory.extentBlock, selected.logicalBlockSize);
            readFully(source, directoryOffset, bytes, 0, bytes.length);

            int offset = 0;
            while (offset < bytes.length) {
                int recordLength = unsignedByte(bytes[offset]);
                if (recordLength == 0) {
                    int nextBlock = ((offset / selected.logicalBlockSize) + 1)
                            * selected.logicalBlockSize;
                    if (nextBlock <= offset) {
                        break;
                    }
                    offset = Math.min(nextBlock, bytes.length);
                    continue;
                }
                if (recordLength < 34 || offset + recordLength > bytes.length) {
                    throw new IOException("Malformed ISO directory record at byte " + offset);
                }

                DirectoryRecord record = parseDirectoryRecord(
                        bytes,
                        offset,
                        recordLength,
                        selected.joliet
                );
                offset += recordLength;

                if (record.specialEntry) {
                    continue;
                }
                if (record.multiExtent) {
                    throw new IOException(
                            "Multi-extent ISO files are not supported safely: " + record.name
                    );
                }

                String path = directory.path.isEmpty()
                        ? record.name
                        : directory.path + "/" + record.name;
                if (!paths.add(path.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Duplicate ISO path: " + path);
                }
                if (entries.size() >= MAX_ENTRIES) {
                    throw new IOException("ISO entry count exceeds the safe limit of " + MAX_ENTRIES);
                }

                validateExtent(sourceSize, selected.logicalBlockSize,
                        record.extentBlock, record.size, path);
                Entry entry = new Entry(
                        path,
                        record.name,
                        record.extentBlock,
                        record.size,
                        record.directory
                );
                entries.add(entry);

                if (record.directory) {
                    directoryCount++;
                    queue.addLast(new DirectoryNode(
                            path,
                            record.extentBlock,
                            record.size,
                            directory.depth + 1
                    ));
                } else {
                    fileCount++;
                    totalFileBytes = safeAdd(totalFileBytes, record.size);
                }
            }
        }

        entries.sort(Comparator
                .comparingInt((Entry entry) -> pathDepth(entry.path))
                .thenComparing(entry -> entry.path.toLowerCase(Locale.ROOT))
                .thenComparing(entry -> entry.path));

        return new Volume(
                selected.volumeId,
                selected.logicalBlockSize,
                selected.joliet,
                entries,
                totalFileBytes,
                fileCount,
                directoryCount
        );
    }

    static void readFully(
            RandomAccessSource source,
            long position,
            byte[] buffer,
            int offset,
            int length
    ) throws IOException {
        if (position < 0L || offset < 0 || length < 0 || offset + length > buffer.length) {
            throw new IndexOutOfBoundsException("Invalid read range");
        }
        int completed = 0;
        while (completed < length) {
            int read = source.read(
                    position + completed,
                    buffer,
                    offset + completed,
                    length - completed
            );
            if (read < 0) {
                throw new EOFException("Unexpected end of ISO image");
            }
            if (read == 0) {
                throw new IOException("ISO source returned zero bytes during a required read");
            }
            completed += read;
        }
    }

    private static Descriptor parseDescriptor(byte[] descriptor, boolean joliet)
            throws IOException {
        int logicalBlockSize = littleEndianUnsignedShort(descriptor, 128);
        if (logicalBlockSize < 512 || logicalBlockSize > 32_768) {
            throw new IOException("Invalid ISO logical block size: " + logicalBlockSize);
        }

        DirectoryRecord root = parseDirectoryRecord(
                descriptor,
                156,
                unsignedByte(descriptor[156]),
                joliet
        );
        if (!root.directory) {
            throw new IOException("ISO root directory record is not marked as a directory");
        }

        String volumeId = joliet
                ? decodeJoliet(descriptor, 40, 32)
                : decodeAscii(descriptor, 40, 32);
        if (volumeId.isEmpty()) {
            volumeId = "UNNAMED_ISO";
        }

        return new Descriptor(
                volumeId,
                logicalBlockSize,
                joliet,
                joliet ? jolietLevel(descriptor) : 0,
                root
        );
    }

    private static DirectoryRecord parseDirectoryRecord(
            byte[] bytes,
            int offset,
            int recordLength,
            boolean joliet
    ) throws IOException {
        if (recordLength < 34 || offset < 0 || offset + recordLength > bytes.length) {
            throw new IOException("Invalid ISO directory record length");
        }

        long extentBlock = littleEndianUnsignedInt(bytes, offset + 2);
        long size = littleEndianUnsignedInt(bytes, offset + 10);
        int flags = unsignedByte(bytes[offset + 25]);
        int identifierLength = unsignedByte(bytes[offset + 32]);
        int identifierOffset = offset + 33;
        if (identifierOffset + identifierLength > offset + recordLength) {
            throw new IOException("ISO directory identifier exceeds its record");
        }

        boolean special = identifierLength == 1
                && (bytes[identifierOffset] == 0 || bytes[identifierOffset] == 1);
        String name = special
                ? ""
                : decodeIdentifier(bytes, identifierOffset, identifierLength, joliet);
        if (!special) {
            name = stripVersion(name);
            validateName(name);
        }

        return new DirectoryRecord(
                name,
                extentBlock,
                size,
                (flags & 0x02) != 0,
                (flags & 0x80) != 0,
                special
        );
    }

    private static String decodeIdentifier(
            byte[] bytes,
            int offset,
            int length,
            boolean joliet
    ) {
        Charset charset = joliet ? StandardCharsets.UTF_16BE : StandardCharsets.ISO_8859_1;
        int safeLength = joliet && (length & 1) != 0 ? length - 1 : length;
        return new String(bytes, offset, safeLength, charset).replace("\u0000", "").trim();
    }

    private static String decodeJoliet(byte[] bytes, int offset, int length) {
        int safeLength = (length & 1) == 0 ? length : length - 1;
        return new String(bytes, offset, safeLength, StandardCharsets.UTF_16BE)
                .replace("\u0000", "")
                .trim();
    }

    private static String decodeAscii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.ISO_8859_1)
                .replace("\u0000", "")
                .trim();
    }

    private static String stripVersion(String name) {
        int semicolon = name.lastIndexOf(';');
        if (semicolon >= 0 && semicolon < name.length() - 1) {
            boolean digits = true;
            for (int index = semicolon + 1; index < name.length(); index++) {
                if (!Character.isDigit(name.charAt(index))) {
                    digits = false;
                    break;
                }
            }
            if (digits) {
                name = name.substring(0, semicolon);
            }
        }
        if (name.endsWith(".") && name.length() > 1) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    private static void validateName(String name) throws IOException {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new IOException("ISO contains an empty or unsafe path segment");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IOException("ISO path segment contains a slash: " + name);
        }
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (value == 0 || Character.isISOControl(value)) {
                throw new IOException("ISO path segment contains a control character");
            }
        }
    }

    private static void validateExtent(
            long sourceSize,
            int logicalBlockSize,
            long extentBlock,
            long dataSize,
            String label
    ) throws IOException {
        if (extentBlock < 0L || dataSize < 0L) {
            throw new IOException("Negative ISO extent for " + label);
        }
        long start = safeMultiply(extentBlock, logicalBlockSize);
        long end = safeAdd(start, dataSize);
        if (start < 0L || end < start || end > sourceSize) {
            throw new IOException("ISO extent points outside the source image: " + label);
        }
    }

    private static boolean hasStandardIdentifier(byte[] sector) {
        return sector.length >= 7
                && sector[1] == 'C'
                && sector[2] == 'D'
                && sector[3] == '0'
                && sector[4] == '0'
                && sector[5] == '1';
    }

    private static boolean isJolietDescriptor(byte[] sector) {
        return sector.length > 90
                && sector[88] == '%'
                && sector[89] == '/'
                && (sector[90] == '@' || sector[90] == 'C' || sector[90] == 'E');
    }

    private static int jolietLevel(byte[] sector) {
        if (!isJolietDescriptor(sector)) {
            return 0;
        }
        if (sector[90] == 'E') {
            return 3;
        }
        if (sector[90] == 'C') {
            return 2;
        }
        return 1;
    }

    private static int littleEndianUnsignedShort(byte[] bytes, int offset) {
        return unsignedByte(bytes[offset]) | (unsignedByte(bytes[offset + 1]) << 8);
    }

    private static long littleEndianUnsignedInt(byte[] bytes, int offset) {
        return (long) unsignedByte(bytes[offset])
                | ((long) unsignedByte(bytes[offset + 1]) << 8)
                | ((long) unsignedByte(bytes[offset + 2]) << 16)
                | ((long) unsignedByte(bytes[offset + 3]) << 24);
    }

    private static int unsignedByte(byte value) {
        return value & 0xff;
    }

    private static int pathDepth(String path) {
        int depth = 0;
        for (int index = 0; index < path.length(); index++) {
            if (path.charAt(index) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private static long safeAdd(long left, long right) throws IOException {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
            throw new IOException("ISO size arithmetic overflow");
        }
        return left + right;
    }

    private static long safeMultiply(long left, long right) throws IOException {
        if (left < 0L || right < 0L || (right != 0L && left > Long.MAX_VALUE / right)) {
            throw new IOException("ISO offset arithmetic overflow");
        }
        return left * right;
    }

    private static final class Descriptor {
        private final String volumeId;
        private final int logicalBlockSize;
        private final boolean joliet;
        private final int jolietLevel;
        private final DirectoryRecord root;

        private Descriptor(
                String volumeId,
                int logicalBlockSize,
                boolean joliet,
                int jolietLevel,
                DirectoryRecord root
        ) {
            this.volumeId = volumeId;
            this.logicalBlockSize = logicalBlockSize;
            this.joliet = joliet;
            this.jolietLevel = jolietLevel;
            this.root = root;
        }
    }

    private static final class DirectoryRecord {
        private final String name;
        private final long extentBlock;
        private final long size;
        private final boolean directory;
        private final boolean multiExtent;
        private final boolean specialEntry;

        private DirectoryRecord(
                String name,
                long extentBlock,
                long size,
                boolean directory,
                boolean multiExtent,
                boolean specialEntry
        ) {
            this.name = name;
            this.extentBlock = extentBlock;
            this.size = size;
            this.directory = directory;
            this.multiExtent = multiExtent;
            this.specialEntry = specialEntry;
        }
    }

    private static final class DirectoryNode {
        private final String path;
        private final long extentBlock;
        private final long size;
        private final int depth;

        private DirectoryNode(String path, long extentBlock, long size, int depth) {
            this.path = path;
            this.extentBlock = extentBlock;
            this.size = size;
            this.depth = depth;
        }
    }
}
