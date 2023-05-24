import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorRefResolver;
import datapersistence.ServerFileWriter;
import messages.ClientMessage;
import messages.RaftMessage;
import org.junit.*;
import raftstates.Candidate;
import raftstates.FailFlag;
import raftstates.Follower;
import raftstates.Leader;
import statemachine.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ServerUnstableReadsTests {

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
    public void followerRespondsToUnstableReadWithUncommittedEntry(){
        TestProbe<ClientMessage> client = testKit.createTestProbe();
        server = testKit.spawn(Follower.create(new ServerFileWriter(), new TicketCounter(0), new FailFlag()));
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(1, createCommand()));
        server.tell(new RaftMessage.AppendEntries(1, probeRef, -1,-1, entries, -1));
        server.tell(new RaftMessage.ClientUnstableReadRequest(client.ref()));
        client.expectMessage(new ClientMessage.ClientReadResponse<Integer>(-1));
    }

    @Test
    public void followerRespondsToUnstableReadWithUncommittedEntries(){
        TestProbe<ClientMessage> client = testKit.createTestProbe();
        server = testKit.spawn(Follower.create(new ServerFileWriter(), new TicketCounter(10), new FailFlag()));
        List<Entry> entries = getEntries(3);
        server.tell(new RaftMessage.AppendEntries(1, probeRef, -1,-1, entries, 0));
        server.tell(new RaftMessage.ClientUnstableReadRequest(client.ref()));
        client.expectMessage(new ClientMessage.ClientReadResponse<Integer>(7));
    }

    @Test
    public void followerReceivesCommittedReadNoLeaderBuffersRequestAndThenForwardsItAfterDiscoversLeader(){
        TestProbe<ClientMessage> client = testKit.createTestProbe();
        server = testKit.spawn(Follower.create(new ServerFileWriter(), new TicketCounter(10), new FailFlag()));
        server.tell(new RaftMessage.ClientCommittedReadRequest(client.ref()));
        server.tell(new RaftMessage.AppendEntries(1, probeRef, -1,-1, new ArrayList<>(), 0));
        probe.expectMessage(new RaftMessage.ClientCommittedReadRequest(client.ref()));
    }


    @Test
    public void CandidateReceivesStableReadForwardsRequestToSelfAfterWinningElectionAndResponds(){
        TestProbe<ClientMessage> client = testKit.createTestProbe();
        List<TestProbe<RaftMessage>> serverGroup = getProbeGroup(2);
        List<ActorRef<RaftMessage>> groupRefs = getProbeGroupRefs(serverGroup);
        server = testKit.spawn(Candidate.create(new ServerFileWriter(), new TicketCounter(0), new FailFlag(), new Object(), 0, groupRefs, -1, -1));
        server.tell(new RaftMessage.RequestVoteResponse(0, true));
        server.tell(new RaftMessage.ClientCommittedReadRequest(client.ref()));
        client.expectMessage(new ClientMessage.ClientReadResponse<>(0));
    }

    @Test
    public void CandidateReceivesStableReadNoLeaderElectedClientReceivesNoResponse(){
        TestProbe<ClientMessage> client = testKit.createTestProbe();
        List<TestProbe<RaftMessage>> serverGroup = getProbeGroup(2);
        List<ActorRef<RaftMessage>> groupRefs = getProbeGroupRefs(serverGroup);
        server = testKit.spawn(Candidate.create(new ServerFileWriter(), new TicketCounter(0), new FailFlag(), new Object(), 0, groupRefs, -1, -1));
        server.tell(new RaftMessage.ClientCommittedReadRequest(client.ref()));
        client.expectNoMessage();
    }

    @Test
    public void CandidateReceivesUnstableReadRespondsImmediately(){
        TestProbe<ClientMessage> client = testKit.createTestProbe();
        List<TestProbe<RaftMessage>> serverGroup = getProbeGroup(2);
        List<ActorRef<RaftMessage>> groupRefs = getProbeGroupRefs(serverGroup);
        server = testKit.spawn(Candidate.create(new ServerFileWriter(), new TicketCounter(0), new FailFlag(), new Object(), 0, groupRefs, -1, -1));
        server.tell(new RaftMessage.ClientUnstableReadRequest(client.ref()));
        client.expectMessage(new ClientMessage.ClientReadResponse<>(0));
    }

    @Test
    public void CandidateReceivesUnstableReadRespondsWithUncommittedEntries() {
        TestProbe<ClientMessage> client = testKit.createTestProbe();
        List<TestProbe<RaftMessage>> serverGroup = getProbeGroup(2);
        List<ActorRef<RaftMessage>> groupRefs = getProbeGroupRefs(serverGroup);
        server = testKit.spawn(Candidate.create(new ServerFileWriter(), new TicketCounter(0), new FailFlag(), new Object(), 0, groupRefs, -1, -1));
        List<Entry> entries = getEntries(3);
        server.tell(new RaftMessage.TestMessage.SaveEntries(entries));
        server.tell(new RaftMessage.ClientUnstableReadRequest(client.ref()));
        client.expectMessage(new ClientMessage.ClientReadResponse<>(-3));
    }

    @Test
    public void leaderReceivesCommittedReadRequestReturnsCommittedState(){
        TestProbe<ClientMessage> client = testKit.createTestProbe();
        server = testKit.spawn(Leader.create(new ServerFileWriter(), new TicketCounter(0),  new Object(), new FailFlag(), 0, new ArrayList<>(), -1, -1));
        server.tell(new RaftMessage.TestMessage.SaveEntries(getEntries(3)));
        server.tell(new RaftMessage.ClientCommittedReadRequest(client.ref()));
        client.expectMessage(new ClientMessage.ClientReadResponse<>(0));
    }

    @Test
    public void leaderReceivesUnstableReadRequestReturnsTentativeState(){
        TestProbe<ClientMessage> client = testKit.createTestProbe();
        server = testKit.spawn(Leader.create(new ServerFileWriter(), new TicketCounter(0),  new Object(), new FailFlag(), 0, new ArrayList<>(), -1, -1));
        server.tell(new RaftMessage.TestMessage.SaveEntries(getEntries(3)));
        server.tell(new RaftMessage.ClientUnstableReadRequest(client.ref()));
        client.expectMessage(new ClientMessage.ClientReadResponse<>(-3));
    }
}
