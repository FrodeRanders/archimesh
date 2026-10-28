package org.gautelis.archimesh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

class CommitDeliveryServiceTest {
    @Test
    void failedModelRetainsOrderedIntentWhileOtherModelsProgress() throws Exception {
        try (var f = new Fixture()) {
            f.add("a","first","second"); f.add("b","healthy");
            f.failedModels.add("a");
            f.drainAndRun();
            Assertions.assertEquals(List.of("a/first","b/healthy"),f.attempts);
            Assertions.assertEquals(List.of("b/healthy"),f.published);
            f.failedModels.clear();
            f.drainAndRun();
            Assertions.assertEquals(2,f.attempts.size(),"submit-triggered scans must respect failure cooldown");
            f.clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            f.drainAndRun();
            Assertions.assertEquals(List.of("a/first","b/healthy","a/first","a/second"),f.attempts);
            Assertions.assertTrue(f.pending.get("a").isEmpty());
        }
    }

    @Test
    void busyModelCannotConsumeTheWholeSelectionWindow() throws Exception {
        try (var f = new Fixture()) {
            f.delivery.concurrentModels = 1;
            f.delivery.batchesPerModel = 2;
            for (int i=0;i<120;i++) f.add("a","batch-"+i);
            f.add("b","healthy"); f.add("c","also-healthy");
            f.drainAndRun(); f.drainAndRun(); f.drainAndRun();
            Assertions.assertEquals(List.of("a/batch-0","a/batch-1","b/healthy","c/also-healthy"),f.attempts);
            Assertions.assertEquals(118,f.pending.get("a").size());
            f.drainAndRun();
            Assertions.assertEquals("a/batch-2",f.attempts.get(4),"cursor must wrap to unfinished models");
        }
    }

    @Test
    void acknowledgmentWriteFailureBlocksOnlyThatModelsSuccessors() throws Exception {
        try (var f = new Fixture()) {
            f.add("a","first","second"); f.add("b","healthy");
            f.failedAcknowledgments.add("a/first");
            f.drainAndRun();
            Assertions.assertEquals(List.of("a/first","b/healthy"),f.attempts);
            f.failedAcknowledgments.clear(); f.clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            f.drainAndRun();
            Assertions.assertEquals(List.of("a/first","b/healthy","a/first","a/second"),f.attempts);
            Assertions.assertEquals(List.of("b/healthy","a/first","a/second"),f.published);
        }
    }

    @Test
    void corruptPayloadInOneModelDoesNotCancelOtherModels() throws Exception {
        try (var f = new Fixture()) {
            f.add("a","corrupt","later"); f.add("b","healthy");
            f.unreadableModels.add("a");
            f.drainAndRun();
            Assertions.assertEquals(List.of("b/healthy"),f.attempts);
            Assertions.assertEquals(2,f.pending.get("a").size());
        }
    }

    @Test
    void concurrentModelLimitAlsoPreventsOverlappingWorkersForTheSameModel() throws Exception {
        try (var f = new Fixture()) {
            f.delivery.concurrentModels = 2;
            f.add("a","one"); f.add("b","one"); f.add("c","one");
            f.delivery.drain(); f.delivery.drain();
            Assertions.assertEquals(2,f.manual.tasks.size(),"queued and running models count against the worker limit");
            f.manual.tasks.remove().run();
            f.delivery.drain();
            Assertions.assertEquals(2,f.manual.tasks.size());
            f.manual.runAll();
            Assertions.assertEquals(List.of("a/one","b/one","c/one"),f.attempts);
        }
    }

    @Test
    void leaseLossBeforeOrDuringDeliveryLeavesIntentUnacknowledged() throws Exception {
        try (var f = new Fixture()) {
            f.add("a","first","second"); f.lease(false);
            f.drainAndRun(); Assertions.assertTrue(f.attempts.isEmpty());
            f.lease(true);
            f.send = (model,batch) -> {
                try { f.lease(false); } catch (Exception e) { throw new RuntimeException(e); }
                return CompletableFuture.completedFuture(null);
            };
            f.drainAndRun();
            Assertions.assertEquals(List.of("a/first"),f.attempts);
            Assertions.assertTrue(f.published.isEmpty());
            Assertions.assertEquals(2,f.pending.get("a").size());
        }
    }

    @Test
    void stalledSendDoesNotBlockAnotherModelAndCannotBeOvertaken() throws Exception {
        try (var f = new Fixture()) {
            var pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
            f.delivery.workers = pool;
            f.add("a","first","second"); f.add("b","healthy");
            var stalled = new CompletableFuture<Void>();
            f.send = (model,batch) -> batch.path("opBatchId").asText().equals("first") ? stalled : CompletableFuture.completedFuture(null);
            f.delivery.drain();
            await(() -> f.published.contains("b/healthy") && f.attempts.contains("a/first"));
            Assertions.assertEquals(List.of("b/healthy"),f.published);
            for (int i=0;i<3;i++) f.delivery.drain();
            Assertions.assertEquals(1,Collections.frequency(f.attempts,"a/first"));
            Assertions.assertFalse(f.attempts.contains("a/second"));
            stalled.complete(null);
            await(() -> pool.getActiveCount()==0 && pool.getQueue().isEmpty());
            Assertions.assertEquals(List.of("b/healthy","a/first","a/second"),f.published);
        }
    }

    @Test
    void shutdownInterruptsStalledWorkerWithoutAcknowledgingItsIntent() throws Exception {
        try (var f = new Fixture()) {
            var pool = Executors.newFixedThreadPool(1); f.delivery.workers = pool;
            f.add("a","first","second");
            f.send = (model,batch) -> new CompletableFuture<>();
            f.delivery.drain(); await(() -> !f.attempts.isEmpty());
            f.delivery.stop();
            Assertions.assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));
            Assertions.assertTrue(f.published.isEmpty());
            Assertions.assertEquals(List.of("a/first"),f.attempts);
        }
    }

    @Test
    void invalidWorkerPolicyFailsBeforeAcquiringLease() {
        var service = new CommitDeliveryService(); service.concurrentModels = 0;
        Assertions.assertThrows(IllegalArgumentException.class,service::start);
    }

    @Test
    void inProgressLeaseRenewalCannotReactivateStoppedDelivery() throws Exception {
        var service = new CommitDeliveryService();
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        service.repository = (Neo4jRepository) Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Neo4jRepository.class},
                (proxy,method,args) -> { started.countDown(); release.await(); return true; });
        Thread renewal = Thread.ofPlatform().daemon().start(service::renewLease);
        try {
            Assertions.assertTrue(started.await(3,TimeUnit.SECONDS));
            service.stop(); release.countDown(); renewal.join(3000);
            Assertions.assertFalse(renewal.isAlive());
            Assertions.assertThrows(IllegalStateException.class,service::requireWriterLease);
        } finally { release.countDown(); service.stop(); }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        Assertions.assertTrue(condition.getAsBoolean(),"delivery did not complete in time");
    }

    private static final class Fixture implements AutoCloseable {
        final CommitDeliveryService delivery = new CommitDeliveryService();
        final ManualExecutor manual = new ManualExecutor();
        final SortedMap<String,List<JsonNode>> pending = new TreeMap<>();
        final List<String> attempts = new CopyOnWriteArrayList<>(), published = new CopyOnWriteArrayList<>();
        final Set<String> failedModels = ConcurrentHashMap.newKeySet(), unreadableModels = ConcurrentHashMap.newKeySet(),
                failedAcknowledgments = ConcurrentHashMap.newKeySet();
        final AtomicLong clock = new AtomicLong();
        BiFunction<String,JsonNode,CompletableFuture<Void>> send = (model,batch) -> CompletableFuture.completedFuture(null);

        Fixture() throws Exception {
            delivery.workers = manual; delivery.nanoTime = clock::get; lease(true);
            delivery.repository = (Neo4jRepository) Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Neo4jRepository.class},
                    (proxy,method,args) -> {
                        synchronized (pending) {
                            return switch (method.getName()) {
                                case "pendingBroadcastModels" -> pending.entrySet().stream().filter(e -> !e.getValue().isEmpty())
                                        .map(Map.Entry::getKey).filter(id -> args[0]==null || id.compareTo((String)args[0])>0)
                                        .limit((int)args[1]).toList();
                                case "pendingBroadcasts" -> {
                                    if (unreadableModels.contains(args[0])) throw new IllegalStateException("invalid stored payload");
                                    yield pending.getOrDefault(args[0],List.of()).stream().limit((int)args[1]).toList();
                                }
                                case "markBroadcastPublished" -> {
                                    String key = args[0]+"/"+args[1];
                                    if (failedAcknowledgments.contains(key)) throw new IllegalStateException("database unavailable");
                                    pending.get(args[0]).removeIf(b -> args[1].equals(b.path("opBatchId").asText()));
                                    published.add(key); yield null;
                                }
                                default -> throw new UnsupportedOperationException(method.getName());
                            };
                        }
                    });
            delivery.publisher = (KafkaPublisher) Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{KafkaPublisher.class},
                    (proxy,method,args) -> {
                        String model = (String)args[0]; JsonNode batch = (JsonNode)args[1];
                        attempts.add(model+"/"+batch.path("opBatchId").asText());
                        return failedModels.contains(model) ? CompletableFuture.failedFuture(new IllegalStateException("broker failure"))
                                : send.apply(model,batch);
                    });
        }

        void add(String model,String... ids) {
            for (String id : ids) pending.computeIfAbsent(model,key -> new ArrayList<>())
                    .add(new ObjectMapper().createObjectNode().put("modelId",model).put("opBatchId",id));
        }
        void lease(boolean valid) throws Exception {
            var field = CommitDeliveryService.class.getDeclaredField("leaseValid"); field.setAccessible(true); field.set(delivery,valid);
        }
        void drainAndRun() { delivery.drain(); manual.runAll(); }
        @Override public void close() { delivery.stop(); }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean stopped;
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
        @Override public void execute(Runnable task) { if (stopped) throw new RejectedExecutionException(); tasks.add(task); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; var rest = List.copyOf(tasks); tasks.clear(); return rest; }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long time,TimeUnit unit) { return isTerminated(); }
    }
}
