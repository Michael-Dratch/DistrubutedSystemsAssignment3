import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import datapersistence.ServerFileWriter;
import messages.RaftMessage;
import org.junit.*;
import raftstates.Candidate;
import raftstates.FailFlag;
import statemachine.CommandList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CandidateTests     {

    ActorRef<RaftMessage> candidate;

    static ActorTestKit testKit;

    TestProbe<RaftMessage> probe;

    ActorRef<RaftMessage> probeRef;

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
        probeRef = probe.ref();
    }
    @After
    public void tearDown(){
        clearDataDirectory();
    }

    @Test
    public void candidateReceivesNoVotesSendsOutAnotherRoundOfVoteRequestsWithIncrementedTerm(){
        TestProbe<RaftMessage> probe2 = testKit.createTestProbe();
        List<ActorRef<RaftMessage>> groupRefs = new ArrayList<>();
        groupRefs.add(probeRef);
        groupRefs.add(probe2.ref());
        candidate = testKit.spawn(Candidate.create(new ServerFileWriter(), new CommandList(), new FailFlag(), new Object(),1, groupRefs, -1, -1));
        probe.expectMessage(new RaftMessage.RequestVote(2, candidate, -1, -1));
        probe2.expectMessage(new RaftMessage.RequestVote(2, candidate, -1, -1));
    }

    @Test
    public void candidateGetsRequestVoteFromOtherNodeSameTermSendsResponseWithFalse(){
        candidate = testKit.spawn(Candidate.create(new ServerFileWriter(), new CommandList(), new FailFlag(), new Object(), 1, new ArrayList<>(), -1, -1));
        candidate.tell(new RaftMessage.RequestVote(1, probeRef, -1, -1));
        probe.expectMessage(new RaftMessage.RequestVoteResponse(1, false));
    }

    @Test
    public void candidateGetsRequestVoteFromLaterTermImmediatlyChangesTofollower(){
        candidate = testKit.spawn(Candidate.create(new ServerFileWriter(), new CommandList(),new FailFlag(), new Object(),1, new ArrayList<>(), -1, -1));
        candidate.tell(new RaftMessage.RequestVote(2, probeRef, -1, -1));
        candidate.tell(new RaftMessage.TestMessage.GetBehavior(probeRef));
        probe.expectMessage(new RaftMessage.TestMessage.GetBehaviorResponse("FOLLOWER"));
    }

    @Test
    public void candidateReceivesOldAppendEntriesReplyFalse(){
        candidate = testKit.spawn(Candidate.create(new ServerFileWriter(), new CommandList(),new FailFlag(), new Object(),1, new ArrayList<>(), -1, -1));
        candidate.tell(new RaftMessage.AppendEntries(0, probeRef, -1,-1, new ArrayList<>(), -1));
        probe.expectMessage(new RaftMessage.AppendEntriesResponse(candidate, 1, false, -1));
    }

    @Test
    public void candidateReceivesAppendEntriesSameTermConvertsToFollower(){
        candidate = testKit.spawn(Candidate.create(new ServerFileWriter(), new CommandList(),new FailFlag(), new Object(),1, new ArrayList<>(), -1, -1));
        candidate.tell(new RaftMessage.AppendEntries(1, probeRef, -1,-1, new ArrayList<>(), -1));
        candidate.tell(new RaftMessage.TestMessage.GetBehavior(probeRef));
        probe.expectMessage(new RaftMessage.TestMessage.GetBehaviorResponse("FOLLOWER"));
    }

    @Test
    public void candidateReceivesLessThanMajorityVotesStaysCandidate(){
        List<ActorRef<RaftMessage>> groupRefs = new ArrayList<>();
        groupRefs.add(probeRef);
        groupRefs.add(probeRef);
        groupRefs.add(probeRef);
        groupRefs.add(probeRef);
        candidate = testKit.spawn(Candidate.create(new ServerFileWriter(), new CommandList(),new FailFlag(), new Object(),1, groupRefs, -1, -1));
        candidate.tell(new RaftMessage.RequestVoteResponse(1, true));
        candidate.tell(new RaftMessage.RequestVoteResponse(1, false));
        candidate.tell(new RaftMessage.RequestVoteResponse(1, false));
        candidate.tell(new RaftMessage.RequestVoteResponse(1, false));
        candidate.tell(new RaftMessage.TestMessage.GetBehavior(probeRef));
        probe.expectMessage(new RaftMessage.TestMessage.GetBehaviorResponse("CANDIDATE"));
    }

    @Test
    public void receivesRequestVoteResponseFromLaterTermImmediatelyBecomesFollower(){
        candidate = testKit.spawn(Candidate.create(new ServerFileWriter(), new CommandList(), new FailFlag(),new Object(),1, new ArrayList<>(), -1, -1));
        candidate.tell(new RaftMessage.RequestVoteResponse(2, false));
        candidate.tell(new RaftMessage.TestMessage.GetBehavior(probeRef));
        probe.expectMessage(new RaftMessage.TestMessage.GetBehaviorResponse("FOLLOWER"));
    }

    @Test
    public void candidateReceivesMajorityVotesBecomesLeader(){
        List<ActorRef<RaftMessage>> groupRefs = new ArrayList<>();
        groupRefs.add(probeRef);
        groupRefs.add(probeRef);
        groupRefs.add(probeRef);
        groupRefs.add(probeRef);
        candidate = testKit.spawn(Candidate.create(new ServerFileWriter(), new CommandList(),new FailFlag(), new Object(),1, groupRefs, -1, -1));
        candidate.tell(new RaftMessage.RequestVoteResponse(1, true));
        candidate.tell(new RaftMessage.RequestVoteResponse(1, true));
        candidate.tell(new RaftMessage.RequestVoteResponse(1, false));
        candidate.tell(new RaftMessage.RequestVoteResponse(1, false));
        TestProbe<RaftMessage> probe2 = testKit.createTestProbe();
        candidate.tell(new RaftMessage.TestMessage.GetBehavior(probe2.ref()));
        probe2.expectMessage(new RaftMessage.TestMessage.GetBehaviorResponse("LEADER"));
    }



}

