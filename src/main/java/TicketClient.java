import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorRefResolver;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.*;
import messages.ClientMessage;
import messages.OrchMessage;
import messages.RaftMessage;

import java.time.Duration;
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
        this.isPreferredServerActive = true;
        this.isRetryingPreferredServer = false;
        this.nextServerIndex = 0;
        this.requestQueue = new ArrayList<>();
        this.nextRequest = 0;
        this.randomGenerator = new Random();
        this.randomGenerator.setSeed(System.currentTimeMillis());
        this.refResolver = ActorRefResolver.get(context.getSystem());
    }

    private final int requestTimeOutDuration = 500;
    private final int preferredRetryTimeOutDuration = 2000;

    private List<ActorRef<RaftMessage>> serverRefs;

    private ActorRef<RaftMessage> preferredServer;

    private int nextServerIndex;

    private boolean isPreferredServerActive;

    private boolean isRetryingPreferredServer;

    private Object REQUEST_TIMER_KEY = new Object();

    private Object PREFERRED_RETRY_TIMER_KEY = new Object();

    protected TimerScheduler<ClientMessage> timer;

    private List<RaftMessage> requestQueue;

    private int nextRequest;

    private ActorRef<OrchMessage> alertWhenFinished;

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
            case ClientMessage.ClientCommittedReadResponse msg:
                handleReadResponse(msg);
                break;
            case ClientMessage.ClientUnstableReadResponse msg:
                handleReadResponse(msg);
                break;
            case ClientMessage.ClientUpdateResponse msg:
                handleUpdateResponse(msg);
                break;
            case ClientMessage.AlertWhenFinished msg:
                this.alertWhenFinished = msg.sender();
                break;
            case ClientMessage.TimeOut msg:
                handleTimeOut();
                break;
            case ClientMessage.PreferredRetryTimout msg:
                isPreferredServerActive = true;
                isRetryingPreferredServer = true;
                break;
            case ClientMessage.ShutDown msg:
                return Behaviors.stopped();
            default:
                break;
        }
        return this;
    }

    private void start(){
        sendNextRequest();
        startRequestTimer();
    }

    private void handleReadResponse(ClientMessage.ClientUnstableReadResponse<Integer> msg) {
        if (msg.state() <= 0) {
            getContext().getLog().info("CLIENT RECEIVED RESPONSE. NO TICKETS LEFT. TERMINATING");
            shutdown();
        }
        else{
            getContext().getLog().info("CLIENT RECEIVED UNSTABLE READ RESPONSE. TICKETS: " + msg.state());
            this.nextRequest++;
            sendNextRequest();
        }
    }

    private void handleReadResponse(ClientMessage.ClientCommittedReadResponse<Integer> msg) {
        if (msg.state() <= 0) {
            getContext().getLog().info("CLIENT RECEIVED RESPONSE. NO TICKETS LEFT. TERMINATING");
            shutdown();
        }
        else{
            getContext().getLog().info("CLIENT RECEIVED STABLE READ RESPONSE. TICKETS: " + msg.state());
            this.nextRequest++;
            sendNextRequest();
        }
    }

    private void sendNextRequest() {
        if (allRequestsAlreadySent()) shutdown();
        else if (this.isPreferredServerActive) {
            getContext().getLog().info("CLIENT: SENDING REQUEST " + this.requestQueue.get(this.nextRequest).toString());
            this.preferredServer.tell(this.requestQueue.get(this.nextRequest));
        }
        else {
            getContext().getLog().info("CLIENT: SENDING REQUEST " + this.requestQueue.get(this.nextRequest).toString());
            sendRequestToNonPreferredServer();
        }
    }

    private boolean allRequestsAlreadySent() {
        return this.nextRequest >= this.requestQueue.size();
    }

    private void handleUpdateResponse(ClientMessage.ClientUpdateResponse msg) {
        if (msg.success()) {
            this.nextRequest++;
            sendNextRequest();
        }
        else {
            getContext().getLog().info("CLIENT RECEIVED UPDATE RESPONSE. FAILED. TERMINATING");
            shutdown();
        }
    }

    private void handleTimeOut() {
        if (isPreferredServerUnresponsive()) {
            isPreferredServerActive = false;
            startPreferredServerRetryTimer();
        } else this.nextServerIndex++;
        sendNextRequest();
        isRetryingPreferredServer = false;
        startRequestTimer();
    }

    private boolean isPreferredServerUnresponsive() {
        return isPreferredServerActive && !isRetryingPreferredServer;
    }

    private void shutdown() {
        getContext().getSelf().tell(new ClientMessage.ShutDown(null));
    }

    private void sendRequestToNonPreferredServer() {
        if (serverRefs.size() <= 1) return; // no other servers wait for other timer to run out and retry preferred server
        if (nextServerIndex >= serverRefs.size()) nextServerIndex = 0;
        if (serverRefs.get(nextServerIndex) == preferredServer) nextServerIndex++;
        this.serverRefs.get(nextServerIndex).tell(this.requestQueue.get(this.nextRequest));
    }

    private void startRequestTimer() {
        this.timer.startSingleTimer(REQUEST_TIMER_KEY, new ClientMessage.TimeOut(), Duration.ofMillis(requestTimeOutDuration));
    }

    private void startPreferredServerRetryTimer(){
        this.timer.startSingleTimer(PREFERRED_RETRY_TIMER_KEY, new ClientMessage.PreferredRetryTimout(), Duration.ofMillis(preferredRetryTimeOutDuration));
    }

}
