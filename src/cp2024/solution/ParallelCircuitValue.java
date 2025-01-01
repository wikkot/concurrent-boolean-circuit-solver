package cp2024.solution;

import cp2024.circuit.CircuitValue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class ParallelCircuitValue implements CircuitValue {
    private Future<Boolean> future;

    ParallelCircuitValue() { // package-private
        this.future = null;
    }

    void setFuture(Future<Boolean> future) { // package-private
        this.future = future;
    }

    Future<Boolean> getFuture() { // package-private
        return this.future;
    }

    @Override
    public boolean getValue() throws InterruptedException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            throw new InterruptedException("Current thread was interrupted while waiting for the circuit value.");
        } catch (ExecutionException e) {
            throw new InterruptedException("Computations of the circuit value have failed.");
        } catch (NullPointerException e) {
            throw new RuntimeException("Future has not been set.");
        } catch (CancellationException e) {
            throw new InterruptedException("Solver has been stopped.");
        }
    }
}
