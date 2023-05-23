package statemachine;

import java.io.Serializable;

public class StringCommand extends Command implements Serializable {
    private String value;

    public StringCommand(String serializableClientRef, int commandID, String value){
        super(serializableClientRef, commandID);
        this.value = value;
    }

    public String getValue() {
        return this.value;
    }
}
