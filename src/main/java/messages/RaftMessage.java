package messages;

import akka.actor.typed.ActorRef;
import statemachine.Command;
import statemachine.Entry;

import java.util.List;



public interface RaftMessage {

    public record ClientRequest(ActorRef<ClientMessage> clientRef, Command command) implements RaftMessage {}

    public record AppendEntries(int term,
                                ActorRef<RaftMessage> leaderRef,
                                int prevLogIndex,
                                int prevLogTerm,
                                List<Entry> entries,
                                int leaderCommit
                                ) implements RaftMessage {}

    public record RequestVote(int term,
                              ActorRef<RaftMessage> candidateRef,
                              int lastLogIndex,
                              int lastLogTerm
                              ) implements RaftMessage {}

    public record AppendEntriesResponse(ActorRef<RaftMessage> sender, int term, boolean success, int matchIndex) implements RaftMessage {}

    public record RequestVoteResponse(int term, boolean voteGranted) implements RaftMessage {}

    public record SetGroupRefs(List<ActorRef<RaftMessage>> groupRefs) implements RaftMessage {}

    public record Start() implements RaftMessage{}

    public record TimeOut() implements RaftMessage {}

    public record Failure() implements RaftMessage {}
    public record ShutDown(ActorRef<OrchMessage> sender) implements RaftMessage {}



    public interface TestMessage extends RaftMessage{
        public record GetState(ActorRef<RaftMessage> sender) implements TestMessage{}
        public record GetStateResponse(int currentTerm,
                                       ActorRef<RaftMessage> votedFor,
                                       List<Entry> log,
                                       int commitIndex,
                                       int lastApplied) implements TestMessage {}
        public record GetBehavior(ActorRef<RaftMessage> sender) implements TestMessage {}
        public record GetBehaviorResponse(String behavior) implements TestMessage {}
        public record GetLog(ActorRef<RaftMessage> sender) implements TestMessage{}
        public record GetLogResponse(List<Entry> log) implements TestMessage{}
        public record GetCommitIndex(ActorRef<RaftMessage> sender) implements TestMessage {}
        public record GetCommitIndexResponse(int commitIndex) implements TestMessage {}
        public record GetStateMachineCommands(ActorRef<RaftMessage> sender) implements TestMessage {}
        public record GetStateMachineCommandsResponse(List<Command> commands) implements TestMessage {}

        public record SaveEntries(List<Entry> entries) implements TestMessage {}
        public record testFail() implements TestMessage {}
    }
}
