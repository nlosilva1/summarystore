package com.samsung.sra.DataStore;

import com.samsung.sra.DataStore.Aggregates.CMSOperator;
import com.samsung.sra.DataStore.Aggregates.MaxOperator;
import com.samsung.sra.DataStore.Aggregates.SimpleCountOperator;
import com.samsung.sra.DataStore.Storage.BackingStore;
import com.samsung.sra.DataStore.Storage.BackingStoreException;
import com.samsung.sra.DataStore.Storage.MainMemoryBackingStore;
import com.samsung.sra.DataStore.Storage.RocksDBBackingStore;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Start here. All external code will construct and interact with an instance of this class.
 *
 * Forwards all API calls to Stream, after serializing any calls that modify the stream (append, start/end landmark,
 * flush, close).
 */
public class SummaryStore implements AutoCloseable {
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(SummaryStore.class);

    private final BackingStore backingStore;
    private final String indexesFile;
    private final ExecutorService executorService;

    private ConcurrentHashMap<Long, Stream> streams;
    private final boolean readonly;

    /**
     * Create a SummaryStore that stores data and indexes in files/directories that
     * start with filePrefix. To store everything in-memory use a null filePrefix
     */
    public SummaryStore(String filePrefix, long cacheSizePerStream, boolean readonly)
            throws BackingStoreException, IOException, ClassNotFoundException {
        if (filePrefix != null) {
            this.backingStore = new RocksDBBackingStore(filePrefix + ".backingStore", cacheSizePerStream);
            this.indexesFile = filePrefix + ".indexes";
        } else {
            this.backingStore = new MainMemoryBackingStore();
            this.indexesFile = null;
        }
        this.readonly = readonly;
        executorService = Executors.newCachedThreadPool();
        deserializeIndexes();
    }

    public SummaryStore(String filePrefix, long cacheSizePerStream)
            throws BackingStoreException, IOException, ClassNotFoundException {
        this(filePrefix, cacheSizePerStream, false);
    }

    public SummaryStore(String filePrefix) throws BackingStoreException, IOException, ClassNotFoundException {
        this(filePrefix, 0);
    }

    public SummaryStore() throws BackingStoreException, IOException, ClassNotFoundException {
        this(null);
    }

    private void serializeIndexes() throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(indexesFile))) {
            oos.writeObject(streams);
        }
        //backingStore.putMetadata(streams);
    }

    private void deserializeIndexes() throws IOException, ClassNotFoundException {
        File file;
        if (indexesFile == null || !(file = new File(indexesFile)).exists()) {
            streams = new ConcurrentHashMap<>();
        } else {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                streams = (ConcurrentHashMap<Long, Stream>) ois.readObject();
                for (Stream si: streams.values()) {
                    si.populateTransientFields(backingStore, executorService);
                }
            }
        }
        /*Object uncast = backingStore.getMetadata();
        if (uncast != null) {
            streams = (ConcurrentHashMap<Long, Stream>) uncast;
            for (Stream si: streams.values()) {
                si.populateTransientFields(backingStore, executorService);
            }
        } else {
            streams = new ConcurrentHashMap<>();
        }*/
    }

    public void registerStream(final long streamID, WindowingMechanism windowingMechanism, WindowOperator... operators)
            throws StreamException, BackingStoreException {
        synchronized (streams) {
            if (streams.containsKey(streamID)) {
                 throw new StreamException("attempting to register streamID " + streamID + " multiple times");
            } else {
                Stream sm = new Stream(streamID, windowingMechanism, operators);
                sm.populateTransientFields(backingStore, executorService);
                streams.put(streamID, sm);
            }
        }
    }

    private Stream getStream(long streamID) throws StreamException {
        Stream stream = streams.get(streamID);
        if (stream == null) {
            throw new StreamException("invalid streamID " + streamID);
        }
        return stream;
    }

    public Object query(long streamID, long t0, long t1, int aggregateNum, Object... queryParams)
            throws StreamException, BackingStoreException {
        if (t0 < 0 || t0 > t1) {
            throw new IllegalArgumentException("[" + t0 + ", " + t1 + "] is not a valid time interval");
        }
        return getStream(streamID).query(aggregateNum, t0, t1, queryParams);
    }

    public void append(long streamID, long ts, Object... value) throws StreamException, BackingStoreException {
        Stream stream = getStream(streamID);
        stream.extLock.lock();
        try {
            //logger.trace("Appending new value: <ts: " + ts + ", val: " + value + ">");
            stream.append(ts, value);
        } finally {
            stream.extLock.unlock();
        }
    }

    /**
     * Initiate a landmark window with specified start timestamp. timestamp must be strictly larger than last appended
     * value.
     *
     * Has no effect is there already is an active landmark window.
     */
    public void startLandmark(long streamID, long timestamp) throws StreamException, BackingStoreException {
        Stream stream = getStream(streamID);
        stream.extLock.lock();
        try {
            stream.startLandmark(timestamp);
        } finally {
            stream.extLock.unlock();
        }
    }

    /**
     * Seal the active landmark window, throwing an exception if there isn't one. timestamp must not precede last
     * appended value.
     */
    public void endLandmark(long streamID, long timestamp) throws StreamException, BackingStoreException {
        Stream stream = getStream(streamID);
        stream.extLock.lock();
        try {
            stream.endLandmark(timestamp);
        } finally {
            stream.extLock.unlock();
        }
    }

    public void printWindowState(long streamID, boolean printPerWindowState) throws StreamException, BackingStoreException {
        getStream(streamID).printWindows(printPerWindowState);
    }

    public void printWindowState(long streamID) throws StreamException, BackingStoreException {
        printWindowState(streamID, false);
    }

    public void flush(long streamID) throws BackingStoreException, StreamException {
        // FIXME: clean up flush logic
        Stream stream = getStream(streamID);
        stream.extLock.lock();
        try {
            stream.windowingMechanism.flush(stream.windowManager);
        } finally {
            stream.extLock.unlock();
        }
    }

    @Override
    public void close() throws BackingStoreException, IOException {
        // FIXME: clean up flush logic
        synchronized (streams) {
            if (!readonly) {
                // wait for all in-process writes and reads to finish
                for (Stream stream : streams.values()) {
                    stream.extLock.lock();
                }
                // At this point all operations on existing streams will be blocked. New stream
                // creates are already blocked because we're synchronizing on streams
                for (Stream stream : streams.values()) {
                    stream.windowingMechanism.close(stream.windowManager);
                    backingStore.flushToDisk(stream.windowManager);
                }
                serializeIndexes();
            }
            backingStore.close();
        }
    }

    /**
     * Get number of summary windows in specified stream. Use a null streamID to get total count over all streams
     */
    public long getNumSummaryWindows(Long streamID) throws StreamException {
        Collection<Stream> streams = streamID != null
                ? Collections.singletonList(getStream(streamID))
                : this.streams.values();
        long ret = 0;
        for (Stream sm : streams) {
            ret += sm.getNumSummaryWindows();
        }
        return ret;
    }

    public StreamStatistics getStreamStatistics(long streamID) throws StreamException {
        return new StreamStatistics(getStream(streamID).stats);
    }

    public static void main(String[] args) {
        SummaryStore store = null;
        try {
            String storeLoc = "/tmp/tdstore";
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "rm -rf " + storeLoc + "*"}).waitFor();
            store = new SummaryStore(storeLoc);
            //store = new SummaryStore(null);
            long streamID = 0;
            if (!store.streams.containsKey(streamID)) {
                Windowing windowing
                        = new GenericWindowing(new ExponentialWindowLengths(2));
                        //= new RationalPowerWindowing(1, 1);
                store.registerStream(streamID, new CountBasedWBMH(windowing, 33),
                        new SimpleCountOperator(),
                        new CMSOperator(5, 100, 0),
                        new MaxOperator());
                for (long i = 0; i < 1022; ++i) {
                    if (i == 491) {
                        store.startLandmark(streamID, i);
                    }
                    store.append(streamID, i, i % 10, 1000L);
                    if (i == 500) {
                        store.endLandmark(streamID, i);
                    }
                    store.printWindowState(streamID);
                }
                store.flush(streamID);
            }
            store.printWindowState(streamID, true);
            long t0 = 1, t1 = 511;
            System.out.println("[" + t0 + ", " + t1 + "] count = " + store.query(streamID, t0, t1, 0, 0.95));
            System.out.println("[" + t0 + ", " + t1 + "] freq(8) = " + store.query(streamID, t0, t1, 1, 8L));
            System.out.println("[" + t0 + ", " + t1 + "] max = " + store.query(streamID, t0, t1, 2));
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (store != null) {
                try {
                    store.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
