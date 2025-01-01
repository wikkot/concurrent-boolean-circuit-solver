package cp2024.solution;

import cp2024.circuit.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ParallelCircuitSolver implements CircuitSolver {
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
    private volatile boolean stopped = false;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ConcurrentMap<CircuitValue, Future<Boolean>> tasks = new ConcurrentHashMap<>();
    // Main tasks — one for each solve() call — are stored in tasks map.

    @Override
    public CircuitValue solve(Circuit c) {
        try {
            lock.readLock().lockInterruptibly();
        } catch (InterruptedException e) {
            return new InterruptedCircuitValue();
        }

        if (stopped) {
            return new InterruptedCircuitValue();
        }

        ParallelCircuitValue value = new ParallelCircuitValue();

        FutureTask<Boolean> future = new FutureTask<>(() -> {
            try {
                return evaluateNode(c.getRoot(), value);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                tasks.remove(value);
            }
        });

        value.setFuture(future);
        tasks.put(value, future);
        pool.execute(future); // execute the task only after setting the future

        lock.readLock().unlock();
        return value;
    }

    @Override
    public void stop() {
        lock.readLock().lock();

        if (!stopped) {
            stopped = true;
            tasks.values().forEach(future -> future.cancel(true));
            pool.shutdownNow();
        }

        lock.readLock().unlock();
    }

    private boolean evaluateNode(CircuitNode node, ParallelCircuitValue rootCircuitValue) throws InterruptedException {
        return evaluateNode(node, rootCircuitValue.getFuture());
    }

    private boolean evaluateNode(CircuitNode node, Future<Boolean> mainTask) throws InterruptedException {
        return switch (node.getType()) {
            case LEAF -> evaluateLEAF((LeafNode) node);
            case NOT -> evaluateNOT(node, mainTask);
            case AND -> evaluateAND(node, mainTask);
            case OR -> evaluateOR(node, mainTask);
            case GT -> evaluateGT((ThresholdNode) node, mainTask);
            case LT -> evaluateLT((ThresholdNode) node, mainTask);
            case IF -> evaluateIF(node, mainTask);
        };
    }

    // Submits the task to the thread pool.
    // If the task is interrupted, it cancels mainTask (unless cleanUp == true).
    private <T> Future<T> submitEvaluation(Future<Boolean> mainTask, AtomicBoolean cleanUp, Callable<T> task) {
        return pool.submit(() -> {
            try {
                return task.call();
            } catch (InterruptedException e) {
                if (!cleanUp.get()) {
                    mainTask.cancel(true);
                }
                throw new RuntimeException(e);
            }
        });
    }

    private boolean evaluateLEAF(LeafNode node) throws InterruptedException {
        return node.getValue();
    }

    private boolean evaluateNOT(CircuitNode node, Future<Boolean> mainTask) throws InterruptedException {
        CircuitNode[] args = node.getArgs();
        return !evaluateNode(args[0], mainTask);
    }

    // AND, OR, GT, and LT are all implemented via GT (with unpacked arguments).

    private boolean evaluateAND(CircuitNode node, Future<Boolean> mainTask) throws InterruptedException {
        CircuitNode[] args = node.getArgs();
        return evaluateUnpackedGT(args, args.length - 1, mainTask);
    }

    private boolean evaluateOR(CircuitNode node, Future<Boolean> mainTask) throws InterruptedException {
        CircuitNode[] args = node.getArgs();
        return evaluateUnpackedGT(args, 0, mainTask);
    }

    private boolean evaluateGT(ThresholdNode node, Future<Boolean> mainTask) throws InterruptedException {
        return evaluateUnpackedGT(node.getArgs(), node.getThreshold(), mainTask);
    }

    private boolean evaluateLT(ThresholdNode node, Future<Boolean> mainTask) throws InterruptedException {
        int threshold = node.getThreshold();

        // Simple edge case.
        if (threshold == 0) {
            return false;
        }

        return !evaluateUnpackedGT(node.getArgs(), threshold - 1, mainTask);
    }

    // Evaluates GT_threshold(args).
    private boolean evaluateUnpackedGT(CircuitNode[] args, int threshold, Future<Boolean> mainTask) throws InterruptedException {
        // Simple edge case.
        if (threshold >= args.length) {
            return false;
        }

        CountDownLatch remainingTrue = new CountDownLatch(threshold + 1);
        CountDownLatch remainingFalse = new CountDownLatch(args.length - threshold);
        CountDownLatch resultReady = new CountDownLatch(1);
        AtomicBoolean cleanUp = new AtomicBoolean(false);
        List<Future<Boolean>> argsFutures = new ArrayList<>();

        Future<Void> trueObserver = submitEvaluation(mainTask, cleanUp, () -> {
            remainingTrue.await();
            resultReady.countDown();
            return null;
        });

        Future<Void> falseObserver = submitEvaluation(mainTask, cleanUp, () -> {
            remainingFalse.await();
            resultReady.countDown();
            return null;
        });

        for (CircuitNode arg : args) {
            argsFutures.add(submitEvaluation(mainTask, cleanUp, () -> {
                boolean val = evaluateNode(arg, mainTask);
                if (val) {
                    remainingTrue.countDown();
                }
                else {
                    remainingFalse.countDown();
                }
                return null;
            }));
        }

        try {
            resultReady.await(); // can throw InterruptedException
        } finally {
            cleanUp.set(true);
            trueObserver.cancel(true);
            falseObserver.cancel(true);
            argsFutures.forEach(f -> f.cancel(true));
        }

        return remainingTrue.getCount() == 0;
    }

    // IF gate logic.

    private static Callable<Void> conditionObserver(Future<Boolean> conditionFuture, Future<Boolean> trueFuture,
                                                    Future<Boolean> falseFuture, CountDownLatch resultReady, AtomicBoolean result) {
        return () -> {
            try {
                boolean value = conditionFuture.get();
                if (value) {
                    falseFuture.cancel(true); // immediately cancel the false future not to waste resources
                    result.set(trueFuture.get());
                }
                else {
                    trueFuture.cancel(true); // immediately cancel the true future not to waste resources
                    result.set(falseFuture.get());
                }
                resultReady.countDown();
                return null;
            } catch (CancellationException e) {
                return null;
            } catch (ExecutionException e) {
                throw new InterruptedException();
            }
        };
    }

    private static Callable<Void> trueFalseObserver(Future<Boolean> trueFuture, Future<Boolean> falseFuture,
                                                    CountDownLatch resultReady, AtomicBoolean result) {
        return () -> {
            try {
                boolean trueValue = trueFuture.get();
                boolean falseValue = falseFuture.get();
                if (trueValue == falseValue) { // both branches evaluate to the same value, so condition is irrelevant
                    result.set(trueValue);
                    resultReady.countDown();
                }
                return null;
            } catch (CancellationException e) {
                return null;
            } catch (ExecutionException e) {
                throw new InterruptedException();
            }
        };
    }

    private boolean evaluateIF(CircuitNode node, Future<Boolean> mainTask) throws InterruptedException {
        CircuitNode[] args = node.getArgs();
        CountDownLatch resultReady = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean();
        AtomicInteger conditionEvaluated = new AtomicInteger(0);
        AtomicBoolean cleanUp = new AtomicBoolean(false);

        Future<Boolean> conditionFuture = submitEvaluation(mainTask, cleanUp, () -> {
            boolean val = evaluateNode(args[0], mainTask);
            conditionEvaluated.set(val ? 1 : 2);
            return val;
        });

        Future<Boolean> trueFuture = submitEvaluation(mainTask, cleanUp, () -> {
            try {
                return evaluateNode(args[1], mainTask);
            } catch (InterruptedException e) {
                if (conditionEvaluated.get() != 2) throw e; // rethrow only if this value is still relevant
                return false;
            }
        });

        Future<Boolean> falseFuture = submitEvaluation(mainTask, cleanUp, () -> {
            try {
                return evaluateNode(args[2], mainTask);
            } catch (InterruptedException e) {
                if (conditionEvaluated.get() != 1) throw e; // rethrow only if this value is still relevant
                return false;
            }
        });

        submitEvaluation(mainTask, cleanUp, conditionObserver(conditionFuture, trueFuture, falseFuture, resultReady, result));
        submitEvaluation(mainTask, cleanUp, trueFalseObserver(trueFuture, falseFuture, resultReady, result));

        try {
            resultReady.await(); // can throw InterruptedException
        } finally {
            cleanUp.set(true);
            conditionFuture.cancel(true);
            trueFuture.cancel(true);
            falseFuture.cancel(true);
            // Observers don't need to be cancelled, as they were only waiting for the 3 futures, which has just been cancelled.
        }

        return result.get();
    }
}