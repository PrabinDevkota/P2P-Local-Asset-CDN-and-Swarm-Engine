package com.prabin.swarmedge.manifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Turns a file into ordered integrity chunks. Fixed and FastCDC both return
 * {@link ChunkEntry} rows, which is the only shape the transfer path reads.
 */
public interface Chunker {

    ChunkingSpec spec();

    List<ChunkEntry> chunk(Path file) throws IOException;
}
