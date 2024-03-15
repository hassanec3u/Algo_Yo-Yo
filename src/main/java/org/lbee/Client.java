package org.lbee;

import org.lbee.instrumentation.trace.TLATracer;
import org.lbee.instrumentation.clock.ClockException;
import org.lbee.instrumentation.clock.ClockFactory;
import org.lbee.network.NetworkManager;
import org.lbee.protocol.AgentYoyo;

import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Client manager (transaction manager or resource manager)
 */
public class Client {

    public static void main(String[] args) throws IOException, ClockException {

        if (args.length < 5) {
            System.out.println("Missing arguments. hostname, port, type={tm, rm}, rmName, duration expected.");
            return;
        }

        // Get hostname, port and type of manager
        final String hostname = args[0];
        final int port = Integer.parseInt(args[1]);
        final int duration = Integer.parseInt(args[args.length - 1]);

        // final JsonObject jsonConfig = ConfigurationManager.read("twophase.ndjson.conf");
        // System.out.println(jsonConfig);
        // final Configuration config = new Configuration(jsonConfig);

        try (Socket socket = new Socket(hostname, port)) {
            NetworkManager networkManager = new NetworkManager(socket);
            //  TLATracer spec = TLATracer.getTracer(managerName + ".ndjson",
            //  ClockFactory.getClock(ClockFactory.FILE, "twophase.clock"));

            AgentYoyo agent = new AgentYoyo(networkManager,String.valueOf(1),new HashSet<>(),new HashSet<>());

            agent.run();


            // Send bye to server (kill the server thread)
            // networkManager.sendRaw("bye");
        } catch (UnknownHostException ex) {
            System.out.println("Server not found: " + ex.getMessage());
        } catch (IOException ex) {
            System.out.println("I/O error: " + ex.getMessage());
        }
    }
}