import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorRefResolver;
import messages.ClientMessage;
import messages.OrchMessage;
import messages.RaftMessage;
import org.junit.*;
import statemachine.Command;
import statemachine.CounterCommand;
import statemachine.Entry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class ClientTests {

    ActorRef<RaftMessage> follower;

    static ActorTestKit testKit;

    TestProbe<RaftMessage> probe;

    ActorRef<RaftMessage> probeRef;

    private static CounterCommand createCommand() {
        return new CounterCommand("", 1, 1);
    }

    private List<TestProbe<RaftMessage>> getProbeGroup(int count){
        List<TestProbe<RaftMessage>> group = new ArrayList<>();
        for (int i = 0; i < count; i++){
            TestProbe<RaftMessage> probe = testKit.createTestProbe();
            group.add(probe);
        }
        return group;
    }

    private List<ActorRef<RaftMessage>> getProbeGroupRefs(List<TestProbe<RaftMessage>> probeGroup){
        List<ActorRef<RaftMessage>> groupRefs = new ArrayList<>();
        for (TestProbe<RaftMessage> probe: probeGroup){
            groupRefs.add(probe.ref());
        }
        return groupRefs;
    }
    private static List<Entry> getEntries(int count) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++){
            entries.add(new Entry(1, createCommand()));
        }
        return entries;
    }

    private  List<ActorRef<RaftMessage>> getSingleProbeGroupRefs() {
        List<ActorRef<RaftMessage>> groupRefs = new ArrayList<>();
        groupRefs.add(probeRef);
        return groupRefs;
    }


    @BeforeClass
    public static void classSetUp(){testKit = ActorTestKit.create();}

    @AfterClass
    public static void classTearDown(){
        testKit.shutdownTestKit();
    }

    @Before
    public void setUp(){
        probe = testKit.createTestProbe();
        probeRef = probe.ref();
    }

    @After
    public void tearDown(){}

    @Test
    public void clientSendsFirstMessageInRequestQueueToPrefferedServer(){
        List<ActorRef<RaftMessage>> serverRefs = getSingleProbeGroupRefs();
        List<RaftMessage> requests = new ArrayList<>();
        requests.add(new RaftMessage.ClientUnstableReadRequest(null));
        ActorRef<ClientMessage> client = testKit.spawn(TicketClient.create(serverRefs, probeRef));
        client.tell(new ClientMessage.SetRequestQueue(requests));
        client.tell(new ClientMessage.Start());
        probe.expectMessage(new RaftMessage.ClientUnstableReadRequest(null));
    }

    @Test
    public void clientReceivesRequestResponseAndSendsNextRequest(){
        List<ActorRef<RaftMessage>> serverRefs = getSingleProbeGroupRefs();
        List<RaftMessage> requests = new ArrayList<>();
        requests.add(new RaftMessage.ClientUnstableReadRequest(null));
        requests.add(new RaftMessage.ClientCommittedReadRequest(null));
        ActorRef<ClientMessage> client = testKit.spawn(TicketClient.create(serverRefs, probeRef));
        client.tell(new ClientMessage.SetRequestQueue(requests));
        client.tell(new ClientMessage.Start());
        probe.expectMessage(new RaftMessage.ClientUnstableReadRequest(null));
        client.tell(new ClientMessage.ClientReadResponse<>(1));
        probe.expectMessage(new RaftMessage.ClientCommittedReadRequest(null));
    }

    @Test
    public void clientReceivesSuccessfulUpdateResponseAndSendsNextRequest(){
        List<ActorRef<RaftMessage>> serverRefs = getSingleProbeGroupRefs();
        List<RaftMessage> requests = new ArrayList<>();
        Command command = new CounterCommand("", 1, 1);
        requests.add(new RaftMessage.ClientUpdateRequest(null, command));
        requests.add(new RaftMessage.ClientCommittedReadRequest(null));
        ActorRef<ClientMessage> client = testKit.spawn(TicketClient.create(serverRefs, probeRef));
        client.tell(new ClientMessage.SetRequestQueue(requests));
        client.tell(new ClientMessage.Start());
        probe.expectMessage(requests.get(0));
        client.tell(new ClientMessage.ClientUpdateResponse(true, 1));
        probe.expectMessage(requests.get(1));
    }

    @Test
    public void clientNotifiesCorrectActorWhenHaveReceivedResponsesForAllRequestsAndStops(){
        TestProbe<OrchMessage> orchestrator = testKit.createTestProbe();
        List<ActorRef<RaftMessage>> serverRefs = getSingleProbeGroupRefs();
        List<RaftMessage> requests = new ArrayList<>();
        requests.add(new RaftMessage.ClientCommittedReadRequest(null));
        ActorRef<ClientMessage> client = testKit.spawn(TicketClient.create(serverRefs, probeRef));
        client.tell(new ClientMessage.AlertWhenFinished(orchestrator.ref()));
        client.tell(new ClientMessage.SetRequestQueue(requests));
        client.tell(new ClientMessage.Start());
        probe.expectMessage(requests.get(0));
        client.tell(new ClientMessage.ClientReadResponse( 1));
        orchestrator.expectMessage(new OrchMessage.ClientTerminated());
    }

    @Test
    public void clientNotifiesCorrectActorWhenItReceivesFailedUpdateAndStops(){
        TestProbe<OrchMessage> orchestrator = testKit.createTestProbe();
        List<ActorRef<RaftMessage>> serverRefs = getSingleProbeGroupRefs();
        List<RaftMessage> requests = new ArrayList<>();
        requests.add(new RaftMessage.ClientUpdateRequest(null, createCommand()));
        ActorRef<ClientMessage> client = testKit.spawn(TicketClient.create(serverRefs, probeRef));
        client.tell(new ClientMessage.AlertWhenFinished(orchestrator.ref()));
        client.tell(new ClientMessage.SetRequestQueue(requests));
        client.tell(new ClientMessage.Start());
        probe.expectMessage(requests.get(0));
        client.tell(new ClientMessage.ClientUpdateResponse( false, 1));
        orchestrator.expectMessage(new OrchMessage.ClientTerminated());
    }

    @Test
    public void clientNotifiesCorrectActorWhenGets0TicketsResponseAndStops(){
        TestProbe<OrchMessage> orchestrator = testKit.createTestProbe();
        List<ActorRef<RaftMessage>> serverRefs = getSingleProbeGroupRefs();
        List<RaftMessage> requests = new ArrayList<>();
        requests.add(new RaftMessage.ClientUnstableReadRequest(null));
        ActorRef<ClientMessage> client = testKit.spawn(TicketClient.create(serverRefs, probeRef));
        client.tell(new ClientMessage.AlertWhenFinished(orchestrator.ref()));
        client.tell(new ClientMessage.SetRequestQueue(requests));
        client.tell(new ClientMessage.Start());
        probe.expectMessage(requests.get(0));
        client.tell(new ClientMessage.ClientReadResponse<>(0));
        orchestrator.expectMessage(new OrchMessage.ClientTerminated());
    }

    @Test
    public void clientTimesOutResendsRequestToADifferentServer(){
        List<TestProbe<RaftMessage>> probeGroup = getProbeGroup(2);
        List<ActorRef<RaftMessage>> serverRefs = getProbeGroupRefs(probeGroup);
        List<RaftMessage> requests = new ArrayList<>();
        requests.add(new RaftMessage.ClientUnstableReadRequest(null));
        ActorRef<ClientMessage> client = testKit.spawn(TicketClient.create(serverRefs, serverRefs.get(0)));
        client.tell(new ClientMessage.SetRequestQueue(requests));
        client.tell(new ClientMessage.Start());
        probeGroup.get(0).expectMessage(new RaftMessage.ClientUnstableReadRequest(null));
        probeGroup.get(1).expectMessage(new RaftMessage.ClientUnstableReadRequest(null));
    }

    @Test
    public void clientTimesOutResendsRequestToADifferentServerAndContinuesToResendOnTimeOuts(){
        List<TestProbe<RaftMessage>> probeGroup = getProbeGroup(3);
        List<ActorRef<RaftMessage>> serverRefs = getProbeGroupRefs(probeGroup);
        List<RaftMessage> requests = new ArrayList<>();
        requests.add(new RaftMessage.ClientUnstableReadRequest(null));
        ActorRef<ClientMessage> client = testKit.spawn(TicketClient.create(serverRefs, serverRefs.get(0)));
        client.tell(new ClientMessage.SetRequestQueue(requests));
        client.tell(new ClientMessage.Start());
        probeGroup.get(0).expectMessage(new RaftMessage.ClientUnstableReadRequest(null));
        probeGroup.get(1).expectMessage(new RaftMessage.ClientUnstableReadRequest(null));
        probeGroup.get(2).expectMessage(new RaftMessage.ClientUnstableReadRequest(null));
        probeGroup.get(1).expectMessage(new RaftMessage.ClientUnstableReadRequest(null));
    }

    @Test
    public void clientRequestTimesOutUsesNonPreferredServerThenGoesBackToPreferredAfterPreferredTimerGoesOff(){
        List<TestProbe<RaftMessage>> probeGroup = getProbeGroup(3);
        List<ActorRef<RaftMessage>> serverRefs = getProbeGroupRefs(probeGroup);
        List<RaftMessage> requests = new ArrayList<>();
        requests.add(new RaftMessage.ClientUnstableReadRequest(null));
        ActorRef<ClientMessage> client = testKit.spawn(TicketClient.create(serverRefs, serverRefs.get(0)));
        client.tell(new ClientMessage.SetRequestQueue(requests));
        client.tell(new ClientMessage.Start());
        probeGroup.get(0).expectMessage(new RaftMessage.ClientUnstableReadRequest(null));
        probeGroup.get(1).expectMessage(new RaftMessage.ClientUnstableReadRequest(null));
        probeGroup.get(0).expectMessage(new RaftMessage.ClientUnstableReadRequest(null));
    }
}
