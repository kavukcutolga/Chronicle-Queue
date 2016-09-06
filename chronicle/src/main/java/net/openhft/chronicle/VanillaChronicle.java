/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle;

import net.openhft.affinity.AffinitySupport;
import net.openhft.chronicle.tools.CheckedExcerpt;
import net.openhft.lang.Jvm;
import net.openhft.lang.Maths;
import net.openhft.lang.io.FileLifecycleListener;
import net.openhft.lang.io.IOTools;
import net.openhft.lang.io.NativeBytes;
import net.openhft.lang.io.VanillaMappedBytes;
import net.openhft.lang.io.serialization.BytesMarshallableSerializer;
import net.openhft.lang.io.serialization.JDKObjectSerializer;
import net.openhft.lang.io.serialization.JDKZObjectSerializer;
import net.openhft.lang.io.serialization.ObjectSerializer;
import net.openhft.lang.io.serialization.impl.VanillaBytesMarshallerFactory;
import net.openhft.lang.model.constraints.NotNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by peter
 */
public class VanillaChronicle implements Chronicle {

    public static final long MIN_CYCLE_LENGTH = TimeUnit.HOURS.toMillis(1);
    /**
     * Number of most-significant bits used to hold the thread id in index entries. The remaining
     * least-significant bits of the index entry are used for the data offset info.
     */
    public static final int THREAD_ID_BITS = Integer.getInteger("os.max.pid.bits", Jvm.PID_BITS);
    /**
     * Mask used to validate that the thread id does not exceed the allocated number of bits.
     */
    public static final long THREAD_ID_MASK = -1L >>> -THREAD_ID_BITS;
    /**
     * Number of least-significant bits used to hold the data offset info in index entries.
     */
    public static final int INDEX_DATA_OFFSET_BITS = 64 - THREAD_ID_BITS;
    /**
     * Mask used to extract the data offset info from an index entry.
     */
    public static final long INDEX_DATA_OFFSET_MASK = -1L >>> -INDEX_DATA_OFFSET_BITS;

    @NotNull
    final ChronicleQueueBuilder.VanillaChronicleQueueBuilder builder;

    @NotNull
    final FileLifecycleListener lifecycleListener;

    private final String name;
    private final ThreadLocal<WeakReference<ObjectSerializer>> marshallersCache;
    private final ThreadLocal<WeakReference<VanillaTailer>> tailerCache;
    private final ThreadLocal<WeakReference<VanillaAppender>> appenderCache;
    private final VanillaIndexCache indexCache;
    private final VanillaDataCache dataCache;
    private final VanillaDateCache dateCache;
    private final int indexBlockSizeBits;
    private final int indexBlockSizeMask;
    private final int indexBlockLongsBits;
    private final int indexBlockLongsMask;
    private final int dataBlockSizeBits;
    private final int dataBlockSizeMask;
    private final int entriesForCycleBits;
    private final long entriesForCycleMask;

    //    private volatile int cycle;
    private final AtomicLong lastWrittenIndex = new AtomicLong(-1L);
    private volatile boolean closed = false;

    VanillaChronicle(ChronicleQueueBuilder.VanillaChronicleQueueBuilder builder) {
        this.builder = builder.clone();
        this.lifecycleListener = this.builder.fileLifecycleListener();
        this.marshallersCache = new ThreadLocal<>();
        this.tailerCache = new ThreadLocal<>();
        this.appenderCache = new ThreadLocal<>();
        this.name = builder.path().getName();

        this.dateCache = new VanillaDateCache(
            builder.cycleFormat(), builder.cycleLength(), builder.cycleTimeZone());

        this.indexBlockSizeBits = Maths.intLog2(builder.indexBlockSize());
        this.indexBlockSizeMask = -1 >>> -indexBlockSizeBits;
        this.indexBlockLongsBits = indexBlockSizeBits - 3;
        this.indexBlockLongsMask = indexBlockSizeMask >>> 3;
        this.indexCache = new VanillaIndexCache(this.builder, dateCache, indexBlockSizeBits, builder.fileLifecycleListener());

        this.dataBlockSizeBits = Maths.intLog2(builder.dataBlockSize());
        this.dataBlockSizeMask = -1 >>> -dataBlockSizeBits;
        this.dataCache = new VanillaDataCache(this.builder, dateCache, dataBlockSizeBits);

        this.entriesForCycleBits = Maths.intLog2(this.builder.entriesPerCycle());
        this.entriesForCycleMask = -1L >>> -entriesForCycleBits;
    }

    public boolean isClosed() {
        return closed;
    }

    void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException(builder.path() + " is closed");
        }
    }

    @Override
    public String name() {
        return name;
    }

    public int getEntriesForCycleBits() {
        return entriesForCycleBits;
    }

    ObjectSerializer acquireSerializer() {
        WeakReference<ObjectSerializer> serializerRef = marshallersCache.get();

        ObjectSerializer serializer = null;
        if (serializerRef != null) {
            serializer = serializerRef.get();
        }

        if (serializer == null) {
            serializer = BytesMarshallableSerializer.create(
                new VanillaBytesMarshallerFactory(),
                builder.useCompressedObjectSerializer()
                    ? JDKZObjectSerializer.INSTANCE
                    : JDKObjectSerializer.INSTANCE
            );

            marshallersCache.set(new WeakReference<>(serializer));
        }

        return serializer;
    }

    @NotNull
    @Override
    public ExcerptTailer createTailer() throws IOException {
        WeakReference<VanillaTailer> ref = tailerCache.get();
        VanillaTailer tailer = null;

        if (ref != null) {
            tailer = ref.get();
            if (tailer != null && tailer.unmapped()) {
                tailer = null;
            }
        }

        if (tailer == null) {
            tailer = createTailer0();
            tailerCache.set(new WeakReference<>(tailer));
        }

        return tailer;
    }

    private VanillaTailer createTailer0() {
        return new VanillaTailerImpl();
    }

    @NotNull
    @Override
    public VanillaAppender createAppender() throws IOException {
        WeakReference<VanillaAppender> ref = appenderCache.get();
        VanillaAppender appender = null;

        if (ref != null) {
            appender = ref.get();
            if (appender != null && appender.unmapped()) {
                appender = null;
            }
        }

        if (appender == null) {
            appender = createAppender0();
            appenderCache.set(new WeakReference<>(appender));
        }

        return appender;
    }

    private VanillaAppender createAppender0() {
        final VanillaAppender appender = new VanillaAppenderImpl();

        return !builder.useCheckedExcerpt()
                ? appender
                : new VanillaCheckedAppender(appender);
    }

    @NotNull
    @Override
    public Excerpt createExcerpt() throws IOException {
        final Excerpt excerpt = builder.useCheckedExcerpt()
                ? new VanillaExcerpt()
                : new VanillaCheckedExcerpt(new VanillaExcerpt());

        return excerpt;
    }

    @Override
    public long lastWrittenIndex() {
        return lastWrittenIndex.get();
    }

    /**
     * This method returns the very last index in the chronicle.  Not to be confused with
     * lastWrittenIndex(), this method returns the actual last index by scanning the underlying data
     * even the appender has not been activated.
     *
     * @return The last index in the file
     */
    @Override
    public long lastIndex() {
        int cycle = (int) indexCache.lastCycle();
        int lastIndexCount = indexCache.lastIndexFile(cycle, -1);
        if (lastIndexCount >= 0) {
            try {
                final VanillaMappedBytes buffer = indexCache.indexFor(cycle, lastIndexCount, false);
                final long indices = VanillaIndexCache.countIndices(buffer);
                buffer.release();

                final long indexEntryNumber = (indices > 0) ? indices - 1 : 0;
                return (((long) cycle) << entriesForCycleBits) + (((long) lastIndexCount) << indexBlockLongsBits) + indexEntryNumber;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        } else {
            return -1;
        }
    }

    @Override
    public long size() {
        return lastWrittenIndex.get() + 1;
    }

    @Override
    public void close() {
        closed = true;
        indexCache.close();
        dataCache.close();
    }

    @Override
    public void clear() {
        indexCache.close();
        dataCache.close();
        IOTools.deleteDir(builder.path().getAbsolutePath());
    }

    public void checkCounts(int min, int max) {
        indexCache.checkCounts(min, max);
        dataCache.checkCounts(min, max);
    }

    public enum Cycle {
            SECONDS ("yyyyMMddHHmmss", 1000, 1L << 28),
            MINUTES ("yyyyMMddHHmm"  , 60 * 1000, 1L << 34),
            HOURS   ("yyyyMMddHH"    , 60 * 60 * 1000, 1L << 40),
            DAYS    ("yyyyMMdd"      , 24 * 60 * 60 * 1000, 1L << 40),
        ;

        private static Cycle[] VALUES = values();

        private final String format;
        private final int length;
        private final long entries;

        Cycle(String format, int length, long entries) {
            this.format = format;
            this.length = length;
            this.entries = entries;
        }

        public static Cycle forLength(int length) {
            for(int i=VALUES.length - 1; i >= 0; i--) {
                if(VALUES[i].length == length) {
                    return VALUES[i];
                }
            }

            throw new IllegalArgumentException("Unknown value for CycleLength (" + length + ")");
        }

        public static Cycle forFormat(String format) {
            for(int i=VALUES.length - 1; i >= 0; i--) {
                if(VALUES[i].format == format || VALUES[i].format.equals(format)) {
                    return VALUES[i];
                }
            }

            throw new IllegalArgumentException("Unknown value for CycleFormat (" + format + ")");
        }

        public String format() {
            return this.format;
        }

        public int length() {
            return this.length;
        }

        public long entries() {
            return this.entries;
        }
    }

    // *************************************************************************
    //
    // *************************************************************************

    public interface VanillaExcerptCommon extends ExcerptCommon {
        boolean unmapped();
    }

    public interface VanillaAppender extends VanillaExcerptCommon, ExcerptAppender {
        void startExcerpt(long capacity, int cycle);
    }

    public interface VanillaTailer extends VanillaExcerptCommon, ExcerptTailer {
        public String getActiveWorkingDirectory();
    }

    // *************************************************************************
    //
    // *************************************************************************

    private abstract class AbstractVanillaExcerpt extends NativeBytes implements VanillaExcerptCommon {
        protected VanillaMappedBytes indexBytes;
        protected VanillaMappedBytes dataBytes;
        private long index = -1;
        private int lastCycle = Integer.MIN_VALUE;
        private int lastIndexCount = Integer.MIN_VALUE;
        private int lastThreadId = Integer.MIN_VALUE;
        private int lastDataCount = Integer.MIN_VALUE;

        public AbstractVanillaExcerpt() {
            super(acquireSerializer(), NO_PAGE, NO_PAGE, null);
        }

        @Override
        public boolean unmapped() {
            return (indexBytes == null || dataBytes == null) || indexBytes.unmapped() && dataBytes.unmapped();
        }

        @Override
        public boolean wasPadding() {
            return false;
        }

        @Override
        public long index() {
            return index;
        }

        protected void setIndex(long index) {
            this.index = index;
        }

        @Override
        public long size() {
            return lastWrittenIndex() + 1;
        }

        @Override
        public Chronicle chronicle() {
            return VanillaChronicle.this;
        }

        public int cycle() {
            return (int) (System.currentTimeMillis() / builder.cycleLength());
        }

        public boolean index(long nextIndex) {
            checkNotClosed();

            try {
                int cycle = (int) (nextIndex >>> entriesForCycleBits);
                int indexCount = (int) ((nextIndex & entriesForCycleMask) >>> indexBlockLongsBits);
                int indexOffset = (int) (nextIndex & indexBlockLongsMask);
                long indexValue;
                boolean indexFileChange = false;

                try {
                    if (lastCycle != cycle || lastIndexCount != indexCount || indexBytes == null) {
                        if (indexBytes != null) {
                            indexBytes.release();
                            indexBytes = null;
                        }
                        if (dataBytes != null) {
                            dataBytes.release();
                            dataBytes = null;
                        }

                        indexBytes = indexCache.indexFor(cycle, indexCount, false);
                        indexFileChange = true;
                        assert indexBytes.refCount() > 1;
                        lastCycle = cycle;
                        lastIndexCount = indexCount;
                    }
                    indexValue = indexBytes.readVolatileLong(indexOffset << 3);
                } catch (FileNotFoundException e) {
                    return false;
                }

                if (indexValue == 0) {
                    return false;
                }

                int threadId = (int) (indexValue >>> INDEX_DATA_OFFSET_BITS);
                long dataOffset0 = indexValue & INDEX_DATA_OFFSET_MASK;
                int dataCount = (int) (dataOffset0 >>> dataBlockSizeBits);
                int dataOffset = (int) (dataOffset0 & dataBlockSizeMask);

                if (lastThreadId != threadId || lastDataCount != dataCount || indexFileChange) {
                    if (dataBytes != null) {
                        dataBytes.release();
                        dataBytes = null;
                    }
                }

                if (dataBytes == null) {
                    dataBytes = dataCache.dataFor(cycle, threadId, dataCount, false);
                    lastThreadId = threadId;
                    lastDataCount = dataCount;
                }

                int len = dataBytes.readVolatileInt(dataOffset - 4);
                if (len == 0)
                    return false;

                int len2 = ~len;
                // invalid if either the top two bits are set,
                if ((len2 >>> 30) != 0) {
                    throw new IllegalStateException("Corrupted length " + Integer.toHexString(len));
                }

                startAddr = positionAddr = dataBytes.startAddr() + dataOffset;
                limitAddr = startAddr + ~len;
                capacityAddr = limitAddr;

                index = nextIndex;
                finished = false;

                return true;
            } catch (FileNotFoundException ex){
                return false;
            } catch(IOException ioe) {
                throw new AssertionError(ioe);
            }
        }

        public boolean nextIndex() {
            checkNotClosed();
            if (index < 0) {
                toStart();
                if (index < 0)
                    return false;
            }

            long nextIndex = index + 1;
            while (true) {
                boolean found = index(nextIndex);
                if (found) {
                    index = nextIndex;
                    return true;
                }

                int cycle = (int) (nextIndex / builder.entriesPerCycle());
                if (cycle >= cycle()) {
                    return false;
                }

                nextIndex = (cycle + 1) * builder.entriesPerCycle();
            }
        }

        @NotNull
        public ExcerptCommon toStart() {
            int cycle = (int) indexCache.firstCycle();
            if (cycle >= 0) {
                index = (cycle * builder.entriesPerCycle()) - 1;
            }

            return this;
        }

        @NotNull
        protected ExcerptCommon toEnd() {
            long lastIndex = lastIndex();
            if (lastIndex >= 0) {
                index(lastIndex);

            } else {
                return toStart();
            }

            return this;
        }

        @Override
        public void close() {
            finished = true;

            if (indexBytes != null) {
                indexBytes.release();
                indexBytes = null;
            }

            if (dataBytes != null) {
                dataBytes.release();
                dataBytes = null;
            }

            super.close();
        }

        @Override
        protected void finalize() throws Throwable {
            close();
            super.finalize();
        }
    }

    private class VanillaExcerpt extends AbstractVanillaExcerpt implements Excerpt {
        public long findMatch(@NotNull ExcerptComparator comparator) {
            throw new UnsupportedOperationException();
        }

        public void findRange(@NotNull long[] startEnd, @NotNull ExcerptComparator comparator) {
            throw new UnsupportedOperationException();
        }

        @NotNull
        @Override
        public Excerpt toStart() {
            super.toStart();
            return this;
        }

        @NotNull
        @Override
        public Excerpt toEnd() {
            super.toEnd();
            return this;
        }
    }

    private class VanillaAppenderImpl extends AbstractVanillaExcerpt implements VanillaAppender {
        private int lastCycle;
        private int lastThreadId;
        private int appenderCycle;
        private boolean nextSynchronous;
        private long lastWrittenIndex;
        private long[] positionArr = {0L};
        private int lastDataIndex;
        private int lastIndexIndex;

        public VanillaAppenderImpl() {
            this.lastCycle = Integer.MIN_VALUE;
            this.lastThreadId = Integer.MIN_VALUE;
            this.lastWrittenIndex = -1;
            this.appenderCycle = -1;
            this.nextSynchronous = builder.synchronous();
            this.lastDataIndex = 0;
            this.lastIndexIndex = 0;
        }

        @Override
        public void startExcerpt() {
            startExcerpt(builder.defaultMessageSize(), cycle());
        }

        @Override
        public void startExcerpt(long capacity) {
            startExcerpt(capacity, cycle());
        }

        @Override
        public void startExcerpt(long capacity, int cycle) {
            checkNotClosed();
            try {
                appenderCycle = cycle;
                int appenderThreadId = AffinitySupport.getThreadId();
                assert (appenderThreadId & THREAD_ID_MASK) == appenderThreadId : "appenderThreadId: " + appenderThreadId;

                if (appenderCycle != lastCycle) {
                    if (indexBytes != null) {
                        if(builder.syncOnRoll()) {
                            indexBytes.force();
                        }

                        indexBytes.release();
                        indexBytes = null;
                    }
                    if (dataBytes != null) {
                        if(builder.syncOnRoll()) {
                            dataBytes.force();
                        }

                        dataBytes.release();
                        dataBytes = null;
                    }

                    lastCycle = appenderCycle;
                    lastIndexIndex = indexCache.lastIndexFile(lastCycle);
                    lastThreadId = appenderThreadId;

                } else if (appenderThreadId != lastThreadId) {
                    if (dataBytes != null) {
                        if(builder.syncOnRoll()) {
                            dataBytes.force();
                        }

                        dataBytes.release();
                        dataBytes = null;
                    }

                    lastThreadId = appenderThreadId;
                }

                if (dataBytes == null) {
                    lastDataIndex = dataCache.findNextDataCount(appenderCycle, appenderThreadId);
                    dataBytes = dataCache.dataFor(appenderCycle, appenderThreadId, lastDataIndex, true);
                }

                if (dataBytes.remaining() < capacity + 4) {
                    dataBytes.release();
                    dataBytes = dataCache.dataFor(appenderCycle, appenderThreadId, ++lastDataIndex, true);
                }

                startAddr = positionAddr = dataBytes.positionAddr() + 4;
                limitAddr = startAddr + capacity;
                nextSynchronous = builder.synchronous();
                capacityAddr = limitAddr;
                finished = false;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public void addPaddedEntry() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean nextSynchronous() {
            return nextSynchronous;
        }

        @Override
        public void nextSynchronous(boolean nextSynchronous) {
            this.nextSynchronous = nextSynchronous;
        }
        @Override
        public void finish() {
            if (finished) {
                throw new IllegalStateException("Not started");
            }

            super.finish();
            if (dataBytes == null) {
                return;
            }

            int length = ~(int) (positionAddr - startAddr);
            NativeBytes.UNSAFE.putOrderedInt(null, startAddr - 4, length);

            // position of the start not the end.
            int offset = (int) (startAddr - dataBytes.address());
            long dataOffset = dataBytes.index() * builder.dataBlockSize() + offset;
            long indexValue = ((long) lastThreadId << INDEX_DATA_OFFSET_BITS) + dataOffset;

            try {
                long position = VanillaIndexCache.append(indexBytes, indexValue, nextSynchronous);
                if (position < 0) {
                    if (indexBytes != null) {
                        lastIndexIndex = (int)indexBytes.index() + 1;
                        indexBytes.release();
                        indexBytes = null;
                    }

                    indexBytes = indexCache.append(appenderCycle, indexValue, nextSynchronous, lastIndexIndex, positionArr);
                    lastIndexIndex = (int)indexBytes.index();
                    setLastWrittenIndex(appenderCycle, indexBytes.index(), positionArr[0]);

                } else {
                    setLastWrittenIndex(appenderCycle, indexBytes.index(), position);
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }

            setIndex(lastWrittenIndex() + 1);

            dataBytes.positionAddr(positionAddr);
            dataBytes.alignPositionAddr(4);

            if (nextSynchronous) {
                dataBytes.force();
            }
        }

        private long indexFrom(long cycle, long indexCount, long indexPosition) {
            return (cycle << entriesForCycleBits) + (indexCount << indexBlockLongsBits) + (indexPosition >> 3);
        }

        @NotNull
        @Override
        public ExcerptAppender toEnd() {
            // NO-OP
            return this;
        }

        @Override
        public long lastWrittenIndex() {
            return lastWrittenIndex;
        }

        protected void setLastWrittenIndex(long cycle, long indexCount, long indexPosition) {
            setLastWrittenIndex(indexFrom(cycle, indexCount, indexPosition));
        }

        protected void setLastWrittenIndex(long lastWrittenIndex) {
            this.lastWrittenIndex = lastWrittenIndex;
            for (; ; ) {
                long lwi = VanillaChronicle.this.lastWrittenIndex();
                if (lwi >= lastWrittenIndex || VanillaChronicle.this.lastWrittenIndex.compareAndSet(lwi, lastWrittenIndex)) {
                    break;
                }
            }
        }
    }

    private class VanillaTailerImpl extends AbstractVanillaExcerpt implements VanillaTailer {

        @NotNull
        @Override
        public ExcerptTailer toStart() {
            super.toStart();
            return this;
        }

        @NotNull
        @Override
        public ExcerptTailer toEnd() {
            super.toEnd();
            return this;
        }

        /**
         * Calculate working directory path from current cycle
         * @return directory path
         */
        @Override
        public String getActiveWorkingDirectory() {
            int cycle = (int)((index() >> 40) & 0xFFFFFF);
            return dateCache.formatFor(cycle);
        }
    }

    private final class VanillaCheckedExcerpt extends CheckedExcerpt implements VanillaExcerptCommon {
        public VanillaCheckedExcerpt(@NotNull VanillaExcerptCommon common) {
            super(common);
        }

        @Override
        public boolean unmapped() {
            return ((VanillaExcerptCommon) wrappedCommon).unmapped();
        }

        @Override
        protected void finalize() throws Throwable {
            close();
            super.finalize();
        }
    }

    private final class VanillaCheckedAppender extends CheckedExcerpt implements VanillaAppender {
        public VanillaCheckedAppender(@NotNull VanillaAppender common) {
            super(common);
        }

        @Override
        public boolean unmapped() {
            return ((VanillaExcerptCommon) wrappedCommon).unmapped();
        }

        @Override
        public void startExcerpt(long capacity, int cycle) {
            ((VanillaAppender) wrappedCommon).startExcerpt(capacity, cycle);
        }

        @Override
        protected void finalize() throws Throwable {
            close();
            super.finalize();
        }
    }
}
