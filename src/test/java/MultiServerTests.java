import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import datapersistence.ServerFileWriter;
import messages.ClientMessage;
import messages.RaftMessage;
import org.junit.*;
import raftstates.Client;
import raftstates.FailFlag;
import raftstates.Follower;
import statemachine.Command;
import statemachine.CommandList;
import java.time.Duration;
import static org.junit.Assert.assertEquals;


import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MultiServerTests {

    static ActorTestKit testKit;


    private List<ActorRef<RaftMessage>> getGroupRefs(int count){
        List<ActorRef<RaftMessage>> groupRefs = new ArrayList<>();
        for (int i = 0; i < count; i++){
            TestProbe<RaftMessage> probe = testKit.createTestProbe();
            groupRefs.add(probe.ref());
        }
        return groupRefs;
    }

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

    private static List<String> getCommandList(int count) {
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < count; i++){
            commands.add("COMMAND_" + i );
        }
        return commands;
    }

    private static List<ActorRef<RaftMessage>> createServerGroup(int serverCount) {
        List<ActorRef<RaftMessage>> groupRefs = new ArrayList<>();

        for (int i = 0; i < serverCount; i++){
            ActorRef<RaftMessage> server = testKit.spawn(Follower.create(new ServerFileWriter(), new CommandList(), new FailFlag()));
            groupRefs.add(server);
        }
        return groupRefs;
    }

    private static void sendGroupRefsToServers(List<ActorRef<RaftMessage>> groupRefs) {
        for (ActorRef<RaftMessage> server : groupRefs){
            List<ActorRef<RaftMessage>> serverRemoved = new ArrayList<>(groupRefs);
            serverRemoved.remove(server);
            server.tell(new RaftMessage.SetGroupRefs(serverRemoved));
        }
    }

    private static void startServerGroup(List<ActorRef<RaftMessage>> groupRefs) {
        for (ActorRef<RaftMessage> server: groupRefs){
            server.tell(new RaftMessage.Start());
        }
    }

    private TestProbe<ClientMessage> probe;

    private static void sendShutDownMessages(List<ActorRef<RaftMessage>> groupRefs) {
        for (ActorRef<RaftMessage> server: groupRefs){
            server.tell(new RaftMessage.ShutDown(null));
        }
    }

    private static void assertCorrectOrderOfServerStateMachineCommands(List<RaftMessage> responses) {
        for (RaftMessage r : responses){
            List<Command> serverState = ((RaftMessage.TestMessage.GetStateMachineCommandsResponse) r).commands();
            for (int i = 0; i < serverState.size(); i++){
                assertEquals(i, serverState.get(i).getCommandID());
            }
        }
    }

    private static void sendGetStateMachineMessages(List<ActorRef<RaftMessage>> groupRefs, TestProbe<RaftMessage> probe2) {
        for (ActorRef<RaftMessage> server : groupRefs){
            server.tell(new RaftMessage.TestMessage.GetStateMachineCommands(probe2.ref()));
        }
    }

    @BeforeClass
    public static void classSetUp(){
        testKit = ActorTestKit.create();
    }

    @AfterClass
    public static void classTearDown(){
        testKit.shutdownTestKit();
    }

    @Before
    public void setUp(){
        probe = testKit.createTestProbe();

    }

    @After
    public void tearDown(){
      //clearDataDirectory();
    }

    @Test
    public void oneClientNoFailures4ServersSuccessfullyCommitsAllEntries() {
        List<String> commands = getCommandList(20);
        List<ActorRef<RaftMessage>> groupRefs = createServerGroup(4);
        sendGroupRefsToServers(groupRefs);
        startServerGroup(groupRefs);

        ActorRef<ClientMessage> client = testKit.spawn(Client.create(groupRefs,commands));
        client.tell(new ClientMessage.AlertWhenFinished(probe.ref()));
        client.tell(new ClientMessage.Start());
        probe.expectMessage(new ClientMessage.Finished());

        TestProbe<RaftMessage> probe2 = testKit.createTestProbe();
        sendGetStateMachineMessages(groupRefs, probe2);
        List<RaftMessage> responses = probe2.receiveSeveralMessages(4);
        assertCorrectOrderOfServerStateMachineCommands(responses);
        probe.expectNoMessage();
    }

    @Test
    public void oneClientRandomFailures4ServersSuccessfullyCommitsAllEntries(){
        List<String> commands = getCommandList(20);
        List<ActorRef<RaftMessage>> groupRefs = createServerGroup(4);
        sendGroupRefsToServers(groupRefs);
        startServerGroup(groupRefs);

        ActorRef<ClientMessage> client = testKit.spawn(Client.create(groupRefs,commands));
        client.tell(new ClientMessage.AlertWhenFinished(probe.ref()));
        client.tell(new ClientMessage.StartFailMode(4, 1));
        probe.expectMessage(Duration.ofSeconds(10), new ClientMessage.Finished());

    }

    @Test
    public void TwoClientNoFailures4ServersSuccessfullyCommitsAllEntries() {
        List<String> commands1 = getCommandList(10);
        List<String> commands2 = getCommandList(10);
        List<ActorRef<RaftMessage>> groupRefs = createServerGroup(4);
        sendGroupRefsToServers(groupRefs);
        startServerGroup(groupRefs);

        ActorRef<ClientMessage> client1 = testKit.spawn(Client.create(groupRefs, commands1));
        ActorRef<ClientMessage> client2 = testKit.spawn(Client.create(groupRefs, commands2));
        client1.tell(new ClientMessage.AlertWhenFinished(probe.ref()));
        client2.tell(new ClientMessage.AlertWhenFinished(probe.ref()));
        client1.tell(new ClientMessage.Start());
        client2.tell(new ClientMessage.Start());
        probe.expectMessage(Duration.ofSeconds(5), new ClientMessage.Finished());
        probe.expectMessage(Duration.ofSeconds(5), new ClientMessage.Finished());
    }

    @Test
    public void TwoClientWithFailures5ServersSuccessfullyCommitsAllEntries() {
        List<String> commands1 = getCommandList(10);
        List<String> commands2 = getCommandList(10);
        List<ActorRef<RaftMessage>> groupRefs = createServerGroup(4);
        sendGroupRefsToServers(groupRefs);
        startServerGroup(groupRefs);

        ActorRef<ClientMessage> client1 = testKit.spawn(Client.create(groupRefs, commands1));
        ActorRef<ClientMessage> client2 = testKit.spawn(Client.create(groupRefs, commands2));
        client1.tell(new ClientMessage.AlertWhenFinished(probe.ref()));
        client2.tell(new ClientMessage.AlertWhenFinished(probe.ref()));
        client1.tell(new ClientMessage.StartFailMode(4,1));
        client2.tell(new ClientMessage.Start());
        probe.expectMessage(Duration.ofSeconds(5), new ClientMessage.Finished());
        probe.expectMessage(Duration.ofSeconds(5), new ClientMessage.Finished());
    }

    @Test
    public void oneClientRandomFailures2ServersAtATime5ServersTotalSuccessfullyCommitsAllEntries(){
        List<String> commands = getCommandList(15);
        List<ActorRef<RaftMessage>> groupRefs = createServerGroup(4);
        sendGroupRefsToServers(groupRefs);
        startServerGroup(groupRefs);

        ActorRef<ClientMessage> client = testKit.spawn(Client.create(groupRefs,commands));
        client.tell(new ClientMessage.AlertWhenFinished(probe.ref()));
        client.tell(new ClientMessage.StartFailMode(5, 2));
        probe.expectMessage(Duration.ofSeconds(10), new ClientMessage.Finished());

    }
}



