package com.samsung.sra.DataStoreExperiments;

import com.moandjiezana.toml.Toml;
import com.samsung.sra.DataStore.*;
import com.samsung.sra.DataStore.Aggregates.SimpleBloomFilterOperator;
import com.samsung.sra.DataStore.Aggregates.SimpleCountOperator;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.math3.util.Pair;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Created by n.agrawal1 on 7/21/16.
 */
public class SimpleTester {

    private static String loc_prefix = "/tmp/tdstore_";
    private static long streamID = 0;
    public static Logger logger = LoggerFactory.getLogger(SimpleTester.class);


    public static void main(String[] args) throws Exception {

        Runtime.getRuntime().exec(new String[]{"sh", "-c",  "rm -rf " + loc_prefix + "*"}).waitFor();

        SummaryStore store = null;
        long T =100; //  entries
        //long storageSavingsFactor = 1;
        //long W = T / 2 / storageSavingsFactor; // # windows
        long W = T;
        long Q = 1000;

        LinkedHashMap<String, SummaryStore> stores = new LinkedHashMap<>();

        Windowing windowing
                = new GenericWindowing(new ExponentialWindowLengths(2));
                //= new RationalPowerWindowing(1, 2);


        try {
            store = new SummaryStore(loc_prefix);
            store.registerStream(streamID, new CountBasedWBMH(windowing),
                    new SimpleCountOperator()
                    // new SimpleCountOperator(SimpleCountOperator.Estimator.PROPORTIONAL),
                    ,new SimpleBloomFilterOperator()
                    );
            stores.put("expstore", store);
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("Testing a store with " + T + " elements");

        RandomStreamGenerator generator = new RandomStreamGenerator(new Toml().read(
                "interarrivals = {distribution = \"ExponentialDistribution\", lambda = 1.0}\n" +
                "values = {distribution = \"FixedDistribution\", value = 10}"
        ));
        long w0 = System.currentTimeMillis();
        //BiConsumer<Long, Long> printer = (ts, v) -> System.out.println(ts + "\t" + v);
        //BiConsumer<Long, Long> myvals = (ts, v) -> store.append(streamID, ts, v);

        generator.generate(T, (t, v) -> {
            try {
                //logger.debug("Inserting " + v + " at Time " + t);
                for (SummaryStore astore: stores.values()) {
                    astore.append(streamID, t, v);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        long we = System.currentTimeMillis();
        //System.out.println("Write throughput = " + (T * 1000d / (we - w0)) + " appends/s" );

        store.printBucketState(streamID, false);
        //logger.debug("Size for store " + streamID + " : " + store.getStoreSizeInBytes());

        Object o[] = {new Long(50), new Long(0)};
        ResultError<Boolean,Double> re2 =
                (ResultError<Boolean,Double>) store.query(streamID, 0, T-1, 1, o[0]);
        logger.debug("Bloom.ispresent(" + o[0] + "): " + re2.result);


        Object o2[] = {new Long(82)};
        re2 = (ResultError<Boolean,Double>) store.query(streamID, 0, T-1, 1, o2[0]);
        logger.debug("Bloom.ispresent(" + o2[0] + "): " + re2.result);


        /*
        ResultError<Double,Pair<Double, Double>> re =
                (ResultError<Double,Pair<Double, Double>>) store.query(streamID, 4928, 4975, 0, o[1]);
        logger.debug("Querying in stream " + streamID + " Time0: t0" + " Time1: t1; RE: " + re.result);


        long f0 = System.currentTimeMillis();
        store.query(streamID, 0, T-1, 0, o[1]);
        long fe = System.currentTimeMillis();
        System.out.println("Time to run query, spanning [0, T) = " + ((fe - f0) / 1000d) + " sec");
        */

        store.close();
    }

    private static void registerStore(Map<String, SummaryStore> stores, String storeName, WindowingMechanism windowingMechanism) throws RocksDBException, StreamException {
        SummaryStore store = new SummaryStore(loc_prefix + storeName);
        //SummaryStore store = new SummaryStore(new MainMemoryBucketStore());
        store.registerStream(streamID, windowingMechanism);
        stores.put(storeName, store);
    }
}