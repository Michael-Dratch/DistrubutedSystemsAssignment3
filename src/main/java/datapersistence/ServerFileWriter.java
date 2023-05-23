package datapersistence;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorRefResolver;
import messages.RaftMessage;
import statemachine.Entry;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
public class ServerFileWriter implements ServerDataManager {

    @Override
    public void saveLog(List<Entry> log) {
        try {
            ObjectOutputStream oos = createObjectOutputStream(getLogFile());
            oos.writeObject(log);
            oos.flush();
            oos.close();
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public void saveCurrentTerm(int term) {
        try {
            ObjectOutputStream ois = createObjectOutputStream(getCurrentTermFile());
            ois.writeObject(term);
            ois.flush();
            ois.close();
        } catch(IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void saveVotedFor(ActorRef<RaftMessage> actorRef) {
        try {
            VotedForWrapper votedFor = getVotedForWrapper(actorRef);
            ObjectOutputStream ois = createObjectOutputStream(getVotedForFile());
            ois.writeObject(votedFor);
            ois.flush();
            ois.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void saveGroupRefs(List<ActorRef<RaftMessage>> groupRefs){
        try {
            List<String> serializableRefs = getSerializableRefs(groupRefs);
            ObjectOutputStream ois = createObjectOutputStream(getGroupRefFile());
            ois.writeObject(serializableRefs);
            ois.flush();
            ois.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }



    @Override
    public List<Entry> getLog() {
        try {
            ObjectInputStream ois = createObjectInputStream(getLogFile());
            List<Entry> log = (ArrayList<Entry>) ois.readObject();
            ois.close();
            return log;
        }catch(IOException e){
            throw new RuntimeException(e);
        }catch(ClassNotFoundException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getCurrentTerm() {
        try {
            ObjectInputStream ois = createObjectInputStream(getCurrentTermFile());
            int term = (int) ois.readObject();
            ois.close();
            return term;
        }catch(IOException e){
            throw new RuntimeException(e);
        }catch(ClassNotFoundException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public ActorRef<RaftMessage> getVotedFor() {
        try {
            ObjectInputStream ois = createObjectInputStream(getVotedForFile());
            VotedForWrapper votedForWrapper = (VotedForWrapper) ois.readObject();
            ActorRef<RaftMessage> votedFor = this.refResolver.resolveActorRef(votedForWrapper.votedFor);
            ois.close();
            return votedFor;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<ActorRef<RaftMessage>> getGroupRefs() {
        try {
            ObjectInputStream ois = createObjectInputStream(getGroupRefFile());
            List<String> serializableRefs = (List<String>) ois.readObject();
            ois.close();
            List<ActorRef<RaftMessage>> groupRefs = new ArrayList<>();
            for (String refString: serializableRefs){
                groupRefs.add(this.refResolver.resolveActorRef(refString));
            }
            return groupRefs;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setServerID(int ID) {
        this.serverUID = ID;
        initializeDataFiles();
    }

    @Override
    public void setActorRefResolver(ActorRefResolver refResolver) {
        this.refResolver = refResolver;
    }

    private int serverUID;

    private ActorRefResolver refResolver;

    private VotedForWrapper getVotedForWrapper(ActorRef<RaftMessage> actorRef) {
        if (actorRef == null) return new VotedForWrapper(null);
        else return new VotedForWrapper(this.refResolver.toSerializationFormat(actorRef));
    }

    private List<String> getSerializableRefs(List<ActorRef<RaftMessage>> groupRefs) {
        List<String> serializableRefs = new ArrayList<>();
        for (ActorRef<RaftMessage> ref : groupRefs){
            serializableRefs.add(this.refResolver.toSerializationFormat(ref));
        }
        return serializableRefs;
    }


    private void initializeDataFiles(){
        File actorDirectory = new File(getActorDirectoryPath());
        File currentTermFile = getCurrentTermFile();
        File logFile = getLogFile();
        File votedForFile = getVotedForFile();
        File groupRefFile = getGroupRefFile();

        try{
            if(!actorDirectory.exists()){
                actorDirectory.mkdirs();
            }
            if (!currentTermFile.exists()){
                currentTermFile.createNewFile();
                saveCurrentTerm(0);
            }
            if (!logFile.exists()) {
                logFile.createNewFile();
                saveLog(new ArrayList<Entry>());
            }
            if (!votedForFile.exists()){
                votedForFile.createNewFile();
                saveVotedFor(null);
            }
            if (!groupRefFile.exists()){
                groupRefFile.createNewFile();
                saveGroupRefs(new ArrayList<>());

            }

        } catch(IOException e){
                throw new RuntimeException(e);
        }
    }


    private static ObjectOutputStream createObjectOutputStream(File logFile) throws IOException {
        FileOutputStream fos = new FileOutputStream(logFile);
        return new ObjectOutputStream(fos);
    }

    private static ObjectInputStream createObjectInputStream(File logFile) throws IOException {
        FileInputStream fis = new FileInputStream(logFile);
        return new ObjectInputStream(fis);
    }

    private String getActorDirectoryPath(){
        String UID = String.valueOf(this.serverUID);
        return "./data/" + UID + "/";
    }

    private File getCurrentTermFile(){
        return new File(getActorDirectoryPath() + "/term.ser");
    }
    private File getLogFile(){
        return new File(getActorDirectoryPath() + "/log.ser");
    }

    private File getVotedForFile(){
        return new File(getActorDirectoryPath() + "/vote.ser");
    }

    private File getGroupRefFile(){
        return new File(getActorDirectoryPath() + "/group.ser");
    }

    private record VotedForWrapper(String votedFor) implements Serializable{ }

}
