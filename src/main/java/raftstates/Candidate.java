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
import messages.RaftMessage;
import statemachine.StateMachine;

import java.util.ArrayList;
import java.util.List;

public class Candidate extends RaftServer {

    public static Behavior<RaftMessage> create(ServerDataManager dataManager,
                                               StateMachine stateMachine,
                                               FailFlag failFlag,
                                               Object timerKey,
                                               int currentTerm,
                                               List<ActorRef<RaftMessage>> groupRefs,
                                               int commitIndex,
                                               int lastApplied){
        return Behaviors.<RaftMessage>supervise(
                Behaviors.setup(context -> Behaviors.withTimers(timers -> new Candidate(context, timers, dataManager, stateMachine, failFlag, timerKey, currentTerm, groupRefs, commitIndex, lastApplied)))
        ).onFailure(SupervisorStrategy.restart());
    }

    @Override
    public Receive<RaftMessage> createReceive() {
        return newReceiveBuilder()
                .onMessage(RaftMessage.class, this::dispatch)
                .onSignal(PreRestart.class, this::handlePreRestart)
                .build();
    }

    private int votesReceived;
    private int votesRequired;

    private List<RaftMessage.ClientUpdateRequest> requestBuffer;



    protected Candidate(ActorContext<RaftMessage> context,
                        TimerScheduler<RaftMessage> timers,
                        ServerDataManager dataManager,
                        StateMachine stateMachine,
                        FailFlag failFlag,
                        Object timerKey,
                        int currentTerm,
                        List<ActorRef<RaftMessage>> groupRefs,
                        int commitIndex,
                        int lastApplied){
        super(context, timers, dataManager, stateMachine, failFlag, timerKey, commitIndex, lastApplied);
        this.currentTerm = currentTerm;
        this.dataManager.saveCurrentTerm(this.currentTerm);
        this.groupRefs = groupRefs;
        this.dataManager.saveGroupRefs(this.groupRefs);
        this.votesReceived = 0;
        this.votesRequired = getVotesRequired(groupRefs);
        this.requestBuffer = new ArrayList<>();
        startTimer();
    }


    private Behavior<RaftMessage> dispatch(RaftMessage message){
        if (!this.failFlag.failed) {

            switch (message) {
                case RaftMessage.AppendEntries msg:
                    if (msg.term() < this.currentTerm) sendAppendEntriesResponse(msg, false);
                    else {
                        sendBufferedRequests(msg.leaderRef());
                        return Follower.create(dataManager, this.stateMachine, this.failFlag);
                    }
                    break;
                case RaftMessage.RequestVote msg:
                    if (msg.term() > this.currentTerm)
                        return Follower.create(this.dataManager, this.stateMachine, this.failFlag);
                    else sendRequestVoteFailResponse(msg);
                    break;
                case RaftMessage.RequestVoteResponse msg:
                    if (msg.term() > this.currentTerm)
                        return Follower.create(this.dataManager, this.stateMachine, this.failFlag);
                    else {
                        handleRequestVoteResponse(msg);
                        if (votesReceived >= votesRequired) {
                            getContext().getLog().info("ELECTED TO LEADER " + getContext().getSelf().path().uid());
                            sendBufferedRequests(getContext().getSelf());
                            return getLeaderBehavior();
                        }
                    }
                    break;
                case RaftMessage.TimeOut msg:
                    getContext().getLog().info("CANDIDATE TIMEOUT STARTING NEW ELECTION");
                    handleTimeOut();
                    votesReceived = 0;
                    break;
                case RaftMessage.ClientUpdateRequest msg:
                    getContext().getLog().info("RECEIVED CLIENT REQUEST");
                    handleClientRequest(msg);
                    break;
                case RaftMessage.Failure msg:   // Used to simulate node failure
                    throw new RuntimeException("Test Failure");
                case RaftMessage.ShutDown msg:
                    return Behaviors.stopped();
                case RaftMessage.TestMessage msg:
                    handleTestMessage(msg);
                    break;
                default:
                    break;
            }
            return this;
        }else{
            resetTransientState();
            this.failFlag.failed = false;
            getContext().getSelf().tell(message);
            return Follower.create(this.dataManager, this.stateMachine, this.failFlag);
        }

    }

    private void sendBufferedRequests(ActorRef<RaftMessage> leader) {
        for (RaftMessage.ClientUpdateRequest request : requestBuffer){
            leader.tell(request);
        }
    }

    private void handleClientRequest(RaftMessage.ClientUpdateRequest msg) {
        requestBuffer.add(msg);
    }

    private Behavior<RaftMessage> getLeaderBehavior() {
        return Leader.create(this.dataManager,
                this.stateMachine,
                this.TIMER_KEY,
                this.failFlag,
                this.currentTerm,
                this.groupRefs,
                this.commitIndex,
                this.lastApplied);
    }

    private void handleRequestVoteResponse(RaftMessage.RequestVoteResponse msg) {
        if (msg.voteGranted() == true){
            getContext().getLog().info("RECEIVED VOTE");
            votesReceived++;
        }
    }

    private void handleTestMessage(RaftMessage.TestMessage message) {
        switch(message) {
            case RaftMessage.TestMessage.GetBehavior msg:
                msg.sender().tell(new RaftMessage.TestMessage.GetBehaviorResponse("CANDIDATE"));
                break;
            case RaftMessage.TestMessage.GetStateMachineState msg:
                msg.sender().tell(new RaftMessage.TestMessage.GetStateMachineStateResponse((this.stateMachine.getState())));
                break;
            default:
                break;
        }
    }

    private void sendRequestVoteFailResponse(RaftMessage.RequestVote msg) {
        msg.candidateRef().tell(new RaftMessage.RequestVoteResponse(this.currentTerm, false));
    }

    private int getVotesRequired(List<ActorRef<RaftMessage>> groupRefs) {
        int size = groupRefs.size();
        if(size % 2 == 0) return size/2;
        else return size/2 + 1;
    }
}

