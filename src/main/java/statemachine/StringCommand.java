package statemachine;

import java.io.Serializable;

public class StringCommand implements Command, Serializable {
    private String clientRef;
    private int commandID;
    private String value;

    public StringCommand(String serializableClientRef, int commandID, String value){
        this.clientRef = serializableClientRef;
        this.commandID = commandID;
        this.value = value;
    }

    @Override
    public boolean equals(Command other){
        return this.clientRef == other.getClientRef() && this.commandID == other.getCommandID();
    }

    @Override
    public String getClientRef() {
        return this.clientRef;
    }

    @Override
    public int getCommandID() {
        return this.commandID;
    }

    public String getValue() {
        return this.value;
    }
}
