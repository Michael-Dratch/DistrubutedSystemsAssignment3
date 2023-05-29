package statemachine;

import java.util.List;

public interface StateMachine<stateType, commandType> {

    public void apply(commandType command);
    public void applyAll(List<commandType> commands);

    public stateType getState();

    public void resetState();

    public StateMachine<stateType, commandType> forkStateMachine();

    public boolean isStateValid();
}
