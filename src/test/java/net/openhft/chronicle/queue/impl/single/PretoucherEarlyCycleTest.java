package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.wire.DocumentContext;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.stream.IntStream.range;
import static net.openhft.chronicle.queue.DirectoryUtils.tempDir;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class PretoucherEarlyCycleTest {
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    private final List<Integer> capturedCycles = new ArrayList<>();
    private final PretoucherTest.CapturingChunkListener chunkListener = new PretoucherTest.CapturingChunkListener();

    @Test
    public void shouldHandleEarlyCycleRollByPretoucher() {
        System.setProperty("SingleChronicleQueueExcerpts.earlyAcquireNextCycle", "true");
        System.setProperty("SingleChronicleQueueExcerpts.pretoucherPrerollTimeMs", "100");
        cycleRollByPretoucher(100);
    }

    private void cycleRollByPretoucher(int earlyMillis) {
        File dir = tempDir("shouldHandleEarlyCycleRoll");
        clock.set(100);
        try (final SingleChronicleQueue queue = PretoucherTest.createQueue(dir, clock::get);
             final Pretoucher pretoucher = new Pretoucher(PretoucherTest.createQueue(dir, clock::get), chunkListener, capturedCycles::add)) {

            range(0, 10).forEach(i -> {
                try (final DocumentContext ctx = queue.acquireAppender().writingDocument()) {
                    assertEquals(i == 0 ? 0 : i + 1, capturedCycles.size());
                    ctx.wire().write().int32(i);

                    ctx.wire().write().bytes(new byte[1024]);
                }
                try {
                    pretoucher.execute();
                } catch (InvalidEventHandlerException e) {
                    throw Jvm.rethrow(e);
                }
                assertEquals(i + 1, capturedCycles.size());
                clock.addAndGet(950 - earlyMillis);
                try {
                    pretoucher.execute();
                } catch (InvalidEventHandlerException e) {
                    throw Jvm.rethrow(e);
                }
                clock.addAndGet(50 + earlyMillis);
                assertEquals(i + 2, capturedCycles.size());
            });

            assertEquals(11, capturedCycles.size());
            assertFalse(chunkListener.chunkMap.isEmpty());
        }
    }
}