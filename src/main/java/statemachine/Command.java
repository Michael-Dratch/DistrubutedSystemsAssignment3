package statemachine;

import java.io.Serializable;

public abstract class Command implements Serializable {

    private String clientRef;
    private int commandID;

    public Command(String clientRef, int commandID){
        this.clientRef = clientRef;
        this.commandID = commandID;
    }
    public String getClientRef(){
        return clientRef;
    }
    public int getCommandID(){
        return commandID;
    }
    public boolean equals(Command other){
        return this.clientRef == other.getClientRef() && this.commandID == other.getCommandID();
    }
}
