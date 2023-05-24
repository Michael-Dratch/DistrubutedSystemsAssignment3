import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorRefResolver;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.*;
import messages.ClientMessage;
import messages.OrchMessage;
import messages.RaftMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TicketClient extends AbstractBehavior<ClientMessage> {
    public static Behavior<ClientMessage> create(List<ActorRef<RaftMessage>> serverRefs, ActorRef<RaftMessage> preferredServer){
        return Behaviors.<ClientMessage>supervise(
                Behaviors.setup(context -> Behaviors.withTimers(timers -> new TicketClient(context, timers, serverRefs, preferredServer)))
        ).onFailure(SupervisorStrategy.restart());
    }

    @Override
    public Receive<ClientMessage> createReceive() {
        return newReceiveBuilder()
                .onMessage(ClientMessage.class, this::dispatch)
                .build();
    }

    protected TicketClient(ActorContext<ClientMessage> context,
                     TimerScheduler<ClientMessage> timers,
                     List<ActorRef<RaftMessage>> serverRefs,
                     ActorRef<RaftMessage> preferredServer){
        super(context);
        this.timer = timers;
        this.serverRefs = serverRefs;
        this.preferredServer = preferredServer;
        this.requestQueue = new ArrayList<>();
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

    private ActorRef<RaftMessage> preferredServer;

    private Object TIMER_KEY = new Object();

    protected TimerScheduler<ClientMessage> timer;

    private List<RaftMessage> requestQueue;

    private int nextRequest;

    private ActorRef<OrchMessage> alertWhenFinished;

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
            case ClientMessage.SetRequestQueue msg:
                this.requestQueue.addAll(msg.requests());
                break;
            case ClientMessage.StartFailMode msg:
                this.failMode = true;
                this.requestsPerFailure = msg.requestsPerFailure();
                this.concurrentFails = msg.concurrentFails();
                start();
                break;
            case ClientMessage.ClientReadResponse msg:
                handleReadResponse(msg);
                break;
            case ClientMessage.ClientUpdateResponse msg:
                handleUpdateResponse(msg);
                //startTimer();
                break;
            case ClientMessage.AlertWhenFinished msg:
                this.alertWhenFinished = msg.sender();
                break;
            case ClientMessage.TimeOut msg:
                //this.sendNextRequestToRandomServer();
                //startTimer();
                break;
            case ClientMessage.ShutDown msg:
                return Behaviors.stopped();
            default:
                break;
        }
        return this;
    }

    private void handleUpdateResponse(ClientMessage.ClientUpdateResponse msg) {
        if (msg.success()) {
            this.nextRequest++;
            sendNextRequestToPreferredServer();
        }
        else{
            System.out.println("Update Failed");
            shutdown();
        }
    }

    private void handleReadResponse(ClientMessage.ClientReadResponse<Integer> msg) {
        if (msg.state() <= 0) {
            System.out.println("No Tickets Left Client Terminating");
            shutdown();
        }
        else{
            this.nextRequest++;
            sendNextRequestToPreferredServer();
        }
    }

    private void shutdown() {
        this.alertWhenFinished.tell(new OrchMessage.ClientTerminated());
        getContext().getSelf().tell(new ClientMessage.ShutDown(null));
    }

    private void start(){
        sendNextRequestToPreferredServer();
//        startTimer();
    }

    private void sendNextRequestToPreferredServer() {
        if (this.nextRequest >= this.requestQueue.size()) {
            System.out.println("All Requests Sent");
            shutdown();
        }
        else this.preferredServer.tell(this.requestQueue.get(this.nextRequest));
    }

}
