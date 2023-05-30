import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorRefResolver;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import datapersistence.ServerFileWriter;
import messages.ClientMessage;
import messages.OrchMessage;
import messages.RaftMessage;
import raftstates.FailFlag;
import raftstates.Follower;
import statemachine.CounterCommand;
import statemachine.TicketCounter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class Orchestrator extends AbstractBehavior<OrchMessage> {
    public static Behavior<OrchMessage> create() {
        return Behaviors.setup(context -> new Orchestrator(context));
    }

    private Orchestrator(ActorContext ctxt) {
        super(ctxt);
        clientsTerminated = 0;
        serversTerminated = 0;
        refResolver = ActorRefResolver.get(getContext().getSystem());
    }

    private final int numServers = 5;
    private final int numClients = 5;

    private int numTicketRequestsPerClient = 25;

    private int initialCounterState = 100;
    private List<ActorRef<RaftMessage>> serverRefs;
    private List<ActorRef<ClientMessage>> clientRefs;
    int clientsTerminated;
    int serversTerminated;

    ActorRefResolver refResolver;


    @Override
    public Receive<OrchMessage> createReceive() {
        return newReceiveBuilder()
                .onMessage(OrchMessage.class, this::dispatch)
                .build();
    }


    public Behavior<OrchMessage> dispatch(OrchMessage msg) {
        switch (msg) {
            case OrchMessage.Start start:
                handleStart(start);
                break;
            case OrchMessage.ShutDown shutDown:
                getContext().getLog().info("[Orchestrator] TERMINATING HOSTS");
                handleShutdown();
                break;
            case OrchMessage.ClientTerminated terminated:
                handleClientTerminated();
                break;
            case OrchMessage.ServerTerminated terminated:
                handleServerTerminated();
                break;
            case OrchMessage.ShutDownComplete complete:
                getContext().getLog().info("[Orchestrator] SHUTTING DOWN ");
                return Behaviors.stopped();
            default:
                break;
        }
        return this;
    }

    private void handleServerTerminated() {
        this.serversTerminated++;
        if (areAllHostsTerminated()){
            getContext().getSelf().tell(new OrchMessage.ShutDownComplete());
        }
    }

    private void handleClientTerminated() {
        this.clientsTerminated++;
       if (clientsTerminated == this.clientRefs.size()) getContext().getLog().info("ALL CLIENTS TERMINATED");
        if (areAllHostsTerminated()){
            getContext().getSelf().tell(new OrchMessage.ShutDownComplete());
        }
    }

    private boolean areAllHostsTerminated() {
        return this.serversTerminated == this.serverRefs.size() && this.clientsTerminated == this.clientRefs.size();
    }


    private void handleStart(OrchMessage.Start start) {
        getContext().getLog().info("[Orchestrator] spawning servers ");
        this.serverRefs = createServers(numServers);
        sendGroupRefsToServers(this.serverRefs);
        getContext().getLog().info("[Orchestrator] spawning clients ");
        this.clientRefs = createClients(numClients, this.serverRefs);
        sendRequestQueueToClients(this.clientRefs);
        notifyAllClients(new ClientMessage.AlertWhenFinished(getContext().getSelf()));
        getContext().getLog().info("[Orchestrator] starting servers");
        notifyAllServers(new RaftMessage.Start());
        getContext().getLog().info("[Orchestrator] starting clients");
        notifyAllClients(new ClientMessage.Start());
    }

    private void sendRequestQueueToClients(List<ActorRef<ClientMessage>> clientRefs) {
        for (ActorRef<ClientMessage> client: clientRefs){
            List<RaftMessage> requestQueue = getRequestList(client);
            client.tell(new ClientMessage.SetRequestQueue(requestQueue));
        }
    }

    private void sendRequestQueueToClientsWithFailures(List<ActorRef<ClientMessage>> clientRefs) {
        for (int i = 0; i < 4; i++){
            List<RaftMessage> requestQueue = getRequestList(clientRefs.get(i));
            clientRefs.get(i).tell(new ClientMessage.SetRequestQueue(requestQueue));
        }
        List<RaftMessage> requestQueueWithFailures = getRequestListWithFailures(clientRefs.get(4));
        clientRefs.get(4).tell(new ClientMessage.SetRequestQueue(requestQueueWithFailures));
    }


    private ArrayList<ActorRef<RaftMessage>> createServers(int serverCount) {
        ArrayList<ActorRef<RaftMessage>> serverRefs = new ArrayList<>();
        for (int count = 0; count < serverCount; count++){
            var serverRef = this.getContext().spawn(Follower.create(new ServerFileWriter(),
                                                                            new TicketCounter(initialCounterState),
                                                                            new FailFlag()),
                                                                            "SERVER_" + count);
            serverRefs.add(serverRef);
            this.getContext().watchWith(serverRef, new OrchMessage.ServerTerminated());
        }
        return serverRefs;
    }

    private List<ActorRef<ClientMessage>> createClients(int clientCount, List<ActorRef<RaftMessage>> serverRefs) {
        List<ActorRef<ClientMessage>> clientRefs = new ArrayList<>();
        for (int count = 0; count < clientCount; count++){
            List<ActorRef<RaftMessage>> shuffledServerRefs = getShuffledServerRefs(serverRefs, count);
            var clientRef = this.getContext().spawn(TicketClient.create(shuffledServerRefs,
                                                                                serverRefs.get(count)),
                                                                                "CLIENT_" + count);
            clientRefs.add(clientRef);
            this.getContext().watchWith(clientRef, new OrchMessage.ClientTerminated());
        }
        return clientRefs;
    }

    private static List<ActorRef<RaftMessage>> getShuffledServerRefs(List<ActorRef<RaftMessage>> serverRefs, int randSeed) {
        List<ActorRef<RaftMessage>> shuffledServerRefs = new ArrayList<>(serverRefs);
        Collections.shuffle(shuffledServerRefs, new Random(randSeed));
        return shuffledServerRefs;
    }

    private List<RaftMessage> getRequestList(ActorRef<ClientMessage> clientRef) {
        List<RaftMessage> requests = new ArrayList<>();
        for (int i = 0; i < this.numTicketRequestsPerClient; i++){
            addUpdateRequests(clientRef, requests, i,10);
            requests.add(new RaftMessage.ClientUnstableReadRequest(clientRef));
            requests.add(new RaftMessage.ClientCommittedReadRequest(clientRef));
        }
        return requests;
    }

    private List<RaftMessage> getRequestListWithFailures(ActorRef<ClientMessage> clientRef) {
        List<RaftMessage> requests = new ArrayList<>();
        for (int i = 0; i < this.numTicketRequestsPerClient; i++){
            addUpdateRequests(clientRef, requests, i,10);
            if (i % 8 == 0) requests.add(new RaftMessage.Failure());
            requests.add(new RaftMessage.ClientUnstableReadRequest(clientRef));
            requests.add(new RaftMessage.ClientCommittedReadRequest(clientRef));
        }
        return requests;
    }

    private void addUpdateRequests(ActorRef<ClientMessage> clientRef, List<RaftMessage> requests, int id, int count) {
        for (int i = 0; i < count; i++){
            requests.add(new RaftMessage.ClientUpdateRequest(clientRef, new CounterCommand(refResolver.toSerializationFormat(clientRef), id*count + i, 1)));
        }
    }

    private void handleShutdown(){
        notifyAllServers(new RaftMessage.ShutDown(getContext().getSelf()));
        notifyAllClients(new ClientMessage.ShutDown(getContext().getSelf()));
    }


    private void notifyAllClients(ClientMessage msg){
        for (ActorRef<ClientMessage> client : this.clientRefs){
            client.tell(msg);
        }
    }

    private void notifyAllServers(RaftMessage msg){
        for (ActorRef<RaftMessage> server : this.serverRefs){
            server.tell(msg);
        }
    }

    private static void sendGroupRefsToServers(List<ActorRef<RaftMessage>> groupRefs) {
        for (ActorRef<RaftMessage> server : groupRefs){
            List<ActorRef<RaftMessage>> serverRemoved = new ArrayList<>(groupRefs);
            serverRemoved.remove(server);
            server.tell(new RaftMessage.SetGroupRefs(serverRemoved));
        }
    }
}