package cp2024.solution;

import cp2024.circuit.CircuitValue;

public class InterruptedCircuitValue implements CircuitValue {
    @Override
    public boolean getValue() throws InterruptedException {
        throw new InterruptedException();
    }
}
