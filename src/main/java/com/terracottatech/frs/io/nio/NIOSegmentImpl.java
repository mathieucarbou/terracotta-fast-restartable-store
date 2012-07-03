/*
 * All content copyright (c) 2012 Terracotta, Inc., except as may otherwise
 * be noted in a separate copyright notice. All rights reserved.
 */
package com.terracottatech.frs.io.nio;

import com.terracottatech.frs.io.*;
import com.terracottatech.frs.util.ByteBufferUtils;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author mscott
 */
class NIOSegmentImpl {

    static final int FILE_HEADER_SIZE = 42;
    private static final String LOCKED_FILE_ACCESS = "could not obtain file lock";
    private static final short IMPL_NUMBER = 02;
    private final NIOStreamImpl parent;
    private final int segNum;
    private final File src;

 //  for reading and writing
    private FileBuffer buffer;
    private ByteBuffer memoryBuffer;
    private BufferSource bufferSource;
//  for reading 
    private ReadbackStrategy strategy;
//  for writing
    private boolean forWriting = false;
//    private FileLock lock;
    private List<Long> writeJumpList;
    private long lowestMarker;
    private long minMarker;
    private long maxMarker;
    private UUID streamId;
    private static final Logger LOGGER = LoggerFactory.getLogger(IOManager.class);

    NIOSegmentImpl(NIOStreamImpl p, File file) {
        this.parent = p;
        this.src = file;
        this.segNum = NIOSegmentList.convertSegmentNumber(file);
    }

    File getFile() {
        return src;
    }
    
    private FileBuffer createFileBuffer(FileChannel segment, int bufferSize, BufferSource source) throws IOException {
        assert(memoryBuffer == null);
        
        bufferSource = source;
        memoryBuffer = source.getBuffer(bufferSize);
        if (memoryBuffer == null) {
            LOGGER.warn("direct memory unavailable. Allocating on heap.  Fix configuration for more direct memory. request: " + bufferSize);
            memoryBuffer = ByteBuffer.allocate(1024 * 1024);
        }
        
        FileBuffer created = ( parent != null && parent.getBufferBuilder() != null ) 
                ? parent.getBufferBuilder().createBuffer(segment, memoryBuffer) 
                : new FileBuffer(segment, memoryBuffer);
        
        return created;
    }
    
    NIOSegmentImpl openForHeader(BufferSource reader) throws IOException, HeaderException {
        long fileSize = 0;

        FileChannel segment = new FileInputStream(src).getChannel();

        fileSize = segment.size();

        if (fileSize < FILE_HEADER_SIZE) {
            segment.close();
            throw new HeaderException("bad header", this);
        }

        buffer = createFileBuffer(segment, FILE_HEADER_SIZE, reader);

        buffer.read(1);
        readFileHeader(buffer);

        return this;
    }    

    NIOSegmentImpl openForReading(BufferSource reader) throws IOException, HeaderException {
        long fileSize = 0;

        FileChannel segment = new FileInputStream(src).getChannel();

        fileSize = segment.size();

        if (fileSize < FILE_HEADER_SIZE) {
            throw new HeaderException("bad header", this);
        }
        
        if ( buffer != null ) {
            buffer.close();
            buffer = null;
        }

        MappedByteBuffer buf = segment.map(FileChannel.MapMode.READ_ONLY,0,(int)src.length());
        buf.load();
        readFileHeader(new WrappingChunk(buf));
        strategy = new MappedReadbackStrategy(buf,Direction.REVERSE);
        
        return this;
    }
    
    String getStrategyDebug() {
        if ( strategy == null ) return "";
        return strategy.getClass().getName();
    }

    private void readFileHeader(Chunk readBuffer) throws IOException, HeaderException {
        if ( readBuffer.remaining() < FILE_HEADER_SIZE ) {
            throw new IOException("file buffering size too small");
        }
                
        byte[] code = new byte[4];
        if ( readBuffer.get(code) != 4 ) {
            throw new HeaderException("empty file", this);
        }
        if (!SegmentHeaders.LOG_FILE.validate(code)) {
            throw new HeaderException("file header is corrupted " + new String(code), this);
        }
        short impl = readBuffer.getShort();
        int checkSeg = readBuffer.getInt();
        if (segNum != checkSeg) {
            throw new HeaderException("the filename does not match the internal file structure", this);
        }

        if (impl != IMPL_NUMBER) {
            throw new HeaderException("unknown implementation number", this);
        }

        streamId = new UUID(readBuffer.getLong(), readBuffer.getLong());
        lowestMarker = readBuffer.getLong();
        minMarker = readBuffer.getLong();
    }
    
    

    //  open and write the header.
    NIOSegmentImpl openForWriting(BufferSource pool) throws IOException {
        if (src.length() > 0) {
            throw new IOException("bad access");
        }

        FileChannel segment = new FileOutputStream(src).getChannel();
        forWriting = true;
        
        while ( buffer == null ) {
            buffer = createFileBuffer(segment, 512 * 1024, pool);
        } 

        writeJumpList = new ArrayList<Long>();

        return this;
    }

    void insertFileHeader(long lowestMarker, long marker) throws IOException {
        this.lowestMarker = lowestMarker;
        this.minMarker = marker;
        
        if ( lowestMarker < 99 || marker < 99 ) {
            throw new AssertionError("bad markers");
        }
        
        this.streamId = parent.getStreamId();

        buffer.clear();

        buffer.put(SegmentHeaders.LOG_FILE.getBytes());
        buffer.putShort(IMPL_NUMBER);
        buffer.putInt(segNum);
        buffer.putLong(streamId.getMostSignificantBits());
        buffer.putLong(streamId.getLeastSignificantBits());
        buffer.putLong(this.lowestMarker);
        buffer.putLong(this.minMarker);

        buffer.write(1);
    }
    
    private long piggybackBufferOptimization(ByteBuffer used) throws IOException {
        long amt = used.remaining();
        int estart = used.limit();
        int position = used.position() - (ByteBufferUtils.LONG_SIZE + ByteBufferUtils.INT_SIZE);
//  expand buffer
        used.position(position);
        used.limit(estart + (2*ByteBufferUtils.LONG_SIZE) + ByteBufferUtils.INT_SIZE);
//  place header            
        used.putInt(position,SegmentHeaders.CHUNK_START.getIntValue());
        used.putLong(position + ByteBufferUtils.INT_SIZE,amt);
//  place footer          
        used.putLong(estart,amt);
        used.putLong(estart + ByteBufferUtils.LONG_SIZE,maxMarker);
        used.putInt(estart + (2*ByteBufferUtils.LONG_SIZE),SegmentHeaders.FILE_CHUNK.getIntValue());
//   write it all out
        amt = buffer.writeFully(used);
        
        writeJumpList.add(buffer.offset());
        return amt;
    }

    public long append(Chunk c, long maxMarker) throws IOException {
        int writeCount = 0;
        buffer.clear();
        this.maxMarker = maxMarker;
        ByteBuffer[] raw = c.getBuffers();
        if (
//  very specfic optimization to write out buffers as quickly as possible by using extra space in 
//    passed in buffer creating one large write rather than small header writes
            raw.length == 1 && !raw[0].isReadOnly() && raw[0].isDirect() &&
            raw[0].position() > ByteBufferUtils.LONG_SIZE + ByteBufferUtils.INT_SIZE &&    //  header is a long size and an int chunk marker
            raw[0].capacity() - raw[0].limit() > (2*ByteBufferUtils.LONG_SIZE) + ByteBufferUtils.INT_SIZE //  footer is a long size for marker long for size and an int chunk marker
        ) {
            return piggybackBufferOptimization(raw[0]);
        } else {
            buffer.clear();
            buffer.partition(ByteBufferUtils.LONG_SIZE + ByteBufferUtils.INT_SIZE);
            long amt = c.remaining();
            buffer.put(SegmentHeaders.CHUNK_START.getBytes());
            buffer.putLong(amt);
            buffer.insert(raw, 1, false);
            buffer.putLong(amt);
            buffer.putLong(maxMarker);
            buffer.put(SegmentHeaders.FILE_CHUNK.getBytes());
            writeCount = raw.length + 2;
            try {
                return buffer.write(writeCount);
            } finally {
                writeJumpList.add(buffer.offset());
            }
        }

    }
    
    void prepareForClose() throws IOException {
        if (buffer != null) {
            buffer.clear();
            buffer.put(SegmentHeaders.CLOSE_FILE.getBytes());
            writeJumpList(buffer);
            buffer.write(1);
        }
    }

    long close() throws IOException {
        
        long totalWrite = 0;
        
 //  don't need any memory buffers anymore       
        if ( bufferSource != null ) {
            bufferSource.returnBuffer(memoryBuffer);
            bufferSource = null;
            memoryBuffer = null;
        }        
        
        if (buffer == null || !buffer.isOpen()) {
            return 0;
        }
        
        if ( forWriting ) {
           totalWrite = buffer.getTotal();
           buffer.sync();
        }
        buffer.close();
        buffer = null;
        
        return totalWrite;
    }
    
    private void writeJumpList(FileBuffer target) throws IOException {
        target.clear();
        target.put(SegmentHeaders.CLOSE_FILE.getBytes());
        for (long jump : writeJumpList) {
            if (target.remaining() < ByteBufferUtils.LONG_SIZE
                    + ByteBufferUtils.SHORT_SIZE
                    + ByteBufferUtils.INT_SIZE) {
                target.write(1);
                target.clear();
            }
            target.putLong(jump);
        }
        if (writeJumpList.size() < Short.MAX_VALUE) {
            target.putShort((short) writeJumpList.size());
        } else {
            target.putShort((short) -1);
        }
        target.put(SegmentHeaders.JUMP_LIST.getBytes());
    }
//  assume single threaded

    public long fsync() throws IOException {
        long pos = buffer.offset();
        buffer.sync();
        return pos;
    }

    public int getSegmentId() {
        return segNum;
    }

    UUID getStreamId() {
        return streamId;
    }

    long getBaseMarker() {
        return minMarker;
    }

    long getMinimumMarker() {
        return lowestMarker;
    }

    long getMaximumMarker() {
        return maxMarker;
    }

    public boolean isClosed() {
        return (buffer == null);
    }

    public boolean wasProperlyClosed() throws IOException {
        if (strategy != null && strategy.isConsistent()) {
//  close file magic was seen as part of read back scan
            return true;
        } else {
// do it the hard way
            if (buffer.size() < FILE_HEADER_SIZE + ByteBufferUtils.INT_SIZE) {
                return false;
            }
            buffer.clear();
            buffer.position(buffer.size() - buffer.capacity()).read(1);
            int fileEnd = buffer.getInt(buffer.remaining() - ByteBufferUtils.INT_SIZE);
            if (SegmentHeaders.CLOSE_FILE.validate(fileEnd)) {
                return true;
            }
            if (SegmentHeaders.JUMP_LIST.validate(fileEnd)) {
                return true;
            }

            return false;
        }
    }

    public Chunk next(Direction dir) throws IOException {
        if (strategy.hasMore(dir)) {
            return strategy.iterate(dir);
        }
        throw new IOException("segment bounds");
    }

    public boolean hasMore(Direction dir) throws IOException {
        return strategy.hasMore(dir);
    }

    public long length() throws IOException {
        return src.length();
    }

    public long position() throws IOException {
        return ( buffer == null ) ? 0 : buffer.position();
    }

    public void limit(long pos) throws IOException {
        buffer.clear();
        buffer.position(pos - ByteBufferUtils.INT_SIZE);
        buffer.partition(4);
        buffer.read(1);
        byte[] code = new byte[4];
        buffer.get(code);
        if (!SegmentHeaders.FILE_CHUNK.validate(code)) {
            throw new IOException("bad truncation " + new String(code));
        }
        
        FileChannel fc = new FileOutputStream(src,true).getChannel();
        fc.truncate(pos);
        fc.position(pos);
        ByteBuffer close = ByteBuffer.allocate(4096);
        FileBuffer target = ( parent.getBufferBuilder() != null ) 
                ? parent.getBufferBuilder().createBuffer(fc, close) 
                : new FileBuffer(fc, close);
        target.put(SegmentHeaders.CLOSE_FILE.getBytes());
        writeJumpList(target);
        target.write(1);
        fc.force(true);
        fc.close();
    }

    boolean last() throws IOException {
        IntegrityReadbackStrategy find = new IntegrityReadbackStrategy(buffer);
        int count = 0;
        try {
            while (find.hasMore(Direction.FORWARD)) {
                try {
                    find.iterate(Direction.FORWARD);
                    count += 1;
                } catch (IOException ioe) {
                    return false;
                }
            }
        } finally {
            buffer.clear();
            maxMarker = find.getLastValidMarker();           
            buffer.position(find.getLastValidPosition());
            writeJumpList = find.getJumpList();
        }
        
        if ( count == 0 ) {
            return false;
        }
        
        if ( !find.wasClosed() ) {
            this.limit(this.position());
        }
        
        return true;
    }
}
