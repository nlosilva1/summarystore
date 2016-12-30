package com.samsung.sra.DataStoreExperiments;

import com.samsung.sra.DataStore.CountBasedWBMH;
import com.samsung.sra.DataStore.SummaryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class PopulateData {
    private static final long streamID = 0;
    private static final Logger logger = LoggerFactory.getLogger(PopulateData.class);

    public static void main(String[] args) throws Exception {
        File configFile;
        if (args.length != 1 || !(configFile = new File(args[0])).isFile()) {
            System.err.println("SYNTAX: PopulateData config.toml");
            System.exit(2);
            return;
        }
        Configuration config = new Configuration(configFile);

        // uncomment the parallelStream to parallelize
        config.getDecayFunctions()./*parallelStream().*/forEach(decay -> {
            String outprefix = config.getStorePrefix(decay);
            if ((new File(outprefix + ".bucketStore").exists())) {
                logger.warn("Decay function {} already populated at {}.bucketStore, skipping", decay, outprefix);
                return;
            }
            try (SummaryStore store = new SummaryStore(outprefix, config.getBucketCacheSize());
                 StreamGenerator streamgen = config.getStreamGenerator()) {
                store.registerStream(streamID,
                        new CountBasedWBMH(config.parseDecayFunction(decay), config.getIngestBufferSize()),
                        config.getOperators());
                streamgen.reset();
                long[] N = {0};
                streamgen.generate(config.getTstart(), config.getTend(), (t, v) -> {
                    try {
                        if (++N[0] % 10_000_000 == 0) {
                            logger.info("Inserted {} elements", N[0]);
                        }
                        store.append(streamID, t, v);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                store.flush(streamID);
                logger.info("Inserted {} elements", N[0]);
                logger.info("{} = {} windows", outprefix, store.getNumWindows(streamID));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}