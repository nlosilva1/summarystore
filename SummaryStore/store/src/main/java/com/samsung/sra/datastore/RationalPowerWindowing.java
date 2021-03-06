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
package com.samsung.sra.datastore;

import java.util.*;

/**
 * R of size S, R * 2^(p-1) of size S * 2^q, ..., R * k^(p-1) of size S * k^q, ...
 * Decay b(n) = O(n^(-q/(p+q)))
 */
public class RationalPowerWindowing implements Windowing {
    private final long p, q, R, S;

    public RationalPowerWindowing(long p, long q, long R, long S) {
        if (p < 1 || q < 0 || R < 1 || S < 1) throw new IllegalArgumentException("invalid p, q, R or S");
        this.p = p;
        this.q = q;
        this.R = R;
        this.S = S;

        addOne();
    }

    /* For each distinct length l = S * k^q, k = 1, 2, 3, ... store both
             l -> left marker of first window of size l
       and the inverse mapping */
    private TreeMap<Long, Long>
            lengthToFirstMarker = new TreeMap<>(),
            firstMarkerToLength = new TreeMap<>();

    private long lastLength = 0, lastMarker = 0, lastK = 0;

    private void addOne() {
        lastLength = S * (long)Math.pow(lastK + 1, q);
        lastMarker += R * (long)Math.pow(lastK, p + q - 1);
        ++lastK;
        lengthToFirstMarker.put(lastLength, lastMarker);
        firstMarkerToLength.put(lastMarker, lastLength);
    }

    private void addUntilLength(long targetLength) {
        if (q != 0) while (lastLength < targetLength) addOne();
    }

    private void addPastMarker(long targetMarker) {
        if (q != 0) while (lastMarker <= targetMarker) addOne();
    }

    @Override
    public long getFirstContainingTime(long Tl, long Tr, long T) {
        assert 0 <= Tl && Tl <= Tr && Tr <= T-1;
        long l = T-1 - Tr, r = T-1 - Tl;
        long length = r - l + 1;
        if (q == 0 && length > S) {
            // if q = 0 the maximum length we can achieve is S
            // (lengths are unbounded for q > 0)
            return -1;
        } else {
            addUntilLength(length);
            Map.Entry<Long, Long> targetLengthEntry = lengthToFirstMarker.ceilingEntry(length);
            long lengthMarker = targetLengthEntry.getValue();
            if (lengthMarker >= l) {
                // at T', l' should be lengthMarker
                return T + lengthMarker - l;
            } else {
                // we have already hit the target length, so [l, r] is either already
                // in the same window or will be once move into the next window
                addPastMarker(l);
                targetLengthEntry = firstMarkerToLength.floorEntry(l);
                long targetLength = targetLengthEntry.getKey();
                lengthMarker = targetLengthEntry.getValue();
                // [Wl, Wr] is the window containing l
                long Wl = lengthMarker + (l - lengthMarker) / targetLength;
                long Wr = Wl + targetLength - 1;
                if (r <= Wr) {
                    // [l, r] is already in the same window, viz [Wl, Wr]
                    return T;
                } else {
                    // at T', l' should be Wr+1
                    return T + Wr+1 - l;
                }
            }
        }
    }

    @Override
    public long getSizeOfFirstWindow() {
        return S;
    }

    @Override
    public List<Long> getWindowsCoveringUpto(long N) {
        List<Long> ret = new ArrayList<>();
        long NsoFar = 0;
        for (long b = 1; ; ++b) {
            long count = (long)Math.pow(b, p-1) * R;
            long size = (long)Math.pow(b, q) * S;
            for (long i = 0; i < count; ++i) {
                if (NsoFar + size > N) {
                    return ret;
                } else {
                    ret.add(size);
                    NsoFar += size;
                }
            }
        }
    }
}
