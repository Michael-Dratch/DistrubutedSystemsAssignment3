package statemachine;

public interface Command {
    String getClientRef();
    int getCommandID();
    boolean equals(Command other);
}
