package com.samsung.sra.DataStore;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.TreeMap;

class StreamInfo implements Serializable {
    public final StreamID streamID;
    // Object that readers and writers respectively will use "synchronized" with (acts as a mutex)
    public final Object readerSyncObj = new Object(), writerSyncObj = new Object();
    // How many values have we inserted so far?
    public int numValues = 0;
    // What was the timestamp of the latest value appended?
    public Timestamp lastValueTimestamp = null;

    // TODO: register an object to track what data structure we will use for each bucket

    /** All buckets for this stream */
    public final LinkedHashMap<BucketID, BucketMetadata> buckets = new LinkedHashMap<BucketID, BucketMetadata>();
    /** If there is an active (unclosed) landmark bucket, its ID */
    public BucketID activeLandmarkBucket = null;
    /** Index mapping bucket.tStart -> bucketID, used to answer queries */
    public final TreeMap<Timestamp, BucketID> timeIndex = new TreeMap<Timestamp, BucketID>();

    StreamInfo(StreamID streamID) {
        this.streamID = streamID;
    }

    public void reconstructTimeIndex() {
        timeIndex.clear();
        for (BucketMetadata bucketMetadata : buckets.values()) {
            assert bucketMetadata.tStart != null;
            timeIndex.put(bucketMetadata.tStart, bucketMetadata.bucketID);
        }
    }

}
