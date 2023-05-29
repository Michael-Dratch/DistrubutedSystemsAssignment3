import akka.actor.typed.ActorSystem;
import messages.OrchMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class TicketAppDemo {
    public static void main(String[] args) throws IOException {



        var orc = ActorSystem.create(Orchestrator.create(), "TICKET-APP-DEMO");
        var done = false;
        var console = new BufferedReader(new InputStreamReader(System.in));

        orc.tell(new OrchMessage.Start());

        while (!done) {
            var command = console.readLine();
            if (command.length()==0) {
                done = true;
                terminateSystem(orc);
            }
        }
    }

    private static void terminateSystem(ActorSystem<OrchMessage> orc) {
        orc.tell(new OrchMessage.ShutDown());
    }
}
