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

import com.samsung.sra.datastore.StreamException;
import org.apache.commons.math3.util.Pair;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.StreamTokenizer;
import java.util.*;

import static org.apache.commons.math3.util.FastMath.*;

public class LogarithmicAgeLengths {
    private Random random = new Random();
    private final TreeMap<Double, AgeLengthClass> cdf = new TreeMap<>();

    public LogarithmicAgeLengths(List<AgeLengthClass> classes, List<Double> weights) {
        double normfact = 0d;
        for (Double weight: weights) {
            normfact += weight;
        }
        double cumsum = 0;
        for (int i = 0; i < classes.size(); ++i) {
            AgeLengthClass cls = classes.get(i);
            double weight = weights.get(i);
            cdf.put(cumsum, cls);
            cumsum += weight / normfact;
        }
    }

    public LogarithmicAgeLengths(long streamAge, long streamLength, int nAgeClasses, int nLengthClasses, List<Double> weights) throws StreamException {
        this(getAgeLengthClasses(streamAge, streamLength, nAgeClasses, nLengthClasses), weights);
    }

    public Collection<AgeLengthClass> getAllClasses() {
        return cdf.values();
    }

    public AgeLengthClass selectRandomAgeLengthClass() {
        return cdf.floorEntry(random.nextDouble()).getValue();
    }

    public Pair<Long, Long> sample() {
        return selectRandomAgeLengthClass().sample(random);
    }

    public static List<AgeLengthClass> getAgeLengthClasses(long streamAge, long streamLength, int nAgeClasses, int nLengthClasses) {
        assert streamAge > 0 && streamLength > 0;
        assert nAgeClasses > 0 && nLengthClasses > 0;

        long[] ageMarkers = new long[nAgeClasses + 1];
        // Query age is the stream age divided into roughly equal bins of size sAge/#age categories
        double ageBSize = log(2, streamAge)/ nAgeClasses; // remainder: log(sAge)%nAge
        ageMarkers[0] = 1;
        for (int i = 1; i < nAgeClasses; ++i) {
            ageMarkers[i] = (long)ceil(pow(2, ageBSize * i));
        }
        ageMarkers[nAgeClasses] = streamAge + 1;

        /** Query length can be much smaller than the stream length
         * 0.1% for 1M stream = 1000, 10% = 100K
         *
         * or based on loglog(sAge); n = loglog(2^32) -> 32 -> 5 ; range = 1(or 2)  to  (n-1)
         * We can ignore first 'r' and last 'k' bins; divvy up rest in (n - (r+k))/nAge)
         * from 2^2^0 - 2^2^1, 2^2^1 - 2^2^2, 2^2^2 - 2^2^3, 2^2^3 - 2^2^4, 2^2^4 - 2^2^5
         * bin 1: 2-4; bin 2: 4-32; bin 3: 32-256, bin 4: 256-65536, bin 5: 65536-4294967296;
         */
        long[] lengthMarkers = new long[nLengthClasses + 1];
        double lengthBSize = log(2, log(2, streamLength)) / nLengthClasses;
        lengthMarkers[0] = 1;
        for (int i = 1; i < nLengthClasses; ++i) {
            lengthMarkers[i] = (long)ceil(pow(2, pow(2, lengthBSize * i)));
        }
        lengthMarkers[nLengthClasses] = streamLength + 1;

        List<AgeLengthClass> ret = new ArrayList<>();
        for (int a = 0; a < nAgeClasses; ++a) {
            AgeLengthClass.Bin ageBin = new AgeLengthClass.Bin("a" + a, ageMarkers[a], ageMarkers[a+1] - 1, 1);
            for (int l = 0; l < nLengthClasses; ++l) {
                AgeLengthClass.Bin lengthBin = new AgeLengthClass.Bin("l" + l, lengthMarkers[l], lengthMarkers[l+1] - 1, 1);
                ret.add(new AgeLengthClass(ageBin, lengthBin));
            }
        }
        return ret;
    }

    public static LogarithmicAgeLengths constructFromFileSpec(long streamAge, long streamLength, String filename) throws IOException, StreamException {
        int nAgeClasses, nLengthClasses;
        List<Double> weights;
        try (BufferedReader r = new BufferedReader(new FileReader(filename))) {
            StreamTokenizer st = new StreamTokenizer(r);
            st.nextToken();
            nAgeClasses = (int) st.nval;
            st.nextToken();
            nLengthClasses = (int) st.nval;

            weights = new ArrayList<>();
            int i = 0, token;
            boolean eof = false;
            do {
                token = st.nextToken();
                i++;

                switch (token) {
                    case StreamTokenizer.TT_WORD:
                        System.out.println("Word: " + st.sval);
                        break;
                    case StreamTokenizer.TT_NUMBER:
                        //System.out.println("Number: " + st.nval);
                        weights.add(st.nval);
                        break;
                    default:
                        //System.out.println((char) token + " encountered.");
                }

                if (i == nAgeClasses * nLengthClasses || token == '!') {
                    //System.out.println("eof: " + i);
                    eof = true;
                }

            } while (!eof);
            if (token == '!' && weights.size() < nAgeClasses * nLengthClasses) { // entry to indicate same p values for all
                for (int j = weights.size(); j < nAgeClasses * nLengthClasses; j++)
                    weights.add(st.nval);
            }
        }
        return new LogarithmicAgeLengths(streamAge, streamLength, nAgeClasses, nLengthClasses, weights);
    }

    public static void main(String[] args) {
        long streamAge = 1_000_000, streamLength = 1_000_000;
        LogarithmicAgeLengths alc;

        assert args.length == 2 && args[0].equalsIgnoreCase("-f");
        try {
            alc = constructFromFileSpec(streamAge, streamLength, args[1]);

            for (int i=0; i<10; i++) {
                System.out.println(alc.selectRandomAgeLengthClass());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
