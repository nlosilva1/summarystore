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

import com.moandjiezana.toml.Toml;
import com.samsung.sra.datastore.aggregates.SimpleCountOperator;
import com.samsung.sra.datastore.ingest.CountBasedWBMH;
import com.samsung.sra.datastore.RationalPowerWindowing;
import com.samsung.sra.datastore.SummaryStore;
import com.samsung.sra.datastore.WindowOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

public class MeasureInstThroughput {
    private static final Logger logger = LoggerFactory.getLogger(MeasureInstThroughput.class);

    private static final String directory = "/tmp/tdstore_throughput";
    private static final String streamConf =
              "interarrivals = {distribution = \"FixedDistribution\", value = 1}\n"
            + "values = {distribution = \"UniformDistribution\", min = 0, max = 100}\n"
            + "random-seed = 0";

    /** Populate one stream (we use one thread per stream) */
    private static class PopulateStream implements Runnable {
        private final SummaryStore store;
        private final long streamID;
        private final long T; // we will generate over the time range [0, T]
        private final Supplier<CountBasedWBMH> windowing;
        private final Supplier<WindowOperator[]> operators;
        private final PrintThroughput printer;

        PopulateStream(SummaryStore store, long streamID, long T,
                       Supplier<CountBasedWBMH> windowing, Supplier<WindowOperator[]> operators,
                       PrintThroughput printer) {
            this.store = store;
            this.streamID = streamID;
            this.T = T;
            this.windowing = windowing;
            this.operators = operators;
            this.printer = printer;
        }

        @Override
        public void run() {
            try {
                store.registerStream(streamID, windowing.get(), operators.get());
                StreamGenerator generator = new RandomStreamGenerator(new Toml().read(streamConf));
                generator.generate(0, T, op -> {
                    assert op.type == StreamGenerator.Operation.Type.APPEND;
                    try {
                        store.append(streamID, op.timestamp, op.value);
                        printer.notifyInsertComplete();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                store.flush(streamID);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Every printInterval inserts, compute and print throughput since last print */
    private static class PrintThroughput {
        final long printInterval;
        private long t0;
        private long nLeft;

        PrintThroughput(long printInterval) {
            assert printInterval > 0;
            this.printInterval = printInterval;
            reset();
        }

        private void reset() {
            nLeft = printInterval;
            t0 = System.nanoTime();
        }

        private void printThroughput() {
            long te = System.nanoTime();
            logger.info("Instantaneous throughput = {} / second",
                    String.format("%,.0f", printInterval * 1e9 / (double) (te - t0)));
        }

        synchronized void notifyInsertComplete() {
            --nLeft;
            if (nLeft == 0) {
                printThroughput();
                reset();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Runtime.getRuntime().exec(new String[]{"sh", "-c", "rm -rf " + directory}).waitFor();
        int nStreams = 4;
        long T = 1_000_000_000;
        long printInterval = 10_000_000;
        Supplier<CountBasedWBMH> windowing = () ->
                new CountBasedWBMH(new RationalPowerWindowing(1, 1, 6, 1)).setBufferSize(2_000_000);
        Supplier<WindowOperator[]> operators = () -> new WindowOperator[]{
                new SimpleCountOperator()};

        PrintThroughput throughputPrinter = new PrintThroughput(printInterval);
        try (SummaryStore store = new SummaryStore(directory)) {
            if (nStreams == 1) {
                new PopulateStream(store, 0, T, windowing, operators, throughputPrinter).run();
            } else {
                Thread[] threads = new Thread[nStreams];
                for (int i = 0; i < nStreams; ++i) {
                    threads[i] = new Thread(new PopulateStream(store, i, T, windowing, operators, throughputPrinter));
                    threads[i].start();
                }
                for (int i = 0; i < nStreams; ++i) {
                    threads[i].join();
                }
            }
            /*long f0 = System.currentTimeMillis();
            store.query(streamID, 0, T - 1, 0);
            long fe = System.currentTimeMillis();
            System.out.println("Time to run longest query, spanning [0, T) = " + ((fe - f0) / 1000d) + " sec");*/
        }
    }
}
