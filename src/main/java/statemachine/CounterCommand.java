package statemachine;

public class CounterCommand extends Command{

    private int decrementCount;

    public CounterCommand(String serializableClientRef, int commandID, int decrementCount){
        super(serializableClientRef, commandID);
        this.decrementCount = decrementCount;
    }

    public int getValue(){
        return decrementCount;
    }
}
