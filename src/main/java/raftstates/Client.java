package raftstates;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorRefResolver;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.*;
import messages.ClientMessage;
import messages.RaftMessage;
import statemachine.StringCommand;

import java.time.Duration;
import java.util.List;
import java.util.Random;

public class Client extends AbstractBehavior<ClientMessage> {
    public static Behavior<ClientMessage> create(List<ActorRef<RaftMessage>> serverRefs, List<String> commandQueue){
        return Behaviors.<ClientMessage>supervise(
                Behaviors.setup(context -> Behaviors.withTimers(timers -> new Client(context, timers, serverRefs, commandQueue)))
        ).onFailure(SupervisorStrategy.restart());
    }

    @Override
    public Receive<ClientMessage> createReceive() {
        return newReceiveBuilder()
                .onMessage(ClientMessage.class, this::dispatch)
                .build();
    }

    protected Client(ActorContext<ClientMessage> context,
                        TimerScheduler<ClientMessage> timers,
                        List<ActorRef<RaftMessage>> serverRefs,
                        List<String> commandQueue){
        super(context);
        this.timer = timers;
        this.serverRefs = serverRefs;
        this.commandQueue = commandQueue;
        this.nextRequest = 0;
        this.failMode = false;
        this.requestsSinceFail = 0;
        this.concurrentFails = 0;
        this.requestsPerFailure = 0;
        this.randomGenerator = new Random();
        this.randomGenerator.setSeed(System.currentTimeMillis());
        this.refResolver = ActorRefResolver.get(context.getSystem());

    }

    private List<ActorRef<RaftMessage>> serverRefs;

    private Object TIMER_KEY = new Object();

    protected TimerScheduler<ClientMessage> timer;

    private List<String> commandQueue;

    private int nextRequest;

    private ActorRef<ClientMessage> alertWhenFinished;

    private boolean failMode;
    private int requestsSinceFail;
    private int requestsPerFailure;

    private int concurrentFails;

    private Random randomGenerator;
    private ActorRefResolver refResolver;

    private Behavior<ClientMessage> dispatch(ClientMessage message){
        switch (message) {
            case ClientMessage.Start msg:
                start();
                break;
            case ClientMessage.StartFailMode msg:
                this.failMode = true;
                this.requestsPerFailure = msg.requestsPerFailure();
                this.concurrentFails = msg.concurrentFails();
                start();
                break;
            case ClientMessage.ClientResponse msg:
                handleClientResponse(msg);
                startTimer();
                break;
            case ClientMessage.AlertWhenFinished msg:
                this.alertWhenFinished = msg.sender();
                break;
            case ClientMessage.TimeOut msg:
                this.sendNextRequestToRandomServer();
                startTimer();
                break;
            case ClientMessage.ShutDown msg:
                return Behaviors.stopped();
            default:
                break;
        }
        return this;
    }

    private void start(){
        sendNextRequestToRandomServer();
        startTimer();
    }

    private void sendNextRequestToRandomServer() {
        if (nextRequest >= this.commandQueue.size()) return;
        int randomServer = getRandomServer();
        this.serverRefs.get(randomServer).tell(getRequestMessage(this.nextRequest, this.commandQueue.get(this.nextRequest)));

        if (this.failMode){
            this.requestsSinceFail++;
            if (isTimeToSendFailures()) sendFailures();
        }
    }

    private boolean isTimeToSendFailures() {
        if (this.requestsSinceFail >= this.requestsPerFailure) return true;
        else return false;
    }

    private void sendFailures() {
        for (int i = 0; i < this.concurrentFails; i++) {
            int randServer = getRandomServer();
            this.serverRefs.get(randServer).tell(new RaftMessage.Failure());
        }
        this.requestsSinceFail = 0;
    }

    private void handleClientResponse(ClientMessage.ClientResponse response){
        if (response.success()){
            getContext().getLog().info("CLIENT RECEIVED RESPONSE SUCCESS for " + response.commandID());
            if (response.commandID() < this.nextRequest) return;
            this.nextRequest++;
            if (noMoreRequestsLeft()) {
                getContext().getLog().info("CLIENT RECEIVED RESPONSE FOR ALL REQUESTS - FINISHED");
                this.timer.cancel(this.TIMER_KEY);
                if (this.alertWhenFinished != null) this.alertWhenFinished.tell(new ClientMessage.Finished());
            }
            else {
                getContext().getLog().info("CLIENT SENDING REQUEST " + this.nextRequest);
                sendNextRequestToRandomServer();
            }

        } else{
            getContext().getLog().info("CLIENT RECEIVED RESPONSE FAILED");
            sendNextRequestToRandomServer();
        }
    }

    private boolean noMoreRequestsLeft() {
        return this.nextRequest >= this.commandQueue.size();
    }

    private int getRandomServer() {
       return this.randomGenerator.nextInt(serverRefs.size());
    }

    private void startTimer() {
        this.timer.startSingleTimer(TIMER_KEY, new ClientMessage.TimeOut(), Duration.ofSeconds(1));
    }

    private RaftMessage.ClientRequest getRequestMessage(int commandId, String command){
        return new RaftMessage.ClientRequest(getContext().getSelf(),
                new StringCommand(refResolver.toSerializationFormat(getContext().getSelf()),
                            commandId,
                            command));
    }
}

