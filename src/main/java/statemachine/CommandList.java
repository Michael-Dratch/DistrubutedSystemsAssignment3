package statemachine;

import java.util.ArrayList;
import java.util.List;

public class CommandList implements StateMachine<List<Command>> {

    public CommandList(){
        this.commands = new ArrayList<>();
    }

    private List<Command> commands;

    @Override
    public void apply(Command command) {
        this.commands.add(command);
    }

    @Override
    public void applyAll(List<Command> commands) {
        this.commands.addAll(commands);
    }

    @Override
    public List<Command> getState(){
        return this.commands;
    }

    @Override
    public void resetState(){
        this.commands.clear();
    }
}
