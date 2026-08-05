package io.canvasmc.canvas.fakechunks.disk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public final class RegionFileReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionFileReader.class);

    private static final int SECTOR_SIZE = 4096;
    private static final int HEADER_SECTORS = 2;
    private static final int HEADER_SIZE = SECTOR_SIZE * HEADER_SECTORS;
    private static final int LOCATION_ENTRY_SIZE = 4;

    private static final int COMPRESSION_GZIP = 1;
    private static final int COMPRESSION_ZLIB = 2;
    private static final int COMPRESSION_NONE = 3;

    private static final int DECOMPRESSION_BUFFER_SIZE = 8192;
    private static final int CHUNK_HEADER_BYTES = 5;
    private static final int REGION_COORD_SHIFT = 5;
    private static final int REGION_LOCAL_MASK = 31;
    private static final int REGION_DIMENSION = 32;
    private static final int SECTOR_OFFSET_SHIFT = 8;
    private static final int SECTOR_COUNT_MASK = 0xFF;
    private static final int MIN_SECTOR_OFFSET = 2;

    private static final ThreadLocal<ByteBuffer> LOCATION_BUF =
        ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(LOCATION_ENTRY_SIZE));
    private static final ThreadLocal<ByteBuffer> CHUNK_HEADER_BUF =
        ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(CHUNK_HEADER_BYTES));
    private static final ThreadLocal<byte[]> DECOMPRESSION_BUF =
        ThreadLocal.withInitial(() -> new byte[DECOMPRESSION_BUFFER_SIZE]);

    private static final Map<String, FileChannel> CHANNEL_CACHE = new ConcurrentHashMap<>();

    private RegionFileReader() {}

    public static byte[] readChunkBytes(File worldFolder, int chunkX, int chunkZ) {
        int regionX = chunkX >> REGION_COORD_SHIFT;
        int regionZ = chunkZ >> REGION_COORD_SHIFT;

        File regionFolder = new File(worldFolder, "region");
        File regionFile = new File(regionFolder, "r." + regionX + "." + regionZ + ".mca");

        if (!regionFile.exists()) {
            return null;
        }

        int localX = chunkX & REGION_LOCAL_MASK;
        int localZ = chunkZ & REGION_LOCAL_MASK;
        int locationIndex = (localX + localZ * REGION_DIMENSION) * LOCATION_ENTRY_SIZE;

        FileChannel channel = CHANNEL_CACHE.computeIfAbsent(regionFile.getAbsolutePath(), path -> {
            try {
                return FileChannel.open(regionFile.toPath(), StandardOpenOption.READ);
            } catch (IOException e) {
                LOGGER.error("Failed to open FileChannel for region file {}: {}", path, e.getMessage());
                return null;
            }
        });

        if (channel == null) {
            return null;
        }

        try {
            long fileLength = channel.size();
            if (fileLength < HEADER_SIZE) {
                return null;
            }

            ByteBuffer buf = LOCATION_BUF.get();
            buf.clear();
            int bytesRead = channel.read(buf, locationIndex);
            if (bytesRead < 4) {
                return null;
            }
            buf.flip();
            int locationValue = buf.getInt();
            if (locationValue == 0) {
                return null;
            }

            int sectorOffset = (locationValue >> SECTOR_OFFSET_SHIFT) & 0xFFFFFF;
            int sectorCount = locationValue & SECTOR_COUNT_MASK;

            if (sectorOffset < MIN_SECTOR_OFFSET) {
                return null;
            }

            long dataStart = (long) sectorOffset * SECTOR_SIZE;
            if (dataStart >= fileLength) {
                return null;
            }

            ByteBuffer headerBuf = CHUNK_HEADER_BUF.get();
            headerBuf.clear();
            int headerRead = channel.read(headerBuf, dataStart);
            if (headerRead < CHUNK_HEADER_BYTES) {
                return null;
            }
            headerBuf.flip();
            int dataLength = headerBuf.getInt();
            int compressionType = headerBuf.get() & 0xFF;

            if (dataLength <= 0) {
                return null;
            }

            int compressedLength = dataLength - 1;
            if (compressedLength <= 0) {
                return null;
            }

            ByteBuffer dataBuf = ByteBuffer.allocate(compressedLength);
            int dataRead = channel.read(dataBuf, dataStart + CHUNK_HEADER_BYTES);
            if (dataRead < compressedLength) {
                return null;
            }

            byte[] compressedData = dataBuf.array();
            return decompress(compressedData, compressionType, sectorCount, chunkX, chunkZ, regionFile.getName());
        } catch (java.nio.channels.ClosedChannelException e) {
            CHANNEL_CACHE.remove(regionFile.getAbsolutePath());
            return null;
        } catch (IOException e) {
            CHANNEL_CACHE.remove(regionFile.getAbsolutePath());
            return null;
        }
    }

    public static void clearCache() {
        for (FileChannel ch : CHANNEL_CACHE.values()) {
            if (ch != null) {
                try {
                    ch.close();
                } catch (IOException ignored) {}
            }
        }
        CHANNEL_CACHE.clear();
        LOCATION_BUF.remove();
        CHUNK_HEADER_BUF.remove();
        DECOMPRESSION_BUF.remove();
    }

    private static byte[] decompress(byte[] data, int compressionType, int sectorCount, int chunkX, int chunkZ, String fileName) {
        try {
            return switch (compressionType) {
                case COMPRESSION_GZIP -> decompressGzip(data, sectorCount);
                case COMPRESSION_ZLIB -> decompressZlib(data, sectorCount);
                case COMPRESSION_NONE -> data;
                default -> {
                    LOGGER.warn("Unknown compression type {} for chunk [{}, {}] in {}", compressionType, chunkX, chunkZ, fileName);
                    yield null;
                }
            };
        } catch (IOException e) {
            LOGGER.error("Failed to decompress chunk [{}, {}] from {} (type={}): {}", chunkX, chunkZ, fileName, compressionType, e.getMessage(), e);
            return null;
        }
    }

    private static byte[] decompressGzip(byte[] data, int sectorCount) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readAllBytes(gis, sectorCount);
        }
    }

    private static byte[] decompressZlib(byte[] data, int sectorCount) throws IOException {
        Inflater inflater = new Inflater();
        try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(data), inflater)) {
            return readAllBytes(iis, sectorCount);
        } finally {
            inflater.end();
        }
    }

    private static byte[] readAllBytes(InputStream is, int sectorCount) throws IOException {
        int estimatedSize = Math.max(DECOMPRESSION_BUFFER_SIZE, sectorCount * SECTOR_SIZE);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(estimatedSize);
        byte[] buffer = DECOMPRESSION_BUF.get();
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        return bos.toByteArray();
    }
}
