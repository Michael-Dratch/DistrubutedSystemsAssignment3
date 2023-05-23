import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import datapersistence.ServerFileWriter;
import messages.ClientMessage;
import messages.OrchMessage;
import messages.RaftMessage;
import raftstates.Client;
import raftstates.FailFlag;
import raftstates.Follower;
import statemachine.CommandList;

import java.util.ArrayList;
import java.util.List;

public class Orchestrator extends AbstractBehavior<OrchMessage> {
    public static Behavior<OrchMessage> create() {
        return Behaviors.setup(context -> new Orchestrator(context));
    }

    private Orchestrator(ActorContext ctxt) {
        super(ctxt);
        clientsTerminated = 0;
        serversTerminated = 0;
    }

    private List<ActorRef<RaftMessage>> serverRefs;
    private List<ActorRef<ClientMessage>> clientRefs;
    int clientsTerminated;
    int serversTerminated;

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
        if (areAllHostsTerminated()){
            getContext().getSelf().tell(new OrchMessage.ShutDownComplete());
        }
    }

    private boolean areAllHostsTerminated() {
        return this.serversTerminated == this.serverRefs.size() && this.clientsTerminated == this.clientRefs.size();
    }


    private void handleStart(OrchMessage.Start start) {
        getContext().getLog().info("[Orchestrator] spawning servers ");
        this.serverRefs = createServers(start.serverCount());
        getContext().getLog().info("[Orchestrator] spawning clients ");
        sendGroupRefsToServers(this.serverRefs);
        this.clientRefs = createClients(start.clientCount(), this.serverRefs, start.numClientRequests());
        getContext().getLog().info("[Orchestrator] starting servers");
        notifyAllServers(new RaftMessage.Start());
        getContext().getLog().info("[Orchestrator] starting clients");
        if (start.concurrentFailures() < 1) notifyAllClients(new ClientMessage.Start());
        else startClientsWithFailMessages(start);
    }

    private void startClientsWithFailMessages(OrchMessage.Start start) {
        this.clientRefs.get(0).tell(new ClientMessage.StartFailMode(5, start.concurrentFailures()));
        for (int i = 1; i < this.clientRefs.size(); i++){
            this.clientRefs.get(i).tell(new ClientMessage.Start());
        }
    }

    private ArrayList<ActorRef<RaftMessage>> createServers(int serverCount) {
        ArrayList<ActorRef<RaftMessage>> serverRefs = new ArrayList<>();
        for (int count = 0; count < serverCount; count++){
            var serverRef = this.getContext().spawn(Follower.create(new ServerFileWriter(), new CommandList(), new FailFlag()), "SERVER_" + count);
            serverRefs.add(serverRef);
            this.getContext().watchWith(serverRef, new OrchMessage.ServerTerminated());
        }
        return serverRefs;
    }

    private ArrayList<ActorRef<ClientMessage>> createClients(int clientCount, List<ActorRef<RaftMessage>> serverRefs, int numRequests) {
        List<String> commands = getCommandList(numRequests);

        ArrayList<ActorRef<ClientMessage>> clientRefs = new ArrayList<>();
        for (int count = 0; count < clientCount; count++){
            var clientRef = this.getContext().spawn(Client.create(serverRefs, commands),  "CLIENT_" + count);
            clientRefs.add(clientRef);
            this.getContext().watchWith(clientRef, new OrchMessage.ClientTerminated());
        }
        return clientRefs;
    }

    private static List<String> getCommandList(int count) {
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < count; i++){
            commands.add("COMMAND_" + i );
        }
        return commands;
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