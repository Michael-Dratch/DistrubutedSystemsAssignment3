package raftstates;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorRefResolver;
import akka.actor.typed.Behavior;
import akka.actor.typed.PreRestart;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.TimerScheduler;
import datapersistence.ServerDataManager;
import messages.RaftMessage;
import statemachine.Entry;
import statemachine.StateMachine;

import java.time.Duration;
import java.util.List;
import java.util.Random;


abstract class RaftServer extends AbstractBehavior<RaftMessage> {

    protected TimerScheduler<RaftMessage> timer;

    protected Object TIMER_KEY = new Object();

    protected ServerDataManager dataManager;
    protected StateMachine stateMachine;

    protected FailFlag failFlag;

    protected List<ActorRef<RaftMessage>> groupRefs;

    protected int currentTerm;

    protected ActorRef<RaftMessage> votedFor;

    protected List<Entry> log;

    protected int commitIndex;

    protected int lastApplied;

    private Random randomGen;


    protected RaftServer(ActorContext<RaftMessage> context,
                        TimerScheduler<RaftMessage> timers,
                        ServerDataManager dataManager,
                         StateMachine stateMachine,
                         FailFlag failFlag,
                         int commitIndex,
                         int lastApplied){
        super(context);
        this.timer = timers;
        this.dataManager = dataManager;
        this.stateMachine = stateMachine;
        this.failFlag = failFlag;
        this.commitIndex = commitIndex;
        this.lastApplied = lastApplied;
        this.randomGen = new Random();
        this.randomGen.setSeed(getContext().getSelf().path().uid());

        initializeDataManager(context, dataManager);
        initializeState(dataManager);
    }

    protected RaftServer(ActorContext<RaftMessage> context,
                         TimerScheduler<RaftMessage> timers,
                         ServerDataManager dataManager,
                         StateMachine stateMachine,
                         FailFlag failFlag,
                         Object timerKey,
                         int commitIndex,
                         int lastApplied){
        super(context);
        this.timer = timers;
        this.TIMER_KEY = timerKey;
        this.dataManager = dataManager;
        this.stateMachine = stateMachine;
        this.failFlag = failFlag;
        this.commitIndex = commitIndex;
        this.lastApplied = lastApplied;
        this.randomGen = new Random();
        this.randomGen.setSeed(getContext().getSelf().path().uid());

        initializeDataManager(context, dataManager);
        initializeState(dataManager);
    }

    protected void initializeDataManager(ActorContext<RaftMessage> context, ServerDataManager dataManager) {
        dataManager.setActorRefResolver(ActorRefResolver.get(context.getSystem()));
        dataManager.setServerID(context.getSelf().path().uid());
    }

    protected void initializeState(ServerDataManager dataManager) {
        this.currentTerm = this.dataManager.getCurrentTerm();
        this.votedFor = dataManager.getVotedFor();
        if (this.votedFor.equals(getContext().getSystem().deadLetters())){
            this.votedFor = null;
        }
        this.log = dataManager.getLog();
        this.groupRefs = dataManager.getGroupRefs();
    }

    protected void startTimer() {
        int randomNum = randomGen.nextInt(600);
        this.timer.startSingleTimer(TIMER_KEY, new RaftMessage.TimeOut(), Duration.ofMillis(400 + randomNum));
    }

    protected void handleTimeOut() {
        this.currentTerm++;
        this.dataManager.saveCurrentTerm(this.currentTerm);
        this.votedFor = getContext().getSelf();
        this.dataManager.saveVotedFor(this.votedFor);
        sendRequestVotesToAllNodes();
    }

    protected void updateCurrentTerm(int senderTerm) {
        if (senderTerm > this.currentTerm){this.currentTerm = senderTerm;}
        this.dataManager.saveCurrentTerm(this.currentTerm);
    }

    protected void sendAppendEntriesResponse(RaftMessage.AppendEntries msg, boolean success) {
        msg.leaderRef().tell(new RaftMessage.AppendEntriesResponse(getContext().getSelf(),
                                                                    this.currentTerm,
                                                                    success,
                                                          msg.prevLogIndex() + msg.entries().size()));
    }

    protected void sendRequestVoteResponse(RaftMessage.RequestVote msg, boolean success) {
        msg.candidateRef().tell(new RaftMessage.RequestVoteResponse(this.currentTerm, success));
    }

    protected void applyCommittedEntriesToStateMachine(){
        List<Entry> entries = this.log.subList(this.lastApplied + 1, this.commitIndex + 1);
        for (Entry e : entries) this.stateMachine.apply(e.command());
        this.lastApplied = this.commitIndex;
    }

    protected Behavior<RaftMessage> handlePreRestart(PreRestart signal) {
        this.failFlag.failed = true;
        return Behaviors.same();
    }

    protected void resetTransientState(){
        this.stateMachine.resetState();
        this.commitIndex = -1;
        this.lastApplied = -1;
    }

    private void sendRequestVotesToAllNodes() {
        for (ActorRef<RaftMessage> ref: this.groupRefs){
            ref.tell(new RaftMessage.RequestVote(this.currentTerm,
                    getContext().getSelf(),
                    this.log.size()-1,
                    getLastLogTerm()));
        }
    }

    private int getLastLogTerm() {
        if (this.log.size() == 0) return -1;
        else return this.log.get(this.log.size() - 1).term();
    }

    protected boolean isDuplicate(RaftMessage.ClientRequest msg) {
        boolean isDuplicate = false;
        for (Entry e : this.log){
            if (e.command().equals(msg.command())){
                isDuplicate = true;
            }
        }
        return isDuplicate;
    }
}
