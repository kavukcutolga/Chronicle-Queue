/*
 * Copyright 2016 higherfrequencytrading.com
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

package net.openhft.chronicle.queue;

import net.openhft.affinity.AffinityLock;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.NativeBytes;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.WireType;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;

public class ChronicleQueueTwoThreads extends ChronicleQueueTestBase {

    private static final int BYTES_LENGTH = 256;
    private static final int BLOCK_SIZE = 256 << 20;
    private static final long INTERVAL_US = 10;

    @Test(timeout = 10000)
    public void testUnbuffered() throws IOException, InterruptedException {
        doTest(false);
    }

    void doTest(boolean buffered) throws IOException, InterruptedException {
        String path = getTmpDir() + "/deleteme.q";

        new File(path).deleteOnExit();

        AtomicLong counter = new AtomicLong();
        Thread tailerThread = new Thread(() -> {
            AffinityLock rlock = AffinityLock.acquireLock();
            Bytes bytes = NativeBytes.nativeBytes(BYTES_LENGTH).unchecked(true);
            try (ChronicleQueue rqueue = new SingleChronicleQueueBuilder(path)
                    .wireType(WireType.FIELDLESS_BINARY)
                    .blockSize(BLOCK_SIZE)
                    .build()) {

                ExcerptTailer tailer = rqueue.createTailer();

                while (!Thread.interrupted()) {
                    bytes.clear();
                    if (tailer.readBytes(bytes)) {
                        counter.incrementAndGet();
                    }
                }

            } finally {
                if (rlock != null) {
                    rlock.release();
                }
                System.out.printf("Read %,d messages", counter.intValue());
            }
        }, "tailer thread");

        long runs = 50_000;

        Thread appenderThread = new Thread(() -> {
            AffinityLock wlock = AffinityLock.acquireLock();
            try {
                ChronicleQueue wqueue = new SingleChronicleQueueBuilder(path)
                        .wireType(WireType.FIELDLESS_BINARY)
                        .blockSize(BLOCK_SIZE)
                        .buffered(buffered)
                        .build();

                ExcerptAppender appender = wqueue.createAppender();

                Bytes bytes = Bytes.allocateDirect(BYTES_LENGTH).unchecked(true);

                long next = System.nanoTime() + INTERVAL_US * 1000;
                for (int i = 0; i < runs; i++) {
                    while (System.nanoTime() < next)
                        /* busy wait*/ ;
                    long start = next;
                    bytes.readPositionRemaining(0, BYTES_LENGTH);
                    bytes.writeLong(0L, start);

                    appender.writeBytes(bytes);
                    next += INTERVAL_US * 1000;
                }
                wqueue.close();
            } finally {
                if (wlock != null) {
                    wlock.release();
                }
            }
        }, "appender thread");

        tailerThread.start();
        Jvm.pause(100);

        appenderThread.start();
        appenderThread.join();

        //Pause to allow tailer to catch up (if needed)
        for (int i = 0; i < 10; i++) {
            if (runs != counter.get())
                Jvm.pause(100);
        }

        for (int i = 0; i < 10; i++) {
            tailerThread.interrupt();
            tailerThread.join(100);
        }

        assertEquals(runs, counter.get());

    }
}
