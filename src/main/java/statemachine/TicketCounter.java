package statemachine;

import java.util.List;

public class TicketCounter implements StateMachine<Integer, CounterCommand> {

    private Integer count;

    private Integer initialState;

    public TicketCounter(int count){
        this.count = count;
        this.initialState = count;
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
        this.count = this.initialState;
    }
}
