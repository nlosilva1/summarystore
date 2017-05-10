package com.clearspring.analytics.stream.frequency;

import com.samsung.sra.protocol.SummaryStore.ProtoCMS;

import java.util.Arrays;
import java.util.stream.Stream;

public class CMSProtofier {
    public static ProtoCMS.Builder protofy(CountMinSketch cms) {
        ProtoCMS.Builder builder = ProtoCMS.newBuilder();
        builder.setSize(cms.size);
        assert cms.hashA.length == cms.depth;
        assert cms.table.length == cms.depth;
        for (int i = 0; i < cms.depth; ++i) {
            ProtoCMS.Row.Builder rowBuilder = ProtoCMS.Row.newBuilder();
            for (int j = 0; j < cms.width; ++j) {
                rowBuilder.addCell(cms.table[i][j]);
            }
            builder.addRow(rowBuilder);
        }
        return builder;
    }

    public static CountMinSketch deprotofy(ProtoCMS protoCMS, int depth, int width, long[] hashAorig) {
        long size = protoCMS.getSize();
        long[] hashA = hashAorig.clone();
        assert protoCMS.getRowCount() == depth;
        long[][] table = new long[depth][width];
        for (int i = 0; i < depth; ++i) {
            ProtoCMS.Row row = protoCMS.getRow(i);
            assert row.getCellCount() == width;
            for (int j = 0; j < width; ++j) {
                table[i][j] = row.getCell(j);
            }
        }
        // FIXME: bug in CMS code, uses int for size in constructor but field is declared as long
        return new CountMinSketch(depth, width, (int) size, hashA, table);
    }

    public static CountMinSketch createEmpty(int depth, int width, long[] hashA) {
        return new CountMinSketch(depth, width, 0, hashA.clone(), new long[depth][width]);
    }

    /** Merge cmses[1:] into cmses[0] */
    public static CountMinSketch merge(Stream<CountMinSketch> cmses) {
        CountMinSketch baseA[] = {null}; // wrap in array so we can modify in forEach
        cmses.forEach(cms -> {
            if (baseA[0] == null) {
                baseA[0] = cms;
            } else {
                CountMinSketch base = baseA[0];
                assert base.depth == cms.depth && base.width == cms.width && Arrays.equals(base.hashA, cms.hashA);
                for (int i = 0; i < base.table.length; i++) {
                    for (int j = 0; j < base.table[i].length; j++) {
                        base.table[i][j] += cms.table[i][j];
                    }
                }
                base.size += cms.size;
            }
        });
        return baseA[0];
    }
}
