package statemachine;

public class CounterCommand extends Command{

    int decrementCount;

    public CounterCommand(String serializableClientRef, int commandID, int decrementCount){
        super(serializableClientRef, commandID);
        this.decrementCount = decrementCount;
    }

    public int getDecrementCount(){
        return decrementCount;
    }
}
