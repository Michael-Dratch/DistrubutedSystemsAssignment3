package statemachine;

import java.util.List;

public class TicketCounter implements StateMachine<Integer, CounterCommand> {

    private Integer count;
    private Integer initialCount;

    public TicketCounter(int initialCount){
        this.initialCount = initialCount;
        this.count = initialCount;
    }

    @Override
    public void apply(CounterCommand command) {
        this.count -= command.decrementCount;
    }

    @Override
    public void applyAll(List<CounterCommand> commands) {
        for (CounterCommand command: commands){
            this.count -= command.decrementCount;
        }
    }

    @Override
    public Integer getState() {
        return count;
    }

    @Override
    public void resetState() {
        this.count = this.initialCount;
    }
}
