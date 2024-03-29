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
package com.samsung.sra.datastore.aggregates;

import com.clearspring.analytics.stream.frequency.CMSProtofier;
import com.clearspring.analytics.stream.frequency.CountMinSketch;
import com.samsung.sra.datastore.*;
import com.samsung.sra.protocol.OpTypeOuterClass.OpType;
import com.samsung.sra.protocol.SummaryStore.ProtoOperator;
import org.apache.commons.lang3.tuple.Pair;

import java.util.function.Function;
import java.util.stream.Stream;

//import org.apache.commons.math3.util.Pair;

/**
 * A = CountMinSketch
 * V = Long
 * R = Long, a count
 * E = Pair<Double, Double>, a CI
 */
public class CMSOperator implements WindowOperator<CountMinSketch,Double,Pair<Double,Double>> {
    private static final OpType opType = OpType.CMS;

    @Override
    public OpType getOpType() {
        return opType;
    }

    private int depth, width;
    private long[] hashA;

    public CMSOperator(int depth, int width, int seed) {
        this.depth = depth;
        this.width = width;
        this.hashA = CMSProtofier.getHashes(depth, width, seed);
    }

    @Override
    public CountMinSketch createEmpty() {
        return CMSProtofier.createEmpty(depth, width, hashA);
    }

    @Override
    public CountMinSketch merge(Stream<CountMinSketch> aggrs) {
        return CMSProtofier.merge(aggrs);
    }

    @Override
    public CountMinSketch insert(CountMinSketch aggr, long timestamp, Object val) {
        /*long entry = (long) val[0];
        long count = val.length > 1 ? (long) val[1] : 1;
        aggr.add(entry, count);*/
        aggr.add((long) val, 1);
        return aggr;
    }

    @Override
    public ResultError<Double, Pair<Double, Double>> query(StreamStatistics streamStats,
                                                           Stream<SummaryWindow> summaryWindows,
                                                           Function<SummaryWindow, CountMinSketch> cmsRetriever,
                                                           Stream<LandmarkWindow> landmarkWindows,
                                                           long t0, long t1, Object... params) {
        assert params != null && params.length > 0;
        long targetVal = (long) params[0];
        Function<SummaryWindow, Long> countRetriever = cmsRetriever.andThen(
                cms -> cms.estimateCount(targetVal)
        );
        // FIXME: Returns correct base answer, but we have a better CI construction with a hypergeom distr
        //        (which accounts for, among other things, the fact that we know true count over all values)
        double confidenceLevel = 1;
        if (params.length > 1) {
            confidenceLevel = ((Number) params[1]).doubleValue();
        }
        double sdMultiplier = streamStats.getCVInterarrival();
        return new SumEstimator(t0, t1, summaryWindows, countRetriever, landmarkWindows,
                o -> ((Long) o) == targetVal ? 1L : 0L)
                .estimate(sdMultiplier, confidenceLevel);
    }

    @Override
    public ResultError<Double, Pair<Double, Double>> getEmptyQueryResult() {
        return new ResultError<>(0d, null);
    }

    /** protofy code needs access to package-local members, so put it in the com.clearspring... package */
    @Override
    public ProtoOperator.Builder protofy(CountMinSketch aggr) {
        return ProtoOperator
                .newBuilder()
                .setCms(CMSProtofier.protofy(aggr));
    }

    @Override
    public CountMinSketch deprotofy(ProtoOperator protoOperator) {
        return CMSProtofier.deprotofy(protoOperator.getCms(), depth, width, hashA);
    }
}
