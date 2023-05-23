package statemachine;

import java.io.Serializable;

public record Entry(int term, Command command) implements Serializable {
    public boolean equals(Entry e){
        return this.term == e.term() && this.command.equals(e.command);
    }
}
