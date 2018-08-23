package com.samsung.sra.datastore;

import com.samsung.sra.datastore.aggregates.SimpleCountOperator;
import com.samsung.sra.datastore.ingest.CountBasedWBMH;
import com.samsung.sra.datastore.storage.MainMemoryBackingStore;
import com.samsung.sra.datastore.storage.StreamWindowManager;
import org.junit.Test;

import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;

public class CountBasedWBMHTest {
    @Test
    public void exponential() throws Exception {
        StreamWindowManager swm = new StreamWindowManager(0L, new WindowOperator[]{new SimpleCountOperator()}, true);
        swm.populateTransientFields(new MainMemoryBackingStore());
        CountBasedWBMH wbmh = new CountBasedWBMH(new GenericWindowing(new ExponentialWindowLengths(2)));
        wbmh.populateTransientFields(swm);

        Integer[][] expectedEvolution = {
                {1},
                {1, 1},
                {2, 1},
                {2, 1, 1},
                {2, 2, 1},
                {2, 2, 1, 1},
                {4, 2, 1},
                {4, 2, 1, 1},
                {4, 2, 2, 1},
                {4, 2, 2, 1, 1},
                {4, 4, 2, 1},
                {4, 4, 2, 1, 1},
                {4, 4, 2, 2, 1},
                {4, 4, 2, 2, 1, 1},
                {8, 4, 2, 1}
        };

        for (int t = 0; t < expectedEvolution.length; ++t) {
            wbmh.append(t, 0L);
            wbmh.flush();
            assertArrayEquals(expectedEvolution[t], swm
                    .getSummaryWindowsOverlapping(0, t)
                    .map(w -> ((Number) w.aggregates[0]).intValue())
                    //.map(w -> (int) (w.ce - w.cs + 1))
                    .collect(Collectors.toList())
                    .toArray(new Integer[0]));
        }
    }
}