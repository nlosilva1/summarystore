package com.samsung.sra.DataStore.Ingest;

import com.samsung.sra.DataStore.Storage.StreamWindowManager;
import com.samsung.sra.DataStore.SummaryWindow;
import com.samsung.sra.DataStore.Utilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.concurrent.BlockingQueue;

/** Summarize values in full IngestBuffers into a sequence of SummaryWindows, and pass them on to Writer's queue */
class Summarizer implements Runnable, Serializable {
    static final IngestBuffer SHUTDOWN_SENTINEL = new IngestBuffer(0);
    static final IngestBuffer FLUSH_SENTINEL = new IngestBuffer(0);
    private static final Logger logger = LoggerFactory.getLogger(Summarizer.class);

    private final BlockingQueue<IngestBuffer> summarizerQueue; // input queue
    private final BlockingQueue<SummaryWindow> writerQueue; // output queue
    private final BlockingQueue<IngestBuffer> emptyBuffers; // output queue (discards cleared out buffers here for recycling)
    private final BlockingQueue<IngestBuffer> partialBuffers; // output queue (drains unsummarizable remnants of partial
                                                              // buffers here)
    private final CountBasedWBMH.FlushBarrier flushBarrier;

    private int[] windowLengths;

    private long N = 0;

    private transient StreamWindowManager windowManager = null;

    Summarizer(int[] windowLengths,
               BlockingQueue<IngestBuffer> emptyBuffers, BlockingQueue<IngestBuffer> partialBuffers,
               BlockingQueue<IngestBuffer> summarizerQueue,
               BlockingQueue<SummaryWindow> writerQueue,
               CountBasedWBMH.FlushBarrier flushBarrier) {
        this.windowLengths = windowLengths;
        this.emptyBuffers = emptyBuffers;
        this.partialBuffers = partialBuffers;
        this.summarizerQueue = summarizerQueue;
        this.writerQueue = writerQueue;
        this.flushBarrier = flushBarrier;
    }

    void populateTransientFields(StreamWindowManager windowManager) {
        this.windowManager = windowManager;
    }

    @Override
    public void run() {
        while (true) {
            IngestBuffer buffer = Utilities.take(summarizerQueue);
            if (buffer == SHUTDOWN_SENTINEL) {
                flushBarrier.notify(CountBasedWBMH.FlushBarrier.SUMMARIZER);
                break;
            } else if (buffer == FLUSH_SENTINEL) {
                flushBarrier.notify(CountBasedWBMH.FlushBarrier.SUMMARIZER);
                continue;
            }
            int W = getNumWindowsCovering(buffer);
            int bs = 0, be; // index of first and last elements in the buffer belonging to current window
            // invariant: at end of each loop, we have processed elements [0, 1, ..., bs-1]
            for (int w = W - 1; w >= 0; --w) {
                be = bs + windowLengths[w] - 1;
                SummaryWindow window = windowManager.createEmptySummaryWindow(
                        buffer.getTimestamp(bs), buffer.getTimestamp(be), N + bs, N + be);
                for (int c = bs; c <= be; ++c) {
                    windowManager.insertIntoSummaryWindow(window, buffer.getTimestamp(c), buffer.getValue(c));
                }
                Utilities.put(writerQueue, window);
                bs = be + 1;
            }
            N += bs;
            if (bs == buffer.size()) {
                buffer.clear();
                Utilities.put(emptyBuffers, buffer);
            } else {
                buffer.truncateHead(bs);
                Utilities.put(partialBuffers, buffer);
            }
        }
    }

    private int getNumWindowsCovering(IngestBuffer buffer) {
        if (buffer.isFull()) {
            return windowLengths.length;
        } else {
            long N = 0;
            for (int w = 0; w < windowLengths.length; ++w) {
                N += windowLengths[w]; // N is now size of first (w+1) windows
                if (N > buffer.size()) {
                    return w; // first w+1 windows cover > buffer.size() elements and first w cover <; so return w
                } else if (N == buffer.size()) {
                    return w + 1; // first w+1 windows cover buffer.size() elements exactly
                }
            }
        }
        throw new IllegalStateException("hit unreachable code");
    }

    void setWindowLengths(int[] windowLengths) {
        this.windowLengths = windowLengths;
    }
}
