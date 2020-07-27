/*
* Copyright 2016 Samsung Research America. All rights reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.samsung.sra.experiments;

import com.samsung.sra.datastore.RationalPowerWindowing;
import com.samsung.sra.datastore.SummaryStore;
import com.samsung.sra.datastore.aggregates.*;
import com.samsung.sra.datastore.ingest.CountBasedWBMH;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.concurrent.Semaphore;

public class MeasureThroughput {
    private static final String directory = "/data/tdstore_throughput";
    private static final Logger logger = LoggerFactory.getLogger(MeasureThroughput.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("SYNTAX: MeasureThroughput numValuesPerThread numThreads [numParallelThreads]");
            System.exit(2);
        }
        long T = Long.parseLong(args[0].replace("_", ""));
        int nThreads = Integer.parseInt(args[1]);
        Semaphore parallelismSem = args.length > 2
                ? new Semaphore(Integer.parseInt(args[2]))
                : null;
        Runtime.getRuntime().exec(new String[]{"sh", "-c", "rm -rf " + directory}).waitFor();

        try (SummaryStore store = new SummaryStore(directory, new SummaryStore.StoreOptions().setKeepReadIndexes(false))) {
            StreamWriter[] writers = new StreamWriter[nThreads];
            Thread[] writerThreads = new Thread[nThreads];
            for (int i = 0; i < nThreads; ++i) {
                writers[i] = new MeasureThroughput().new StreamWriter(store, parallelismSem, i, T, i+Long.parseLong(args[2]));
                writerThreads[i] = new Thread(writers[i], i + "-appender");
            }
            long w0 = System.currentTimeMillis();
            for (int i = 0; i < nThreads; ++i) {
                writerThreads[i].start();
            }
            for (int i = 0; i < nThreads; ++i) {
                writerThreads[i].join();
            }
            long we = System.currentTimeMillis();
            logger.info("Write throughput = {} appends/s",  String.format("%,.0f", (nThreads * T * 1000d / (we - w0))));
            store.loadStream(0L);
            logger.info("Stream 0 has {} elements in {} windows", T, store.getNumSummaryWindows(0L));

            /*long f0 = System.currentTimeMillis();
            store.query(0, 0, T - 1, 0);
            long fe = System.currentTimeMillis();
            logger.info("Time to run longest query, spanning [0, T) = {} sec", (fe - f0) / 1000d);*/
        }
    }

    private class StreamWriter implements Runnable {
        private final long streamID, N;
        private final SummaryStore store;
        private final Semaphore semaphore;
        private final Random random;

        private StreamWriter(SummaryStore store, Semaphore semaphore, long streamID, long N, long random) throws Exception {
            this.store = store;
            this.semaphore = semaphore;
            this.streamID = streamID;
            this.N = N;
            this.random = new Random(random);
        }

        @Override
        public void run() {
            if (semaphore != null) semaphore.acquireUninterruptibly();
            CountBasedWBMH wbmh = new CountBasedWBMH(new RationalPowerWindowing(1, 1, 1, 1))
                    .setValuesAreLongs(true)
                    .setBufferSize(800_000_000)
                    .setWindowsPerMergeBatch(100_000)
                    .setParallelizeMerge(10);
            try {
                store.registerStream(streamID, false, wbmh,
                        new SimpleCountOperator(),
                        new CMSOperator(5, 1000, 0),
                        new BloomFilterOperator(5, 1000),
                        new SumOperator(),
                        new MaxOperator(),
                        new MinOperator());
                long maxTime = 0;
                long minTime = 9223372036854775806L;
                double avgTime = 0;
                for (long t = 0; t < N; ++t) {
                    long v = random.nextLong();
                    long st = System.nanoTime();
                    store.append(streamID, t, v);
                    long en = System.nanoTime();
                    long batchTime = en - st;
                    if (maxTime < batchTime) {
                        maxTime = batchTime;
                    }
                    if (minTime > batchTime) {
                        minTime = batchTime;
                    }
                    avgTime += batchTime / (double)100000000;
                    if((t + 1) % 100000000 == 0) {
                        logger.info("Batch {}: BatchMax= {} ns, BatchMin= {} ns, BatchAvg = {} ns",
                                (t + 1) / 100000000, maxTime, minTime, avgTime);
                        maxTime = 0;
                        minTime = 9223372036854775806L;
                        avgTime = 0;
                    }
                }
                store.flush(streamID);
                wbmh.setBufferSize(0);
                wbmh.flushAndSetUnbuffered();
                logger.info("Populated stream {}", streamID);
                if (semaphore != null) {
                    store.unloadStream(streamID);
                    logger.info("Unloaded stream {}", streamID);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                if (semaphore != null) semaphore.release();
            }
        }
    }
}
