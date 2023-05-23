package statemachine;

import java.util.List;

public interface StateMachine<stateType> {

    public void apply(Command command);
    public void applyAll(List<Command> commands);

    public stateType getState();

    public void resetState();
}
