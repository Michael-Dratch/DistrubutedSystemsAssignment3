import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorRefResolver;
import datapersistence.ServerFileWriter;
import messages.ClientMessage;
import messages.RaftMessage;
import org.junit.*;
import raftstates.FailFlag;
import raftstates.Follower;
import statemachine.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SingleServerTests {

    ActorRef<RaftMessage> server;

    static ActorTestKit testKit;

    TestProbe<RaftMessage> probe;

    ActorRef<RaftMessage> probeRef;

    static TestProbe<ClientMessage> clientProbe;

    static ActorRefResolver refResolver;

    private void clearDataDirectory(){
        File dataDir = new File("./data/");
        File[] contents = dataDir.listFiles();
        if (contents != null) {
            for (File file : contents) {
                deleteDirectory(file);
            }
        }
    }

    private void deleteDirectory(File directory){
        File[] contents = directory.listFiles();
        if (contents != null){
            for (File file : contents){
                deleteDirectory(file);
            }
        }
        directory.delete();
    }

    private static CounterCommand createCommand() {
        return new CounterCommand("", 1, 1);
    }

    @BeforeClass
    public static void classSetUp(){
        testKit = ActorTestKit.create();
        clientProbe = testKit.createTestProbe();
        refResolver = ActorRefResolver.get(testKit.system());
    }

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
    public void tearDown(){
        clearDataDirectory();
    }


    @Test
    public void counterStateMachineReturnsToCorrectInitialValueOnFailure(){
        server = testKit.spawn(Follower.create(new ServerFileWriter(), new TicketCounter(5), new FailFlag()));
        TestProbe<RaftMessage> leader = testKit.createTestProbe();
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(1, createCommand()));
        server.tell(new RaftMessage.AppendEntries(1, leader.ref(), -1, -1,  entries, 1));
        server.tell(new RaftMessage.TestMessage.GetStateMachineState(probeRef));
        probe.expectMessage(new RaftMessage.TestMessage.GetStateMachineStateResponse<Integer>(4));
        server.tell(new RaftMessage.Failure());
        server.tell(new RaftMessage.TestMessage.GetStateMachineState(probeRef));
        probe.expectMessage(new RaftMessage.TestMessage.GetStateMachineStateResponse<Integer>(5));
    }

    @Test
    public void followerReceivesStableReadForwardsRequestToLeader(){
        TestProbe<ClientMessage> client = testKit.createTestProbe();
        server = testKit.spawn(Follower.create(new ServerFileWriter(), new TicketCounter(0), new FailFlag()));
        server.tell(new RaftMessage.AppendEntries(0, probeRef, -1,-1, new ArrayList<>(), -1));
        server.tell(new RaftMessage.ClientCommittedReadRequest(client.ref()));
        probe.receiveMessage();
        probe.expectMessage(new RaftMessage.ClientCommittedReadRequest(client.ref()));
    }

    @Test
    public void followerReceivesUpdateForwardsRequestToLeader(){
        TestProbe<ClientMessage> client = testKit.createTestProbe();
        server = testKit.spawn(Follower.create(new ServerFileWriter(), new TicketCounter(0), new FailFlag()));
        server.tell(new RaftMessage.AppendEntries(0, probeRef, -1,-1, new ArrayList<>(), -1));
        RaftMessage.ClientUpdateRequest request = new RaftMessage.ClientUpdateRequest(client.ref(), createCommand());

        server.tell(request);
        probe.receiveMessage();
        probe.expectMessage(request);
    }

    @Test
    public void followerReceivesUnstableReadRespondsToClientWithState(){
        TestProbe<ClientMessage> client = testKit.createTestProbe();
        server = testKit.spawn(Follower.create(new ServerFileWriter(), new TicketCounter(0), new FailFlag()));
        server.tell(new RaftMessage.ClientUnstableReadRequest(client.ref()));
        client.expectMessage(new ClientMessage.ClientReadResponse<Integer>(0));
    }

    @Test
    public void followerRespondsToUnstableReadWithUncommittedEntries(){
        TestProbe<ClientMessage> client = testKit.createTestProbe();
        server = testKit.spawn(Follower.create(new ServerFileWriter(), new TicketCounter(0), new FailFlag()));
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(1, createCommand()));
        server.tell(new RaftMessage.AppendEntries(1, probeRef, -1,-1, entries, -1));
        server.tell(new RaftMessage.ClientUnstableReadRequest(client.ref()));
        client.expectMessage(new ClientMessage.ClientReadResponse<Integer>(-1));
    }
}
