package statemachine;

import java.util.List;

public interface StateMachine {

    public void apply(Command command);
    public void applyAll(List<Command> commands);

    public List<Command> getCommands();

    public void clearAll();
}
