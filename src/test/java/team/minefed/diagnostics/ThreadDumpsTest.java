package team.minefed.diagnostics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ThreadDumpsTest {
    @Test
    void repeatedSamplesNeverRequestOwnedLocksOrUnboundedStacks() {
        ThreadInfo snapshot = currentSnapshot();
        SafeBean fake = new SafeBean(snapshot);

        for (int i = 0; i < 20; i++) {
            assertSame(snapshot, ThreadDumps.sample(fake.bean, snapshot.getThreadId()));
        }
        assertEquals(20, fake.sampleCalls);
        assertTrue(snapshot.getStackTrace().length > 0);

        fake.byId.clear();
        assertNull(ThreadDumps.sample(fake.bean, snapshot.getThreadId()), "terminated thread is absent");
        assertEquals(21, fake.sampleCalls);
    }

    @Test
    void boundedSamplesAndRepeatedDumpsPreserveBlockedOwnerAndStack() throws Exception {
        Object monitor = new Object();
        CountDownLatch attempting = new CountDownLatch(1);
        Thread blocked = new Thread(() -> {
            attempting.countDown();
            synchronized (monitor) {
                // Release naturally when the test exits the synchronized block below.
            }
        }, "diagnostics-blocked-thread");
        blocked.setDaemon(true);

        try {
            synchronized (monitor) {
                blocked.start();
                assertTrue(attempting.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (blocked.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertEquals(Thread.State.BLOCKED, blocked.getState());
                ThreadInfo waiting = ManagementFactory.getThreadMXBean()
                        .getThreadInfo(blocked.getId(), ThreadDumps.MAX_STACK_DEPTH);
                assertNotNull(waiting);
                ThreadInfo owner = currentSnapshot();
                SafeBean fake = new SafeBean(owner, waiting);

                ThreadInfo sampled = ThreadDumps.sample(fake.bean, blocked.getId());
                assertEquals(Thread.State.BLOCKED, sampled.getThreadState());
                assertEquals(owner.getThreadId(), sampled.getLockOwnerId());
                assertEquals(owner.getThreadName(), sampled.getLockOwnerName());
                assertNotNull(sampled.getLockInfo());
                assertEquals(0, sampled.getLockedMonitors().length);
                assertEquals(0, sampled.getLockedSynchronizers().length);
                assertTrue(ThreadDumps.waitDescription(sampled).contains(owner.getThreadName()));

                for (int i = 0; i < 3; i++) {
                    StringBuilder out = new StringBuilder();
                    ThreadDumps.appendAllThreads(fake.bean, out, new ModAttribution(), blocked.getId(),
                            Map.of(blocked.getId(), "waiting for test monitor"));
                    String text = out.toString();
                    assertTrue(text.indexOf("\"" + blocked.getName() + "\" id=")
                            < text.indexOf("\"" + owner.getThreadName() + "\" id="), text);
                    assertTrue(text.contains("blocked on " + sampled.getLockInfo()), text);
                    assertTrue(text.contains("owned by \"" + owner.getThreadName() + "\""), text);
                    assertTrue(text.contains(ThreadDumpsTest.class.getName()), text);
                    assertTrue(text.contains("waiting for test monitor"), text);
                    assertTrue(text.contains("Stacks limited to 256 frames per thread"), text);
                    assertTrue(text.contains("owned monitor/synchronizer lists omitted"), text);
                }
                assertEquals(3, fake.dumpCalls);
            }
        } finally {
            blocked.join(5_000);
            assertFalse(blocked.isAlive(), "test thread must terminate after its monitor is released");
        }
    }

    @Test
    void deadlockIdsAndStackDetailsSurviveWithoutOwnedLockCollection() {
        ThreadInfo snapshot = currentSnapshot();
        SafeBean fake = new SafeBean(snapshot);
        fake.deadlockedIds = new long[]{snapshot.getThreadId(), Long.MAX_VALUE};

        StringBuilder out = new StringBuilder();
        ThreadDumps.appendDeadlocks(fake.bean, out, new ModAttribution());
        assertEquals(1, fake.synchronizerDeadlockQueries);
        assertArrayEquals(fake.deadlockedIds, fake.requestedIds);
        assertEquals(1, fake.sampleCalls);
        assertTrue(out.toString().contains("2 THREADS DEADLOCKED"));
        assertTrue(out.toString().contains(snapshot.getThreadName()));
        assertTrue(out.toString().contains(ThreadDumpsTest.class.getName()));
        assertTrue(out.toString().contains(">>> deadlocked"));
        assertTrue(out.toString().contains("owned monitor/synchronizer lists omitted"));

        fake.synchronizerSupported = false;
        out.setLength(0);
        ThreadDumps.appendDeadlocks(fake.bean, out, new ModAttribution());
        assertEquals(1, fake.monitorDeadlockQueries);
        assertTrue(out.toString().contains("2 THREADS DEADLOCKED"));

        fake.deadlockedIds = null;
        out.setLength(0);
        ThreadDumps.appendDeadlocks(fake.bean, out, new ModAttribution());
        assertEquals("JVM deadlock detection: none\n", out.toString());
        assertEquals(2, fake.sampleCalls, "no snapshot request when no deadlock is found");
    }

    @Test
    void deepStacksAreBoundedAndTheirLimitIsVisible() {
        ThreadInfo snapshot = deepSnapshot(ThreadDumps.MAX_STACK_DEPTH + 32);
        assertEquals(ThreadDumps.MAX_STACK_DEPTH, snapshot.getStackTrace().length);
        StringBuilder out = new StringBuilder();
        ThreadDumps.appendThread(out, snapshot, new ModAttribution(), null);
        assertTrue(out.toString().contains("stack limit reached (256 frames)"));
    }

    private static ThreadInfo deepSnapshot(int remaining) {
        return remaining == 0 ? ThreadDumps.sample(Thread.currentThread().getId()) : deepSnapshot(remaining - 1);
    }

    private static ThreadInfo currentSnapshot() {
        return ManagementFactory.getThreadMXBean()
                .getThreadInfo(Thread.currentThread().getId(), ThreadDumps.MAX_STACK_DEPTH);
    }

    /** Fails before delegation if a caller tries the heap-walking API or drops the depth bound. */
    private static final class SafeBean implements InvocationHandler {
        final Map<Long, ThreadInfo> byId = new HashMap<>();
        final ThreadInfo[] snapshots;
        final ThreadMXBean bean = (ThreadMXBean) Proxy.newProxyInstance(ThreadMXBean.class.getClassLoader(),
                new Class<?>[]{ThreadMXBean.class}, this);
        long[] deadlockedIds, requestedIds;
        boolean synchronizerSupported = true;
        int sampleCalls, dumpCalls, synchronizerDeadlockQueries, monitorDeadlockQueries;

        SafeBean(ThreadInfo... snapshots) {
            this.snapshots = snapshots;
            for (ThreadInfo info : snapshots) byId.put(info.getThreadId(), info);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getThreadInfo" -> {
                    assertBoundedWithoutOwnedLocks(args);
                    sampleCalls++;
                    if (args[0] instanceof long[] ids) {
                        requestedIds = ids.clone();
                        yield Arrays.stream(ids).mapToObj(byId::get).toArray(ThreadInfo[]::new);
                    }
                    yield byId.get((Long) args[0]);
                }
                case "dumpAllThreads" -> {
                    assertBoundedWithoutOwnedLocks(args);
                    assertEquals(3, args.length);
                    assertEquals(false, args[0]);
                    assertEquals(false, args[1]);
                    dumpCalls++;
                    yield snapshots.clone();
                }
                case "isObjectMonitorUsageSupported" -> true;
                case "isSynchronizerUsageSupported" -> synchronizerSupported;
                case "findDeadlockedThreads" -> {
                    synchronizerDeadlockQueries++;
                    yield deadlockedIds;
                }
                case "findMonitorDeadlockedThreads" -> {
                    monitorDeadlockQueries++;
                    yield deadlockedIds;
                }
                case "isThreadCpuTimeSupported", "isThreadCpuTimeEnabled" -> false;
                default -> throw new AssertionError("Unexpected ThreadMXBean call: " + method);
            };
        }

        private static void assertBoundedWithoutOwnedLocks(Object[] args) {
            for (Object arg : args) {
                if (arg instanceof Boolean flag) assertFalse(flag, "owned-lock inventories must never be requested");
            }
            assertEquals(ThreadDumps.MAX_STACK_DEPTH, args[args.length - 1], "every stack request must be bounded");
        }
    }
}
