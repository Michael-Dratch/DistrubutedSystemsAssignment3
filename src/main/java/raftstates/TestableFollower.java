package raftstates;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.TimerScheduler;
import datapersistence.ServerFileWriter;
import messages.RaftMessage;
import statemachine.CommandList;
import statemachine.Entry;

import java.util.List;

public class TestableFollower extends Follower {


    public static Behavior<RaftMessage> create() {
        return Behaviors.<RaftMessage>supervise(
                Behaviors.setup(context -> {
                    return Behaviors.withTimers(timers -> {
                        return new TestableFollower(context, timers);
                    });
                })).onFailure(SupervisorStrategy.restart());
    }

    private TestableFollower(ActorContext<RaftMessage> context, TimerScheduler<RaftMessage> timers) {
        super(context, timers, new ServerFileWriter(), new CommandList(), new FailFlag());
    }

    public static Behavior<RaftMessage> create(int currentTerm, List<Entry> log) {
        return Behaviors.<RaftMessage>supervise(
            Behaviors.setup(context -> {
                return Behaviors.withTimers(timers -> {
                    return new TestableFollower(context, timers, currentTerm, log);
                });
            })).onFailure(SupervisorStrategy.restart());
    }

   //  constructor without restart supervision strategy for debugging
//        public static Behavior<messages.RaftMessage> create(int currentTerm, List<statemachine.Entry> log) {
//        return Behaviors.setup(context -> {
//                return Behaviors.withTimers(timers -> {
//                    return new raftstates.TestableFollower(context, timers, currentTerm, log);
//                });
//            });
//
//    }

    private TestableFollower(ActorContext<RaftMessage> context, TimerScheduler<RaftMessage> timers, int currentTerm, List<Entry> log) {
        super(context, timers, new ServerFileWriter(), new CommandList(), new FailFlag());
        this.timer = timers;
        this.log = log;
        this.currentTerm = currentTerm;
        this.dataManager.saveCurrentTerm(this.currentTerm);
    }

    public static Behavior<RaftMessage> create(int currentTerm, List<Entry> log, int commitIndex) {
        return Behaviors.<RaftMessage>supervise(
            Behaviors.setup(context -> {
                return Behaviors.withTimers(timers -> {
                    return new TestableFollower(context, timers, currentTerm, log, commitIndex);
                });
            })).onFailure(SupervisorStrategy.restart());
    }

    private TestableFollower(ActorContext<RaftMessage> context, TimerScheduler<RaftMessage> timers, int currentTerm, List<Entry> log, int commitIndex) {
        super(context, timers, new ServerFileWriter(), new CommandList(), new FailFlag());
        this.currentTerm = currentTerm;
        this.dataManager.saveCurrentTerm(this.currentTerm);
        this.commitIndex = commitIndex;
        this.log = log;
        this.dataManager.saveLog(log);
    }

    public static Behavior<RaftMessage> create(int currentTerm, ActorRef<RaftMessage> votedFor, List<Entry> log, int commitIndex, int lastApplied) {
        return Behaviors.<RaftMessage>supervise(
            Behaviors.setup(context -> {
                return Behaviors.withTimers(timers -> {
                    return new TestableFollower(context, timers, currentTerm, votedFor, log, commitIndex, lastApplied);
                });
        })).onFailure(SupervisorStrategy.restart());
    }


    private TestableFollower(ActorContext<RaftMessage> context,
                             TimerScheduler<RaftMessage> timers,
                             int currentTerm,
                             ActorRef<RaftMessage> votedFor,
                             List<Entry> log,
                             int commitIndex,
                             int lastApplied) {

        super(context, timers, new ServerFileWriter(), new CommandList(), new FailFlag());
        this.timer = timers;
        this.commitIndex = commitIndex;
        this.lastApplied = lastApplied;
        this.log = log;
        this.dataManager.saveLog(this.log);
        this.currentTerm = currentTerm;
        this.dataManager.saveCurrentTerm(this.currentTerm);
        this.votedFor = votedFor;
        this.dataManager.saveVotedFor(votedFor);
    }

    protected void handleTestMessage(RaftMessage.TestMessage message) {
        switch (message) {
            case RaftMessage.TestMessage.GetLog msg:
                msg.sender().tell(new RaftMessage.TestMessage.GetLogResponse(this.log));
                break;
            case RaftMessage.TestMessage.GetCommitIndex msg:
                msg.sender().tell(new RaftMessage.TestMessage.GetCommitIndexResponse(this.commitIndex));
                break;
            case RaftMessage.TestMessage.SaveEntries msg:
                this.dataManager.saveLog(msg.entries());
                break;
            case RaftMessage.TestMessage.GetState msg:
                msg.sender().tell(new RaftMessage.TestMessage.GetStateResponse(this.currentTerm,
                                                                                this.votedFor,
                                                                                this.log,
                                                                                this.commitIndex,
                                                                                this.lastApplied));
                break;
            case RaftMessage.TestMessage.testFail msg:
                throw new RuntimeException("Test Failure");
            default:
                break;
        }
    }
}