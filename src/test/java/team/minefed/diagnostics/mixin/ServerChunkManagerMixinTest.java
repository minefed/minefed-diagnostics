package team.minefed.diagnostics.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.junit.jupiter.api.Test;
import team.minefed.diagnostics.Hooks;

import static org.junit.jupiter.api.Assertions.*;

class ServerChunkManagerMixinTest {
    @Test
    void lithiumExceptionClearsObservationAndPreservesOriginalException() throws Exception {
        RuntimeException failure = new IllegalStateException("chunk request failed");
        withActiveObservation((mixin, waits) -> {
            Operation<Chunk> original = arguments -> { throw failure; };
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> invokeLithium(mixin, true, original));
            assertSame(failure, thrown.getCause());
            assertFalse(waits.containsKey(Thread.currentThread()));
        });
    }

    @Test
    void lithiumSuccessKeepsArgumentsAndClearsObservation() throws Exception {
        withActiveObservation((mixin, waits) -> {
            Operation<Chunk> original = arguments -> {
                assertArrayEquals(new Object[]{7, 9, null, false}, arguments);
                return null;
            };
            assertNull(invokeLithium(mixin, false, original));
            assertFalse(waits.containsKey(Thread.currentThread()));
        });
    }

    @Test
    void vanillaSuccessfulSubmissionKeepsFutureAndJoinClearsObservation() throws Exception {
        withActiveObservation((mixin, waits) -> {
            Object result = new Object();
            Supplier<Object> supplier = () -> result;
            Executor executor = Runnable::run;
            CompletableFuture<Object> future = CompletableFuture.completedFuture(result);
            Operation<CompletableFuture<Object>> submit = arguments -> {
                assertArrayEquals(new Object[]{supplier, executor}, arguments);
                return future;
            };
            assertSame(future, invokeSubmit(mixin, supplier, executor, submit));
            assertTrue(waits.containsKey(Thread.currentThread()), "submission alone does not end the waiting call");
            assertSame(result, invokeJoin(mixin, future));
            assertFalse(waits.containsKey(Thread.currentThread()));
        });
    }

    @Test
    void vanillaRejectedSubmissionClearsObservationAndPreservesException() throws Exception {
        RejectedExecutionException failure = new RejectedExecutionException("executor stopped");
        withActiveObservation((mixin, waits) -> {
            Operation<CompletableFuture<Object>> submit = arguments -> { throw failure; };
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> invokeSubmit(mixin, () -> null, Runnable::run, submit));
            assertSame(failure, thrown.getCause());
            assertFalse(waits.containsKey(Thread.currentThread()));
        });
    }

    @Test
    void vanillaSubmissionErrorAlsoClearsObservationAndPreservesError() throws Exception {
        AssertionError failure = new AssertionError("submission error");
        withActiveObservation((mixin, waits) -> {
            Operation<CompletableFuture<Object>> submit = arguments -> { throw failure; };
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> invokeSubmit(mixin, () -> null, Runnable::run, submit));
            assertSame(failure, thrown.getCause());
            assertFalse(waits.containsKey(Thread.currentThread()));
        });
    }

    @Test
    void vanillaFailedJoinClearsObservationAndPreservesCompletionException() throws Exception {
        CompletionException failure = new CompletionException(new IllegalStateException("chunk generation failed"));
        withActiveObservation((mixin, waits) -> {
            CompletableFuture<Object> future = CompletableFuture.failedFuture(failure);
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> invokeJoin(mixin, future));
            assertSame(failure, thrown.getCause());
            assertFalse(waits.containsKey(Thread.currentThread()));
        });
    }

    private static Object invokeLithium(ServerChunkManagerMixin mixin, boolean create, Operation<Chunk> operation)
            throws Exception {
        Method method = ServerChunkManagerMixin.class.getDeclaredMethod("minefedDiagnostics$getChunkOffThreadLithium",
                int.class, int.class, ChunkStatus.class, boolean.class, Operation.class);
        method.setAccessible(true);
        return method.invoke(mixin, 7, 9, null, create, operation);
    }

    private static Object invokeSubmit(ServerChunkManagerMixin mixin, Supplier<Object> supplier, Executor executor,
                                       Operation<CompletableFuture<Object>> operation) throws Exception {
        Method method = ServerChunkManagerMixin.class.getDeclaredMethod("minefedDiagnostics$submitVanilla",
                Supplier.class, Executor.class, Operation.class, int.class, int.class, ChunkStatus.class, boolean.class);
        method.setAccessible(true);
        return method.invoke(mixin, supplier, executor, operation, 7, 9, null, true);
    }

    private static Object invokeJoin(ServerChunkManagerMixin mixin, CompletableFuture<?> future) throws Exception {
        Method method = ServerChunkManagerMixin.class.getDeclaredMethod("minefedDiagnostics$joinVanilla",
                CompletableFuture.class, Operation.class);
        method.setAccessible(true);
        Operation<Object> operation = arguments -> ((CompletableFuture<?>) arguments[0]).join();
        return method.invoke(mixin, future, operation);
    }

    private static void withActiveObservation(Check check) throws Exception {
        ServerChunkManagerMixin mixin = new ServerChunkManagerMixin() {};
        mixin.serverThread = new Thread("test-server-thread");
        Field field = Hooks.class.getDeclaredField("OFF_THREAD");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Thread, Hooks.OffThreadWait> waits = (Map<Thread, Hooks.OffThreadWait>) field.get(null);
        Thread current = Thread.currentThread();
        // Seed the real registry without booting a Minecraft server or enabling a diagnostics service.
        // Production wrappers must preserve it until completion, then remove it on either exit path.
        waits.put(current, new Hooks.OffThreadWait(current, "test", 7, 9, "full", true, System.nanoTime()));
        try { check.run(mixin, waits); }
        finally { waits.remove(current); }
    }

    @FunctionalInterface
    private interface Check {
        void run(ServerChunkManagerMixin mixin, Map<Thread, Hooks.OffThreadWait> waits) throws Exception;
    }
}
