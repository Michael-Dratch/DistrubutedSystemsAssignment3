package messages;

import akka.actor.typed.ActorRef;

import java.util.List;

public interface ClientMessage {

    public record SetRequestQueue(List<RaftMessage> requests) implements ClientMessage {}
    public record Start() implements ClientMessage{}
    public record StartFailMode(int requestsPerFailure, int concurrentFails) implements ClientMessage {}
    public record ClientUpdateResponse(boolean success, int commandID) implements ClientMessage{}
    public record ClientReadResponse<stateType>(stateType state) implements ClientMessage{}
    public record TimeOut() implements ClientMessage {}
    public record AlertWhenFinished(ActorRef<OrchMessage> sender) implements ClientMessage{}
    public record Finished() implements ClientMessage {}
    public record ShutDown(ActorRef<OrchMessage> sender) implements ClientMessage {}
}
