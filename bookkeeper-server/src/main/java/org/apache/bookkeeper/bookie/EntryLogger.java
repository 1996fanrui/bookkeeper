/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.bookkeeper.bookie;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.bookkeeper.conf.ServerConfiguration;

/**
 * This class manages the writing of the bookkeeper entries. All the new
 * entries are written to a common log. The LedgerCache will have pointers
 * into files created by this class with offsets into the files to find
 * the actual ledger entry. The entry log files created by this class are
 * identified by a long.
 */
public class EntryLogger {
    private static final Logger LOG = LoggerFactory.getLogger(EntryLogger.class);
    private File dirs[];

    private long logId;
    /**
     * The maximum size of a entry logger file.
     */
    final long logSizeLimit;
    private volatile BufferedChannel logChannel;
    /**
     * The 1K block at the head of the entry logger file
     * that contains the fingerprint and (future) meta-data
     */
    final static int LOGFILE_HEADER_SIZE = 1024;
    final ByteBuffer LOGFILE_HEADER = ByteBuffer.allocate(LOGFILE_HEADER_SIZE);

    // this indicates that a write has happened since the last flush
    private volatile boolean somethingWritten = false;

    final static long MB = 1024 * 1024;

    /**
     * Records the total size, remaining size and the set of ledgers that comprise a entry log.
     */
    static class EntryLogMetadata {
        long entryLogId;
        long totalSize;
        long remainingSize;
        ConcurrentHashMap<Long, Long> ledgersMap;

        public EntryLogMetadata(long logId) {
            this.entryLogId = logId;

            totalSize = remainingSize = 0;
            ledgersMap = new ConcurrentHashMap<Long, Long>();
        }

        public void addLedgerSize(long ledgerId, long size) {
            totalSize += size;
            remainingSize += size;
            Long ledgerSize = ledgersMap.get(ledgerId);
            if (null == ledgerSize) {
                ledgerSize = 0L;
            }
            ledgerSize += size;
            ledgersMap.put(ledgerId, ledgerSize);
        }

        public void removeLedger(long ledgerId) {
            Long size = ledgersMap.remove(ledgerId);
            if (null == size) {
                return;
            }
            remainingSize -= size;
        }

        public boolean containsLedger(long ledgerId) {
            return ledgersMap.containsKey(ledgerId);
        }

        public double getUsage() {
            if (totalSize == 0L) {
                return 0.0f;
            }
            return (double)remainingSize / totalSize;
        }

        public boolean isEmpty() {
            return ledgersMap.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("{ totalSize = ").append(totalSize).append(", remainingSize = ")
              .append(remainingSize).append(", ledgersMap = ").append(ledgersMap).append(" }");
            return sb.toString();
        }
    }

    /**
     * Scan entries in a entry log file.
     */
    static interface EntryLogScanner {
        /**
         * Tests whether or not the entries belongs to the specified ledger
         * should be processed.
         *
         * @param ledgerId
         *          Ledger ID.
         * @return true if and only the entries of the ledger should be scanned.
         */
        public boolean accept(long ledgerId);

        /**
         * Process an entry.
         *
         * @param ledgerId
         *          Ledger ID.
         * @param entry
         *          Entry ByteBuffer
         * @throws IOException
         */
        public void process(long ledgerId, ByteBuffer entry) throws IOException;
    }

    /**
     * Create an EntryLogger that stores it's log files in the given
     * directories
     */
    public EntryLogger(ServerConfiguration conf) throws IOException {
        this.dirs = Bookie.getCurrentDirectories(conf.getLedgerDirs());
        // log size limit
        this.logSizeLimit = conf.getEntryLogSizeLimit();

        // Initialize the entry log header buffer. This cannot be a static object
        // since in our unit tests, we run multiple Bookies and thus EntryLoggers
        // within the same JVM. All of these Bookie instances access this header
        // so there can be race conditions when entry logs are rolled over and
        // this header buffer is cleared before writing it into the new logChannel.
        LOGFILE_HEADER.put("BKLO".getBytes());
        // Find the largest logId
        for(File f: dirs) {
            long lastLogId = getLastLogId(f);
            if (lastLogId >= logId) {
                logId = lastLogId+1;
            }
        }
        createLogId(logId);
    }

    /**
     * Maps entry log files to open channels.
     */
    private ConcurrentHashMap<Long, BufferedChannel> channels = new ConcurrentHashMap<Long, BufferedChannel>();

    /**
     * Creates a new log file with the given id.
     */
    private void createLogId(long logId) throws IOException {
        List<File> list = Arrays.asList(dirs);
        Collections.shuffle(list);
        File firstDir = list.get(0);
        if (logChannel != null) {
            logChannel.flush(true);
        }
        logChannel = new BufferedChannel(new RandomAccessFile(new File(firstDir, Long.toHexString(logId)+".log"), "rw").getChannel(), 64*1024);
        logChannel.write((ByteBuffer) LOGFILE_HEADER.clear());
        channels.put(logId, logChannel);
        for(File f: dirs) {
            setLastLogId(f, logId);
        }
    }

    /**
     * Remove entry log.
     *
     * @param entryLogId
     *          Entry Log File Id
     */
    protected boolean removeEntryLog(long entryLogId) {
        BufferedChannel bc = channels.remove(entryLogId);
        if (null != bc) {
            // close its underlying file channel, so it could be deleted really
            try {
                bc.getFileChannel().close();
            } catch (IOException ie) {
                LOG.warn("Exception while closing garbage collected entryLog file : ", ie);
            }
        }
        File entryLogFile;
        try {
            entryLogFile = findFile(entryLogId);
        } catch (FileNotFoundException e) {
            LOG.error("Trying to delete an entryLog file that could not be found: "
                    + entryLogId + ".log");
            return false;
        }
        entryLogFile.delete();
        return true;
    }

    /**
     * writes the given id to the "lastId" file in the given directory.
     */
    private void setLastLogId(File dir, long logId) throws IOException {
        FileOutputStream fos;
        fos = new FileOutputStream(new File(dir, "lastId"));
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos));
        try {
            bw.write(Long.toHexString(logId) + "\n");
            bw.flush();
        } finally {
            try {
                fos.close();
            } catch (IOException e) {
            }
        }
    }

    /**
     * reads id from the "lastId" file in the given directory.
     */
    private long getLastLogId(File f) {
        FileInputStream fis;
        try {
            fis = new FileInputStream(new File(f, "lastId"));
        } catch (FileNotFoundException e) {
            return -1;
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(fis));
        try {
            String lastIdString = br.readLine();
            return Long.parseLong(lastIdString, 16);
        } catch (IOException e) {
            return -1;
        } catch(NumberFormatException e) {
            return -1;
        } finally {
            try {
                fis.close();
            } catch (IOException e) {
            }
        }
    }

    private void openNewChannel() throws IOException {
        createLogId(++logId);
    }

    synchronized void flush() throws IOException {
        if (logChannel != null) {
            logChannel.flush(true);
        }
    }
    synchronized long addEntry(long ledger, ByteBuffer entry) throws IOException {
        if (logChannel.position() + entry.remaining() + 4 > logSizeLimit) {
            openNewChannel();
        }
        ByteBuffer buff = ByteBuffer.allocate(4);
        buff.putInt(entry.remaining());
        buff.flip();
        logChannel.write(buff);
        long pos = logChannel.position();
        logChannel.write(entry);
        //logChannel.flush(false);
        somethingWritten = true;
        return (logId << 32L) | pos;
    }

    byte[] readEntry(long ledgerId, long entryId, long location) throws IOException {
        long entryLogId = location >> 32L;
        long pos = location & 0xffffffffL;
        ByteBuffer sizeBuff = ByteBuffer.allocate(4);
        pos -= 4; // we want to get the ledgerId and length to check
        BufferedChannel fc;
        try {
            fc = getChannelForLogId(entryLogId);
        } catch (FileNotFoundException e) {
            FileNotFoundException newe = new FileNotFoundException(e.getMessage() + " for " + ledgerId + " with location " + location);
            newe.setStackTrace(e.getStackTrace());
            throw newe;
        }
        if (fc.read(sizeBuff, pos) != sizeBuff.capacity()) {
            throw new IOException("Short read from entrylog " + entryLogId);
        }
        pos += 4;
        sizeBuff.flip();
        int entrySize = sizeBuff.getInt();
        // entrySize does not include the ledgerId
        if (entrySize > MB) {
            LOG.error("Sanity check failed for entry size of " + entrySize + " at location " + pos + " in " + entryLogId);

        }
        byte data[] = new byte[entrySize];
        ByteBuffer buff = ByteBuffer.wrap(data);
        int rc = fc.read(buff, pos);
        if ( rc != data.length) {
            throw new IOException("Short read for " + ledgerId + "@" + entryId + " in " + entryLogId + "@" + pos + "("+rc+"!="+data.length+")");
        }
        buff.flip();
        long thisLedgerId = buff.getLong();
        if (thisLedgerId != ledgerId) {
            throw new IOException("problem found in " + entryLogId + "@" + entryId + " at position + " + pos + " entry belongs to " + thisLedgerId + " not " + ledgerId);
        }
        long thisEntryId = buff.getLong();
        if (thisEntryId != entryId) {
            throw new IOException("problem found in " + entryLogId + "@" + entryId + " at position + " + pos + " entry is " + thisEntryId + " not " + entryId);
        }

        return data;
    }

    private BufferedChannel getChannelForLogId(long entryLogId) throws IOException {
        BufferedChannel fc = channels.get(entryLogId);
        if (fc != null) {
            return fc;
        }
        File file = findFile(entryLogId);
        FileChannel newFc = new RandomAccessFile(file, "rw").getChannel();
        // If the file already exists before creating a BufferedChannel layer above it,
        // set the FileChannel's position to the end so the write buffer knows where to start.
        newFc.position(newFc.size());
        synchronized (channels) {
            fc = channels.get(entryLogId);
            if (fc != null) {
                newFc.close();
                return fc;
            }
            fc = new BufferedChannel(newFc, 8192);
            channels.put(entryLogId, fc);
            return fc;
        }
    }

    private File findFile(long logId) throws FileNotFoundException {
        for(File d: dirs) {
            File f = new File(d, Long.toHexString(logId)+".log");
            if (f.exists()) {
                return f;
            }
        }
        throw new FileNotFoundException("No file for log " + Long.toHexString(logId));
    }

    synchronized public boolean testAndClearSomethingWritten() {
        try {
            return somethingWritten;
        } finally {
            somethingWritten = false;
        }
    }

    /**
     * A scanner used to extract entry log meta from entry log files.
     */
    class ExtractionScanner implements EntryLogScanner {
        EntryLogMetadata meta;

        public ExtractionScanner(EntryLogMetadata meta) {
            this.meta = meta;
        }

        @Override
        public boolean accept(long ledgerId) {
            return true;
        }
        @Override
        public void process(long ledgerId, ByteBuffer entry) {
            // add new entry size of a ledger to entry log meta
            meta.addLedgerSize(ledgerId, entry.limit() + 4);
        }
    }

    /**
     * Method to read in all of the entry logs (those that we haven't done so yet),
     * and find the set of ledger ID's that make up each entry log file.
     *
     * @param entryLogMetaMap
     *          Existing EntryLogs to Meta
     * @throws IOException
     */
    protected Map<Long, EntryLogMetadata> extractMetaFromEntryLogs(Map<Long, EntryLogMetadata> entryLogMetaMap) throws IOException {
        // Extract it for every entry log except for the current one.
        // Entry Log ID's are just a long value that starts at 0 and increments
        // by 1 when the log fills up and we roll to a new one.
        long curLogId = logId;
        for (long entryLogId = 0; entryLogId < curLogId; entryLogId++) {
            // Comb the current entry log file if it has not already been extracted.
            if (entryLogMetaMap.containsKey(entryLogId)) {
                continue;
            }
            LOG.info("Extracting entry log meta from entryLogId: " + entryLogId);
            EntryLogMetadata entryLogMeta = new EntryLogMetadata(entryLogId);
            ExtractionScanner scanner = new ExtractionScanner(entryLogMeta);
            // Read through the entry log file and extract the entry log meta
            try {
                scanEntryLog(entryLogId, scanner);
                LOG.info("Retrieved entry log meta data entryLogId: " + entryLogId + ", meta: " + entryLogMeta);
                entryLogMetaMap.put(entryLogId, entryLogMeta);
            } catch(IOException e) {
              LOG.warn("Premature exception when processing " + entryLogId +
                       "recovery will take care of the problem", e);
            }

        }
        return entryLogMetaMap;
    }

    protected EntryLogMetadata extractMetaFromEntryLog(long entryLogId) {
        EntryLogMetadata entryLogMeta = new EntryLogMetadata(entryLogId);
        ExtractionScanner scanner = new ExtractionScanner(entryLogMeta);
        // Read through the entry log file and extract the entry log meta
        try {
            scanEntryLog(entryLogId, scanner);
            LOG.info("Retrieved entry log meta data entryLogId: " + entryLogId + ", meta: " + entryLogMeta);
        } catch(IOException e) {
          LOG.warn("Premature exception when processing " + entryLogId +
                   "recovery will take care of the problem", e);
        }
        return entryLogMeta;
    }

    /**
     * Scan entry log
     *
     * @param entryLogId
     *          Entry Log Id
     * @param scanner
     *          Entry Log Scanner
     * @throws IOException
     */
    protected void scanEntryLog(long entryLogId, EntryLogScanner scanner) throws IOException {
        ByteBuffer sizeBuff = ByteBuffer.allocate(4);
        ByteBuffer lidBuff = ByteBuffer.allocate(8);
        BufferedChannel bc;
        // Get the BufferedChannel for the current entry log file
        try {
            bc = getChannelForLogId(entryLogId);
        } catch (IOException e) {
            LOG.warn("Failed to get channel to scan entry log: " + entryLogId + ".log");
            throw e;
        }
        // Start the read position in the current entry log file to be after
        // the header where all of the ledger entries are.
        long pos = LOGFILE_HEADER_SIZE;
        // Read through the entry log file and extract the ledger ID's.
        while (true) {
            // Check if we've finished reading the entry log file.
            if (pos >= bc.size()) {
                break;
            }
            if (bc.read(sizeBuff, pos) != sizeBuff.capacity()) {
                throw new IOException("Short read for entry size from entrylog " + entryLogId);
            }
            pos += 4;
            sizeBuff.flip();
            int entrySize = sizeBuff.getInt();
            if (entrySize > MB) {
                LOG.warn("Found large size entry of " + entrySize + " at location " + pos + " in "
                        + entryLogId);
            }
            sizeBuff.clear();
            // try to read ledger id first
            if (bc.read(lidBuff, pos) != lidBuff.capacity()) {
                throw new IOException("Short read for ledger id from entrylog " + entryLogId);
            }
            lidBuff.flip();
            long lid = lidBuff.getLong();
            lidBuff.clear();
            if (!scanner.accept(lid)) {
                // skip this entry
                pos += entrySize;
                continue;
            }
            // read the entry
            byte data[] = new byte[entrySize];
            ByteBuffer buff = ByteBuffer.wrap(data);
            int rc = bc.read(buff, pos);
            if (rc != data.length) {
                throw new IOException("Short read for ledger entry from entryLog " + entryLogId
                                    + "@" + pos + "(" + rc + "!=" + data.length + ")");
            }
            buff.flip();
            // process the entry
            scanner.process(lid, buff);
            // Advance position to the next entry
            pos += entrySize;
        }
    }

    /**
     * Shutdown method to gracefully stop entry logger.
     */
    public void shutdown() {
        // since logChannel is buffered channel, do flush when shutting down
        try {
            flush();
        } catch (IOException ie) {
            // we have no idea how to avoid io exception during shutting down, so just ignore it
            LOG.error("Error flush entry log during shutting down, which may cause entry log corrupted.", ie);
        }
    }

}
