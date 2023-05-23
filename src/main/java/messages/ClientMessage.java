package messages;

import akka.actor.typed.ActorRef;

public interface ClientMessage {
    public record Start() implements ClientMessage{}
    public record StartFailMode(int requestsPerFailure, int concurrentFails) implements ClientMessage {}
    public record ClientUpdateResponse(boolean success, int commandID) implements ClientMessage{}
    public record ClientReadResponse<stateType>(stateType state) implements ClientMessage{}
    public record TimeOut() implements ClientMessage {}
    public record AlertWhenFinished(ActorRef<ClientMessage> sender) implements ClientMessage{}
    public record Finished() implements ClientMessage {}
    public record ShutDown(ActorRef<OrchMessage> sender) implements ClientMessage {}
}
