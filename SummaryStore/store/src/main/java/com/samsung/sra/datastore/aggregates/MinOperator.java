package com.samsung.sra.datastore.aggregates;

import com.samsung.sra.datastore.*;
import com.samsung.sra.protocol.OpTypeOuterClass;
import com.samsung.sra.protocol.SummaryStore;
import org.apache.commons.lang3.mutable.MutableLong;

import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Query along a Long stream returning the Long minimum. query() also returns a boolean true if we are certain of
 * the answer (happens when querying only over landmarks) */
public class MinOperator implements WindowOperator<Long, Long, Boolean> {
    private static final OpTypeOuterClass.OpType opType = OpTypeOuterClass.OpType.MIN;

    /** What value to return for min over empty set */
    private static final long EMPTY_MIN = Long.MAX_VALUE;

    @Override
    public OpTypeOuterClass.OpType getOpType() {
        return opType;
    }

    @Override
    public Long createEmpty() {
        return EMPTY_MIN;
    }

    @Override
    public Long merge(Stream<Long> aggrs) {
        return aggrs.mapToLong(Long::longValue).min().orElse(EMPTY_MIN);
    }

    @Override
    public Long insert(Long aggr, long timestamp, Object val) {
        return Math.min(aggr, (Long) val);
    }

    @Override
    public ResultError<Long, Boolean> query(StreamStatistics streamStats,
                                            Stream<SummaryWindow> summaryWindows,
                                            Function<SummaryWindow, Long> summaryRetriever,
                                            Stream<LandmarkWindow> landmarkWindows,
                                            long t0, long t1, Object... params) {
        long smin = merge(summaryWindows.map(summaryRetriever));
        MutableLong lminM = new MutableLong(EMPTY_MIN);
        landmarkWindows.forEach(w -> w.values.forEach((t, v) -> {
            if (t0 <= t && t <= t1) {
                lminM.setValue(Math.min(lminM.longValue(), (Long) v));
            }
        }));
        long lmin = lminM.longValue();
        return new ResultError<>(Math.min(smin, lmin), smin == EMPTY_MIN);
    }

    @Override
    public ResultError<Long, Boolean> getEmptyQueryResult() {
        return new ResultError<>(EMPTY_MIN, true);
    }

    @Override
    public SummaryStore.ProtoOperator.Builder protofy(Long aggr) {
        return SummaryStore.ProtoOperator.newBuilder().setLong(aggr);
    }

    @Override
    public Long deprotofy(SummaryStore.ProtoOperator operator) {
        return operator.getLong();
    }
}
