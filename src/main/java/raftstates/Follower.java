package raftstates;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.PreRestart;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.TimerScheduler;
import datapersistence.ServerDataManager;
import messages.ClientMessage;
import messages.RaftMessage;
import statemachine.Entry;
import statemachine.StateMachine;

import java.util.ArrayList;
import java.util.List;


public class Follower extends RaftServer {

    public static Behavior<RaftMessage> create(ServerDataManager dataManager, StateMachine stateMachine, FailFlag failFlag){
        return Behaviors.<RaftMessage>supervise(
            Behaviors.setup(context -> Behaviors.withTimers(timers -> new Follower(context, timers, dataManager, stateMachine, failFlag)))
        ).onFailure(SupervisorStrategy.restart());
    }


    @Override
    public Receive<RaftMessage> createReceive() {
        return newReceiveBuilder()
                .onMessage(RaftMessage.class, this::dispatch)
                .onSignal(PreRestart.class, this::handlePreRestart)
                .build();
    }



    protected Follower(ActorContext<RaftMessage> context, TimerScheduler<RaftMessage> timers, ServerDataManager dataManager, StateMachine stateMachine, FailFlag failFlag){
        super(context, timers, dataManager, stateMachine, failFlag,  -1,-1);
        updateRequestBuffer = new ArrayList<>();
        committedReadBuffer = new ArrayList<>();
        currentLeader = null;
    }

    private ActorRef<RaftMessage> currentLeader;

    private List<RaftMessage.ClientUpdateRequest> updateRequestBuffer;
    private List<RaftMessage.ClientCommittedReadRequest> committedReadBuffer;



    private Behavior<RaftMessage> dispatch(RaftMessage message){
        if (!this.failFlag.failed) {
            switch (message) {
                case RaftMessage.Start msg:
                    startTimer();
                    break;
                case RaftMessage.SetGroupRefs msg:
                    this.groupRefs = msg.groupRefs();
                    this.dataManager.saveGroupRefs(this.groupRefs);
                    break;
                case RaftMessage.AppendEntries msg:
                    handleAppendEntries(msg);
                    break;
                case RaftMessage.RequestVote msg:
                    handleRequestVote(msg);
                    break;
                case RaftMessage.TimeOut msg:
                    handleTimeOut();
                    getContext().getLog().info("TIMEOUT STARTING ELECTION " + getContext().getSelf().path().uid());
                    sendBufferedRequestsToSelf();
                    return Candidate.create(this.dataManager, this.stateMachine, this.failFlag, this.TIMER_KEY, this.currentTerm, this.groupRefs, this.commitIndex, this.lastApplied);
                case RaftMessage.ClientUpdateRequest msg:
                    getContext().getLog().info("RECEIVED CLIENT REQUEST");
                    handleClientUpdateRequest(msg);
                    break;
                case RaftMessage.ClientCommittedReadRequest msg:
                    getContext().getLog().info("RECEIVED CLIENT Stable Read REQUEST");
                    handleClientCommittedReadRequest(msg);
                    break;
                case RaftMessage.ClientUnstableReadRequest msg:
                    getContext().getLog().info("RECEIVED CLIENT UNSTABLE READ REQUEST");
                    handleUnstableReadRequest(msg);
                    break;
                case RaftMessage.TestMessage msg:
                    handleTestMessage(msg);
                    break;
                case RaftMessage.Failure msg:   // Used to simulate node failure
                    throw new RuntimeException("Test Failure");
                case RaftMessage.ShutDown msg:
                    return Behaviors.stopped();
                default:
                    break;
            }
            return this;
        } else {
            resetTransientState();
            this.failFlag.failed = false;
            getContext().getSelf().tell(message);
            return this;
        }
    }

    private void handleAppendEntries(RaftMessage.AppendEntries msg){
        updateCurrentTerm(msg.term());
        if (doesAppendEntriesFail(msg)){
            sendAppendEntriesResponse(msg, false);
        } else {
            startTimer();
            processSuccessfulAppendEntries(msg);
            sendAppendEntriesResponse(msg, true);
        }
    }

    private boolean doesAppendEntriesFail(RaftMessage.AppendEntries msg) {
        if (msg.term() < this.currentTerm) return true;
        else if (msg.prevLogIndex() == -1) return false;
        else if (this.log.size() <= msg.prevLogIndex()) return true;
        if (this.log.size() < 1) return false;
        else if(this.log.get(msg.prevLogIndex()).term() != msg.prevLogTerm()) return true;
        return false;
    }

    private void processSuccessfulAppendEntries(RaftMessage.AppendEntries msg) {

        int entryCount = msg.entries().size();

        for (int i = 0; i < entryCount; i++){
            if (entryIndexExceedsLogSize(msg, i)){
                addRemainingEntriesToLog(msg, i);
                break;
            }
            else if (isConflictBetweenMessageAndLogEntry(msg, i)){
                removeConflictingLogEntries(msg.prevLogIndex(), i);
                addRemainingEntriesToLog(msg, i);
                break;
            }
        }
        updateCommitIndex(msg);
        this.dataManager.saveLog(this.log);
        if (this.currentLeader == null){
            forwardBufferedRequests(msg);
        }
        this.currentLeader = msg.leaderRef();
        this.votedFor = null;
    }

    private void forwardBufferedRequests(RaftMessage.AppendEntries msg) {
        getContext().getLog().info("FORWARDING BUFFERED REQUESTS TO LEADER");
        for (RaftMessage.ClientUpdateRequest request : updateRequestBuffer){
            msg.leaderRef().tell(request);
        }
        for (RaftMessage.ClientCommittedReadRequest request : committedReadBuffer){
            msg.leaderRef().tell(request);
        }
        updateRequestBuffer.clear();
        committedReadBuffer.clear();
    }

    private boolean entryIndexExceedsLogSize(RaftMessage.AppendEntries msg, int i) {
        return msg.prevLogIndex() + i > log.size() - 1 || msg.prevLogIndex() + 1 + i >= log.size();
    }

    private void addRemainingEntriesToLog(RaftMessage.AppendEntries msg, int i) {
        this.log.addAll(msg.entries().subList(i, msg.entries().size()));
        this.log = new ArrayList<>(this.log);
    }

    private boolean isConflictBetweenMessageAndLogEntry(RaftMessage.AppendEntries msg, int i) {
        return msg.entries().get(i).term() != this.log.get(msg.prevLogIndex() + 1 + i).term();
    }

    private void removeConflictingLogEntries(int prevLogIndex, int i) {
        this.log = this.log.subList(0, prevLogIndex + i + 1);
    }

    private void updateCommitIndex(RaftMessage.AppendEntries msg) {
        if (msg.leaderCommit() > this.commitIndex){
            this.commitIndex = Math.min(msg.leaderCommit(), this.log.size() - 1);
            if (commitIndex != -1) this.applyCommittedEntriesToStateMachine();
        }
    }

    private void handleRequestVote(RaftMessage.RequestVote msg) {
        updateCurrentTerm(msg.term());
        if (doesRequestVoteFail(msg)) {
            returnRequestVoteResponse(msg, false);
        }else{
            startTimer();
            this.votedFor = msg.candidateRef();
            this.dataManager.saveVotedFor(this.votedFor);
            returnRequestVoteResponse(msg, true);
        }
    }

    private boolean doesRequestVoteFail(RaftMessage.RequestVote msg){
        if (msg.term() < this.currentTerm) return true;

        else if (votedFor != null) {
            return true;}

        else if (this.log.size() == 0) return false;

        else if (msg.lastLogTerm() < this.log.get(log.size()-1).term()) return true;

        else if (msg.lastLogTerm() == this.log.get(log.size()-1).term()) {
            if (msg.lastLogIndex() < this.log.size() - 1) return true;
        }
        return false;
    }

    private void returnRequestVoteResponse(RaftMessage.RequestVote msg, boolean voteGranted) {
        msg.candidateRef().tell(new RaftMessage.RequestVoteResponse(this.currentTerm, voteGranted));
    }

    private void handleClientUpdateRequest(RaftMessage.ClientUpdateRequest msg) {
        if (currentLeader != null) {
            getContext().getLog().info("FORWARDING REQUEST TO LEADER");
            this.currentLeader.tell(msg);
        }
        else updateRequestBuffer.add(msg);
    }

    private void handleClientCommittedReadRequest(RaftMessage.ClientCommittedReadRequest msg) {
        if (currentLeader != null) {
            getContext().getLog().info("FORWARDING READ REQUEST TO LEADER");
            this.currentLeader.tell(msg);
        }
        else committedReadBuffer.add(msg);
    }

    private void sendBufferedRequestsToSelf() {
        for (RaftMessage.ClientUpdateRequest request : updateRequestBuffer){
            getContext().getSelf().tell(request);
        }
        for (RaftMessage.ClientCommittedReadRequest request : committedReadBuffer){
            getContext().getSelf().tell(request);
        }
    }


    protected void handleTestMessage(RaftMessage.TestMessage message){
        switch(message){
            case RaftMessage.TestMessage.GetBehavior msg:
                msg.sender().tell(new RaftMessage.TestMessage.GetBehaviorResponse("FOLLOWER"));
                break;
            case RaftMessage.TestMessage.GetStateMachineState msg:
                msg.sender().tell(new RaftMessage.TestMessage.GetStateMachineStateResponse(this.stateMachine.getState()));
                break;
            case RaftMessage.TestMessage.GetState msg:
                msg.sender().tell(new RaftMessage.TestMessage.GetStateResponse(this.currentTerm, this.votedFor, this.log, this.commitIndex, this.lastApplied));
                break;
            default:
                break;
        }
    }
}
